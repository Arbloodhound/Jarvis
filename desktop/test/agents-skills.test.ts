import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { createServer as netServer } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { AgentStore, childTools, definitionSuffix, parseToolList } from "../src/agents.ts";
import { defaults, paths } from "../src/config.ts";
import { Gate, Episode } from "../src/gate/gate.ts";
import { Runtime } from "../src/runtime.ts";
import { startServer } from "../src/server.ts";
import { SkillAdmin, formatFrontmatter } from "../src/skills-admin.ts";
import { parseFrontmatter, SkillStore, skillRoots } from "../src/skills.ts";
import { ALL_TOOLS } from "../src/tools/index.ts";
import type { Provider, Turn, TurnRequest, TurnResult } from "../src/providers/types.ts";

const tempHome = (prefix: string) => {
  const home = mkdtempSync(path.join(tmpdir(), prefix));
  return { home, p: paths(home) };
};

// ---------- the agents store ----------

test("agents store: create / get / list / update / delete round trip, atomic files, validated names", () => {
  const { home } = tempHome("jarvis-agents-");
  const store = new AgentStore(home, () => ["read_file", "web_search"]);
  assert.deepEqual(store.list(), []);
  const a = store.save("price-checker", { description: "Compares prices", tools: "read_file, web_search, spawn_agent, nope", instructions: "Check three shops.\nReport a table." }, "create");
  assert.equal(a.name, "price-checker");
  assert.deepEqual(a.tools, ["read_file", "web_search", "spawn_agent", "nope"], "unknown names are kept in the file");
  assert.deepEqual(a.unknownTools, ["spawn_agent", "nope"], "…and reported as unknown");
  const file = path.join(home, "agents", "price-checker", "AGENT.md");
  assert.equal(a.location, file);
  const text = readFileSync(file, "utf8");
  const fm = parseFrontmatter(text);
  assert.equal(fm.name, "price-checker");
  assert.equal(fm.description, "Compares prices");
  assert.equal(fm.tools, "read_file, web_search, spawn_agent, nope");
  assert.ok(text.endsWith("Check three shops.\nReport a table."));
  assert.deepEqual(readdirSync(path.dirname(file)), ["AGENT.md"], "no temp files left behind");

  assert.throws(() => store.save("price-checker", { description: "x", instructions: "y" }, "create"), /already exists/);
  assert.throws(() => store.save("missing", { description: "x", instructions: "y" }, "update"), /No agent/);
  for (const bad of ["Bad", "a b", "../x", "-x", "x--y", "", "a".repeat(65)]) {
    assert.throws(() => store.save(bad, { description: "x", instructions: "y" }), /lowercase/, `name ${JSON.stringify(bad)} refused`);
  }
  assert.throws(() => store.save("ok", { description: "two\nlines", instructions: "y" }), /single line/);
  assert.throws(() => store.save("ok", { description: "", instructions: "y" }), /required/);
  assert.throws(() => store.save("ok", { description: "d", instructions: "  " }), /instructions are required/);
  assert.throws(() => store.save("ok", { description: "d", tools: "a b", instructions: "y" }), /not a tool name/);
  assert.equal(store.get("../etc"), null);

  const b = store.save("price-checker", { description: "Updated", tools: [], instructions: "New body" }, "update");
  assert.deepEqual(b.tools, [], "empty = default set");
  assert.equal(b.description, "Updated");
  store.save("alpha", { description: "first", instructions: "go" });
  assert.deepEqual(store.list().map((x) => x.name), ["alpha", "price-checker"]);

  const { movedTo } = store.delete("alpha");
  assert.equal(existsSync(path.join(home, "agents", "alpha")), false);
  assert.ok(movedTo.startsWith(path.join(home, "deleted", "agents", "alpha.deleted-")), "moved aside, not erased");
  assert.ok(existsSync(path.join(movedTo, "AGENT.md")));
  assert.deepEqual(store.list().map((x) => x.name), ["price-checker"]);
  assert.throws(() => store.delete("alpha"), /No agent/);
});

test("agents: tool lists parse; childTools only narrows; the suffix carries the instructions", () => {
  assert.deepEqual(parseToolList(" a, b ,a,, "), ["a", "b"]);
  assert.deepEqual(parseToolList(["x", "y"]), ["x", "y"]);
  assert.throws(() => parseToolList(5), /comma-separated/);
  const base = [{ name: "read_file" }, { name: "web_search" }, { name: "list_dir" }];
  assert.equal(childTools(undefined, base), base);
  assert.equal(childTools({ tools: [] }, base), base);
  assert.deepEqual(childTools({ tools: ["web_search", "spawn_agent", "made_up"] }, base).map((t) => t.name), ["web_search"]);
  assert.equal(definitionSuffix(undefined), "");
  assert.match(definitionSuffix({ name: "n", instructions: "Be brief." }), /saved agent "n"[\s\S]*Be brief\./);
});

test("frontmatter writer round-trips through skills.ts's parser, quotes included", () => {
  const fields = { name: "x", description: `He said "hi": ok 'yes'`, tools: "" };
  assert.deepEqual(parseFrontmatter(formatFrontmatter(fields) + "body"), fields);
  assert.throws(() => formatFrontmatter({ name: "a\nb" }), /single line/);
});

// ---------- skill admin ----------

test("skill admin: create / edit / delete in the home skills dir; other roots read-only; delete moves aside", () => {
  const { home, p } = tempHome("jarvis-skilladmin-");
  const roots = skillRoots(home, p.workspace);
  mkdirSync(path.join(p.workspace, "skills", "ws-skill"), { recursive: true });
  writeFileSync(path.join(p.workspace, "skills", "ws-skill", "SKILL.md"), "---\nname: ws-skill\ndescription: from the workspace\n---\nbody");
  const admin = new SkillAdmin(home, roots);
  const made = admin.save("weekly-report", { description: "Write the weekly report", body: "# Steps\n1. gather" }, "create");
  assert.equal(made.editable, true);
  assert.equal(made.body, "# Steps\n1. gather");
  const ws = admin.list().find((s) => s.name === "ws-skill")!;
  assert.equal(ws.editable, false);
  assert.throws(() => admin.save("ws-skill", { description: "x", body: "y" }), /outside the app's skills folder/);
  assert.throws(() => admin.delete("ws-skill"), /outside the app's skills folder/);

  // Extra frontmatter fields survive an edit that does not send them.
  const file = path.join(home, "skills", "weekly-report", "SKILL.md");
  writeFileSync(file, readFileSync(file, "utf8").replace("---\n", "---\nallowed-tools: \"read_file\"\n"));
  const edited = admin.save("weekly-report", { description: "Edited", body: "new" }, "update");
  assert.equal(edited.frontmatter["allowed-tools"], "read_file");
  assert.equal(edited.description, "Edited");

  const { movedTo } = admin.delete("weekly-report");
  assert.ok(movedTo.startsWith(path.join(home, "deleted", "skills", "weekly-report.deleted-")));
  assert.ok(existsSync(path.join(movedTo, "SKILL.md.deleted")), "kept, under a name no root discovers");
  const fresh = new SkillStore(null);
  fresh.discover([...roots, home]);
  assert.equal(fresh.entry("weekly-report"), null, "a deleted skill is not rediscovered, even from a root over the whole home");
});

// ---------- the tools ----------

test("agent tools: registered; create/delete always confirm (create also lasts), list is read-only", () => {
  const byName = (n: string) => ALL_TOOLS.find((t) => t.name === n)!;
  const create = byName("create_agent");
  const del = byName("delete_agent");
  const list = byName("list_agents");
  assert.deepEqual(create.policy, { effects: ["mutate"], lastsBeyondRequest: true, alwaysConfirm: true });
  assert.deepEqual(del.policy, { effects: ["mutate"], alwaysConfirm: true });
  assert.deepEqual(list.policy.effects, ["read"]);
  const gate = new Gate({ writableRoots: [] }, tmpdir());
  const ep = new Episode("make me an agent");
  assert.equal(gate.check(ep, "create_agent", create.policy, { name: "x", description: "d", instructions: "i" }).confirm?.rule, "always_confirm");
  assert.equal(gate.check(ep, "delete_agent", del.policy, { name: "x" }).confirm?.rule, "always_confirm");
  assert.equal(gate.check(ep, "list_agents", list.policy, {}).allowed, true);
});

test("agent tools: run against the store; unknown tools reported, not granted", async () => {
  const { home } = tempHome("jarvis-agenttools-");
  const ctx = { home } as never;
  const create = ALL_TOOLS.find((t) => t.name === "create_agent")!;
  const out = await create.run({ name: "scout", description: "Looks around", tools: ["list_dir", "spawn_agent"], instructions: "Look." }, ctx);
  assert.match(out, /Saved agent "scout"/);
  assert.match(out, /never granted: spawn_agent/);
  await assert.rejects(create.run({ name: "scout", description: "again", instructions: "x" }, ctx), /already exists/);
  assert.match(await create.run({ name: "scout", description: "again", instructions: "x", replace: true }, ctx), /Saved/);
  assert.match(await ALL_TOOLS.find((t) => t.name === "list_agents")!.run({}, ctx), /- scout: again/);
  assert.match(await ALL_TOOLS.find((t) => t.name === "delete_agent")!.run({ name: "scout" }, ctx), /Deleted agent "scout"/);
});

// ---------- spawn_agent with a definition (full stack, fake brain) ----------

const end = (text: string): TurnResult => ({ text, stop: "end", toolCalls: [] });
const callTool = (id: string, name: string, input: Record<string, unknown>): TurnResult => ({ text: "", stop: "tool_use", toolCalls: [{ id, name, input }] });
const used = (turns: Turn[], name: string) => turns.some((t) => t.role === "tool" && t.results.some((r) => r.name === name));
const handedOff = (turns: Turn[]) => turns.some((t) => t.role === "user" && t.text.includes("[Hand-off from subagent"));

test("spawn_agent with `agent`: the child gets the instructions and the filtered tools; depth stays 1", async () => {
  const { home, p } = tempHome("jarvis-spawndef-");
  new AgentStore(home).save("scout", { description: "d", tools: "list_dir, read_file, spawn_agent, made_up", instructions: "ALWAYS-SAY-SCOUT" });
  const childReqs: TurnRequest[] = [];
  const b: Provider = {
    kind: "local",
    label: "fake",
    async turn(req) {
      await new Promise((r) => setTimeout(r, 1));
      if (req.system.includes("You are a subagent")) {
        childReqs.push(req);
        return end("scout done");
      }
      if (handedOff(req.turns)) return end("parent done");
      if (!used(req.turns, "spawn_agent")) return callTool("s1", "spawn_agent", { task: "look around", agent: "scout" });
      await new Promise((r) => setTimeout(r, 20));
      return callTool("st", "agent_status", {});
    },
  };
  const rt = new Runtime(defaults(p), p, home);
  (rt as unknown as { provider: () => Provider }).provider = () => b;
  const answer = await rt.agent.run("run scout", { emit: () => {}, confirm: async () => true });
  assert.equal(answer, "parent done");
  assert.ok(childReqs.length >= 1);
  const req = childReqs[0]!;
  assert.match(req.system, /ALWAYS-SAY-SCOUT/, "the definition's instructions are in the child's system prompt");
  assert.deepEqual(req.tools.map((t) => t.name).sort(), ["list_dir", "read_file"], "only the allowlisted child tools; never spawn_agent, never unknowns");
  assert.equal(rt.orchestrator.list()[0]!.name, "scout", "an unnamed child takes the definition's name");
  assert.throws(() => rt.orchestrator.spawn("nested", undefined, 1, "scout"), /cannot spawn subagents/, "depth cap holds with a definition");
  assert.throws(() => rt.orchestrator.spawn("x", undefined, 0, "ghost"), /No saved agent named "ghost"/);
});

// ---------- gateway endpoints ----------

async function freePort(): Promise<number> {
  return new Promise((resolve) => {
    const s = netServer().listen(0, "127.0.0.1", () => {
      const a = s.address();
      s.close(() => resolve(typeof a === "object" && a ? a.port : 0));
    });
  });
}

test("gateway /api/skills, /api/agents, /api/tools: token, CRUD round trip, errors, skill store sees API skills", async () => {
  const { home, p } = tempHome("jarvis-menus-http-");
  const rt = new Runtime(defaults(p), p, home);
  const TOKEN = "menus-token";
  const port = await freePort();
  const gw = await startServer(rt, TOKEN, port);
  const base = `http://127.0.0.1:${port}`;
  const auth = { authorization: `Bearer ${TOKEN}` };
  const call = async (method: string, url: string, body?: unknown, headers: Record<string, string> = auth) => {
    const res = await fetch(base + url, { method, headers: { ...headers, ...(body !== undefined ? { "content-type": "application/json" } : {}) }, body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body) });
    return { status: res.status, body: await res.json() as Record<string, any> };
  };
  try {
    // 401 without a token, on every route and method.
    for (const [m, u] of [["GET", "/api/skills"], ["GET", "/api/skills/x"], ["PUT", "/api/skills/x"], ["DELETE", "/api/skills/x"],
      ["GET", "/api/agents"], ["GET", "/api/agents/x"], ["PUT", "/api/agents/x"], ["DELETE", "/api/agents/x"], ["GET", "/api/tools"]] as const) {
      assert.equal((await call(m, u, m === "PUT" ? {} : undefined, {})).status, 401, `${m} ${u} needs the token`);
      assert.equal((await call(m, u, m === "PUT" ? {} : undefined, { authorization: "Bearer wrong" })).status, 401);
    }
    // The token works as a query parameter and x-jarvis-token header too.
    assert.equal((await call("GET", `/api/skills?token=${TOKEN}`, undefined, {})).status, 200);
    assert.equal((await call("GET", "/api/agents", undefined, { "x-jarvis-token": TOKEN })).status, 200);
    // A foreign Origin is refused.
    assert.equal((await call("GET", "/api/skills", undefined, { ...auth, origin: "http://evil.example" })).status, 403);

    // tools
    const tools = await call("GET", "/api/tools");
    assert.equal(tools.status, 200);
    const names = (tools.body.tools as Array<{ name: string }>).map((t) => t.name);
    assert.ok(names.includes("read_file") && !names.includes("spawn_agent"));

    // skills CRUD
    assert.deepEqual((await call("GET", "/api/skills")).body.skills, []);
    const created = await call("PUT", "/api/skills/daily-notes", { description: "Summarise my day", body: "Steps here", mode: "create" });
    assert.equal(created.status, 200, JSON.stringify(created.body));
    assert.equal(created.body.skill.name, "daily-notes");
    assert.equal(rt.skillStore().entry("daily-notes")?.description, "Summarise my day", "the runtime's skill store lists an API-created skill");
    assert.equal((await call("PUT", "/api/skills/daily-notes", { description: "dup", body: "", mode: "create" })).body.code, "exists");
    const got = await call("GET", "/api/skills/daily-notes");
    assert.equal(got.body.skill.body, "Steps here");
    const upd = await call("PUT", "/api/skills/daily-notes", { description: "Edited", body: "New steps", mode: "update" });
    assert.equal(upd.body.skill.description, "Edited");
    assert.equal((await call("GET", "/api/skills")).body.skills.length, 1);
    const delS = await call("DELETE", "/api/skills/daily-notes");
    assert.equal(delS.status, 200);
    assert.ok(String(delS.body.movedTo).includes(path.join("deleted", "skills")));
    assert.equal((await call("GET", "/api/skills/daily-notes")).status, 404);
    assert.equal(rt.skillStore().entry("daily-notes"), null);

    // agents CRUD
    const ca = await call("PUT", "/api/agents/scout", { description: "Looks", tools: ["list_dir", "bogus"], instructions: "Look around.", mode: "create" });
    assert.equal(ca.status, 200, JSON.stringify(ca.body));
    assert.deepEqual(ca.body.agent.unknownTools, ["bogus"]);
    assert.equal((await call("GET", "/api/agents")).body.agents[0].name, "scout");
    assert.equal((await call("GET", "/api/agents/scout")).body.agent.instructions, "Look around.");
    assert.equal((await call("PUT", "/api/agents/scout", { description: "Looks more", tools: "", instructions: "Again.", mode: "update" })).body.agent.description, "Looks more");
    assert.equal((await call("DELETE", "/api/agents/scout")).status, 200);
    assert.equal((await call("GET", "/api/agents/scout")).status, 404);
    assert.equal((await call("DELETE", "/api/agents/scout")).body.code, "not_found");

    // errors: bad JSON, bad name, bad field, bad mode, too large, wrong method, unknown update
    assert.equal((await call("PUT", "/api/agents/x", "{not json")).body.code, "bad_json");
    assert.equal((await call("PUT", "/api/agents/x", [1])).body.code, "bad_json");
    const badName = await call("PUT", "/api/skills/Bad%20Name", { description: "d", body: "" });
    assert.equal(badName.status, 400);
    assert.equal(badName.body.code, "invalid_name");
    assert.equal((await call("GET", "/api/skills/..%2Fetc")).body.code, "invalid_name");
    assert.equal((await call("PUT", "/api/agents/x", { description: "a\nb", instructions: "i" })).body.code, "invalid_field");
    assert.equal((await call("PUT", "/api/agents/x", { description: "d", instructions: "i", mode: "zap" })).body.code, "invalid_field");
    assert.equal((await call("PUT", "/api/skills/x", { description: "d", body: "y".repeat(600 * 1024) })).status, 413);
    assert.equal((await call("POST", "/api/skills/x", {})).status, 405);
    assert.equal((await call("PUT", "/api/skills/nothere", { description: "d", body: "", mode: "update" })).status, 404);
    assert.equal(existsSync(path.join(home, "skills", "x")), false, "refused writes wrote nothing");
  } finally {
    await new Promise<void>((r) => gw.server.close(() => r()));
  }
});

test("UI: index.html loads menus.js/menus.css, the gateway serves them, copy-ui ships src/ui whole", async () => {
  const root = path.join(import.meta.dirname, "..");
  const html = readFileSync(path.join(root, "src", "ui", "index.html"), "utf8");
  assert.match(html, /<script src="\/ui\/menus\.js"><\/script>/);
  assert.match(html, /<link rel="stylesheet" href="\/ui\/menus\.css">/);
  assert.ok(existsSync(path.join(root, "src", "ui", "menus.js")) && existsSync(path.join(root, "src", "ui", "menus.css")));
  const copy = readFileSync(path.join(root, "scripts", "copy-ui.mjs"), "utf8");
  assert.match(copy, /cpSync\("src\/ui", "dist\/ui", \{ recursive: true \}\)/, "copy-ui copies the whole ui folder, so the menu files ship");
  const js = readFileSync(path.join(root, "src", "ui", "menus.js"), "utf8");
  for (const route of ["/api/skills", "/api/agents", "/api/tools"]) assert.ok(js.includes(route), `menus.js calls ${route}`);
  assert.ok(js.includes("confirm("), "delete asks a browser confirm()");

  const { home, p } = tempHome("jarvis-menus-static-");
  const port = await freePort();
  const gw = await startServer(new Runtime(defaults(p), p, home), "t", port);
  try {
    const js2 = await fetch(`http://127.0.0.1:${port}/ui/menus.js`);
    assert.equal(js2.status, 200);
    assert.match(js2.headers.get("content-type") ?? "", /javascript/);
    const css = await fetch(`http://127.0.0.1:${port}/ui/menus.css`);
    assert.equal(css.status, 200);
    assert.equal((await fetch(`http://127.0.0.1:${port}/ui/index.html`)).status, 404, "only the two menu files are served from /ui");
  } finally {
    await new Promise<void>((r) => gw.server.close(() => r()));
  }
});
