import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { Agent, type AgentEvent } from "../src/agent/loop.ts";
import { loadConfig, paths } from "../src/config.ts";
import { AuditLog } from "../src/gate/audit.ts";
import { Gate } from "../src/gate/gate.ts";
import {
  checkClaim, checkClaimWithModel, createLocalModelChecker, entailmentPrompt,
  extractCheckableClaims, isNegated, numbersIn, parseEntailment, type ModelChecker,
} from "../src/gate/nli.ts";
import { Memory } from "../src/memory.ts";
import type { Provider, TurnRequest, TurnResult } from "../src/providers/types.ts";
import { ALL_TOOLS } from "../src/tools/index.ts";

/**
 * The deterministic checker is a 1:1 port of features/nli_gate.py; these cases
 * pin the reference semantics (including the ones the task's live check names),
 * the one-directional model layer, the claim-extraction rule, and the loop
 * wiring (re-check once, then surface — never silently pass or drop).
 */

// --- deterministic checker: the reference verdict paths ---

test("nli: numeric contradiction via number mismatch fails", () => {
  const v = checkClaim("The price is 42 dollars.", ["The price is 45 dollars."]);
  assert.equal(v.passed, false);
  assert.match(v.reason, /number mismatch with evidence/);
  assert.equal(v.source, "deterministic");
});

test("nli: negation-flip contradiction fails (same numbers, opposite polarity)", () => {
  const v = checkClaim("The store is not open 24 hours.", ["The store is open 24 hours."]);
  assert.equal(v.passed, false);
  assert.match(v.reason, /contradiction with evidence/);
});

test("nli: corroborated numbers pass", () => {
  const v = checkClaim("The price is 42 dollars.", ["Listing: the price is 42 dollars today."]);
  assert.deepEqual([v.passed, v.reason], [true, "numbers corroborated"]);
});

test("nli: claim number absent from all evidence numbers fails", () => {
  const v = checkClaim("It has 8 GB of RAM.", ["It has 16 GB of RAM and a 512 GB disk."]);
  assert.equal(v.passed, false);
  assert.match(v.reason, /number mismatch/);
});

test("nli: no evidence fails closed", () => {
  const v = checkClaim("The price is 42 dollars.", []);
  assert.deepEqual([v.passed, v.reason], [false, "no evidence to check against"]);
});

test("nli: non-numeric claim with no contradiction is a weak pass", () => {
  const v = checkClaim("The store is downtown.", ["The store is downtown near the harbour."]);
  assert.deepEqual([v.passed, v.reason], [true, "no contradiction found (weak support)"]);
});

test("nli: decimal mismatch fails; matching decimals corroborate", () => {
  assert.equal(checkClaim("It weighs 2.5 kg.", ["It weighs 2.75 kg."]).passed, false);
  const ok = checkClaim("It weighs 2.5 kg.", ["Weight: 2.5 kg."]);
  assert.deepEqual([ok.passed, ok.reason], [true, "numbers corroborated"]);
});

test("nli: helpers match the reference (negation set, number extraction incl. decimals)", () => {
  assert.equal(isNegated("It isn't ready"), true);
  assert.equal(isNegated("It cannot ship"), true);
  assert.equal(isNegated("It is ready"), false);
  // Reference splits on whitespace only: a negation glued to punctuation is not a token.
  assert.equal(isNegated("It is not, ready"), false);
  assert.deepEqual([...numbersIn("a 42 b 2.5 c 42")].sort(), ["2.5", "42"]);
  assert.deepEqual([...numbersIn("no numbers here")], []);
});

test("nli: per-evidence mismatch returns on the first disjoint evidence (reference behaviour)", () => {
  // Evidence 1 corroborates, evidence 2 has disjoint numbers: the reference
  // fails on evidence 2 because its loop returns before the support check.
  const v = checkClaim("The price is 42 dollars.", ["The price is 42 dollars.", "A different item costs 99 dollars."]);
  assert.equal(v.passed, false);
  assert.match(v.reason, /number mismatch/);
});

// --- claim extraction rule ---

test("nli: extraction rule — only sentences containing numbers are checkable", () => {
  const answer = "The laptop is fast. It costs 999 dollars! Does it have 16 GB of RAM?\nIt weighs 2.5 kg\nNo numbers in this tail";
  assert.deepEqual(extractCheckableClaims(answer), [
    "It costs 999 dollars!",
    "Does it have 16 GB of RAM?",
    "It weighs 2.5 kg",
  ]);
  assert.deepEqual(extractCheckableClaims("No numbers at all."), []);
  assert.deepEqual(extractCheckableClaims(""), []);
});

// --- model-backed layer: one-directional ---

const entailed: ModelChecker = async () => "entailed";
const contradicted: ModelChecker = async () => "contradicted";

test("nli model layer: a deterministic FAIL is never overridden by the model", async () => {
  const v = await checkClaimWithModel("The price is 42 dollars.", ["The price is 45 dollars."], entailed);
  assert.equal(v.passed, false);
  assert.equal(v.source, "deterministic");
});

test("nli model layer: model contradiction turns a deterministic pass into a fail", async () => {
  const v = await checkClaimWithModel("The store is downtown.", ["The store moved out of town last year."], contradicted);
  assert.equal(v.passed, false);
  assert.equal(v.source, "model");
  assert.match(v.reason, /model contradiction/);
});

test("nli model layer: entailed / neutral / no-verdict / model error all leave the pass standing", async () => {
  for (const mc of [entailed, async () => "neutral" as const, async () => null, async () => { throw new Error("server down"); }]) {
    const v = await checkClaimWithModel("The price is 42 dollars.", ["The price is 42 dollars."], mc);
    assert.deepEqual([v.passed, v.reason, v.source], [true, "numbers corroborated", "deterministic"]);
  }
});

test("nli model layer: prompt is strict, parser takes the first verdict word", () => {
  const p = entailmentPrompt("Claim X", ["Evidence Y"]);
  assert.match(p, /exactly one word: ENTAILED, CONTRADICTED, or NEUTRAL/);
  assert.ok(p.includes("Claim: Claim X") && p.includes("1. Evidence Y"));
  assert.equal(parseEntailment("CONTRADICTED"), "contradicted");
  assert.equal(parseEntailment("The answer is entailed."), "entailed");
  assert.equal(parseEntailment("I am not sure"), null);
});

test("nli model layer: local checker posts to the configured provider and parses the verdict", async () => {
  let url = "";
  let body: Record<string, unknown> = {};
  const fakeFetch = (async (u: string, init: { body: string }) => {
    url = u; body = JSON.parse(init.body) as Record<string, unknown>;
    return { ok: true, json: async () => ({ choices: [{ message: { content: "CONTRADICTED" } }] }) };
  }) as unknown as typeof fetch;
  const mc = createLocalModelChecker({ baseUrl: "http://127.0.0.1:11434/v1/", model: "qwen3:8b", fetchFn: fakeFetch });
  assert.equal(await mc("The price is 42 dollars.", ["The price is 45 dollars."]), "contradicted");
  assert.equal(url, "http://127.0.0.1:11434/v1/chat/completions");
  assert.equal(body.model, "qwen3:8b");
  assert.equal(body.temperature, 0);
  const bad = createLocalModelChecker({ baseUrl: "http://x/v1", model: "m", fetchFn: (async () => ({ ok: false, text: async () => "" })) as unknown as typeof fetch });
  assert.equal(await bad("c", ["e"]), null);
});

// --- config ---

test("nli config: defaults on (deterministic only); old configs without the key merge to defaults", () => {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-nli-cfg-"));
  const p = paths(home);
  writeFileSync(p.config, JSON.stringify({ brain: "local" }) + "\n");
  const cfg = loadConfig(p);
  assert.deepEqual(cfg.nli, { enabled: true, modelCheck: false });
});

// --- loop wiring ---

const home = mkdtempSync(path.join(tmpdir(), "jarvis-nli-loop-"));
const dataFile = path.join(home, "data.txt");
writeFileSync(dataFile, "The price is 45 dollars.\n");

function scripted(results: TurnResult[], seen: TurnRequest[] = []): Provider {
  let i = 0;
  return { kind: "local", label: "local", async turn(req) { seen.push({ ...req, turns: [...req.turns] }); return results[Math.min(i++, results.length - 1)]!; } };
}

function makeAgent(provider: Provider, opts: { nli?: { enabled: boolean; modelCheck?: ModelChecker | null }; audit?: AuditLog } = {}): Agent {
  return new Agent({
    provider: () => provider,
    embedder: () => null,
    vision: () => null,
    tools: () => ALL_TOOLS,
    gate: new Gate({ writableRoots: [home], extraAllowedCommands: [] }, home),
    memory: new Memory(null),
    audit: opts.audit ?? new AuditLog(null),
    home, workspace: home, cwd: home, maxTurns: 6,
    ...(opts.nli ? { nli: opts.nli } : {}),
  });
}

const quiet = { emit: (_e: AgentEvent) => {}, confirm: async () => true };
const readThen = (text: string): TurnResult[] => [
  { text: "", stop: "tool_use", toolCalls: [{ id: "r1", name: "read_file", input: { path: dataFile } }] },
  { text, stop: "end", toolCalls: [] },
];

test("loop nli: a contradicted answer is corrected in one re-check turn", async () => {
  const seen: TurnRequest[] = [];
  const a = makeAgent(scripted([
    ...readThen("The price is 42 dollars."),
    { text: "The price is 45 dollars.", stop: "end", toolCalls: [] },
  ], seen));
  const out = await a.run("what is the price in data.txt?", quiet);
  assert.equal(out, "The price is 45 dollars.");
  // The re-check turn carries the contradiction and no tools.
  const recheck = seen[2]!;
  assert.equal(recheck.tools.length, 0);
  const prompt = recheck.turns[recheck.turns.length - 1]!;
  assert.equal(prompt.role, "user");
  assert.ok(prompt.role === "user" && prompt.text.includes("[NLI gate re-check]") && prompt.text.includes("number mismatch"));
});

test("loop nli: a still-failing answer is delivered WITH the disagreement surfaced", async () => {
  const a = makeAgent(scripted(readThen("The price is 42 dollars.")));
  const out = await a.run("what is the price in data.txt?", quiet);
  assert.ok(out.includes("The price is 42 dollars."), "the claim is delivered, not dropped");
  assert.ok(out.includes("NLI gate disagreement"), "the disagreement is surfaced");
  assert.ok(out.includes("number mismatch with evidence"));
});

test("loop nli: gate events are written to the audit log", async () => {
  const auditFile = path.join(mkdtempSync(path.join(tmpdir(), "jarvis-nli-audit-")), "audit.jsonl");
  const a = makeAgent(scripted(readThen("The price is 42 dollars.")), { audit: new AuditLog(auditFile) });
  await a.run("what is the price in data.txt?", quiet);
  const entries = readFileSync(auditFile, "utf8").trim().split("\n").map((l) => JSON.parse(l) as Record<string, unknown>);
  const nli = entries.filter((e) => e.tool === "nli_gate");
  assert.ok(nli.some((e) => e.gate === "nli_fail" && e.round === "initial"));
  assert.ok(nli.some((e) => e.gate === "nli_disagreement_surfaced"));
});

test("loop nli: nli.enabled=false disables the wiring (no re-check turn)", async () => {
  const seen: TurnRequest[] = [];
  const a = makeAgent(scripted(readThen("The price is 42 dollars."), seen), { nli: { enabled: false } });
  const out = await a.run("what is the price in data.txt?", quiet);
  assert.equal(out, "The price is 42 dollars.");
  assert.equal(seen.length, 2);
});

test("loop nli: a corroborated answer passes untouched, and answers without tool results are not gated", async () => {
  const seen: TurnRequest[] = [];
  const a = makeAgent(scripted(readThen("The price is 45 dollars."), seen));
  assert.equal(await a.run("what is the price in data.txt?", quiet), "The price is 45 dollars.");
  assert.equal(seen.length, 2);
  const b = makeAgent(scripted([{ text: "There are 9 planets, trust me.", stop: "end", toolCalls: [] }]));
  assert.equal(await b.run("how many planets?", quiet), "There are 9 planets, trust me.");
});
