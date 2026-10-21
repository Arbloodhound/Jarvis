import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import {
  MAX_SCROLL_CLICKS,
  easeInOutCubic,
  mulberry32,
  planDuration,
  planMousePath,
  planScroll,
  planTyping,
} from "../src/tools/human.ts";
import {
  replayMousePath,
  replayScrollPlan,
  replayTypingPlan,
  screenClick,
  screenScroll,
  screenType,
  useOsForTest,
  useScreenDepsForTest,
  type ScreenExecDeps,
} from "../src/tools/platform.ts";

/**
 * Human-quality input: the planners in human.ts must produce eased,
 * jittered, naturally-timed plans (deterministic under a seed), and the
 * Linux executors in platform.ts must replay a plan step for step.
 */

// ---------- mouse planner ----------

test("mouse plan: a long move is many steps, landing exactly on target", () => {
  const steps = planMousePath({ x: 0, y: 0 }, { x: 800, y: 600 }, { seed: 42 });
  assert.ok(steps.length > 10, `expected many steps, got ${steps.length}`);
  assert.deepEqual({ x: steps.at(-1)!.x, y: steps.at(-1)!.y }, { x: 800, y: 600 });
  const total = planDuration(steps);
  assert.ok(total >= 200 && total <= 1200, `path duration ${total}ms out of practical range`);
});

test("mouse plan: a tiny hop stays a single step", () => {
  assert.equal(planMousePath({ x: 10, y: 10 }, { x: 11, y: 10 }, { seed: 1 }).length, 1);
});

test("mouse plan: easing bunches steps at the ends and spreads them mid-path", () => {
  const from = { x: 0, y: 0 };
  const to = { x: 1000, y: 0 };
  const steps = planMousePath(from, to, { seed: 7, maxJitterPx: 0 });
  const pts = [from, ...steps];
  const segs = pts.slice(1).map((p, i) => Math.hypot(p.x - pts[i]!.x, p.y - pts[i]!.y));
  const max = Math.max(...segs);
  // Ease-in-out promise: the first and last segments are far shorter
  // than the fastest mid-path segment.
  assert.ok(segs[0]! < max * 0.35, `first segment ${segs[0]} not eased vs max ${max}`);
  assert.ok(segs.at(-1)! < max * 0.35, `last segment ${segs.at(-1)} not eased vs max ${max}`);
  const mid = segs[Math.floor(segs.length / 2)]!;
  assert.ok(mid > segs[0]! * 2, "mid-path should be much faster than the start");
  // The easing curve itself: symmetric, slow-fast-slow.
  assert.ok(easeInOutCubic(0.25) < 0.25 && easeInOutCubic(0.75) > 0.75);
  assert.equal(easeInOutCubic(0.5), 0.5);
});

test("mouse plan: jitter stays within bounds and never moves the endpoints", () => {
  const from = { x: 100, y: 100 };
  const to = { x: 900, y: 100 }; // horizontal line: perpendicular distance is |y - 100|
  const steps = planMousePath(from, to, { seed: 3, maxJitterPx: 3.5 });
  for (const s of steps) {
    assert.ok(Math.abs(s.y - 100) <= 4.5, `jitter out of bounds at ${JSON.stringify(s)}`); // bound + rounding
  }
  assert.equal(steps.at(-1)!.y, 100);
});

test("mouse plan: deterministic under a fixed seed, different across seeds", () => {
  const a = planMousePath({ x: 0, y: 0 }, { x: 640, y: 480 }, { seed: 42 });
  const b = planMousePath({ x: 0, y: 0 }, { x: 640, y: 480 }, { seed: 42 });
  const c = planMousePath({ x: 0, y: 0 }, { x: 640, y: 480 }, { seed: 43 });
  assert.deepEqual(a, b);
  assert.notDeepEqual(a, c);
  assert.equal(mulberry32(9)(), mulberry32(9)());
});

// ---------- typing planner ----------

test("typing plan: one step per character, text reassembles exactly", () => {
  const text = "The quick brown fox.";
  const steps = planTyping(text, { seed: 5 });
  assert.equal(steps.length, [...text].length);
  assert.equal(steps.map((s) => s.text).join(""), text);
});

test("typing plan: delays vary and the total sits in a human range", () => {
  const steps = planTyping("The quick brown fox jumps over the lazy dog.", { seed: 11 });
  const delays = steps.map((s) => s.delayMs);
  assert.ok(new Set(delays).size > 5, "cadence must not be a metronome");
  for (const d of delays) assert.ok(d >= 15 && d <= 620, `delay ${d}ms outside stated bounds`);
  const total = planDuration(steps);
  // 45 chars at a natural ~75ms average, plus pauses: a few seconds, not instant, not a minute.
  assert.ok(total >= 1500 && total <= 9000, `total ${total}ms outside human range`);
});

test("typing plan: a 200-character type is capped, never a minute", () => {
  const steps = planTyping("Pack my box with five dozen liquor jugs. ".repeat(5).slice(0, 200), { seed: 2 });
  const total = planDuration(steps);
  assert.ok(total <= 20_000, `200 chars took ${total}ms, over the 20s cap`);
  assert.ok(total >= 200 * 15, "cap scaling keeps the per-key floor");
});

test("typing plan: pauses land after spaces and punctuation", () => {
  // With a constant rand the only variation left is the structural
  // pauses, so they can be asserted exactly.
  const steps = planTyping("ab cd. ef", { rand: () => 0.5 });
  const delayAfter = (i: number) => steps[i + 1]!.delayMs; // wait before the char after index i
  const plain = steps[1]!.delayMs; // before 'b', after 'a': no pause
  assert.ok(delayAfter(2) > plain, "pause after a space");
  assert.ok(delayAfter(5) > delayAfter(2), "bigger pause after a full stop");
});

test("typing plan: deterministic under a fixed seed", () => {
  assert.deepEqual(planTyping("hello world", { seed: 8 }), planTyping("hello world", { seed: 8 }));
  assert.deepEqual(planTyping("", { seed: 8 }), []);
});

// ---------- scroll planner ----------

test("scroll plan: splits into increments that sum to the amount", () => {
  const steps = planScroll(10, { seed: 4 });
  assert.ok(steps.length >= 4, `10 clicks in ${steps.length} increments is not incremental`);
  assert.equal(steps.reduce((a, s) => a + s.steps, 0), 10);
  for (const s of steps) {
    assert.ok(s.steps >= 1 && s.steps <= 3, "each increment is 1-3 clicks");
    assert.ok(s.delayMs >= 25 && s.delayMs <= 200, `pause ${s.delayMs}ms outside stated bounds`);
  }
});

test("scroll plan: direction is preserved and zero is a no-op", () => {
  const up = planScroll(-7, { seed: 4 });
  assert.equal(up.reduce((a, s) => a + s.steps, 0), -7);
  assert.ok(up.every((s) => s.steps < 0));
  assert.deepEqual(planScroll(0, { seed: 4 }), []);
});

test("scroll plan: huge amounts clamp to the stated maximum", () => {
  const steps = planScroll(500, { seed: 4 });
  assert.equal(steps.reduce((a, s) => a + s.steps, 0), MAX_SCROLL_CLICKS);
  assert.ok(planDuration(steps) <= 4000 + steps.length * 25, "duration stays capped");
});

test("scroll plan: deterministic under a fixed seed", () => {
  assert.deepEqual(planScroll(9, { seed: 6 }), planScroll(9, { seed: 6 }));
});

// ---------- executors (fake command runner) ----------

interface Recorded {
  events: Array<{ kind: "run"; cmd: string; args: string[] } | { kind: "sleep"; ms: number }>;
  deps: ScreenExecDeps;
}

function fakeDeps(location = "X=100\nY=100\nSCREEN=0\nWINDOW=0"): Recorded {
  const events: Recorded["events"] = [];
  const deps: ScreenExecDeps = {
    runFn: async (cmd, args) => {
      events.push({ kind: "run", cmd, args });
      if (args[0] === "getmouselocation") return location;
      return "";
    },
    sleepFn: async (ms) => {
      events.push({ kind: "sleep", ms });
    },
  };
  return { events, deps };
}

afterEach(() => {
  useScreenDepsForTest(null);
  useOsForTest(null);
});

test("executor: replayMousePath emits the plan in order, sleeping before each step", async () => {
  const plan = planMousePath({ x: 0, y: 0 }, { x: 300, y: 200 }, { seed: 21 });
  const { events, deps } = fakeDeps();
  await replayMousePath(plan, deps);
  const moves = events.filter((e) => e.kind === "run").map((e) => (e as { args: string[] }).args);
  assert.deepEqual(
    moves.map((a) => [Number(a.at(-2)), Number(a.at(-1))]),
    plan.map((s) => [s.x, s.y]),
  );
  const sleeps = events.filter((e) => e.kind === "sleep").map((e) => (e as { ms: number }).ms);
  assert.deepEqual(sleeps, plan.filter((s) => s.delayMs > 0).map((s) => s.delayMs));
  // Interleaving: no two moves back to back without their planned wait
  // (every step in this plan has a positive delay).
  assert.equal(events[0]!.kind, "sleep");
});

test("executor: replayTypingPlan types per step, never one burst", async () => {
  const plan = planTyping("hi there", { seed: 21 });
  const { events, deps } = fakeDeps();
  await replayTypingPlan(plan, deps);
  const runs = events.filter((e) => e.kind === "run").map((e) => (e as { args: string[] }).args);
  assert.equal(runs.length, plan.length);
  assert.ok(runs.every((a) => a[0] === "type" && a.at(-1)!.length === 1), "one character per xdotool type call");
  assert.equal(runs.map((a) => a.at(-1)).join(""), "hi there");
});

test("executor: replayScrollPlan expands increments into individual wheel clicks", async () => {
  const plan = planScroll(-6, { seed: 21 });
  const { events, deps } = fakeDeps();
  await replayScrollPlan(plan, deps);
  const runs = events.filter((e) => e.kind === "run").map((e) => (e as { args: string[] }).args);
  assert.equal(runs.length, 6);
  assert.ok(runs.every((a) => a[0] === "click" && a[1] === "4"), "negative scroll is wheel-up (button 4)");
});

test("executor: screenClick glides through intermediate points before clicking", async () => {
  useOsForTest("linux");
  const { events, deps } = fakeDeps(); // cursor starts at 100,100
  useScreenDepsForTest(deps);
  await screenClick(500, 400, "left");
  const runs = events.filter((e) => e.kind === "run").map((e) => (e as { args: string[] }).args);
  const moves = runs.filter((a) => a[0] === "mousemove");
  assert.ok(moves.length > 5, `click teleported: only ${moves.length} moves`);
  const positions = moves.map((a) => [Number(a.at(-2)), Number(a.at(-1))] as const);
  assert.ok(positions.some(([x, y]) => x > 100 && x < 500 && y > 100 && y < 400), "an intermediate position exists");
  assert.deepEqual(positions.at(-1), [500, 400]);
  assert.deepEqual(runs.at(-1), ["click", "1"], "the click comes last, at the end of the path");
});

test("executor: screenType through the platform path emits per-key calls", async () => {
  useOsForTest("linux");
  const { events, deps } = fakeDeps();
  useScreenDepsForTest(deps);
  await screenType("hello");
  const types = events.filter((e) => e.kind === "run").map((e) => (e as { args: string[] }).args).filter((a) => a[0] === "type");
  assert.equal(types.length, 5);
  assert.equal(types.map((a) => a.at(-1)).join(""), "hello");
});

test("executor: screenScroll emits wheel-down clicks in increments", async () => {
  useOsForTest("linux");
  const { events, deps } = fakeDeps();
  useScreenDepsForTest(deps);
  await screenScroll(5);
  const runs = events.filter((e) => e.kind === "run").map((e) => (e as { args: string[] }).args);
  const clicks = runs.filter((a) => a[0] === "click");
  assert.equal(clicks.length, 5);
  assert.ok(clicks.every((a) => a[1] === "5"), "positive scroll is wheel-down (button 5)");
  const pauses = events.filter((e) => e.kind === "sleep").length;
  assert.ok(pauses >= 2, "scroll pauses between increments");
});
