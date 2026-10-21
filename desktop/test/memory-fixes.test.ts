import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { EcholaliaStore } from "../src/echolalia.ts";
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
