import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { createServer, type Server } from "node:http";
import { createServer as netServer } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { createInterface } from "node:readline/promises";
import { PassThrough } from "node:stream";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { WebSocket } from "ws";
import { defaults, paths } from "../src/config.ts";
import type { Provider, TurnRequest, TurnResult } from "../src/providers/types.ts";
import { Runtime } from "../src/runtime.ts";
import { startServer } from "../src/server.ts";
import { TerminalPrompt } from "../src/terminal.ts";

/**
 * Interrupt: a new message typed while Jarvis works stops the run and is
 * taken as the next request — in `jarvis chat` (a line typed with no card
 * up) and in the chat page (send with `interrupt: true`).
 */

const PKG = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const CLI = path.join(PKG, "src", "cli.ts");
const strip = (s: string) => s.replace(/\x1b\[[0-9;]*m/g, "");
const lastUser = (req: TurnRequest): string => {
  const u = [...req.turns].reverse().find((t) => t.role === "user") as { text?: string } | undefined;
  return u?.text ?? "";
};

async function until(fn: () => boolean, ms = 10_000): Promise<boolean> {
  const t0 = Date.now();
  while (!fn()) {
    if (Date.now() - t0 > ms) return false;
    await new Promise((r) => setTimeout(r, 10));
  }
  return true;
}

test("prompt: a silent chat prompt takes a line typed mid-run without being shown; a card still goes first; reveal shows it", async () => {
  const input = new PassThrough();
  const output = new PassThrough();
  let printed = "";
  output.on("data", (d) => (printed += d));
  const term = new TerminalPrompt(createInterface({ input, output, terminal: false }), output);
  const listen = term.ask("you › ", { silent: true });
  await new Promise((r) => setTimeout(r, 10));
  assert.ok(!printed.includes("you › "), "the silent prompt is not printed over the run's output");
  const card = term.card("c1", "? card\n", "Allow? ");
  input.write("y\n");
  assert.equal(await card, "y", "a card up takes the line before the silent prompt");
  assert.ok(!printed.includes("you › "), "answering the card does not show the silent prompt");
  input.write("new request\n");
  assert.equal(await listen, "new request", "with no card up, the silent prompt takes the line");

  const again = term.ask("you › ", { silent: true });
  term.reveal();
  assert.ok(printed.includes("you › "), "reveal shows the prompt");
  input.write("later\n");
  assert.equal(await again, "later");
});

async function freePort(): Promise<number> {
  return new Promise((resolve) => {
    const s = netServer().listen(0, "127.0.0.1", () => {
      const a = s.address();
      s.close(() => resolve(typeof a === "object" && a ? a.port : 0));
    });
  });
}

test("gateway: send with interrupt stops the working run and runs the new message", { timeout: 30_000 }, async () => {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-interrupt-gw-"));
  const p = paths(home);
  const rt = new Runtime(defaults(p), p, home);
  const asked: string[] = [];
  const b: Provider = {
    kind: "local",
    label: "fake brain",
    async turn(req): Promise<TurnResult> {
      const u = lastUser(req);
      asked.push(u);
      if (u.includes("slow job")) {
        // Works until stopped.
        await new Promise((_, reject) => req.signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true }));
      }
      return { text: "switched to the new thing", stop: "end", toolCalls: [] };
    },
  };
  (rt as unknown as { provider: () => Provider }).provider = () => b;
  const port = await freePort();
  const TOKEN = "interrupt-token";
  const gw = await startServer(rt, TOKEN, port);
  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=${TOKEN}`, { headers: { origin: `http://127.0.0.1:${port}` } });
  const got: Array<Record<string, unknown>> = [];
  ws.on("message", (raw) => got.push(JSON.parse(raw.toString()) as Record<string, unknown>));
  await new Promise<void>((resolve, reject) => { ws.once("open", () => resolve()); ws.once("error", reject); });
  try {
    ws.send(JSON.stringify({ type: "send", text: "slow job" }));
    assert.ok(await until(() => rt.agent.busy && asked.length === 1), "the first run is working");
    // Without interrupt, a send while working is refused as before.
    ws.send(JSON.stringify({ type: "send", text: "plain send" }));
    assert.ok(await until(() => got.some((e) => e.type === "error" && String(e.message).includes("Stop it first"))));
    ws.send(JSON.stringify({ type: "send", text: "new thing", interrupt: true }));
    assert.ok(await until(() => got.some((e) => e.type === "done" && e.stopped === true)), "the working run was stopped");
    assert.ok(await until(() => got.some((e) => e.type === "done" && String(e.text).includes("switched to the new thing"))), JSON.stringify(got.filter((e) => e.type === "done" || e.type === "error")));
    assert.ok(asked.some((u) => u.includes("new thing")), "the new message reached the model");
    assert.ok(!asked.some((u) => u.includes("plain send")), "the refused send never ran");
  } finally {
    ws.close();
    gw.server.close();
  }
});

function sse(chunks: unknown[]): string {
  return chunks.map((c) => `data: ${JSON.stringify(c)}\n\n`).join("") + "data: [DONE]\n\n";
}

test("chat: a line typed while a run works interrupts it and becomes the next request", { timeout: 120_000 }, async () => {
  const users: string[] = [];
  const server: Server = createServer((req, res) => {
    let body = "";
    req.on("data", (d) => (body += d));
    req.on("end", () => {
      if (req.method !== "POST") { res.writeHead(404).end(); return; }
      const msgs = (JSON.parse(body) as { messages: Array<{ role: string; content?: unknown }> }).messages;
      const last = [...msgs].reverse().find((m) => m.role === "user");
      const u = typeof last?.content === "string" ? last.content : JSON.stringify(last?.content ?? "");
      users.push(u);
      res.writeHead(200, { "content-type": "text/event-stream" });
      if (u.includes("slow job")) {
        // Starts answering, then never finishes: only a stop ends it.
        res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: "working on the slow job…" } }] })}\n\n`);
        return;
      }
      res.end(sse([{ choices: [{ delta: { content: "switched to the new thing" } }] }, { choices: [{ delta: {}, finish_reason: "stop" }] }]));
    });
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", () => r()));
  const baseUrl = `http://127.0.0.1:${(server.address() as { port: number }).port}/v1`;
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-interrupt-cli-"));
  writeFileSync(path.join(home, "config.json"), JSON.stringify({ local: { baseUrl, model: "fake-1" } }));
  const child = spawn(process.execPath, ["--import", "tsx", CLI, "chat"], {
    cwd: PKG,
    env: { PATH: process.env.PATH ?? "", HOME: process.env.HOME ?? "", JARVIS_HOME: home },
  });
  let buf = "";
  child.stdout.on("data", (d) => (buf += d));
  child.stderr.on("data", (d) => (buf += d));
  const exited = new Promise<number | null>((resolve) => child.on("close", resolve));
  const out = () => strip(buf);
  try {
    assert.ok(await until(() => out().includes("you › "), 30_000), out());
    child.stdin.write("do the slow job\n");
    assert.ok(await until(() => out().includes("working on the slow job"), 30_000), out());
    child.stdin.write("do the new thing instead\n");
    assert.ok(await until(() => out().includes("switched to the new thing"), 30_000), out());
    assert.ok(out().includes("Interrupted"), out());
    assert.ok(await until(() => out().split("you › ").length - 1 >= 2, 5000), `the chat prompt is back after the new run:\n${out()}`);
    child.stdin.end();
    await exited;
    assert.ok(users.some((u) => u.includes("do the new thing instead")), JSON.stringify(users));
  } finally {
    child.kill("SIGKILL");
    server.close();
  }
});
