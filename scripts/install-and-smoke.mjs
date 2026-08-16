#!/usr/bin/env node

import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const configPath = join(root, ".codex", "local-context.properties");
const packageName = "com.tcrrry.desktoplyrics";
const activityName = `${packageName}/.MainActivity`;
const occupancyLeaseAction = "com.tcrrry.icar.surface.action.ACQUIRE_OCCUPANCY_LEASE";
const occupancyLeaseServiceName = "SurfaceOccupancyLeaseService";
const defaultApk = join(root, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
const requestedApk = process.argv[2];
const apkPath = requestedApk
  ? (isAbsolute(requestedApk) ? requestedApk : resolve(root, requestedApk))
  : defaultApk;

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

if (!existsSync(configPath)) fail("缺少 .codex/local-context.properties");
if (!existsSync(apkPath)) fail(`找不到 debug APK：${apkPath}`);

const config = parseProperties(readFileSync(configPath, "utf8"));
const adb = config.PRIMARY_ADB;
const serial = config.VEHICLE_ADB_SERIAL;
if (!adb || !existsSync(adb)) fail("PRIMARY_ADB 不存在");
if (!serial) fail("本机配置缺少 VEHICLE_ADB_SERIAL");

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

const state = adbRun(["get-state"], "无法连接目标车机").output;
if (state !== "device") fail(`目标车机状态不是 device：${state}`);

const serviceBefore = adbRun(
  ["shell", "dumpsys", "activity", "services", packageName],
  "无法读取安装前服务状态",
  true
).output.includes("LyricsOverlayService");

const install = adbRun(
  ["install", "--no-streaming", "-r", apkPath],
  "保留数据覆盖安装失败"
);
if (!/\bSuccess\b/.test(install.output)) fail("ADB 未返回安装成功", install.output);

const launch = adbRun(
  ["shell", "am", "start", "-W", "-n", activityName],
  "设置页启动失败"
).output;
if (!/Status:\s*ok/i.test(launch) && !/Activity:\s*com\.tcrrry\.desktoplyrics/i.test(launch)) {
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

const activities = adbRun(
  ["shell", "dumpsys", "activity", "activities"],
  "无法读取前台页面"
).output;
const resumedLine = activities.split(/\r?\n/).find((line) => line.includes("mResumedActivity")) || "";
if (!resumedLine.includes(packageName)) fail("设置页未处于前台", resumedLine);

const processErrors = adbRun(
  ["logcat", "-d", `--pid=${pid}`, "-v", "brief", "-t", "300", "*:E"],
  "无法读取应用错误日志",
  true
);
if (processErrors.status !== 0) fail("当前 ADB 不支持按应用进程读取日志", processErrors.output);
const fatalPattern = /FATAL EXCEPTION|AndroidRuntime|Process:\s*com\.tcrrry\.desktoplyrics|WebView .*\bERROR\b/;
if (fatalPattern.test(processErrors.output)) fail("应用进程存在致命错误", processErrors.output);

const serviceAfter = adbRun(
  ["shell", "dumpsys", "activity", "services", packageName],
  "无法读取安装后服务状态",
  true
).output.includes("LyricsOverlayService");
if (!serviceAfter) fail("设置页未自动恢复歌词服务");

console.log("车机安装与基础 smoke 通过。");
console.log(`- 设备：${serial}`);
console.log(`- 已安装：${packageName} ${versionName} (${versionCode})`);
console.log(`- 进程：PID ${pid}`);
console.log("- 设置页：已启动并位于前台");
console.log("- 表面占用租约：已发现");
console.log("- 致命日志：未发现");
console.log("- 歌词服务恢复：运行中");
