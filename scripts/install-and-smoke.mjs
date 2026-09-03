#!/usr/bin/env node

import { existsSync, readFileSync, readdirSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  hasDeadServiceConnection,
  serviceRecordIsBound,
} from "./lib/android-service-state.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const configPath = join(root, ".codex", "local-context.properties");
const namespacePackageName = "com.ninepointnine.desktoplyrics";
const debugPackageName = `${namespacePackageName}.test`;
const occupancyLeaseAction = "com.tcrrry.icar.surface.action.ACQUIRE_OCCUPANCY_LEASE";
const occupancyLeaseServiceName = "SurfaceOccupancyLeaseService";
const fullDisplayOccupancyLeaseAction =
  "com.tcrrry.icar.surface.action.ACQUIRE_FULL_DISPLAY_OCCUPANCY_LEASE";
const fullDisplayOccupancyLeaseServiceName = "FullDisplayOccupancyLeaseService";
const startupSettleMs = 8_000;
const defaultApk = join(root, "app", "build", "outputs", "apk", "debug", "app-debug.apk");

function parseProperties(content) {
  const values = {};
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator < 1) continue;
    values[line.slice(0, separator).trim()] = line.slice(separator + 1).trim();
  }
  return values;
}

function fail(message, output = "") {
  console.error(`车机安装 smoke 失败：${message}`);
  if (output.trim()) console.error(output.trim());
  process.exit(1);
}

function parseArguments(argv) {
  let apkPath = defaultApk;
  let apkProvided = false;
  let serialOverride = null;
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--serial") {
      const value = argv[index + 1];
      if (!value) fail("--serial 缺少设备地址");
      serialOverride = value;
      index += 1;
      continue;
    }
    if (argument.startsWith("--")) fail(`未知参数：${argument}`);
    if (apkProvided) fail(`存在多余的 APK 路径：${argument}`);
    apkPath = isAbsolute(argument) ? argument : resolve(root, argument);
    apkProvided = true;
  }
  return { apkPath, serialOverride };
}

const options = parseArguments(process.argv.slice(2));
const apkPath = options.apkPath;

if (!existsSync(configPath)) fail("缺少 .codex/local-context.properties");
if (!existsSync(apkPath)) fail(`找不到 debug APK：${apkPath}`);

const config = parseProperties(readFileSync(configPath, "utf8"));
const adb = config.PRIMARY_ADB;
const serial = options.serialOverride || config.VEHICLE_ADB_SERIAL;
const androidSdkRoot = config.ANDROID_SDK_ROOT;
if (!adb || !existsSync(adb)) fail("PRIMARY_ADB 不存在");
if (!serial) fail("未通过 --serial 指定设备，且本机配置缺少 VEHICLE_ADB_SERIAL");
if (!androidSdkRoot || !existsSync(androidSdkRoot)) fail("ANDROID_SDK_ROOT 不存在");

const buildToolsRoot = join(androidSdkRoot, "build-tools");
const aapt = readdirSync(buildToolsRoot)
  .sort((left, right) => right.localeCompare(left, undefined, { numeric: true }))
  .map((version) => join(buildToolsRoot, version, "aapt"))
  .find(existsSync);
if (!aapt) fail("Android Build Tools 中找不到 aapt");

const badging = spawnSync(aapt, ["dump", "badging", apkPath], {
  cwd: root,
  encoding: "utf8",
  maxBuffer: 2 * 1024 * 1024,
});
if (badging.status !== 0) {
  fail("无法读取 debug APK 身份", `${badging.stdout || ""}${badging.stderr || ""}`);
}
const apkIdentity = `${badging.stdout || ""}`.match(
  /package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'/
);
if (!apkIdentity) fail("无法解析 debug APK 包名与版本");
const packageName = apkIdentity[1];
const expectedVersionCode = apkIdentity[2];
const expectedVersionName = apkIdentity[3];
if (packageName !== debugPackageName) {
  fail(`安装脚本只接受 ${debugPackageName}，实际 APK 为 ${packageName}`);
}
const activityName = `${packageName}/${namespacePackageName}.MainActivity`;
const dockAccessibilityService =
  `${packageName}/${namespacePackageName}.IcarDockAccessibilityService`;
const notificationServiceRecord = `${packageName}/${namespacePackageName}.MediaListenerService`;

function adbRun(args, label, allowFailure = false) {
  const result = spawnSync(adb, ["-s", serial, ...args], {
    cwd: root,
    encoding: "utf8",
    maxBuffer: 8 * 1024 * 1024,
  });
  const output = `${result.stdout || ""}${result.stderr || ""}`.trim();
  if (result.status !== 0 && !allowFailure) fail(label, output);
  return { status: result.status, output };
}

function parseAccessibilityServices(value) {
  const normalized = value.trim();
  if (!normalized || normalized === "null") return [];
  return normalized.split(":").map((item) => item.trim()).filter(Boolean);
}

function isDockAccessibilityService(component) {
  return component === dockAccessibilityService;
}

function enableDockAccessibilityService() {
  const before = parseAccessibilityServices(
    adbRun(
      ["shell", "settings", "get", "secure", "enabled_accessibility_services"],
      "无法读取已启用无障碍服务"
    ).output
  );
  const next = before.some(isDockAccessibilityService)
    ? before
    : [...before, dockAccessibilityService];
  if (next.length !== before.length) {
    adbRun(
      ["shell", "settings", "put", "secure", "enabled_accessibility_services", next.join(":")],
      "无法追加歌词窗口避让无障碍服务"
    );
  }

  const accessibilityEnabled = adbRun(
    ["shell", "settings", "get", "secure", "accessibility_enabled"],
    "无法读取无障碍总开关"
  ).output;
  if (accessibilityEnabled !== "1") {
    adbRun(
      ["shell", "settings", "put", "secure", "accessibility_enabled", "1"],
      "无法启用无障碍总开关"
    );
  }

  const after = parseAccessibilityServices(
    adbRun(
      ["shell", "settings", "get", "secure", "enabled_accessibility_services"],
      "无法复核已启用无障碍服务"
    ).output
  );
  const removedServices = before.filter((component) => !after.includes(component));
  if (removedServices.length > 0) {
    fail(`追加窗口避让服务时丢失既有无障碍组件：${removedServices.join(", ")}`);
  }
  if (!after.some(isDockAccessibilityService)) {
    fail("歌词窗口避让无障碍服务未进入系统启用列表");
  }
  return { preservedCount: before.length, added: next.length !== before.length };
}

function serviceDump() {
  return adbRun(
    ["shell", "dumpsys", "activity", "services", packageName],
    "无法读取应用服务绑定状态",
    true
  );
}

function serviceBound(serviceRecord) {
  const dump = serviceDump();
  return dump.status === 0 && serviceRecordIsBound(dump.output, serviceRecord);
}

function waitForServiceBound(serviceRecord) {
  const waitBuffer = new Int32Array(new SharedArrayBuffer(4));
  for (let attempt = 0; attempt < 20; attempt += 1) {
    if (serviceBound(serviceRecord)) return true;
    Atomics.wait(waitBuffer, 0, 0, 500);
  }
  return false;
}

function waitForServiceAbsent(serviceRecord) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const dump = serviceDump();
    if (dump.status === 0 && !dump.output.includes(serviceRecord)) return true;
    wait(500);
  }
  return false;
}

function retriggerDockAccessibilityBinding() {
  const before = parseAccessibilityServices(
    adbRun(
      ["shell", "settings", "get", "secure", "enabled_accessibility_services"],
      "无法读取窗口避让无障碍服务"
    ).output
  );
  if (!before.some(isDockAccessibilityService)) return;

  const preserved = before.filter((component) => !isDockAccessibilityService(component));
  adbRun(
    ["shell", "settings", "put", "secure", "enabled_accessibility_services", preserved.join(":")],
    "无法暂时关闭歌词窗口避让无障碍服务"
  );
  wait(750);

  const disabled = parseAccessibilityServices(
    adbRun(
      ["shell", "settings", "get", "secure", "enabled_accessibility_services"],
      "无法复核歌词窗口避让无障碍服务关闭状态"
    ).output
  );
  if (disabled.some(isDockAccessibilityService)) {
    fail("歌词窗口避让无障碍服务未完成关闭刷新");
  }
  const removedServices = preserved.filter((component) => !disabled.includes(component));
  if (removedServices.length > 0) {
    fail(`刷新窗口避让服务时丢失既有无障碍组件：${removedServices.join(", ")}`);
  }
  if (!waitForServiceAbsent(dockAccessibilityService)) {
    adbRun(
      [
        "shell",
        "settings",
        "put",
        "secure",
        "enabled_accessibility_services",
        [...disabled, dockAccessibilityService].join(":"),
      ],
      "无法恢复歌词窗口避让无障碍授权"
    );
    fail(
      "歌词窗口避让无障碍服务未完成干净解绑；已恢复授权名单并停止继续刷新",
      serviceDump().output
    );
  }

  adbRun(
    [
      "shell",
      "settings",
      "put",
      "secure",
      "enabled_accessibility_services",
      [...disabled, dockAccessibilityService].join(":"),
    ],
    "无法重新启用歌词窗口避让无障碍服务"
  );
}

function wait(milliseconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

const state = adbRun(["get-state"], "无法连接目标车机").output;
if (state !== "device") fail(`目标车机状态不是 device：${state}`);

const install = adbRun(
  ["install", "--no-streaming", "-r", apkPath],
  "保留数据覆盖安装失败"
);
if (!/\bSuccess\b/.test(install.output)) fail("ADB 未返回安装成功", install.output);

const launch = adbRun(
  ["shell", "am", "start", "-W", "-n", activityName],
  "设置页启动失败"
).output;
if (!/Status:\s*ok/i.test(launch) && !launch.includes(`Activity: ${packageName}/`)) {
  fail("设置页未报告成功启动", launch);
}

const pid = adbRun(["shell", "pidof", packageName], "应用进程未运行").output
  .split(/\s+/)[0];
if (!/^\d+$/.test(pid)) fail(`应用 PID 异常：${pid}`);

const packageDump = adbRun(
  ["shell", "dumpsys", "package", packageName],
  "无法读取已安装版本"
).output;
const versionName = packageDump.match(/versionName=([^\s]+)/)?.[1];
const versionCode = packageDump.match(/versionCode=(\d+)/)?.[1];
if (!versionName || !versionCode) fail("无法确认已安装版本", packageDump);
if (versionName !== expectedVersionName || versionCode !== expectedVersionCode) {
  fail(
    `安装版本与 APK 不一致：APK ${expectedVersionName} (${expectedVersionCode})，` +
      `设备 ${versionName} (${versionCode})`
  );
}
if (!packageDump.includes("IcarDockAccessibilityService")) {
  fail("已安装歌词 APK 未声明窗口避让无障碍服务", packageDump);
}
const accessibilityAuthorization = enableDockAccessibilityService();
if (!waitForServiceBound(dockAccessibilityService)) {
  const initialDump = serviceDump().output;
  if (hasDeadServiceConnection(initialDump, dockAccessibilityService)) {
    fail(
      "歌词窗口避让无障碍服务未完成系统绑定：系统仍保留该服务的 DEAD 连接；需要先恢复系统无障碍管理器状态",
      initialDump
    );
  }
  retriggerDockAccessibilityBinding();
  if (!waitForServiceBound(dockAccessibilityService)) {
    const dump = serviceDump().output;
    const detail = hasDeadServiceConnection(dump, dockAccessibilityService)
      ? "系统仍保留该服务的 DEAD 连接；需要先恢复系统无障碍管理器状态"
      : "系统未创建目标服务自己的有效绑定记录";
    fail(`歌词窗口避让无障碍服务未完成系统绑定：${detail}`, dump);
  }
}
if (!waitForServiceBound(notificationServiceRecord)) {
  fail("03歌词播放状态服务未完成系统绑定");
}

const occupancyLeaseProviders = adbRun(
  ["shell", "cmd", "package", "query-services", "--brief", "-a", occupancyLeaseAction],
  "无法查询表面占用租约服务"
).output;
const hasOccupancyLeaseProvider = occupancyLeaseProviders
  .split(/\r?\n/)
  .some((line) => line.includes(packageName) && line.includes(occupancyLeaseServiceName));
if (!hasOccupancyLeaseProvider) {
  fail("已安装歌词 APK 未声明可发现的表面占用租约服务", occupancyLeaseProviders);
}

const fullDisplayOccupancyLeaseProviders = adbRun(
  ["shell", "cmd", "package", "query-services", "--brief", "-a", fullDisplayOccupancyLeaseAction],
  "无法查询全屏独占租约服务"
).output;
const hasFullDisplayOccupancyLeaseProvider = fullDisplayOccupancyLeaseProviders
  .split(/\r?\n/)
  .some((line) =>
    line.includes(packageName) && line.includes(fullDisplayOccupancyLeaseServiceName)
  );
if (!hasFullDisplayOccupancyLeaseProvider) {
  fail(
    "已安装歌词 APK 未声明可发现的全屏独占租约服务",
    fullDisplayOccupancyLeaseProviders
  );
}

const activities = adbRun(
  ["shell", "dumpsys", "activity", "activities"],
  "无法读取前台页面"
).output;
const resumedLine = activities.split(/\r?\n/).find((line) => line.includes("mResumedActivity")) || "";
if (!resumedLine.includes(packageName)) fail("设置页未处于前台", resumedLine);

wait(startupSettleMs);

const processErrors = adbRun(
  ["logcat", "-d", `--pid=${pid}`, "-v", "brief", "-t", "300", "*:E"],
  "无法读取应用错误日志",
  true
);
if (processErrors.status !== 0) fail("当前 ADB 不支持按应用进程读取日志", processErrors.output);
const escapedPackageName = packageName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const fatalPattern = new RegExp(
  `FATAL EXCEPTION|AndroidRuntime|Process:\\s*${escapedPackageName}|WebView .*\\bERROR\\b`
);
if (fatalPattern.test(processErrors.output)) fail("应用进程存在致命错误", processErrors.output);

const serviceAfter = adbRun(
  ["shell", "dumpsys", "activity", "services", packageName],
  "无法读取安装后服务状态",
  true
).output.includes("LyricsOverlayService");
if (!serviceAfter) {
  const activeNotifications = adbRun(
    ["shell", "dumpsys", "notification", "--noredact"],
    "无法读取歌词恢复状态",
    true
  ).output.split("mArchive=Archive")[0];
  if (activeNotifications.includes(`pkg=${packageName}`) &&
      activeNotifications.includes("id=4204")) {
    fail("商业门禁未开放，歌词服务处于权益恢复态");
  }
  fail("设置页未自动恢复歌词服务");
}

console.log("车机安装与基础 smoke 通过。");
console.log(`- 设备：${serial}`);
console.log(`- 已安装：${packageName} ${versionName} (${versionCode})`);
console.log(`- 进程：PID ${pid}`);
console.log("- 设置页：已启动并位于前台");
console.log("- 表面占用租约：已发现");
console.log("- 全屏独占租约：已发现");
console.log(
  `- 窗口避让无障碍：已绑定（保留 ${accessibilityAuthorization.preservedCount} 个既有组件，` +
    `${accessibilityAuthorization.added ? "本次已追加" : "原已启用"}）`
);
console.log("- 播放状态监听：已绑定");
console.log("- 致命日志：未发现");
console.log("- 歌词服务恢复：运行中");
