import assert from "node:assert/strict";
import { test } from "node:test";
import { isCrostini, onWindowClose } from "../src/app/behavior.ts";

test("window close hides to the tray, or quits when there's no tray", () => {
  assert.equal(onWindowClose(true, false), "hide");   // normal desktop: keep running in the tray
  assert.equal(onWindowClose(false, false), "close");  // no tray (Crostini): closing quits
  assert.equal(onWindowClose(true, true), "close");    // already quitting: let it close
  assert.equal(onWindowClose(false, true), "close");
});

test("Crostini detection: env or marker files on Linux only", () => {
  const no = () => false;
  assert.equal(isCrostini({ SOMMELIER_VERSION: "1.0" }, no), process.platform === "linux");
  assert.equal(isCrostini({}, (p) => p === "/opt/google/cros-containers"), process.platform === "linux");
  assert.equal(isCrostini({}, no), false);
});
