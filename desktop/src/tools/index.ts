import { exec } from "node:child_process";
import { lookup } from "node:dns/promises";
import { appendFile, mkdir, readdir, readFile, stat, writeFile } from "node:fs/promises";
import { isIP } from "node:net";
import { arch, cpus, freemem, homedir, hostname, platform, release, totalmem, uptime } from "node:os";
import path from "node:path";
import { z } from "zod";
import type { ToolPolicy } from "../gate/gate.js";
import { resolvePath } from "../gate/gate.js";
import type { Memory } from "../memory.js";
import * as os from "./platform.js";

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
  policy: { effects: ["exec"], egressArgs: ["command"] },
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

function privateAddress(ip: string): boolean {
  if (isIP(ip) === 4) {
    const [a, b] = ip.split(".").map(Number) as [number, number];
    return a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127);
  }
  const l = ip.toLowerCase();
  return l === "::1" || l === "::" || l.startsWith("fc") || l.startsWith("fd") || l.startsWith("fe80") || l.startsWith("::ffff:127.") || l.startsWith("::ffff:10.") || l.startsWith("::ffff:192.168.");
}

/** Web tools may not reach this computer or the local network: that is where the gateway and other private services live. */
export async function assertPublicUrl(raw: string): Promise<URL> {
  const u = new URL(raw);
  if (u.protocol !== "http:" && u.protocol !== "https:") throw new Error("Only http and https links.");
  const host = u.hostname.replace(/^\[|\]$/g, "");
  if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) throw new Error("Web tools do not reach this computer or the local network.");
  const addrs = isIP(host) ? [{ address: host }] : await lookup(host, { all: true });
  if (addrs.some((a) => privateAddress(a.address))) throw new Error("Web tools do not reach this computer or the local network.");
  return u;
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

async function safeFetch(raw: string, signal: AbortSignal): Promise<Response> {
  let url = raw;
  for (let hop = 0; hop < 5; hop++) {
    await assertPublicUrl(url);
    const res = await fetch(url, {
      redirect: "manual",
      signal: AbortSignal.any([signal, AbortSignal.timeout(20000)]),
      headers: { "user-agent": "Mozilla/5.0 (Jarvis desktop agent)", accept: "text/html,application/json,text/plain;q=0.9,*/*;q=0.5" },
    });
    const loc = res.headers.get("location");
    if (res.status >= 300 && res.status < 400 && loc) { url = new URL(loc, url).toString(); continue; }
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
    const type = res.headers.get("content-type") ?? "";
    const body = await res.text();
    const text = type.includes("html") ? htmlToText(body) : body;
    return `${res.status} ${url}\n${text.length > MAX_FETCH ? text.slice(0, MAX_FETCH) + "\n[… cut]" : text}`;
  },
});

export function parseDuckDuckGo(html: string): Array<{ title: string; url: string; snippet: string }> {
  const out: Array<{ title: string; url: string; snippet: string }> = [];
  const rx = /<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>[\s\S]*?(?:class="result__snippet"[^>]*>([\s\S]*?)<\/a>)?/g;
  for (const m of html.matchAll(rx)) {
    let href = m[1]!.replace(/&amp;/g, "&");
    const ud = /[?&]uddg=([^&]+)/.exec(href);
    if (ud) href = decodeURIComponent(ud[1]!);
    if (href.startsWith("//")) href = `https:${href}`;
    if (/duckduckgo\.com\/y\.js/.test(href)) continue; // ads
    out.push({ title: htmlToText(m[2]!), url: href, snippet: htmlToText(m[3] ?? "") });
    if (out.length >= 8) break;
  }
  return out;
}

const webSearchTool = tool({
  name: "web_search",
  description: "Search the web (DuckDuckGo). Returns titles, links and snippets. If nothing is found, say so; never invent results.",
  schema: z.object({ query: z.string().min(1) }),
  policy: { effects: ["egress"], egressArgs: ["query"] },
  outside: true,
  network: true,
  parallelSafe: true,
  async run({ query }, ctx) {
    const res = await safeFetch(`https://html.duckduckgo.com/html/?q=${encodeURIComponent(query)}`, ctx.signal);
    const results = parseDuckDuckGo(await res.text());
    if (!results.length) return `No results for "${query}". Do not make any up.`;
    return results.map((r, i) => `${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}`).join("\n");
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
    await os.openApp(name);
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
  policy: { effects: ["mutate"] },
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
  policy: { effects: ["mutate"] },
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
    const hits = ctx.memory.recall(query);
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
  clipboardReadTool, clipboardWriteTool, notifyTool, screenshotTool, systemInfoTool,
  rememberTool, recallTool, forgetTool,
];

export function toolsFor(offlineOnly: boolean): Tool[] {
  return ALL_TOOLS.filter((t) => !(offlineOnly && t.network));
}
