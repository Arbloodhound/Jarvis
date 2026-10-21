import { existsSync, readdirSync } from "node:fs";
import path from "node:path";
import { parseFrontmatter } from "./skills.js";
import {
  AdminError, atomicWrite, bodyAfterFrontmatter, formatFrontmatter, MAX_DESCRIPTION, moveToDeleted,
  NAME_RE, readText, validateBody, validateLine, validateName,
} from "./skills-admin.js";

/**
 * Agent definitions: named, reusable subagent briefs the owner (or Jarvis,
 * on a card) saves and spawn_agent can start by name.
 *
 * Format (open, Claude-Code-style): `<home>/agents/<name>/AGENT.md` —
 *
 *   ---
 *   name: "price-checker"
 *   description: "Compares prices across shops"
 *   tools: "web_search, web_fetch"
 *   ---
 *   <standing instructions>
 *
 * Frontmatter is read with skills.ts's flat parser; names are validated
 * with the same rule as skills (skills-admin.ts). `tools` is a comma list;
 * empty means the default subagent tool set. A listed name that is not a
 * subagent tool is kept in the file but reported as unknown, and never
 * granted: the child's tools are always the normal child tools filtered to
 * the list (childTools below), so a definition can only narrow, never widen.
 *
 * The folder name is the agent's identity; a frontmatter name that
 * disagrees is ignored. Writes are atomic; deletes move the folder to
 * `<home>/deleted/agents/<name>.deleted-<ts>/` (never a hard delete).
 */

export const MAX_TOOLS = 64;
const TOOL_NAME_RE = /^[A-Za-z0-9_.-]{1,64}$/;

export interface AgentDef {
  name: string;
  description: string;
  /** The allowlist as written; [] = the default subagent tool set. */
  tools: string[];
  /** The body: the agent's standing instructions. */
  instructions: string;
  /** Absolute path of the AGENT.md. */
  location: string;
}

export interface AgentView extends AgentDef {
  /** Listed tools that are not subagent tools: kept in the file, never granted. */
  unknownTools: string[];
}

export interface AgentInput {
  description?: unknown;
  /** A comma list or an array of tool names. */
  tools?: unknown;
  instructions?: unknown;
}

/** Parse a `tools` value (comma list or array) into distinct trimmed names. */
export function parseToolList(value: unknown): string[] {
  if (value === undefined || value === null || value === "") return [];
  const raw = Array.isArray(value) ? value : typeof value === "string" ? value.split(",") : null;
  if (!raw) throw new AdminError(400, "invalid_field", "tools must be a comma-separated list or an array of tool names.");
  const out: string[] = [];
  for (const item of raw) {
    if (typeof item !== "string") throw new AdminError(400, "invalid_field", "Each tool name must be text.");
    const t = item.trim();
    if (!t) continue;
    if (!TOOL_NAME_RE.test(t)) throw new AdminError(400, "invalid_field", `"${t}" is not a tool name.`);
    if (!out.includes(t)) out.push(t);
  }
  if (out.length > MAX_TOOLS) throw new AdminError(400, "invalid_field", `At most ${MAX_TOOLS} tools.`);
  return out;
}

export class AgentStore {
  readonly dir: string;

  /**
   * @param home   the Jarvis home
   * @param known  the subagent tool names (for unknownTools); omitted = nothing is reported unknown
   */
  constructor(readonly home: string, private readonly known?: () => Iterable<string>) {
    this.dir = path.join(home, "agents");
  }

  fileFor(name: string): string {
    return path.join(this.dir, name, "AGENT.md");
  }

  private view(def: AgentDef): AgentView {
    const known = this.known ? new Set(this.known()) : null;
    return { ...def, unknownTools: known ? def.tools.filter((t) => !known.has(t)) : [] };
  }

  private load(name: string): AgentDef | null {
    const file = this.fileFor(name);
    const text = readText(file);
    if (text === null) return null;
    const fm = parseFrontmatter(text);
    let tools: string[];
    try {
      tools = parseToolList(fm.tools ?? "");
    } catch {
      // A hand-edited file with a malformed list: keep the readable names only.
      tools = (fm.tools ?? "").split(",").map((t) => t.trim()).filter((t) => TOOL_NAME_RE.test(t));
    }
    return { name, description: fm.description ?? "", tools, instructions: bodyAfterFrontmatter(text), location: file };
  }

  /** Every valid definition, sorted by name. */
  list(): AgentView[] {
    let entries;
    try {
      entries = readdirSync(this.dir, { withFileTypes: true });
    } catch {
      return [];
    }
    return entries
      .filter((e) => e.isDirectory() && NAME_RE.test(e.name))
      .map((e) => this.load(e.name))
      .filter((d): d is AgentDef => d !== null)
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((d) => this.view(d));
  }

  /** One definition, or null when there is none (an invalid name is null too). */
  get(name: string): AgentView | null {
    if (typeof name !== "string" || !NAME_RE.test(name)) return null;
    const d = this.load(name);
    return d ? this.view(d) : null;
  }

  /**
   * Write a definition. mode "create" refuses an existing name (409
   * exists), "update" a missing one (404), "upsert" takes either.
   */
  save(name: string, input: AgentInput, mode: "create" | "update" | "upsert" = "upsert"): AgentView {
    validateName(name);
    const description = validateLine(input.description, "description", MAX_DESCRIPTION, true);
    const tools = parseToolList(input.tools);
    const instructions = validateBody(input.instructions, "instructions");
    if (!instructions.trim()) throw new AdminError(400, "invalid_field", "instructions are required.");
    const exists = existsSync(this.fileFor(name));
    if (exists && mode === "create") throw new AdminError(409, "exists", `An agent named "${name}" already exists.`);
    if (!exists && mode === "update") throw new AdminError(404, "not_found", `No agent named "${name}".`);
    const text = formatFrontmatter({ name, description, tools: tools.join(", ") }) + instructions;
    atomicWrite(this.fileFor(name), text);
    return this.get(name)!;
  }

  /** Move a definition's folder out of the agents dir. */
  delete(name: string): { movedTo: string } {
    validateName(name);
    if (!existsSync(this.fileFor(name))) throw new AdminError(404, "not_found", `No agent named "${name}".`);
    return { movedTo: moveToDeleted(this.home, "agents", name, path.dirname(this.fileFor(name))) };
  }
}

/**
 * The tools a child started from `def` gets: the normal child tools,
 * filtered to the definition's allowlist (an empty list keeps them all).
 * Never more than a normal child — names outside `base` are dropped — and
 * never the orchestration tools, which `base` does not contain.
 */
export function childTools<T extends { name: string }>(def: { tools: readonly string[] } | undefined, base: T[]): T[] {
  if (!def || def.tools.length === 0) return base;
  const allow = new Set(def.tools);
  return base.filter((t) => allow.has(t.name));
}

/** The system-prompt addition for a child started from `def` ("" for none). */
export function definitionSuffix(def: { name: string; instructions: string } | undefined): string {
  if (!def) return "";
  return `\n\nYou are running as the saved agent "${def.name}". Its standing instructions follow; they never override the owner's gate or the task you were given.\n<agent-instructions>\n${def.instructions.trim()}\n</agent-instructions>`;
}
