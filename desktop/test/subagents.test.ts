import assert from "node:assert/strict";
import { existsSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { Orchestrator, formatHandoff, type ChildHandle, type ChildRun } from "../src/agent/orchestrator.ts";
import { defaults, paths } from "../src/config.ts";
import { Runtime } from "../src/runtime.ts";
import type { Provider, Turn, TurnRequest, TurnResult } from "../src/providers/types.ts";

// ---------- helpers ----------

function deferred<T = void>() {
  let resolve!: (v: T | PromiseLike<T>) => void;
  let reject!: (e?: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

async function until(fn: () => boolean, ms = 8000): Promise<void> {
  const t0 = Date.now();
  while (!fn()) {
    if (Date.now() - t0 > ms) throw new Error("until: timed out");
    await new Promise((r) => setTimeout(r, 5));
  }
}

const hasHandoff = (turns: Turn[], name?: string): boolean =>
  turns.some((t) => t.role === "user" && t.text.includes("[Hand-off from subagent" + (name ? ` "${name}"` : "")));
const usedTool = (turns: Turn[], name: string): boolean =>
  turns.some((t) => t.role === "tool" && t.results.some((r) => r.name === name));
const toolSaid = (turns: Turn[], name: string, phrase: string): boolean =>
  turns.some((t) => t.role === "tool" && t.results.some((r) => r.name === name && r.content.includes(phrase)));
const allText = (turns: Turn[]): string => turns.map((t) => (t.role === "tool" ? "" : t.text)).join("\n");

// ---------- orchestrator (phone-semantics port) ----------

/** A starter whose children run only when the test says so. */
function fakeStarter() {
  const started: Array<{ handle: ChildHandle; gate: ReturnType<typeof deferred<string>>; stops: number }> = [];
  const start = (handle: ChildHandle): ChildRun => {
    const rec = { handle, gate: deferred<string>(), stops: 0 };
    started.push(rec);
    return { done: rec.gate.promise, stop: () => { rec.stops++; } };
  };
  return { start, started };
}

function orch(max = 3) {
  const fake = fakeStarter();
  const handoffs: string[] = [];
  const o = new Orchestrator({
    start: fake.start,
    maxConcurrent: () => max,
    onHandoff: (h) => handoffs.push(h.id),
  });
  return { o, fake, handoffs };
}

test("spawn: ids, names, listing", () => {
  const { o } = orch();
  const id = o.spawn("research prices", "prices");
  assert.match(id, /^agent:[0-9a-f]{8}$/);
  const anon = o.spawn("unnamed job");
  const all = o.list();
  assert.equal(all.length, 2);
  assert.equal(all[0]!.name, "prices");
  assert.equal(all[0]!.state, "running");
  assert.equal(all[0]!.task, "research prices");
  assert.equal(all[1]!.name, anon, "an unnamed child is named by its id");
  const dup = o.spawn("more prices", "prices");
  assert.equal(o.list().find((c) => c.id === dup)!.name, "prices-2", "duplicate names are uniquified");
});

test("spawn refuses a blank task and any nesting (depth cap 1)", () => {
  const { o } = orch();
  assert.throws(() => o.spawn("   "), /needs a task/);
  assert.throws(() => o.spawn("nested", undefined, 1), /cannot spawn subagents/);
});

test("the concurrency cap refuses (never queues), and frees up as children settle", async () => {
  const { o, fake } = orch(2);
  o.spawn("one");
  o.spawn("two");
  assert.throws(() => o.spawn("three"), /Too many subagents running \(2 of at most 2\)/);
  fake.started[0]!.gate.resolve("done one");
  await until(() => o.list()[0]!.state === "done");
  o.spawn("three");
  assert.equal(o.list().filter((c) => c.state === "running").length, 2);
});

test("steer queues into the child's inbox, in order; only while running", async () => {
  const { o, fake } = orch();
  const id = o.spawn("steerable", "s");
  assert.equal(o.steer(id, "first"), true);
  assert.equal(o.steer("s", "second"), true, "steering by name works");
  assert.deepEqual(fake.started[0]!.handle.drainInbox(), ["first", "second"]);
  assert.deepEqual(fake.started[0]!.handle.drainInbox(), [], "the inbox drains once");
  assert.equal(o.steer("agent:nope", "x"), false);
  fake.started[0]!.gate.resolve("done");
  await until(() => o.list()[0]!.state === "done");
  assert.equal(o.steer(id, "too late"), false, "a finished child takes no steering");
});

test("stop: the child is stopped and hands off as stopped, with what it last said", async () => {
  const { o, fake, handoffs } = orch();
  const id = o.spawn("long job", "slow");
  assert.equal(o.stop(id), true);
  assert.equal(fake.started[0]!.stops, 1);
  fake.started[0]!.gate.resolve("partial findings");
  await until(() => o.list()[0]!.state === "stopped");
  assert.deepEqual(handoffs, [id], "the hand-off fired as it settled");
  const [h] = o.drainHandoffs();
  assert.equal(h!.state, "stopped");
  assert.match(formatHandoff(h!), /STOPPED \(not verified\)/);
  assert.match(formatHandoff(h!), /partial findings/);
  assert.deepEqual(o.drainHandoffs(), [], "hand-offs drain once");
  assert.equal(o.stop(id), false, "a settled child cannot be stopped again");
});

test("failure hands off as failed, unverified, with the error", async () => {
  const { o, fake } = orch();
  o.spawn("doomed", "d");
  fake.started[0]!.gate.reject(new Error("boom"));
  await until(() => o.list()[0]!.state === "failed");
  const [h] = o.drainHandoffs();
  assert.equal(h!.state, "failed");
  assert.equal(h!.error, "boom");
  assert.equal(h!.verified, false);
  assert.equal(h!.verifyNote, "not verified");
  assert.match(formatHandoff(h!), /FAILED \(not verified\)[\s\S]*boom/);
});

test("hand-offs arrive in completion order, not spawn order", async () => {
  const { o, fake, handoffs } = orch();
  const a = o.spawn("first spawned", "a");
  const b = o.spawn("second spawned", "b");
  fake.started[1]!.gate.resolve("b result");
  await until(() => o.list().find((c) => c.id === b)!.state === "done");
  fake.started[0]!.gate.resolve("a result");
  await until(() => o.list().find((c) => c.id === a)!.state === "done");
  assert.deepEqual(handoffs, [b, a]);
  const drained = o.drainHandoffs();
  assert.deepEqual(drained.map((h) => h.id), [b, a]);
  assert.match(formatHandoff(drained[0]!), /"b"[\s\S]*b result/);
});

test("stopAll stops every running child and no settled one", async () => {
  const { o, fake } = orch();
  o.spawn("x");
  o.spawn("y");
  fake.started[0]!.gate.resolve("x done");
  await until(() => o.list()[0]!.state === "done");
  o.spawn("z");
  o.stopAll();
  assert.equal(fake.started[0]!.stops, 0, "the finished child is untouched");
  assert.equal(fake.started[1]!.stops, 1);
  assert.equal(fake.started[2]!.stops, 1);
});

test("a starter that throws fails the child with a hand-off, not a crash", () => {
  const o = new Orchestrator({
    start: () => { throw new Error("no starter"); },
    maxConcurrent: () => 3,
  });
  const id = o.spawn("anything");
  assert.equal(o.list()[0]!.state, "failed");
  const [h] = o.drainHandoffs();
  assert.equal(h!.id, id);
  assert.equal(h!.error, "no starter");
});

// ---------- full stack: real Runtime, fake brain ----------

const end = (text: string): TurnResult => ({ text, stop: "end", toolCalls: [] });
const callTool = (id: string, name: string, input: Record<string, unknown>): TurnResult =>
  ({ text: "", stop: "tool_use", toolCalls: [{ id, name, input }] });

type BrainFn = (req: TurnRequest, who: "parent" | "child") => TurnResult | Promise<TurnResult>;

function brain(fn: BrainFn): Provider {
  return {
    kind: "local",
    label: "fake brain",
    async turn(req) {
      // A real provider takes network time; yielding to the event loop here
      // keeps a polling parent from starving a child's filesystem calls.
      await new Promise((r) => setTimeout(r, 1));
      const who = req.system.includes("You are a subagent") ? "child" : "parent";
      return fn(req, who);
    },
  };
}

function runtime(b: Provider, tune?: (rt: Runtime) => void) {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-sub-"));
  const p = paths(home);
  const rt = new Runtime(defaults(p), p, home);
  (rt as unknown as { provider: () => Provider }).provider = () => b;
  tune?.(rt);
  return { rt, home, p };
}

const allowAll = { emit: () => {}, confirm: async () => true };
const childTask = (req: TurnRequest): string => req.turns.find((t) => t.role === "user")?.text ?? "";

test("full stack: two children run concurrently; hand-offs reach the parent in completion order", async () => {
  const gateA = deferred<void>();
  const gateB = deferred<void>();
  let rtRef!: Runtime;
  const b = brain(async (req, who) => {
    if (who === "child") {
      const task = childTask(req);
      if (task.includes("alpha")) { await gateA.promise; return end("alpha result"); }
      await gateB.promise;
      return end("beta result");
    }
    if (hasHandoff(req.turns, "alpha") && hasHandoff(req.turns, "beta")) return end("Both reported.");
    if (!usedTool(req.turns, "spawn_agent")) {
      return {
        text: "", stop: "tool_use",
        toolCalls: [
          { id: "s1", name: "spawn_agent", input: { task: "alpha task", name: "alpha" } },
          { id: "s2", name: "spawn_agent", input: { task: "beta task", name: "beta" } },
        ],
      };
    }
    // Wait inside the turn until both children settled, then take one more
    // step (whose tool results let the loop drain both hand-offs in).
    await until(() => rtRef.orchestrator.list().filter((c) => c.state === "done").length === 2);
    return callTool("st", "agent_status", {});
  });
  const { rt } = runtime(b);
  rtRef = rt;
  const runP = rt.agent.run("delegate both jobs", allowAll);
  await until(() => rt.orchestrator.list().filter((c) => c.state === "running").length === 2);
  gateB.resolve();
  await until(() => rt.orchestrator.list().find((c) => c.name === "beta")!.state === "done");
  gateA.resolve();
  const answer = await runP;
  assert.equal(answer, "Both reported.");
  const all = allText(rt.agent.turns);
  const betaAt = all.indexOf("[Hand-off from subagent \"beta\"");
  const alphaAt = all.indexOf("[Hand-off from subagent \"alpha\"");
  assert.ok(betaAt > -1 && alphaAt > -1, "both hand-offs are in the parent's conversation");
  assert.ok(betaAt < alphaAt, "beta finished first, so its hand-off lands first");
  assert.ok(all.includes("DONE (not verified)"), "the hand-off carries its unverified verdict");
  assert.deepEqual(rt.orchestrator.drainHandoffs(), [], "the parent consumed the hand-offs");
});

test("full stack: steering reaches the child's next step and shapes its answer", async () => {
  const b = brain((req, who) => {
    if (who === "child") {
      const steered = req.turns.some((t) => t.role === "user" && t.text.includes("focus on prices"));
      if (steered) return end("steered: focus on prices");
      return callTool("c1", "list_dir", { path: "." });
    }
    if (hasHandoff(req.turns)) return end("steering landed");
    if (!usedTool(req.turns, "spawn_agent")) return callTool("s1", "spawn_agent", { task: "steerable job", name: "steerable" });
    if (!usedTool(req.turns, "steer_agent")) return callTool("s2", "steer_agent", { agent: "steerable", message: "focus on prices" });
    return callTool("s3", "agent_status", {});
  });
  const { rt } = runtime(b);
  const answer = await rt.agent.run("delegate and steer it", allowAll);
  assert.equal(answer, "steering landed");
  assert.ok(allText(rt.agent.turns).includes("steered: focus on prices"), "the child's answer reflects the steering");
});

test("full stack: a gate denial inside a child is honored, and its card is attributed", async () => {
  const cards: Array<{ tool: string; reason: string }> = [];
  let target = "";
  const b = brain((req, who) => {
    if (who === "child") {
      if (toolSaid(req.turns, "write_file", "The owner declined this.")) return end("the owner declined the write");
      return callTool("w1", "write_file", { path: target, content: "x" });
    }
    if (hasHandoff(req.turns)) return end("child reported");
    if (!usedTool(req.turns, "spawn_agent")) return callTool("s1", "spawn_agent", { task: "write the file", name: "writer" });
    return callTool("s2", "agent_status", {});
  });
  const { rt, home } = runtime(b);
  target = path.join(home, "evil.txt"); // in the home folder, outside the workspace the gate protects
  rt.setChannel({
    emit: () => {},
    confirm: async (card) => { cards.push({ tool: card.tool, reason: card.reason }); return false; },
  });
  const answer = await rt.agent.run("have the writer do it", allowAll);
  assert.equal(answer, "child reported");
  assert.equal(existsSync(target), false, "the declined write never happened");
  const writeCard = cards.find((c) => c.tool === "write_file");
  assert.ok(writeCard, "the child's card reached the owner channel");
  assert.ok(writeCard!.reason.includes("via subagent \"writer\""), `the card is attributed: ${writeCard!.reason}`);
  assert.ok(allText(rt.agent.turns).includes("the owner declined the write"), "the denial is in the child's hand-off");
});

test("full stack: stop_agent stops a child mid-run and its hand-off says stopped", async () => {
  const b = brain(async (req, who) => {
    if (who === "child") {
      await new Promise<never>((_res, rej) => {
        if (req.signal.aborted) rej(new Error("aborted"));
        req.signal.addEventListener("abort", () => rej(new Error("aborted")), { once: true });
      });
      return end("unreachable");
    }
    if (hasHandoff(req.turns)) return end("saw the stop hand-off");
    if (!usedTool(req.turns, "spawn_agent")) return callTool("s1", "spawn_agent", { task: "slow job", name: "slow" });
    if (!usedTool(req.turns, "stop_agent")) return callTool("s2", "stop_agent", { agent: "slow" });
    return callTool("s3", "agent_status", {});
  });
  const { rt } = runtime(b);
  const answer = await rt.agent.run("start it, then stop it", allowAll);
  assert.equal(answer, "saw the stop hand-off");
  assert.equal(rt.orchestrator.list().find((c) => c.name === "slow")!.state, "stopped");
  assert.ok(allText(rt.agent.turns).includes("STOPPED (not verified)"));
});

test("full stack: stopping the parent stops its children too", async () => {
  const hang = async (req: TurnRequest): Promise<TurnResult> => {
    await new Promise<never>((_res, rej) => {
      if (req.signal.aborted) rej(new Error("aborted"));
      req.signal.addEventListener("abort", () => rej(new Error("aborted")), { once: true });
    });
    return end("unreachable");
  };
  const b = brain((req, who) => {
    if (who === "child") return hang(req);
    if (!usedTool(req.turns, "spawn_agent")) return callTool("s1", "spawn_agent", { task: "slow job", name: "slow" });
    return hang(req);
  });
  const { rt } = runtime(b);
  const runP = rt.agent.run("start a slow child and keep working", allowAll);
  await until(() => rt.orchestrator.list().some((c) => c.state === "running"));
  rt.stopAll();
  assert.equal(await runP, "Stopped.");
  await until(() => rt.orchestrator.list()[0]!.state === "stopped");
  const [h] = rt.orchestrator.drainHandoffs();
  assert.equal(h!.state, "stopped", "the stopped child's hand-off waits for the parent's next turn");
});

test("full stack: the concurrency cap refuses a second spawn while one runs", async () => {
  const gate = deferred<void>();
  const b = brain(async (req, who) => {
    if (who === "child") { await gate.promise; return end("only child done"); }
    if (toolSaid(req.turns, "spawn_agent", "Too many subagents running")) return end("cap seen");
    if (!usedTool(req.turns, "spawn_agent")) return callTool("s1", "spawn_agent", { task: "the one job", name: "only" });
    return callTool("s2", "spawn_agent", { task: "one too many", name: "extra" });
  });
  const { rt } = runtime(b, (r) => { r.cfg.subagents.maxConcurrent = 1; });
  const answer = await rt.agent.run("try to over-spawn", allowAll);
  assert.equal(answer, "cap seen");
  const refused = rt.agent.turns.some((t) => t.role === "tool" && t.results.some((r) => r.isError && r.content.includes("Too many subagents")));
  assert.ok(refused, "the refusal is an error result the parent can read");
  gate.resolve();
  await until(() => rt.orchestrator.list().find((c) => c.name === "only")!.state === "done");
});

test("full stack: a child has no spawn_agent — nesting is refused by construction", async () => {
  let childTools: string[] = [];
  const b = brain((req, who) => {
    if (who === "child") {
      childTools = req.tools.map((t) => t.name);
      if (toolSaid(req.turns, "spawn_agent", "No tool named spawn_agent")) return end("nested spawn refused");
      return callTool("n1", "spawn_agent", { task: "nested job" });
    }
    if (hasHandoff(req.turns)) return end("child reported");
    if (!usedTool(req.turns, "spawn_agent")) return callTool("s1", "spawn_agent", { task: "try to nest", name: "recursor" });
    return callTool("s2", "agent_status", {});
  });
  const { rt } = runtime(b);
  const answer = await rt.agent.run("delegate to a would-be nester", allowAll);
  assert.equal(answer, "child reported");
  assert.ok(allText(rt.agent.turns).includes("nested spawn refused"));
  for (const t of ["spawn_agent", "agent_status", "steer_agent", "stop_agent"]) {
    assert.ok(!childTools.includes(t), `the child's tools exclude ${t}`);
  }
});
