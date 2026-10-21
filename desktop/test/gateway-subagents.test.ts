import assert from "node:assert/strict";
import { existsSync, mkdtempSync } from "node:fs";
import { createServer as netServer } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { WebSocket } from "ws";
import { defaults, paths } from "../src/config.ts";
import type { Provider, Turn, TurnRequest, TurnResult } from "../src/providers/types.ts";
import { Runtime } from "../src/runtime.ts";
import { startServer } from "../src/server.ts";

/**
 * The gateway with subagents:
 *
 *  - bug 7: a subagent's card left open when Stop ended the child stayed in
 *    the gateway's pending map and was re-sent to every page that
 *    reconnected — a card for a run that no longer exists.
 *  - bug 3: a hand-off reached no one until the main agent's next turn; the
 *    gateway now broadcasts a notice and fresh state the moment it lands.
 */

const TOKEN = "gw-sub-token";
const end = (text: string): TurnResult => ({ text, stop: "end", toolCalls: [] });
const callTool = (id: string, name: string, input: Record<string, unknown>): TurnResult =>
  ({ text: "", stop: "tool_use", toolCalls: [{ id, name, input }] });
const usedTool = (turns: Turn[], name: string): boolean =>
  turns.some((t) => t.role === "tool" && t.results.some((r) => r.name === name));

async function freePort(): Promise<number> {
  return new Promise((resolve) => {
    const s = netServer().listen(0, "127.0.0.1", () => {
      const a = s.address();
      s.close(() => resolve(typeof a === "object" && a ? a.port : 0));
    });
  });
}

async function until(fn: () => boolean, ms = 8000): Promise<void> {
  const t0 = Date.now();
  while (!fn()) {
    if (Date.now() - t0 > ms) throw new Error("until: timed out");
    await new Promise((r) => setTimeout(r, 10));
  }
}

async function gateway(fn: (req: TurnRequest, child: boolean) => TurnResult | Promise<TurnResult>) {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-gwsub-"));
  const p = paths(home);
  const rt = new Runtime(defaults(p), p, home);
  const b: Provider = {
    kind: "local",
    label: "fake brain",
    async turn(req) {
      await new Promise((r) => setTimeout(r, 1));
      return fn(req, req.system.includes("You are a subagent"));
    },
  };
  (rt as unknown as { provider: () => Provider }).provider = () => b;
  const port = await freePort();
  const gw = await startServer(rt, TOKEN, port);
  const connect = async () => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=${TOKEN}`, { headers: { origin: `http://127.0.0.1:${port}` } });
    const got: Array<Record<string, unknown>> = [];
    ws.on("message", (raw) => got.push(JSON.parse(raw.toString()) as Record<string, unknown>));
    await new Promise<void>((resolve, reject) => { ws.once("open", () => resolve()); ws.once("error", reject); });
    return { ws, got };
  };
  return { rt, gw, home, connect };
}

test("bug 7: a stopped subagent's open card is settled, not replayed to a page that reconnects", { timeout: 30_000 }, async () => {
  let target = "";
  const { rt, gw, home, connect } = await gateway((req, child) => {
    if (child) return callTool("w1", "write_file", { path: target, content: "x" });
    if (!usedTool(req.turns, "spawn_agent")) return callTool("s1", "spawn_agent", { task: "write the file", name: "writer" });
    return end("Delegated.");
  });
  target = path.join(home, "outside.txt");
  try {
    const page = await connect();
    page.ws.on("message", (raw) => {
      const e = JSON.parse(raw.toString()) as { type: string; tool?: string; cardId?: string };
      if (e.type === "confirm" && e.tool === "spawn_agent") page.ws.send(JSON.stringify({ type: "confirm", cardId: e.cardId, allow: true }));
    });
    page.ws.send(JSON.stringify({ type: "send", text: "delegate the write" }));
    await until(() => page.got.some((e) => e.type === "confirm" && e.tool === "write_file"));
    const card = page.got.find((e) => e.type === "confirm" && e.tool === "write_file")!;
    assert.ok(String(card.reason).includes("via subagent \"writer\""));
    await until(() => !rt.agent.busy);
    page.ws.send(JSON.stringify({ type: "stop" }));
    await until(() => rt.orchestrator.list()[0]?.state === "stopped");
    page.ws.close();

    const again = await connect();
    await new Promise((r) => setTimeout(r, 300));
    again.ws.close();
    const replayed = again.got.filter((e) => e.type === "confirm" && e.cardId === card.cardId);
    assert.deepEqual(replayed, [], "the stopped child's card is not re-sent to a reconnecting page");
    assert.equal(existsSync(target), false, "the write never happened");
  } finally {
    rt.stopAll();
    gw.server.close();
  }
});

test("bug 3: the gateway broadcasts a hand-off notice and fresh state when a child settles", { timeout: 30_000 }, async () => {
  const { rt, gw, connect } = await gateway((req, child) => {
    if (child) return end("the child's findings");
    if (!usedTool(req.turns, "spawn_agent")) return callTool("s1", "spawn_agent", { task: "find things", name: "finder" });
    return end("Delegated.");
  });
  try {
    const page = await connect();
    page.ws.on("message", (raw) => {
      const e = JSON.parse(raw.toString()) as { type: string; cardId?: string };
      if (e.type === "confirm") page.ws.send(JSON.stringify({ type: "confirm", cardId: e.cardId, allow: true }));
    });
    page.ws.send(JSON.stringify({ type: "send", text: "delegate it" }));
    await until(() => page.got.some((e) => e.type === "handoff"));
    const i = page.got.findIndex((e) => e.type === "handoff");
    const notice = page.got[i]!;
    assert.equal((notice.agent as { name: string }).name, "finder");
    assert.equal(notice.state, "done");
    assert.match(String(notice.summary), /the child's findings/);
    await until(() => page.got.slice(i).some((e) => e.type === "state"));
    page.ws.close();
  } finally {
    rt.stopAll();
    gw.server.close();
  }
});
