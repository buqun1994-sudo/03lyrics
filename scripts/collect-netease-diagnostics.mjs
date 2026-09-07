#!/usr/bin/env node

import { createHash } from "node:crypto";
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const diagnosticPackage = "com.ninepointnine.desktoplyrics.diagnostic";
const remoteDirectory = "files/diagnostics";
const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");

export function verifyDiagnosticArchive(file) {
  const names = execFileSync("unzip", ["-Z1", file], { encoding: "utf8" }).trim().split(/\r?\n/).sort();
  if (JSON.stringify(names) !== JSON.stringify(["integrity.json", "report.json"])) {
    throw new Error("Unexpected diagnostic archive contents");
  }
  const bytes = execFileSync("unzip", ["-p", file, "report.json"], { maxBuffer: 1024 * 1024 });
  const integrity = JSON.parse(execFileSync("unzip", ["-p", file, "integrity.json"], { encoding: "utf8" }));
  if (integrity.file !== "report.json" || integrity.bytes !== bytes.length || integrity.sha256 !== sha256(bytes)) {
    throw new Error("Diagnostic archive integrity mismatch");
  }
  const report = JSON.parse(bytes.toString("utf8"));
  if (report.schemaVersion !== 2 || report.kind !== "netease_media_events") {
    throw new Error("Unsupported diagnostic report schema");
  }
  return report;
}

export function parseArguments(argv) {
  const options = { duration: 60, serial: null, outputDirectory: null };
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index], value = argv[index + 1];
    if (!value) throw new Error("Missing value for " + key);
    if (key === "--duration") options.duration = Number(value);
    else if (key === "--serial") options.serial = value;
    else if (key === "--output-dir") options.outputDirectory = resolve(value);
    else throw new Error("Unknown option " + key);
  }
  if (!Number.isInteger(options.duration) || options.duration < 0 || options.duration > 60) {
    throw new Error("Duration must be an integer from 0 to 60 seconds");
  }
  return options;
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const config = Object.fromEntries(readFileSync(join(root, ".codex/local-context.properties"), "utf8")
    .split(/\r?\n/).filter((line) => !line.startsWith("#") && line.includes("="))
    .map((line) => [line.slice(0, line.indexOf("=")).trim(), line.slice(line.indexOf("=") + 1).trim()]));
  const adb = config.PRIMARY_ADB;
  const serial = options.serial || config.VEHICLE_ADB_SERIAL;
  if (!adb || !existsSync(adb) || !serial) throw new Error("ADB configuration unavailable");
  function run(args) {
    const result = spawnSync(adb, ["-s", serial, ...args], {
      encoding: "utf8", timeout: 15_000, maxBuffer: 4 * 1024 * 1024
    });
    return { ok: result.status === 0, output: result.stdout || "", error: result.error?.message || result.stderr || "" };
  }
  if (!run(["get-state"]).output.trim().includes("device")) throw new Error("Target device is unavailable");
  const output = options.outputDirectory || join(root, "app/build/reports/netease", String(Date.now()));
  mkdirSync(output, { recursive: true });
  function snapshot() {
    return {
      observedAtEpochMs: Date.now(),
      deviceEpochSeconds: run(["shell", "date", "+%s"]),
      uptime: run(["shell", "cat", "/proc/uptime"]),
      mediaSessions: run(["shell", "dumpsys", "media_session"]),
      audioFocusAndPlayback: run(["shell", "dumpsys", "audio"])
    };
  }
  const before = snapshot();
  console.log("开始被动取证，请在车机复现网易云播放问题。");
  for (let elapsed = 0; elapsed < options.duration;) {
    const seconds = Math.min(20, options.duration - elapsed);
    await new Promise((done) => setTimeout(done, seconds * 1_000));
    elapsed += seconds;
    console.log("已观察 " + elapsed + " 秒。");
  }
  const after = snapshot();
  const logs = run(["logcat", "-b", "main", "-b", "system", "-b", "crash", "-d", "-v", "epoch", "-t", "5000"]);
  const startSeconds = Number(before.deviceEpochSeconds.output.trim());
  const relevant = /MediaSessionService|MediaFocusControl|AudioService|AudioFocus|DesktopLyrics|netease|Netease|com\.tencent\.wecarflow/;
  const logLines = logs.output.split(/\r?\n/).filter((line) => {
    const epoch = Number(line.trim().split(/\s+/, 1)[0]);
    return relevant.test(line) && (!Number.isFinite(startSeconds) || !Number.isFinite(epoch) || epoch >= startSeconds);
  });
  const report = {
    schemaVersion: 1, kind: "netease_adb_companion", before, after,
    logs: { ok: logs.ok, error: logs.error, tailLimit: 5000, filteredLines: logLines },
    archives: []
  };
  const archivePaths = [];
  const listing = run(["shell", "run-as", diagnosticPackage, "ls", "-1t", remoteDirectory]);
  report.archiveListing = { ok: listing.ok, error: listing.error, access: "diagnostic_run_as" };
  for (const name of listing.output.trim().split(/\r?\n/).filter((value) =>
    /^03lyrics-netease-[0-9]+-[a-f0-9-]+\.zip$/.test(value)).slice(0, 3)) {
    const path = join(output, name);
    const pulled = spawnSync(adb, ["-s", serial, "exec-out", "run-as", diagnosticPackage,
      "cat", remoteDirectory + "/" + name], { timeout: 15_000, maxBuffer: 1024 * 1024 });
    const entry = { name, pulled: pulled.status === 0 };
    try {
      if (!entry.pulled) throw new Error(pulled.error?.message || pulled.stderr?.toString() || "ADB read failed");
      writeFileSync(path, pulled.stdout);
      const diagnostic = verifyDiagnosticArchive(path);
      entry.verified = true;
      entry.startedAtEpochMs = diagnostic.startedAtEpochMs;
      entry.captureCompleted = diagnostic.captureCompleted;
      entry.truncated = diagnostic.trace?.truncated;
      archivePaths.push(path);
    } catch (error) {
      entry.verified = false;
      entry.error = error.message;
    }
    report.archives.push(entry);
  }
  const companion = join(output, "adb-companion.json");
  writeFileSync(companion, JSON.stringify(report, null, 2) + "\n", "utf8");
  const files = [companion, ...archivePaths];
  const integrity = join(output, "bundle-integrity.json");
  writeFileSync(integrity, JSON.stringify(files.map((path) => {
    const bytes = readFileSync(path);
    return { file: path.slice(output.length + 1), bytes: bytes.length, sha256: sha256(bytes) };
  }), null, 2) + "\n");
  const zip = join(output, "netease-evidence-" + Date.now() + ".zip");
  const temporary = zip + ".tmp.zip";
  execFileSync("zip", ["-j", "-q", temporary, ...files, integrity]);
  execFileSync("unzip", ["-t", temporary]);
  renameSync(temporary, zip);
  console.log("取证完成：" + zip);
  if (!report.archives.some((entry) => entry.verified)) {
    console.log("未取得助手 ZIP，证据包已注明缺口；可在助手中录制并导出报告。");
  }
  console.log("未清理日志、未启动或控制播放器。");
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
