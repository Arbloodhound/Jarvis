import { randomUUID } from "node:crypto";
import { existsSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { startupWarnings } from "./config.js";
import { dot, type Embedder } from "./embeddings.js";

/**
 * What Jarvis remembers about its owner: short facts in a JSON file. Forgotten
 * facts go to a trash list first, as on the phone, so "forget" can be undone
 * until the trash is emptied.
 *
 * Recall is word-overlap by default. When an embedding model is configured, a
 * fact's vector is stored beside it (`vec`, tagged with the embedder `emb` that
 * produced it) and recall ranks by meaning instead. The two never mix: a vector
 * made by a different model is ignored, and when no vector is available for the
 * query the search falls back to word overlap. So turning embeddings on or off,
 * or switching models, degrades gracefully and never loses a fact.
 */

export interface Fact {
  id: string;
  text: string;
  at: string;
  /** How this fact earned its place (e.g. echolalia verification), when recorded. */
  provenance?: string;
  /** Unit vector, present only once embedded. */
  vec?: number[];
  /** The embedder id that produced `vec`; a mismatch means re-embed. */
  emb?: string;
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
    this.store = { facts: [], trash: [] };
    if (!file || !existsSync(file)) return;
    try {
      const raw = JSON.parse(readFileSync(file, "utf8")) as Partial<Store>;
      this.store = { facts: Array.isArray(raw.facts) ? raw.facts : [], trash: Array.isArray(raw.trash) ? raw.trash : [] };
    } catch (err) {
      const aside = `${file}.broken-${Date.now()}`;
      renameSync(file, aside);
      startupWarnings.push(`memory.json could not be read (${err instanceof Error ? err.message : String(err)}). It was moved to ${aside}; memory starts empty.`);
    }
  }

  remember(text: string, provenance?: string): Fact {
    const t = text.trim();
    const same = this.store.facts.find((f) => f.text.toLowerCase() === t.toLowerCase());
    if (same) return same;
    const f: Fact = { id: randomUUID().slice(0, 8), text: t, at: new Date().toISOString(), ...(provenance ? { provenance } : {}) };
    this.store.facts.push(f);
    this.save();
    return f;
  }

  /** Word-overlap recall. Always available, no server needed. */
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

  /**
   * Semantic recall when an embedder is given and the facts are embedded with
   * it; otherwise word overlap. Best-effort: if the embedding server is down,
   * it falls back rather than failing the request.
   */
  async search(query: string, embedder: Embedder | null, limit = 8): Promise<Fact[]> {
    if (!embedder || this.store.facts.length === 0) return this.recall(query, limit);
    try {
      await this.ensureEmbedded(embedder);
      const usable = this.store.facts.filter((f) => f.vec && f.emb === embedder.id);
      if (usable.length === 0) return this.recall(query, limit);
      const qv = await embedder.embedOne(query);
      return usable
        .map((f) => ({ f, score: dot(qv, f.vec!) }))
        .sort((a, b) => b.score - a.score)
        .slice(0, limit)
        .map((x) => x.f);
    } catch {
      return this.recall(query, limit);
    }
  }

  /** Embed any facts that aren't embedded with this embedder yet (batched). */
  async ensureEmbedded(embedder: Embedder, signal?: AbortSignal): Promise<number> {
    const todo = this.store.facts.filter((f) => f.emb !== embedder.id || !f.vec);
    if (todo.length === 0) return 0;
    let done = 0;
    for (let i = 0; i < todo.length; i += 32) {
      const batch = todo.slice(i, i + 32);
      const vecs = await embedder.embed(batch.map((f) => f.text), signal);
      batch.forEach((f, j) => { f.vec = vecs[j]; f.emb = embedder.id; });
      done += batch.length;
    }
    this.save();
    return done;
  }

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
    this.store.trash.push(...hits.map((f) => ({ ...f, vec: undefined })));
    // The trash keeps the last 500 forgotten facts; older ones are gone for good.
    if (this.store.trash.length > 500) this.store.trash = this.store.trash.slice(-500);
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
