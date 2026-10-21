import { createHash } from "node:crypto";
import WebSocket from "ws";
import { z } from "zod";
import type { Vision } from "../vision.js";
import type { Tool } from "./index.js";
import * as os from "./platform.js";

/**
 * Reading more than one screen at a time.
 *
 * - `read_browser_page`: the whole text and links of a Chrome tab, straight
 *   from the browser over its local debug port — no scrolling, no
 *   screenshots, exact text. Chrome must run with
 *   `--remote-debugging-port=9222` (and, since Chrome 136, a non-default
 *   `--user-data-dir`). The port is loopback only. The only thing sent to
 *   the page is one fixed read-only expression.
 * - `scroll_read`: any app. Screenshot, scroll, screenshot… until the
 *   screen stops changing or the cap, each screen read by the vision model
 *   (one image per request, so a small vision context still fits), with
 *   the overlap between screens removed. One tool call instead of a
 *   look → scroll → look loop of model turns.
 */

function tool<S extends z.ZodType>(t: Tool<S>): Tool {
  return t as unknown as Tool;
}

export const CHROME_DEBUG_PORT = 9222;
const PAGE_TEXT_CAP = 60_000;
const SCROLL_TEXT_CAP = 40_000;

const CHROME_HELP =
  `Chrome isn't reachable on its debug port (127.0.0.1:${CHROME_DEBUG_PORT}). Start it with: google-chrome --remote-debugging-port=${CHROME_DEBUG_PORT} --user-data-dir=$HOME/.config/jarvis-chrome (add --no-sandbox when running as root). Or use scroll_read to read it from the screen.`;

export interface ChromeTarget {
  id: string;
  type: string;
  title: string;
  url: string;
  webSocketDebuggerUrl?: string;
}

/** The tab to read: the first page whose URL or title contains `match` (case-insensitive), else the first page (Chrome lists the most recently used first). */
export function pickTab(targets: ChromeTarget[], match?: string): ChromeTarget | null {
  const pages = targets.filter((t) => t.type === "page" && t.webSocketDebuggerUrl && !t.url.startsWith("devtools://"));
  if (!match) return pages[0] ?? null;
  const m = match.toLowerCase();
  return pages.find((t) => t.url.toLowerCase().includes(m) || t.title.toLowerCase().includes(m)) ?? null;
}

/** The one expression ever evaluated in a page: read its title, URL, visible text and links. */
export const READ_PAGE_EXPRESSION = `(() => {
  const links = [...document.querySelectorAll("a[href]")].slice(0, 300).map((a) => ({ text: (a.innerText || a.title || "").trim().replace(/\\s+/g, " ").slice(0, 100), href: a.href })).filter((l) => l.href.startsWith("http"));
  return { title: document.title, url: location.href, text: document.body ? document.body.innerText : "", links };
})()`;

interface PageRead { title: string; url: string; text: string; links: Array<{ text: string; href: string }> }

export function formatPage(p: PageRead, maxChars: number): string {
  const text = p.text.replace(/\n{3,}/g, "\n\n").trim();
  const cut = text.length > maxChars;
  const seen = new Set<string>();
  const links = p.links.filter((l) => (seen.has(l.href) ? false : (seen.add(l.href), true))).slice(0, 150);
  return [
    `Page: ${p.title}`,
    `URL: ${p.url}`,
    `Text (${text.length} chars${cut ? `, first ${maxChars} shown` : ""}):`,
    cut ? `${text.slice(0, maxChars)}\n[… cut]` : text,
    links.length ? `\nLinks (${links.length}):\n${links.map((l) => `- ${l.text || "(no text)"} → ${l.href}`).join("\n")}` : "",
  ].join("\n");
}

async function evaluate(wsUrl: string, expression: string, signal?: AbortSignal): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    const done = (fn: () => void) => { clearTimeout(timer); signal?.removeEventListener("abort", onAbort); ws.close(); fn(); };
    const timer = setTimeout(() => done(() => reject(new Error("Chrome did not answer within 15 s."))), 15_000);
    const onAbort = () => done(() => reject(new Error("stopped")));
    signal?.addEventListener("abort", onAbort, { once: true });
    ws.on("open", () => ws.send(JSON.stringify({ id: 1, method: "Runtime.evaluate", params: { expression, returnByValue: true } })));
    ws.on("message", (raw) => {
      const msg = JSON.parse(raw.toString()) as { id?: number; result?: { result?: { value?: unknown }; exceptionDetails?: { text?: string } }; error?: { message?: string } };
      if (msg.id !== 1) return;
      if (msg.error) return done(() => reject(new Error(`Chrome: ${msg.error!.message ?? "error"}`)));
      if (msg.result?.exceptionDetails) return done(() => reject(new Error(`The page refused the read: ${msg.result!.exceptionDetails!.text ?? "exception"}`)));
      done(() => resolve(msg.result?.result?.value));
    });
    ws.on("error", (err) => done(() => reject(err)));
  });
}

export async function readChromePage(match: string | undefined, maxChars: number, signal?: AbortSignal, port = CHROME_DEBUG_PORT): Promise<string> {
  let targets: ChromeTarget[];
  try {
    const res = await fetch(`http://127.0.0.1:${port}/json/list`, { signal: signal ?? AbortSignal.timeout(5000) });
    targets = (await res.json()) as ChromeTarget[];
  } catch {
    throw new Error(CHROME_HELP);
  }
  const tab = pickTab(targets, match);
  if (!tab) {
    const open = targets.filter((t) => t.type === "page").map((t) => `- ${t.title} (${t.url})`).join("\n");
    throw new Error(match ? `No open Chrome tab matches "${match}". Open tabs:\n${open || "(none)"}` : "Chrome has no open page.");
  }
  const value = (await evaluate(tab.webSocketDebuggerUrl!, READ_PAGE_EXPRESSION, signal)) as PageRead | undefined;
  if (!value || typeof value.text !== "string") throw new Error("Chrome returned nothing for that page.");
  return formatPage(value, maxChars);
}

export const readBrowserPageTool = tool({
  name: "read_browser_page",
  description:
    "Read the full text and links of a page open in Chrome, straight from the browser — the whole page at once, no scrolling or screenshots, exact text. Defaults to the most recently used tab; `match` picks a tab by part of its URL or title. Prefer this over look_at_screen / scroll_read for web pages in Chrome. Needs Chrome started with its local debug port (the result says how if it isn't).",
  schema: z.object({
    match: z.string().max(200).optional().describe("part of the tab's URL or title"),
    max_chars: z.number().int().min(1000).max(PAGE_TEXT_CAP).optional().describe(`text cap (default ${PAGE_TEXT_CAP})`),
  }),
  policy: { effects: ["read"] },
  // Page text is web content: it gets the same provenance cards as a fetched page.
  outside: true,
  parallelSafe: true,
  async run({ match, max_chars }, ctx) {
    return readChromePage(match, max_chars ?? PAGE_TEXT_CAP, ctx.signal);
  },
});

// ---------- scroll_read ----------

const norm = (s: string) => s.toLowerCase().replace(/\s+/g, " ").trim();

/** Drop the leading lines of `next` that already appeared at the end of `prev` (the overlap between two scrolled screens). */
export function dropOverlap(prev: string, next: string): string {
  const tail = new Set(prev.split("\n").slice(-60).map(norm).filter((l) => l.length > 3));
  const lines = next.split("\n");
  let i = 0;
  // Skip lines already seen; allow short or blank lines in between.
  let lastSeen = -1;
  for (; i < Math.min(lines.length, 80); i++) {
    const l = norm(lines[i]!);
    if (!l || l.length <= 3) continue;
    if (tail.has(l)) lastSeen = i;
    else if (i - lastSeen > 2) break;
  }
  return lines.slice(lastSeen + 1).join("\n").trim();
}

export function transcribeQuestion(n: number, prevTail: string, question?: string): string {
  return [
    `This is screen ${n} of a page or document being scrolled down.`,
    "Transcribe all readable text on it, top to bottom, exactly as written (keep headings, list items, prices, numbers, names and link texts). Skip window chrome, toolbars and ads.",
    prevTail ? `The previous screen ended with:\n"""\n${prevTail}\n"""\nDo not repeat that text; start after it.` : "",
    question ? `Also note anything on this screen relevant to: ${question}` : "",
    "Output the text only, no commentary.",
  ].filter(Boolean).join("\n");
}

export interface ScrollReadDeps {
  vision: Vision;
  screenshot(): Promise<Buffer>;
  scroll(clicks: number, x?: number, y?: number): Promise<void>;
  signal?: AbortSignal;
  settleMs?: number;
}

export interface ScrollReadResult { screens: number; reachedEnd: boolean; text: string }

export async function runScrollRead(opts: { maxScreens: number; clicks: number; x?: number; y?: number; question?: string }, deps: ScrollReadDeps): Promise<ScrollReadResult> {
  const settle = deps.settleMs ?? 700;
  const hash = (b: Buffer) => createHash("sha1").update(b).digest("hex");
  const parts: string[] = [];
  let shot = await deps.screenshot();
  let last = hash(shot);
  let reachedEnd = false;
  let screens = 0;
  for (let n = 1; n <= opts.maxScreens; n++) {
    if (deps.signal?.aborted) throw new Error("stopped");
    screens = n;
    const prev = parts.join("\n");
    const prevTail = prev.split("\n").slice(-8).join("\n").slice(-600);
    const read = await deps.vision.describe([{ data: shot.toString("base64"), mediaType: "image/png" }], transcribeQuestion(n, prevTail, opts.question), deps.signal, 1800);
    const fresh = n === 1 ? read.trim() : dropOverlap(prev, read);
    if (fresh) parts.push(fresh);
    if (parts.join("\n").length > SCROLL_TEXT_CAP || n === opts.maxScreens) break;
    await deps.scroll(opts.clicks, opts.x, opts.y);
    if (settle > 0) await new Promise((r) => setTimeout(r, settle));
    shot = await deps.screenshot();
    const h = hash(shot);
    if (h === last) { reachedEnd = true; break; }
    last = h;
  }
  const text = parts.join("\n\n").slice(0, SCROLL_TEXT_CAP);
  return { screens, reachedEnd, text };
}

export const scrollReadTool = tool({
  name: "scroll_read",
  description:
    "Scroll and read in one step, in any app: screenshots the screen, scrolls down, and repeats until the screen stops changing (the end) or max_screens, reading each screen with the vision model and joining the text without the overlap. Use it for long documents, chats, feeds or pages that aren't in Chrome (for Chrome pages read_browser_page is faster and exact). Put the mouse over (x, y) of the scrollable area if the page needs it. Off unless screen control is on; asks the owner first like control_screen.",
  schema: z.object({
    question: z.string().max(300).optional().describe("what to look for while reading (optional)"),
    max_screens: z.number().int().min(1).max(15).optional().describe("screens to read at most (default 6)"),
    clicks_per_screen: z.number().int().min(1).max(30).optional().describe("wheel clicks per scroll (default 5)"),
    x: z.number().int().min(0).max(20000).optional().describe("scroll with the mouse over this point"),
    y: z.number().int().min(0).max(20000).optional(),
  }),
  // It scrolls the owner's screen: same standing as control_screen.
  policy: { effects: ["mutate"], alwaysConfirm: true },
  async run({ question, max_screens, clicks_per_screen, x, y }, ctx) {
    if (!ctx.vision) throw new Error("No vision model is set up. Set a local vision model in config (vision.model), or switch to the cloud brain.");
    const r = await runScrollRead(
      { maxScreens: max_screens ?? 6, clicks: clicks_per_screen ?? 5, x, y, question },
      { vision: ctx.vision, screenshot: () => os.screenshotBuffer(), scroll: (c, px, py) => os.screenScroll(c, px, py), signal: ctx.signal },
    );
    return `Read ${r.screens} screen(s)${r.reachedEnd ? " — reached the end (the screen stopped changing)" : r.screens >= (max_screens ?? 6) ? " — stopped at max_screens; more may follow" : ""}.\n\n${r.text || "(no readable text)"}`;
  },
});
