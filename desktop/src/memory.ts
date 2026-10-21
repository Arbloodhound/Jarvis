import { randomUUID } from "node:crypto";
import { existsSync, readFileSync, renameSync, statSync, writeFileSync } from "node:fs";
import { startupWarnings } from "./config.js";
import { dot, type Embedder } from "./embeddings.js";

/**
 * What Jarvis remembers about its owner: claims, not blobs — the semantics of
 * the phone harness's memory plane (harness/memory.py), ported to the desktop
 * fact store. Each fact carries a `kind`, a `salience` tier, the time it was
 * last reinforced, and supersede links, on top of its text.
 *
 * - Kind: a closed set (CLAIM_KINDS), validated on write.
 * - Salience: low | medium | high. Set explicitly or defaulted by kind,
 *   promoted one tier when a fact is reinforced.
 * - Decay: at QUERY time a fact's ranking weight is multiplied by a temporal
 *   factor, exp(-ln2 * ageDays / 30) from its last reinforcement (30-day
 *   half-life). Curated (`evergreen`) facts never decay. Decay only sinks a
 *   fact in ranking — it never deletes; forgetting stays an explicit act.
 * - Supersede: an explicit write-time link, as on the phone. Remembering a
 *   fact with `supersedes: <id>` marks the old fact supersededBy the new one
 *   (with invalidatedAt) and keeps it for history. Default recall excludes
 *   superseded facts; tooling can ask for them explicitly.
 *
 * Retrieval scoring is the phone's hybrid shape adapted to the desktop: the
 * content score (semantic dot product when embedded, word overlap otherwise)
 * multiplied by a salience weight and the temporal factor. (Retrieval shape
 * adapted from OpenClaw memory-core, MIT, Copyright (c) 2026 OpenClaw
 * Foundation; the desktop has no confidence field, so the source's prior
 * term has no counterpart here.)
 *
 * Forgotten facts go to a trash list first, as on the phone, so "forget" can
 * be undone until the trash is emptied.
 *
 * Embeddings: when an embedding model is configured, a fact's vector is
 * stored beside it (`vec`, tagged with the embedder `emb` that produced it)
 * and recall ranks by meaning instead of words. The two never mix: a vector
 * made by a different model is ignored, and when no vector is available for
 * the query the search falls back to word overlap. So turning embeddings on
 * or off, or switching models, degrades gracefully and never loses a fact.
 */

/** The phone design's closed kind set (harness/memory.py KINDS). */
export const CLAIM_KINDS = [
  "preference", "fact", "finding", "request", "completion", "correction", "observation",
] as const;
export type ClaimKind = (typeof CLAIM_KINDS)[number];

export type Salience = "low" | "medium" | "high";

/** Numeric weight per salience tier, used in recall ranking. */
export const SALIENCE_WEIGHT: Record<Salience, number> = { low: 0.5, medium: 1, high: 1.5 };

/** Salience when a remember call doesn't say: flat "medium" in the phone
 *  design; the desktop defaults two kinds away from it — a correction should
 *  outrank what it corrects, an observation is background by nature. */
const KIND_DEFAULT_SALIENCE: Record<ClaimKind, Salience> = {
  preference: "medium",
  fact: "medium",
  finding: "medium",
  request: "medium",
  completion: "low",
  correction: "high",
  observation: "low",
};

/** Temporal-decay half-life in days (the phone design's HALF_LIFE_DAYS). */
export const HALF_LIFE_DAYS = 30;

export interface Fact {
  id: string;
  text: string;
  at: string;
  kind: ClaimKind;
  salience: Salience;
  /** Last reinforcement (write, re-remember, recall-and-use). Decay runs from here. */
  reinforcedAt: string;
  /** Curated facts never decay. */
  evergreen?: boolean;
  /** Id of the fact this one replaces (explicit write-time link). */
  supersedes?: string;
  /** Id of the fact that replaced this one. Set ⇒ excluded from default recall. */
  supersededBy?: string;
  /** When the fact was superseded. */
  invalidatedAt?: string;
  /** How this fact earned its place (e.g. echolalia verification), when recorded. */
  provenance?: string;
  /** Unit vector, present only once embedded. */
  vec?: number[];
  /** The embedder id that produced `vec`; a mismatch means re-embed. */
  emb?: string;
}

export interface RememberOptions {
  kind?: ClaimKind;
  salience?: Salience;
  evergreen?: boolean;
  /** Id of an existing fact this one replaces. */
  supersedes?: string;
  /** How this fact earned its place (e.g. echolalia verification). */
  provenance?: string;
}

interface Store {
  facts: Fact[];
  trash: Fact[];
}

const STOP = new Set("a an the i me my you your is are was were be to of in on at for and or it that this what do does did about with".split(" "));

function words(s: string): string[] {
  return s.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter((w) => w.length > 1 && !STOP.has(w));
}

const isActive = (f: Fact): boolean => !f.supersededBy;

/**
 * Query-time temporal decay: 1.0 for evergreen or just-reinforced facts,
 * halving every HALF_LIFE_DAYS since the last reinforcement.
 */
export function temporalFactor(fact: Fact, nowMs: number): number {
  if (fact.evergreen) return 1;
  const ageDays = (nowMs - Date.parse(fact.reinforcedAt)) / 86_400_000;
  if (!(ageDays > 0)) return 1;
  return Math.exp(-Math.LN2 * (ageDays / HALF_LIFE_DAYS));
}

export class Memory {
  private store: Store;

  /**
   * `now` is injectable so decay/reinforcement are testable without sleeping;
   * production callers get the wall clock.
   */
  constructor(
    private readonly file: string | null,
    private readonly now: () => Date = () => new Date(),
  ) {
    this.store = { facts: [], trash: [] };
    if (!file || !existsSync(file)) return;
    try {
      const raw = JSON.parse(readFileSync(file, "utf8")) as Partial<Store>;
      // Migration: stores written before the claims machinery lack the claim
      // fields. They load losslessly with defaults and are re-saved in the
      // new format below. reinforcedAt falls back to the fact's own `at`,
      // then the file's mtime, then now — in that order.
      let mtimeIso: string | null = null;
      try { mtimeIso = statSync(file).mtime.toISOString(); } catch { /* file vanished mid-load */ }
      let migrated = false;
      const norm = (f: Fact): Fact => {
        const before = JSON.stringify(f);
        if (!CLAIM_KINDS.includes(f.kind)) f.kind = "fact";
        if (!(f.salience in SALIENCE_WEIGHT)) f.salience = "medium";
        if (!f.at) f.at = mtimeIso ?? this.now().toISOString();
        if (!f.reinforcedAt) f.reinforcedAt = f.at;
        if (JSON.stringify(f) !== before) migrated = true;
        return f;
      };
      this.store = {
        facts: (Array.isArray(raw.facts) ? raw.facts : []).map(norm),
        trash: (Array.isArray(raw.trash) ? raw.trash : []).map(norm),
      };
      if (migrated) this.save();
    } catch (err) {
      const aside = `${file}.broken-${Date.now()}`;
      renameSync(file, aside);
      startupWarnings.push(`memory.json could not be read (${err instanceof Error ? err.message : String(err)}). It was moved to ${aside}; memory starts empty.`);
    }
  }

  private nowIso(): string {
    return this.now().toISOString();
  }

  /** Ranking weight of a fact right now: salience × temporal freshness. */
  private weight(f: Fact): number {
    return SALIENCE_WEIGHT[f.salience] * temporalFactor(f, this.now().getTime());
  }

  private reinforceFact(f: Fact): void {
    f.reinforcedAt = this.nowIso();
    if (f.salience === "low") f.salience = "medium";
    else if (f.salience === "medium") f.salience = "high";
  }

  remember(text: string, opts: RememberOptions = {}): Fact {
    const t = text.trim();
    const same = this.store.facts.find((f) => f.text.toLowerCase() === t.toLowerCase());
    if (same) {
      // Re-remembering is reinforcement, not a duplicate.
      if (!same.supersededBy) this.reinforceFact(same);
      if (opts.supersedes && opts.supersedes !== same.id && !same.supersedes) {
        same.supersedes = opts.supersedes;
        this.markSuperseded(opts.supersedes, same.id);
      }
      this.save();
      return same;
    }
    const kind = opts.kind && CLAIM_KINDS.includes(opts.kind) ? opts.kind : "fact";
    const at = this.nowIso();
    const f: Fact = {
      id: randomUUID().slice(0, 8),
      text: t,
      at,
      kind,
      salience: opts.salience && opts.salience in SALIENCE_WEIGHT ? opts.salience : KIND_DEFAULT_SALIENCE[kind],
      reinforcedAt: at,
      ...(opts.evergreen ? { evergreen: true } : {}),
      ...(opts.supersedes ? { supersedes: opts.supersedes } : {}),
      ...(opts.provenance ? { provenance: opts.provenance } : {}),
    };
    this.store.facts.push(f);
    if (opts.supersedes) this.markSuperseded(opts.supersedes, f.id);
    this.save();
    return f;
  }

  /** The old end of a supersede link: replaced by `byId`, history preserved. */
  private markSuperseded(oldId: string, byId: string): void {
    const old = this.store.facts.find((x) => x.id === oldId && x.id !== byId);
    if (old && !old.supersededBy) {
      old.supersededBy = byId;
      old.invalidatedAt = this.nowIso();
    }
  }

  /**
   * Reinforce a fact (recalled-and-used, re-asserted): resets its decay and
   * promotes its salience one tier. Superseded facts can't be reinforced —
   * reinforce the fact that replaced them instead.
   */
  reinforce(id: string): boolean {
    const f = this.store.facts.find((x) => x.id === id);
    if (!f || f.supersededBy) return false;
    this.reinforceFact(f);
    this.save();
    return true;
  }

  /** A fact by id, whatever its state. */
  byId(id: string): Fact | undefined {
    return this.store.facts.find((f) => f.id === id);
  }

  /** The fact plus everything it superseded, newest first (source: history()). */
  chain(id: string): Fact[] {
    const out: Fact[] = [];
    const seen = new Set<string>();
    let cur = this.byId(id);
    while (cur && !seen.has(cur.id)) {
      seen.add(cur.id);
      out.push(cur);
      cur = cur.supersedes ? this.byId(cur.supersedes) : undefined;
    }
    return out;
  }

  /** Word-overlap recall. Always available, no server needed. */
  recall(query: string, limit = 8, opts: { includeSuperseded?: boolean } = {}): Fact[] {
    const pool = opts.includeSuperseded ? this.store.facts : this.store.facts.filter(isActive);
    const q = new Set(words(query));
    if (q.size === 0) return pool.slice(-limit).reverse();
    return pool
      .map((f) => ({ f, overlap: words(f.text).filter((w) => q.has(w)).length }))
      .filter((x) => x.overlap > 0)
      .map((x) => ({ ...x, score: x.overlap * this.weight(x.f) }))
      .sort((a, b) => b.score - a.score || b.f.at.localeCompare(a.f.at) || a.f.id.localeCompare(b.f.id))
      .slice(0, limit)
      .map((x) => x.f);
  }

  /**
   * Semantic recall when an embedder is given and the facts are embedded with
   * it; otherwise word overlap. Ranking is semantic score × salience ×
   * freshness either way. Best-effort: if the embedding server is down, it
   * falls back rather than failing the request.
   */
  async search(query: string, embedder: Embedder | null, limit = 8, opts: { includeSuperseded?: boolean } = {}): Promise<Fact[]> {
    if (!embedder || this.store.facts.length === 0) return this.recall(query, limit, opts);
    try {
      await this.ensureEmbedded(embedder);
      const usable = this.store.facts.filter((f) => f.vec && f.emb === embedder.id && (opts.includeSuperseded || isActive(f)));
      if (usable.length === 0) return this.recall(query, limit, opts);
      const qv = await embedder.embedOne(query);
      return usable
        .map((f) => ({ f, score: dot(qv, f.vec!) * this.weight(f) }))
        .sort((a, b) => b.score - a.score || a.f.id.localeCompare(b.f.id))
        .slice(0, limit)
        .map((x) => x.f);
    } catch {
      return this.recall(query, limit, opts);
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
    const hits = byId.length ? byId : this.recall(query, 50, { includeSuperseded: true }).filter((f) => {
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
