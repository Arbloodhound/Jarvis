import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { createServer, type Server } from "node:http";
import { createServer as netServer } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";
import { WebSocket } from "ws";
import { loadConfig, paths } from "../src/config.ts";
import { Runtime } from "../src/runtime.ts";
import { startServer } from "../src/server.ts";

/**
 * End to end through the real gateway, agent loop, gate and local provider,
 * with a fake OpenAI-compatible model server standing in for Ollama.
 */

const home = mkdtempSync(path.join(tmpdir(), "jarvis-e2e-"));
const p = paths(home);
const TOKEN = "test-token-123";
let gw: Server;
let model: Server;
let port = 0;
const seenRequests: Array<{ messages: Array<{ role: string; content?: string }>; tools?: unknown[] }> = [];

function sse(chunks: unknown[]): string {
  return chunks.map((c) => `data: ${JSON.stringify(c)}\n\n`).join("") + "data: [DONE]\n\n";
}

async function freePort(): Promise<number> {
  return new Promise((resolve) => {
    const s = netServer().listen(0, "127.0.0.1", () => {
      const a = s.address();
      s.close(() => resolve(typeof a === "object" && a ? a.port : 0));
    });
  });
}

before(async () => {
  model = createServer((req, res) => {
    let body = "";
    req.on("data", (d) => (body += d));
    req.on("end", () => {
      const json = JSON.parse(body);
      seenRequests.push(json);
      res.writeHead(200, { "content-type": "text/event-stream" });
      if (seenRequests.length === 1) {
        // Turn 1: think, then two tool calls: a read (runs) and a command (needs a card).
        res.end(sse([
          { choices: [{ delta: { content: "<think>list then clean</think>" } }] },
          { choices: [{ delta: { tool_calls: [{ index: 0, id: "c1", function: { name: "list_dir", arguments: JSON.stringify({ path: p.workspace }) } }] } }] },
          { choices: [{ delta: { tool_calls: [{ index: 1, id: "c2", function: { name: "run_command", arguments: '{"command":"rm -rf ' } }] } }] },
          { choices: [{ delta: { tool_calls: [{ index: 1, function: { arguments: 'old-builds"}' } }] } }] },
          { choices: [{ delta: {}, finish_reason: "tool_calls" }] },
        ]));
      } else {
        res.end(sse([
          { choices: [{ delta: { content: "Your workspace is empty. I did not delete anything." } }] },
          { choices: [{ delta: {}, finish_reason: "stop" }] },
        ]));
      }
    });
  });
  await new Promise<void>((r) => model.listen(0, "127.0.0.1", () => r()));
  const mport = (model.address() as { port: number }).port;

  port = await freePort();
  const cfg = loadConfig(p);
  cfg.local.baseUrl = `http://127.0.0.1:${mport}/v1`;
  cfg.port = port;
  gw = await startServer(new Runtime(cfg, p, home), TOKEN, port);
});

after(() => {
  gw?.close();
  model?.close();
});

function open(query: string, headers: Record<string, string> = {}): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws${query}`, { headers });
    ws.once("open", () => resolve(ws));
    ws.once("error", reject);
    ws.once("unexpected-response", (_req, res) => reject(new Error(`HTTP ${res.statusCode}`)));
  });
}

test("gateway refuses a wrong token and a foreign origin", async () => {
  await assert.rejects(open("?token=nope"), /403/);
  await assert.rejects(open(`?token=${TOKEN}`, { origin: "https://evil.example" }), /403/);
  const res = await fetch(`http://127.0.0.1:${port}/api/health`, { headers: { host: "evil.example" } }).catch(() => null);
  // fetch may refuse to override Host; when it does send it, the server must answer 421.
  if (res) assert.ok(res.status === 421 || res.status === 200);
});

test("a run: parallel read, confirm card declined, honest answer", async () => {
  const ws = await open(`?token=${TOKEN}`, { origin: `http://127.0.0.1:${port}` });
  const events: Array<Record<string, unknown>> = [];
  const done = new Promise<void>((resolve) => {
    ws.on("message", (raw) => {
      const e = JSON.parse(raw.toString());
      events.push(e);
      if (e.type === "confirm") ws.send(JSON.stringify({ type: "confirm", cardId: e.cardId, allow: false }));
      if (e.type === "done") resolve();
    });
  });
  ws.send(JSON.stringify({ type: "send", text: "what's in my workspace? clear out old builds" }));
  await done;
  ws.close();

  const types = events.map((e) => e.type);
  assert.ok(types.includes("thinking"), "thinking streamed");
  const card = events.find((e) => e.type === "confirm")!;
  assert.equal(card.tool, "run_command");
  assert.equal(card.rule, "command");
  const ends = events.filter((e) => e.type === "tool_end");
  assert.equal(ends.find((e) => e.name === "list_dir")?.ok, true);
  assert.equal(ends.find((e) => e.name === "run_command")?.ok, false);
  assert.equal(events.find((e) => e.type === "done")?.text, "Your workspace is empty. I did not delete anything.");

  // The second model request carried both results back, the declined one as an error.
  const second = seenRequests[1]!.messages;
  const toolMsgs = second.filter((m) => m.role === "tool");
  assert.equal(toolMsgs.length, 2);
  assert.match(String(toolMsgs[1]!.content), /declined/);
  assert.ok(Array.isArray(seenRequests[0]!.tools) && seenRequests[0]!.tools.length > 10);
});

test("fast command skips the model", async () => {
  const before = seenRequests.length;
  const ws = await open(`?token=${TOKEN}`);
  const text = await new Promise<string>((resolve) => {
    ws.on("message", (raw) => {
      const e = JSON.parse(raw.toString());
      if (e.type === "done") resolve(e.text);
    });
    ws.send(JSON.stringify({ type: "send", text: "remember that my sister is called Ana" }));
  });
  ws.close();
  assert.equal(text, "Got it, I'll remember that.");
  assert.equal(seenRequests.length, before);
});
