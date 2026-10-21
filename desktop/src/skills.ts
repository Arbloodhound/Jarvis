import { closeSync, existsSync, openSync, readFileSync, readSync, readdirSync, realpathSync, statSync } from "node:fs";
import { homedir } from "node:os";
import path from "node:path";

/**
 * Skills — lazy loading (contract adapted from OpenClaw, MIT; faithful port
 * of harness/skills.py, which is the semantics source for this cluster).
 *
 * - Discovery scans precedence-tiered roots and parses SKILL.md frontmatter,
 *   caching metadata-only entries. Earlier roots win: a skill name already
 *   seen is never overridden by a later root.
 * - The prompt gets only a bounded directory (name + description +
 *   location). Full bodies load on demand via search + read.
 * - An allowlist governs which skills a session sees. Following the Python
 *   original (`set(allowlist) if allowlist else None`), an empty or absent
 *   allowlist means every discovered skill is eligible.
 *
 * Desktop root mapping (the Python store takes its roots from its caller;
 * no Python caller fixes them, so this is the desktop layout's mapping):
 *   1. `<JARVIS_HOME>/skills`            — app-home skills dir (highest)
 *   2. `<JARVIS_HOME>/workspace/skills`  — workspace skills dir
 *   3. `skills.roots` from config.json   — extra roots, in listed order
 */

/** Budget for the directory injected into the prompt. */
export const MAX_DIR_CHARS = 4000;
/** Cap on a single full SKILL.md read. */
export const MAX_READ_BYTES = 256 * 1024;
/** Frontmatter is read from this many leading bytes of a SKILL.md. */
const HEAD_BYTES = 4096;

const FRONTMATTER_RE = /^---\s*\n([\s\S]*?)\n---\s*\n/;

/**
 * The Python parser: `key: value` lines between --- fences, values stripped
 * of surrounding quotes. No YAML library — frontmatter here is flat.
 */
export function parseFrontmatter(text: string): Record<string, string> {
  const m = FRONTMATTER_RE.exec(text);
  if (!m) return {};
  const out: Record<string, string> = {};
  for (const line of m[1]!.split("\n")) {
    const i = line.indexOf(":");
    if (i < 0) continue;
    out[line.slice(0, i).trim()] = line.slice(i + 1).trim().replace(/^["']|["']$/g, "");
  }
  return out;
}

export interface SkillEntry {
  name: string;
  description: string;
  /** Absolute path to the SKILL.md — the body's provenance. */
  location: string;
  /** Every frontmatter field, including any beyond name/description. */
  frontmatter: Record<string, string>;
}

export interface SkillRead {
  name: string;
  /** Absolute path the body came from. */
  location: string;
  body: string;
  /** True when the file was larger than MAX_READ_BYTES and the body was cut. */
  truncated: boolean;
}

function readHead(file: string): string {
  const fd = openSync(file, "r");
  try {
    const buf = Buffer.alloc(HEAD_BYTES);
    const n = readSync(fd, buf, 0, HEAD_BYTES, 0);
    return buf.subarray(0, n).toString("utf8");
  } finally {
    closeSync(fd);
  }
}

/** Every SKILL.md under root, recursively. Sorted so discovery is deterministic. */
function findSkillFiles(root: string, out: string[] = []): string[] {
  let entries;
  try {
    entries = readdirSync(root, { withFileTypes: true });
  } catch {
    return out;
  }
  entries.sort((a, b) => a.name.localeCompare(b.name));
  for (const e of entries) {
    const full = path.join(root, e.name);
    if (e.isDirectory()) findSkillFiles(full, out);
    else if (e.isFile() && e.name === "SKILL.md") out.push(full);
  }
  return out;
}

function isInsideDir(child: string, parent: string): boolean {
  const rel = path.relative(parent, child);
  return rel === "" || (!rel.startsWith("..") && !path.isAbsolute(rel));
}

export class SkillStore {
  private readonly entries = new Map<string, SkillEntry>();
  private readonly roots: string[] = [];
  /** null = no restriction (Python: an empty/absent allowlist becomes None). */
  readonly allowlist: Set<string> | null;

  constructor(allowlist?: readonly string[] | null) {
    this.allowlist = allowlist && allowlist.length ? new Set(allowlist) : null;
  }

  /** Scan roots for SKILL.md files. Earlier roots win; later roots don't override. */
  discover(roots: readonly string[]): number {
    for (const root of roots) {
      const abs = path.resolve(root);
      if (!this.roots.includes(abs)) this.roots.push(abs);
      if (!existsSync(abs) || !statSync(abs).isDirectory()) continue;
      for (const file of findSkillFiles(abs)) {
        let head: string;
        try {
          head = readHead(file);
        } catch {
          continue;
        }
        const fm = parseFrontmatter(head);
        const name = fm.name || path.basename(path.dirname(file));
        if (!name || this.entries.has(name)) continue;
        this.entries.set(name, { name, description: fm.description ?? "", location: file, frontmatter: fm });
      }
    }
    return this.entries.size;
  }

  eligible(name: string): boolean {
    return this.allowlist === null || this.allowlist.has(name);
  }

  /** One discovered skill's metadata, or null when unknown. */
  entry(name: string): SkillEntry | null {
    return this.entries.get(name) ?? null;
  }

  /** All eligible skills, sorted by name. Metadata only — never bodies. */
  list(): SkillEntry[] {
    return [...this.entries.values()].filter((e) => this.eligible(e.name)).sort((a, b) => a.name.localeCompare(b.name));
  }

  /**
   * Bounded directory for prompt injection: one `- name: description
   * [location]` line per eligible skill, sorted, at most MAX_DIR_CHARS
   * characters in total. When skills are left out, a trailing marker line
   * says how many (the Python original stops silently; the marker is the
   * one deliberate desktop addition, and it counts against the budget).
   */
  directory(): string {
    const all = this.list();
    const lines: string[] = [];
    // The budget is on the joined string, newlines included (the Python
    // original counts line lengths only; counting the newlines too is the
    // stricter reading of the same 4000-char budget, so the injected text
    // itself never exceeds it).
    let used = 0;
    let shown = 0;
    for (const e of all) {
      const line = `- ${e.name}: ${e.description} [${e.location}]`;
      const needed = used + (lines.length ? 1 : 0) + line.length;
      if (needed > MAX_DIR_CHARS) break;
      lines.push(line);
      used = needed;
      shown++;
    }
    if (shown < all.length) {
      // Make room for the marker by dropping trailing lines until it fits.
      for (;;) {
        const omitted = all.length - shown;
        const marker = `- [… ${omitted} more skill${omitted === 1 ? "" : "s"} not shown; use skill_search to find them]`;
        const needed = used + (lines.length ? 1 : 0) + marker.length;
        if (needed <= MAX_DIR_CHARS) {
          lines.push(marker);
          break;
        }
        const dropped = lines.pop();
        if (dropped === undefined) break;
        used -= dropped.length + (lines.length ? 1 : 0);
        shown--;
      }
    }
    return lines.join("\n");
  }

  /**
   * Lexical search; name matches weigh 2x (the Python scoring, including
   * its tie-break: equal scores order by name, descending, as Python's
   * `sorted(..., reverse=True)` on (score, name) tuples does).
   */
  search(query: string): string[] {
    const qtok = new Set(query.toLowerCase().split(/\s+/).filter(Boolean));
    const scored: Array<[number, string]> = [];
    for (const e of this.entries.values()) {
      if (!this.eligible(e.name)) continue;
      const nameToks = new Set(e.name.toLowerCase().replaceAll("-", " ").split(/\s+/).filter(Boolean));
      const descToks = new Set(e.description.toLowerCase().split(/\s+/).filter(Boolean));
      const nameHits = [...qtok].filter((t) => nameToks.has(t)).length;
      const descHits = [...qtok].filter((t) => descToks.has(t)).length;
      const score = 2 * nameHits + descHits;
      if (score > 0) scored.push([score, e.name]);
    }
    scored.sort((a, b) => b[0] - a[0] || (a[1] < b[1] ? 1 : a[1] > b[1] ? -1 : 0));
    return scored.map(([, name]) => name);
  }

  /** Search results as metadata entries, best first. */
  searchEntries(query: string): SkillEntry[] {
    return this.search(query).map((n) => this.entries.get(n)!).filter(Boolean);
  }

  /**
   * Full SKILL.md, size-capped. Null when the skill is unknown or not
   * eligible. The body only ever comes from a discovered entry, and the
   * entry's path is re-checked against the discovered roots before the
   * read, so a name can never be turned into a path outside the roots.
   */
  read(name: string): string | null {
    return this.readResult(name)?.body ?? null;
  }

  readResult(name: string): SkillRead | null {
    const e = this.entries.get(name);
    if (!e || !this.eligible(name)) return null;
    if (!this.confined(e.location)) return null;
    try {
      const buf = readFileSync(e.location);
      const truncated = buf.length > MAX_READ_BYTES;
      return { name: e.name, location: e.location, body: buf.subarray(0, MAX_READ_BYTES).toString("utf8"), truncated };
    } catch {
      return null;
    }
  }

  /** True when file (symlinks followed) sits inside one of the discovered roots. */
  private confined(file: string): boolean {
    let real: string;
    try {
      real = realpathSync.native(file);
    } catch {
      return false;
    }
    return this.roots.some((root) => {
      try {
        return isInsideDir(real, realpathSync.native(root));
      } catch {
        return isInsideDir(path.resolve(file), root);
      }
    });
  }
}

/**
 * The precedence-ordered skill roots for a Jarvis home (see the mapping in
 * the header comment). Extra config roots are resolved like the gate
 * resolves paths: `~` is the OS home, a relative root is taken from the
 * Jarvis home.
 */
export function skillRoots(home: string, workspace: string, extraRoots: readonly string[] = []): string[] {
  const extra = extraRoots.map((r) => {
    const expanded = r === "~" ? homedir() : r.startsWith("~/") || r.startsWith("~\\") ? path.join(homedir(), r.slice(2)) : r;
    return path.isAbsolute(expanded) ? expanded : path.join(home, expanded);
  });
  return [path.join(home, "skills"), path.join(workspace, "skills"), ...extra];
}
