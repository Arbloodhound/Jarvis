import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { Agent, type AgentEvent } from "../src/agent/loop.ts";
import { AuditLog } from "../src/gate/audit.ts";
import { Gate } from "../src/gate/gate.ts";
import { Memory } from "../src/memory.ts";
import { toMessages } from "../src/providers/claude.ts";
import { LocalProvider } from "../src/providers/local.ts";
import type { Provider, TurnRequest, TurnResult } from "../src/providers/types.ts";
import { ALL_TOOLS, parseDuckDuckGo } from "../src/tools/index.ts";

const home = mkdtempSync(path.join(tmpdir(), "jarvis-loop-"));

function agent(provider: Provider, maxTurns = 4): Agent {
  return new Agent({
    provider: () => provider,
    embedder: () => null,
    tools: () => ALL_TOOLS,
    gate: new Gate({ writableRoots: [home], extraAllowedCommands: [] }, home),
    memory: new Memory(null),
    audit: new AuditLog(null),
    home, workspace: home, cwd: home, maxTurns,
  });
}

function scripted(kind: "cloud" | "local", results: TurnResult[], seen: TurnRequest[] = []): Provider {
  let i = 0;
  return { kind, label: kind, async turn(req) { seen.push({ ...req, turns: [...req.turns] }); return results[Math.min(i++, results.length - 1)]!; } };
}

const quiet = { emit: (_e: AgentEvent) => {}, confirm: async () => false };

test("a tool call cut off by the length limit is never run", async () => {
  const target = path.join(home, "half.txt");
  const a = agent(scripted("cloud", [
    { text: "", stop: "max_tokens", toolCalls: [{ id: "t1", name: "write_file", input: { path: target, content: "half a fi" } }] },
    { text: "Done.", stop: "end", toolCalls: [] },
  ]));
  await a.run("write a long file", quiet);
  const { existsSync } = await import("node:fs");
  assert.equal(existsSync(target), false);
  const tool = a.turns.find((t) => t.role === "tool");
  assert.ok(tool && tool.role === "tool" && tool.results[0]!.isError);
});

test("a crash before the run starts doesn't leave Jarvis stuck busy", async () => {
  const a = agent(scripted("local", [{ text: "hi", stop: "end", toolCalls: [] }]));
  let first = true;
  await a.run("hello", { emit: () => { if (first) { first = false; throw new Error("boom"); } }, confirm: async () => false }).catch(() => {});
  assert.equal(a.busy, false);
  assert.equal(await a.run("hello again", quiet), "hi");
});

test("secrets the local brain saw are masked when the conversation moves to Claude", async () => {
  const f = path.join(home, "keys.txt");
  writeFileSync(f, "ANTHROPIC=sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789\n");
  const a = agent(scripted("local", [
    { text: "", stop: "tool_use", toolCalls: [{ id: "r1", name: "read_file", input: { path: f } }] },
    { text: "Read it.", stop: "end", toolCalls: [] },
  ]));
  await a.run("read keys.txt", quiet);
  const wire = JSON.stringify(toMessages(a.turns));
  assert.ok(!wire.includes("sk-ant-api03-abcdef"), "raw key reached the cloud request");
  assert.ok(wire.includes("[anthropic_key hidden]"));
});

test("long conversations are trimmed by whole exchanges", async () => {
  const big = "x".repeat(200_000);
  const a = agent(scripted("local", [{ text: big, stop: "end", toolCalls: [] }]));
  for (let i = 0; i < 6; i++) await a.run(`question ${i}`, quiet);
  const size = a.turns.reduce((n, t) => n + JSON.stringify(t).length, 0);
  assert.ok(size < 900_000, `history is ${size} chars`);
  assert.equal(a.turns[0]!.role, "user");
  a.reset();
  assert.equal(a.turns.length, 0);
});

test("local server: two tool calls in one chunk without an index stay two calls", async () => {
  const srv = createServer((_req, res) => {
    res.writeHead(200, { "content-type": "text/event-stream" });
    const chunk = { choices: [{ delta: { tool_calls: [
      { id: "a", function: { name: "read_file", arguments: '{"path":"x"}' } },
      { id: "b", function: { name: "list_dir", arguments: '{"path":"y"}' } },
    ] }, finish_reason: "tool_calls" }] };
    res.end(`data: ${JSON.stringify(chunk)}\n\ndata: [DONE]\n\n`);
  });
  await new Promise<void>((r) => srv.listen(0, "127.0.0.1", () => r()));
  const port = (srv.address() as { port: number }).port;
  const r = await new LocalProvider(`http://127.0.0.1:${port}/v1`, "m").turn({
    system: "s", turns: [{ role: "user", text: "go" }], tools: [], signal: new AbortController().signal, onText() {}, onThinking() {},
  });
  srv.close();
  assert.deepEqual(r.toolCalls.map((c) => [c.name, c.input]), [["read_file", { path: "x" }], ["list_dir", { path: "y" }]]);
});

test("search results keep their snippets", () => {
  const html = `<div class="result results_links results_links_deep web-result "><div class="links_main links_deep result__body">
    <h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa&amp;rut=1">Example <b>A</b></a></h2>
    <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">The first <b>snippet</b>.</a></div></div>
    <div class="result results_links web-result "><h2 class="result__title"><a rel="nofollow" class="result__a" href="https://example.org/b">B</a></h2>
    <div class="result__snippet">Second one.</div></div>`;
  const r = parseDuckDuckGo(html);
  assert.equal(r.length, 2);
  assert.deepEqual(r[0], { title: "Example A", url: "https://example.com/a", snippet: "The first snippet ." });
  assert.equal(r[1]!.snippet, "Second one.");
});
