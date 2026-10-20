import { exec } from "node:child_process";
import { lookup as dnsLookup, type LookupOptions } from "node:dns";
import { appendFile, mkdir, readdir, readFile, stat, writeFile } from "node:fs/promises";
import http from "node:http";
import https from "node:https";
import { BlockList, isIP, type LookupFunction } from "node:net";
import { arch, cpus, freemem, homedir, hostname, platform, release, totalmem, uptime } from "node:os";
import path from "node:path";
import { z } from "zod";
import type { ToolPolicy } from "../gate/gate.js";
import { resolvePath } from "../gate/gate.js";
import type { Embedder } from "../embeddings.js";
import type { Memory } from "../memory.js";
import type { Vision } from "../vision.js";
import * as os from "./platform.js";
import { shoppingTools } from "./shopping.js";

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
  embedder: Embedder | null;
  vision: Vision | null;
  signal: AbortSignal;
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

const MAX_BODY = 3 * 1024 * 1024;

function getOnce(u: URL, signal: AbortSignal): Promise<Fetched & { location?: string }> {
  return new Promise((resolve, reject) => {
    const mod = u.protocol === "https:" ? https : http;
    const req = mod.request(u, {
      method: "GET",
      lookup: publicOnlyLookup as unknown as LookupFunction,
      signal: AbortSignal.any([signal, AbortSignal.timeout(20000)]),
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

export async function safeFetch(raw: string, signal: AbortSignal): Promise<Fetched> {
  let url = raw;
  for (let hop = 0; hop < 5; hop++) {
    const u = assertPublicUrl(url);
    const res = await getOnce(u, signal);
    if (res.status >= 300 && res.status < 400 && res.location) { url = new URL(res.location, url).toString(); continue; }
    return res;
  }
  throw new Error("Too many redirects.");
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
    const res = await safeFetch(url, ctx.signal);
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
    const outcome = await runWebSearch(query, ctx.signal);
    return outcome.text;
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
  description: "Save a short fact about the owner or their world for later (one fact per call).",
  schema: z.object({ fact: z.string().min(2).max(500) }),
  policy: { effects: ["mutate"], lastsBeyondRequest: true },
  async run({ fact }, ctx) {
    const f = ctx.memory.remember(fact);
    return `Remembered (${f.id}): ${f.text}`;
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
  webSearchTool, webFetchTool, openUrlTool, openAppTool,
  clipboardReadTool, clipboardWriteTool, notifyTool, screenshotTool, seeScreenTool, seeImageTool, controlScreenTool, systemInfoTool,
  rememberTool, recallTool, forgetTool,
  ...shoppingTools(),
];

export function toolsFor(opts: { offlineOnly: boolean; screenControl: boolean; cloudVision?: boolean }): Tool[] {
  return ALL_TOOLS.filter((t) => {
    if (opts.offlineOnly && t.network) return false;
    if (t.name === "control_screen" && !opts.screenControl) return false;
    return true;
  }).map((t) => {
    // With cloud vision, a screenshot or image leaves the computer, so confirm each look.
    if (opts.cloudVision && (t.name === "look_at_screen" || t.name === "look_at_image")) {
      return { ...t, policy: { ...t.policy, alwaysConfirm: true } };
    }
    return t;
  });
}
