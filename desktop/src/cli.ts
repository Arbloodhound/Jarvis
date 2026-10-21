#!/usr/bin/env node
import { createInterface } from "node:readline/promises";
import { Writable } from "node:stream";
import path from "node:path";
import type { AgentEvent, RunHooks } from "./agent/loop.js";
import { readFile } from "node:fs/promises";
import { accessToken, loadConfig, paths, saveConfig, type Config, type Paths } from "./config.js";
import { EcholaliaStore, formatEntry } from "./echolalia.js";
import { AuditLog } from "./gate/audit.js";
import { Memory } from "./memory.js";
import { Runtime } from "./runtime.js";
import { probeTorProxy } from "./tools/index.js";
import { speak } from "./voice.js";
import { startServer } from "./server.js";

/**
 * jarvis serve        start the gateway and chat page (http://127.0.0.1:7337)
 * jarvis ask "..."    one request in this terminal
 * jarvis chat         a conversation in this terminal
 * jarvis where        print where config, memory and the audit log live
 */

const HELP = `Jarvis desktop

  jarvis serve          start the gateway + chat page
  jarvis ask "request"  run one request here (or pipe the request in)
  jarvis chat           talk in this terminal (Ctrl+C to quit)
  jarvis set-key        save the Claude API key to config (stays saved)
  jarvis say "text."    speak a finished sentence with the computer's voice
  jarvis transcribe f   transcribe an audio file (needs voice.sttBaseUrl)
  jarvis where          show where Jarvis keeps its files
  jarvis memory …       verify-before-store (echolalia) inspection:
                          pending | quarantined | all
                          submit "claim" [--source S] [--source-text T]
                          promote <id> | drop <id> | verify

Attaching context: 'jarvis ask "summarize this" --file notes.md --file chart.png'
attaches files (repeatable); in any request, @file:path, @folder:path,
@image:path and @url:https://… point Jarvis at context inline. Attachments
and references are read as data, not instructions, and ride the one request.
Conversations live in branches (fork and switch them on the serve page); the
terminal keeps working on whichever branch is active.

Brain: set "brain" in config.json to "local" (default; any OpenAI-compatible
server such as Ollama) or "cloud" (Claude; needs ANTHROPIC_API_KEY or
cloud.apiKey). "offlineOnly": true keeps everything on this computer.
"tor": {"enabled": true} sends web search and fetch through a local Tor
proxy (port 9050; 9150 for Tor Browser) — also a switch on the serve page —
and is the only way .onion addresses open.`;

const dim = (s: string) => `\x1b[2m${s}\x1b[0m`;
const yellow = (s: string) => `\x1b[33m${s}\x1b[0m`;

function terminalHooks(rl: ReturnType<typeof createInterface>, input: { closed: boolean }): RunHooks {
  let thinking = false;
  return {
    emit(e: AgentEvent) {
      if (e.type === "thinking") {
        if (!thinking) { process.stdout.write(dim("\n[thinking] ")); thinking = true; }
        process.stdout.write(dim(e.delta));
        return;
      }
      if (thinking && e.type !== "confirm") { process.stdout.write("\n"); thinking = false; }
      if (e.type === "run_start") process.stdout.write(dim(`(${e.brain} · route: ${e.route.tier}/${e.route.mode} — ${e.route.reason})\n`));
      else if (e.type === "text") process.stdout.write(e.delta);
      else if (e.type === "tool_start") process.stdout.write(dim(`\n→ ${e.name} ${JSON.stringify(e.args).slice(0, 160)}\n`));
      else if (e.type === "tool_end") process.stdout.write(dim(`  ${e.ok ? "✓" : "✗"} ${e.summary.split("\n")[0]?.slice(0, 160) ?? ""}\n`));
      else if (e.type === "warning") process.stdout.write(yellow(`\n! ${e.text}\n`));
      else if (e.type === "error") process.stdout.write(yellow(`\n✗ ${e.message}\n`));
      else if (e.type === "done") process.stdout.write("\n");
    },
    async confirm(card) {
      process.stdout.write(yellow(`\n? ${card.tool}: ${card.reason}\n  ${JSON.stringify(card.args).slice(0, 400)}\n`));
      // Piped stdin reaches EOF before a card appears, and rl.question on a
      // closed interface rejects ("readline was closed") and used to kill
      // the run. A card nobody can answer is a decline, said out loud.
      if (input.closed) {
        process.stdout.write(yellow("  Confirmation unavailable — the input is closed, so this is treated as declined.\n"));
        return false;
      }
      try {
        const a = (await rl.question("  Allow? [y/N] ")).trim().toLowerCase();
        return a === "y" || a === "yes";
      } catch {
        process.stdout.write(yellow("  Confirmation unavailable — the input closed, so this is treated as declined.\n"));
        return false;
      }
    },
  };
}

/** Read one line with echo muted, so a typed key never lands on screen, in scrollback, or in a log. */
function promptHidden(prompt: string): Promise<string> {
  return new Promise((resolve, reject) => {
    let muted = false;
    const sink = new Writable({
      write(chunk, _enc, cb) {
        if (!muted) process.stdout.write(chunk as Buffer);
        cb();
      },
    });
    const rl = createInterface({ input: process.stdin, output: sink, terminal: true });
    process.stdout.write(prompt);
    muted = true;
    rl.question("").then(
      (answer) => {
        muted = false;
        rl.close();
        process.stdout.write("\n");
        resolve(answer);
      },
      (err) => {
        rl.close();
        reject(err instanceof Error ? err : new Error(String(err)));
      },
    );
  });
}

/**
 * `jarvis set-key`: save the Claude API key into config.json so it stays
 * saved on this client (config writes are mode 0600). The key comes from
 * ANTHROPIC_API_KEY when set — so it never passes through the shell's
 * history or argv — else from a hidden terminal prompt. It is never
 * printed back, echoed, or logged; only the fact that it saved is said.
 */
async function setKey(p: Paths, cfg: Config): Promise<void> {
  let key = process.env.ANTHROPIC_API_KEY?.trim() ?? "";
  if (!key) {
    if (!process.stdin.isTTY) {
      console.error("No key to save: ANTHROPIC_API_KEY is not set and there is no terminal to type one at. Set ANTHROPIC_API_KEY and run `jarvis set-key` again, or run it in a terminal.");
      process.exitCode = 1;
      return;
    }
    key = (await promptHidden("Claude API key (typing is hidden): ")).trim();
  }
  if (!key) {
    console.error("No key entered — nothing was saved.");
    process.exitCode = 1;
    return;
  }
  cfg.cloud.apiKey = key;
  saveConfig(cfg, p);
  console.log(`Claude API key saved to ${p.config}. It stays saved for the cloud brain and cloud vision, and is never shown again.`);
}

/**
 * `jarvis memory …`: the owner's hands on the echolalia pending store.
 * Works whether or not verifyBeforeStore is on (inspection is always
 * available); promotions and quarantines are written to the audit log.
 */
async function memoryCommand(p: Paths, rest: string[]): Promise<void> {
  const audit = new AuditLog(p.audit);
  const memory = new Memory(p.memory);
  const store = new EcholaliaStore(p.pending, memory, (entry, decision) => {
    audit.write({ tool: "echolalia", decision, id: entry.id, claim: entry.claim, source: entry.source, echoes: entry.echoes, reasons: entry.reasons, via: "cli" });
  });
  const [sub = "pending", ...args] = rest;
  if (sub === "pending" || sub === "quarantined" || sub === "all") {
    const rows = sub === "all" ? store.list() : store.list(sub);
    console.log(rows.length ? rows.map(formatEntry).join("\n") : `No ${sub === "all" ? "" : `${sub} `}candidate facts.`);
    return;
  }
  if (sub === "submit") {
    const flag = (name: string): string | undefined => {
      const i = args.indexOf(`--${name}`);
      return i >= 0 ? args[i + 1] : undefined;
    };
    const claim = args.filter((a, i) => !a.startsWith("--") && args[i - 1] !== "--source" && args[i - 1] !== "--source-text").join(" ").trim();
    if (!claim) { console.error('Usage: jarvis memory submit "claim" [--source S] [--source-text T]'); process.exitCode = 1; return; }
    const out = await store.submit(claim, { source: flag("source"), sourceText: flag("source-text") });
    console.log(out.message);
    return;
  }
  if (sub === "promote" || sub === "drop") {
    const id = args[0] ?? "";
    const e = sub === "promote" ? store.promoteByOwner(id) : store.drop(id);
    if (!e) { console.error(`No pending entry with id ${id}.`); process.exitCode = 1; return; }
    console.log(sub === "promote" ? `Promoted (${e.id} -> fact ${e.factId}): ${e.claim}` : `Dropped (${e.id}): ${e.claim}`);
    return;
  }
  if (sub === "verify") {
    const changed = store.verifyAll();
    console.log(changed.length ? changed.map(formatEntry).join("\n") : "No status changes.");
    return;
  }
  console.error("Usage: jarvis memory pending|quarantined|all|submit|promote <id>|drop <id>|verify");
  process.exitCode = 1;
}

async function main(): Promise<void> {
  const [cmd = "help", ...rest] = process.argv.slice(2);
  const p = paths();
  if (cmd === "where") {
    console.log(Object.entries(p).map(([k, v]) => `${k.padEnd(10)} ${v}`).join("\n"));
    const cfgW = loadConfig(p);
    const keyState = cfgW.cloud.apiKey
      ? "saved in config (jarvis set-key)"
      : process.env.ANTHROPIC_API_KEY
        ? "from ANTHROPIC_API_KEY — not saved (jarvis set-key saves it)"
        : "not set (jarvis set-key saves one)";
    console.log(`${"api key".padEnd(10)} ${keyState}`);
    const torState = cfgW.tor.enabled
      ? `on — proxy 127.0.0.1:${cfgW.tor.socksPort} ${(await probeTorProxy(cfgW.tor.socksPort)) ? "reachable" : "NOT reachable"}`
      : "off";
    console.log(`${"tor".padEnd(10)} ${torState}`);
    return;
  }
  if (cmd === "help" || cmd === "--help" || cmd === "-h") { console.log(HELP); return; }

  const cfg = loadConfig(p);
  if (cmd === "set-key") {
    await setKey(p, cfg);
    return;
  }
  if (cmd === "memory") {
    await memoryCommand(p, rest);
    return;
  }
  const rt = new Runtime(cfg, p);

  if (cmd === "say") {
    // The direct voice path: the same finished-utterance rule and OS TTS
    // backend the speak tool uses. Failures are printed, never hidden.
    const text = rest.join(" ").trim();
    if (!text) { console.error('Usage: jarvis say "A finished sentence."'); process.exitCode = 1; return; }
    if (!cfg.voice.ttsEnabled) { console.error("Text-to-speech is off (voice.ttsEnabled is false in config)."); process.exitCode = 1; return; }
    const outIdx = rest.indexOf("--out");
    const outFile = outIdx >= 0 ? rest[outIdx + 1] : undefined;
    const spoken = outIdx >= 0 ? rest.filter((_, i) => i !== outIdx && i !== outIdx + 1).join(" ").trim() : text;
    try {
      const r = await speak(spoken, outFile ? { outFile: path.resolve(outFile) } : {});
      console.log(r.outFile ? `Saved speech (${r.chars} characters, via ${r.backend}) to ${r.outFile}.` : `Spoke ${r.chars} characters (via ${r.backend}).`);
    } catch (err) {
      console.error(err instanceof Error ? err.message : err);
      process.exitCode = 1;
    }
    return;
  }

  if (cmd === "transcribe") {
    const file = rest[0];
    if (!file) { console.error("Usage: jarvis transcribe <audio file>"); process.exitCode = 1; return; }
    const t = rt.transcriber();
    if (!t) {
      console.error("Speech-to-text is not set up. Set voice.sttBaseUrl in config to an OpenAI-compatible transcription server (a whisper.cpp / faster-whisper server you run); with offlineOnly on, only a loopback address is used.");
      process.exitCode = 1;
      return;
    }
    try {
      const buf = await readFile(path.resolve(file));
      console.log(await t.transcribe(buf, path.basename(file)));
    } catch (err) {
      console.error(err instanceof Error ? err.message : err);
      process.exitCode = 1;
    }
    return;
  }

  if (cmd === "serve") {
    const token = accessToken(p);
    const gw = await startServer(rt, token, cfg.port);
    console.log(`Jarvis is listening on this computer only.\n\n  Open: ${gw.url}\n\n  Brain: ${rt.provider().label}${cfg.offlineOnly ? " (offline only)" : ""}\n  Files: ${p.home}`);
    return;
  }

  // `echo "what time is it" | jarvis ask`: with no request on the command
  // line, the request is the piped input. Read it before readline is set up
  // on the same stream; the input is then at EOF, which confirm() handles.
  let pipedText = "";
  if (cmd === "ask" && !rest.join(" ").trim() && !process.stdin.isTTY) {
    for await (const chunk of process.stdin) pipedText += chunk;
    pipedText = pipedText.trim();
  }

  const rl = createInterface({ input: process.stdin, output: process.stdout });
  const input = { closed: pipedText !== "" };
  rl.on("close", () => { input.closed = true; });
  process.on("SIGINT", () => {
    if (rt.agent.busy) { rt.agent.stop(); return; }
    rl.close();
    process.exit(0);
  });
  const hooks = terminalHooks(rl, input);
  if (cmd === "ask") {
    // --file <path> (repeatable) attaches files to this one request.
    const files: Array<{ path: string; name: string }> = [];
    const words: string[] = [];
    for (let i = 0; i < rest.length; i++) {
      if (rest[i] === "--file" && rest[i + 1]) {
        const f = rest[++i]!;
        files.push({ path: path.resolve(f), name: path.basename(f) });
      } else words.push(rest[i]!);
    }
    const text = words.join(" ").trim() || pipedText;
    if (!text) { console.log('Usage: jarvis ask "what\'s in my Downloads folder?" [--file <path> …]'); rl.close(); return; }
    await rt.agent.run(text, hooks, await rt.ownerContext(text, files), { channel: "cli" });
    rt.syncConversation();
    rl.close();
    return;
  }
  if (cmd === "chat") {
    console.log(dim(`${rt.provider().label}. Ctrl+C stops a run, or quits when idle.`));
    for (;;) {
      let line: string;
      try {
        line = (await rl.question("\nyou › ")).trim();
      } catch {
        break; // Piped input ended: end the chat instead of dying on a closed readline.
      }
      if (!line) continue;
      await rt.agent.run(line, hooks, await rt.ownerContext(line), { channel: "cli" });
      rt.syncConversation();
    }
  }
  console.log(HELP);
  rl.close();
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : err);
  process.exit(1);
});
