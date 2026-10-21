import assert from "node:assert/strict";
import { test } from "node:test";
import type { Vision } from "../src/vision.ts";
import {
  formatReport,
  groundingQuestion,
  parseGrounding,
  pngSize,
  runSeeActVerify,
  verifyMatch,
  type ScreenActDeps,
} from "../src/tools/screenact.ts";

/** A minimal buffer with a real PNG signature + IHDR dimensions (1000x500). */
function fakePng(width = 1000, height = 500): Buffer {
  const b = Buffer.alloc(24);
  b.writeUInt32BE(0x89504e47, 0);
  b.writeUInt32BE(width, 16);
  b.writeUInt32BE(height, 20);
  return b;
}

/**
 * Scripted deps: vision reads come from two queues (grounding reads say
 * where the target is; verify reads say what the screen shows), and every
 * action is recorded so a test can prove what executed — and what did not.
 */
function scripted(groundReads: string[], verifyReads: string[]) {
  const actions: string[] = [];
  let gi = 0;
  let vi = 0;
  const vision = {
    async describe(_images: unknown, question: string): Promise<string> {
      if (question.includes("Find this target")) return groundReads[Math.min(gi++, groundReads.length - 1)]!;
      return verifyReads[Math.min(vi++, verifyReads.length - 1)]!;
    },
  } as unknown as Vision;
  const deps: ScreenActDeps = {
    vision,
    screenshot: async () => fakePng(),
    click: async (x, y) => void actions.push(`click ${x},${y}`),
    type: async (t) => void actions.push(`type ${t}`),
    key: async (k) => void actions.push(`key ${k}`),
    settleMs: 0,
  };
  return { deps, actions };
}

const GROUND_OK = "The target is the terminal prompt line, near the top-left of the window. X=250, Y=100";

test("pngSize reads IHDR dimensions and rejects non-PNGs", () => {
  assert.deepEqual(pngSize(fakePng(1280, 800)), { width: 1280, height: 800 });
  assert.equal(pngSize(Buffer.from("not a png")), null);
});

test("parseGrounding accepts X=/Y= and (n, n) forms, converts to pixels, rejects prose", () => {
  assert.deepEqual(parseGrounding(GROUND_OK, 1000, 500), { relX: 250, relY: 100, x: 250, y: 50 });
  assert.deepEqual(parseGrounding("centre is (500, 500)", 1280, 800), { relX: 500, relY: 500, x: 640, y: 400 });
  assert.equal(parseGrounding("it is somewhere near the top", 1000, 500), null);
  assert.equal(parseGrounding("TARGET NOT VISIBLE", 1000, 500), null);
});

test("verifyMatch is case/whitespace-insensitive containment", () => {
  assert.equal(verifyMatch("line reads: MARKER:   ZEBRAQUANTUM7398\nYES", "zebraquantum7398"), true);
  assert.equal(verifyMatch("MARKER: NONE", "ZEBRAQUANTUM7398"), false);
});

test("grounding question asks for coordinates and for a verification-challenge description", () => {
  const q = groundingQuestion("the prompt");
  assert.match(q, /X=<number>, Y=<number>/);
  assert.match(q, /human-verification challenge/);
  assert.match(q, /whether the box appears checked/);
});

test("verified on the first attempt: action executes AT the grounded point", async () => {
  const { deps, actions } = scripted([GROUND_OK], ["The screen shows MARKER: ZEBRAQUANTUM7398. YES"]);
  const r = await runSeeActVerify(
    { target: "the terminal prompt line", clickTarget: true, typeText: "clear; echo 'MARKER: ZEBRAQUANTUM7398'", keys: "Return", expected: "ZEBRAQUANTUM7398" },
    deps,
  );
  assert.equal(r.outcome, "verified");
  assert.equal(r.attempts, 1);
  assert.deepEqual(actions, ["click 250,50", "type clear; echo 'MARKER: ZEBRAQUANTUM7398'", "key Return"]);
  assert.match(r.comparison, /Expected: "ZEBRAQUANTUM7398"/);
  assert.match(r.comparison, /Match: YES/);
  assert.match(formatReport(r), /Outcome: VERIFIED on attempt 1/);
});

test("verify mismatch: the action is NOT repeated; a re-observe that shows it landed late counts", async () => {
  const { deps, actions } = scripted(
    [GROUND_OK, GROUND_OK],
    ["MARKER: NONE. NO", "Now it shows MARKER: ZEBRAQUANTUM7398. YES"],
  );
  const r = await runSeeActVerify(
    { target: "the terminal prompt line", clickTarget: true, typeText: "echo x", keys: "Return", expected: "ZEBRAQUANTUM7398" },
    deps,
  );
  // One card, one action: exactly one click, one typing, one key press.
  assert.deepEqual(actions, ["click 250,50", "type echo x", "key Return"]);
  assert.equal(r.attempts, 1);
  assert.equal(r.groundings.length, 1);
  assert.equal(r.verifies.length, 2);
  assert.equal(r.verifies[0]!.matched, false);
  assert.equal(r.verifies[1]!.matched, true);
  assert.equal(r.outcome, "verified");
  assert.match(formatReport(r), /re-observed, no input repeated/);
});

test("verify mismatch twice: FAILED, the action ran exactly once, and the report says what the screen shows", async () => {
  const { deps, actions } = scripted([GROUND_OK], ["MARKER: NONE. NO", "The screen shows a login dialog. NO"]);
  const r = await runSeeActVerify(
    { target: "the terminal prompt line", clickTarget: true, typeText: "echo x", keys: "Return", expected: "ZEBRAQUANTUM7398" },
    deps,
  );
  assert.equal(r.outcome, "failed");
  assert.deepEqual(actions, ["click 250,50", "type echo x", "key Return"]); // never repeated
  assert.equal(r.attempts, 1);
  assert.equal(r.verifies.length, 2); // the first read + one re-observe, no third
  assert.match(r.comparison, /Match: NO/);
  assert.match(r.comparison, /login dialog/);
  const out = formatReport(r);
  assert.match(out, /Outcome: FAILED/);
  assert.match(out, /NOT repeated/);
});

test("a first grounding miss re-grounds without acting; the action still runs once", async () => {
  const { deps, actions } = scripted(["TARGET NOT VISIBLE", GROUND_OK], ["MARKER: NONE. NO"]);
  const r = await runSeeActVerify(
    { target: "the terminal prompt line", clickTarget: true, typeText: "echo x", expected: "ZEBRAQUANTUM7398" },
    deps,
  );
  assert.equal(r.groundings.length, 2);
  assert.deepEqual(actions, ["click 250,50", "type echo x"]);
  assert.equal(r.outcome, "failed");
});

test("see_act_verify's description promises one action per call, not a retry", async () => {
  const { ALL_TOOLS } = await import("../src/tools/index.ts");
  const d = ALL_TOOLS.find((t) => t.name === "see_act_verify")!.description;
  assert.doesNotMatch(d, /retries/);
  assert.match(d, /at most once/);
});

test("no grounding coordinates: NOT GROUNDED and zero actions executed", async () => {
  const { deps, actions } = scripted(["TARGET NOT VISIBLE"], ["unused"]);
  const r = await runSeeActVerify(
    { target: "a thing that is not on screen", clickTarget: true, typeText: "echo x", expected: "ZEBRAQUANTUM7398" },
    deps,
  );
  assert.equal(r.outcome, "not-grounded");
  assert.deepEqual(actions, []);
  assert.match(formatReport(r), /NOT GROUNDED/);
});

test("see_act_verify is offered only when screen control is on (same gate as control_screen)", async () => {
  const { toolsFor, ALL_TOOLS } = await import("../src/tools/index.ts");
  assert.ok(ALL_TOOLS.some((t) => t.name === "see_act_verify"));
  assert.equal(toolsFor({ offlineOnly: false, screenControl: false }).some((t) => t.name === "see_act_verify"), false);
  assert.equal(toolsFor({ offlineOnly: false, screenControl: true }).some((t) => t.name === "see_act_verify"), true);
  const t = ALL_TOOLS.find((t) => t.name === "see_act_verify")!;
  assert.equal(t.policy.alwaysConfirm, true);
});
