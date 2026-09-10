#!/usr/bin/env node

import assert from "node:assert/strict";
import { mkdirSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { Script } from "node:vm";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const reports = join(root, "app/build/reports/lyrics-overlay");
const captureScreenshots = !process.argv.includes("--no-screenshots");
if (captureScreenshots) mkdirSync(reports, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  channel: process.env.LYRICS_TEST_BROWSER_CHANNEL || undefined,
});
const firstLine = "This long first line previews the lyrics before progress arrives and stays at its starting edge";
const lyrics = `[00:10.00]${firstLine}\n[00:20.00]Second line\n[00:40.00]Third line`;
const failures = [];
let assertions = 0;

async function view(page) {
  return page.evaluate(() => {
    const line = document.querySelector(".compact-line.current .compact-line-text");
    const active = document.querySelector("#lyrics .line.active");
    const bounds = active?.getBoundingClientRect();
    const viewport = document.querySelector(".lyrics-viewport").getBoundingClientRect();
    return {
      visible: document.body.classList.contains("lyrics-visible"),
      active: active ? Number(active.dataset.i) : null,
      text: line?.textContent || "",
      scrolling: line?.classList.contains("marquee") || false,
      scrollReady: line?.dataset.scrollReady === "true",
      activeY: bounds?.y,
      activeHeight: bounds?.height,
      viewportHeight: innerHeight,
      focusY: viewport.y + viewport.height * 0.48,
    };
  });
}

function check(actual, expected, message) {
  assert.deepEqual(actual, expected, message);
  assertions += 1;
}

try {
  for (const [name, width, height, surface] of [
    ["desktop", 1230, 810, "desktop"],
    ["topbar", 600, 90, "topbar"],
    ["narrow-topbar", 360, 90, "topbar"],
  ]) {
    const page = await browser.newPage({ viewport: { width, height } });
    page.on("pageerror", (error) => failures.push(`${name}: ${error.message}`));
    await page.clock.install();
    await page.clock.pauseAt(Date.now() + 1_000);
    await page.goto(pathToFileURL(join(root, "app/src/main/assets/lyrics_overlay.html")).href);
    const scripts = await page.locator("script").allTextContents();
    for (const source of scripts) new Script(source, { filename: "lyrics_overlay.html" });
    await page.evaluate((mode) => {
      window.LobstaOverlay.setSurfaceMode(mode);
      window.LobstaOverlay.setTheme("light");
    }, surface);
    if (surface === "topbar") await page.addStyleTag({ content: "html { background: #191c20; }" });

    let playback = {
      hasSession: true, track: "Timing Test", artist: "Artist", album: "Album",
      recordingGeneration: 1, queryRevision: 1, state: "playing",
      positionMs: 0, durationMs: 60_000, speed: 1, timelineReady: false,
    };
    const send = async (changes = {}) => {
      playback = { ...playback, ...changes };
      await page.evaluate((data) => window.LobstaOverlay.updatePlayback(data), playback);
      await page.clock.runFor(80);
    };
    const receive = async (generation = 1, revision = 1, body = lyrics) => {
      await page.evaluate(({ generation, revision, body }) => {
        window.LobstaOverlay.receiveLyrics(generation, revision, { lyrics: body, duration: 60_000 });
      }, { generation, revision, body });
      await page.clock.runFor(80);
    };

    await send();
    await receive();
    let state = await view(page);
    check(state.visible, true, `${name}: provisional lyric is visible`);
    check(state.text, firstLine, `${name}: provisional first line`);
    check(state.active, 0, `${name}: provisional active row`);
    check(state.scrolling, false, `${name}: provisional lyric does not scroll`);
    check(state.scrollReady, false, `${name}: preview never fabricates timing readiness`);

    await page.evaluate(() => { window.testInitialRow = document.querySelector("#lyrics .line"); });
    await send({ positionMs: 45_000 });
    check((await view(page)).text, firstLine, `${name}: unknown progress cannot move the preview`);
    check(await page.evaluate(() => window.testInitialRow === document.querySelector("#lyrics .line")), true, `${name}: progress does not rebuild lyrics`);
    if (surface === "desktop") {
      await page.evaluate(() => {
        window.LobstaOverlay.setSurfaceMode("topbar");
        window.LobstaOverlay.setSurfaceMode("desktop");
      });
      await page.clock.runFor(250);
      state = await view(page);
      check(state.visible, true, "desktop: preview survives surface changes");
      assert(state.activeY >= 0 && state.activeY + state.activeHeight <= height, "desktop preview stays inside its viewport");
      assertions += 1;
    } else {
      await page.setViewportSize({ width: 1230, height: 810 });
      await page.evaluate(() => window.LobstaOverlay.setSurfaceMode("desktop"));
      await page.clock.runFor(250);
      state = await view(page);
      assert(Math.abs(state.activeY + state.activeHeight / 2 - state.focusY) < 3,
        `preview recenters after the actual window geometry changes: ${JSON.stringify(state)}`);
      assertions += 1;
      await page.setViewportSize({ width, height });
      await page.evaluate(() => window.LobstaOverlay.setSurfaceMode("topbar"));
      await page.clock.runFor(250);
    }
    if (captureScreenshots) await page.screenshot({ path: join(reports, `${name}-preview.png`) });

    await send({ timelineReady: true, positionMs: 21_000 });
    check((await view(page)).active, 1, `${name}: first trusted progress immediately selects the correct row`);
    check((await view(page)).text, "Second line", `${name}: compact text follows trusted progress`);
    await send({ state: "paused", positionMs: 21_000, speed: 0 });
    await page.clock.runFor(2_000);
    check((await view(page)).active, 1, `${name}: pause freezes lyrics`);
    check((await view(page)).visible, true, `${name}: pause retains visible lyrics`);
    await send({ positionMs: 45_000 });
    check((await view(page)).active, 2, `${name}: a seek while paused moves to the correct row`);
    await send({ state: "playing", positionMs: 0, speed: 1 });
    check((await view(page)).active, 0, `${name}: backward seek returns to the first row`);
    check((await view(page)).scrollReady, false, `${name}: intro preview has no horizontal scrolling`);
    await send({ positionMs: 10_000 });
    check((await view(page)).scrollReady, true, `${name}: reaching the timestamp allows scrolling`);
    await page.clock.runFor(10_100);
    check((await view(page)).active, 1, `${name}: the lyric deadline advances without polling`);
    await page.clock.runFor(300);
    if (captureScreenshots) {
      await page.screenshot({ path: join(reports, `${name}-synchronized.png`), animations: "disabled" });
    }

    await send({ track: "Next Song", recordingGeneration: 2, queryRevision: 2, positionMs: 0 });
    check((await view(page)).visible, false, `${name}: switching tracks removes old lyrics`);
    await receive(1, 1);
    check((await view(page)).visible, false, `${name}: a late old result cannot reappear`);
    await receive(2, 2, "[00:00.00]New recording\n[00:20.00]Next line");
    check((await view(page)).text, "New recording", `${name}: new recording renders`);
    await send({ queryRevision: 3 });
    await receive(2, 2);
    check((await view(page)).visible, false, `${name}: an obsolete query revision is rejected`);
    await receive(2, 3, "[00:00.00]Revised recording\n[00:20.00]Next line");
    check((await view(page)).text, "Revised recording", `${name}: current query renders`);
    await send({ state: "stopped", speed: 0 });
    check((await view(page)).visible, false, `${name}: stopped playback clears the display`);

    await send({ recordingGeneration: 3, queryRevision: 4, state: "paused", positionMs: 0 });
    await receive(3, 4);
    check((await view(page)).visible, false, `${name}: a newly discovered paused track stays hidden`);
    await send({ state: "playing", speed: 1, positionMs: 45_000 });
    check((await view(page)).active, 2, `${name}: resuming a newly discovered track reveals the correct row`);
    await send({ hasSession: false });
    check((await view(page)).visible, false, `${name}: removing the session clears lyrics`);
    await page.close();
    console.log(`PASS ${name}`);
  }
  check(failures, [], "production overlay has no JavaScript errors");
  console.log(`PASS ${assertions} overlay assertions; ${captureScreenshots ? "screenshots: app/build/reports/lyrics-overlay" : "screenshots disabled"}`);
} finally {
  await browser.close();
}
