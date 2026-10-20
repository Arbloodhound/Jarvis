import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { Agent, type AgentEvent } from "../src/agent/loop.ts";
import { routeRequest } from "../src/agent/router.ts";
import { loadConfig, paths } from "../src/config.ts";
import { AuditLog } from "../src/gate/audit.ts";
import { Gate } from "../src/gate/gate.ts";
import { Memory } from "../src/memory.ts";
import { LocalProvider } from "../src/providers/local.ts";
import type { Provider, TurnRequest, TurnResult } from "../src/providers/types.ts";
import { Runtime } from "../src/runtime.ts";
import { ALL_TOOLS } from "../src/tools/index.ts";

/* Tier: features/router.py ported faithfully. */

test("router tier: voice is always fast, even for a deep-sounding ask", () => {
  const r = routeRequest("do deep research on batteries", { channel: "voice" });
  assert.equal(r.tier, "fast");
  assert.match(r.reason, /voice: fast, fully finished, never truncated/);
});

test("router tier: research channel and every deep phrase are deep", () => {
  assert.equal(routeRequest("chip news please", { channel: "research" }).tier, "deep");
  for (const p of ["do deep research on X", "investigate this company", "be thorough about it", "an exhaustive list please", "write a report on rents"]) {
    const r = routeRequest(p);
    assert.equal(r.tier, "deep", p);
    assert.match(r.reason, /deep-research ask: full thinking budget/);
  }
});

test("router tier: router.py's long/complex condition, precedence included", () => {
  assert.equal(routeRequest("x".repeat(501)).tier, "deep"); // >500 chars, no "?" needed
  const longQ = `${Array(41).fill("word").join(" ")}?`;
  assert.equal(routeRequest(longQ).tier, "deep"); // "?" and >40 words
  assert.equal(routeRequest("why is the sky blue?").tier, "light"); // short question stays light
  assert.equal(routeRequest(Array(41).fill("word").join(" ")).tier, "light"); // >40 words but no "?"
});

test("router tier: plain chat and the cli channel are light", () => {
  for (const channel of ["chat", "cli"] as const) {
    const r = routeRequest("what's a good dinner with chickpeas?", { channel });
    assert.equal(r.tier, "light");
    assert.match(r.reason, /standard chat: light thinking/);
  }
});

test("router tier: a fast-command match is labelled fast and still short-circuits", () => {
  const r = routeRequest("open github.com", { channel: "chat", fastCommand: true });
  assert.equal(r.tier, "fast");
  assert.match(r.reason, /fast command/);
});

/* Mode: the Claim 10 Heuristic vs Literal matrix. */

test("router mode: verbatim, quotes and step-by-step asks are literal", () => {
  for (const p of [
    "repeat back word for word what I said about the lease",
    "give me the exact quote from that email",
    "what did she say about the deadline",
    "give me step-by-step instructions to reset it",
  ]) {
    assert.equal(routeRequest(p).mode, "literal", p);
  }
});

test("router mode: code and numbers asks are literal", () => {
  for (const p of [
    "write a typescript function that sorts users by name",
    "debug this code for me",
    "what is 17 * 24?",
    "how much is 250 grams in ounces?",
    "calculate a 15% tip on 84 dollars",
  ]) {
    assert.equal(routeRequest(p).mode, "literal", p);
  }
});

test("router mode: creative, summary and exploratory asks are heuristic; heuristic is the default", () => {
  for (const p of [
    "brainstorm ideas for a story about a lighthouse keeper",
    "summarize this article for me",
    "explain how photosynthesis works",
    "what's a good dinner with chickpeas and spinach?",
    "hello there",
  ]) {
    assert.equal(routeRequest(p).mode, "heuristic", p);
  }
  assert.match(routeRequest("hello there").reason, /heuristic: open ask \(default\)/);
});

test("router: effort follows tier and routing is deterministic", () => {
  assert.equal(routeRequest("hi", { channel: "voice" }).effort, "low");
  assert.equal(routeRequest("hi").effort, "medium");
  assert.equal(routeRequest("write a report on X").effort, "high");
  const a = routeRequest("write a typescript function, step by step", { channel: "chat" });
  assert.deepEqual(a, routeRequest("write a typescript function, step by step", { channel: "chat" }));
  assert.equal(a.mode, "literal"); // step-by-step/code outrank the heuristic default
});

/* Loop wiring: the route reaches the provider, the events and the record. */

const home = mkdtempSync(path.join(tmpdir(), "jarvis-router-"));
const quiet = { emit: (_e: AgentEvent) => {}, confirm: async () => false };

function agent(provider: Provider, seen: TurnRequest[] = []): Agent {
  return new Agent({
    provider: () => provider,
    embedder: () => null,
    vision: () => null,
    tools: () => ALL_TOOLS,
    gate: new Gate({ writableRoots: [home], extraAllowedCommands: [] }, home),
    memory: new Memory(null),
    audit: new AuditLog(null),
    home, workspace: home, cwd: home, maxTurns: 4,
  });
}

function scripted(results: TurnResult[], seen: TurnRequest[] = []): Provider {
  let i = 0;
  return { kind: "cloud", label: "cloud", async turn(req) { seen.push(req); return results[Math.min(i++, results.length - 1)]!; } };
}

test("loop: a deep ask sends effort=high to the provider and the route rides run_start + the transcript", async () => {
  const seen: TurnRequest[] = [];
  const events: AgentEvent[] = [];
  const a = agent(scripted([{ text: "Researched.", stop: "end", toolCalls: [] }], seen));
  await a.run("please do deep research on solid-state batteries and write a report", { emit: (e) => events.push(e), confirm: async () => false });
  assert.equal(seen[0]!.effort, "high");
  const start = events.find((e) => e.type === "run_start");
  assert.ok(start && start.type === "run_start");
  assert.equal(start.route.tier, "deep");
  assert.equal(start.route.mode, "heuristic");
  assert.equal(a.lastRoute?.tier, "deep");
  const assistant = a.turns.find((t) => t.role === "assistant");
  assert.ok(assistant && assistant.role === "assistant" && assistant.route?.tier === "deep");
});

test("loop: a fast command is labelled tier=fast and never reaches the provider", async () => {
  const seen: TurnRequest[] = [];
  const a = agent(scripted([{ text: "model answer", stop: "end", toolCalls: [] }], seen));
  const answer = await a.run("what time is it?", quiet);
  assert.match(answer, /It's .+/);
  assert.equal(seen.length, 0);
  assert.equal(a.lastRoute?.tier, "fast");
});

test("loop: voice channel routes fast through the loop", async () => {
  const seen: TurnRequest[] = [];
  const a = agent(scripted([{ text: "ok", stop: "end", toolCalls: [] }], seen));
  await a.run("tell me a joke about sailors", { ...quiet }, { channel: "voice" });
  assert.equal(a.lastRoute?.tier, "fast");
  assert.equal(seen[0]!.effort, "low");
});

test("local provider: the route effort goes out as reasoning_effort", async () => {
  let body = "";
  const srv = createServer((req, res) => {
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      res.writeHead(200, { "content-type": "text/event-stream" });
      res.end(`data: ${JSON.stringify({ choices: [{ delta: { content: "hi" }, finish_reason: "stop" }] })}\n\ndata: [DONE]\n\n`);
    });
  });
  await new Promise<void>((r) => srv.listen(0, "127.0.0.1", () => r()));
  const port = (srv.address() as { port: number }).port;
  const p = new LocalProvider(`http://127.0.0.1:${port}/v1`, "m");
  const base = { system: "s", turns: [{ role: "user", text: "go" }] as TurnRequest["turns"], tools: [], signal: new AbortController().signal, onText() {}, onThinking() {} };
  await p.turn({ ...base, effort: "high" });
  assert.equal((JSON.parse(body) as { reasoning_effort?: string }).reasoning_effort, "high");
  body = "";
  await p.turn({ ...base });
  assert.equal("reasoning_effort" in (JSON.parse(body) as object), false);
  srv.close();
});

test("runtime state carries the last route", async () => {
  const dir = mkdtempSync(path.join(tmpdir(), "jarvis-router-state-"));
  const p = paths(dir);
  const rt = new Runtime(loadConfig(p), p, dir);
  assert.equal(rt.state().lastRoute, null);
  await rt.agent.run("what time is it?", quiet);
  assert.equal(rt.state().lastRoute?.tier, "fast");
});
