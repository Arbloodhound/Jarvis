import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { existsSync, mkdtempSync, writeFileSync } from "node:fs";
import { createServer, type Server } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { after, before, test } from "node:test";

/**
 * The terminal front-ends with subagents, as real subprocesses against a
 * stand-in local model (OpenAI-style SSE):
 *
 *  - bug 1: in `jarvis chat`, a subagent's card raised while the chat sat at
 *    "you ›" never resolved (readline answers one question at a time), and
 *    the owner's "y" went to the main agent as the request "y".
 *  - bug 3: `jarvis ask` exited with children still running, so their
 *    hand-offs were never seen.
 */

const PKG = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const CLI = path.join(PKG, "src", "cli.ts");
const strip = (s: string) => s.replace(/\x1b\[[0-9;]*m/g, "");

let server: Server;
let baseUrl = "";
let marker = "";
/** Every user message text the PARENT agent sent the model. */
const parentUsers: string[] = [];

function sse(chunks: unknown[]): string {
  return chunks.map((c) => `data: ${JSON.stringify(c)}\n\n`).join("") + "data: [DONE]\n\n";
}
const toolCall = (id: string, name: string, args: Record<string, unknown>) => sse([
  { choices: [{ delta: { tool_calls: [{ index: 0, id, function: { name, arguments: JSON.stringify(args) } }] } }] },
  { choices: [{ delta: {}, finish_reason: "tool_calls" }] },
]);
const say = (text: string) => sse([
  { choices: [{ delta: { content: text } }] },
  { choices: [{ delta: {}, finish_reason: "stop" }] },
]);

type Msg = { role: string; content?: unknown };
const textOf = (m: Msg): string => (typeof m.content === "string" ? m.content : JSON.stringify(m.content ?? ""));

before(async () => {
  server = createServer((req, res) => {
    let body = "";
    req.on("data", (d) => (body += d));
    req.on("end", () => {
      const msgs = (JSON.parse(body) as { messages: Msg[] }).messages;
      const child = msgs.some((m) => m.role === "system" && textOf(m).includes("You are a subagent"));
      const toolDone = msgs.some((m) => m.role === "tool");
      res.writeHead(200, { "content-type": "text/event-stream" });
      if (child) {
        // The child's first step comes late, so the parent's run has ended
        // and the chat is back at "you ›" when the child's card appears.
        if (!toolDone) setTimeout(() => res.end(toolCall("c1", "run_command", { command: `touch ${marker}` })), 400);
        else res.end(say("child finished the job"));
        return;
      }
      for (const m of msgs) if (m.role === "user") parentUsers.push(textOf(m));
      if (!toolDone) res.end(toolCall("p1", "spawn_agent", { task: "touch the marker file", name: "toucher" }));
      else res.end(say("Delegated."));
    });
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", () => r()));
  baseUrl = `http://127.0.0.1:${(server.address() as { port: number }).port}/v1`;
});

after(() => server?.close());

function freshHome(): string {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-cli-chat-"));
  writeFileSync(path.join(home, "config.json"), JSON.stringify({ local: { baseUrl, model: "fake-1" } }));
  return home;
}

/** A CLI subprocess whose stdin stays open: the test types into it as the output asks. */
function interactive(args: string[], home: string) {
  const child = spawn(process.execPath, ["--import", "tsx", CLI, ...args], {
    cwd: PKG,
    env: { PATH: process.env.PATH ?? "", HOME: process.env.HOME ?? "", JARVIS_HOME: home },
  });
  let buf = "";
  child.stdout.on("data", (d) => (buf += d));
  child.stderr.on("data", (d) => (buf += d));
  const exited = new Promise<number | null>((resolve) => child.on("close", resolve));
  const out = () => strip(buf);
  const count = (s: string) => out().split(s).length - 1;
  async function waitFor(pred: () => boolean, ms = 30_000): Promise<boolean> {
    const t0 = Date.now();
    while (!pred()) {
      if (Date.now() - t0 > ms) return false;
      await new Promise((r) => setTimeout(r, 20));
    }
    return true;
  }
  return { child, out, count, waitFor, exited, type: (line: string) => child.stdin.write(`${line}\n`) };
}

test("bug 1: in chat, a subagent's card raised at the idle prompt takes the next line, and the chat goes on", { timeout: 120_000 }, async () => {
  parentUsers.length = 0;
  const home = freshHome();
  marker = path.join(home, "touched.txt");
  const cli = interactive(["chat"], home);
  try {
    assert.ok(await cli.waitFor(() => cli.out().includes("you › ")), cli.out());
    cli.type("delegate the marker job");
    // The main agent's own spawn card (no overlap yet — this always worked).
    assert.ok(await cli.waitFor(() => cli.out().includes("Allow? [y/N]")), cli.out());
    cli.type("y");
    // The main run ends and the chat waits at "you ›"; then the child's card appears.
    assert.ok(await cli.waitFor(() => cli.out().includes("(via subagent \"toucher\")")), cli.out());
    await new Promise((r) => setTimeout(r, 200));
    cli.type("y");
    const handedOff = await cli.waitFor(() => cli.out().includes("[Hand-off from subagent \"toucher\""), 8000);
    // After the card, the chat prompt comes back and still works.
    if (handedOff) await cli.waitFor(() => cli.count("you › ") >= 2, 5000);
    cli.child.stdin.end();
    await cli.exited;
    const out = cli.out();
    assert.ok(!parentUsers.some((u) => u.endsWith("\n\ny")), `the card's "y" must never reach the main agent as a request: ${JSON.stringify(parentUsers)}`);
    assert.ok(existsSync(marker), `the owner's "y" answered the subagent's card, so its command ran:\n${out}`);
    assert.ok(handedOff, `the child's hand-off was printed:\n${out}`);
    assert.ok(out.includes("DONE (not verified)"), out);
    assert.ok(cli.count("Allow? [y/N]") >= 2, `both cards were shown with their own question:\n${out}`);
  } finally {
    cli.child.kill("SIGKILL");
  }
});

test("bug 3: jarvis ask waits for a running child and prints its hand-off before exiting", { timeout: 120_000 }, async () => {
  parentUsers.length = 0;
  const home = freshHome();
  marker = path.join(home, "ask-touched.txt");
  const cli = interactive(["ask", "delegate the marker job"], home);
  try {
    assert.ok(await cli.waitFor(() => cli.out().includes("Allow? [y/N]")), cli.out());
    cli.type("y"); // the spawn card
    assert.ok(await cli.waitFor(() => cli.out().includes("(via subagent \"toucher\")")), cli.out());
    await new Promise((r) => setTimeout(r, 200));
    cli.type("y"); // the child's card
    const code = await Promise.race([cli.exited, new Promise<"hung">((r) => setTimeout(() => r("hung"), 20_000))]);
    const out = cli.out();
    assert.equal(code, 0, out);
    assert.ok(out.includes("Waiting up to"), `ask says it is waiting for the child:\n${out}`);
    assert.ok(out.includes("[Hand-off from subagent \"toucher\""), `the hand-off was printed before exit:\n${out}`);
    assert.ok(out.includes("child finished the job"), out);
    assert.ok(existsSync(marker));
  } finally {
    cli.child.kill("SIGKILL");
  }
});
