import { randomUUID } from "node:crypto";
import { existsSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { startupWarnings } from "./config.js";
import { dot, type Embedder } from "./embeddings.js";
import type { Memory } from "./memory.js";

/**
 * Echolalia: verify-before-store memory (patent Claim 31).
 *
 * A candidate memory is NOT written to the durable store on first mention.
 * It enters this pending store with its source (where it came from) and an
 * excerpt of the source text. A verification pass then decides:
 *
 *   PROMOTE     -> written to durable Memory (recallable as fact)
 *   QUARANTINE  -> held, visible on inspection, never recalled as fact
 *   DROP        -> owner (or a later decision) discards it
 *   stay PENDING -> not a fact; surfaces only when explicitly asked, and
 *                   then labelled unverified
 *
 * Verification signals, in order of strength:
 *   1. Source check — deterministic consistency of the claim against its
 *      recorded source text: every number and named entity in the claim
 *      must appear in the source, and a shared-numbers claim with flipped
 *      negation contradicts it. (The deterministic core of the phone's
 *      features/nli_gate.py, reimplemented here; no NLI model needed.)
 *   2. Repetition ("echo") — the same claim arrives again, from an
 *      independent source or a later turn, and matches: whole-token
 *      containment after normalization, or embedding cosine >= ECHO_COSINE
 *      when an embedder is configured — either way only with the same
 *      numbers and named entities (sameAnchors).
 *   3. Owner confirmation — the owner promotes it (tool or CLI); that
 *      promotes immediately, and the override is recorded when it
 *      overrules a failed source check.
 *
 * Promotion rule: source check PASS + (echoes >= 1 OR owner-confirmed).
 * Source check FAIL -> quarantine, with the reason kept on the entry.
 * Source check PASS with no echo yet -> stays pending.
 * No source text to check against -> "unverifiable": stays pending until
 * an owner confirms it (an echo alone cannot promote what was never
 * checked against a source).
 *
 * Storage follows Memory's conventions: one JSON file, written via a temp
 * file + rename at mode 0600, a corrupt file moved aside with a startup
 * warning rather than crashing.
 */

export type PendingStatus = "pending" | "promoted" | "quarantined" | "dropped";
export type SourceCheck = "pass" | "fail" | "unverifiable";

export interface PendingEntry {
  id: string;
  claim: string;
  /** Where the claim came from: a message, a tool name, a URL, "owner", … */
  source: string;
  /** Excerpt of the source text the claim is checked against (may be ""). */
  sourceText: string;
  arrivedAt: string;
  /** How many later matching arrivals ("echoes") this claim has had. */
  echoes: number;
  status: PendingStatus;
  /** Why it is in its current state, newest decision last. */
  reasons: string[];
  sourceCheck: SourceCheck;
  /** Durable fact id once promoted. */
  factId?: string;
  vec?: number[];
  emb?: string;
}

interface PendingFile {
  entries: PendingEntry[];
}

export interface SourceCheckResult {
  result: SourceCheck;
  reason: string;
}

/** Cosine at or above which two claims count as the same claim (echo). */
export const ECHO_COSINE = 0.85;
const SOURCE_EXCERPT = 2000;

const NEGATIONS = new Set("not no never n't none without isn't aren't wasn't weren't don't doesn't didn't cannot can't won't".split(" "));
const NUM_RE = /\d+(?:\.\d+)?/g;

function numbers(text: string): Set<string> {
  return new Set(text.match(NUM_RE) ?? []);
}

function negated(text: string): boolean {
  return text.toLowerCase().split(/[^a-z']+/).some((w) => NEGATIONS.has(w));
}

/** Named entities: capitalised words that are not just a sentence start. */
export function entities(text: string): Set<string> {
  const out = new Set<string>();
  const tokens = text.split(/\s+/);
  tokens.forEach((tok, i) => {
    const clean = tok.replace(/^[^A-Za-z]+|[^A-Za-z]+$/g, "");
    if (!/^[A-Z][a-z]{2,}$/.test(clean) && !/^[A-Z]{2,}$/.test(clean)) return;
    // A capitalised first word may only be sentence case; keep it only when
    // the same word appears capitalised mid-sentence elsewhere, or is an acronym.
    if (i === 0 && !/^[A-Z]{2,}$/.test(clean)) {
      const later = tokens.slice(1).some((t) => t.replace(/^[^A-Za-z]+|[^A-Za-z]+$/g, "") === clean);
      if (!later) return;
    }
    out.add(clean.toLowerCase());
  });
  return out;
}

/**
 * The deterministic source check. PASS only when every number and named
 * entity the claim asserts actually appears in the source text and no
 * negation flip contradicts it; FAIL says exactly what did not check out.
 */
export function checkSource(claim: string, sourceText: string): SourceCheckResult {
  const src = sourceText.trim();
  if (!src) return { result: "unverifiable", reason: "no source text recorded; nothing to check the claim against" };
  const srcLower = src.toLowerCase();
  const claimNums = numbers(claim);
  const srcNums = numbers(src);
  const missingNums = [...claimNums].filter((n) => !srcNums.has(n));
  if (missingNums.length) {
    return { result: "fail", reason: `number mismatch with source: claim number(s) ${missingNums.join(", ")} do not appear in the source text` };
  }
  if (claimNums.size && negated(claim) !== negated(src)) {
    // Same numbers, opposite polarity — the nli_gate contradiction pattern.
    // (Polarity of the whole source is a blunt instrument, so this only
    // fires when the source shares the claim's numbers exactly.)
    if ([...claimNums].every((n) => srcNums.has(n)) && srcNums.size === claimNums.size) {
      return { result: "fail", reason: "contradiction with source: same numbers, opposite negation" };
    }
  }
  const missingEntities = [...entities(claim)].filter((e) => !srcLower.includes(e));
  if (missingEntities.length) {
    return { result: "fail", reason: `entity mismatch with source: ${missingEntities.join(", ")} do(es) not appear in the source text` };
  }
  if (claimNums.size) return { result: "pass", reason: "numbers and entities corroborated by source" };
  if (entities(claim).size) return { result: "pass", reason: "entities corroborated by source" };
  return { result: "pass", reason: "no contradiction found in source (weak support)" };
}

/** Claim identity for echo matching: case/punctuation-insensitive. */
export function normalizeClaim(text: string): string {
  return text.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, " ").trim().replace(/\s+/g, " ");
}

/**
 * Same anchors: two claims assert the same set of numbers, and every named
 * entity either one asserts is a whole token of the other. (Entities are
 * compared against tokens, not entities(), because a name opening one
 * sentence is not an entity there but is one mid-sentence in the other.)
 * Without this "owes me 50" would echo "owes me 500", "Tom" would echo
 * "Tom Baker", and an embedding match would ignore a changed number.
 */
export function sameAnchors(a: string, b: string): boolean {
  const na = numbers(a);
  const nb = numbers(b);
  if (na.size !== nb.size || [...na].some((n) => !nb.has(n))) return false;
  const ta = new Set(normalizeClaim(a).split(" "));
  const tb = new Set(normalizeClaim(b).split(" "));
  return [...entities(a)].every((e) => tb.has(e)) && [...entities(b)].every((e) => ta.has(e));
}

/**
 * Deterministic echo match: equal, or one contains the other as a run of
 * whole tokens, after normalization — and the same anchors (sameAnchors).
 * Raw substrings are not enough: "pay tom" sits inside "pay tomas".
 */
export function claimsMatch(a: string, b: string): boolean {
  const na = normalizeClaim(a);
  const nb = normalizeClaim(b);
  if (!na || !nb) return false;
  const contained = na === nb || ` ${na} `.includes(` ${nb} `) || ` ${nb} `.includes(` ${na} `);
  return contained && sameAnchors(a, b);
}

export interface SubmitOutcome {
  entry: PendingEntry;
  /** True when this arrival was an echo of an existing pending entry. */
  echo: boolean;
  /** Human line describing what happened, safe to show the owner/model. */
  message: string;
}

export class EcholaliaStore {
  private store: PendingFile;

  constructor(
    private readonly file: string | null,
    private readonly memory: Memory,
    /** Notified on every promotion/quarantine/drop (Runtime wires the audit log). */
    private readonly onDecision?: (entry: PendingEntry, decision: string) => void,
  ) {
    this.store = { entries: [] };
    if (!file || !existsSync(file)) return;
    try {
      const raw = JSON.parse(readFileSync(file, "utf8")) as Partial<PendingFile>;
      this.store = { entries: Array.isArray(raw.entries) ? raw.entries : [] };
    } catch (err) {
      const aside = `${file}.broken-${Date.now()}`;
      renameSync(file, aside);
      startupWarnings.push(`memory-pending.json could not be read (${err instanceof Error ? err.message : String(err)}). It was moved to ${aside}; the pending store starts empty.`);
    }
  }

  /**
   * A candidate claim arrives. A matching pending entry makes this an echo;
   * otherwise a new pending entry is created. Verification runs on every
   * arrival, so an echo can promote and a contradicting source can
   * quarantine at any point.
   */
  async submit(
    claim: string,
    opts: { source?: string; sourceText?: string } = {},
    embedder: Pick<Embedder, "id" | "embedOne"> | null = null,
  ): Promise<SubmitOutcome> {
    const text = claim.trim();
    const source = (opts.source ?? "conversation").trim() || "conversation";
    const sourceText = (opts.sourceText ?? "").slice(0, SOURCE_EXCERPT);
    let entry = await this.findEcho(text, embedder);
    let echo = false;
    if (entry) {
      echo = true;
      entry.echoes += 1;
      entry.reasons.push(`echo #${entry.echoes} from ${source}${source === entry.source ? " (same source, later turn)" : " (independent source)"}`);
      // A later arrival may bring the source text the first one lacked.
      if (sourceText && !entry.sourceText) entry.sourceText = sourceText;
      if (sourceText && source !== entry.source) entry.reasons.push(`echo source text recorded from ${source}`);
    } else {
      entry = {
        id: randomUUID().slice(0, 8),
        claim: text,
        source,
        sourceText,
        arrivedAt: new Date().toISOString(),
        echoes: 0,
        status: "pending",
        reasons: [`arrived from ${source}`],
        sourceCheck: "unverifiable",
      };
      this.store.entries.push(entry);
      if (embedder) {
        try {
          entry.vec = await embedder.embedOne(text);
          entry.emb = embedder.id;
        } catch { /* deterministic matching still works without vectors */ }
      }
    }
    const before = entry.status;
    this.verify(entry);
    this.save();
    if (entry.status !== before) this.onDecision?.(entry, entry.status);
    const message =
      entry.status === "promoted"
        ? `Verified and remembered (${entry.id} -> fact ${entry.factId}): ${entry.claim}`
        : entry.status === "quarantined"
          ? `Quarantined, NOT remembered as fact (${entry.id}): ${entry.claim} — ${entry.reasons[entry.reasons.length - 1]}`
          : `Not stored yet — held as UNVERIFIED pending (${entry.id}): ${entry.claim} — ${entry.reasons[entry.reasons.length - 1]}`;
    return { entry, echo, message };
  }

  /** Run the verification pass over one entry and apply the promotion rule. */
  verify(entry: PendingEntry, opts: { ownerConfirmed?: boolean } = {}): PendingEntry {
    if (entry.status === "promoted" || entry.status === "dropped") return entry;
    if (opts.ownerConfirmed) return this.promote(entry, "owner confirmed");
    const check = checkSource(entry.claim, entry.sourceText);
    entry.sourceCheck = check.result;
    if (check.result === "fail") {
      entry.status = "quarantined";
      entry.reasons.push(`quarantined: ${check.reason}`);
      return entry;
    }
    if (check.result === "pass" && entry.echoes >= 1) {
      return this.promote(entry, `source check pass (${check.reason}) + ${entry.echoes} echo(es)`);
    }
    entry.status = "pending";
    entry.reasons.push(
      check.result === "pass"
        ? `source check pass (${check.reason}); waiting for an echo or owner confirmation`
        : `${check.reason}; waiting for source text or owner confirmation`,
    );
    return entry;
  }

  /** Owner's hands: promote a pending/quarantined entry now, recording why. */
  promoteByOwner(id: string): PendingEntry | null {
    const entry = this.byId(id);
    if (!entry || entry.status === "promoted" || entry.status === "dropped") return entry ?? null;
    const before = entry.status;
    this.promote(entry, entry.sourceCheck === "fail" ? "owner confirmed (overrides failed source check)" : "owner confirmed");
    this.save();
    if (entry.status !== before) this.onDecision?.(entry, "promoted");
    return entry;
  }

  /** Owner's hands: drop a pending/quarantined entry for good. */
  drop(id: string): PendingEntry | null {
    const entry = this.byId(id);
    if (!entry) return null;
    if (entry.status !== "dropped") {
      entry.status = "dropped";
      entry.reasons.push("dropped by owner");
      this.save();
      this.onDecision?.(entry, "dropped");
    }
    return entry;
  }

  /** Re-run verification on every held entry (e.g. after sources arrive). */
  verifyAll(): PendingEntry[] {
    const changed: PendingEntry[] = [];
    for (const e of this.store.entries) {
      if (e.status !== "pending" && e.status !== "quarantined") continue;
      if (e.status === "quarantined" && e.sourceCheck === "fail") continue; // a failed check needs new source text or the owner
      const before = e.status;
      this.verify(e);
      if (e.status !== before) { changed.push(e); this.onDecision?.(e, e.status); }
    }
    this.save();
    return changed;
  }

  byId(id: string): PendingEntry | undefined {
    return this.store.entries.find((e) => e.id === id.trim());
  }

  list(status?: PendingStatus): PendingEntry[] {
    return this.store.entries.filter((e) => !status || e.status === status);
  }

  /**
   * Explicit inspection of held claims. These are NOT facts: callers must
   * keep the status label attached (the tools and CLI do).
   */
  recallUnverified(query: string): PendingEntry[] {
    const q = normalizeClaim(query);
    return this.store.entries.filter(
      (e) => (e.status === "pending" || e.status === "quarantined") && (!q || normalizeClaim(e.claim).includes(q) || q.includes(normalizeClaim(e.claim))),
    );
  }

  private promote(entry: PendingEntry, why: string): PendingEntry {
    const fact = this.memory.remember(
      entry.claim,
      { provenance: `echolalia: ${why}; source: ${entry.source}; echoes: ${entry.echoes}; pending id ${entry.id}` },
    );
    entry.factId = fact.id;
    entry.status = "promoted";
    entry.reasons.push(`promoted: ${why}`);
    return entry;
  }

  private async findEcho(claim: string, embedder: Pick<Embedder, "id" | "embedOne"> | null): Promise<PendingEntry | undefined> {
    const held = this.store.entries.filter((e) => e.status === "pending");
    const direct = held.find((e) => claimsMatch(e.claim, claim));
    if (direct || !embedder) return direct;
    try {
      const v = await embedder.embedOne(claim);
      let best: PendingEntry | undefined;
      let bestScore = ECHO_COSINE;
      for (const e of held) {
        if (!e.vec || e.emb !== embedder.id || !sameAnchors(e.claim, claim)) continue;
        const s = dot(v, e.vec);
        if (s >= bestScore) { best = e; bestScore = s; }
      }
      if (best) best.reasons.push(`echo matched by embedding similarity (${bestScore.toFixed(2)} >= ${ECHO_COSINE})`);
      return best;
    } catch {
      return undefined;
    }
  }

  private save(): void {
    if (!this.file) return;
    const tmp = `${this.file}.tmp`;
    writeFileSync(tmp, JSON.stringify(this.store, null, 2), { mode: 0o600 });
    renameSync(tmp, this.file);
  }
}

/** One inspection line for a held entry, status label always attached. */
export function formatEntry(e: PendingEntry): string {
  const label = e.status === "pending" ? "UNVERIFIED — pending" : e.status.toUpperCase();
  return `(${e.id}) [${label}] ${e.claim} — source: ${e.source}, echoes: ${e.echoes}, arrived ${e.arrivedAt}, last: ${e.reasons[e.reasons.length - 1] ?? ""}`;
}
