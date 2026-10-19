import { timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import { createServer, type IncomingMessage, type Server } from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocketServer, type WebSocket } from "ws";
import type { AgentEvent } from "./agent/loop.js";
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

const UI_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), "ui");

type ClientMsg =
  | { type: "send"; text: string }
  | { type: "confirm"; cardId: string; allow: boolean }
  | { type: "stop" }
  | { type: "settings"; brain?: "local" | "cloud"; offlineOnly?: boolean }
  | { type: "state" };

function sameToken(a: string, b: string): boolean {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

export function startServer(rt: Runtime, token: string, port: number, host = "127.0.0.1"): Promise<Server> {
  const allowedHosts = new Set([`127.0.0.1:${port}`, `localhost:${port}`]);
  const allowedOrigins = new Set([`http://127.0.0.1:${port}`, `http://localhost:${port}`]);
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
  const pending = new Map<string, (allow: boolean) => void>();

  const broadcast = (msg: unknown) => {
    const data = JSON.stringify(msg);
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
    ws.on("close", () => clients.delete(ws));
    ws.on("message", (raw) => {
      let msg: ClientMsg;
      try { msg = JSON.parse(raw.toString()) as ClientMsg; } catch { return; }
      if (msg.type === "send" && typeof msg.text === "string" && msg.text.trim()) {
        if (rt.agent.busy) { ws.send(JSON.stringify({ type: "error", runId: "", message: "Still working on the last request. Stop it first." })); return; }
        broadcast({ type: "user", text: msg.text });
        void rt.agent.run(msg.text.trim(), {
          emit: (e: AgentEvent) => broadcast(e),
          confirm: (card) => new Promise<boolean>((resolve) => {
            pending.set(card.cardId, resolve);
            broadcast(card);
          }),
        }).finally(() => {
          // A stopped run can leave a card nobody answered; close it as declined.
          for (const [id, resolve] of pending) { resolve(false); pending.delete(id); }
          broadcast({ type: "state", ...rt.state() });
        });
      } else if (msg.type === "confirm" && typeof msg.cardId === "string") {
        const resolve = pending.get(msg.cardId);
        if (resolve) { pending.delete(msg.cardId); resolve(msg.allow === true); }
      } else if (msg.type === "stop") {
        rt.agent.stop();
      } else if (msg.type === "settings") {
        rt.update(msg);
        broadcast({ type: "state", ...rt.state() });
      } else if (msg.type === "state") {
        ws.send(JSON.stringify({ type: "state", ...rt.state() }));
      }
    });
  });

  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, host, () => resolve(server));
  });
}
