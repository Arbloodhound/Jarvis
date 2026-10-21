import { createHash, randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, writeFileSync } from "node:fs";
import path from "node:path";

/**
 * Investigate — case files, 4-leg orchestration, draft-only outreach.
 *
 * Desktop port of the phone's research/Investigation.kt + Contacts.kt and the
 * v5 reference in features/investigate.py, reshaped for a computer:
 *
 * - A case lives under the app home: cases/<slug>/case.json, with one text
 *   file per evidence entry under cases/<slug>/evidence/ and the compiled
 *   report at cases/<slug>/report.md. Everything is a local file; the only
 *   network in this cluster is the auto-gather leg runner in
 *   tools/investigate.ts, which goes through the app's existing safe
 *   search/fetch machinery.
 * - Standing scope (owner, from the rebuild): general-purpose investigative
 *   research — any target kind (person, company, domain, other); general
 *   contacts including handles and PGP keys; outreach stays DRAFT-ONLY with
 *   per-recipient approval. There is deliberately no send path anywhere in
 *   this module or its tools: a draft's `sent` is the literal false, its
 *   approval flag is bookkeeping for the owner, and nothing here can change
 *   either into a transmission.
 * - Evidence never enters a case without a source URL (the phone's
 *   invariant). Auto-gathered entries arrive graded "unverified"; a human or
 *   the model promotes a grade later, with the source still attached.
 * - People-search / data-broker and breach-lookup sites (Contacts.kt
 *   BROKERS) are never read for findings or contacts.
 */

export const LEGS = ["identity", "records", "social", "documents"] as const;
export type Leg = (typeof LEGS)[number];

export const LEG_LABELS: Record<Leg, string> = {
  identity: "Web identity & footprint",
  records: "Official records & registries",
  social: "Social & public mentions",
  documents: "Documents & publications",
};

export type TargetKind = "person" | "company" | "domain" | "other";
export type CaseStatus = "open" | "closed";
export type EvidenceGrade = "confirmed" | "likely" | "unverified";
export type LegStatus = "ok" | "empty" | "skipped" | "error" | "manual";
export type ContactKind = "email" | "handle" | "pgp" | "phone" | "name_role" | "other";

export interface EvidenceEntry {
  id: string;
  leg: Leg;
  claim: string;
  /** The source this claim came from. Required — sourceless claims are refused. */
  sourceUrl: string;
  /** The fetched excerpt the claim rests on (may be a search snippet). */
  excerpt: string;
  grade: EvidenceGrade;
  fetchedAt: string;
}

export interface CaseContact {
  id: string;
  kind: ContactKind;
  value: string;
  /** Where the contact was found. Required — untraceable contacts are refused. */
  provenanceUrl: string;
  provenanceSnippet: string;
  fetchedAt: string;
}

export interface CaseDraft {
  id: string;
  /** The contact value this draft addresses (must exist in the case's contacts). */
  to: string;
  channel: string;
  subject: string;
  body: string;
  /** Per-recipient approval state. Approval is bookkeeping only — it sends nothing. */
  approved: boolean;
  status: "draft";
  /** Literal false: this module has no send path, by construction. */
  sent: false;
  createdAt: string;
}

export interface LegRun {
  leg: Leg;
  status: LegStatus;
  note: string;
  at: string;
}

export interface CaseFile {
  slug: string;
  target: string;
  targetKind: TargetKind;
  status: CaseStatus;
  createdAt: string;
  updatedAt: string;
  legs: LegRun[];
  evidence: EvidenceEntry[];
  contacts: CaseContact[];
  drafts: CaseDraft[];
}

// ---------- brokers (ported from Contacts.kt) ----------

/** People-search, data-broker and breach-lookup sites: never read for contacts or findings. */
export const BROKERS = new Set([
  "spokeo.com", "whitepages.com", "beenverified.com", "truepeoplesearch.com", "fastpeoplesearch.com",
  "intelius.com", "peoplefinders.com", "radaris.com", "mylife.com", "instantcheckmate.com", "truthfinder.com",
  "192.com", "thatsthem.com", "peekyou.com", "pipl.com", "zabasearch.com", "spydialer.com", "usphonebook.com",
  "nuwber.com", "clustrmaps.com", "familytreenow.com", "peoplelooker.com", "checkpeople.com", "ussearch.com",
  "haveibeenpwned.com", "dehashed.com", "leakcheck.io", "snusbase.com", "intelx.io", "leak-lookup.com",
]);

export function domainOf(url: string): string {
  try { return new URL(url).hostname.toLowerCase().replace(/^www\./, ""); } catch { return ""; }
}

export function isBroker(domain: string): boolean {
  const d = domain.toLowerCase().replace(/^www\./, "");
  for (const b of BROKERS) if (d === b || d.endsWith(`.${b}`)) return true;
  return false;
}

export function isBrokerUrl(url: string): boolean {
  return isBroker(domainOf(url));
}

// ---------- slugs ----------

/**
 * A target -> a folder name assertSlug accepts. Accents fold to ASCII
 * ("Müller GmbH" -> "muller-gmbh"); a target whose letters have no ASCII
 * form (CJK, Cyrillic …) gets a stable hash slug ("case-1a2b3c4d") rather
 * than a name the store would then refuse. No letters or digits at all: "case".
 */
export function slugify(target: string): string {
  const ascii = target.normalize("NFKD").replace(/\p{M}+/gu, "").toLowerCase();
  const s = ascii.replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 60).replace(/-+$/g, "");
  if (s) return s;
  const t = target.trim();
  if (!/[\p{L}\p{N}]/u.test(t)) return "case";
  return `case-${createHash("sha256").update(t).digest("hex").slice(0, 8)}`;
}

const SLUG_OK = /^[a-z0-9][a-z0-9-]*$/;

export function assertSlug(slug: string): string {
  const s = slug.trim().toLowerCase();
  if (!SLUG_OK.test(s)) throw new Error(`No case named "${slug}".`);
  return s;
}

// ---------- contact extraction (ported from Contacts.kt) ----------

const EMAIL_RE = /[\w.+-]+@[\w-]+(?:\.[\w-]+)*\.([A-Za-z]{2,24})\b/g;
const NOT_MAIL_TLD = new Set(["png", "jpg", "jpeg", "gif", "svg", "webp", "css", "js", "ico"]);
const HANDLE_RE = /(?<![\w@.])@([A-Za-z0-9_]{2,32})\b/g;
const PGP_RE = /-----BEGIN PGP (?:PUBLIC KEY BLOCK|SIGNED MESSAGE|MESSAGE)-----/;
// No line breaks inside a candidate: HTML-to-text puts table cells on separate
// lines, and a "phone" spanning lines is a table fragment, not a number.
const PHONE_RE = /(?<![\w+])(?:\+\d{1,3}[ .-]?)?\(?\d[\d .()-]{5,20}\d(?![\w])/g;
const DATE_RE = /\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}/;
const NAME_ROLE_RE = /([A-Z][a-z]+(?: [A-Z][a-z'.]+){1,2})\s*[,:–—-]\s*([A-Z][a-zA-Z &/.'-]{2,40})/g;

export interface ExtractedContact {
  kind: ContactKind;
  value: string;
  snippet: string;
}

/**
 * Contacts in page [text] fetched from [url]: emails, conservative phone
 * numbers, @handles, a PGP-block marker, and "Name — Role" pairs near a
 * contact. One per (kind, value). Kinds come from the page, not from lists.
 */
/** Per-page extraction caps, so one huge page can't flood a case with candidates. */
const EXTRACT_CAPS: Record<ContactKind, number> = { email: 20, phone: 10, handle: 20, pgp: 1, name_role: 10, other: 0 };

export function extractContacts(text: string, url: string): ExtractedContact[] {
  const out: ExtractedContact[] = [];
  const seen = new Set<string>();
  const counts = new Map<ContactKind, number>();
  const add = (kind: ContactKind, value: string, at: number) => {
    const v = value.trim();
    if (!v || !seen.add(`${kind}|${v.toLowerCase()}`)) return;
    if ((counts.get(kind) ?? 0) >= (EXTRACT_CAPS[kind] ?? 0)) return;
    counts.set(kind, (counts.get(kind) ?? 0) + 1);
    const snippet = text.slice(Math.max(0, at - 60), Math.min(text.length, at + 80)).trim().slice(0, 140);
    out.push({ kind, value: v, snippet });
  };
  const blocks: Array<[number, number]> = [];
  for (const m of text.matchAll(EMAIL_RE)) {
    if (m[0].toLowerCase().includes(".onion") || NOT_MAIL_TLD.has((m[1] ?? "").toLowerCase())) continue;
    add("email", m[0], m.index); blocks.push([m.index - 300, m.index + m[0].length + 300]);
  }
  for (const m of text.matchAll(PHONE_RE)) {
    const raw = m[0].trim();
    const digits = (raw.match(/\d/g) ?? []).length;
    if (digits < 7 || digits > 15 || DATE_RE.test(raw)) continue;
    // Desktop tightening over the phone heuristic: HTML-to-text leaves bare
    // digit runs that are page ids, timestamps or YYYYMMDD dates, not phones.
    const digitRun = raw.replace(/\D/g, "");
    if (!/[\s.()-]/.test(raw) && !raw.startsWith("+") && (digitRun.length > 10 || /^(19|20)\d{6}$/.test(digitRun))) continue;
    if (/^\d+\.\d+\s*[-–]\s*\d+\.\d+$/.test(raw)) continue; // a decimal range (prices, measurements), not a phone
    if (/\(\d{4}\)?$/.test(raw)) continue; // "(2025" — a year in brackets, not an area code
    add("phone", raw, m.index); blocks.push([m.index - 300, m.index + raw.length + 300]);
  }
  for (const m of text.matchAll(HANDLE_RE)) add("handle", `@${m[1]}`, m.index);
  if (PGP_RE.test(text)) add("pgp", "PGP block present", 0);
  for (const [b0, b1] of blocks) {
    const start = Math.max(0, b0); const end = Math.min(text.length, b1);
    if (start >= end) continue;
    for (const m of text.slice(start, end).matchAll(NAME_ROLE_RE)) {
      add("name_role", `${m[1]} — ${m[2]!.trim()}`, start + m.index);
    }
  }
  return out;
}

// ---------- the store ----------

const nowIso = () => new Date().toISOString();
const newId = () => randomUUID().slice(0, 8);

export class CaseStore {
  readonly casesDir: string;

  constructor(home: string) {
    this.casesDir = path.join(home, "cases");
  }

  caseDir(slug: string): string {
    return path.join(this.casesDir, assertSlug(slug));
  }

  create(target: string, targetKind: TargetKind = "other"): CaseFile {
    const t = target.trim();
    if (!t) throw new Error("A case needs a target.");
    const base = slugify(t);
    let slug = base;
    for (let n = 2; existsSync(path.join(this.casesDir, slug)); n++) slug = `${base}-${n}`;
    const at = nowIso();
    const c: CaseFile = {
      slug, target: t, targetKind, status: "open", createdAt: at, updatedAt: at,
      legs: [], evidence: [], contacts: [], drafts: [],
    };
    this.save(c);
    return c;
  }

  load(slug: string): CaseFile {
    const file = path.join(this.caseDir(slug), "case.json");
    if (!existsSync(file)) throw new Error(`No case named "${slug}".`);
    const raw = JSON.parse(readFileSync(file, "utf8")) as CaseFile;
    return {
      ...raw,
      legs: Array.isArray(raw.legs) ? raw.legs : [],
      evidence: Array.isArray(raw.evidence) ? raw.evidence : [],
      contacts: Array.isArray(raw.contacts) ? raw.contacts : [],
      drafts: Array.isArray(raw.drafts) ? raw.drafts : [],
    };
  }

  /** All cases, newest activity first; a broken case file is skipped, never fatal. */
  list(): CaseFile[] {
    if (!existsSync(this.casesDir)) return [];
    const out: CaseFile[] = [];
    for (const name of readdirSync(this.casesDir)) {
      try { out.push(this.load(name)); } catch { /* broken or not a case folder */ }
    }
    return out.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  }

  save(c: CaseFile): void {
    const dir = this.caseDir(c.slug);
    mkdirSync(path.join(dir, "evidence"), { recursive: true });
    c.updatedAt = nowIso();
    const file = path.join(dir, "case.json");
    const tmp = `${file}.tmp`;
    writeFileSync(tmp, JSON.stringify(c, null, 2) + "\n", { mode: 0o600 });
    renameSync(tmp, file);
    // One plain-text file per evidence entry, so the case folder is readable without the app.
    for (const e of c.evidence) {
      writeFileSync(path.join(dir, "evidence", `${e.id}.txt`),
        `${e.claim}\n\nLeg: ${e.leg} (${LEG_LABELS[e.leg]})\nGrade: ${e.grade}\nSource: ${e.sourceUrl}\nFetched: ${e.fetchedAt}\n\n${e.excerpt}\n`, { mode: 0o600 });
    }
  }

  setStatus(slug: string, status: CaseStatus): CaseFile {
    const c = this.load(slug);
    c.status = status;
    this.save(c);
    return c;
  }

  recordLeg(c: CaseFile, leg: Leg, status: LegStatus, note: string): void {
    c.legs = [...c.legs.filter((l) => l.leg !== leg), { leg, status, note, at: nowIso() }];
  }

  /**
   * Add an evidence entry. Refused without a source URL, and refused when the
   * source is a data-broker / breach-lookup site (phone scope rule).
   */
  addEvidence(slug: string, input: { leg: Leg; claim: string; sourceUrl: string; excerpt?: string; grade?: EvidenceGrade }): EvidenceEntry {
    const c = this.load(slug);
    const url = input.sourceUrl.trim();
    if (!url) throw new Error("Evidence refused: no source URL — a claim without a source is not evidence.");
    try { new URL(url); } catch { throw new Error(`Evidence refused: "${url}" is not a URL.`); }
    if (isBrokerUrl(url)) throw new Error("Evidence refused: people-search / data-broker sites are out of scope.");
    if (!input.claim.trim()) throw new Error("Evidence refused: empty claim.");
    const e: EvidenceEntry = {
      id: newId(), leg: input.leg, claim: input.claim.trim().slice(0, 2000),
      sourceUrl: url, excerpt: (input.excerpt ?? "").trim().slice(0, 1200),
      grade: input.grade ?? "unverified", fetchedAt: nowIso(),
    };
    c.evidence.push(e);
    this.save(c);
    return e;
  }

  /** Promote (or demote) an entry's grade; the source stays attached either way. */
  setGrade(slug: string, evidenceId: string, grade: EvidenceGrade): EvidenceEntry {
    const c = this.load(slug);
    const e = c.evidence.find((x) => x.id === evidenceId);
    if (!e) throw new Error(`No evidence entry ${evidenceId} in case ${c.slug}.`);
    e.grade = grade;
    this.save(c);
    return e;
  }

  /**
   * Add a contact. Refused without a provenance URL, refused from a broker
   * site; a duplicate (same kind and value) returns the existing entry.
   */
  addContact(slug: string, input: { kind: ContactKind; value: string; provenanceUrl: string; provenanceSnippet?: string }): { contact: CaseContact; added: boolean } {
    const c = this.load(slug);
    const url = input.provenanceUrl.trim();
    if (!url) throw new Error("Contact refused: no provenance — a contact Jarvis can't trace doesn't enter a case.");
    if (!input.value.trim()) throw new Error("Contact refused: empty value.");
    if (isBrokerUrl(url)) throw new Error("Contact refused: people-search / data-broker sites are out of scope.");
    const dup = c.contacts.find((x) => x.kind === input.kind && x.value.toLowerCase() === input.value.trim().toLowerCase());
    if (dup) return { contact: dup, added: false };
    const contact: CaseContact = {
      id: newId(), kind: input.kind, value: input.value.trim(), provenanceUrl: url,
      provenanceSnippet: (input.provenanceSnippet ?? "").trim().slice(0, 300), fetchedAt: nowIso(),
    };
    c.contacts.push(contact);
    this.save(c);
    return { contact, added: true };
  }

  /**
   * Write an outreach DRAFT into the case. Draft only, by construction: this
   * class has no send method, no network, no messaging — the draft is a file
   * entry whose `sent` is the literal false and whose per-recipient approval
   * flag defaults to unapproved. Placeholders {value}, {kind}, {query},
   * {source_url} are filled from the addressed contact (the phone's Outreach
   * contract); any other placeholder is refused so a draft never carries a
   * hole. The recipient must be a contact already in the case, with its
   * provenance — Jarvis never drafts to a recipient it can't trace.
   */
  addDraft(slug: string, input: { to: string; channel?: string; subject: string; body: string }): CaseDraft {
    const c = this.load(slug);
    const contact = c.contacts.find((x) => x.value.toLowerCase() === input.to.trim().toLowerCase());
    if (!contact) throw new Error(`Draft refused: "${input.to}" is not a contact in case ${c.slug}. Add the contact (with its source) first.`);
    if (!input.subject.trim() && !input.body.trim()) throw new Error("Draft refused: empty subject and body.");
    const fields: Record<string, string> = {
      value: contact.value, kind: contact.kind, query: c.target, source_url: contact.provenanceUrl,
    };
    const fill = (t: string) => t.replace(/\{([a-z_]+)\}/g, (_m, k: string) => {
      const v = fields[k];
      if (v === undefined) throw new Error(`Draft refused: unknown placeholder {${k}}.`);
      return v;
    });
    const draft: CaseDraft = {
      id: newId(), to: contact.value, channel: input.channel?.trim() || contact.kind,
      subject: fill(input.subject).slice(0, 500), body: fill(input.body).slice(0, 5000),
      approved: false, status: "draft", sent: false, createdAt: nowIso(),
    };
    c.drafts.push(draft);
    this.save(c);
    return draft;
  }

  /** Record the owner's per-recipient approval decision. Bookkeeping only: approving sends nothing. */
  setDraftApproval(slug: string, draftId: string, approved: boolean): CaseDraft {
    const c = this.load(slug);
    const d = c.drafts.find((x) => x.id === draftId);
    if (!d) throw new Error(`No draft ${draftId} in case ${c.slug}.`);
    d.approved = approved;
    this.save(c);
    return d;
  }

  /** Compile the case report and write it beside the case file. Returns the markdown. */
  compileReport(slug: string): string {
    const c = this.load(slug);
    const md = renderReport(c);
    writeFileSync(path.join(this.caseDir(c.slug), "report.md"), md, { mode: 0o600 });
    return md;
  }
}

// ---------- report ----------

const GRADE_ORDER: Record<EvidenceGrade, number> = { confirmed: 0, likely: 1, unverified: 2 };

/** The graded report: findings grouped by leg, every claim with its grade and source URL. */
export function renderReport(c: CaseFile): string {
  const lines: string[] = [];
  lines.push(`# Investigation report — ${c.target}`);
  lines.push("");
  lines.push(`- Case: ${c.slug} (${c.targetKind})`);
  lines.push(`- Status: ${c.status}`);
  lines.push(`- Created: ${c.createdAt}`);
  lines.push(`- Updated: ${c.updatedAt}`);
  lines.push(`- Evidence: ${c.evidence.length} entries — ${countGrade(c, "confirmed")} confirmed, ${countGrade(c, "likely")} likely, ${countGrade(c, "unverified")} unverified`);
  lines.push("");
  lines.push("## Legs");
  lines.push("");
  for (const leg of LEGS) {
    const run = c.legs.find((l) => l.leg === leg);
    lines.push(`- ${LEG_LABELS[leg]}: ${run ? `${run.status}${run.note ? ` — ${run.note}` : ""}` : "not run"}`);
  }
  lines.push("");
  lines.push("## Findings by leg");
  for (const leg of LEGS) {
    const entries = c.evidence.filter((e) => e.leg === leg).sort((a, b) => GRADE_ORDER[a.grade] - GRADE_ORDER[b.grade]);
    lines.push("");
    lines.push(`### ${LEG_LABELS[leg]}`);
    if (!entries.length) { lines.push(""); lines.push("No evidence in this leg yet."); continue; }
    for (const e of entries) {
      lines.push("");
      lines.push(`- [${e.grade}] ${e.claim}`);
      lines.push(`  Source: ${e.sourceUrl}`);
      if (e.excerpt) lines.push(`  Excerpt: ${e.excerpt}`);
      lines.push(`  Fetched: ${e.fetchedAt} (id ${e.id})`);
    }
  }
  lines.push("");
  lines.push("## Contacts");
  if (!c.contacts.length) lines.push("\nNo contacts recorded.");
  for (const ct of c.contacts) {
    lines.push("");
    lines.push(`- ${ct.kind}: ${ct.value}`);
    lines.push(`  Found on: ${ct.provenanceUrl}${ct.provenanceSnippet ? ` — "${ct.provenanceSnippet}"` : ""}`);
  }
  lines.push("");
  lines.push("## Outreach drafts (draft only — never sent by Jarvis)");
  if (!c.drafts.length) lines.push("\nNo drafts.");
  for (const d of c.drafts) {
    lines.push("");
    lines.push(`- To ${d.to} via ${d.channel} — approval: ${d.approved ? "approved by owner (still unsent; the owner sends it themselves)" : "unapproved"}; status: draft — never sent by Jarvis`);
    if (d.subject) lines.push(`  Subject: ${d.subject}`);
    lines.push(`  Body: ${d.body.slice(0, 400)}${d.body.length > 400 ? "…" : ""}`);
  }
  lines.push("");
  lines.push("## Caveats");
  lines.push("");
  lines.push("- Unverified entries are candidates gathered from public sources, not established facts; a grade is promoted only with its source attached.");
  lines.push("- People-search / data-broker and breach-lookup sites are out of scope and were not read.");
  lines.push("- Outreach is drafts only. Jarvis never sends a draft; sending is the owner's own action, per recipient.");
  lines.push("");
  return lines.join("\n");
}

function countGrade(c: CaseFile, g: EvidenceGrade): number {
  return c.evidence.filter((e) => e.grade === g).length;
}

/** One-paragraph case summary for tool results and case listings. */
export function summarizeCase(c: CaseFile): string {
  const legs = LEGS.map((leg) => {
    const run = c.legs.find((l) => l.leg === leg);
    return `${leg}: ${run ? run.status : "not run"}`;
  }).join("; ");
  return [
    `Case ${c.slug} — ${c.target} (${c.targetKind}, ${c.status})`,
    `Created ${c.createdAt}, updated ${c.updatedAt}`,
    `Legs: ${legs}`,
    `Evidence: ${c.evidence.length} (${countGrade(c, "confirmed")} confirmed, ${countGrade(c, "likely")} likely, ${countGrade(c, "unverified")} unverified); contacts: ${c.contacts.length}; drafts: ${c.drafts.length} (draft only, never sent)`,
  ].join("\n");
}

// ---------- leg orchestration ----------

export interface SearchHit { title: string; url: string; snippet: string }
export interface GatherDeps {
  search(query: string): Promise<SearchHit[]>;
  /** Fetch a page's readable text. Only the identity leg fetches pages (for contacts). */
  fetchText(url: string): Promise<string>;
  /** Hard bound on search + page fetches in one leg. */
  budget?: number;
}

export interface LegOutcome {
  leg: Leg;
  status: LegStatus;
  note: string;
  evidenceAdded: number;
  contactsAdded: number;
  fetches: number;
}

/** The search queries each leg runs, at most two per leg. Pure, so tests pin the shape. */
export function legQueries(leg: Leg, target: string): string[] {
  const t = target.trim().replace(/"/g, "");
  switch (leg) {
    case "identity": return [`"${t}"`, `${t} official website`];
    case "records": return [`${t} company registration registry`, `"${t}" incorporation filing`];
    case "social": return [`${t} profile`, `"${t}" mentions`];
    case "documents": return [`"${t}" filetype:pdf`, `${t} publications documents`];
  }
}

/**
 * Run one leg against the live web through the injected search/fetch (the
 * app's existing safe machinery). Candidate results become UNVERIFIED
 * evidence — grades are promoted later, never by the gatherer. Bounded:
 * at most ~6 fetches per leg (2 searches + up to 3 page fetches on the
 * identity leg + slack), broker and duplicate sources skipped, and every
 * failure is recorded on the leg rather than hidden. The identity leg also
 * extracts contacts from the pages it fetches, each with its provenance.
 */
export async function gatherLeg(store: CaseStore, slug: string, leg: Leg, deps: GatherDeps): Promise<LegOutcome> {
  const budget = deps.budget ?? 6;
  let fetches = 0;
  let evidenceAdded = 0;
  let contactsAdded = 0;
  const failures: string[] = [];
  const c0 = store.load(slug);
  const queries = legQueries(leg, c0.target);
  // Dedupe within this leg only: the same source can legitimately support
  // several legs (an official site is both identity and a records pointer),
  // and a case-wide dedupe would starve every leg after the first.
  const seenUrls = new Set(c0.evidence.filter((e) => e.leg === leg).map((e) => e.sourceUrl));
  const pages: Array<{ url: string; hit: SearchHit }> = [];

  for (const q of queries) {
    if (fetches >= budget) break;
    fetches++;
    let hits: SearchHit[];
    try { hits = await deps.search(q); } catch (err) {
      failures.push(`search failed (${err instanceof Error ? err.message.slice(0, 80) : String(err).slice(0, 80)})`);
      continue;
    }
    for (const h of hits.slice(0, 4)) {
      if (!h.url || seenUrls.has(h.url) || isBrokerUrl(h.url)) continue;
      seenUrls.add(h.url);
      const claim = [h.title, h.snippet].filter((s) => s.trim()).join(" — ").slice(0, 600) || h.url;
      try {
        store.addEvidence(slug, { leg, claim, sourceUrl: h.url, excerpt: h.snippet, grade: "unverified" });
        evidenceAdded++;
        if (leg === "identity" && pages.length < 3) pages.push({ url: h.url, hit: h });
      } catch { /* a refused source (e.g. broker) is skipped, not fatal */ }
    }
  }

  if (leg === "identity") {
    for (const p of pages) {
      if (fetches >= budget) break;
      fetches++;
      let text: string;
      try { text = await deps.fetchText(p.url); } catch { failures.push(`couldn't read ${p.url}`); continue; }
      for (const x of extractContacts(text, p.url)) {
        const r = store.addContact(slug, { kind: x.kind, value: x.value, provenanceUrl: p.url, provenanceSnippet: x.snippet });
        if (r.added) contactsAdded++;
      }
    }
  }

  const status: LegStatus = failures.length && evidenceAdded === 0 ? "error" : evidenceAdded > 0 ? "ok" : "empty";
  const note = `${evidenceAdded} unverified evidence${contactsAdded ? `, ${contactsAdded} contacts` : ""} from ${queries.length} queries` +
    (failures.length ? `; ${failures.join("; ")}` : "");
  const c = store.load(slug);
  store.recordLeg(c, leg, status, note);
  store.save(c);
  return { leg, status, note, evidenceAdded, contactsAdded, fetches };
}
