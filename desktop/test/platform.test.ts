import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { afterEach, test } from "node:test";
import { screenClick, screenKey, useOsForTest } from "../src/tools/platform.ts";

/**
 * The macOS/Windows screen actions used to report success without performing
 * (a fixed Return/ENTER for every key combo, and osascript failures
 * swallowed). These tests pin the honest behavior on Linux by pretending
 * the OS: a supported action must at least ATTEMPT its real backend (which
 * is absent here, so the call must fail loudly, never resolve), and an
 * unsupported combo must fail with a clear not-supported reason before any
 * backend is tried.
 */

afterEach(() => useOsForTest(null));

test("macOS click no longer swallows a failed click", async () => {
  useOsForTest("darwin");
  // osascript does not exist on this machine: the old code resolved anyway.
  await assert.rejects(screenClick(10, 10, "left"), /osascript/);
});

test("macOS key sends the requested combo, failing loudly when it can't", async () => {
  useOsForTest("darwin");
  await assert.rejects(screenKey("ctrl+c"), /osascript/);
  await assert.rejects(screenKey("cmd+shift+Return"), /osascript/);
});

test("macOS key refuses a combo it cannot express, before trying anything", async () => {
  useOsForTest("darwin");
  await assert.rejects(screenKey("banana"), /isn't one macOS screen control can send/);
  await assert.rejects(screenKey("hyper+x"), /isn't supported on macOS/);
});

test("Windows key sends the requested combo via SendKeys, failing loudly when it can't", async () => {
  useOsForTest("win32");
  // powershell does not exist on this machine: the old code resolved anyway.
  await assert.rejects(screenKey("ctrl+c"), /powershell/);
  await assert.rejects(screenKey("Return"), /powershell/);
});

test("Windows key refuses the meta modifier instead of silently pressing ENTER", async () => {
  useOsForTest("win32");
  await assert.rejects(screenKey("meta+q"), /can't be sent with SendKeys/);
  await assert.rejects(screenKey("banana"), /isn't one Windows screen control can send/);
});

test("Windows click stays honestly unimplemented", async () => {
  useOsForTest("win32");
  await assert.rejects(screenClick(5, 5, "left"), /isn't wired up on Windows yet/);
});

test("Linux key with no backend installed reports the install hint", async () => {
  const has = (cmd: string) => spawnSync("which", [cmd]).status === 0;
  if (has("xdotool") || has("ydotool")) return; // a backend exists here; nothing honest to assert
  await assert.rejects(screenKey("ctrl+c"), /xdotool.*ydotool|Screen control needs/);
});
