import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseArguments, verifyDiagnosticArchive } from "../collect-netease-diagnostics.mjs";

test("duration is bounded and unknown options cannot reach adb", () => {
  assert.equal(parseArguments(["--duration", "0"]).duration, 0);
  for (const args of [["--duration", "61"], ["--duration", "-1"], ["--duration", "NaN"], ["--start", "yes"]]) {
    assert.throws(() => parseArguments(args));
  }
});

test("exported bytes must agree with the archive manifest", () => {
  const directory = mkdtempSync(join(tmpdir(), "netease-evidence-"));
  try {
    const data = Buffer.from(JSON.stringify({ schemaVersion: 2, kind: "netease_media_events", title: "Song" }));
    const report = join(directory, "report.json"), integrity = join(directory, "integrity.json");
    writeFileSync(report, data);
    writeFileSync(integrity, JSON.stringify({
      file: "report.json", bytes: data.length, sha256: createHash("sha256").update(data).digest("hex")
    }));
    const archive = join(directory, "report.zip");
    execFileSync("zip", ["-jq", archive, report, integrity]);
    assert.equal(verifyDiagnosticArchive(archive).title, "Song");
    const automatic = Buffer.from(JSON.stringify({ schemaVersion: 3, kind: "netease_media_events", capturePolicy: { mode: "automatic" } }));
    writeFileSync(report, automatic);
    writeFileSync(integrity, JSON.stringify({
      file: "report.json", bytes: automatic.length, sha256: createHash("sha256").update(automatic).digest("hex")
    }));
    execFileSync("zip", ["-jq", archive, report, integrity]);
    assert.equal(verifyDiagnosticArchive(archive).capturePolicy.mode, "automatic");
    writeFileSync(report, "{}");
    execFileSync("zip", ["-jq", archive, report]);
    assert.throws(() => verifyDiagnosticArchive(archive), /integrity mismatch/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
