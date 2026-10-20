import { timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import { createServer, type IncomingMessage, type Server } from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocketServer, type WebSocket } from "ws";
import { redactArgs, type AgentEvent } from "./agent/loop.js";
import { redactSecrets } from "./gate/secrets.js";
import { startupWarnings } from "./config.js";
import type { Runtime } from "./runtime.js";

/**
 * The gateway: one HTTP + WebSocket server on 127.0.0.1. It serves the chat
 * page and streams agent events to every connected client (the page, and
 * later the phone app as a thin client, the way OpenClaw's phone talks to its
 * gateway).
 *
 * Who may talk to it:
 * - Only this computer: it binds to 127.0.0.1.
 * - Only with the token from ~/.jarvis/token.
 * - Not a web page you happen to visit: the Host header must be this server
 *   (stops DNS rebinding) and a browser's Origin must be this server (stops
 *   other sites from driving it).
 */

/** An unanswered card is declined after 10 minutes, so a forgotten card can't hold Jarvis forever. */
const CARD_TIMEOUT_MS = 10 * 60 * 1000;

const UI_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), "ui");

type ClientMsg =
  | { type: "send"; text: string }
  | { type: "confirm"; cardId: string; allow: boolean }
  | { type: "stop" }
  | { type: "settings"; brain?: "local" | "cloud"; offlineOnly?: boolean; embeddings?: boolean; screenControl?: boolean; tor?: boolean }
  | { type: "set_model"; which: "local" | "cloud"; model: string }
  | { type: "list_models" }
  | { type: "state" }
  | { type: "reset" };

function sameToken(a: string, b: string): boolean {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

export interface ServerOptions {
  /** Every event the gateway broadcasts, for the desktop app (tray, notifications). */
  onEvent?: (e: { type: string } & Record<string, unknown>) => void;
  /** When the port is taken, take any free one instead of failing. */
  fallbackPort?: boolean;
}

export interface Gateway {
  server: Server;
  port: number;
  url: string;
  /** Tell every client the settings or busy state changed (e.g. from the tray menu). */
  pushState: () => void;
}

export async function startServer(rt: Runtime, token: string, port: number, opts: ServerOptions = {}): Promise<Gateway> {
  const host = "127.0.0.1";
  // If Tor was left on in config, learn early whether its proxy answers.
  if (rt.cfg.tor.enabled) void rt.refreshTorStatus();
  // Filled in once the real port is known (it may be a fallback).
  const allowedHosts = new Set<string>();
  const allowedOrigins = new Set<string>();
  const hostOk = (req: IncomingMessage) => allowedHosts.has(req.headers.host ?? "");

  const server = createServer((req, res) => {
    if (!hostOk(req)) { res.writeHead(421).end("Wrong host."); return; }
    const url = new URL(req.url ?? "/", `http://${req.headers.host}`);
    if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/index.html")) {
      res.writeHead(200, {
        "content-type": "text/html; charset=utf-8",
        "cache-control": "no-store",
        "content-security-policy": "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self' ws://127.0.0.1:* ws://localhost:*; img-src 'self' data:",
        "x-frame-options": "DENY",
        "referrer-policy": "no-referrer",
      });
      res.end(readFileSync(path.join(UI_DIR, "index.html")));
      return;
    }
    if (url.pathname === "/favicon.ico") { res.writeHead(204).end(); return; }
    if (req.method === "GET" && url.pathname === "/api/health") {
      res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify({ ok: true }));
      return;
    }
    res.writeHead(404).end("Not found.");
  });

  const wss = new WebSocketServer({ noServer: true, maxPayload: 1024 * 1024 });
  const clients = new Set<WebSocket>();
  /** Open confirm cards: re-sent to a page that reconnects, declined after CARD_TIMEOUT_MS. */
  const pending = new Map<string, { resolve: (allow: boolean) => void; card: Record<string, unknown>; timer: NodeJS.Timeout }>();
  const settleCard = (id: string, allow: boolean) => {
    const p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    clearTimeout(p.timer);
    p.resolve(allow);
  };

  /**
   * The wire copy of an event. Tool-call payloads (tool_start, confirm cards)
   * reach every connected client here — and confirm cards are re-sent from
   * `pending` — while cards and the audit log only ever show tool args
   * redacted. So the broadcast gets the same treatment: args redacted with
   * the loop's helper, and a card's reason (which quotes the raw command)
   * passed through redactSecrets. The copies the agent loop and the owner
   * at the terminal work with are untouched; this shapes only what leaves.
   */
  const forWire = (msg: Record<string, unknown>): Record<string, unknown> => {
    let out = msg;
    if (msg.args && typeof msg.args === "object" && !Array.isArray(msg.args)) {
      out = { ...out, args: redactArgs(msg.args as Record<string, unknown>) };
    }
    if (msg.type === "confirm" && typeof msg.reason === "string") {
      out = { ...out, reason: redactSecrets(msg.reason).text };
    }
    return out;
  };

  const broadcast = (msg: { type: string } & Record<string, unknown>) => {
    const out = forWire(msg) as { type: string } & Record<string, unknown>;
    opts.onEvent?.(out);
    const data = JSON.stringify(out);
    for (const c of clients) if (c.readyState === c.OPEN) c.send(data);
  };

  server.on("upgrade", (req, socket, head) => {
    const url = new URL(req.url ?? "/", `http://${req.headers.host}`);
    const origin = req.headers.origin;
    const tok = url.searchParams.get("token") ?? "";
    if (url.pathname !== "/ws" || !hostOk(req) || (origin && !allowedOrigins.has(origin)) || !sameToken(tok, token)) {
      socket.write("HTTP/1.1 403 Forbidden\r\n\r\n");
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws));
  });

  wss.on("connection", (ws: WebSocket) => {
    clients.add(ws);
    ws.send(JSON.stringify({ type: "state", ...rt.state() }));
    for (const w of startupWarnings) ws.send(JSON.stringify({ type: "error", runId: "", message: w }));
    for (const p of pending.values()) ws.send(JSON.stringify(forWire(p.card)));
    ws.on("close", () => clients.delete(ws));
    ws.on("message", (raw) => {
      let msg: ClientMsg;
      try { msg = JSON.parse(raw.toString()) as ClientMsg; } catch { return; }
      if (msg.type === "send" && typeof msg.text === "string" && msg.text.trim()) {
        if (rt.agent.busy) { ws.send(JSON.stringify({ type: "error", runId: "", message: "Still working on the last request. Stop it first." })); return; }
        broadcast({ type: "user", text: msg.text });
        void rt.agent.run(msg.text.trim(), {
          emit: (e: AgentEvent) => broadcast(e as AgentEvent & Record<string, unknown>),
          confirm: (card) => new Promise<boolean>((resolve) => {
            const timer = setTimeout(() => settleCard(card.cardId, false), CARD_TIMEOUT_MS);
            pending.set(card.cardId, { resolve, card: card as typeof card & Record<string, unknown>, timer });
            broadcast(card as typeof card & Record<string, unknown>);
          }),
        }).finally(() => {
          // A stopped run can leave a card nobody answered; close it as declined.
          for (const id of [...pending.keys()]) settleCard(id, false);
          broadcast({ type: "state", ...rt.state() });
        });
      } else if (msg.type === "confirm" && typeof msg.cardId === "string") {
        settleCard(msg.cardId, msg.allow === true);
      } else if (msg.type === "stop") {
        rt.agent.stop();
      } else if (msg.type === "settings") {
        rt.update(msg);
        broadcast({ type: "state", ...rt.state() });
        // A Tor toggle also refreshes the proxy probe, then re-pushes state
        // so the settings row can show whether a proxy actually answers.
        if (typeof msg.tor === "boolean") {
          void rt.refreshTorStatus().then(() => broadcast({ type: "state", ...rt.state() }));
        }
      } else if (msg.type === "set_model" && (msg.which === "local" || msg.which === "cloud") && typeof msg.model === "string") {
        rt.setModel(msg.which, msg.model);
        broadcast({ type: "state", ...rt.state() });
      } else if (msg.type === "list_models") {
        rt.listModels().then(
          (models) => ws.send(JSON.stringify({ type: "models", models })),
          (err) => ws.send(JSON.stringify({ type: "models", models: [], error: err instanceof Error ? err.message : String(err) })),
        );
      } else if (msg.type === "reset") {
        if (rt.agent.busy) { ws.send(JSON.stringify({ type: "error", runId: "", message: "Stop the current request first." })); return; }
        rt.agent.reset();
        broadcast({ type: "reset" });
      } else if (msg.type === "state") {
        ws.send(JSON.stringify({ type: "state", ...rt.state() }));
      }
    });
  });

  const listen = (p: number) => new Promise<number>((resolve, reject) => {
    server.once("error", reject);
    server.listen(p, host, () => {
      server.off("error", reject);
      const a = server.address();
      resolve(typeof a === "object" && a ? a.port : p);
    });
  });
  let actual: number;
  try {
    actual = await listen(port);
  } catch (err) {
    if (!opts.fallbackPort || (err as NodeJS.ErrnoException).code !== "EADDRINUSE") throw err;
    actual = await listen(0);
  }
  for (const h of [`127.0.0.1:${actual}`, `localhost:${actual}`]) {
    allowedHosts.add(h);
    allowedOrigins.add(`http://${h}`);
  }
  return {
    server,
    port: actual,
    url: `http://127.0.0.1:${actual}/#token=${token}`,
    pushState: () => broadcast({ type: "state", ...rt.state() }),
  };
}
