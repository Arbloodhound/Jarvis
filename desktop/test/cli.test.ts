import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { createServer, type Server } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { after, before, test } from "node:test";
import { loadConfig, paths } from "../src/config.ts";
import { Runtime } from "../src/runtime.ts";

/**
 * CLI-level tests, run as real subprocesses against a stand-in local model
 * (SSE, same shape as the other suites). Two regression areas:
 *
 *  - Fix 3: `jarvis ask` with piped stdin used to die with "readline was
 *    closed" when a confirm card fired after stdin EOF, and ignored a
 *    question piped in with no argv text.
 *  - Fix 5: `jarvis set-key` persists the cloud key from ANTHROPIC_API_KEY
 *    into config.json (0600) without ever printing it.
 */

const PKG = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const CLI = path.join(PKG, "src", "cli.ts");
const strip = (s: string) => s.replace(/\[[0-9;]*m/g, "");

let server: Server;
let baseUrl = "";
let calls = 0;
let marker = "";
let mode: "tool" | "text" = "tool";

function sse(chunks: unknown[]): string {
  return chunks.map((c) => `data: ${JSON.stringify(c)}\n\n`).join("") + "data: [DONE]\n\n";
}

before(async () => {
  server = createServer((req, res) => {
    let body = "";
    req.on("data", (d) => (body += d));
    req.on("end", () => {
      calls++;
      // A small delay so piped stdin reaches EOF before any card fires —
      // that ordering is the whole bug.
      setTimeout(() => {
        res.writeHead(200, { "content-type": "text/event-stream" });
        if (mode === "tool" && calls === 1) {
          res.end(sse([
            { choices: [{ delta: { tool_calls: [{ index: 0, id: "c1", function: { name: "run_command", arguments: JSON.stringify({ command: `touch ${marker}` }) } }] } }] },
            { choices: [{ delta: {}, finish_reason: "tool_calls" }] },
          ]));
        } else if (mode === "tool") {
          res.end(sse([
            { choices: [{ delta: { content: "All done." } }] },
            { choices: [{ delta: {}, finish_reason: "stop" }] },
          ]));
        } else {
          res.end(sse([
            { choices: [{ delta: { content: "Plain answer." } }] },
            { choices: [{ delta: {}, finish_reason: "stop" }] },
          ]));
        }
      }, 50);
    });
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", () => r()));
  baseUrl = `http://127.0.0.1:${(server.address() as { port: number }).port}/v1`;
});

after(() => server?.close());

function freshHome(withModel = true): string {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-cli-"));
  if (withModel) {
    writeFileSync(path.join(home, "config.json"), JSON.stringify({ local: { baseUrl, model: "fake-1" } }));
  }
  return home;
}

/**
 * Run the CLI as a real subprocess. Async on purpose: the fake model lives
 * in this process, so a blocking spawn would deadlock the child against
 * the server it is waiting on.
 */
function runCli(args: string[], home: string, input: string, extraEnv: Record<string, string> = {}): Promise<{ status: number | null; out: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ["--import", "tsx", CLI, ...args], {
      cwd: PKG,
      env: { PATH: process.env.PATH ?? "", HOME: process.env.HOME ?? "", JARVIS_HOME: home, ...extraEnv },
    });
    let buf = "";
    child.stdout.on("data", (d) => (buf += d));
    child.stderr.on("data", (d) => (buf += d));
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      reject(new Error(`CLI subprocess timed out: ${args.join(" ")}\n${strip(buf)}`));
    }, 90_000);
    child.on("error", (err) => {
      clearTimeout(timer);
      reject(err);
    });
    child.on("close", (status) => {
      clearTimeout(timer);
      resolve({ status, out: strip(buf) });
    });
    child.stdin.end(input);
  });
}

test("fix 3: piped stdin + confirm card declines safely instead of dying", { timeout: 120_000 }, async () => {
  mode = "tool";
  calls = 0;
  const home = freshHome();
  marker = path.join(home, "ran.txt");
  const r = await runCli(["ask", "clean up the folder"], home, "y\n");
  assert.equal(r.status, 0, r.out);
  assert.ok(!r.out.includes("readline was closed"), r.out);
  assert.ok(r.out.includes("Confirmation unavailable"), r.out);
  assert.ok(r.out.includes("All done."), r.out);
  assert.ok(!existsSync(marker), "the unconfirmed command must not have run");
});

test("fix 3: a question piped in with no argv text is the request", { timeout: 120_000 }, async () => {
  mode = "tool";
  calls = 0;
  const home = freshHome();
  marker = path.join(home, "ran.txt");
  const r = await runCli(["ask"], home, "clean up the folder\n");
  assert.equal(r.status, 0, r.out);
  assert.ok(!r.out.includes("Usage:"), r.out);
  assert.ok(r.out.includes("Confirmation unavailable"), r.out);
  assert.ok(r.out.includes("All done."), r.out);
  assert.ok(!existsSync(marker), "the unconfirmed command must not have run");
});

test("fix 3: piped stdin with no card behaves as before", { timeout: 120_000 }, async () => {
  mode = "text";
  calls = 0;
  const home = freshHome();
  const r = await runCli(["ask", "say hello"], home, "x\n");
  assert.equal(r.status, 0, r.out);
  assert.ok(r.out.includes("Plain answer."), r.out);
  assert.ok(!r.out.includes("readline was closed"), r.out);
});

test("fix 5: set-key saves the env key to config (0600), never prints it, and vision then routes cloud", { timeout: 120_000 }, async () => {
  const KEY = "sk-ant-save-me-in-config-0123456789";
  const home = freshHome(false);

  const saved = await runCli(["set-key"], home, "", { ANTHROPIC_API_KEY: KEY });
  assert.equal(saved.status, 0, saved.out);
  assert.ok(saved.out.includes("saved"), saved.out);
  assert.ok(!saved.out.includes(KEY), "the key must never appear in CLI output");

  const file = path.join(home, "config.json");
  const onDisk = JSON.parse(readFileSync(file, "utf8")) as { cloud: { apiKey: string } };
  assert.equal(onDisk.cloud.apiKey, KEY, "the key persisted into config.json");
  assert.equal(statSync(file).mode & 0o777, 0o600, "config.json stays mode 0600");

  // A fresh load sees it, and with NO env key the cloud vision route now works.
  const p = paths(home);
  const cfg = loadConfig(p);
  assert.equal(cfg.cloud.apiKey, KEY);
  const savedEnv = process.env.ANTHROPIC_API_KEY;
  delete process.env.ANTHROPIC_API_KEY;
  try {
    cfg.brain = "cloud";
    cfg.vision.model = "qwen3-vl";
    assert.equal(new Runtime(cfg, p, home).vision()?.kind, "cloud");
  } finally {
    if (savedEnv === undefined) delete process.env.ANTHROPIC_API_KEY;
    else process.env.ANTHROPIC_API_KEY = savedEnv;
  }

  // Status surfaces report saved/not-saved without the value.
  const where = await runCli(["where"], home, "");
  assert.equal(where.status, 0, where.out);
  assert.ok(where.out.includes("saved in config"), where.out);
  assert.ok(!where.out.includes(KEY), "status output must not carry the key");
});

test("fix 5: set-key with no env key and no terminal is a clear refusal, not a hang", { timeout: 120_000 }, async () => {
  const home = freshHome(false);
  const r = await runCli(["set-key"], home, "");
  assert.equal(r.status, 1, r.out);
  assert.ok(r.out.includes("No key to save"), r.out);
});
