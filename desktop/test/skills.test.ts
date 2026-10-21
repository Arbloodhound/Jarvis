import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { Agent, type AgentEvent } from "../src/agent/loop.ts";
import { systemPrompt } from "../src/agent/prompt.ts";
import { defaults, loadConfig, paths } from "../src/config.ts";
import { AuditLog } from "../src/gate/audit.ts";
import { Gate } from "../src/gate/gate.ts";
import { Memory } from "../src/memory.ts";
import type { Provider, TurnRequest } from "../src/providers/types.ts";
import { Runtime } from "../src/runtime.ts";
import { MAX_DIR_CHARS, MAX_READ_BYTES, SkillStore, parseFrontmatter, skillRoots } from "../src/skills.ts";
import { ALL_TOOLS, type ToolContext } from "../src/tools/index.ts";

/**
 * The skills contract, ported from harness/skills.py: discovery scans
 * precedence-tiered roots and caches metadata only; the prompt gets a
 * bounded directory; bodies load on demand via search + read, capped;
 * an allowlist (empty = all) governs eligibility.
 */

function root(): string {
  return mkdtempSync(path.join(tmpdir(), "jarvis-skills-"));
}

function writeSkill(dir: string, name: string, description: string, body: string, fmName?: string): string {
  const d = path.join(dir, name);
  mkdirSync(d, { recursive: true });
  const file = path.join(d, "SKILL.md");
  writeFileSync(file, `---\nname: ${fmName ?? name}\ndescription: ${description}\n---\n\n${body}\n`);
  return file;
}

function storeWith(...roots: string[]): SkillStore {
  const s = new SkillStore();
  s.discover(roots);
  return s;
}

// ---------- frontmatter ----------

test("frontmatter: flat key:value lines, quotes stripped, missing fence = empty", () => {
  assert.deepEqual(parseFrontmatter("---\nname: alpha\ndescription: 'Does things'\nextra: \"x\"\n---\nbody"), {
    name: "alpha", description: "Does things", extra: "x",
  });
  assert.deepEqual(parseFrontmatter("# no fence\nname: alpha\n"), {});
  assert.deepEqual(parseFrontmatter("---\nname: only\n---\n"), { name: "only" });
});

// ---------- discovery ----------

test("discovery: nested SKILL.md files, name falls back to the folder, missing roots skipped", () => {
  const r = root();
  writeSkill(path.join(r, "deep"), "nested-skill", "Nested one", "body");
  const bare = path.join(r, "bare-skill");
  mkdirSync(bare, { recursive: true });
  writeFileSync(path.join(bare, "SKILL.md"), "no frontmatter at all\n");
  const s = storeWith(path.join(r, "does-not-exist"), r);
  assert.deepEqual(s.list().map((e) => e.name), ["bare-skill", "nested-skill"]);
  assert.equal(s.entry("nested-skill")!.description, "Nested one");
});

test("discovery: the earlier root wins a name collision; later roots never override", () => {
  const a = root();
  const b = root();
  const first = writeSkill(a, "dup", "from the first root", "first body");
  writeSkill(b, "dup", "from the second root", "second body");
  const s = storeWith(a, b);
  assert.equal(s.list().length, 1);
  assert.equal(s.entry("dup")!.description, "from the first root");
  assert.equal(s.entry("dup")!.location, first);
  assert.equal(s.read("dup")!.includes("first body"), true);
});

test("discovery: a SKILL.md reached through a symlinked folder is not picked up", () => {
  const outside = root();
  writeSkill(outside, "escaped", "outside the roots", "outside body");
  const r = root();
  symlinkSync(path.join(outside, "escaped"), path.join(r, "link"), "dir");
  const s = storeWith(r);
  assert.equal(s.entry("escaped"), null);
});

// ---------- directory ----------

test("directory: metadata only (name, description, location) — never a body", () => {
  const r = root();
  const file = writeSkill(r, "marker-answer", "Formats a marker code answer", "SECRET-BODY-TOKEN-4419 procedure");
  const dir = storeWith(r).directory();
  assert.equal(dir, `- marker-answer: Formats a marker code answer [${file}]`);
  assert.ok(!dir.includes("SECRET-BODY-TOKEN-4419"));
});

test("directory: bounded at MAX_DIR_CHARS with a truncation marker naming the omission", () => {
  const r = root();
  for (let i = 0; i < 60; i++) {
    writeSkill(r, `skill-${String(i).padStart(2, "0")}`, `Description number ${i} with enough words to take real space in the directory listing`, "body");
  }
  const dir = storeWith(r).directory();
  assert.ok(dir.length <= MAX_DIR_CHARS, `directory is ${dir.length} chars`);
  assert.match(dir, /\[… \d+ more skills not shown; use skill_search to find them\]$/);
});

// ---------- allowlist ----------

test("allowlist: empty means all eligible; a set restricts directory, search and read alike", () => {
  const r = root();
  writeSkill(r, "alpha", "the alpha skill", "alpha body");
  writeSkill(r, "beta", "the beta skill", "beta body");
  const all = storeWith(r);
  assert.deepEqual(all.list().map((e) => e.name), ["alpha", "beta"]);

  const limited = new SkillStore(["beta"]);
  limited.discover([r]);
  assert.deepEqual(limited.list().map((e) => e.name), ["beta"]);
  assert.ok(!limited.directory().includes("alpha"));
  // "alpha" would name-match the ineligible skill; only beta's description hit counts.
  assert.deepEqual(limited.search("alpha skill"), ["beta"]);
});

// ---------- search ----------

test("search: name matches weigh 2x, description matches 1x, no match = empty", () => {
  const r = root();
  writeSkill(r, "weather-report", "current conditions", "body");
  writeSkill(r, "daily-brief", "the weather and news brief", "body");
  writeSkill(r, "unrelated", "nothing matching", "body");
  const s = storeWith(r);
  // "weather" hits weather-report's name (2) and daily-brief's description (1).
  assert.deepEqual(s.search("weather"), ["weather-report", "daily-brief"]);
  assert.deepEqual(s.search("zzzz"), []);
});

// ---------- read ----------

test("read: full body with provenance, unknown and ineligible names give null", () => {
  const r = root();
  const file = writeSkill(r, "alpha", "the alpha skill", "alpha body text");
  const s = storeWith(r);
  const got = s.readResult("alpha");
  assert.equal(got!.location, file);
  assert.ok(got!.body.includes("alpha body text"));
  assert.equal(got!.truncated, false);
  assert.equal(s.read("missing"), null);
  assert.equal(s.read("../../etc/passwd"), null);
  assert.equal(s.read("/etc/passwd"), null);

  const limited = new SkillStore(["other"]);
  limited.discover([r]);
  assert.equal(limited.read("alpha"), null);
});

test("read: a body larger than MAX_READ_BYTES is cut at the cap and marked truncated", () => {
  const r = root();
  writeSkill(r, "big", "a big skill", `start ${"x".repeat(MAX_READ_BYTES)} end`);
  const got = storeWith(r).readResult("big")!;
  assert.equal(Buffer.byteLength(got.body), MAX_READ_BYTES);
  assert.equal(got.truncated, true);
  assert.ok(!got.body.includes(" end"));
});

// ---------- roots + config ----------

test("skillRoots: app-home first, workspace second, config extras last (relative = from home)", () => {
  const home = path.join(root(), "home");
  const ws = path.join(home, "workspace");
  assert.deepEqual(skillRoots(home, ws, ["extra-dir", "/abs/dir"]), [
    path.join(home, "skills"),
    path.join(ws, "skills"),
    path.join(home, "extra-dir"),
    "/abs/dir",
  ]);
});

test("config: skills defaults are allow-all with no extra roots; a saved file merges over them", () => {
  const home = root();
  const p = paths(home);
  assert.deepEqual(defaults(p).skills, { allow: [], roots: [] });
  assert.equal(p.skills, path.join(home, "skills"));
  writeFileSync(p.config, JSON.stringify({ skills: { allow: ["alpha"], roots: ["/somewhere"] } }));
  const cfg = loadConfig(p);
  assert.deepEqual(cfg.skills, { allow: ["alpha"], roots: ["/somewhere"] });
});

test("prompt: the <skills> block appears only when a directory is given", () => {
  const home = root();
  const withSkills = systemPrompt(home, "test-os", "- alpha: the alpha skill [/x/alpha/SKILL.md]");
  assert.ok(withSkills.includes("<skills>"));
  assert.ok(withSkills.includes("- alpha: the alpha skill [/x/alpha/SKILL.md]"));
  assert.ok(withSkills.includes("skill_read"));
  const without = systemPrompt(home, "test-os");
  assert.ok(!without.includes("<skills>"));
});

// ---------- tools (direct) ----------

function ctxFor(store: SkillStore): ToolContext {
  return {
    cwd: root(), workspace: root(), memory: new Memory(null), embedder: null, vision: null,
    skills: store, signal: new AbortController().signal,
  };
}

function toolByName(name: string) {
  const t = ALL_TOOLS.find((x) => x.name === name);
  assert.ok(t, `tool ${name} exists`);
  return t!;
}

test("tools: skill_list / skill_search return metadata; skill_read returns the body with its source", async () => {
  const r = root();
  const file = writeSkill(r, "marker-answer", "Formats a marker code answer", "the procedure body");
  const ctx = ctxFor(storeWith(r));
  assert.equal(await toolByName("skill_list").run({}, ctx), `- marker-answer: Formats a marker code answer [${file}]`);
  assert.equal(await toolByName("skill_search").run({ query: "marker code" }, ctx), `- marker-answer: Formats a marker code answer [${file}]`);
  assert.equal(await toolByName("skill_search").run({ query: "zzzz" }, ctx), `No skill matches "zzzz".`);
  const read = await toolByName("skill_read").run({ name: "marker-answer" }, ctx);
  assert.ok(read.startsWith(`Skill: marker-answer\nSource: ${file}\n\n`));
  assert.ok(read.includes("the procedure body"));
});

test("tools: skill_read is read-class, takes no path, and refuses names that are paths", async () => {
  const r = root();
  writeSkill(r, "alpha", "the alpha skill", "alpha body");
  const t = toolByName("skill_read");
  assert.deepEqual(t.policy, { effects: ["read"] });
  await assert.rejects(() => t.run({ name: "../../etc/passwd" }, ctxFor(storeWith(r))), /No skill named/);
  await assert.rejects(() => t.run({ name: "missing" }, ctxFor(storeWith(r))), /No skill named "missing"/);
});

test("tools: skill tools stay available offline (they are local reads, not network)", () => {
  for (const name of ["skill_list", "skill_search", "skill_read"]) {
    assert.notEqual(toolByName(name).network, true);
  }
});

// ---------- runtime wiring ----------

test("runtime: skills discovered from the app-home dir, the allowlist applied, state counts them", () => {
  const home = root();
  const p = paths(home);
  writeSkill(p.skills, "alpha", "the alpha skill", "alpha body");
  writeSkill(path.join(p.workspace, "skills"), "beta", "the beta skill", "beta body");
  const cfg = loadConfig(p);
  const rt = new Runtime(cfg, p, home);
  assert.deepEqual(rt.skillStore().list().map((e) => e.name), ["alpha", "beta"]);
  assert.equal((rt.state() as unknown as Record<string, unknown>).skills, 2);
  cfg.skills.allow = ["beta"];
  assert.deepEqual(rt.skillStore().list().map((e) => e.name), ["beta"]);
});

// ---------- live check through the agent loop (scripted provider, repo harness pattern) ----------

test("loop live check: directory in the prompt has metadata not the body; skill_read loads it; the procedure is followed", async () => {
  const home = root();
  const file = writeSkill(path.join(home, "skills"), "marker-answer",
    "Formats a marker code answer in the XYZ format",
    "Procedure ZEBRA-4419: when asked for a marker code, answer with exactly one marker in the format XYZ-4419 — the letters XYZ, a hyphen, the digits 4419 — and nothing else.");
  const store = storeWith(path.join(home, "skills"));

  const seenSystems: string[] = [];
  let confirmCalls = 0;
  const provider: Provider = {
    kind: "local", label: "scripted",
    async turn(req: TurnRequest) {
      seenSystems.push(req.system);
      const toolTurn = req.turns.find((t) => t.role === "tool");
      if (!toolTurn || toolTurn.role !== "tool") {
        // First turn: the model asks for the skill body by name.
        return { text: "", stop: "tool_use", toolCalls: [{ id: "s1", name: "skill_read", input: { name: "marker-answer" } }] };
      }
      // Second turn: follow the procedure found in the loaded body.
      const body = toolTurn.results.map((r) => r.content).join("\n");
      const marker = /XYZ-\d{4}/.exec(body);
      assert.ok(marker, "the loaded body carries the marker format");
      return { text: marker![0], stop: "end", toolCalls: [] };
    },
  };

  const events: AgentEvent[] = [];
  const agent = new Agent({
    provider: () => provider,
    embedder: () => null,
    vision: () => null,
    skills: () => store,
    tools: () => ALL_TOOLS,
    gate: new Gate({ writableRoots: [home], extraAllowedCommands: [] }, home),
    memory: new Memory(null),
    audit: new AuditLog(null),
    home, workspace: home, cwd: home, maxTurns: 4,
  });
  const answer = await agent.run("give me a marker code", {
    emit: (e) => events.push(e),
    confirm: async () => { confirmCalls++; return false; },
  });

  // (1) The prompt directory carried the metadata, not the body.
  assert.equal(seenSystems.length, 2);
  assert.ok(seenSystems[0]!.includes(`- marker-answer: Formats a marker code answer in the XYZ format [${file}]`));
  assert.ok(!seenSystems[0]!.includes("ZEBRA-4419"), "skill body leaked into the prompt");
  assert.ok(!seenSystems[0]!.includes("4419"), "skill body digits leaked into the prompt");

  // (2) The session invoked skill_read and the body loaded, with provenance.
  const toolTurn = agent.turns.find((t) => t.role === "tool");
  assert.ok(toolTurn && toolTurn.role === "tool");
  assert.equal(toolTurn.results[0]!.name, "skill_read");
  assert.equal(toolTurn.results[0]!.isError, false);
  assert.ok(toolTurn.results[0]!.content.includes(`Source: ${file}`));
  assert.ok(toolTurn.results[0]!.content.includes("ZEBRA-4419"));
  assert.ok(events.some((e) => e.type === "tool_start" && e.name === "skill_read"));
  assert.ok(events.some((e) => e.type === "tool_end" && e.name === "skill_read" && e.ok));

  // Read-class for the gate: no confirm card was ever asked.
  assert.equal(confirmCalls, 0);

  // (3) The procedure from the body was followed in the run's behaviour.
  assert.equal(answer, "XYZ-4419");
});
