#!/usr/bin/env node
import { createInterface } from "node:readline/promises";
import { Writable } from "node:stream";
import type { AgentEvent, RunHooks } from "./agent/loop.js";
import { accessToken, loadConfig, paths, saveConfig, type Config, type Paths } from "./config.js";
import { Runtime } from "./runtime.js";
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
  jarvis where          show where Jarvis keeps its files

Brain: set "brain" in config.json to "local" (default; any OpenAI-compatible
server such as Ollama) or "cloud" (Claude; needs ANTHROPIC_API_KEY or
cloud.apiKey). "offlineOnly": true keeps everything on this computer.`;

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
      if (e.type === "run_start") process.stdout.write(dim(`(${e.brain})\n`));
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

async function main(): Promise<void> {
  const [cmd = "help", ...rest] = process.argv.slice(2);
  const p = paths();
  if (cmd === "where") {
    console.log(Object.entries(p).map(([k, v]) => `${k.padEnd(10)} ${v}`).join("\n"));
    const keyState = loadConfig(p).cloud.apiKey
      ? "saved in config (jarvis set-key)"
      : process.env.ANTHROPIC_API_KEY
        ? "from ANTHROPIC_API_KEY — not saved (jarvis set-key saves it)"
        : "not set (jarvis set-key saves one)";
    console.log(`${"api key".padEnd(10)} ${keyState}`);
    return;
  }
  if (cmd === "help" || cmd === "--help" || cmd === "-h") { console.log(HELP); return; }

  const cfg = loadConfig(p);
  if (cmd === "set-key") {
    await setKey(p, cfg);
    return;
  }
  const rt = new Runtime(cfg, p);

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
    const text = rest.join(" ").trim() || pipedText;
    if (!text) { console.log('Usage: jarvis ask "what\'s in my Downloads folder?"'); rl.close(); return; }
    await rt.agent.run(text, hooks);
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
      await rt.agent.run(line, hooks);
    }
  }
  console.log(HELP);
  rl.close();
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : err);
  process.exit(1);
});
