import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { loadConfig, paths } from "../src/config.ts";
import { checkSource, claimsMatch, EcholaliaStore } from "../src/echolalia.ts";
import type { Embedder } from "../src/embeddings.ts";
import { Memory } from "../src/memory.ts";
import { Runtime } from "../src/runtime.ts";
import { ALL_TOOLS, type ToolContext } from "../src/tools/index.ts";

/**
 * Echolalia (verify-before-store, Claim 31): a candidate fact is held
 * pending, source-checked, and only promoted to durable memory on
 * source-pass + echo, or on owner confirmation. Contradictions quarantine.
 */

function rig() {
  const dir = mkdtempSync(path.join(tmpdir(), "jarvis-echo-"));
  const memory = new Memory(path.join(dir, "memory.json"));
  const decisions: Array<{ id: string; decision: string }> = [];
  const store = new EcholaliaStore(path.join(dir, "pending.json"), memory, (e, d) => decisions.push({ id: e.id, decision: d }));
  return { dir, memory, store, decisions };
}

function ctxFor(memory: Memory, echolalia: EcholaliaStore | null): ToolContext {
  return { cwd: "/tmp", workspace: "/tmp", memory, echolalia, embedder: null, vision: null, signal: new AbortController().signal };
}

async function toolRun(name: string, args: Record<string, unknown>, ctx: ToolContext): Promise<string> {
  const t = ALL_TOOLS.find((x) => x.name === name)!;
  return t.run(args as never, ctx);
}

test("source check: numbers/entities must appear in the source; negation flip contradicts", () => {
  assert.equal(checkSource("The workshop has 42 seats", "The workshop has 42 seats in total").result, "pass");
  const mismatch = checkSource("The workshop has 42 seats", "The workshop has 17 seats in total");
  assert.equal(mismatch.result, "fail");
  assert.match(mismatch.reason, /number mismatch/);
  assert.match(mismatch.reason, /42/);
  const entity = checkSource("Ana lives in Lyon", "Ana lives in Paris");
  assert.equal(entity.result, "fail");
  assert.match(entity.reason, /entity mismatch/);
  assert.equal(checkSource("Anything at all", "").result, "unverifiable");
  assert.equal(checkSource("The dose is not 5 mg", "The dose is 5 mg").result, "fail");
});

test("claimsMatch: normalized equality and containment, not mere similarity", () => {
  assert.ok(claimsMatch("Ana lives in Lyon.", "ana lives in lyon"));
  assert.ok(claimsMatch("Ana lives in Lyon", "Reminder: Ana lives in Lyon!"));
  assert.ok(!claimsMatch("Ana lives in Lyon", "Ana lives in Paris"));
});

test("(c) source pass but no echo: stays pending, labelled unverified, not in normal recall", async () => {
  const { memory, store } = rig();
  const out = await store.submit("The workshop has 42 seats", { source: "venue page", sourceText: "The workshop has 42 seats in total" });
  assert.equal(out.entry.status, "pending");
  assert.equal(out.entry.sourceCheck, "pass");
  assert.match(out.message, /UNVERIFIED pending/);
  assert.deepEqual(memory.all(), []);
  assert.deepEqual(memory.recall("workshop seats"), []);
  const held = store.recallUnverified("workshop");
  assert.equal(held.length, 1);
  assert.equal(held[0]!.status, "pending");
});

test("(a) source pass + independent echo: promoted, recallable, provenance recorded", async () => {
  const { memory, store, decisions } = rig();
  await store.submit("The workshop has 42 seats", { source: "venue page", sourceText: "The workshop has 42 seats in total" });
  const echoed = await store.submit("The workshop has 42 seats", { source: "booking email", sourceText: "Confirmed: the workshop has 42 seats" });
  assert.equal(echoed.echo, true);
  assert.equal(echoed.entry.status, "promoted");
  assert.equal(echoed.entry.echoes, 1);
  const facts = memory.recall("workshop seats");
  assert.equal(facts.length, 1);
  assert.match(facts[0]!.provenance ?? "", /echolalia: source check pass/);
  assert.match(facts[0]!.provenance ?? "", /echoes: 1/);
  assert.ok(decisions.some((d) => d.decision === "promoted"));
});

test("(b) numbers contradicting the source: quarantined with reason, never recallable as fact", async () => {
  const { memory, store, decisions } = rig();
  const out = await store.submit("The workshop has 42 seats", { source: "venue page", sourceText: "The workshop has 17 seats in total" });
  assert.equal(out.entry.status, "quarantined");
  assert.match(out.entry.reasons.join(" "), /number mismatch/);
  assert.deepEqual(memory.all(), []);
  assert.deepEqual(memory.recall("workshop"), []);
  assert.equal(store.list("quarantined").length, 1);
  assert.ok(decisions.some((d) => d.decision === "quarantined"));
  // An echo cannot resurrect a quarantined claim on its own.
  const again = await store.submit("The workshop has 42 seats", { source: "other page", sourceText: "The workshop has 42 seats" });
  assert.notEqual(again.entry.status, "promoted");
  assert.deepEqual(memory.all(), []);
});

test("no source text: unverifiable stays pending even after an echo; owner promotion is the way out", async () => {
  const { memory, store } = rig();
  await store.submit("The owner prefers tea in the morning", { source: "conversation" });
  const echo = await store.submit("The owner prefers tea in the morning", { source: "conversation" });
  assert.equal(echo.entry.status, "pending");
  assert.equal(echo.entry.sourceCheck, "unverifiable");
  const promoted = store.promoteByOwner(echo.entry.id);
  assert.equal(promoted!.status, "promoted");
  assert.equal(memory.recall("tea morning").length, 1);
  assert.match(promoted!.reasons.join(" "), /owner confirmed/);
});

test("owner can promote a quarantined entry (override recorded) and drop a pending one", async () => {
  const { memory, store } = rig();
  const bad = await store.submit("The workshop has 42 seats", { source: "venue page", sourceText: "The workshop has 17 seats in total" });
  const promoted = store.promoteByOwner(bad.entry.id);
  assert.equal(promoted!.status, "promoted");
  assert.match(promoted!.reasons.join(" "), /owner confirmed \(overrides failed source check\)/);
  assert.equal(memory.all().length, 1);

  const held = await store.submit("Trains leave at 9", { source: "timetable", sourceText: "Trains leave at 9 daily" });
  assert.equal(store.drop(held.entry.id)!.status, "dropped");
  assert.equal(store.list("pending").length, 0);
  assert.equal(store.byId("nope"), undefined);
});

test("pending store persists across instances, same conventions as Memory", async () => {
  const { dir, memory, store } = rig();
  const out = await store.submit("The workshop has 42 seats", { source: "venue page", sourceText: "The workshop has 42 seats in total" });
  const reloaded = new EcholaliaStore(path.join(dir, "pending.json"), memory);
  assert.equal(reloaded.list("pending").length, 1);
  assert.equal(reloaded.list("pending")[0]!.id, out.entry.id);
  assert.ok(existsSync(path.join(dir, "pending.json")));
});

test("echo by embedding similarity when an embedder is configured", async () => {
  const { memory, store } = rig();
  // Deterministic stand-in embedder: claims about seats share a vector.
  const fake = {
    id: "fake@full",
    embedOne: async (s: string) => (s.toLowerCase().includes("seat") ? [1, 0] : [0, 1]),
  } as unknown as Embedder;
  await store.submit("The workshop has 42 seats", { source: "venue page", sourceText: "The workshop has 42 seats in total" }, fake);
  const paraphrase = await store.submit("The workshop can seat 42 people", { source: "booking email", sourceText: "The workshop can seat 42 people" }, fake);
  // "can seat 42" has no shared numbers problem (42 is in its source) and
  // its normalized text does not contain the original — only the vector
  // path can match it as an echo of the first entry.
  assert.equal(paraphrase.echo, true);
  assert.equal(paraphrase.entry.status, "promoted");
  assert.equal(memory.all().length, 1);
});

test("remember tool routes through the pipeline only when the store is in the context", async () => {
  const { memory, store } = rig();
  const onCtx = ctxFor(memory, store);
  const first = await toolRun("remember", { fact: "The workshop has 42 seats", source: "venue page", source_text: "The workshop has 42 seats in total" }, onCtx);
  assert.match(first, /UNVERIFIED pending/);
  assert.deepEqual(memory.all(), []);
  const second = await toolRun("remember", { fact: "The workshop has 42 seats", source: "booking email", source_text: "Confirmed: the workshop has 42 seats" }, onCtx);
  assert.match(second, /Verified and remembered/);
  assert.equal(memory.all().length, 1);

  const listed = await toolRun("memory_pending", {}, onCtx);
  assert.match(listed, /No pending or quarantined/);
  const decided = await toolRun("memory_decide", { id: "zzzz", action: "drop" }, onCtx);
  assert.match(decided, /No pending entry/);

  // Off (no store in context): direct write, exactly as before.
  const offCtx = ctxFor(memory, null);
  const direct = await toolRun("remember", { fact: "The owner likes tea" }, offCtx);
  assert.match(direct, /^Remembered \(/);
  const offList = await toolRun("memory_pending", {}, offCtx);
  assert.match(offList, /verify-before-store is off/i);
});

test("config: verifyBeforeStore defaults off and loads when set; Runtime exposes the store state", () => {
  const dir = mkdtempSync(path.join(tmpdir(), "jarvis-echo-cfg-"));
  const p = paths(dir);
  const base = loadConfig(p);
  assert.equal(base.memory.verifyBeforeStore, false);
  const rt = new Runtime(base, p, dir);
  assert.equal(rt.state().verifyBeforeStore, false);
  rt.update({ verifyBeforeStore: true });
  assert.equal(loadConfig(p).memory.verifyBeforeStore, true);
  assert.equal(rt.state().verifyBeforeStore, true);
});

const PKG = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const CLI = path.join(PKG, "src", "cli.ts");

function runCli(args: string[], home: string): Promise<{ status: number | null; out: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ["--import", "tsx", CLI, ...args], {
      cwd: PKG,
      env: { PATH: process.env.PATH ?? "", HOME: process.env.HOME ?? "", JARVIS_HOME: home },
    });
    let buf = "";
    child.stdout.on("data", (d) => (buf += d));
    child.stderr.on("data", (d) => (buf += d));
    const timer = setTimeout(() => { child.kill("SIGKILL"); reject(new Error(`CLI timed out: ${args.join(" ")}\n${buf}`)); }, 60_000);
    child.on("error", (e) => { clearTimeout(timer); reject(e); });
    child.on("close", (status) => { clearTimeout(timer); resolve({ status, out: buf }); });
    child.stdin.end("");
  });
}

test("CLI: jarvis memory submit/pending/quarantined/promote/drop drive the store", { timeout: 120_000 }, async () => {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-echo-cli-"));
  const bad = await runCli(["memory", "submit", "The workshop has 42 seats", "--source", "venue page", "--source-text", "The workshop has 17 seats in total"], home);
  assert.equal(bad.status, 0, bad.out);
  assert.match(bad.out, /Quarantined, NOT remembered as fact/);
  const q = await runCli(["memory", "quarantined"], home);
  assert.match(q.out, /QUARANTINED/);
  assert.match(q.out, /number mismatch/);
  const id = /\(([0-9a-f]{8})\)/.exec(q.out)![1]!;
  const promoted = await runCli(["memory", "promote", id], home);
  assert.match(promoted.out, /Promoted/);
  const mem = JSON.parse(readFileSync(path.join(home, "memory.json"), "utf8")) as { facts: Array<{ text: string; provenance?: string }> };
  assert.equal(mem.facts.length, 1);
  assert.match(mem.facts[0]!.provenance ?? "", /echolalia/);
  const audit = readFileSync(path.join(home, "audit.jsonl"), "utf8");
  assert.match(audit, /"decision":"quarantined"/);
  assert.match(audit, /"decision":"promoted"/);

  const held = await runCli(["memory", "submit", "Trains leave at 9", "--source", "timetable", "--source-text", "Trains leave at 9 daily"], home);
  assert.match(held.out, /UNVERIFIED pending/);
  const heldId = /\(([0-9a-f]{8})\)/.exec(held.out)![1]!;
  const dropped = await runCli(["memory", "drop", heldId], home);
  assert.match(dropped.out, /Dropped/);
  const pending = await runCli(["memory", "pending"], home);
  assert.match(pending.out, /No pending candidate facts/);
});
