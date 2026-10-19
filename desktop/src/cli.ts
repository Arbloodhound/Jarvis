#!/usr/bin/env node
import { createInterface } from "node:readline/promises";
import type { AgentEvent, RunHooks } from "./agent/loop.js";
import { accessToken, loadConfig, paths } from "./config.js";
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
  jarvis ask "request"  run one request here
  jarvis chat           talk in this terminal (Ctrl+C to quit)
  jarvis where          show where Jarvis keeps its files

Brain: set "brain" in config.json to "local" (default; any OpenAI-compatible
server such as Ollama) or "cloud" (Claude; needs ANTHROPIC_API_KEY or
cloud.apiKey). "offlineOnly": true keeps everything on this computer.`;

const dim = (s: string) => `\x1b[2m${s}\x1b[0m`;
const yellow = (s: string) => `\x1b[33m${s}\x1b[0m`;

function terminalHooks(rl: ReturnType<typeof createInterface>): RunHooks {
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
      const a = (await rl.question("  Allow? [y/N] ")).trim().toLowerCase();
      return a === "y" || a === "yes";
    },
  };
}

async function main(): Promise<void> {
  const [cmd = "help", ...rest] = process.argv.slice(2);
  const p = paths();
  if (cmd === "where") {
    console.log(Object.entries(p).map(([k, v]) => `${k.padEnd(10)} ${v}`).join("\n"));
    return;
  }
  if (cmd === "help" || cmd === "--help" || cmd === "-h") { console.log(HELP); return; }

  const cfg = loadConfig(p);
  const rt = new Runtime(cfg, p);

  if (cmd === "serve") {
    const token = accessToken(p);
    await startServer(rt, token, cfg.port);
    console.log(`Jarvis is listening on this computer only.\n\n  Open: http://127.0.0.1:${cfg.port}/#token=${token}\n\n  Brain: ${rt.provider().label}${cfg.offlineOnly ? " (offline only)" : ""}\n  Files: ${p.home}`);
    return;
  }

  const rl = createInterface({ input: process.stdin, output: process.stdout });
  process.on("SIGINT", () => {
    if (rt.agent.busy) { rt.agent.stop(); return; }
    rl.close();
    process.exit(0);
  });
  const hooks = terminalHooks(rl);
  if (cmd === "ask") {
    const text = rest.join(" ").trim();
    if (!text) { console.log('Usage: jarvis ask "what\'s in my Downloads folder?"'); rl.close(); return; }
    await rt.agent.run(text, hooks);
    rl.close();
    return;
  }
  if (cmd === "chat") {
    console.log(dim(`${rt.provider().label}. Ctrl+C stops a run, or quits when idle.`));
    for (;;) {
      const line = (await rl.question("\nyou › ")).trim();
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
