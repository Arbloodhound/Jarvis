import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { isUploadPath, ownerContext, parseRefs, resolveAttachments, resolveRefs, sniffKind, storeUpload, type ContextDeps } from "../src/context.ts";
import { Conversations } from "../src/conversations.ts";
import { Agent, type RunHooks } from "../src/agent/loop.ts";
import { AuditLog } from "../src/gate/audit.ts";
import { Gate } from "../src/gate/gate.ts";
import { Memory } from "../src/memory.ts";
import type { Provider, Turn, TurnResult } from "../src/providers/types.ts";
import type { Vision } from "../src/vision.ts";

/**
 * Owner-provided context: @-references, attachments, uploads — and the
 * conversation branches they ride in on.
 */

const home = mkdtempSync(path.join(tmpdir(), "jarvis-context-"));
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64");

const stubVision = {
  label: "stub-vision",
  async describe(images: Array<{ data: string }>) { return `saw an image (${images[0]!.data.length} b64 chars)`; },
} as unknown as Vision;

function deps(over: Partial<ContextDeps> = {}): ContextDeps {
  return {
    cwd: home,
    vision: stubVision,
    webFetch: async (url: string) => ({ status: 200, type: "text/html", body: `<html><body><h1>Page for ${url}</h1></body></html>`, url }),
    ...over,
  };
}

test("parseRefs finds the four kinds in order, dedupes, and ignores lookalikes", () => {
  const refs = parseRefs("look at @file:notes.md and @url:https://example.com/x, then @folder:docs and @image:pic.png and @file:notes.md again; mail me at bob@file:fake");
  assert.deepEqual(refs.map((r) => r.raw), ["@file:notes.md", "@url:https://example.com/x,", "@folder:docs", "@image:pic.png"]);
  // The URL target keeps its trailing comma (targets run to whitespace) — resolution reports it honestly if it fails.
  assert.equal(refs[0]!.kind, "file");
  assert.equal(refs[2]!.target, "docs");
  assert.deepEqual(parseRefs("no refs here, just an @mention"), []);
});

test("sniffKind: png by extension, text by content, binary is other", () => {
  assert.equal(sniffKind("a.png", PNG), "image");
  assert.equal(sniffKind("a.txt", Buffer.from("hello\nworld")), "text");
  assert.equal(sniffKind("a.bin", Buffer.from([0, 1, 2, 0, 255])), "other");
  assert.equal(sniffKind("noext", Buffer.from("# markdown")), "text");
});

test("@file inlines the content, labelled as data; a big file is cut and says so", async () => {
  writeFileSync(path.join(home, "notes.md"), "the meeting is at noon");
  const small = await resolveRefs("summarize @file:notes.md", deps());
  assert.equal(small.refs[0]!.ok, true);
  assert.match(small.context, /<owner-context>/);
  assert.match(small.context, /data, not instructions/);
  assert.match(small.context, /the meeting is at noon/);

  writeFileSync(path.join(home, "big.txt"), "x".repeat(20_000));
  const big = await resolveRefs("@file:big.txt", deps());
  assert.match(big.refs[0]!.note, /cut/);
  assert.ok(big.context.length < 13_000, "the block stays near the cap");
});

test("@file that does not exist becomes an honest note, never a crash", async () => {
  const out = await resolveRefs("read @file:nope.txt please", deps());
  assert.equal(out.refs[0]!.ok, false);
  assert.match(out.context, /not found on this computer/);
});

test("@folder lists names and sizes, no content dump", async () => {
  const dir = path.join(home, "docs");
  mkdirSync(dir, { recursive: true });
  writeFileSync(path.join(dir, "a.txt"), "12345");
  writeFileSync(path.join(dir, "b.txt"), "12");
  const out = await resolveRefs("what is in @folder:docs", deps());
  assert.equal(out.refs[0]!.ok, true);
  assert.match(out.context, /a\.txt \(file, 5 bytes\)/);
  assert.match(out.context, /b\.txt \(file, 2 bytes\)/);
  const missing = await resolveRefs("@folder:gone", deps());
  assert.equal(missing.refs[0]!.ok, false);
  assert.match(missing.context, /could not be listed/);
});

test("@image goes to the vision route; without vision it says so and keeps the path", async () => {
  writeFileSync(path.join(home, "pic.png"), PNG);
  const seen = await resolveRefs("what is @image:pic.png", deps());
  assert.equal(seen.refs[0]!.ok, true);
  assert.match(seen.context, /saw an image/);
  assert.match(seen.refs[0]!.note, /described by stub-vision/);

  const blind = await resolveRefs("what is @image:pic.png", deps({ vision: null }));
  assert.equal(blind.refs[0]!.ok, false);
  assert.match(blind.context, /no vision model is configured/);
  assert.match(blind.context, /pic\.png/);
});

test("@url fetches through the given transport; offline and failure are honest notes", async () => {
  const ok = await resolveRefs("read @url:https://example.com", deps());
  assert.equal(ok.refs[0]!.ok, true);
  assert.match(ok.context, /Page for https:\/\/example\.com/);

  const offline = await resolveRefs("read @url:https://example.com", deps({ webFetch: null }));
  assert.equal(offline.refs[0]!.ok, false);
  assert.match(offline.context, /offline only is on/);

  const failed = await resolveRefs("read @url:https://example.com", deps({
    webFetch: async () => { throw new Error("That is a .onion address…"); },
  }));
  assert.equal(failed.refs[0]!.ok, false);
  assert.match(failed.context, /could not be fetched/);
});

test("attachments: text inlines, image is described, binary is a path pointer, missing is a note", async () => {
  writeFileSync(path.join(home, "a.txt"), "attached words");
  writeFileSync(path.join(home, "a.png"), PNG);
  writeFileSync(path.join(home, "a.bin"), Buffer.from([0, 9, 0, 9]));
  const out = await resolveAttachments(
    [
      { path: path.join(home, "a.txt"), name: "a.txt" },
      { path: path.join(home, "a.png"), name: "a.png" },
      { path: path.join(home, "a.bin"), name: "a.bin" },
      { path: path.join(home, "gone.txt"), name: "gone.txt" },
    ],
    deps(),
  );
  assert.match(out.context, /attached words/);
  assert.match(out.context, /saw an image/);
  assert.match(out.context, /not text — referenced by path/);
  assert.match(out.context, /gone\.txt[\s\S]*not found/);
  assert.deepEqual(out.refs.map((r) => r.ok), [true, true, true, false]);

  const combined = await ownerContext("see @file:a.txt", [{ path: path.join(home, "a.txt"), name: "a.txt" }], deps());
  assert.equal((combined.match(/<owner-context>/g) ?? []).length, 2);
});

test("storeUpload stamps and sanitizes the name, detects kind, never overwrites", () => {
  const one = storeUpload(home, "../../evil notes!!.txt", Buffer.from("hi"));
  assert.ok(one.path.startsWith(path.join(home, "uploads")));
  assert.ok(!one.name.includes("/") && !one.name.includes(" "));
  assert.equal(one.kind, "text");
  assert.equal(readFileSync(one.path, "utf8"), "hi");
  const two = storeUpload(home, "evil notes!!.txt", Buffer.from("hi again"));
  assert.notEqual(two.path, one.path);
  assert.equal(storeUpload(home, "p.png", PNG).kind, "image");
  assert.equal(storeUpload(home, "x.bin", Buffer.from([0, 0])).kind, "other");
  assert.equal(isUploadPath(home, one.path), true);
  assert.equal(isUploadPath(home, path.join(home, "config.json")), false);
  assert.equal(isUploadPath(home, "/etc/passwd"), false);
});

// ---------- conversation branches ----------

const turn = (text: string): Turn[] => [
  { role: "user", text },
  { role: "assistant", text: `answer to ${text}`, toolCalls: [], brain: "local" },
];

test("conversations: fork shares the prefix, then the branches diverge", () => {
  const file = path.join(home, "branches-1.json");
  const c = new Conversations(file);
  assert.equal(c.list().length, 1);
  assert.equal(c.active().name, "main");
  c.setActiveTurns([...turn("one"), ...turn("two")]);
  const fork = c.fork(c.activeTurns(), 2, "what-if");
  assert.equal(fork.name, "what-if");
  assert.equal(c.activeId, fork.id);
  assert.deepEqual(c.activeTurns(), turn("one"), "the fork holds only the shared prefix");
  c.setActiveTurns([...turn("one"), ...turn("three")]);
  const back = c.switchTo(c.list().find((b) => b.name === "main")!.id);
  assert.deepEqual(back, [...turn("one"), ...turn("two")], "main kept its own continuation");
  const fwd = c.switchTo(fork.id);
  assert.deepEqual(fwd, [...turn("one"), ...turn("three")], "the fork diverged independently");
  assert.equal(c.switchTo("no-such-id"), null);
});

test("conversations: persist across instances; the last branch cannot be deleted", () => {
  const file = path.join(home, "branches-2.json");
  const c = new Conversations(file);
  c.setActiveTurns(turn("kept"));
  const fork = c.fork(c.activeTurns(), 2);
  assert.equal(c.remove(fork.id)?.name, "main", "deleting the active fork falls back to main");
  assert.equal(c.remove(c.activeId), null, "the last branch stays");

  const again = new Conversations(file);
  assert.deepEqual(again.list().map((b) => b.name), ["main"]);
  assert.deepEqual(again.activeTurns(), turn("kept"), "turns survived the reload");

  const two = new Conversations(path.join(home, "branches-3.json"));
  two.setActiveTurns(turn("x"));
  const f2 = two.fork(two.activeTurns(), 0);
  const reloaded = new Conversations(path.join(home, "branches-3.json"));
  assert.equal(reloaded.list().length, 2);
  assert.equal(reloaded.activeId, f2.id, "the active branch survived the reload");
  assert.equal(reloaded.rename(f2.id, "renamed"), true);
  assert.equal(reloaded.list().find((b) => b.id === f2.id)!.name, "renamed");
});

// ---------- agent: extra context + loadTurns ----------

test("agent: extra context rides the stored user turn; loadTurns repairs a mid-step tail", async () => {
  const provider: Provider = { kind: "local", label: "stub", async turn(): Promise<TurnResult> { return { text: "ok", stop: "end", toolCalls: [] }; } };
  const agent = new Agent({
    provider: () => provider,
    embedder: () => null,
    vision: () => null,
    tools: () => [],
    gate: new Gate({ writableRoots: [home], extraAllowedCommands: [] }, home),
    memory: new Memory(null),
    audit: new AuditLog(null),
    home, workspace: home, cwd: home, maxTurns: 2,
  });
  const hooks: RunHooks = { emit: () => {}, confirm: async () => false };
  await agent.run("hello", hooks, "<owner-context>\n[file: x — inlined]\nwords\n</owner-context>");
  const first = agent.turns[0]!;
  assert.equal(first.role, "user");
  assert.match(first.role === "user" ? first.text : "", /owner-context/);

  agent.loadTurns([
    { role: "user", text: "a" },
    { role: "assistant", text: "", toolCalls: [{ id: "1", name: "x", input: {} }], brain: "local" },
  ]);
  assert.equal(agent.turns.length, 4, "the dangling tool call was closed and the exchange settled");
  assert.equal(agent.turns[2]!.role, "tool");
});

// ---------- end to end through the gateway ----------

import { createServer, type Server } from "node:http";
import { createServer as netServer } from "node:net";
import { WebSocket } from "ws";
import { loadConfig, paths, type Paths } from "../src/config.ts";
import { Runtime } from "../src/runtime.ts";
import { startServer, type Gateway } from "../src/server.ts";

const TOKEN = "context-test-token";

async function freePort(): Promise<number> {
  return new Promise((resolve) => {
    const s = netServer().listen(0, "127.0.0.1", () => {
      const a = s.address();
      s.close(() => resolve(typeof a === "object" && a ? a.port : 0));
    });
  });
}

interface Rig {
  home: string;
  p: Paths;
  port: number;
  gw: Gateway;
  modelBodies: Array<{ messages: Array<{ role: string; content?: string }> }>;
  close: () => void;
}

/** A fake local brain (SSE) that records what it was sent, and a fake vision server. */
async function rig(home: string): Promise<Rig> {
  const modelBodies: Rig["modelBodies"] = [];
  const model = createServer((req, res) => {
    let body = "";
    req.on("data", (d) => (body += d));
    req.on("end", () => {
      modelBodies.push(JSON.parse(body));
      res.writeHead(200, { "content-type": "text/event-stream" });
      res.end(
        `data: ${JSON.stringify({ choices: [{ delta: { content: "model reply" } }] })}\n\n` +
        `data: ${JSON.stringify({ choices: [{ delta: {}, finish_reason: "stop" }] })}\n\n` +
        "data: [DONE]\n\n",
      );
    });
  });
  const vision = createServer((req, res) => {
    let body = "";
    req.on("data", (d) => (body += d));
    req.on("end", () => {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ choices: [{ message: { content: "a red circle on white" } }] }));
    });
  });
  await new Promise<void>((r) => model.listen(0, "127.0.0.1", () => r()));
  await new Promise<void>((r) => vision.listen(0, "127.0.0.1", () => r()));
  const p = paths(home);
  const cfg = loadConfig(p);
  cfg.local.baseUrl = `http://127.0.0.1:${(model.address() as { port: number }).port}/v1`;
  cfg.vision.baseUrl = `http://127.0.0.1:${(vision.address() as { port: number }).port}`;
  const port = await freePort();
  cfg.port = port;
  const gw = await startServer(new Runtime(cfg, p, home), TOKEN, port);
  return { home, p, port, gw, modelBodies, close: () => { gw.server.close(); model.close(); vision.close(); } };
}

type Ev = Record<string, unknown> & { type: string };

function connect(port: number): Promise<{ ws: WebSocket; events: Ev[]; waitFor: (pred: (e: Ev) => boolean, ms?: number) => Promise<Ev> }> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=${TOKEN}`);
    const events: Ev[] = [];
    const waiters: Array<{ pred: (e: Ev) => boolean; done: (e: Ev) => void }> = [];
    let cursor = 0; // each waitFor only sees events after the previous one's match
    ws.on("message", (raw) => {
      const e = JSON.parse(raw.toString()) as Ev;
      events.push(e);
      for (let i = waiters.length - 1; i >= 0; i--) {
        if (waiters[i]!.pred(e)) { const w = waiters.splice(i, 1)[0]!; w.done(e); }
      }
    });
    const waitFor = (pred: (e: Ev) => boolean, ms = 15_000) => {
      const idx = events.findIndex((e, i) => i >= cursor && pred(e));
      if (idx >= 0) { cursor = idx + 1; return Promise.resolve(events[idx]!); }
      return new Promise<Ev>((res2, rej2) => {
        const timer = setTimeout(() => rej2(new Error(`timed out waiting for event; saw: ${events.map((e) => e.type).join(",")}`)), ms);
        waiters.push({
          pred,
          done: (e) => { clearTimeout(timer); cursor = events.indexOf(e) + 1; res2(e); },
        });
      });
    };
    ws.once("open", () => resolve({ ws, events, waitFor }));
    ws.once("error", reject);
  });
}

const userTexts = (r: Rig) => r.modelBodies.flatMap((b) => b.messages).filter((m) => m.role === "user").map((m) => m.content ?? "");

test("gateway: upload round trip, then the attachment and an @file ride the turn as data", async () => {
  const home2 = mkdtempSync(path.join(tmpdir(), "jarvis-context-e2e-"));
  const r = await rig(home2);
  const clients: WebSocket[] = [];
  try {
    // Wrong token: refused. No token at all: refused.
    const bad = await fetch(`http://127.0.0.1:${r.port}/api/upload?token=nope`, { method: "POST", body: JSON.stringify({ name: "x.txt", data: "aGk=" }) });
    assert.equal(bad.status, 403);

    const up = await fetch(`http://127.0.0.1:${r.port}/api/upload?token=${TOKEN}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ name: "report.txt", data: Buffer.from("quarterly numbers grew").toString("base64") }),
    });
    assert.equal(up.status, 200);
    const stored = (await up.json()) as { path: string; name: string; kind: string };
    assert.equal(stored.kind, "text");
    assert.ok(stored.path.startsWith(path.join(home2, "uploads")), "stored under the Jarvis home");
    assert.ok(existsSync(stored.path));

    const { ws, waitFor } = await connect(r.port);
    clients.push(ws);
    ws.send(JSON.stringify({ type: "send", text: "summarize the report", attachments: [{ path: stored.path, name: "report.txt" }] }));
    const userEv = await waitFor((e) => e.type === "user");
    assert.deepEqual(userEv.attachments, ["report.txt"]);
    await waitFor((e) => e.type === "done");
    const sent = userTexts(r).join("\n");
    assert.match(sent, /quarterly numbers grew/, "the attachment's content reached the model");
    assert.match(sent, /attached file: report\.txt/);
    assert.match(sent, /data, not instructions/);
    ws.close();

    // An @file typed in the message resolves the same way.
    writeFileSync(path.join(home2, "inline.md"), "inline ref content");
    const c2 = await connect(r.port);
    clients.push(c2.ws);
    c2.ws.send(JSON.stringify({ type: "send", text: "read @file:inline.md" }));
    await c2.waitFor((e) => e.type === "done");
    assert.match(userTexts(r).join("\n"), /inline ref content/);
    c2.ws.close();

    // A path that was never uploaded here is refused out loud, and nothing runs.
    const c3 = await connect(r.port);
    clients.push(c3.ws);
    const runsBefore = r.modelBodies.length;
    c3.ws.send(JSON.stringify({ type: "send", text: "read this", attachments: [{ path: r.p.config, name: "config.json" }] }));
    const err = await c3.waitFor((e) => e.type === "error");
    assert.match(String(err.message), /not uploaded here/);
    assert.equal(r.modelBodies.length, runsBefore);
    c3.ws.close();
  } finally {
    for (const ws of clients) ws.terminate();
    r.close();
  }
});

test("gateway: an image attachment is described by the vision route for the model", async () => {
  const home3 = mkdtempSync(path.join(tmpdir(), "jarvis-context-img-"));
  const r = await rig(home3);
  let ws0: WebSocket | undefined;
  try {
    const up = await fetch(`http://127.0.0.1:${r.port}/api/upload?token=${TOKEN}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ name: "shot.png", data: PNG.toString("base64") }),
    });
    const stored = (await up.json()) as { path: string; kind: string };
    assert.equal(stored.kind, "image");
    const conn = await connect(r.port);
    ws0 = conn.ws;
    const { ws, waitFor } = conn;
    ws.send(JSON.stringify({ type: "send", text: "what is in this picture?", attachments: [{ path: stored.path, name: "shot.png" }] }));
    await waitFor((e) => e.type === "done");
    assert.match(userTexts(r).join("\n"), /a red circle on white/, "the vision description rode the turn");
    ws.close();
  } finally {
    ws0?.terminate();
    r.close();
  }
});

test("gateway: branches fork, diverge, switch losslessly, and survive a restart", async () => {
  const home4 = mkdtempSync(path.join(tmpdir(), "jarvis-context-branch-"));
  let r = await rig(home4);
  const clients: WebSocket[] = [];
  const ask = async (c: Awaited<ReturnType<typeof connect>>, text: string) => {
    c.ws.send(JSON.stringify({ type: "send", text }));
    await c.waitFor((e) => e.type === "done");
    await c.waitFor((e) => e.type === "turns");
  };
  try {
    const c = await connect(r.port);
    clients.push(c.ws);
    await ask(c, "first question");
    await ask(c, "second question");

    // Fork keeping only the first exchange; the new branch becomes active.
    c.ws.send(JSON.stringify({ type: "branch_fork", keepTurns: 2 }));
    const st = await c.waitFor((e) => e.type === "state" && Array.isArray(e.branches) && (e.branches as unknown[]).length === 2);
    const hist = await c.waitFor((e) => e.type === "history");
    assert.equal((hist.entries as unknown[]).length, 2, "the fork replays just the shared prefix");
    const branches = st.branches as Array<{ id: string; name: string; active: boolean }>;
    const forkId = branches.find((b) => b.active)!.id;
    const mainId = branches.find((b) => !b.active)!.id;

    await ask(c, "third question"); // lands in the fork only

    c.ws.send(JSON.stringify({ type: "branch_switch", id: mainId }));
    const mainHist = await c.waitFor((e) => e.type === "history" && e.branch === "main");
    const mainTexts = (mainHist.entries as Array<{ text: string }>).map((e) => e.text);
    assert.ok(mainTexts.includes("second question") && !mainTexts.includes("third question"), "main kept its own line");

    c.ws.send(JSON.stringify({ type: "branch_switch", id: forkId }));
    const forkHist = await c.waitFor((e) => e.type === "history" && e.branch !== "main");
    const forkTexts = (forkHist.entries as Array<{ text: string }>).map((e) => e.text);
    assert.ok(forkTexts.includes("third question") && !forkTexts.includes("second question"), "the fork diverged");

    // Delete the active fork: the conversation falls back to main.
    c.ws.send(JSON.stringify({ type: "branch_delete", id: forkId }));
    await c.waitFor((e) => e.type === "state" && Array.isArray(e.branches) && (e.branches as unknown[]).length === 1);
    c.ws.close();
  } finally {
    for (const ws of clients) ws.terminate();
    r.close();
  }

  // Restart on the same home: main's history and the branch list are still there.
  r = await rig(home4);
  try {
    const c = await connect(r.port);
    clients.push(c.ws);
    const st = await c.waitFor((e) => e.type === "state");
    assert.equal((st.branches as unknown[]).length, 1);
    const hist = await c.waitFor((e) => e.type === "history");
    const texts = (hist.entries as Array<{ text: string }>).map((e) => e.text);
    assert.ok(texts.includes("first question") && texts.includes("second question"), "history survived the restart");
    assert.ok(!texts.includes("third question"), "the deleted fork stayed deleted");
    c.ws.close();
  } finally {
    for (const ws of clients) ws.terminate();
    r.close();
  }
});
