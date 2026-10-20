import assert from "node:assert/strict";
import { mkdtempSync, readFileSync } from "node:fs";
import { createServer, type Server } from "node:http";
import { createServer as netServer } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";
import { WebSocket } from "ws";
import { loadConfig, paths } from "../src/config.ts";
import { Runtime } from "../src/runtime.ts";
import { startServer } from "../src/server.ts";
import { useNative } from "../src/tools/platform.ts";

/**
 * Regression: tool-call args used to reach every gateway client (and the
 * desktop app's event hook) UNREDACTED in `tool_start`, while confirm cards
 * and the audit log only ever showed them redacted. The broadcast must show
 * the same redacted args — without changing what the tool itself runs with.
 */

const ANTHROPIC_KEY = "sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";
const NPM_TOKEN = `npm_${"a1".repeat(18)}`;

let clip = "";
useNative({
  async openUrl() {},
  async clipboardRead() { return clip; },
  async clipboardWrite(t) { clip = t; },
  async notify() {},
  async screenshot() { return Buffer.from("PNGDATA"); },
});

const home = mkdtempSync(path.join(tmpdir(), "jarvis-broadcast-"));
const p = paths(home);
const TOKEN = "test-token-123";
let gw: Server | undefined;
let model: Server;
let port = 0;
const hooked: Array<Record<string, unknown>> = [];

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
  let n = 0;
  model = createServer((req, res) => {
    let body = "";
    req.on("data", (d) => (body += d));
    req.on("end", () => {
      n++;
      res.writeHead(200, { "content-type": "text/event-stream" });
      if (n === 1) {
        res.end(sse([
          { choices: [{ delta: { tool_calls: [{ index: 0, id: "c1", function: { name: "clipboard_write", arguments: JSON.stringify({ text: `deploy key: ${ANTHROPIC_KEY}` }) } }] } }] },
          { choices: [{ delta: { tool_calls: [{ index: 1, id: "c2", function: { name: "run_command", arguments: JSON.stringify({ command: `deploy --token ${NPM_TOKEN}` }) } }] } }] },
          { choices: [{ delta: {}, finish_reason: "tool_calls" }] },
        ]));
      } else {
        res.end(sse([
          { choices: [{ delta: { content: "Done. The command did not run." } }] },
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
  gw = (await startServer(new Runtime(cfg, p, home), TOKEN, port, {
    onEvent: (e) => hooked.push(e as Record<string, unknown>),
  })).server;
});

after(() => {
  gw?.close();
  model?.close();
});

test("tool args reach broadcast clients redacted; the tool still runs with the real values", async () => {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=${TOKEN}`, {
    headers: { origin: `http://127.0.0.1:${port}` },
  });
  await new Promise<void>((resolve, reject) => {
    ws.once("open", () => resolve());
    ws.once("error", reject);
  });
  const events: Array<Record<string, unknown>> = [];
  const done = new Promise<void>((resolve) => {
    ws.on("message", (raw) => {
      const e = JSON.parse(raw.toString()) as Record<string, unknown>;
      events.push(e);
      if (e.type === "confirm") ws.send(JSON.stringify({ type: "confirm", cardId: e.cardId, allow: false }));
      if (e.type === "done") resolve();
    });
  });
  ws.send(JSON.stringify({ type: "send", text: "copy the deploy key, then deploy" }));
  await done;
  ws.close();

  const wire = JSON.stringify(events);
  assert.ok(!wire.includes(ANTHROPIC_KEY), "the raw Anthropic key must never reach a broadcast client");
  assert.ok(!wire.includes(NPM_TOKEN), "the raw npm token must never reach a broadcast client");

  const start = events.find((e) => e.type === "tool_start" && e.name === "clipboard_write");
  assert.ok(start, "clipboard_write tool_start was broadcast");
  assert.ok(JSON.stringify(start.args).includes("[anthropic_key hidden]"), "tool_start args carry the redaction placeholder");

  const card = events.find((e) => e.type === "confirm");
  assert.ok(card, "run_command raised a confirm card");
  assert.ok(JSON.stringify(card!.args).includes("[npm_token hidden]"), "the card shows the redacted token, as before");
  assert.ok(!String(card!.reason).includes(NPM_TOKEN), "the card's reason (which quotes the command) is redacted on the wire too");
  assert.ok(String(card!.reason).includes("[npm_token hidden]"), "the card's reason keeps its shape, with the token hidden");

  const hookedStart = hooked.find((e) => e.type === "tool_start" && e.name === "clipboard_write");
  assert.ok(hookedStart, "the app's event hook saw the tool_start");
  assert.ok(!JSON.stringify(hookedStart).includes(ANTHROPIC_KEY), "the event hook copy is redacted too");

  // Execution is unchanged: the clipboard tool received the real text.
  assert.equal(clip, `deploy key: ${ANTHROPIC_KEY}`);

  // The audit log is unchanged: redacted, as it always was.
  const audit = readFileSync(p.audit, "utf8");
  assert.ok(!audit.includes(ANTHROPIC_KEY) && !audit.includes(NPM_TOKEN), "audit log holds no raw secrets");
  assert.ok(audit.includes("[anthropic_key hidden]") || audit.includes("clipboard_write"), "audit recorded the calls");
});
