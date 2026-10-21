import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

/**
 * /refresh in `jarvis chat`: the chat restarts in place (new build and
 * config from disk) and carries on with the same conversation.
 */

const PKG = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const CLI = path.join(PKG, "src", "cli.ts");
const strip = (s: string) => s.replace(/\x1b\[[0-9;]*m/g, "");

test("/refresh restarts the chat in place and keeps the conversation", { timeout: 120_000 }, async () => {
  const requests: string[][] = [];
  const server = createServer((req, res) => {
    let body = "";
    req.on("data", (d) => (body += d));
    req.on("end", () => {
      if (req.method !== "POST") { res.writeHead(404).end(); return; }
      const msgs = (JSON.parse(body) as { messages: Array<{ role: string; content?: unknown }> }).messages;
      requests.push(msgs.filter((m) => m.role === "user").map((m) => (typeof m.content === "string" ? m.content : JSON.stringify(m.content))));
      res.writeHead(200, { "content-type": "text/event-stream" });
      res.end(`data: ${JSON.stringify({ choices: [{ delta: { content: `answer ${requests.length}` } }] })}\n\ndata: ${JSON.stringify({ choices: [{ delta: {}, finish_reason: "stop" }] })}\n\ndata: [DONE]\n\n`);
    });
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", () => r()));
  const baseUrl = `http://127.0.0.1:${(server.address() as { port: number }).port}/v1`;
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-refresh-"));
  writeFileSync(path.join(home, "config.json"), JSON.stringify({ local: { baseUrl, model: "fake-1" } }));
  const child = spawn(process.execPath, ["--import", "tsx", CLI, "chat"], { cwd: PKG, env: { PATH: process.env.PATH ?? "", HOME: process.env.HOME ?? "", JARVIS_HOME: home } });
  let buf = "";
  child.stdout.on("data", (d) => (buf += d));
  child.stderr.on("data", (d) => (buf += d));
  const exited = new Promise<number | null>((resolve) => child.on("close", resolve));
  const out = () => strip(buf);
  const until = async (pred: () => boolean, ms = 30_000) => {
    const t0 = Date.now();
    while (!pred()) { if (Date.now() - t0 > ms) return false; await new Promise((r) => setTimeout(r, 20)); }
    return true;
  };
  try {
    assert.ok(await until(() => out().includes("you › ")), out());
    child.stdin.write("my favourite fruit is pineapple, say hi\n");
    assert.ok(await until(() => out().includes("answer 1")), out());
    child.stdin.write("/refresh\n");
    assert.ok(await until(() => out().includes("Reloading Jarvis")), out());
    // The new chat prints its banner again and waits at the prompt.
    assert.ok(await until(() => out().split("/refresh reloads Jarvis").length - 1 >= 2), out());
    assert.ok(await until(() => out().split("you › ").length - 1 >= 3), out());
    child.stdin.write("what was the word?\n");
    assert.ok(await until(() => out().includes("answer 2")), out());
    assert.ok(requests[1]!.some((u) => u.includes("pineapple")), `the conversation survived the refresh: ${JSON.stringify(requests[1])}`);
    child.stdin.end();
    assert.equal(await exited, 0);
  } finally {
    child.kill("SIGKILL");
    server.close();
  }
});
