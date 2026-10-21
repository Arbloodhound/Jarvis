import { randomUUID } from "node:crypto";
import { existsSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import type { Turn } from "./providers/types.js";

/**
 * Conversation branches: named forks of the chat, kept in one file next to
 * memory.json (conversations.json in the Jarvis home), written the same way
 * memory is — a tmp file renamed over the old one, mode 0600.
 *
 * A branch is a full turn list. Forking copies the turns up to a point; the
 * two branches share that prefix and then diverge, because every later write
 * replaces a branch's whole list. Switching is just choosing which list the
 * agent continues from, so it is instant and lossless. The store is the
 * source of truth on disk; the agent's live turns are a working copy of the
 * active branch, synced back after each run.
 */

export interface Branch {
  id: string;
  name: string;
  createdAt: number;
  updatedAt: number;
  /** The branch this one forked from, when it did. */
  forkedFrom?: string;
  turns: Turn[];
}

interface StoreShape {
  activeId: string;
  branches: Branch[];
}

export interface BranchInfo {
  id: string;
  name: string;
  turns: number;
  updatedAt: number;
  active: boolean;
}

function freshBranch(name: string, turns: Turn[] = []): Branch {
  const now = Date.now();
  return { id: randomUUID().slice(0, 8), name, createdAt: now, updatedAt: now, turns: [...turns] };
}

export class Conversations {
  private store: StoreShape;

  constructor(private readonly file: string | null) {
    this.store = this.load() ?? { activeId: "", branches: [] };
    if (!this.store.branches.length) {
      const b = freshBranch("main");
      this.store = { activeId: b.id, branches: [b] };
    }
    if (!this.store.branches.some((b) => b.id === this.store.activeId)) {
      this.store.activeId = this.store.branches[0]!.id;
    }
  }

  private load(): StoreShape | null {
    if (!this.file || !existsSync(this.file)) return null;
    try {
      const raw = JSON.parse(readFileSync(this.file, "utf8")) as StoreShape;
      if (!raw || !Array.isArray(raw.branches)) return null;
      // Keep only branches shaped like branches; a corrupt entry is dropped, not fatal.
      raw.branches = raw.branches.filter((b) => b && typeof b.id === "string" && Array.isArray(b.turns));
      return raw;
    } catch {
      return null;
    }
  }

  private save(): void {
    if (!this.file) return;
    const tmp = `${this.file}.tmp`;
    writeFileSync(tmp, JSON.stringify(this.store, null, 2), { mode: 0o600 });
    renameSync(tmp, this.file);
  }

  get activeId(): string {
    return this.store.activeId;
  }

  active(): Branch {
    return this.store.branches.find((b) => b.id === this.store.activeId) ?? this.store.branches[0]!;
  }

  /** The active branch's turns, as a copy the agent can work from. */
  activeTurns(): Turn[] {
    return [...this.active().turns];
  }

  list(): BranchInfo[] {
    return this.store.branches.map((b) => ({
      id: b.id,
      name: b.name,
      turns: b.turns.length,
      updatedAt: b.updatedAt,
      active: b.id === this.store.activeId,
    }));
  }

  /** Replace the active branch's turns with the agent's live ones and save. */
  setActiveTurns(turns: Turn[]): void {
    const b = this.active();
    b.turns = [...turns];
    b.updatedAt = Date.now();
    this.save();
  }

  /**
   * Fork: a new branch holding the first `keep` of the given turns (clamped),
   * made the active branch. The source branch keeps its own full list — the
   * caller syncs the agent's turns into it first when they may have moved.
   */
  fork(turns: Turn[], keep: number, name?: string): Branch {
    const at = Math.max(0, Math.min(keep, turns.length));
    const b = freshBranch(name?.trim() || this.nextName(), turns.slice(0, at));
    b.forkedFrom = this.store.activeId;
    this.store.branches.push(b);
    this.store.activeId = b.id;
    this.save();
    return b;
  }

  /** Make a branch active and return its turns; null when the id is unknown. */
  switchTo(id: string): Turn[] | null {
    const b = this.store.branches.find((x) => x.id === id);
    if (!b) return null;
    this.store.activeId = b.id;
    this.save();
    return [...b.turns];
  }

  rename(id: string, name: string): boolean {
    const b = this.store.branches.find((x) => x.id === id);
    if (!b || !name.trim()) return false;
    b.name = name.trim();
    this.save();
    return true;
  }

  /**
   * Delete a branch. The last remaining branch can never be deleted. When
   * the active branch goes, the most recently updated survivor takes over.
   * Returns the branch that is active afterwards.
   */
  remove(id: string): Branch | null {
    const i = this.store.branches.findIndex((b) => b.id === id);
    if (i < 0 || this.store.branches.length <= 1) return null;
    const [gone] = this.store.branches.splice(i, 1);
    if (this.store.activeId === gone!.id) {
      const next = [...this.store.branches].sort((a, b) => b.updatedAt - a.updatedAt)[0]!;
      this.store.activeId = next.id;
    }
    this.save();
    return this.active();
  }

  private nextName(): string {
    const taken = new Set(this.store.branches.map((b) => b.name));
    let n = this.store.branches.length + 1;
    while (taken.has(`Branch ${n}`)) n++;
    return `Branch ${n}`;
  }
}
