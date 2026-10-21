import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { claimsMatch, EcholaliaStore } from "../src/echolalia.ts";
import type { Embedder } from "../src/embeddings.ts";
import { Memory } from "../src/memory.ts";

/**
 * Regressions for the memory / echolalia fixes of 2026-10-21: re-remembering
 * a superseded fact, supersede cycles, whole-token echo matching, per-echo
 * source checks, cross-process writes, and owner decisions on settled
 * entries. Time goes through the injected clock, as in claims.test.ts.
 */

const T0 = Date.parse("2026-10-20T00:00:00.000Z");
const DAY = 86_400_000;

function clocked(start = T0) {
  let nowMs = start;
  return {
    memory: (file: string | null = null) => new Memory(file, () => new Date(nowMs)),
    advance: (ms: number) => { nowMs += ms; },
  };
}

const tmp = (tag: string) => mkdtempSync(path.join(tmpdir(), `jarvis-memfix-${tag}-`));

// ---------- bug 1: re-remembering a superseded fact ----------

test("re-remembering a superseded text is a new assertion: a fresh, recallable fact", () => {
  const { memory, advance } = clocked();
  const m = memory();
  const old = m.remember("the office code is blue");
  advance(DAY);
  const newer = m.remember("the office code is green", { supersedes: old.id });
  advance(DAY);
  const again = m.remember("the office code is blue");
  assert.notEqual(again.id, old.id, "a fresh fact, the superseded one stays as history");
  assert.equal(again.supersededBy, undefined);
  assert.deepEqual(m.recall("office code").map((f) => f.id).sort(), [again.id, newer.id].sort());
  assert.equal(m.byId(old.id)!.supersededBy, newer.id, "history untouched");
});

test("re-remembering a superseded text with supersedes: <the newer id> recalls it, no cycle", () => {
  const { memory, advance } = clocked();
  const m = memory();
  const a = m.remember("the office code is blue");
  advance(DAY);
  const b = m.remember("the office code is green", { supersedes: a.id });
  advance(DAY);
  const a2 = m.remember("the office code is blue", { supersedes: b.id });
  const hits = m.recall("office code");
  assert.deepEqual(hits.map((f) => f.id), [a2.id], "the re-asserted fact is the one recalled");
  assert.equal(m.byId(b.id)!.supersededBy, a2.id);
  assert.deepEqual(m.chain(a2.id).map((f) => f.id), [a2.id, b.id, a.id], "a straight history, not a loop");
});

test("a supersede link that would close a cycle is refused", () => {
  const dir = tmp("cycle");
  const file = path.join(dir, "memory.json");
  const at = new Date(T0).toISOString();
  // Hand-edited / legacy data: p says it replaced q, but q was never marked.
  writeFileSync(file, JSON.stringify({
    facts: [
      { id: "q0000000", text: "q fact", at, kind: "fact", salience: "medium", reinforcedAt: at },
      { id: "p0000000", text: "p fact", at, kind: "fact", salience: "medium", reinforcedAt: at, supersedes: "q0000000" },
    ],
    trash: [],
  }));
  const { memory } = clocked();
  const m = memory(file);
  assert.throws(() => m.remember("q fact", { supersedes: "p0000000" }), /cycle/);
  assert.equal(m.byId("q0000000")!.supersedes, undefined);
  assert.equal(m.byId("p0000000")!.supersededBy, undefined);
  assert.equal(m.recall("fact").length, 2, "both still recallable");
});

test("echolalia promote of a superseded text points factId at a recallable fact", () => {
  const { memory } = clocked();
  const m = memory();
  const a = m.remember("Trains leave at 9");
  m.remember("Trains leave at 10", { supersedes: a.id });
  const store = new EcholaliaStore(null, m);
  return store.submit("Trains leave at 9", { source: "conversation" }).then((out) => {
    const e = store.promoteByOwner(out.entry.id)!;
    assert.equal(e.status, "promoted");
    assert.ok(m.recall("trains leave").some((f) => f.id === e.factId), "factId must be recallable");
  });
});

// ---------- bug 2: echo matching on whole tokens, same numbers and entities ----------

test("claimsMatch: whole tokens only; numbers and entities must agree", () => {
  assert.ok(!claimsMatch("alice owes me 50", "alice owes me 500 dollars"), "50 is not 500");
  assert.ok(!claimsMatch("Pay Tom", "Pay Tomas"), "Tom is not Tomas");
  assert.ok(!claimsMatch("Lunch with Tom", "Lunch with Tom Baker"), "an extra entity is a different claim");
  assert.ok(!claimsMatch("Trains leave at 9", "Trains leave at 9 and 10"), "an extra number is a different claim");
  // Unchanged: equality and whole-token containment with the same anchors.
  assert.ok(claimsMatch("Ana lives in Lyon", "Reminder: Ana lives in Lyon!"));
  assert.ok(claimsMatch("alice owes me 50", "Alice owes me 50."));
});

test("an arrival with a different number is not an echo and does not promote the other claim", async () => {
  const m = clocked().memory();
  const store = new EcholaliaStore(null, m);
  await store.submit("alice owes me 500 dollars", { source: "chat", sourceText: "alice owes me 500 dollars" });
  const out = await store.submit("alice owes me 50", { source: "email", sourceText: "alice owes me 50" });
  assert.equal(out.echo, false);
  assert.deepEqual(m.all(), [], "nothing promoted");
  assert.equal(store.list("pending").length, 2);
});

test("an embedding match still needs the same numbers to count as an echo", async () => {
  const m = clocked().memory();
  const store = new EcholaliaStore(null, m);
  const same = { id: "same@2", embedOne: async () => [1, 0] } as unknown as Embedder;
  await store.submit("The workshop has 42 seats", { source: "venue page", sourceText: "The workshop has 42 seats" }, same);
  const out = await store.submit("The workshop seats 40 people", { source: "email", sourceText: "The workshop seats 40 people" }, same);
  assert.equal(out.echo, false);
  assert.deepEqual(m.all(), []);
});

// ---------- bug 3: each echo's own source text is checked ----------

test("an echo whose own source contradicts the claim quarantines it instead of promoting", async () => {
  const m = clocked().memory();
  const store = new EcholaliaStore(null, m);
  await store.submit("The workshop has 42 seats", { source: "venue page", sourceText: "The workshop has 42 seats in total" });
  const out = await store.submit("The workshop has 42 seats", { source: "booking email", sourceText: "The workshop has 17 seats" });
  assert.equal(out.echo, true);
  assert.equal(out.entry.status, "quarantined");
  assert.equal(out.entry.sourceCheck, "fail");
  assert.equal(out.entry.echoes, 0, "a contradicting echo is not counted");
  assert.match(out.entry.reasons.join(" "), /booking email.*number mismatch/);
  assert.match(out.message, /Quarantined/);
  assert.deepEqual(m.all(), []);
  // verifyAll leaves a failed check for the owner, it does not re-pass it.
  store.verifyAll();
  assert.equal(store.byId(out.entry.id)!.status, "quarantined");
});

test("an echo whose own source passes is counted and promotes", async () => {
  const m = clocked().memory();
  const store = new EcholaliaStore(null, m);
  await store.submit("The workshop has 42 seats", { source: "venue page", sourceText: "The workshop has 42 seats in total" });
  const out = await store.submit("The workshop has 42 seats", { source: "booking email", sourceText: "Booked: the workshop has 42 seats" });
  assert.equal(out.entry.status, "promoted");
  assert.equal(out.entry.echoes, 1);
  assert.match(out.entry.reasons.join(" "), /echo from booking email: source check pass/);
});
