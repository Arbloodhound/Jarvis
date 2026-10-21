import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import type { Handoff } from "../src/agent/orchestrator.ts";
import { defaults, paths } from "../src/config.ts";
import { Runtime } from "../src/runtime.ts";
import type { Provider, Turn, TurnRequest, TurnResult } from "../src/providers/types.ts";

/**
 * Runtime-side regressions in the subagent / scheduled-run plumbing:
 *
 *  - bug 2: a run that failed came back as an ordinary answer, so a failed
 *    child handed off DONE and a failed scheduled job was recorded "ran".
 *  - bug 3: hand-offs were queued silently; the owner's channel never heard.
 *  - bug 4: Stop (and quit) never reached a running scheduled job.
 *  - bug 6: steering sent during a child's last model turn was dropped.
 */

function deferred<T = void>() {
  let resolve!: (v: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((res) => { resolve = res; });
  return { promise, resolve };
}

async function until(fn: () => boolean, ms = 4000): Promise<void> {
  const t0 = Date.now();
  while (!fn()) {
    if (Date.now() - t0 > ms) throw new Error("until: timed out");
    await new Promise((r) => setTimeout(r, 5));
  }
}

const end = (text: string): TurnResult => ({ text, stop: "end", toolCalls: [] });
const callTool = (id: string, name: string, input: Record<string, unknown>): TurnResult =>
  ({ text: "", stop: "tool_use", toolCalls: [{ id, name, input }] });
const hasHandoff = (turns: Turn[]): boolean =>
  turns.some((t) => t.role === "user" && t.text.includes("[Hand-off from subagent"));
const usedTool = (turns: Turn[], name: string): boolean =>
  turns.some((t) => t.role === "tool" && t.results.some((r) => r.name === name));
const allText = (turns: Turn[]): string => turns.map((t) => (t.role === "tool" ? "" : t.text)).join("\n");

type Who = "parent" | "child" | "scheduled";
function brain(fn: (req: TurnRequest, who: Who) => TurnResult | Promise<TurnResult>): Provider {
  return {
    kind: "local",
    label: "fake brain",
    async turn(req) {
      await new Promise((r) => setTimeout(r, 1));
      const who: Who = req.system.includes("You are a subagent") ? "child"
        : req.tools.some((t) => t.name === "spawn_agent") ? "parent" : "scheduled";
      return fn(req, who);
    },
  };
}

function runtime(b: Provider): Runtime {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-rtfix-"));
  const p = paths(home);
  const rt = new Runtime(defaults(p), p, home);
  (rt as unknown as { provider: () => Provider }).provider = () => b;
  return rt;
}

/** A model turn that never finishes until its run is aborted. */
const hang = (req: TurnRequest): Promise<TurnResult> => new Promise<never>((_res, rej) => {
  if (req.signal.aborted) rej(new Error("aborted"));
  req.signal.addEventListener("abort", () => rej(new Error("aborted")), { once: true });
});

const allowAll = { emit: () => {}, confirm: async () => true };

// ---------- bug 2: failure is reported as failure ----------

test("bug 2: a child whose run fails hands off as FAILED with the error, not DONE", async () => {
  const b = brain((req, who) => {
    if (who === "child") throw new Error("model server exploded");
    if (hasHandoff(req.turns)) return end("saw the hand-off");
    if (!usedTool(req.turns, "spawn_agent")) return callTool("s1", "spawn_agent", { task: "doomed job", name: "doomed" });
    return callTool("s2", "agent_status", {});
  });
  const rt = runtime(b);
  const answer = await rt.agent.run("delegate the doomed job", allowAll);
  assert.equal(answer, "saw the hand-off");
  assert.equal(rt.orchestrator.list()[0]!.state, "failed", "the orchestrator lists the child as failed");
  const text = allText(rt.agent.turns);
  assert.ok(text.includes("FAILED (not verified)"), text);
  assert.ok(text.includes("model server exploded"), "the hand-off carries the error");
  assert.ok(!text.includes("DONE (not verified)"), "a failed child never hands off as done");
});

test("bug 2: the agent reports a failed run distinctly; run() still returns the text", async () => {
  const rt = runtime(brain(() => { throw new Error("no model loaded"); }));
  const r = await rt.agent.runResult("anything", allowAll);
  assert.equal(r.status, "failed");
  assert.equal(r.error, "no model loaded");
  assert.equal(await rt.agent.run("anything", allowAll), "no model loaded", "run() keeps its string return");
  assert.equal(rt.agent.lastResult?.status, "failed");
});

test("bug 2: a scheduled job whose run fails is recorded failed, not ran", async () => {
  const rt = runtime(brain(() => { throw new Error("brain unreachable"); }));
  const job = rt.jobs.add({ owner: "owner", kind: "once", schedule: new Date(Date.now() + 3_600_000).toISOString(), task: "summarize my day" }, Date.now());
  const out = await rt.scheduler.fire(job);
  assert.equal(out.status, "failed", JSON.stringify(out));
  assert.match(out.detail, /brain unreachable/);
});

// ---------- bug 4: Stop reaches scheduled runs ----------

test("bug 4: a running scheduled job counts as busy, and stopAll stops it (not recorded as ran)", async () => {
  const rt = runtime(brain((req) => hang(req)));
  const job = rt.jobs.add({ owner: "owner", kind: "once", schedule: new Date(Date.now() + 3_600_000).toISOString(), task: "long scheduled job" }, Date.now());
  const fired = rt.scheduler.fire(job);
  await until(() => rt.state().busy === true);
  rt.stopAll();
  const out = await Promise.race([fired, new Promise<null>((r) => setTimeout(() => r(null), 3000))]);
  assert.ok(out, "stopAll reached the scheduled run");
  assert.notEqual(out!.status, "ran", "a stopped scheduled run is not reported as a successful run");
  assert.match(out!.detail, /[Ss]topped/);
  assert.equal(rt.state().busy, false);
});

test("bug 4: stopScheduledRuns (the app's quit path) stops a running scheduled job", async () => {
  const rt = runtime(brain((req) => hang(req)));
  const job = rt.jobs.add({ owner: "owner", kind: "once", schedule: new Date(Date.now() + 3_600_000).toISOString(), task: "another long job" }, Date.now());
  const fired = rt.scheduler.fire(job);
  await until(() => rt.state().busy === true);
  rt.stopScheduledRuns();
  const out = await Promise.race([fired, new Promise<null>((r) => setTimeout(() => r(null), 3000))]);
  assert.ok(out, "the quit path reached the scheduled run");
  assert.notEqual(out!.status, "ran");
});

// ---------- bug 3: the owner's channel hears hand-offs ----------

test("bug 3: a hand-off is told to the owner's channel the moment it is queued", async () => {
  const b = brain((_req, who) => (who === "child" ? end("child result") : end("unused")));
  const rt = runtime(b);
  const told: Handoff[] = [];
  rt.setChannel({ emit: () => {}, confirm: async () => false, handoff: (h: Handoff) => told.push(h) });
  rt.orchestrator.spawn("a small job", "small");
  await until(() => told.length === 1);
  assert.equal(told[0]!.name, "small");
  assert.equal(told[0]!.state, "done");
  assert.equal(told[0]!.result, "child result");
  assert.equal(rt.orchestrator.drainHandoffs().length, 1, "the hand-off still waits for the parent's next turn");
});

test("bug 3: settleChildren waits (bounded) for running children, then stops the rest", async () => {
  const gate = deferred();
  const b = brain(async (req, who) => {
    if (who !== "child") return end("unused");
    if (req.turns.some((t) => t.role === "user" && t.text.includes("quick"))) { await gate.promise; return end("quick result"); }
    return hang(req);
  });
  const rt = runtime(b);
  rt.orchestrator.spawn("quick job", "quick");
  rt.orchestrator.spawn("endless job", "endless");
  setTimeout(() => gate.resolve(), 20);
  const r = await rt.settleChildren(300);
  assert.equal(r.waited, 2);
  assert.deepEqual(r.stopped, ["endless"], "the one still running at the bound was stopped");
  const states = Object.fromEntries(rt.orchestrator.list().map((c) => [c.name, c.state]));
  assert.deepEqual(states, { quick: "done", endless: "stopped" });
});

// ---------- bug 6: late steering is read, not dropped ----------

test("bug 6: steering sent during a child's last model turn is read before it finishes", async () => {
  const inTurn = deferred();
  const release = deferred();
  const b = brain(async (req, who) => {
    if (who !== "child") return end("unused");
    if (req.turns.some((t) => t.role === "user" && t.text.includes("use the blue one"))) return end("steered: blue");
    inTurn.resolve();
    await release.promise;
    return end("unsteered answer");
  });
  const rt = runtime(b);
  const told: Handoff[] = [];
  rt.setChannel({ emit: () => {}, confirm: async () => false, handoff: (h: Handoff) => told.push(h) });
  const id = rt.orchestrator.spawn("pick a colour", "picker");
  await inTurn.promise;
  assert.equal(rt.orchestrator.steer(id, "use the blue one"), true);
  release.resolve();
  await until(() => rt.orchestrator.list()[0]!.state !== "running");
  const [h] = rt.orchestrator.drainHandoffs();
  assert.equal(h!.state, "done");
  assert.ok(h!.result?.includes("steered: blue"), `the late steer shaped the answer: ${h!.result}`);
});

test("bug 6: a steering message that still arrives too late is named in the hand-off, not silently dropped", async () => {
  const { Orchestrator, formatHandoff } = await import("../src/agent/orchestrator.ts");
  const gate = deferred<string>();
  const o = new Orchestrator({ start: () => ({ done: gate.promise, stop: () => {} }), maxConcurrent: () => 3 });
  const id = o.spawn("a job", "late");
  assert.equal(o.steer(id, "also check the cache"), true);
  gate.resolve("answer without the steer");
  await until(() => o.list()[0]!.state === "done");
  const [h] = o.drainHandoffs();
  assert.deepEqual(h!.unread, ["also check the cache"]);
  assert.match(formatHandoff(h!), /before reading 1 steering message\(s\): "also check the cache"/);
});
