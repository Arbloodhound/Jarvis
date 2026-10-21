import { createHash } from "node:crypto";

/**
 * Autonomous browsing — the desktop form of the phone's AutonomousBrowse /
 * BrowseRun pair, built on the full-page-access contract (ARCHITECTURE.md
 * §4, harness/browse.py):
 *
 *  - Every page in the report is a page that was actually FETCHED, in
 *    full, through the injected fetch function (the tool wires in
 *    safeFetch — same SSRF / blocked-range rules as every web tool).
 *    Page content is the fetched body's text, never a search snippet.
 *  - Search snippets are used ONLY to choose which URLs to visit. They
 *    are carried in the report in their own section, explicitly labelled
 *    as snippets, never as page content.
 *  - A page that cannot be fetched is recorded as FAILED, with the
 *    error, and the report says so. There is no silent fallback to the
 *    snippet for that page.
 *  - Provenance (the desktop form of the phone's screen-bound rule):
 *    every page carries its URL and the SHA-256 of the fetched body.
 *    If a URL is fetched again mid-task and the hash differs, the page
 *    is flagged CHANGED and the earlier extract is marked STALE.
 *  - Navigation is bounded: a step budget (one step = one page fetch
 *    attempt), default 8, hard cap 15. Reading only — no form
 *    submission, no clicks, no purchases; the only verb is GET.
 *  - .onion URLs are fetched only when Tor is on (deps.torEnabled, with
 *    fetchFn the Tor transport, which fails closed); with Tor off they are
 *    refused, never fetched — an onion is never fetched over clearnet.
 *
 * Why a tool-internal loop and not an agent-loop sub-run: a tool's
 * context (ToolContext in tools/index.ts) carries no provider handle,
 * so a tool cannot run model turns. The phone's model-driven loop does
 * not map onto this codebase's loop.ts without new plumbing through the
 * gate. What fits honestly is the phone's other half — BrowseRun's
 * bounded fetch/follow mechanics — returning a structured report the
 * calling model then reasons over, with the contract enforced here
 * rather than left to the model's discretion.
 */

export const BROWSE_DEFAULT_STEPS = 8;
export const BROWSE_MAX_STEPS = 15;
/** Extracted text kept per page in the report. */
const PAGE_TEXT_CAP = 4_000;
/** Whole rendered report cap, so a maximal run stays one tool result. */
const REPORT_CAP = 60_000;

export interface BrowseFetchResult {
  status: number;
  type: string;
  body: string;
  /** Final URL after redirects. */
  url: string;
}

export type BrowseFetchFn = (url: string, signal: AbortSignal) => Promise<BrowseFetchResult>;

export interface BrowseSearchResult {
  title: string;
  url: string;
  snippet: string;
}

export interface BrowseSearchOutcome {
  results: BrowseSearchResult[];
  provider: string | null;
  blocked: string[];
}

export type BrowseSearchFn = (query: string, signal: AbortSignal) => Promise<BrowseSearchOutcome>;

export interface BrowseDeps {
  fetchFn: BrowseFetchFn;
  searchFn: BrowseSearchFn;
  /** The same HTML→text conversion web_fetch uses. */
  htmlToText: (html: string) => string;
  signal: AbortSignal;
  /** True when fetchFn is the Tor transport — the only case an .onion is fetched. */
  torEnabled?: boolean;
}

export interface BrowseOptions {
  goal: string;
  startUrl?: string;
  searchQuery?: string;
  /** Step budget; clamped to [1, BROWSE_MAX_STEPS]. */
  maxSteps?: number;
}

export interface BrowsePage {
  step: number;
  url: string;
  finalUrl: string;
  status: number;
  /** SHA-256 (hex) of the fetched body — the page-bound provenance. */
  hash: string;
  title: string;
  text: string;
  foundOn: string;
  /** True when a later fetch of this URL returned a different hash. */
  stale: boolean;
}

export interface BrowseFailure {
  step: number;
  url: string;
  foundOn: string;
  error: string;
}

export interface BrowseFollow {
  fromUrl: string;
  toUrl: string;
  anchor: string;
  reason: string;
}

export interface BrowseReport {
  goal: string;
  stepsUsed: number;
  stepBudget: number;
  budgetExhausted: boolean;
  /** Search snippets, labelled as such — never page content. */
  snippets: Array<BrowseSearchResult & { provider: string | null }>;
  searchBlocked: string[];
  pages: BrowsePage[];
  failed: BrowseFailure[];
  followed: BrowseFollow[];
  /** URLs flagged as changed mid-task (re-fetch returned a new hash). */
  changed: string[];
  notes: string[];
}

export function contentHash(body: string): string {
  return createHash("sha256").update(body, "utf8").digest("hex");
}

export function isOnionUrl(raw: string): boolean {
  try {
    return new URL(raw).hostname.toLowerCase().endsWith(".onion");
  } catch {
    return false;
  }
}

const STOPWORDS = new Set([
  "the", "and", "for", "from", "with", "what", "when", "where", "which", "that", "this",
  "find", "show", "tell", "about", "into", "does", "have", "has", "are", "was", "were",
]);

export function goalTerms(goal: string): string[] {
  const out = new Set<string>();
  for (const raw of goal.toLowerCase().split(/[^a-z0-9.]+/)) {
    const t = raw.replace(/\.+$/, "").replace(/s$/, "");
    if (t.length >= 3 && !STOPWORDS.has(t) && !STOPWORDS.has(raw)) out.add(t);
  }
  return [...out];
}

function termsIn(text: string, terms: string[]): string[] {
  const lower = ` ${text.toLowerCase()} `;
  return terms.filter((t) => lower.includes(t));
}

export interface ExtractedLink {
  url: string;
  anchor: string;
}

/** Outbound http(s) links from a fetched page's HTML, in document order, deduped. */
export function extractLinks(html: string, baseUrl: string, htmlToText: (h: string) => string): ExtractedLink[] {
  const out: ExtractedLink[] = [];
  const seen = new Set<string>();
  const re = /<a\s[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(html)) !== null) {
    let u: URL;
    try {
      u = new URL(m[1]!.replace(/&amp;/g, "&"), baseUrl);
    } catch {
      continue;
    }
    if (u.protocol !== "http:" && u.protocol !== "https:") continue;
    u.hash = "";
    const url = u.toString();
    if (url === baseUrl || seen.has(url)) continue;
    seen.add(url);
    out.push({ url, anchor: htmlToText(m[2]!).replace(/\s+/g, " ").trim().slice(0, 120) });
    if (out.length >= 60) break;
  }
  return out;
}

function pageTitle(html: string, htmlToText: (h: string) => string): string {
  const m = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(html);
  return m ? htmlToText(m[1]!).replace(/\s+/g, " ").trim().slice(0, 160) : "";
}

interface Queued {
  url: string;
  foundOn: string;
  depth: number;
}

/**
 * Run one bounded browse task. Never throws for a page failure — failures
 * are data in the report. Throws only when the run itself is aborted.
 */
export async function runBrowseTask(opts: BrowseOptions, deps: BrowseDeps): Promise<BrowseReport> {
  const budget = Math.max(1, Math.min(BROWSE_MAX_STEPS, Math.floor(opts.maxSteps ?? BROWSE_DEFAULT_STEPS)));
  const terms = goalTerms(opts.goal);
  const report: BrowseReport = {
    goal: opts.goal,
    stepsUsed: 0,
    stepBudget: budget,
    budgetExhausted: false,
    snippets: [],
    searchBlocked: [],
    pages: [],
    failed: [],
    followed: [],
    changed: [],
    notes: [],
  };

  const queue: Queued[] = [];
  const queuedOrVisited = new Set<string>();
  const enqueue = (url: string, foundOn: string, depth: number) => {
    if (queuedOrVisited.has(url)) return;
    queuedOrVisited.add(url);
    queue.push({ url, foundOn, depth });
  };

  if (opts.startUrl) {
    enqueue(opts.startUrl, "the task's start URL", 0);
  } else {
    const query = opts.searchQuery || opts.goal;
    try {
      const outcome = await deps.searchFn(query, deps.signal);
      report.searchBlocked = outcome.blocked;
      for (const r of outcome.results) {
        report.snippets.push({ ...r, provider: outcome.provider });
        enqueue(r.url, `search results for "${query}"`, 0);
      }
      if (!outcome.results.length) {
        report.notes.push(
          outcome.blocked.length
            ? `Search returned no usable results (providers blocked/unreachable: ${outcome.blocked.join(", ")}); no pages could be seeded.`
            : `Search returned no results for "${query}"; no pages could be seeded.`,
        );
      }
    } catch (err) {
      if (deps.signal.aborted) throw err;
      report.notes.push(`Search failed (${err instanceof Error ? err.message : String(err)}); no pages could be seeded.`);
    }
  }

  /** First hash seen per canonical (final) URL, for change detection. */
  const hashByFinalUrl = new Map<string, string>();

  while (queue.length && report.stepsUsed < budget) {
    if (deps.signal.aborted) throw new Error("stopped");
    const next = queue.shift()!;
    report.stepsUsed++;
    const step = report.stepsUsed;

    if (isOnionUrl(next.url) && !deps.torEnabled) {
      report.failed.push({
        step,
        url: next.url,
        foundOn: next.foundOn,
        error: "REFUSED: .onion URLs are fetched only through Tor, and Tor is off — an onion is never fetched over clearnet.",
      });
      continue;
    }

    let res: BrowseFetchResult;
    try {
      res = await deps.fetchFn(next.url, deps.signal);
    } catch (err) {
      if (deps.signal.aborted) throw err;
      report.failed.push({
        step,
        url: next.url,
        foundOn: next.foundOn,
        error: `FAILED to fetch: ${err instanceof Error ? err.message : String(err)}`,
      });
      continue;
    }
    if (res.status >= 400) {
      report.failed.push({
        step,
        url: next.url,
        foundOn: next.foundOn,
        error: `FAILED: HTTP ${res.status}. The page was not read; do not treat any snippet about it as its content.`,
      });
      continue;
    }

    const hash = contentHash(res.body);
    const finalUrl = res.url || next.url;
    const priorHash = hashByFinalUrl.get(finalUrl);
    if (priorHash !== undefined && priorHash !== hash && !report.changed.includes(finalUrl)) {
      report.changed.push(finalUrl);
      for (const p of report.pages) if (p.finalUrl === finalUrl) p.stale = true;
    }
    if (priorHash === undefined) hashByFinalUrl.set(finalUrl, hash);

    const isHtml = res.type.includes("html") || /^\s*</.test(res.body);
    const text = (res.type.includes("html") ? deps.htmlToText(res.body) : res.body).trim();
    report.pages.push({
      step,
      url: next.url,
      finalUrl,
      status: res.status,
      hash,
      title: isHtml ? pageTitle(res.body, deps.htmlToText) : "",
      text: text.slice(0, PAGE_TEXT_CAP),
      foundOn: next.foundOn,
      stale: priorHash !== undefined && priorHash !== hash,
    });

    // Link discovery: this page's outbound links, goal-matching first.
    if (isHtml) {
      const links = extractLinks(res.body, finalUrl, deps.htmlToText);
      const scored = links.map((l) => {
        const hits = [...new Set([...termsIn(l.anchor, terms), ...termsIn(l.url, terms)])];
        return { link: l, hits, score: hits.length };
      });
      scored.sort((a, b) => b.score - a.score);
      for (const { link, hits } of scored) {
        if (queuedOrVisited.has(link.url)) continue;
        const reason = hits.length
          ? `anchor "${link.anchor || link.url}" matches goal term(s): ${hits.join(", ")}`
          : `anchor "${link.anchor || link.url}" — no goal terms matched; queued after matching links for discovery`;
        enqueue(link.url, finalUrl, next.depth + 1);
        report.followed.push({ fromUrl: finalUrl, toUrl: link.url, anchor: link.anchor, reason });
      }
    }
  }
  report.budgetExhausted = queue.length > 0 && report.stepsUsed >= budget;
  if (report.budgetExhausted) {
    report.notes.push(`Step budget (${budget}) exhausted with ${queue.length} discovered link(s) not visited.`);
  }
  return report;
}

/** The report as the calling model receives it: structured, provenanced, explicit about failure. */
export function renderBrowseReport(r: BrowseReport): string {
  const lines: string[] = [];
  lines.push(`Browse task: ${r.goal}`);
  lines.push(
    `Steps used: ${r.stepsUsed}/${r.stepBudget}. Pages read in full: ${r.pages.length}. Pages FAILED: ${r.failed.length}.` +
      (r.budgetExhausted ? " Step budget exhausted before every discovered link was visited." : ""),
  );
  if (r.failed.length) {
    lines.push("FAILED PAGES — these were NOT read. Say so explicitly; never quote a snippet as their content:");
    for (const f of r.failed) lines.push(`- step ${f.step} ${f.url} (found on ${f.foundOn}): ${f.error}`);
  }
  if (r.changed.length) {
    lines.push(`CHANGED PAGES — a re-fetch returned a different content hash mid-task; earlier extracts are marked STALE: ${r.changed.join(", ")}`);
  }
  for (const n of r.notes) lines.push(`Note: ${n}`);
  if (r.snippets.length) {
    lines.push("");
    lines.push("SEARCH SNIPPETS (NOT page content — used only to choose which URLs to visit; never cite a snippet as evidence):");
    for (const s of r.snippets) lines.push(`- [snippet via ${s.provider ?? "search"}] ${s.title} — ${s.url}\n  ${s.snippet}`);
  }
  lines.push("");
  lines.push("PAGES READ IN FULL (the only evidence in this report; cite the URL and hash with any fact taken from a page):");
  if (!r.pages.length) lines.push("(none — no page was successfully read)");
  for (const p of r.pages) {
    lines.push("");
    lines.push(
      `--- page step ${p.step}: ${p.finalUrl}${p.finalUrl !== p.url ? ` (requested ${p.url})` : ""}` +
        `\n    sha256: ${p.hash}\n    status: ${p.status} · found on: ${p.foundOn}${p.title ? ` · title: ${p.title}` : ""}${p.stale ? " · STALE — this page changed later in the task; prefer the later extract" : ""}`,
    );
    lines.push(p.text || "(page body extracted to no text)");
  }
  if (r.followed.length) {
    lines.push("");
    lines.push("LINKS DISCOVERED (in follow order; a link listed here was queued — it appears under PAGES only if it was actually fetched within budget):");
    for (const f of r.followed) lines.push(`- from ${f.fromUrl} -> ${f.toUrl} — ${f.reason}`);
  }
  const out = lines.join("\n");
  return out.length > REPORT_CAP ? out.slice(0, REPORT_CAP) + "\n[… report cut at the size cap; pages above are complete extracts up to that point]" : out;
}
