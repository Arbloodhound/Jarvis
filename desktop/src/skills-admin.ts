import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync } from "node:fs";
import { randomBytes } from "node:crypto";
import path from "node:path";
import { MAX_READ_BYTES, parseFrontmatter, SkillStore, type SkillEntry } from "./skills.js";

/**
 * Owner-side skill management: create, update and delete skills from the
 * Skills menu. The agent's own skill tools stay read-only (skills.ts); this
 * module is what the gateway's owner-authenticated endpoints call.
 *
 * - Format: `<home>/skills/<name>/SKILL.md` — flat `key: value`
 *   frontmatter between `---` fences, then the body. Files are read back
 *   with skills.ts's own parser (parseFrontmatter / SkillStore), never a
 *   second one; every value is written double-quoted, which that parser
 *   strips, so any single-line value round-trips exactly.
 * - Only skills that live in the app-home skills dir are editable. Skills
 *   discovered from the workspace dir or config-listed roots are listed
 *   read-only.
 * - Writes are atomic: a temp file in the same folder, then a rename.
 * - Deletes never hard-delete. There is no file trash in the desktop app
 *   (memory's trash is a list of forgotten facts inside memory.json, not a
 *   folder), so the skill folder is moved to
 *   `<home>/deleted/skills/<name>.deleted-<ts>/`, outside every skill root,
 *   and its SKILL.md is renamed `SKILL.md.deleted` inside it so no root
 *   (even an extra root configured over the home) can rediscover it.
 *   Restoring is a manual move back.
 */

/** A skill or agent name: lowercase letters, digits and single hyphens, like Claude Code's skills. */
export const NAME_RE = /^[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){0,63}$/;
export const MAX_DESCRIPTION = 1024;
/** skills.ts reads frontmatter from the first 4096 bytes; stay under that. */
const MAX_FRONTMATTER_BYTES = 3800;
/** Body caps: the skill read cap (bodies beyond it would be cut on read). */
export const MAX_BODY_BYTES = MAX_READ_BYTES - 8 * 1024;

/** An owner-facing failure with an HTTP-shaped status and a stable code. */
export class AdminError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

/** Throws AdminError(400, "invalid_name") unless name is a valid skill/agent name. */
export function validateName(name: unknown, what = "name"): string {
  if (typeof name !== "string" || !NAME_RE.test(name)) {
    throw new AdminError(400, "invalid_name", `The ${what} must be 1-64 lowercase letters, digits or single hyphens (e.g. "price-check").`);
  }
  return name;
}

/** A single-line frontmatter value: no line breaks, capped. */
export function validateLine(value: unknown, field: string, max = MAX_DESCRIPTION, required = false): string {
  if (value === undefined || value === null) value = "";
  if (typeof value !== "string") throw new AdminError(400, "invalid_field", `${field} must be text.`);
  const v = value.trim();
  if (/[\r\n]/.test(v)) throw new AdminError(400, "invalid_field", `${field} must be a single line.`);
  if (v.length > max) throw new AdminError(400, "invalid_field", `${field} is over ${max} characters.`);
  if (required && !v) throw new AdminError(400, "invalid_field", `${field} is required.`);
  return v;
}

export function validateBody(value: unknown, field: string): string {
  if (value === undefined || value === null) value = "";
  if (typeof value !== "string") throw new AdminError(400, "invalid_field", `${field} must be text.`);
  if (Buffer.byteLength(value, "utf8") > MAX_BODY_BYTES) throw new AdminError(413, "too_large", `${field} is over ${Math.floor(MAX_BODY_BYTES / 1024)} KB.`);
  return value;
}

/**
 * Frontmatter text for flat fields, every value double-quoted. Keys must be
 * simple identifiers; values single-line. The result is what skills.ts's
 * parseFrontmatter reads back to the same map (checked before any write).
 */
export function formatFrontmatter(fields: Record<string, string>): string {
  const lines = ["---"];
  for (const [k, v] of Object.entries(fields)) {
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(k)) throw new AdminError(400, "invalid_field", `Frontmatter key "${k}" is not a simple name.`);
    if (/[\r\n]/.test(v)) throw new AdminError(400, "invalid_field", `Frontmatter value for "${k}" must be a single line.`);
    lines.push(`${k}: "${v}"`);
  }
  lines.push("---", "");
  const text = lines.join("\n");
  if (Buffer.byteLength(text, "utf8") > MAX_FRONTMATTER_BYTES) throw new AdminError(413, "too_large", "The frontmatter is too long.");
  const back = parseFrontmatter(text + "\n");
  for (const [k, v] of Object.entries(fields)) {
    if (back[k] !== v) throw new AdminError(400, "invalid_field", `Frontmatter value for "${k}" would not read back unchanged.`);
  }
  return text;
}

/**
 * The text after the frontmatter. Same fence shape as skills.ts's
 * FRONTMATTER_RE (kept private there); a file without frontmatter is all body.
 */
export function bodyAfterFrontmatter(text: string): string {
  const m = /^---\s*\n[\s\S]*?\n---\s*\n/.exec(text);
  return m ? text.slice(m[0].length) : text;
}

/** Write file atomically: temp file beside it, then rename over it. */
export function atomicWrite(file: string, text: string): void {
  mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.tmp-${process.pid}-${randomBytes(4).toString("hex")}`;
  try {
    writeFileSync(tmp, text, { encoding: "utf8", mode: 0o600 });
    renameSync(tmp, file);
  } catch (err) {
    rmSync(tmp, { force: true });
    throw err;
  }
}

/** Move a folder to `<home>/deleted/<kind>/<name>.deleted-<ts>`; returns where it went. */
export function moveToDeleted(home: string, kind: "skills" | "agents", name: string, dir: string): string {
  const dest = path.join(home, "deleted", kind, `${name}.deleted-${new Date().toISOString().replace(/[:.]/g, "-")}`);
  mkdirSync(path.dirname(dest), { recursive: true });
  let target = dest;
  for (let n = 2; existsSync(target); n++) target = `${dest}-${n}`;
  renameSync(dir, target);
  return target;
}

export interface SkillListing {
  name: string;
  description: string;
  location: string;
  /** True when it lives in the app-home skills dir, the only place the menu edits. */
  editable: boolean;
  /** Whether the config allowlist lets sessions see it. */
  eligible: boolean;
}

export interface SkillDetail extends SkillListing {
  frontmatter: Record<string, string>;
  body: string;
}

export interface SkillInput {
  description?: unknown;
  body?: unknown;
  /** Extra flat frontmatter fields (beyond name/description), kept as given. */
  frontmatter?: unknown;
}

export class SkillAdmin {
  /** The app-home skills dir: where created skills go. */
  readonly dir: string;

  /**
   * @param home   the Jarvis home
   * @param roots  every skill root in precedence order (skillRoots()), for listing
   * @param allow  the config allowlist (for the eligible flag)
   */
  constructor(readonly home: string, private readonly roots: readonly string[], private readonly allow: readonly string[] = []) {
    this.dir = path.join(home, "skills");
  }

  private store(): SkillStore {
    const s = new SkillStore(null);
    s.discover(this.roots);
    return s;
  }

  private fileFor(name: string): string {
    return path.join(this.dir, name, "SKILL.md");
  }

  private view(e: SkillEntry): SkillListing {
    return {
      name: e.name,
      description: e.description,
      location: e.location,
      editable: path.resolve(e.location) === path.resolve(this.fileFor(e.name)),
      eligible: this.allow.length === 0 || this.allow.includes(e.name),
    };
  }

  list(): SkillListing[] {
    return this.store().list().map((e) => this.view(e));
  }

  get(name: string): SkillDetail | null {
    validateName(name);
    const s = this.store();
    const e = s.entry(name);
    if (!e) return null;
    const read = s.readResult(name);
    if (!read) return null;
    return { ...this.view(e), frontmatter: e.frontmatter, body: bodyAfterFrontmatter(read.body) };
  }

  /**
   * Create (mode "create"), update ("update") or either ("upsert") the skill
   * `<home>/skills/<name>/SKILL.md`. Throws AdminError on a bad field, a
   * name owned by a read-only root (409 read_only), or a create over an
   * existing skill (409 exists) / an update of a missing one (404).
   */
  save(name: string, input: SkillInput, mode: "create" | "update" | "upsert" = "upsert"): SkillDetail {
    validateName(name);
    const description = validateLine(input.description, "description", MAX_DESCRIPTION, true);
    const body = validateBody(input.body, "body");
    const extra: Record<string, string> = {};
    if (input.frontmatter !== undefined && input.frontmatter !== null) {
      if (typeof input.frontmatter !== "object" || Array.isArray(input.frontmatter)) throw new AdminError(400, "invalid_field", "frontmatter must be an object of single-line text values.");
      for (const [k, v] of Object.entries(input.frontmatter as Record<string, unknown>)) {
        if (k === "name" || k === "description") continue;
        extra[k] = validateLine(v, `frontmatter.${k}`, MAX_DESCRIPTION);
      }
    }
    const existing = this.store().entry(name);
    const file = this.fileFor(name);
    if (existing && path.resolve(existing.location) !== path.resolve(file)) {
      throw new AdminError(409, "read_only", `A skill named "${name}" already comes from ${existing.location}, outside the app's skills folder; edit it there.`);
    }
    if (!existing && existsSync(file)) {
      // A SKILL.md in that folder that is listed under another name.
      throw new AdminError(409, "conflict", `${file} already holds a skill with a different name; rename or remove it first.`);
    }
    if (existing && mode === "create") throw new AdminError(409, "exists", `A skill named "${name}" already exists.`);
    if (!existing && mode === "update") throw new AdminError(404, "not_found", `No skill named "${name}".`);
    // Fields from the existing file that the form does not show survive an edit.
    const kept: Record<string, string> = {};
    if (existing && input.frontmatter === undefined) {
      for (const [k, v] of Object.entries(existing.frontmatter)) if (k !== "name" && k !== "description") kept[k] = v;
    }
    const text = formatFrontmatter({ name, description, ...kept, ...extra }) + body;
    atomicWrite(file, text);
    return this.get(name)!;
  }

  /** Move an editable skill out of every root. Returns where it went. */
  delete(name: string): { movedTo: string } {
    validateName(name);
    const e = this.store().entry(name);
    if (!e) throw new AdminError(404, "not_found", `No skill named "${name}".`);
    if (path.resolve(e.location) !== path.resolve(this.fileFor(name))) {
      throw new AdminError(409, "read_only", `The skill "${name}" comes from ${e.location}, outside the app's skills folder; remove it there.`);
    }
    const dir = path.dirname(this.fileFor(name));
    if (!statSync(dir).isDirectory()) throw new AdminError(404, "not_found", `No skill named "${name}".`);
    const movedTo = moveToDeleted(this.home, "skills", name, dir);
    const skillFile = path.join(movedTo, "SKILL.md");
    if (existsSync(skillFile)) renameSync(skillFile, `${skillFile}.deleted`);
    return { movedTo };
  }
}

/** Read a whole small text file, or null. */
export function readText(file: string): string | null {
  try {
    return readFileSync(file, "utf8");
  } catch {
    return null;
  }
}
