import { randomUUID } from "node:crypto";
import { existsSync, readFileSync, renameSync, writeFileSync } from "node:fs";

/**
 * What Jarvis remembers about its owner: short facts in a JSON file, searched
 * by word overlap. Forgotten facts go to a trash list first, as on the phone,
 * so "forget" can be undone until the trash is emptied.
 *
 * Deliberately simple: no embeddings yet. Recall quality is word overlap and
 * nothing more; that is stated, not hidden.
 */

export interface Fact {
  id: string;
  text: string;
  at: string;
}

interface Store {
  facts: Fact[];
  trash: Fact[];
}

const STOP = new Set("a an the i me my you your is are was were be to of in on at for and or it that this what do does did about with".split(" "));

function words(s: string): string[] {
  return s.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter((w) => w.length > 1 && !STOP.has(w));
}

export class Memory {
  private store: Store;

  constructor(private readonly file: string | null) {
    this.store = file && existsSync(file) ? (JSON.parse(readFileSync(file, "utf8")) as Store) : { facts: [], trash: [] };
  }

  remember(text: string): Fact {
    const t = text.trim();
    const same = this.store.facts.find((f) => f.text.toLowerCase() === t.toLowerCase());
    if (same) return same;
    const f = { id: randomUUID().slice(0, 8), text: t, at: new Date().toISOString() };
    this.store.facts.push(f);
    this.save();
    return f;
  }

  recall(query: string, limit = 8): Fact[] {
    const q = new Set(words(query));
    if (q.size === 0) return this.store.facts.slice(-limit).reverse();
    return this.store.facts
      .map((f) => ({ f, score: words(f.text).filter((w) => q.has(w)).length }))
      .filter((x) => x.score > 0)
      .sort((a, b) => b.score - a.score || b.f.at.localeCompare(a.f.at))
      .slice(0, limit)
      .map((x) => x.f);
  }

  /** Move matching facts to the trash. Returns what moved. */
  forget(query: string): Fact[] {
    const byId = this.store.facts.filter((f) => f.id === query.trim());
    const hits = byId.length ? byId : this.recall(query, 50).filter((f) => {
      const q = words(query);
      const fw = new Set(words(f.text));
      return q.length > 0 && q.every((w) => fw.has(w));
    });
    if (!hits.length) return [];
    const ids = new Set(hits.map((f) => f.id));
    this.store.facts = this.store.facts.filter((f) => !ids.has(f.id));
    this.store.trash.push(...hits);
    this.save();
    return hits;
  }

  all(): Fact[] {
    return [...this.store.facts];
  }

  private save(): void {
    if (!this.file) return;
    const tmp = `${this.file}.tmp`;
    writeFileSync(tmp, JSON.stringify(this.store, null, 2), { mode: 0o600 });
    renameSync(tmp, this.file);
  }
}
