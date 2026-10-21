import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { capToolResults, chooseCut, isOverflow, replaceHead, SUMMARY_HEAD, turnsTokens, windowFromError } from "../src/agent/compress.ts";
import { Agent, type AgentEvent } from "../src/agent/loop.ts";
import { AuditLog } from "../src/gate/audit.ts";
import { Gate } from "../src/gate/gate.ts";
import { Memory } from "../src/memory.ts";
import type { Provider, Turn, TurnRequest, TurnResult } from "../src/providers/types.ts";

/**
 * Compression keeps the conversation inside the model's context window:
 * near the window older turns become one summary note; a server that still
 * refuses a request as too long ("exceeds the available context size") gets
 * a harder compression and one retry; /compress does it on demand.
 */

const home = mkdtempSync(path.join(tmpdir(), "jarvis-compress-"));
const isSummariser = (req: TurnRequest) => req.system.startsWith("You compress");

function agent(provider: Provider, contextWindow = 0): Agent {
  return new Agent({
    provider: () => provider,
    embedder: () => null,
    vision: () => null,
    tools: () => [],
    gate: new Gate({ writableRoots: [home], extraAllowedCommands: [] }, home),
    memory: new Memory(null),
    audit: new AuditLog(null),
    home, workspace: home, cwd: home, maxTurns: 4,
    contextWindow: () => contextWindow,
  });
}

function recorder() {
  const events: AgentEvent[] = [];
  return { events, hooks: { emit: (e: AgentEvent) => { events.push(e); }, confirm: async () => false } };
}

const LLAMA_400 = (n: number, ctx: number) =>
  `Local model server at http://127.0.0.1:8080/v1 answered 400: {"error":{"code":400,"message":"request (${n} tokens) exceeds the available context size (${ctx} tokens), try increasing it","type":"exceed_context_size_error","n_prompt_tokens":${n},"n_ctx":${ctx}}}`;

test("the llama.cpp overflow error is recognised and its window read", () => {
  const msg = LLAMA_400(66508, 65536);
  assert.equal(isOverflow(msg), true);
  assert.equal(windowFromError(msg), 65536);
  assert.equal(isOverflow("Local model server answered 500: out of memory"), false);
});

test("near the server's window, older turns become one summary and every request fits", async () => {
  const WINDOW = 8000;
  const seen: Array<{ summariser: boolean; tokens: number }> = [];
  const provider: Provider = {
    kind: "local",
    label: "fake",
    async contextSize() { return WINDOW; },
    async turn(req): Promise<TurnResult> {
      seen.push({ summariser: isSummariser(req), tokens: turnsTokens(req.turns) });
      if (isSummariser(req)) return { text: "SUMMARY-OK: the owner asked questions 0..n.", stop: "end", toolCalls: [] };
      return { text: "y".repeat(4500), stop: "end", toolCalls: [] };
    },
  };
  const a = agent(provider);
  const r = recorder();
  for (let i = 0; i < 8; i++) assert.equal((await a.runResult(`question ${i}`, r.hooks)).status, "done");
  assert.ok(seen.some((s) => s.summariser), "the model was asked for a summary");
  for (const s of seen.filter((x) => !x.summariser)) assert.ok(s.tokens < WINDOW, `a request of ~${s.tokens} tokens went to a ${WINDOW}-token window`);
  const first = a.turns[0]!;
  assert.ok(first.role === "user" && first.text.startsWith(SUMMARY_HEAD) && first.text.includes("SUMMARY-OK"), JSON.stringify(first).slice(0, 200));
  assert.ok(r.events.some((e) => e.type === "warning" && e.text.includes("Compressed the conversation")), "the owner is told");
  const last = a.turns[a.turns.length - 1]!;
  assert.ok(last.role === "assistant", "the latest exchange is kept verbatim");
});

test("a server that refuses the request as too long: compress, retry once, and the run still answers", async () => {
  let refusals = 0;
  let summaries = 0;
  const provider: Provider = {
    kind: "local",
    label: "fake",
    async turn(req): Promise<TurnResult> {
      if (isSummariser(req)) { summaries++; return { text: "earlier: long answers about topics", stop: "end", toolCalls: [] }; }
      const n = turnsTokens(req.turns);
      if (n > 3000) { refusals++; throw new Error(LLAMA_400(n, 4000)); }
      return { text: "z".repeat(3000), stop: "end", toolCalls: [] };
    },
  };
  const a = agent(provider);
  const r = recorder();
  for (let i = 0; i < 6; i++) {
    const res = await a.runResult(`topic ${i}`, r.hooks);
    assert.equal(res.status, "done", `run ${i}: ${res.text}`);
  }
  assert.ok(refusals >= 1, "the server refused once before the window was known");
  assert.ok(summaries >= 1, "the refusal led to a summary");
  // Once learned from the error, the window is kept to proactively: refusals stop.
  const before = refusals;
  for (let i = 6; i < 10; i++) await a.runResult(`topic ${i}`, r.hooks);
  assert.equal(refusals, before, "no more refusals once the window was learned");
});

test("a refusal that compression cannot fix still fails the run, with the server's message", async () => {
  const provider: Provider = {
    kind: "local",
    label: "fake",
    async turn(req): Promise<TurnResult> {
      if (isSummariser(req)) return { text: "s", stop: "end", toolCalls: [] };
      throw new Error(LLAMA_400(999999, 10));
    },
  };
  const res = await agent(provider).runResult("hi", recorder().hooks);
  assert.equal(res.status, "failed");
  assert.ok(res.text.includes("exceeds the available context size"), res.text);
});

test("/compress summarises everything before the last exchange", async () => {
  const provider: Provider = {
    kind: "local",
    label: "fake",
    async turn(req): Promise<TurnResult> {
      if (isSummariser(req)) return { text: "MANUAL-SUMMARY", stop: "end", toolCalls: [] };
      const q = (req.turns[req.turns.length - 1] as { text: string }).text.match(/\bq\d+\b/)?.[0] ?? "?";
      return { text: `answer to ${q}`, stop: "end", toolCalls: [] };
    },
  };
  const a = agent(provider);
  assert.equal(await a.compress(), "Nothing to compress yet.");
  for (let i = 0; i < 4; i++) await a.run(`q${i}`, recorder().hooks);
  const msg = await a.compress();
  assert.ok(msg.startsWith("Compressed the conversation"), msg);
  assert.equal(a.turns.length, 4, "summary + ack + last request + its answer");
  assert.ok((a.turns[0] as { text: string }).text.includes("MANUAL-SUMMARY"));
  assert.equal(a.turns[1]!.role, "assistant");
  assert.ok((a.turns[2] as { text: string }).text.includes("q3"));
  assert.equal(a.busy, false);
  assert.equal(await a.run("q4", recorder().hooks), "answer to q4");
});

test("the cut never separates a tool result from its call, and roles alternate after the summary", () => {
  const turns: Turn[] = [
    { role: "user", text: "a".repeat(3000) },
    { role: "assistant", text: "", toolCalls: [{ id: "1", name: "read_file", input: {} }] },
    { role: "tool", results: [{ id: "1", name: "read_file", content: "b".repeat(3000), isError: false }] },
    { role: "assistant", text: "done", toolCalls: [] },
    { role: "user", text: "next" },
  ];
  for (let keep = 1; keep < 3000; keep += 37) {
    const cut = chooseCut(turns, keep);
    assert.notEqual(turns[cut]?.role, "tool", `keep=${keep} cut=${cut}`);
  }
  const copy = structuredClone(turns);
  const cut = chooseCut(copy, 10);
  assert.equal(cut, 4);
  replaceHead(copy, cut, "S", null);
  assert.deepEqual(copy.map((t) => t.role), ["user", "assistant", "user"]);

  const t2 = structuredClone(turns);
  assert.equal(capToolResults(t2, 100, 1), true);
  const tool = t2[2] as Extract<Turn, { role: "tool" }>;
  assert.ok(tool.results[0]!.content.length < 200 && tool.results[0]!.content.endsWith("[cut by compression]"));
});
