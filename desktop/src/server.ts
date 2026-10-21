import { timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocketServer, type WebSocket } from "ws";
import { redactArgs, type AgentEvent } from "./agent/loop.js";
import { isUploadPath, MAX_UPLOAD_BYTES, storeUpload } from "./context.js";
import { redactSecrets } from "./gate/secrets.js";
import { startupWarnings } from "./config.js";
import type { Turn } from "./providers/types.js";
import { asNotificationInput } from "./notifications/inbox.js";
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
  | { type: "send"; text: string; attachments?: Array<{ path: string; name?: string }> }
  | { type: "confirm"; cardId: string; allow: boolean }
  | { type: "stop" }
  | { type: "settings"; brain?: "local" | "cloud"; offlineOnly?: boolean; embeddings?: boolean; screenControl?: boolean; tor?: boolean }
  | { type: "set_model"; which: "local" | "cloud"; model: string }
  | { type: "list_models" }
  | { type: "state" }
  | { type: "reset" }
  | { type: "branch_fork"; keepTurns?: number; name?: string }
  | { type: "branch_switch"; id: string }
  | { type: "branch_delete"; id: string }
  | { type: "branch_rename"; id: string; name: string }
  | { type: "branches" };

function sameToken(a: string, b: string): boolean {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

/** One rendered line of conversation history, for a page that loads or switches branches. */
export interface HistoryEntry {
  role: "user" | "assistant";
  text: string;
  /** Tool names the assistant called in this entry, in order. */
  tools?: string[];
  /** Turns to keep when forking "from here": everything up to this entry. */
  keep: number;
}

/**
 * The display form of stored turns. User turns carry the <context> and
 * <owner-context> blocks the loop wrapped around the owner's words; those
 * are machinery, so the page sees the words. Tool results are not replayed
 * (their summaries were live events); tool calls show as names.
 */
export function historyOf(turns: Turn[]): HistoryEntry[] {
  const out: HistoryEntry[] = [];
  let keep = 0;
  for (let i = 0; i < turns.length; i++) {
    const t = turns[i]!;
    keep = i + 1;
    // A tool turn belongs to the assistant turn before it; forking "from
    // here" keeps the results with the calls.
    if (t.role === "tool") { if (out.length) out[out.length - 1]!.keep = keep; continue; }
    if (t.role === "user") {
      const text = t.text
        .replace(/^<context>[\s\S]*?<\/context>\n\n/, "")
        .replace(/\n\n<owner-context>[\s\S]*?<\/owner-context>$/, "");
      out.push({ role: "user", text, keep });
    } else {
      out.push({ role: "assistant", text: t.text, tools: t.toolCalls.map((c) => c.name), keep });
    }
  }
  return out;
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

  const json = (res: ServerResponse, status: number, body: unknown) => {
    res.writeHead(status, { "content-type": "application/json" }).end(JSON.stringify(body));
  };

  /**
   * File upload: the page POSTs one file as JSON {name, data: base64} with
   * the gateway token, and gets back where it was stored under the Jarvis
   * home. The file itself rides the next "send" as an attachment path.
   * Same guards as the socket: this host only, the token, and a hard cap.
   */
  const handleUpload = (req: IncomingMessage, res: ServerResponse, url: URL) => {
    if (!sameToken(url.searchParams.get("token") ?? "", token)) { json(res, 403, { ok: false, error: "Bad token." }); return; }
    const rawCap = Math.ceil(MAX_UPLOAD_BYTES * 1.4) + 4096;
    const chunks: Buffer[] = [];
    let size = 0;
    let refused = false;
    req.on("data", (d: Buffer) => {
      size += d.length;
      if (size > rawCap) {
        refused = true;
        json(res, 413, { ok: false, error: `That file is over the ${Math.round(MAX_UPLOAD_BYTES / 1024 / 1024)} MB upload limit.` });
        req.destroy();
        return;
      }
      chunks.push(d);
    });
    req.on("end", () => {
      if (refused) return;
      try {
        const body = JSON.parse(Buffer.concat(chunks).toString("utf8")) as { name?: unknown; data?: unknown };
        if (typeof body.name !== "string" || !body.name || typeof body.data !== "string") {
          json(res, 400, { ok: false, error: "An upload needs a name and base64 data." });
          return;
        }
        const data = Buffer.from(body.data, "base64");
        if (data.length > MAX_UPLOAD_BYTES) {
          json(res, 413, { ok: false, error: `That file is over the ${Math.round(MAX_UPLOAD_BYTES / 1024 / 1024)} MB upload limit.` });
          return;
        }
        json(res, 200, { ok: true, ...storeUpload(rt.p.home, body.name, data) });
      } catch (err) {
        json(res, 400, { ok: false, error: err instanceof Error ? err.message : String(err) });
      }
    });
    req.on("error", () => { /* the client went away mid-upload; nothing stored */ });
  };

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
    if (req.method === "POST" && url.pathname === "/api/upload") { handleUpload(req, res, url); return; }
    if (req.method === "POST" && url.pathname === "/api/notifications") {
      // Inbound notification intake. Auth is the gateway's own, no weaker:
      // the Host check above has already run (DNS-rebinding guard), and the
      // caller must present the same access token the WebSocket channel
      // requires — as an Authorization: Bearer header, an x-jarvis-token
      // header, or the ?token= query parameter. There is no unauthenticated
      // intake. Every notification then goes through the runtime's filter
      // choke point; drops are audited there, never surfaced to the agent.
      const bearer = /^Bearer\s+(.+)$/i.exec(req.headers.authorization ?? "")?.[1] ?? "";
      const presented = bearer || (typeof req.headers["x-jarvis-token"] === "string" ? req.headers["x-jarvis-token"] : "") || url.searchParams.get("token") || "";
      if (!sameToken(presented, token)) {
        res.writeHead(401, { "content-type": "application/json" }).end(JSON.stringify({ ok: false, error: "A valid Jarvis access token is required." }));
        return;
      }
      const chunks: Buffer[] = [];
      let size = 0;
      req.on("data", (c: Buffer) => {
        size += c.length;
        if (size <= 1024 * 1024) chunks.push(c);
        else req.destroy();
      });
      req.on("error", () => { if (!res.writableEnded) res.writeHead(400).end("Bad request."); });
      req.on("end", () => {
        let parsed: unknown;
        try {
          parsed = JSON.parse(Buffer.concat(chunks).toString("utf8"));
        } catch {
          res.writeHead(400, { "content-type": "application/json" }).end(JSON.stringify({ ok: false, error: "Body must be JSON: one notification, an array of them, or { notifications: [...] }." }));
          return;
        }
        const items: unknown[] = Array.isArray(parsed)
          ? parsed
          : parsed && typeof parsed === "object" && Array.isArray((parsed as { notifications?: unknown }).notifications)
            ? (parsed as { notifications: unknown[] }).notifications
            : [parsed];
        // File drops that arrived first are older; drain them into the same
        // choke point before this batch so ordering stays arrival order.
        rt.drainNotificationInbox();
        const results = items.map((item) => {
          const input = asNotificationInput(item);
          if (!input) {
            rt.notifications.auditInvalid("gateway", JSON.stringify(item) ?? "");
            return { app: null, allowed: false, reason: "invalid" };
          }
          const r = rt.ingestNotification(input, "gateway");
          return r.allowed
            ? { app: r.notification.app, allowed: true }
            : { app: r.notification.app, allowed: false, reason: r.reason, gate: r.gate };
        });
        const accepted = results.filter((r) => r.allowed).length;
        res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify({ ok: true, accepted, dropped: results.length - accepted, results }));
      });
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

  /**
   * Ask the owner about one card, whichever agent raised it: the card joins
   * the same pending map the page answers and is declined after the card
   * timeout. The runtime's channel points here, so a subagent's cards
   * surface on the page exactly like the main agent's (their reason says
   * which subagent sent them).
   */
  const askOwner = (card: Extract<AgentEvent, { type: "confirm" }>): Promise<boolean> => new Promise<boolean>((resolve) => {
    const timer = setTimeout(() => settleCard(card.cardId, false), CARD_TIMEOUT_MS);
    pending.set(card.cardId, { resolve, card: card as typeof card & Record<string, unknown>, timer });
    broadcast(card as typeof card & Record<string, unknown>);
  });
  rt.setChannel({
    emit: (e) => broadcast(e as AgentEvent & Record<string, unknown>),
    confirm: askOwner,
  });

  /** The state message, with the conversation branches the page renders. */
  const stateMsg = (): { type: string } & Record<string, unknown> => ({
    type: "state",
    ...rt.state(),
    branches: rt.conversations.list(),
    activeBranch: rt.conversations.activeId,
  });

  /** Replay the active branch's conversation to one page (on connect, fork, or switch). */
  const sendHistory = (ws: WebSocket) => {
    const entries = historyOf(rt.agent.turns);
    if (entries.length) ws.send(JSON.stringify({ type: "history", entries, branch: rt.conversations.active().name }));
  };

  /** After a branch change every page re-renders — an empty history clears the log. */
  const broadcastHistory = () => {
    const msg = JSON.stringify({ type: "history", entries: historyOf(rt.agent.turns), branch: rt.conversations.active().name });
    for (const c of clients) if (c.readyState === c.OPEN) c.send(msg);
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
    ws.send(JSON.stringify(stateMsg()));
    for (const w of startupWarnings) ws.send(JSON.stringify({ type: "error", runId: "", message: w }));
    sendHistory(ws);
    for (const p of pending.values()) ws.send(JSON.stringify(forWire(p.card)));
    ws.on("close", () => clients.delete(ws));
    ws.on("message", (raw) => {
      let msg: ClientMsg;
      try { msg = JSON.parse(raw.toString()) as ClientMsg; } catch { return; }
      if (msg.type === "send" && typeof msg.text === "string" && msg.text.trim()) {
        if (rt.agent.busy) { ws.send(JSON.stringify({ type: "error", runId: "", message: "Still working on the last request. Stop it first." })); return; }
        const text = msg.text.trim();
        const attachments = (Array.isArray(msg.attachments) ? msg.attachments : [])
          .filter((a): a is { path: string; name?: string } => !!a && typeof a.path === "string")
          .map((a) => ({ path: a.path, name: typeof a.name === "string" && a.name ? a.name : path.basename(a.path) }));
        // A gateway client may only attach what it uploaded here; anything
        // else is refused out loud rather than quietly read.
        const outside = attachments.find((a) => !isUploadPath(rt.p.home, a.path));
        if (outside) {
          ws.send(JSON.stringify({ type: "error", runId: "", message: `That attachment was not uploaded here: ${outside.name}. Attach the file again.` }));
          return;
        }
        let thisRunId = "";
        void (async () => {
          const extra = await rt.ownerContext(text, attachments);
          broadcast({ type: "user", text, attachments: attachments.map((a) => a.name) });
          await rt.agent.run(text, {
            emit: (e: AgentEvent) => {
              if (e.type === "run_start") thisRunId = e.runId;
              broadcast(e as AgentEvent & Record<string, unknown>);
            },
            confirm: askOwner,
          }, extra);
        })().catch((err) => {
          ws.send(JSON.stringify({ type: "error", runId: "", message: err instanceof Error ? err.message : String(err) }));
        }).finally(() => {
          // A stopped run can leave a card nobody answered; close it as
          // declined. Only this run's cards — a subagent's card is its own
          // run's business and outlives this one.
          for (const [id, p] of [...pending.entries()]) {
            if ((p.card as { runId?: unknown }).runId === thisRunId) settleCard(id, false);
          }
          rt.syncConversation();
          broadcast(stateMsg());
          broadcast({ type: "turns", count: rt.agent.turns.length });
        });
      } else if (msg.type === "confirm" && typeof msg.cardId === "string") {
        settleCard(msg.cardId, msg.allow === true);
      } else if (msg.type === "stop") {
        rt.stopAll();
      } else if (msg.type === "settings") {
        rt.update(msg);
        broadcast(stateMsg());
        // A Tor toggle also refreshes the proxy probe, then re-pushes state
        // so the settings row can show whether a proxy actually answers.
        if (typeof msg.tor === "boolean") {
          void rt.refreshTorStatus().then(() => broadcast(stateMsg()));
        }
      } else if (msg.type === "set_model" && (msg.which === "local" || msg.which === "cloud") && typeof msg.model === "string") {
        rt.setModel(msg.which, msg.model);
        broadcast(stateMsg());
      } else if (msg.type === "list_models") {
        rt.listModels().then(
          (models) => ws.send(JSON.stringify({ type: "models", models })),
          (err) => ws.send(JSON.stringify({ type: "models", models: [], error: err instanceof Error ? err.message : String(err) })),
        );
      } else if (msg.type === "reset") {
        if (rt.agent.busy) { ws.send(JSON.stringify({ type: "error", runId: "", message: "Stop the current request first." })); return; }
        rt.agent.reset();
        rt.syncConversation();
        broadcast({ type: "reset" });
      } else if (msg.type === "branch_fork") {
        if (rt.agent.busy) { ws.send(JSON.stringify({ type: "error", runId: "", message: "Stop the current request first." })); return; }
        rt.syncConversation();
        const branch = rt.conversations.fork(rt.agent.turns, typeof msg.keepTurns === "number" ? msg.keepTurns : rt.agent.turns.length, msg.name);
        rt.agent.loadTurns(branch.turns);
        broadcast(stateMsg());
        broadcastHistory();
      } else if (msg.type === "branch_switch" && typeof msg.id === "string") {
        if (rt.agent.busy) { ws.send(JSON.stringify({ type: "error", runId: "", message: "Stop the current request first." })); return; }
        rt.syncConversation();
        const turns = rt.conversations.switchTo(msg.id);
        if (!turns) { ws.send(JSON.stringify({ type: "error", runId: "", message: "That branch no longer exists." })); return; }
        rt.agent.loadTurns(turns);
        broadcast(stateMsg());
        broadcastHistory();
      } else if (msg.type === "branch_delete" && typeof msg.id === "string") {
        if (rt.agent.busy) { ws.send(JSON.stringify({ type: "error", runId: "", message: "Stop the current request first." })); return; }
        rt.syncConversation();
        const wasActive = rt.conversations.activeId === msg.id;
        const now = rt.conversations.remove(msg.id);
        if (!now) { ws.send(JSON.stringify({ type: "error", runId: "", message: "The last branch can't be deleted." })); return; }
        if (wasActive) {
          rt.agent.loadTurns(now.turns);
          broadcastHistory();
        }
        broadcast(stateMsg());
      } else if (msg.type === "branch_rename" && typeof msg.id === "string" && typeof msg.name === "string") {
        if (rt.conversations.rename(msg.id, msg.name)) broadcast(stateMsg());
      } else if (msg.type === "branches") {
        ws.send(JSON.stringify({ type: "branches", branches: rt.conversations.list(), activeBranch: rt.conversations.activeId }));
      } else if (msg.type === "state") {
        ws.send(JSON.stringify(stateMsg()));
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
    pushState: () => broadcast(stateMsg()),
  };
}
