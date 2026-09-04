#!/usr/bin/env node

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { basename, dirname, isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const configPath = join(root, ".codex", "local-context.properties");
const targetPackages = [
  "com.ninepointnine.desktopcast",
  "com.ninepointnine.desktopcast.test",
];
const diagnosticPackage = "com.ninepointnine.desktoplyrics.diagnostic";
const diagnosticReportsRemoteDirectory = `/sdcard/Android/data/${diagnosticPackage}/files/Download`;
const defaultOutputDirectory = join(root, "build", "diagnostics");
const crashPattern = /FATAL EXCEPTION|AndroidRuntime|Process:|UnsatisfiedLinkError|signal 11|SIGSEGV|signal 6|SIGABRT|Fatal signal|libc|DEBUG|CastService|CastReceiverRuntime|NativeBridge|airplay_native|UxPlay|DLNA|Nsd|SSDP|MediaCodec|Surface|AudioTrack|ConnectivityService|WifiManager/i;

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
  console.error(`03投屏诊断失败：${message}`);
  if (output.trim()) console.error(output.trim());
  process.exit(1);
}

function parseArguments(argv) {
  const options = {
    outputDirectory: defaultOutputDirectory,
    clearLogcat: false,
    launch: true,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--clear-logcat") {
      options.clearLogcat = true;
      continue;
    }
    if (argument === "--no-launch") {
      options.launch = false;
      continue;
    }
    if (argument === "--output") {
      const value = argv[index + 1];
      if (!value) fail("--output 缺少目录");
      options.outputDirectory = isAbsolute(value) ? value : resolve(root, value);
      index += 1;
      continue;
    }
    fail(`未知参数：${argument}`);
  }
  return options;
}

function command(adb, serial, args, label, allowFailure = false) {
  const result = spawnSync(adb, ["-s", serial, ...args], {
    cwd: root,
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
  });
  const output = `${result.stdout || ""}${result.stderr || ""}`.trim();
  if (result.status !== 0 && !allowFailure) fail(label, output);
  return { status: result.status, output };
}

function hostCommand(executable, args) {
  const result = spawnSync(executable, args, {
    cwd: root,
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
  });
  return {
    status: result.status,
    output: `${result.stdout || ""}${result.stderr || ""}`.trim(),
  };
}

function hostBinaryCommand(executable, args) {
  const result = spawnSync(executable, args, {
    cwd: root,
    encoding: null,
    maxBuffer: 64 * 1024 * 1024,
  });
  return {
    status: result.status,
    output: result.stdout || Buffer.alloc(0),
    error: result.stderr || Buffer.alloc(0),
  };
}

function elfHeaderSummary(bytes) {
  const magic = bytes.length >= 4 &&
    bytes[0] === 0x7f && bytes[1] === 0x45 && bytes[2] === 0x4c && bytes[3] === 0x46;
  if (!magic || bytes.length < 20) return { validElf: false };
  const littleEndian = bytes[5] === 1;
  const machine = littleEndian ? bytes.readUInt16LE(18) : bytes.readUInt16BE(18);
  return {
    validElf: true,
    bitness: bytes[4] === 2 ? 64 : bytes[4] === 1 ? 32 : 0,
    endianness: littleEndian ? "little" : bytes[5] === 2 ? "big" : "unknown",
    machine,
    machineAarch64: machine === 183,
  };
}

function wait(milliseconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

function newestBuildTool(sdkRoot, toolName) {
  const buildTools = join(sdkRoot, "build-tools");
  if (!existsSync(buildTools)) return null;
  const versions = requireDirectoryNames(buildTools)
    .sort((left, right) => right.localeCompare(left, undefined, { numeric: true }));
  return versions.map((version) => join(buildTools, version, toolName)).find(existsSync) || null;
}

function requireDirectoryNames(directory) {
  return spawnSync("find", [directory, "-mindepth", "1", "-maxdepth", "1", "-type", "d", "-print"], {
    encoding: "utf8",
  }).stdout.split(/\r?\n/).filter(Boolean).map((path) => basename(path));
}

function extractPackageIdentity(badging) {
  const packageMatch = badging.match(
    /package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'/,
  );
  const sdkMatch = badging.match(/sdkVersion:'([^']+)'/);
  const targetSdkMatch = badging.match(/targetSdkVersion:'([^']+)'/);
  return packageMatch
    ? {
        packageName: packageMatch[1],
        versionCode: packageMatch[2],
        versionName: packageMatch[3],
        minSdk: sdkMatch?.[1] || "",
        targetSdk: targetSdkMatch?.[1] || "",
      }
    : null;
}

function packagePaths(adb, serial, packageName) {
  return command(adb, serial, ["shell", "pm", "path", packageName], `无法读取 ${packageName} 安装路径`, true)
    .output.split(/\r?\n/)
    .map((line) => line.trim().replace(/^package:/, ""))
    .filter(Boolean);
}

function parseLaunchComponent(value, packageName) {
  const line = value.split(/\r?\n/).find((item) => item.includes("/")) || "";
  const component = line.replace(/^priority=\d+ preferredOrder=\d+ match=0x[0-9a-f]+ default=true\s+/i, "");
  if (!component.includes("/")) return `${packageName}/.MainActivity`;
  return component.replace(/^.*?\b(${packageName}\/)/, "$1");
}

function staticPackageEvidence(adb, serial, sdkRoot, packageName) {
  const evidence = {
    packageName,
    installed: false,
    pmPaths: packagePaths(adb, serial, packageName),
  };
  evidence.installed = evidence.pmPaths.length > 0;
  if (!evidence.installed) return evidence;

  const tempDirectory = hostCommand("mktemp", ["-d", join(process.env.TMPDIR || "/tmp", "03cast-diagnostic.XXXXXX")]).output;
  if (!tempDirectory || !existsSync(tempDirectory)) {
    evidence.pull = { succeeded: false, error: "mktemp_failed" };
    return evidence;
  }
  const localApks = [];
  try {
    for (const remotePath of evidence.pmPaths) {
      const localPath = join(tempDirectory, basename(remotePath));
      const pull = command(adb, serial, ["pull", remotePath, localPath], `无法拉取 ${packageName} APK`, true);
      localApks.push({ remotePath, localPath, succeeded: pull.status === 0 && existsSync(localPath) });
    }
    const primary = localApks.find((item) => item.succeeded);
    evidence.pull = {
      succeeded: Boolean(primary),
      apks: localApks.map(({ remotePath, succeeded }) => ({ remotePath, succeeded })),
    };
    if (!primary) return evidence;
    const aapt = newestBuildTool(sdkRoot, "aapt");
    const badging = aapt ? hostCommand(aapt, ["dump", "badging", primary.localPath]) : { status: 1, output: "aapt_missing" };
    evidence.apk = {
      pathName: basename(primary.localPath),
      identity: extractPackageIdentity(badging.output),
      badgingSucceeded: badging.status === 0,
    };
    const listing = hostCommand("unzip", ["-l", primary.localPath]);
    const nativeEntries = listing.output.split(/\r?\n/)
      .filter((line) => /lib\/.*\.so$/.test(line))
      .map((line) => line.trim().split(/\s+/).at(-1))
      .filter(Boolean);
    const expectedArm64Libraries = [
      "lib/arm64-v8a/libairplay_native.so",
      "lib/arm64-v8a/libc++_shared.so",
      "lib/arm64-v8a/libcrypto.so",
      "lib/arm64-v8a/liboboe.so",
    ];
    evidence.native = {
      entries: nativeEntries,
      expectedArm64Libraries,
      missingExpectedArm64Libraries: expectedArm64Libraries.filter((item) => !nativeEntries.includes(item)),
      arm64Complete: expectedArm64Libraries.every((item) => nativeEntries.includes(item)),
      deviceSupportsArm64: false,
    };
    const readelf = process.env.ELF_READELF || "llvm-readelf";
    const soPath = join(tempDirectory, "libairplay_native.so");
    const extract = hostBinaryCommand("unzip", ["-p", primary.localPath, "lib/arm64-v8a/libairplay_native.so"]);
    if (extract.status === 0 && extract.output.length > 0) {
      writeFileSync(soPath, extract.output);
      const header = hostCommand(readelf, ["-h", soPath]);
      const dynamic = hostCommand(readelf, ["-d", soPath]);
      const parsed = elfHeaderSummary(extract.output);
      evidence.native.elf = {
        ...parsed,
        readelf,
        readelfAvailable: header.status === 0,
        header: header.output,
        dynamic: dynamic.output,
        machineAarch64: parsed.machineAarch64 || /AArch64|ARM aarch64/i.test(header.output),
      };
    } else {
      evidence.native.elf = { error: "native_extract_failed" };
    }
  } finally {
    hostCommand("rm", ["-rf", tempDirectory]);
  }
  return evidence;
}

function filteredLog(log) {
  return log.split(/\r?\n/).filter((line) => crashPattern.test(line)).join("\n");
}

function pullDiagnosticReports(adb, serial, outputDirectory) {
  const listing = command(
    adb,
    serial,
    ["shell", "ls", "-1t", `${diagnosticReportsRemoteDirectory}/03lyrics-03cast-diagnostic-*`],
    "无法读取诊断 APK 报告目录",
    true,
  );
  const remoteFiles = listing.output
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) =>
      line.startsWith(`${diagnosticReportsRemoteDirectory}/`) &&
      /\.(json|txt)$/.test(line),
    );
  const pulled = [];
  for (const remotePath of remoteFiles) {
    const localPath = join(outputDirectory, basename(remotePath));
    const result = command(
      adb,
      serial,
      ["pull", remotePath, localPath],
      `无法拉取诊断 APK 报告 ${basename(remotePath)}`,
      true,
    );
    pulled.push({
      remotePath,
      localPath,
      succeeded: result.status === 0 && existsSync(localPath),
    });
  }
  return {
    remoteDirectory: diagnosticReportsRemoteDirectory,
    discovered: remoteFiles,
    pulled,
    note: remoteFiles.length > 0
      ? "已拉取诊断 APK 生成的 JSON/TXT 报告。"
      : "未发现诊断 APK 报告；请先在诊断 APK 中执行一次采集。",
  };
}

function buildReport(adb, serial, sdkRoot, options) {
  const report = {
    schemaVersion: 1,
    observedAtEpochMs: Date.now(),
    device: {},
    targetPackages: [],
    launch: {},
    runtime: {},
    logs: {},
  };
  report.device.model = command(adb, serial, ["shell", "getprop", "ro.product.model"], "无法读取设备型号", true).output;
  report.device.device = command(adb, serial, ["shell", "getprop", "ro.product.device"], "无法读取设备标识", true).output;
  report.device.hardware = command(adb, serial, ["shell", "getprop", "ro.hardware"], "无法读取硬件标识", true).output;
  report.device.release = command(adb, serial, ["shell", "getprop", "ro.build.version.release"], "无法读取 Android 版本", true).output;
  report.device.sdkInt = command(adb, serial, ["shell", "getprop", "ro.build.version.sdk"], "无法读取 SDK 版本", true).output;
  report.device.supportedAbis = command(adb, serial, ["shell", "getprop", "ro.product.cpu.abilist"], "无法读取 ABI", true).output;
  report.device.automotive = command(adb, serial, ["shell", "pm", "has-system-feature", "android.hardware.type.automotive"], "无法读取 Automotive 特性", true).output;
  report.device.supportsArm64 = /arm64-v8a/i.test(report.device.supportedAbis);
  for (const packageName of targetPackages) {
    const evidence = staticPackageEvidence(adb, serial, sdkRoot, packageName);
    if (evidence.native) evidence.native.deviceSupportsArm64 = report.device.supportsArm64;
    report.targetPackages.push(evidence);
  }

  const installed = report.targetPackages.find((item) => item.installed);
  if (options.launch && installed) {
    const packageName = installed.packageName;
    const resolved = command(adb, serial, ["shell", "cmd", "package", "resolve-activity", "--brief", packageName], "无法解析 03投屏 Launcher", true);
    const component = parseLaunchComponent(resolved.output, packageName);
    report.launch.component = component;
    if (options.clearLogcat) command(adb, serial, ["logcat", "-c"], "无法清理 logcat");
    report.launch.startRequestedAtEpochMs = Date.now();
    report.launch.startResult = command(
      adb,
      serial,
      ["shell", "am", "start", "-W", "-n", component],
      "启动 03投屏失败",
      true,
    ).output;
    wait(15_000);
    report.launch.pid = command(adb, serial, ["shell", "pidof", packageName], "无法读取 03投屏 PID", true).output;
    report.launch.activities = command(adb, serial, ["shell", "dumpsys", "activity", "activities"], "无法读取 Activity 状态", true).output;
    report.launch.services = command(adb, serial, ["shell", "dumpsys", "activity", "services", packageName], "无法读取 Service 状态", true).output;
    report.launch.exitInfo = command(adb, serial, ["shell", "dumpsys", "activity", "exit-info", packageName], "无法读取退出原因", true).output;
    report.runtime.window = command(adb, serial, ["shell", "dumpsys", "window", "windows"], "无法读取窗口状态", true).output;
    report.runtime.ports = {
      tcp: command(adb, serial, ["shell", "cat", "/proc/net/tcp"], "无法读取 TCP 端口", true).output,
      tcp6: command(adb, serial, ["shell", "cat", "/proc/net/tcp6"], "无法读取 TCP6 端口", true).output,
      udp: command(adb, serial, ["shell", "cat", "/proc/net/udp"], "无法读取 UDP 端口", true).output,
      udp6: command(adb, serial, ["shell", "cat", "/proc/net/udp6"], "无法读取 UDP6 端口", true).output,
    };
  }
  const fullLog = command(adb, serial, ["logcat", "-b", "all", "-d", "-v", "threadtime"], "无法读取 logcat", true).output;
  report.logs.filtered = filteredLog(fullLog);
  report.logs.totalLines = fullLog ? fullLog.split(/\r?\n/).length : 0;
  report.logs.filteredLines = report.logs.filtered ? report.logs.filtered.split(/\r?\n/).length : 0;
  report.logs.captureNote = options.clearLogcat
    ? "本轮启动前已按用户显式参数清空 logcat。"
    : "未清空既有 logcat；请结合启动时间和 exit-info 判断本轮证据。";
  report.runtime.connectivity = command(adb, serial, ["shell", "dumpsys", "connectivity"], "无法读取 connectivity", true).output;
  report.runtime.wifi = command(adb, serial, ["shell", "dumpsys", "wifi"], "无法读取 Wi-Fi", true).output;
  report.runtime.codec = command(adb, serial, ["shell", "dumpsys", "media.codec"], "无法读取 MediaCodec", true).output;
  report.runtime.mediaExtractor = command(adb, serial, ["shell", "dumpsys", "media.extractor"], "无法读取 media extractor", true).output;
  report.runtime.mediaSession = command(adb, serial, ["shell", "dumpsys", "media_session"], "无法读取媒体会话", true).output;
  report.runtime.car = command(adb, serial, ["shell", "dumpsys", "car_service"], "无法读取 Automotive service", true).output;
  report.diagnosticReports = pullDiagnosticReports(adb, serial, options.outputDirectory);
  return report;
}

const options = parseArguments(process.argv.slice(2));
if (!existsSync(configPath)) fail("缺少 .codex/local-context.properties");
const config = parseProperties(readFileSync(configPath, "utf8"));
const adb = config.PRIMARY_ADB;
const serial = config.VEHICLE_ADB_SERIAL;
const sdkRoot = config.ANDROID_SDK_ROOT;
if (!adb || !existsSync(adb)) fail("PRIMARY_ADB 不存在");
if (!serial) fail("VEHICLE_ADB_SERIAL 未配置");
if (!sdkRoot || !existsSync(sdkRoot)) fail("ANDROID_SDK_ROOT 不存在");
const state = command(adb, serial, ["get-state"], "无法连接目标车机", true).output;
if (state !== "device") fail(`目标车机状态不是 device：${state}`);
mkdirSync(options.outputDirectory, { recursive: true });
const report = buildReport(adb, serial, sdkRoot, options);
const stamp = new Date(report.observedAtEpochMs).toISOString().replace(/[:.]/g, "-");
const jsonPath = join(options.outputDirectory, `03cast-diagnostic-${stamp}.json`);
const textPath = join(options.outputDirectory, `03cast-diagnostic-${stamp}.txt`);
const json = JSON.stringify(report, null, 2);
const text = [
  "03投屏启动与能力诊断报告",
  `observedAtEpochMs=${report.observedAtEpochMs}`,
  `device=${report.device.model}`,
  `release=${report.device.release} sdk=${report.device.sdkInt}`,
  `supportedAbis=${report.device.supportedAbis}`,
  `launchComponent=${report.launch.component || "not_run"}`,
  `pid=${report.launch.pid || "none"}`,
  `diagnosticReports=${report.diagnosticReports.pulled.filter((item) => item.succeeded).map((item) => item.localPath).join(", ") || "none"}`,
  "",
  "=== target packages ===",
  JSON.stringify(report.targetPackages, null, 2),
  "",
  "=== launch ===",
  JSON.stringify(report.launch, null, 2),
  "",
  "=== filtered logcat ===",
  report.logs.filtered || "(no matching log lines)",
].join("\n");
writeFileSync(jsonPath, json, "utf8");
writeFileSync(textPath, text, "utf8");
console.log("03投屏诊断完成。");
console.log(`- 设备：${serial}`);
console.log(`- JSON：${jsonPath}`);
console.log(`- TXT：${textPath}`);
console.log(`- 03投屏包：${report.targetPackages.filter((item) => item.installed).map((item) => item.packageName).join(", ") || "未安装"}`);
console.log(`- 启动：${options.launch ? "已请求并等待 15 秒" : "未执行"}`);
console.log(`- 崩溃关键词日志：${report.logs.filteredLines} 行 / 全量 ${report.logs.totalLines} 行`);
