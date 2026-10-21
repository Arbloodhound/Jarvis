import { timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import type { IncomingMessage, ServerResponse } from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { AgentStore } from "./agents.js";
import type { Runtime } from "./runtime.js";
import { AdminError, SkillAdmin, validateName } from "./skills-admin.js";
import { skillRoots } from "./skills.js";
import { ALL_TOOLS } from "./tools/index.js";

/**
 * Gateway routes behind the Skills and Agents menus (ui/menus.js):
 *
 *   GET    /api/tools                 tool names a saved agent may list
 *   GET    /api/skills                every discovered skill (editable flag)
 *   GET    /api/skills/:name          one skill, with its body
 *   PUT    /api/skills/:name          create / update (body: {description, body, frontmatter?, mode?})
 *   DELETE /api/skills/:name          move it to <home>/deleted/skills
 *   GET    /api/agents                every saved agent definition
 *   GET    /api/agents/:name          one definition
 *   PUT    /api/agents/:name          create / update (body: {description, tools, instructions, mode?})
 *   DELETE /api/agents/:name          move it to <home>/deleted/agents
 *   GET    /ui/menus.js, /ui/menus.css   the menu's static files
 *
 * `mode` is "create" (refuse an existing name), "update" (refuse a missing
 * one) or absent (either).
 *
 * Auth is the gateway's own, as POST /api/notifications does it: the Host
 * check has already run in server.ts, and every /api route here needs the
 * access token as Authorization: Bearer, x-jarvis-token, or ?token=. A
 * browser Origin, when sent, must be this server. These are the owner's
 * own actions from the menu, so no confirm card is raised: the click IS
 * the owner's decision. Errors are JSON {ok:false, code, error}.
 */

const UI_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), "ui");
const STATIC: Record<string, string> = {
  "/ui/menus.js": "text/javascript; charset=utf-8",
  "/ui/menus.css": "text/css; charset=utf-8",
};
/** Request bodies above this are refused (413). Covers a 248 KB skill body plus JSON overhead. */
export const MAX_BODY = 512 * 1024;

function sameToken(a: string, b: string): boolean {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

function send(res: ServerResponse, status: number, body: unknown): void {
  if (res.writableEnded) return;
  res.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" }).end(JSON.stringify(body));
}

function fail(res: ServerResponse, status: number, code: string, error: string): void {
  send(res, status, { ok: false, code, error });
}

function presentedToken(req: IncomingMessage, url: URL): string {
  const bearer = /^Bearer\s+(.+)$/i.exec(req.headers.authorization ?? "")?.[1] ?? "";
  return bearer || (typeof req.headers["x-jarvis-token"] === "string" ? req.headers["x-jarvis-token"] : "") || url.searchParams.get("token") || "";
}

function readJson(req: IncomingMessage): Promise<{ ok: true; value: Record<string, unknown> } | { ok: false; status: number; code: string; error: string }> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = [];
    let size = 0;
    let over = false;
    req.on("data", (c: Buffer) => {
      size += c.length;
      if (size > MAX_BODY) over = true;
      else chunks.push(c);
    });
    req.on("error", () => resolve({ ok: false, status: 400, code: "bad_request", error: "The request body could not be read." }));
    req.on("end", () => {
      if (over) { resolve({ ok: false, status: 413, code: "too_large", error: `The request body is over ${MAX_BODY / 1024} KB.` }); return; }
      let v: unknown;
      try {
        v = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      } catch {
        resolve({ ok: false, status: 400, code: "bad_json", error: "The body must be a JSON object." });
        return;
      }
      if (!v || typeof v !== "object" || Array.isArray(v)) { resolve({ ok: false, status: 400, code: "bad_json", error: "The body must be a JSON object." }); return; }
      resolve({ ok: true, value: v as Record<string, unknown> });
    });
  });
}

function modeOf(v: unknown): "create" | "update" | "upsert" {
  if (v === undefined || v === null || v === "upsert") return "upsert";
  if (v === "create" || v === "update") return v;
  throw new AdminError(400, "invalid_field", 'mode must be "create", "update" or left out.');
}

const toolNames = (): string[] => ALL_TOOLS.map((t) => t.name);

/**
 * Handle one request if it is a menu route. Returns false (untouched) for
 * any other path, so server.ts falls through to its own routes.
 */
export function handleMenuRoute(req: IncomingMessage, res: ServerResponse, url: URL, ctx: { rt: Runtime; token: string }): boolean {
  const p = url.pathname;
  if (req.method === "GET" && STATIC[p]) {
    try {
      const body = readFileSync(path.join(UI_DIR, path.basename(p)));
      res.writeHead(200, { "content-type": STATIC[p]!, "cache-control": "no-store", "x-content-type-options": "nosniff" }).end(body);
    } catch {
      res.writeHead(404).end("Not found.");
    }
    return true;
  }
  const m = /^\/api\/(skills|agents|tools)(?:\/([^/]*))?\/?$/.exec(p);
  if (!m) return false;
  const kind = m[1] as "skills" | "agents" | "tools";
  let name: string | undefined;
  try {
    name = m[2] === undefined ? undefined : decodeURIComponent(m[2]);
  } catch {
    fail(res, 400, "invalid_name", "The name in the path is not valid.");
    return true;
  }

  if (!sameToken(presentedToken(req, url), ctx.token)) {
    fail(res, 401, "unauthorized", "A valid Jarvis access token is required.");
    return true;
  }
  const origin = req.headers.origin;
  if (origin && origin !== `http://${req.headers.host}`) {
    fail(res, 403, "bad_origin", "Requests from other sites are refused.");
    return true;
  }

  const { rt } = ctx;
  const skills = () => new SkillAdmin(rt.p.home, skillRoots(rt.p.home, rt.p.workspace, rt.cfg.skills.roots), rt.cfg.skills.allow);
  const agents = () => new AgentStore(rt.p.home, toolNames);

  const run = async (): Promise<void> => {
    if (kind === "tools") {
      if (name !== undefined) { fail(res, 404, "not_found", "Not found."); return; }
      if (req.method !== "GET") { fail(res, 405, "method_not_allowed", "Use GET."); return; }
      send(res, 200, { ok: true, tools: ALL_TOOLS.map((t) => ({ name: t.name, description: t.description })) });
      return;
    }
    if (name === undefined || name === "") {
      if (req.method !== "GET") { fail(res, 405, "method_not_allowed", "Use GET on the list, or PUT / DELETE on /api/" + kind + "/<name>."); return; }
      send(res, 200, kind === "skills" ? { ok: true, skills: skills().list() } : { ok: true, agents: agents().list() });
      return;
    }
    validateName(name);
    if (req.method === "GET") {
      const item = kind === "skills" ? skills().get(name) : agents().get(name);
      if (!item) { fail(res, 404, "not_found", `No ${kind === "skills" ? "skill" : "agent"} named "${name}".`); return; }
      send(res, 200, kind === "skills" ? { ok: true, skill: item } : { ok: true, agent: item });
      return;
    }
    if (req.method === "PUT") {
      const body = await readJson(req);
      if (!body.ok) { fail(res, body.status, body.code, body.error); return; }
      const v = body.value;
      const mode = modeOf(v.mode);
      if (kind === "skills") {
        const saved = skills().save(name, { description: v.description, body: v.body, frontmatter: v.frontmatter }, mode);
        send(res, 200, { ok: true, skill: saved });
      } else {
        const saved = agents().save(name, { description: v.description, tools: v.tools, instructions: v.instructions }, mode);
        send(res, 200, { ok: true, agent: saved });
      }
      return;
    }
    if (req.method === "DELETE") {
      const out = kind === "skills" ? skills().delete(name) : agents().delete(name);
      send(res, 200, { ok: true, ...out });
      return;
    }
    fail(res, 405, "method_not_allowed", "Use GET, PUT or DELETE.");
  };

  run().catch((err) => {
    if (err instanceof AdminError) fail(res, err.status, err.code, err.message);
    else fail(res, 500, "internal", err instanceof Error ? err.message : String(err));
  });
  return true;
}
