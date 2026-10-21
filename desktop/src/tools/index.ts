import { exec } from "node:child_process";
import { lookup as dnsLookup, type LookupOptions } from "node:dns";
import { appendFile, mkdir, readdir, readFile, stat, writeFile } from "node:fs/promises";
import http from "node:http";
import https from "node:https";
import { BlockList, createConnection, isIP, type LookupFunction } from "node:net";
import { arch, cpus, freemem, homedir, hostname, platform, release, totalmem, uptime } from "node:os";
import path from "node:path";
import { SocksProxyAgent } from "socks-proxy-agent";
import { z } from "zod";
import type { ToolPolicy } from "../gate/gate.js";
import { resolvePath } from "../gate/gate.js";
import type { Embedder } from "../embeddings.js";
import { formatEntry, type EcholaliaStore } from "../echolalia.js";
import type { Memory } from "../memory.js";
import type { Vision } from "../vision.js";
import { speak as speakVoice, type Transcriber } from "../voice.js";
import { formatPending, type NotificationInbox } from "../notifications/inbox.js";
import * as os from "./platform.js";
import { shoppingTools } from "./shopping.js";
import { BROWSE_DEFAULT_STEPS, BROWSE_MAX_STEPS, renderBrowseReport, runBrowseTask } from "./browse.js";

export {
  excludeOnion, extractListings, extractNavLinks, extractPrices, extractReputation, extractReviews,
  fetchMarketplace, fetchProductCheck, fetchProductReviews, fetchSellerCheck, parseEbayResults,
  parseListingPage, runMarketplaceNav, scanScamSignals, sortRowsMechanically,
  type ListingRow, type NavSession,
} from "./shopping.js";

/**
 * Every tool Jarvis can use on the computer. Each one declares its effects for
 * the gate (read / mutate / egress / exec) and which arguments name a target,
 * a path, or text that leaves. A tool is never run without passing the gate;
 * the gate is in agent/loop.ts, not here, so no tool can skip it.
 */

export interface ToolContext {
  cwd: string;
  workspace: string;
  memory: Memory;
  /** Echolalia pending store, present only when memory.verifyBeforeStore is on. */
  echolalia: EcholaliaStore | null;
  embedder: Embedder | null;
  vision: Vision | null;
  /** Speech-to-text backend, or null when not set up / withheld offline. */
  transcriber: Transcriber | null;
  signal: AbortSignal;
  /** How web tools fetch. Set per run by toolsFor (Tor transport when on); direct safeFetch otherwise. */
  webFetch?: WebFetchFn;
}

export interface Tool<S extends z.ZodType = z.ZodType> {
  name: string;
  description: string;
  schema: S;
  policy: ToolPolicy;
  /** True when the result comes from outside the computer (the web). */
  outside?: boolean;
  /** Read-only tools may run in parallel with each other. */
  parallelSafe?: boolean;
  /** Needs the network; removed when offline-only is on. */
  network?: boolean;
  run(args: z.infer<S>, ctx: ToolContext): Promise<string>;
}

const MAX_READ = 60_000;
const MAX_FETCH = 40_000;

function tool<S extends z.ZodType>(t: Tool<S>): Tool {
  return t as unknown as Tool;
}

export function jsonSchema(t: Tool): Record<string, unknown> {
  const s = z.toJSONSchema(t.schema) as Record<string, unknown>;
  delete s.$schema;
  return s;
}

// ---------- files ----------

const readFileTool = tool({
  name: "read_file",
  description: "Read a text file on this computer. Use offset/limit (in lines) for big files.",
  schema: z.object({
    path: z.string().describe("Absolute path, ~/path, or a path relative to the working folder"),
    offset: z.number().int().min(0).optional(),
    limit: z.number().int().min(1).max(5000).optional(),
  }),
  policy: { effects: ["read"], pathArgs: ["path"] },
  parallelSafe: true,
  async run({ path: p, offset = 0, limit = 2000 }, ctx) {
    const abs = resolvePath(p, ctx.cwd);
    const buf = await readFile(abs);
    if (buf.subarray(0, 8000).includes(0)) return `${abs} looks like a binary file (${buf.length} bytes); not shown.`;
    const lines = buf.toString("utf8").split("\n");
    const slice = lines.slice(offset, offset + limit).join("\n");
    const more = offset + limit < lines.length ? `\n[… ${lines.length - offset - limit} more lines; read again with offset=${offset + limit}]` : "";
    return (slice.length > MAX_READ ? slice.slice(0, MAX_READ) + "\n[… cut]" : slice) + more;
  },
});

const listDirTool = tool({
  name: "list_dir",
  description: "List a folder's contents (names, sizes, folders marked with /).",
  schema: z.object({ path: z.string().describe("Folder path; ~ is the home folder") }),
  policy: { effects: ["read"], pathArgs: ["path"] },
  parallelSafe: true,
  async run({ path: p }, ctx) {
    const abs = resolvePath(p, ctx.cwd);
    const entries = await readdir(abs, { withFileTypes: true });
    const rows = await Promise.all(entries.slice(0, 500).map(async (e) => {
      if (e.isDirectory()) return `${e.name}/`;
      try { return `${e.name}  ${(await stat(path.join(abs, e.name))).size}b`; } catch { return e.name; }
    }));
    return `${abs}\n${rows.sort().join("\n")}${entries.length > 500 ? `\n[… ${entries.length - 500} more]` : ""}`;
  },
});

const findFilesTool = tool({
  name: "find_files",
  description: "Find files whose name contains a word, under a folder (skips node_modules, .git and hidden folders).",
  schema: z.object({
    root: z.string().describe("Folder to search under"),
    name: z.string().min(1).describe("Part of the file name, case-insensitive"),
    max: z.number().int().min(1).max(500).optional(),
  }),
  policy: { effects: ["read"], pathArgs: ["root"] },
  parallelSafe: true,
  async run({ root, name, max = 100 }, ctx) {
    const abs = resolvePath(root, ctx.cwd);
    const needle = name.toLowerCase();
    const found: string[] = [];
    const queue = [abs];
    let visited = 0;
    while (queue.length && found.length < max && visited < 20000) {
      if (ctx.signal.aborted) break;
      const dir = queue.shift()!;
      let entries;
      try { entries = await readdir(dir, { withFileTypes: true }); } catch { continue; }
      for (const e of entries) {
        visited++;
        if (e.name.startsWith(".") || e.name === "node_modules") continue;
        const full = path.join(dir, e.name);
        if (e.name.toLowerCase().includes(needle)) found.push(e.isDirectory() ? `${full}/` : full);
        if (e.isDirectory()) queue.push(full);
      }
    }
    return found.length ? found.join("\n") : `Nothing named like "${name}" under ${abs}.`;
  },
});

const writeFileTool = tool({
  name: "write_file",
  description: "Write (or append to) a text file. Files outside Jarvis's workspace need the owner's OK.",
  schema: z.object({
    path: z.string(),
    content: z.string(),
    append: z.boolean().optional(),
  }),
  policy: { effects: ["mutate"], pathArgs: ["path"] },
  async run({ path: p, content, append }, ctx) {
    const abs = resolvePath(p, ctx.workspace);
    await mkdir(path.dirname(abs), { recursive: true });
    if (append) await appendFile(abs, content);
    else await writeFile(abs, content);
    return `${append ? "Appended" : "Wrote"} ${Buffer.byteLength(content)} bytes to ${abs}.`;
  },
});

// ---------- shell ----------

const runCommandTool = tool({
  name: "run_command",
  description:
    "Run a shell command on this computer and return its output. The owner confirms each command except a few read-only ones (ls, pwd, git status…). Prefer the file tools for reading files.",
  schema: z.object({
    command: z.string().min(1),
    cwd: z.string().optional(),
    timeout_sec: z.number().int().min(1).max(600).optional(),
  }),
  policy: { effects: ["exec"], egressArgs: ["command"], pathArgs: ["cwd"] },
  async run({ command, cwd, timeout_sec = 60 }, ctx) {
    const dir = cwd ? resolvePath(cwd, ctx.cwd) : ctx.cwd;
    return new Promise((resolve) => {
      const child = exec(command, { cwd: dir, timeout: timeout_sec * 1000, maxBuffer: 8 * 1024 * 1024, signal: ctx.signal }, (err, stdout, stderr) => {
        const out = `${stdout}${stderr ? `\n[stderr]\n${stderr}` : ""}`.trim();
        const code = err && "code" in err ? ` (exit ${String((err as { code?: unknown }).code)})` : "";
        const body = out.length > MAX_READ ? out.slice(0, MAX_READ) + "\n[… cut]" : out;
        resolve(`${err ? `Failed${code}` : "OK"}\n${body}`.trim());
      });
      child.stdin?.end();
    });
  },
});

// ---------- web ----------

/** Addresses web tools may never reach: this computer, the local network, and special ranges. */
const BLOCKED = new BlockList();
for (const [net, bits] of [
  ["0.0.0.0", 8], ["10.0.0.0", 8], ["100.64.0.0", 10], ["127.0.0.0", 8], ["169.254.0.0", 16], ["172.16.0.0", 12],
  ["192.0.0.0", 24], ["192.168.0.0", 16], ["198.18.0.0", 15], ["224.0.0.0", 4], ["240.0.0.0", 4],
] as const) BLOCKED.addSubnet(net, bits, "ipv4");
for (const [net, bits] of [
  ["::", 128], ["::1", 128], ["fc00::", 7], ["fe80::", 10], ["ff00::", 8], ["64:ff9b::", 96], ["2002::", 16],
] as const) BLOCKED.addSubnet(net, bits, "ipv6");

/** IPv4-mapped IPv6 forms (::ffff:7f00:1) are checked as the IPv4 address they carry. */
export function privateAddress(ip: string): boolean {
  const fam = isIP(ip);
  if (!fam) return true;
  return BLOCKED.check(ip, fam === 6 ? "ipv6" : "ipv4");
}

const NO_LOCAL = "Web tools do not reach this computer or the local network.";

/** Checks that need no DNS: scheme, local names, literal addresses. */
export function assertPublicUrl(raw: string): URL {
  const u = new URL(raw);
  if (u.protocol !== "http:" && u.protocol !== "https:") throw new Error("Only http and https links.");
  const host = u.hostname.replace(/^\[|\]$/g, "");
  if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) throw new Error(NO_LOCAL);
  if (isIP(host) && privateAddress(host)) throw new Error(NO_LOCAL);
  return u;
}

/**
 * DNS lookup used for the connection itself, so the address that is checked is
 * the address that is used. A name that resolves to a public address for the
 * check and a private one for the fetch (DNS rebinding) can't slip between them.
 */
function publicOnlyLookup(hostname: string, options: LookupOptions, cb: (...a: unknown[]) => void): void {
  dnsLookup(hostname, { all: true }, (err, addrs) => {
    if (err) return cb(err);
    if (!addrs.length || addrs.some((a) => privateAddress(a.address))) {
      return cb(Object.assign(new Error(NO_LOCAL), { code: "EBLOCKED" }));
    }
    if (options.all) cb(null, addrs);
    else cb(null, addrs[0]!.address, addrs[0]!.family);
  });
}

export function htmlToText(html: string): string {
  return html
    .replace(/<(script|style|noscript|svg|head)[\s\S]*?<\/\1>/gi, " ")
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(p|div|li|h[1-6]|tr|section|article)>/gi, "\n")
    .replace(/<a\s[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi, (_m, href: string, t: string) => `${t} (${href})`)
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/[ \t]+/g, " ")
    .replace(/\n\s*\n+/g, "\n\n")
    .trim();
}

interface Fetched { status: number; type: string; body: string; url: string }

/** The fetch the web tools run: one URL in, the final page out (redirects followed). */
export type WebFetchFn = (url: string, signal: AbortSignal) => Promise<Fetched>;

// ---------- Tor transport ----------

/** Tor routing for web tools: a SOCKS proxy on this computer (127.0.0.1), remote DNS. */
export interface TorSettings {
  enabled: boolean;
  /** SOCKS port of a local Tor daemon (9050) or Tor Browser (9150). */
  socksPort: number;
}

export const TOR_DISABLED: TorSettings = { enabled: false, socksPort: 9050 };

/** .onion names exist only inside Tor; the local resolver has never heard of them. */
export function isOnionUrl(u: URL): boolean {
  return u.hostname.toLowerCase().endsWith(".onion");
}

const ONION_NEEDS_TOR =
  "That is a .onion address. Jarvis opens .onion addresses only with Tor on (the Tor setting, or \"tor.enabled\" in config) — and only through the Tor proxy, never over the regular internet.";

const torDown = (port: number) =>
  `Tor is on but no Tor proxy is reachable at 127.0.0.1:${port}. Start Tor (or Tor Browser, which uses port 9150), or turn Tor off. Web tools will not fall back to a direct connection.`;

/**
 * Does a SOCKS5 proxy answer on 127.0.0.1:port? A greeting handshake only —
 * no traffic leaves the computer, so it is safe to ask any time (the CLI's
 * `where` line and the settings page use it for the Tor status).
 */
export function probeTorProxy(port: number, timeoutMs = 1500): Promise<boolean> {
  return new Promise((resolve) => {
    let settled = false;
    const sock = createConnection({ host: "127.0.0.1", port });
    const done = (ok: boolean) => {
      if (settled) return;
      settled = true;
      sock.destroy();
      resolve(ok);
    };
    sock.setTimeout(timeoutMs, () => done(false));
    sock.once("error", () => done(false));
    sock.once("connect", () => sock.write(Buffer.from([0x05, 0x01, 0x00]))); // version 5, one method offered: no auth
    sock.once("data", (d) => done(d.length >= 2 && d[0] === 0x05 && d[1] === 0x00));
  });
}

const MAX_BODY = 3 * 1024 * 1024;

function getOnce(u: URL, signal: AbortSignal, agent?: SocksProxyAgent, timeoutMs = 20_000): Promise<Fetched & { location?: string }> {
  return new Promise((resolve, reject) => {
    const mod = u.protocol === "https:" ? https : http;
    const req = mod.request(u, {
      method: "GET",
      // Direct path only: the checked-DNS lookup pins the address that was
      // vetted to the connection that is made. On the Tor path there is no
      // lookup at all — the agent carries the hostname to the proxy.
      ...(agent ? { agent } : { lookup: publicOnlyLookup as unknown as LookupFunction }),
      signal: AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]),
      headers: {
        "user-agent": "Mozilla/5.0 (Jarvis desktop agent)",
        accept: "text/html,application/json,text/plain;q=0.9,*/*;q=0.5",
        "accept-encoding": "identity",
      },
    }, (res) => {
      const chunks: Buffer[] = [];
      let size = 0;
      res.on("data", (c: Buffer) => {
        size += c.length;
        if (size <= MAX_BODY) chunks.push(c);
        else res.destroy();
      });
      const done = () => resolve({
        status: res.statusCode ?? 0,
        type: String(res.headers["content-type"] ?? ""),
        body: Buffer.concat(chunks).toString("utf8"),
        url: u.toString(),
        location: typeof res.headers.location === "string" ? res.headers.location : undefined,
      });
      res.on("end", done);
      res.on("close", done);
      res.on("error", reject);
    });
    req.on("error", reject);
    req.end();
  });
}

/**
 * One fetch, redirects followed by hand (each hop re-checked). Two modes:
 *
 * - Direct (the default, unchanged): the connection's own DNS lookup is the
 *   SSRF check (publicOnlyLookup), so the address vetted is the address used.
 * - Tor (tor.enabled): every hop goes through the local SOCKS proxy with
 *   socks5h semantics — the hostname travels to the proxy UNRESOLVED and
 *   the exit node resolves it. That is the only way .onion names resolve at
 *   all, and it keeps lookups off the local resolver. The direct path's
 *   rebinding defense cannot translate here (we cannot vet an address the
 *   exit chose), so the load is carried by what still applies: the scheme,
 *   local-name and literal-address blocks in assertPublicUrl run on every
 *   hop before anything is fetched, in both modes.
 *
 * .onion is refused outright unless Tor is on — never fetched direct. Tor
 * on but no proxy answering fails closed with a clear error; there is no
 * silent fallback to a direct fetch.
 */
async function fetchChain(raw: string, signal: AbortSignal, tor: TorSettings): Promise<Fetched> {
  let agent: SocksProxyAgent | undefined;
  if (tor.enabled) {
    // Prove the proxy answers before fetching anything (greeting handshake,
    // this computer only). A dead proxy is a clear refusal, not a fallback.
    if (!(await probeTorProxy(tor.socksPort))) throw new Error(torDown(tor.socksPort));
    agent = new SocksProxyAgent(`socks5h://127.0.0.1:${tor.socksPort}`);
  }
  let url = raw;
  for (let hop = 0; hop < 5; hop++) {
    const u = assertPublicUrl(url);
    if (isOnionUrl(u) && !tor.enabled) throw new Error(ONION_NEEDS_TOR);
    // Tor gets a longer leash: a cold onion connection first fetches the
    // service's descriptor and builds a rendezvous circuit, which takes
    // far longer than a clearnet handshake (measured: ~2 min on a cold,
    // proxied Tor; ~4 s warm). The direct path keeps its 20 s ceiling.
    const res = await getOnce(u, signal, agent, tor.enabled ? 120_000 : 20_000);
    if (res.status >= 300 && res.status < 400 && res.location) { url = new URL(res.location, url).toString(); continue; }
    return res;
  }
  throw new Error("Too many redirects.");
}

export async function safeFetch(raw: string, signal: AbortSignal): Promise<Fetched> {
  return fetchChain(raw, signal, TOR_DISABLED);
}

/** The web fetch for one run: through Tor when on, direct when off. */
export function makeWebFetch(tor: TorSettings): WebFetchFn {
  if (!tor.enabled) return safeFetch;
  return (url, signal) => fetchChain(url, signal, tor);
}

const webFetchTool = tool({
  name: "web_fetch",
  description: "Fetch a web page and return its readable text (links kept in brackets).",
  schema: z.object({ url: z.string().url() }),
  policy: { effects: ["egress"], targetArgs: ["url"] },
  outside: true,
  network: true,
  parallelSafe: true,
  async run({ url }, ctx) {
    const res = await (ctx.webFetch ?? safeFetch)(url, ctx.signal);
    const text = res.type.includes("html") ? htmlToText(res.body) : res.body;
    return `${res.status} ${url}\n${text.length > MAX_FETCH ? text.slice(0, MAX_FETCH) + "\n[… cut]" : text}`;
  },
});

export function parseDuckDuckGo(html: string): Array<{ title: string; url: string; snippet: string }> {
  const out: Array<{ title: string; url: string; snippet: string }> = [];
  // One block per result; title and snippet are found inside each block separately.
  for (const block of html.split(/<div[^>]+class="[^"]*\bresult\b[^"]*"/).slice(1)) {
    const a = /<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/.exec(block)
      ?? /<a[^>]*href="([^"]+)"[^>]*class="[^"]*result__a[^"]*"[^>]*>([\s\S]*?)<\/a>/.exec(block);
    if (!a) continue;
    let href = a[1]!.replace(/&amp;/g, "&");
    const ud = /[?&]uddg=([^&]+)/.exec(href);
    if (ud) href = decodeURIComponent(ud[1]!);
    if (href.startsWith("//")) href = `https:${href}`;
    if (/duckduckgo\.com\/y\.js/.test(href)) continue; // ads
    const sn = /class="[^"]*result__snippet[^"]*"[^>]*>([\s\S]*?)<\/(?:a|div|td)>/.exec(block);
    out.push({ title: htmlToText(a[2]!), url: href, snippet: htmlToText(sn?.[1] ?? "") });
    if (out.length >= 8) break;
  }
  return out;
}

export type SearchResult = { title: string; url: string; snippet: string };

/**
 * DuckDuckGo sometimes answers a direct fetch with an HTTP 202 bot-challenge
 * page ("bots use DuckDuckGo too", anomaly-modal markup) instead of results.
 * That page parses to zero results, so challenge and genuine emptiness must
 * be told apart explicitly — a challenge means "try the next provider".
 */
export function isDuckDuckGoChallenge(html: string, status: number): boolean {
  return status === 202 || html.includes("anomaly-modal") || html.includes("bots use DuckDuckGo too");
}

export function parseBing(html: string): SearchResult[] {
  const out: SearchResult[] = [];
  // One <li class="b_algo"> per organic result; title link and snippet inside.
  for (const block of html.split(/<li[^>]+class="[^"]*\bb_algo\b[^"]*"/).slice(1)) {
    const a = /<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/.exec(block);
    if (!a) continue;
    let href = a[1]!.replace(/&amp;/g, "&");
    // Bing wraps result links in a ck/a redirect whose u= param is "a1" + base64url(target).
    const u = /[?&]u=([^&]+)/.exec(href);
    if (/bing\.com\/ck\/a/.test(href) && u?.[1]?.startsWith("a1")) {
      try { href = Buffer.from(u[1].slice(2).replace(/-/g, "+").replace(/_/g, "/"), "base64").toString("utf8"); } catch { /* keep the redirect href */ }
    }
    if (/bing\.com\/ck\/a/.test(href)) continue; // undecodable redirect: not a usable link
    const sn = /<div[^>]+class="[^"]*\bb_caption\b[^"]*"[^>]*>[\s\S]*?<p[^>]*>([\s\S]*?)<\/p>/.exec(block) ?? /<p[^>]*>([\s\S]*?)<\/p>/.exec(block);
    out.push({ title: htmlToText(a[2]!), url: href, snippet: htmlToText(sn?.[1] ?? "") });
    if (out.length >= 8) break;
  }
  return out;
}

export function isBingChallenge(html: string, status: number): boolean {
  return html.includes("b_captcha") || /please solve the challenge/i.test(html);
}

export function parseBrave(html: string): SearchResult[] {
  const out: SearchResult[] = [];
  // One <div class="snippet" data-type="web"> per organic result.
  for (const block of html.split(/<div[^>]+data-type="web"/).slice(1)) {
    const a = /<a[^>]*href="(https?:[^"]+)"[^>]*>/.exec(block);
    if (!a) continue;
    const t = /<div[^>]+class="[^"]*\btitle\b[^"]*"[^>]*>([\s\S]*?)<\/div>/.exec(block);
    if (!t) continue;
    const sn = /generic-snippet[\s\S]*?<div[^>]+class="[^"]*\bcontent\b[^"]*"[^>]*>([\s\S]*?)<\/div>/.exec(block);
    out.push({ title: htmlToText(t[1]!), url: a[1]!.replace(/&amp;/g, "&"), snippet: htmlToText(sn?.[1] ?? "") });
    if (out.length >= 8) break;
  }
  return out;
}

export function isBraveChallenge(html: string, status: number): boolean {
  return /<title>[^<]*captcha/i.test(html) || html.includes("search-captcha");
}

export interface SearchProvider {
  name: string;
  buildUrl(query: string): string;
  parse(html: string): SearchResult[];
  challenge(html: string, status: number): boolean;
}

/**
 * Search providers, in order. DuckDuckGo first (the long-standing default);
 * Bing and Brave are independent fallbacks for networks where DuckDuckGo
 * bot-walls direct fetches. Every fetch goes through safeFetch — same SSRF
 * guard, same redirect policy, no proxy, no API keys.
 */
export const SEARCH_PROVIDERS: SearchProvider[] = [
  {
    name: "DuckDuckGo",
    buildUrl: (q) => `https://html.duckduckgo.com/html/?q=${encodeURIComponent(q)}`,
    parse: parseDuckDuckGo,
    challenge: isDuckDuckGoChallenge,
  },
  {
    name: "Bing",
    buildUrl: (q) => `https://www.bing.com/search?q=${encodeURIComponent(q)}`,
    parse: parseBing,
    challenge: isBingChallenge,
  },
  {
    name: "Brave",
    buildUrl: (q) => `https://search.brave.com/search?q=${encodeURIComponent(q)}`,
    parse: parseBrave,
    challenge: isBraveChallenge,
  },
];

export interface WebSearchOutcome {
  results: SearchResult[];
  /** Provider that produced the results, or null when none did. */
  provider: string | null;
  /** Providers that were challenged, errored or unreachable, in order. */
  blocked: string[];
  text: string;
}

/**
 * The fallback chain: first provider yielding real results wins. A challenge
 * page or a failed fetch falls through to the next provider — it is NOT
 * reported as "no results". The honest empty message comes only after every
 * provider has been tried.
 */
export async function runWebSearch(
  query: string,
  signal: AbortSignal,
  fetchFn: (url: string, signal: AbortSignal) => Promise<Fetched> = safeFetch,
): Promise<WebSearchOutcome> {
  const blocked: string[] = [];
  let sawGenuineEmpty = false;
  for (const p of SEARCH_PROVIDERS) {
    let res: Fetched;
    try {
      res = await fetchFn(p.buildUrl(query), signal);
    } catch (err) {
      if (signal.aborted) throw err;
      blocked.push(p.name);
      continue;
    }
    if (res.status >= 400 || p.challenge(res.body, res.status)) { blocked.push(p.name); continue; }
    const results = p.parse(res.body);
    if (results.length) {
      return {
        results,
        provider: p.name,
        blocked,
        text: results.map((r, i) => `${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}`).join("\n"),
      };
    }
    sawGenuineEmpty = true;
  }
  const allBlocked = blocked.length === SEARCH_PROVIDERS.length && !sawGenuineEmpty;
  const text = allBlocked
    ? `No results for "${query}". Do not make any up. (DuckDuckGo, Bing and Brave were all tried; none returned results — they may be unreachable or blocking automated requests on this network.)`
    : `No results for "${query}". Do not make any up.`;
  return { results: [], provider: null, blocked, text };
}

const webSearchTool = tool({
  name: "web_search",
  description: "Search the web (DuckDuckGo first, then Bing, then Brave if a provider is blocked). Returns titles, links and snippets. If nothing is found, say so; never invent results.",
  schema: z.object({ query: z.string().min(1) }),
  policy: { effects: ["egress"], egressArgs: ["query"] },
  outside: true,
  network: true,
  parallelSafe: true,
  async run({ query }, ctx) {
    const outcome = await runWebSearch(query, ctx.signal, ctx.webFetch ?? safeFetch);
    return outcome.text;
  },
});

const browseTaskTool = tool({
  name: "browse_task",
  description:
    "Browse autonomously for a goal: starting from a search (or a start URL), fetch pages in full and follow their outbound links within a step budget, then return a structured report. Every fact in the report comes from a page actually fetched in full and carries that page's URL and content hash; search snippets are labelled as snippets and are never page content or evidence. A page that cannot be fetched is reported as FAILED — say so, never fill the gap from a snippet. Reading only: no forms, no clicks, no purchases. .onion links are refused (no Tor transport in this build).",
  schema: z.object({
    goal: z.string().min(1).max(2000).describe("What to find out by browsing"),
    start_url: z.string().url().optional().describe("Start here instead of searching"),
    search_query: z.string().min(1).max(500).optional().describe("Search query to seed the task (defaults to the goal)"),
    max_steps: z.number().int().min(1).max(BROWSE_MAX_STEPS).optional().describe(`Page-fetch budget (default ${BROWSE_DEFAULT_STEPS}, hard cap ${BROWSE_MAX_STEPS})`),
  }),
  policy: { effects: ["egress"], egressArgs: ["goal", "search_query"], targetArgs: ["start_url"] },
  outside: true,
  network: true,
  async run({ goal, start_url, search_query, max_steps }, ctx) {
    const report = await runBrowseTask(
      { goal, startUrl: start_url, searchQuery: search_query, maxSteps: max_steps },
      {
        fetchFn: safeFetch,
        searchFn: async (q, signal) => runWebSearch(q, signal),
        htmlToText,
        signal: ctx.signal,
      },
    );
    return renderBrowseReport(report);
  },
});

// ---------- desktop ----------

const openUrlTool = tool({
  name: "open_url",
  description: "Open a link in the owner's default browser.",
  schema: z.object({ url: z.string().url() }),
  policy: { effects: ["mutate", "egress"], targetArgs: ["url"] },
  network: true,
  async run({ url }) {
    await os.openUrl(url);
    return `Opened ${url}.`;
  },
});

const openAppTool = tool({
  name: "open_app",
  description: "Start an application by name (e.g. \"Spotify\", \"Terminal\", \"firefox\").",
  schema: z.object({ name: z.string().min(1).max(80) }),
  policy: { effects: ["mutate"], targetArgs: ["name"] },
  async run({ name }) {
    try {
      await os.openApp(name);
    } catch {
      throw new Error(`I couldn't find an app called "${name}" on this computer.`);
    }
    return `Started ${name}.`;
  },
});

const clipboardReadTool = tool({
  name: "clipboard_read",
  description: "Read the text on the clipboard.",
  schema: z.object({}),
  policy: { effects: ["read"] },
  parallelSafe: true,
  async run() {
    const t = await os.clipboardRead();
    return t ? t.slice(0, MAX_READ) : "The clipboard is empty.";
  },
});

const clipboardWriteTool = tool({
  name: "clipboard_write",
  description: "Put text on the clipboard.",
  schema: z.object({ text: z.string() }),
  policy: { effects: ["mutate"], lastsBeyondRequest: true },
  async run({ text }) {
    await os.clipboardWrite(text);
    return `Copied ${text.length} characters.`;
  },
});

const notifyTool = tool({
  name: "notify",
  description: "Show a desktop notification.",
  schema: z.object({ title: z.string().max(120), message: z.string().max(500) }),
  policy: { effects: ["mutate"] },
  async run({ title, message }) {
    await os.notify(title, message);
    return "Notification shown.";
  },
});

/**
 * Inbound notifications, the counterpart to `notify` above (which is
 * outbound and untouched). The unbound tool has no inbox; the runtime binds
 * its NotificationInbox in toolsFor, the same way Tor binds its fetch. What
 * it returns has already passed the three-gate filter at intake — this tool
 * never sees a dropped notification — and each notification is delivered
 * exactly once.
 */
const readNotificationsTool = tool({
  name: "read_notifications",
  description:
    "Read new inbound notifications that passed the notification filter (allowed apps only, outside quiet hours, under the per-app burst limit) and have not been delivered yet. Each is shown once. Use it to answer \"what did I miss?\".",
  schema: z.object({}),
  policy: { effects: ["read"] },
  parallelSafe: true,
  async run() {
    return "No notification inbox is set up on this computer.";
  },
});

/** Bind read_notifications to a real inbox (draining its file drop first, via takePending). */
export function bindReadNotifications(inbox: NotificationInbox): Tool {
  return {
    ...readNotificationsTool,
    run: async () => formatPending(inbox.takePending()),
  } as Tool;
}

const screenshotTool = tool({
  name: "screenshot",
  description: "Take a screenshot of the whole screen and save it in the workspace. Returns the file path.",
  schema: z.object({}),
  policy: { effects: ["read"] },
  async run(_a, ctx) {
    await mkdir(path.join(ctx.workspace, "screenshots"), { recursive: true });
    const file = path.join(ctx.workspace, "screenshots", `screen-${new Date().toISOString().replace(/[:.]/g, "-")}.png`);
    await os.screenshot(file);
    return `Saved ${file}.`;
  },
});

const seeScreenTool = tool({
  name: "look_at_screen",
  description: "Take a screenshot and have a vision model describe it or answer a question about what's on screen.",
  schema: z.object({ question: z.string().max(500).optional() }),
  policy: { effects: ["read"] },
  network: false,
  async run({ question }, ctx) {
    if (!ctx.vision) throw new Error("No vision model is set up. Set a local vision model in config (vision.model), or switch to the cloud brain.");
    const png = await os.screenshotBuffer();
    return ctx.vision.describe([{ data: png.toString("base64"), mediaType: "image/png" }], question || "Describe what is on the screen.", ctx.signal);
  },
});

const seeImageTool = tool({
  name: "look_at_image",
  description: "Have a vision model describe an image file (png/jpeg) or answer a question about it.",
  schema: z.object({ path: z.string(), question: z.string().max(500).optional() }),
  policy: { effects: ["read"], pathArgs: ["path"] },
  async run({ path: p, question }, ctx) {
    if (!ctx.vision) throw new Error("No vision model is set up. Set a local vision model in config (vision.model), or switch to the cloud brain.");
    const abs = resolvePath(p, ctx.cwd);
    const buf = await readFile(abs);
    if (buf.length > 12 * 1024 * 1024) throw new Error("That image is too large (over 12 MB).");
    const mediaType = /\.jpe?g$/i.test(abs) ? "image/jpeg" as const : "image/png" as const;
    return ctx.vision.describe([{ data: buf.toString("base64"), mediaType }], question || "Describe this image.", ctx.signal);
  },
});

const controlScreenTool = tool({
  name: "control_screen",
  description:
    "Type text, press a key combo, or click at a point on the screen. Experimental; off unless the owner turned it on. Every action asks the owner first. Use look_at_screen to see before acting.",
  schema: z.object({
    action: z.enum(["type", "key", "click"]),
    text: z.string().max(2000).optional().describe("for type"),
    keys: z.string().max(40).optional().describe("for key, e.g. ctrl+c, Return, Tab"),
    x: z.number().int().min(0).max(20000).optional(),
    y: z.number().int().min(0).max(20000).optional(),
    button: z.enum(["left", "right", "middle"]).optional(),
  }),
  // Experimental and far-reaching: a confirm card for every single action.
  policy: { effects: ["mutate"], alwaysConfirm: true },
  async run({ action, text, keys, x, y, button }) {
    if (action === "type") {
      if (!text) throw new Error("Nothing to type.");
      await os.screenType(text);
      return `Typed ${text.length} characters.`;
    }
    if (action === "key") {
      if (!keys) throw new Error("No key combo given.");
      await os.screenKey(keys);
      return `Pressed ${keys}.`;
    }
    if (x === undefined || y === undefined) throw new Error("Click needs x and y.");
    await os.screenClick(x, y, button ?? "left");
    return `Clicked at ${x}, ${y}.`;
  },
});

// ---------- voice ----------

const transcribeAudioTool = tool({
  name: "transcribe_audio",
  description: "Transcribe an audio file (wav/mp3/m4a/ogg…) to text with the owner's whisper-class speech-to-text server. The audio is sent to the server set in config (voice.sttBaseUrl); when none is set up, this says so.",
  schema: z.object({
    path: z.string().describe("Audio file path; ~ is the home folder"),
    language: z.string().max(8).optional().describe("Spoken language code, e.g. \"en\"; omit to auto-detect"),
  }),
  policy: { effects: ["read"], pathArgs: ["path"] },
  async run({ path: p, language }, ctx) {
    if (!ctx.transcriber) {
      throw new Error("Speech-to-text is not set up. Set voice.sttBaseUrl in config to an OpenAI-compatible transcription server (a whisper.cpp / faster-whisper server you run), or, with offlineOnly on, to one on this computer (a loopback address).");
    }
    const abs = resolvePath(p, ctx.cwd);
    const buf = await readFile(abs);
    if (buf.length > 25 * 1024 * 1024) throw new Error("That audio file is too large (over 25 MB).");
    const text = await ctx.transcriber.transcribe(buf, path.basename(abs), { language, signal: ctx.signal });
    return `Transcript of ${abs}:\n${text}`;
  },
});

const speakTool = tool({
  name: "speak",
  description: "Speak text aloud with the computer's own text-to-speech voice. Voice only ever speaks finished answers: the text must end in terminal punctuation (. ! ? …) — unfinished text is refused, never clipped. With out_file, the speech is written to an audio file instead of played.",
  schema: z.object({
    text: z.string().min(1).max(4000),
    out_file: z.string().optional().describe("Write the speech to this audio file instead of playing it"),
  }),
  policy: { effects: ["mutate"], pathArgs: ["out_file"] },
  async run({ text, out_file }, ctx) {
    if (out_file) {
      const abs = resolvePath(out_file, ctx.workspace);
      await mkdir(path.dirname(abs), { recursive: true });
      const r = await speakVoice(text, { outFile: abs });
      return `Saved speech (${r.chars} characters, via ${r.backend}) to ${abs}.`;
    }
    const r = await speakVoice(text);
    return `Spoke ${r.chars} characters (via ${r.backend}).`;
  },
});

const systemInfoTool = tool({
  name: "system_info",
  description: "This computer's OS, CPU, memory, uptime and home folder.",
  schema: z.object({}),
  policy: { effects: ["read"] },
  parallelSafe: true,
  async run() {
    const gb = (n: number) => `${(n / 1024 ** 3).toFixed(1)} GB`;
    return [
      `OS: ${platform()} ${release()} (${arch()})`,
      `Host: ${hostname()}`,
      `CPU: ${cpus()[0]?.model ?? "?"} × ${cpus().length}`,
      `Memory: ${gb(freemem())} free of ${gb(totalmem())}`,
      `Up: ${(uptime() / 3600).toFixed(1)} h`,
      `Home: ${homedir()}`,
      `Time: ${new Date().toString()}`,
    ].join("\n");
  },
});

// ---------- memory ----------

const rememberTool = tool({
  name: "remember",
  description:
    "Save a short fact about the owner or their world for later (one fact per call). When verify-before-store is on, the fact is held UNVERIFIED in the pending store instead: give source and source_text (the text the fact came from) so it can be checked; it becomes a real memory only after the source check passes and it is echoed or the owner confirms it.",
  schema: z.object({
    fact: z.string().min(2).max(500),
    source: z.string().max(200).optional().describe("Where the fact came from (a message, tool, page or person)"),
    source_text: z.string().max(2000).optional().describe("The source text the fact was read from, for verification"),
  }),
  policy: { effects: ["mutate"], lastsBeyondRequest: true },
  async run({ fact, source, source_text }, ctx) {
    if (ctx.echolalia) {
      const out = await ctx.echolalia.submit(fact, { source, sourceText: source_text }, ctx.embedder);
      return out.message;
    }
    const f = ctx.memory.remember(fact);
    return `Remembered (${f.id}): ${f.text}`;
  },
});

const memoryPendingTool = tool({
  name: "memory_pending",
  description:
    "Inspect verify-before-store memory: list pending (UNVERIFIED) and quarantined candidate facts. These are NOT remembered facts — never present them as fact. Empty status lists both.",
  schema: z.object({ status: z.enum(["pending", "quarantined"]).optional() }),
  policy: { effects: ["read"] },
  parallelSafe: true,
  async run({ status }, ctx) {
    if (!ctx.echolalia) return "Verify-before-store is off, so there is no pending store in use. Facts go straight to memory with the remember tool.";
    const rows = status ? ctx.echolalia.list(status) : [...ctx.echolalia.list("pending"), ...ctx.echolalia.list("quarantined")];
    return rows.length ? rows.map(formatEntry).join("\n") : "No pending or quarantined candidate facts.";
  },
});

const memoryDecideTool = tool({
  name: "memory_decide",
  description:
    "The owner's decision on a pending/quarantined candidate fact (see memory_pending for ids): promote makes it a real remembered fact immediately; drop discards it.",
  schema: z.object({ id: z.string().min(1), action: z.enum(["promote", "drop"]) }),
  policy: { effects: ["mutate"], lastsBeyondRequest: true },
  async run({ id, action }, ctx) {
    if (!ctx.echolalia) return "Verify-before-store is off, so there is no pending store in use.";
    const e = action === "promote" ? ctx.echolalia.promoteByOwner(id) : ctx.echolalia.drop(id);
    if (!e) return `No pending entry with id ${id}.`;
    return action === "promote"
      ? `Promoted (${e.id} -> fact ${e.factId}): ${e.claim}`
      : `Dropped (${e.id}): ${e.claim}`;
  },
});

const recallTool = tool({
  name: "recall",
  description: "Search what you remember about the owner. Empty query lists recent facts.",
  schema: z.object({ query: z.string() }),
  policy: { effects: ["read"] },
  parallelSafe: true,
  async run({ query }, ctx) {
    const hits = await ctx.memory.search(query, ctx.embedder);
    return hits.length ? hits.map((f) => `(${f.id}) ${f.text}`).join("\n") : "Nothing remembered about that.";
  },
});

const forgetTool = tool({
  name: "forget",
  description: "Forget facts matching the words (or an id). They go to the trash first.",
  schema: z.object({ query: z.string().min(1) }),
  policy: { effects: ["mutate"] },
  async run({ query }, ctx) {
    const gone = ctx.memory.forget(query);
    return gone.length ? `Forgot: ${gone.map((f) => f.text).join("; ")}` : "Nothing matched; nothing forgotten.";
  },
});

export const ALL_TOOLS: Tool[] = [
  readFileTool, listDirTool, findFilesTool, writeFileTool, runCommandTool,
  webSearchTool, webFetchTool, browseTaskTool, openUrlTool, openAppTool,
  clipboardReadTool, clipboardWriteTool, notifyTool, readNotificationsTool, screenshotTool, seeScreenTool, seeImageTool, controlScreenTool, systemInfoTool,
  transcribeAudioTool, speakTool,
  rememberTool, recallTool, forgetTool, memoryPendingTool, memoryDecideTool,
  ...shoppingTools(),
];

export function toolsFor(opts: { offlineOnly: boolean; screenControl: boolean; cloudVision?: boolean; tor?: TorSettings; ttsEnabled?: boolean; notifications?: NotificationInbox }): Tool[] {
  return ALL_TOOLS.filter((t) => {
    if (opts.offlineOnly && t.network) return false;
    if (t.name === "control_screen" && !opts.screenControl) return false;
    if (t.name === "speak" && opts.ttsEnabled === false) return false;
    return true;
  }).map((t) => {
    let out = t;
    // The notification inbox is the runtime's; bind it so read_notifications
    // drains the real queue instead of the unbound placeholder.
    if (opts.notifications && out.name === "read_notifications") {
      out = bindReadNotifications(opts.notifications);
    }
    // With cloud vision, a screenshot or image leaves the computer, so confirm each look.
    if (opts.cloudVision && (out.name === "look_at_screen" || out.name === "look_at_image")) {
      out = { ...out, policy: { ...out.policy, alwaysConfirm: true } };
    }
    // Tor is transport, not a new tool: the same web search/fetch, fetching
    // through the proxy. The binding is built per toolsFor call — and the
    // agent asks for its tools per run — so a settings toggle takes effect
    // on the next run without a restart. Offline-only still wins above:
    // with it on, these tools are filtered out before this point.
    if (opts.tor?.enabled && (out.name === "web_search" || out.name === "web_fetch")) {
      const webFetch = makeWebFetch(opts.tor);
      const orig = out;
      out = {
        ...orig,
        description: orig.name === "web_fetch"
          ? `${orig.description} Tor is on: the request goes through Tor, and .onion addresses work.`
          : orig.description,
        run: (args, ctx) => orig.run(args, { ...ctx, webFetch }),
      };
    }
    return out;
  });
}
