import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import type { Embedder } from "../src/embeddings.ts";
import { Memory, temporalFactor, type Fact } from "../src/memory.ts";
import { ALL_TOOLS, type ToolContext } from "../src/tools/index.ts";

/**
 * The claims machinery: kind / salience / decay / supersede, ported from the
 * phone harness memory plane (harness/memory.py). All time travel goes
 * through the injected clock — no wall-clock sleeps.
 */

const T0 = Date.parse("2026-10-20T00:00:00.000Z");
const DAY = 86_400_000;

function clocked(start = T0) {
  let nowMs = start;
  return {
    memory: (file: string | null = null) => new Memory(file, () => new Date(nowMs)),
    advance: (ms: number) => { nowMs += ms; },
    now: () => nowMs,
  };
}

const stubEmbedder = {
  id: "stub@4",
  async embed(xs: string[]) { return xs.map(() => [1, 0, 0, 0]); },
  async embedOne() { return [1, 0, 0, 0]; },
} as unknown as Embedder;

// ---------- kind & salience ----------

test("kind defaults to fact; an explicit kind is kept; salience defaults by kind", () => {
  const { memory } = clocked();
  const m = memory();
  assert.equal(m.remember("owner likes tea").kind, "fact");
  assert.equal(m.remember("owner prefers dark roast", { kind: "preference" }).kind, "preference");
  assert.equal(m.remember("actually the meeting is Tuesday", { kind: "correction" }).salience, "high");
  assert.equal(m.remember("noticed the shop opens early", { kind: "observation" }).salience, "low");
  assert.equal(m.remember("sent the invoice", { kind: "completion" }).salience, "low");
  assert.equal(m.remember("explicit wins", { kind: "observation", salience: "high" }).salience, "high");
});

test("salience breaks ties in recall ranking", () => {
  const { memory } = clocked();
  const m = memory();
  m.remember("tea is the morning drink", { salience: "low" });
  m.remember("tea is the evening drink", { salience: "high" });
  const hits = m.recall("tea drink");
  assert.equal(hits.length, 2);
  assert.equal(hits[0]!.text, "tea is the evening drink");
});

test("salience breaks ties in semantic search ranking", async () => {
  const { memory } = clocked();
  const m = memory();
  m.remember("alpha bravo charlie", { salience: "low" });
  m.remember("alpha bravo charlie delta", { salience: "high" });
  const hits = await m.search("alpha", stubEmbedder);
  assert.equal(hits[0]!.salience, "high");
});

// ---------- decay ----------

test("temporalFactor: fresh is 1, halves every 30 days, evergreen never decays", () => {
  const { memory, now } = clocked();
  const m = memory();
  const fresh = m.remember("fresh fact about tea");
  assert.equal(temporalFactor(fresh, now()), 1);
  assert.ok(Math.abs(temporalFactor(fresh, now() + 30 * DAY) - 0.5) < 1e-9);
  assert.ok(Math.abs(temporalFactor(fresh, now() + 60 * DAY) - 0.25) < 1e-9);
  const ever = m.remember("owner's name is Rafael", { evergreen: true });
  assert.equal(temporalFactor(ever, now() + 3650 * DAY), 1);
});

test("decay sinks an unreinforced fact in ranking but never deletes it", () => {
  const c = clocked();
  const m = c.memory();
  const old = m.remember("tea export report");
  c.advance(60 * DAY);
  const fresh = m.remember("tea import report");
  const hits = m.recall("tea report");
  assert.deepEqual(hits.map((f) => f.id), [fresh.id, old.id]);
  // Ten years on, the fact is still stored and still recallable when it is
  // the only match — decay sinks, it does not delete.
  c.advance(3650 * DAY);
  assert.ok(m.all().some((f) => f.id === old.id));
  const only = m.recall("export");
  assert.deepEqual(only.map((f) => f.id), [old.id]);
});

test("reinforcement resets decay and promotes salience one tier", () => {
  const c = clocked();
  const m = c.memory();
  const f = m.remember("tea ceremony on Sundays", { salience: "low" });
  c.advance(90 * DAY);
  assert.ok(temporalFactor(f, c.now()) < 0.13);
  assert.equal(m.reinforce(f.id), true);
  assert.equal(temporalFactor(f, c.now()), 1);
  assert.equal(f.salience, "medium");
  assert.equal(m.reinforce(f.id), true);
  assert.equal(f.salience, "high");
  assert.equal(m.reinforce("no-such-id"), false);
});

test("re-remembering the same fact reinforces instead of duplicating", () => {
  const c = clocked();
  const m = c.memory();
  const first = m.remember("owner drinks tea");
  c.advance(45 * DAY);
  const again = m.remember("OWNER DRINKS TEA");
  assert.equal(again.id, first.id);
  assert.equal(m.all().length, 1);
  assert.equal(temporalFactor(again, c.now()), 1);
});

// ---------- supersede ----------

test("supersede: links both ways, old fact leaves default recall, chain resolves", () => {
  const { memory } = clocked();
  const m = memory();
  const a = m.remember("owner lives in Lyon");
  const b = m.remember("owner lives in Victoria", { supersedes: a.id });
  assert.equal(b.supersedes, a.id);
  assert.equal(a.supersededBy, b.id);
  assert.ok(a.invalidatedAt);
  assert.deepEqual(m.recall("lives").map((f) => f.id), [b.id]);
  assert.deepEqual(
    m.recall("lives", 8, { includeSuperseded: true }).map((f) => f.id).sort(),
    [a.id, b.id].sort(),
  );
  const c3 = m.remember("owner lives in Tofino", { supersedes: b.id });
  assert.deepEqual(m.recall("lives").map((f) => f.id), [c3.id]);
  assert.deepEqual(m.chain(c3.id).map((f) => f.id), [c3.id, b.id, a.id]);
  assert.equal(m.reinforce(a.id), false); // superseded facts can't be reinforced
});

test("superseded facts are excluded from semantic search by default", async () => {
  const { memory } = clocked();
  const m = memory();
  const a = m.remember("alpha bravo");
  m.remember("alpha bravo charlie", { supersedes: a.id });
  const hits = await m.search("alpha", stubEmbedder);
  assert.ok(hits.every((f) => !f.supersededBy));
  const all = await m.search("alpha", stubEmbedder, 8, { includeSuperseded: true });
  assert.equal(all.length, 2);
});

test("forget can still reach a superseded fact, by id and by text", () => {
  const { memory } = clocked();
  const m = memory();
  const a = m.remember("owner's old address is 1 Gone Street");
  m.remember("owner's address is 2 New Street", { supersedes: a.id });
  assert.deepEqual(m.forget(a.id).map((f) => f.id), [a.id]);
  const b = m.remember("owner's old phone was 111");
  m.remember("owner's phone is 222", { supersedes: b.id });
  assert.deepEqual(m.forget("old phone 111").map((f) => f.id), [b.id]);
});

// ---------- migration & persistence ----------

test("old-format store migrates losslessly: defaults filled, file re-saved", () => {
  const dir = mkdtempSync(path.join(tmpdir(), "jarvis-claims-"));
  const file = path.join(dir, "memory.json");
  writeFileSync(file, JSON.stringify({
    facts: [{ id: "old1", text: "owner likes tea", at: "2026-01-01T00:00:00.000Z" }],
    trash: [{ id: "old2", text: "a forgotten thing", at: "2026-01-02T00:00:00.000Z" }],
  }));
  const m = new Memory(file, () => new Date(T0));
  const f = m.all()[0]!;
  assert.equal(f.kind, "fact");
  assert.equal(f.salience, "medium");
  assert.equal(f.reinforcedAt, "2026-01-01T00:00:00.000Z");
  assert.equal(f.text, "owner likes tea");
  const onDisk = JSON.parse(readFileSync(file, "utf8")) as { facts: Fact[] };
  assert.equal(onDisk.facts[0]!.kind, "fact");
  assert.equal(onDisk.facts[0]!.reinforcedAt, "2026-01-01T00:00:00.000Z");
  assert.deepEqual(m.recall("tea").map((x) => x.id), ["old1"]);
});

test("claims fields survive a persistence round-trip", () => {
  const dir = mkdtempSync(path.join(tmpdir(), "jarvis-claims-"));
  const file = path.join(dir, "memory.json");
  const clock = () => new Date(T0);
  const m1 = new Memory(file, clock);
  const a = m1.remember("owner's name is Rafael", { kind: "preference", salience: "high", evergreen: true });
  const b = m1.remember("owner's name is Rafael C.", { kind: "correction", supersedes: a.id });
  const m2 = new Memory(file, clock);
  const a2 = m2.byId(a.id)!;
  const b2 = m2.byId(b.id)!;
  assert.equal(a2.kind, "preference");
  assert.equal(a2.salience, "high");
  assert.equal(a2.evergreen, true);
  assert.equal(a2.supersededBy, b.id);
  assert.equal(b2.supersedes, a.id);
  assert.equal(b2.kind, "correction");
});

// ---------- tool surface ----------

function ctxWith(memory: Memory): ToolContext {
  return {
    cwd: "/tmp", workspace: "/tmp", embedder: null, vision: null,
    signal: new AbortController().signal, memory,
  } as unknown as ToolContext;
}

test("tools: remember passes kind/supersedes; recall marks kind + supersede links and reinforces", async () => {
  const c = clocked();
  const m = c.memory();
  const ctx = ctxWith(m);
  const remember = ALL_TOOLS.find((t) => t.name === "remember")!;
  const recall = ALL_TOOLS.find((t) => t.name === "recall")!;
  const out1 = await remember.run({ fact: "owner lives in Lyon" }, ctx);
  const a = m.all()[0]!;
  assert.ok(out1.includes(a.id));
  const out2 = await remember.run({ fact: "owner lives in Victoria", kind: "correction", supersedes: a.id }, ctx);
  assert.ok(out2.includes(`supersedes ${a.id}`));
  c.advance(40 * DAY);
  const hits = await recall.run({ query: "lives", includeSuperseded: true }, ctx);
  assert.ok(hits.includes("[correction] owner lives in Victoria"));
  assert.ok(hits.includes("superseded by"));
  const active = m.all().find((f) => !f.supersededBy)!;
  assert.equal(temporalFactor(active, c.now()), 1); // recall-and-use reinforced it
  const hidden = await recall.run({ query: "lives" }, ctx);
  assert.ok(!hidden.includes("Lyon"));
});
