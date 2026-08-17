#!/usr/bin/env node

import {
  existsSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
} from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { delimiter, dirname, isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  hasDeadServiceConnection,
  serviceRecordIsBound,
} from "./lib/android-service-state.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const configPath = join(root, ".codex", "local-context.properties");
const formalPackage = "com.tcrrry.desktoplyrics";
const testPackage = "com.tcrrry.desktoplyrics.test";
const instrumentationComponent =
  `${testPackage}/androidx.test.runner.AndroidJUnitRunner`;
const instrumentationClass =
  "com.tcrrry.desktoplyrics.CommercialSecurityInstrumentationTest";
const cleanupMethod = "cleanupContractLeavesNoTestArtifacts";
const formalRecordPrefix = "commercial_secure_v1_";
const testRecordPrefix = "commercial_test_secure_v1_";
const dockAccessibilityService = `${formalPackage}/${formalPackage}.IcarDockAccessibilityService`;
const dockAccessibilityServiceShort = `${formalPackage}/.IcarDockAccessibilityService`;
const defaultAppApk = join(root, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
const defaultTestApk = join(
  root,
  "app",
  "build",
  "outputs",
  "apk",
  "androidTest",
  "debug",
  "app-debug-androidTest.apk"
);

class StepFailure extends Error {
  constructor(message, output = "") {
    super(message);
    this.output = output.trim();
  }
}

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

function parseArguments(argv) {
  const options = {
    appApk: defaultAppApk,
    testApk: defaultTestApk,
    allowReplaceTestPackage: false,
    userApprovedPersistentVehicle: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--allow-replace-test-package") {
      options.allowReplaceTestPackage = true;
      continue;
    }
    if (argument === "--user-approved-persistent-vehicle") {
      options.userApprovedPersistentVehicle = true;
      continue;
    }
    if (argument === "--app-apk" || argument === "--test-apk") {
      const value = argv[index + 1];
      if (!value) throw new StepFailure(`${argument} 缺少 APK 路径`);
      const resolvedPath = isAbsolute(value) ? value : resolve(root, value);
      if (argument === "--app-apk") options.appApk = resolvedPath;
      else options.testApk = resolvedPath;
      index += 1;
      continue;
    }
    throw new StepFailure(`未知参数：${argument}`);
  }
  return options;
}

function runCommand(executable, args, label, allowFailure = false) {
  const result = spawnSync(executable, args, {
    cwd: root,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
  const output = `${result.stdout || ""}${result.stderr || ""}`.trim();
  if (result.error) throw new StepFailure(`${label}：${result.error.message}`);
  if (result.status !== 0 && !allowFailure) throw new StepFailure(label, output);
  return { status: result.status, output };
}

function findApkSigner(androidSdkRoot) {
  const buildToolsDir = join(androidSdkRoot, "build-tools");
  if (!existsSync(buildToolsDir)) throw new StepFailure("Android SDK 缺少 build-tools");
  const versions = readdirSync(buildToolsDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort((left, right) => right.localeCompare(left, undefined, { numeric: true }));
  for (const version of versions) {
    const candidate = join(buildToolsDir, version, "apksigner");
    if (existsSync(candidate)) return candidate;
  }
  throw new StepFailure("Android SDK build-tools 中找不到 apksigner");
}

function verifyApk(apkSigner, apkPath, label) {
  const result = runCommand(
    apkSigner,
    ["verify", "--verbose", "--print-certs", apkPath],
    `${label} 签名验证失败`
  );
  const signerCount = Number(result.output.match(/Number of signers:\s*(\d+)/)?.[1]);
  const v2Verified = /Verified using v2 scheme[^:]*:\s*true/i.test(result.output);
  const certificateDigests = [...result.output.matchAll(
    /Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f]+)/gi
  )].map((match) => match[1].toLowerCase());
  if (signerCount !== 1 || certificateDigests.length !== 1) {
    throw new StepFailure(`${label} 必须且只能包含一个签名者`);
  }
  if (!v2Verified) throw new StepFailure(`${label} 未通过 APK v2 签名验证`);
  return certificateDigests[0];
}

function sameCertificate(expected, actual, label) {
  if (expected !== actual) throw new StepFailure(`${label} 与正式 APK 签名证书不一致`);
}

function printFailure(error) {
  console.error(`- ${error.message}`);
  if (error.output) console.error(error.output);
}

let options;
let adb;
let serial;
let apkSigner;
let temporaryDirectory;
try {
  options = parseArguments(process.argv.slice(2));
  if (!existsSync(configPath)) throw new StepFailure("缺少 .codex/local-context.properties");
  if (!existsSync(options.appApk)) {
    throw new StepFailure(`找不到正式 Debug APK：${options.appApk}`);
  }
  if (!existsSync(options.testApk)) {
    throw new StepFailure(`找不到 androidTest APK：${options.testApk}`);
  }

  const config = parseProperties(readFileSync(configPath, "utf8"));
  adb = config.PRIMARY_ADB;
  serial = config.COMMERCIAL_TEST_ADB_SERIAL;
  const targetRole = config.COMMERCIAL_TEST_DEVICE_ROLE;
  const persistentVehicleSerial = config.VEHICLE_ADB_SERIAL;
  const androidSdkRoot = config.ANDROID_SDK_ROOT;
  const jdk17Home = config.JDK17_HOME;
  if (!adb || !existsSync(adb)) throw new StepFailure("PRIMARY_ADB 不存在");
  if (!serial || !targetRole) {
    throw new StepFailure(
      "商业 instrumentation 不属于普通车机收尾；请显式配置 COMMERCIAL_TEST_ADB_SERIAL 和 COMMERCIAL_TEST_DEVICE_ROLE"
    );
  }
  const targetsPersistentVehicle = Boolean(
    persistentVehicleSerial && serial === persistentVehicleSerial
  );
  if (targetRole === "dedicated-test-device") {
    if (targetsPersistentVehicle) {
      throw new StepFailure(
        "专用商业测试设备不得复用 VEHICLE_ADB_SERIAL 指向的用户车机"
      );
    }
  } else if (targetRole === "persistent-user-vehicle") {
    if (!targetsPersistentVehicle) {
      throw new StepFailure(
        "persistent-user-vehicle 必须与 VEHICLE_ADB_SERIAL 指向同一设备"
      );
    }
    if (!options.userApprovedPersistentVehicle) {
      throw new StepFailure(
        "用户车机默认禁止商业 instrumentation；仅在用户当次明确授权后传入 --user-approved-persistent-vehicle"
      );
    }
  } else {
    throw new StepFailure(
      "COMMERCIAL_TEST_DEVICE_ROLE 仅允许 dedicated-test-device 或 persistent-user-vehicle"
    );
  }
  if (!androidSdkRoot || !existsSync(androidSdkRoot)) {
    throw new StepFailure("ANDROID_SDK_ROOT 不存在");
  }
  if (!jdk17Home || !existsSync(jdk17Home)) throw new StepFailure("JDK17_HOME 不存在");

  process.env.JAVA_HOME = jdk17Home;
  process.env.PATH = `${join(jdk17Home, "bin")}${delimiter}${process.env.PATH || ""}`;
  apkSigner = findApkSigner(androidSdkRoot);
  temporaryDirectory = mkdtempSync(join(tmpdir(), "03lyrics-commercial-security-"));
} catch (error) {
  console.error("商业安全事务测试预检失败。");
  printFailure(error);
  process.exit(1);
}

function adbRun(args, label, allowFailure = false) {
  return runCommand(adb, ["-s", serial, ...args], label, allowFailure);
}

function packagePaths(packageName) {
  const result = adbRun(
    ["shell", "pm", "path", packageName],
    `无法查询 ${packageName} 安装路径`,
    true
  );
  if (result.status !== 0) return [];
  return result.output
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.startsWith("package:"))
    .map((line) => line.slice("package:".length));
}

function pullInstalledApk(packageName, destinationName) {
  const paths = packagePaths(packageName);
  const baseApk = paths.find((path) => path.endsWith("/base.apk")) || paths[0];
  if (!baseApk) throw new StepFailure(`车机未安装 ${packageName}`);
  const destination = join(temporaryDirectory, destinationName);
  adbRun(["pull", baseApk, destination], `无法读取车机已安装的 ${packageName} APK`);
  return destination;
}

function installApk(apkPath, label) {
  const result = adbRun(
    ["install", "--no-streaming", "-r", apkPath],
    `${label} 保留数据覆盖安装失败`
  );
  if (!/\bSuccess\b/.test(result.output)) {
    throw new StepFailure(`${label} 安装未返回 Success`, result.output);
  }
}

function wait(milliseconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

function parseAccessibilityServices(value) {
  const normalized = value.trim();
  if (!normalized || normalized === "null") return [];
  return normalized.split(":").map((item) => item.trim()).filter(Boolean);
}

function isDockAccessibilityService(component) {
  return component === dockAccessibilityService || component === dockAccessibilityServiceShort;
}

function enabledAccessibilityServices() {
  return parseAccessibilityServices(
    adbRun(
      ["shell", "settings", "get", "secure", "enabled_accessibility_services"],
      "无法读取已启用无障碍服务"
    ).output
  );
}

function activityServiceDump() {
  return adbRun(
    ["shell", "dumpsys", "activity", "services", formalPackage],
    "无法读取正式应用服务状态",
    true
  );
}

function waitForDockAccessibilityBound() {
  let latestDump = "";
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const result = activityServiceDump();
    latestDump = result.output;
    if (result.status === 0 &&
        serviceRecordIsBound(latestDump, dockAccessibilityServiceShort)) {
      return latestDump;
    }
    wait(500);
  }
  return latestDump;
}

function waitForDockAccessibilityAbsent() {
  let latestDump = "";
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const result = activityServiceDump();
    latestDump = result.output;
    if (result.status === 0 && !latestDump.includes(dockAccessibilityServiceShort)) {
      return latestDump;
    }
    wait(500);
  }
  return latestDump;
}

function suspendDockAccessibilityService() {
  const before = enabledAccessibilityServices();
  if (!before.some(isDockAccessibilityService)) {
    const dump = activityServiceDump().output;
    if (dump.includes(dockAccessibilityServiceShort)) {
      throw new StepFailure(
        "歌词窗口避让服务未授权但仍残留系统连接，已中止 instrumentation",
        dump
      );
    }
    return false;
  }

  const boundDump = waitForDockAccessibilityBound();
  if (!serviceRecordIsBound(boundDump, dockAccessibilityServiceShort)) {
    const detail = hasDeadServiceConnection(boundDump, dockAccessibilityServiceShort)
      ? "系统存在 DEAD 连接"
      : "系统不存在目标服务自己的有效绑定记录";
    throw new StepFailure(`商业测试前无法安全暂停歌词窗口避让服务：${detail}`, boundDump);
  }

  const preserved = before.filter((component) => !isDockAccessibilityService(component));
  adbRun(
    ["shell", "settings", "put", "secure", "enabled_accessibility_services", preserved.join(":")],
    "无法暂停歌词窗口避让无障碍服务"
  );

  const unboundDump = waitForDockAccessibilityAbsent();
  const after = enabledAccessibilityServices();
  const removedServices = preserved.filter((component) => !after.includes(component));
  if (after.some(isDockAccessibilityService) || removedServices.length > 0) {
    throw new StepFailure("暂停歌词窗口避让服务时改动了非目标授权");
  }
  if (unboundDump.includes(dockAccessibilityServiceShort)) {
    throw new StepFailure(
      "歌词窗口避让服务未完成干净解绑，已中止 instrumentation",
      unboundDump
    );
  }
  return true;
}

function assertDockAccessibilityTransactionPrecondition() {
  const enabled = enabledAccessibilityServices().some(isDockAccessibilityService);
  const initialDump = activityServiceDump().output;
  if (hasDeadServiceConnection(initialDump, dockAccessibilityServiceShort)) {
    throw new StepFailure(
      "商业测试前置检查失败：歌词窗口避让服务存在 DEAD 连接",
      initialDump
    );
  }
  if (!enabled) {
    if (initialDump.includes(dockAccessibilityServiceShort)) {
      throw new StepFailure(
        "商业测试前置检查失败：未授权的歌词窗口避让服务仍有系统连接",
        initialDump
      );
    }
    return;
  }

  const boundDump = serviceRecordIsBound(initialDump, dockAccessibilityServiceShort)
    ? initialDump
    : waitForDockAccessibilityBound();
  if (!serviceRecordIsBound(boundDump, dockAccessibilityServiceShort)) {
    throw new StepFailure(
      "商业测试前置检查失败：歌词窗口避让服务未真实绑定",
      boundDump
    );
  }
}

function listNoBackupFiles() {
  return adbRun(
    ["shell", "run-as", formalPackage, "ls", "-1", "no_backup"],
    "无法读取正式应用 noBackup 文件列表"
  ).output
    .split(/\r?\n/)
    .map((value) => value.trim())
    .filter(Boolean);
}

function snapshotFormalRecords() {
  const snapshot = new Map();
  const formalRecords = listNoBackupFiles()
    .filter((name) => name.startsWith(formalRecordPrefix))
    .sort();
  for (const name of formalRecords) {
    const line = adbRun(
      ["shell", "run-as", formalPackage, "sha256sum", `no_backup/${name}`],
      `无法计算正式权益记录哈希：${name}`
    ).output.trim();
    const match = line.match(/^([0-9a-f]{64})\s+(.+)$/i);
    if (!match || !match[2].endsWith(`/no_backup/${name}`) && match[2] !== `no_backup/${name}`) {
      throw new StepFailure("正式权益记录哈希输出格式异常", line);
    }
    snapshot.set(name, match[1].toLowerCase());
  }
  return snapshot;
}

function snapshotsEqual(left, right) {
  const names = [...new Set([...left.keys(), ...right.keys()])];
  return names.every((name) => left.get(name) === right.get(name));
}

function snapshotStableFormalRecords() {
  let previous = snapshotFormalRecords();
  for (let attempt = 0; attempt < 10; attempt += 1) {
    wait(500);
    const current = snapshotFormalRecords();
    if (snapshotsEqual(previous, current)) return current;
    previous = current;
  }
  throw new StepFailure("正式权益记录在测试前未进入稳定状态");
}

function listTestRecords() {
  return listNoBackupFiles()
    .filter((name) => name.startsWith(testRecordPrefix))
    .sort();
}

function assertSnapshotsEqual(before, after) {
  const names = [...new Set([...before.keys(), ...after.keys()])].sort();
  const changes = names.filter((name) => before.get(name) !== after.get(name));
  if (changes.length === 0) return;
  const details = changes.map((name) =>
    `${name}: before=${before.get(name) || "missing"} after=${after.get(name) || "missing"}`
  ).join("\n");
  throw new StepFailure("商业安全测试改动了正式权益记录", details);
}

function instrumentationSucceeded(result) {
  return result.status === 0 &&
    /\bOK \(\d+ tests?\)/.test(result.output) &&
    !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=/i.test(result.output);
}

function runInstrumentation(testSelector, label) {
  const result = adbRun(
    [
      "shell",
      "am",
      "instrument",
      "-w",
      "-r",
      "-e",
      "class",
      testSelector,
      instrumentationComponent,
    ],
    label,
    true
  );
  if (!instrumentationSucceeded(result)) throw new StepFailure(label, result.output);
}

function uninstallTestPackage() {
  if (packagePaths(testPackage).length === 0) return;
  const result = adbRun(["uninstall", testPackage], "无法卸载商业测试辅助包", true);
  if (result.status !== 0 || !/\bSuccess\b/.test(result.output)) {
    throw new StepFailure("商业测试辅助包卸载失败", result.output);
  }
}

function assertTestPackageAbsent() {
  if (packagePaths(testPackage).length > 0) {
    throw new StepFailure("商业测试辅助包仍安装在车机上");
  }
  const registrations = adbRun(
    ["shell", "pm", "list", "instrumentation"],
    "无法复核 instrumentation 注册"
  ).output;
  if (registrations.includes(`${testPackage}/`)) {
    throw new StepFailure("商业测试 instrumentation 注册未清除", registrations);
  }
}

function runFinalSmoke() {
  runCommand(
    process.execPath,
    [
      join(root, "scripts", "install-and-smoke.mjs"),
      options.appApk,
      "--serial",
      serial,
    ],
    "正式应用恢复安装与 smoke 失败"
  );
}

let primaryFailure = null;
const cleanupFailures = [];
let beforeSnapshot = null;
let testPackageTrusted = false;
let testPackageShouldBeRemoved = false;
let dockAccessibilitySuspended = false;
let commercialTestEnvironmentPrepared = false;

try {
  const state = adbRun(["get-state"], "无法连接目标车机").output;
  if (state !== "device") throw new StepFailure(`目标车机状态不是 device：${state}`);

  const newFormalCertificate = verifyApk(apkSigner, options.appApk, "新正式 APK");
  const newTestCertificate = verifyApk(apkSigner, options.testApk, "新 androidTest APK");
  sameCertificate(newFormalCertificate, newTestCertificate, "新 androidTest APK");

  const installedFormalApk = pullInstalledApk(formalPackage, "installed-formal.apk");
  const installedFormalCertificate = verifyApk(
    apkSigner,
    installedFormalApk,
    "车机已安装正式 APK"
  );
  sameCertificate(newFormalCertificate, installedFormalCertificate, "车机已安装正式 APK");
  assertDockAccessibilityTransactionPrecondition();

  const installedTestPaths = packagePaths(testPackage);
  let replaceExistingTestPackage = false;
  if (installedTestPaths.length > 0) {
    const installedTestApk = pullInstalledApk(testPackage, "installed-test.apk");
    const installedTestCertificate = verifyApk(
      apkSigner,
      installedTestApk,
      "车机已有 androidTest APK"
    );
    if (installedTestCertificate !== newFormalCertificate) {
      if (!options.allowReplaceTestPackage) {
        throw new StepFailure(
          "车机已有测试辅助包签名不同；未传入 --allow-replace-test-package，未执行卸载"
        );
      }
      replaceExistingTestPackage = true;
    } else {
      testPackageTrusted = true;
      testPackageShouldBeRemoved = true;
    }
  }

  try {
    installApk(options.appApk, "正式应用");
    wait(1_000);
    dockAccessibilitySuspended = suspendDockAccessibilityService();
    commercialTestEnvironmentPrepared = true;
    beforeSnapshot = snapshotStableFormalRecords();

    if (replaceExistingTestPackage) {
      testPackageShouldBeRemoved = true;
      uninstallTestPackage();
    }
    testPackageShouldBeRemoved = true;
    installApk(options.testApk, "商业测试辅助包");
    testPackageTrusted = true;
    runInstrumentation(instrumentationClass, "商业安全 instrumentation 失败");
  } catch (error) {
    primaryFailure = error;
  } finally {
    if (commercialTestEnvironmentPrepared && testPackageTrusted &&
        packagePaths(testPackage).length > 0) {
      try {
        runInstrumentation(
          `${instrumentationClass}#${cleanupMethod}`,
          "商业测试专用清场失败"
        );
      } catch (error) {
        cleanupFailures.push(error);
      }
    }

    try {
      if (testPackageShouldBeRemoved) uninstallTestPackage();
      assertTestPackageAbsent();
    } catch (error) {
      cleanupFailures.push(error);
    }

    try {
      const remainingTestRecords = listTestRecords();
      if (remainingTestRecords.length > 0) {
        throw new StepFailure(
          "商业测试记录未清空",
          remainingTestRecords.join("\n")
        );
      }
      if (beforeSnapshot != null) {
        assertSnapshotsEqual(beforeSnapshot, snapshotStableFormalRecords());
      }
    } catch (error) {
      cleanupFailures.push(error);
    }

    try {
      runFinalSmoke();
    } catch (error) {
      cleanupFailures.push(error);
    }
  }
} catch (error) {
  primaryFailure = error;
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true });
}

if (primaryFailure || cleanupFailures.length > 0) {
  console.error("商业安全事务测试失败。");
  if (primaryFailure) {
    console.error("主测试错误：");
    printFailure(primaryFailure);
  }
  if (cleanupFailures.length > 0) {
    console.error("清场或恢复错误：");
    cleanupFailures.forEach(printFailure);
  }
  process.exit(1);
}

console.log("商业安全事务测试通过。");
console.log(`- 设备：${serial}`);
console.log("- APK：正式包、测试包与车机已安装包均为单签名且通过 v2 验证");
console.log("- Instrumentation：商业安全测试与专用清场均通过");
console.log("- 隔离：测试记录、测试辅助包和 instrumentation 注册均为空");
console.log(
  `- 无障碍：${dockAccessibilitySuspended ? "测试前已干净暂停，最终 smoke 已恢复真实绑定" : "测试前未启用，最终 smoke 已完成授权与绑定"}`
);
console.log(
  `- 正式权益：${beforeSnapshot?.size || 0} 个受保护记录的集合与 SHA-256 前后完全一致`
);
console.log("- 正式应用：已保留数据覆盖安装并通过基础 smoke");
