import type { Provider, ToolSpec, Turn } from "../providers/types.js";

/**
 * Compression: keep the conversation inside the model's context window.
 *
 * A local model server has a fixed window per conversation (llama.cpp:
 * `-c` divided by `-np`), and a request over it fails outright ("request
 * exceeds the available context size"). Before every model step the loop
 * estimates the request; near the window it shrinks old tool results, then
 * has the model summarise the older turns into one note and keeps the
 * recent ones verbatim. A server that still refuses is compressed harder
 * and retried once.
 *
 * Token counts are estimates (characters / 3 — deliberately pessimistic
 * for JSON-heavy turns); the overflow retry covers an estimate that is off.
 */

const CHARS_PER_TOKEN = 3;
export const TOOL_RESULT_KEEP = 3000;
export const TOOL_RESULT_HARD = 800;
const CUT_MARK = "\n…[cut by compression]";
export const SUMMARY_HEAD = "[Earlier conversation, compressed]";

export const estimateTokens = (chars: number): number => Math.ceil(chars / CHARS_PER_TOKEN);
export const turnsTokens = (turns: Turn[]): number => estimateTokens(turns.reduce((n, t) => n + JSON.stringify(t).length, 0));

/** The tokens a request spends before the conversation: system prompt and tool specs. */
export function fixedTokens(system: string, tools: ToolSpec[]): number {
  return estimateTokens(system.length + JSON.stringify(tools).length);
}

/** Tokens the conversation may use in a `window`-token context, leaving room for the answer. */
export function historyBudget(window: number, fixed: number): number {
  const reserve = Math.min(8192, Math.floor(window * 0.15));
  return Math.max(1024, window - fixed - reserve);
}

/** A model server refusing a request for being longer than its context. */
export function isOverflow(message: string): boolean {
  return /exceed_context_size|exceeds the available context|context (length|size|window)[^.]*exceed|maximum context length|prompt is too long|too many tokens/i.test(message);
}

/** The window size a server named in its overflow error, or 0. */
export function windowFromError(message: string): number {
  const m = message.match(/"n_ctx"\s*:\s*(\d+)/) ?? message.match(/context size \((\d+) tokens\)/i) ?? message.match(/maximum context length is (\d+)/i);
  return m ? Number(m[1]) : 0;
}

/** Cap every tool result outside the last `keepLast` turns at `max` characters. True when anything changed. */
export function capToolResults(turns: Turn[], max: number, keepLast: number): boolean {
  let changed = false;
  const end = Math.max(0, turns.length - keepLast);
  for (let i = 0; i < end; i++) {
    const t = turns[i]!;
    if (t.role !== "tool") continue;
    for (const r of t.results) {
      if (r.content.length > max + CUT_MARK.length) {
        r.content = r.content.slice(0, max) + CUT_MARK;
        changed = true;
      }
    }
  }
  return changed;
}

/**
 * Where the kept tail starts: the earliest index whose tail fits `keepTokens`,
 * moved later until it starts on a user or assistant turn (a tool result is
 * never separated from the call it answers). At least the last turn is kept.
 * 0 = nothing to summarise.
 */
export function chooseCut(turns: Turn[], keepTokens: number): number {
  let cut = turns.length - 1;
  let used = turnsTokens([turns[cut]!]);
  while (cut > 0) {
    const next = turnsTokens([turns[cut - 1]!]);
    if (used + next > keepTokens) break;
    used += next;
    cut--;
  }
  while (cut < turns.length - 1 && turns[cut]!.role === "tool") cut++;
  if (turns[cut]?.role === "tool") return 0;
  return cut;
}

/** The turns as plain text for the summariser, newest kept when over `maxChars`. */
export function renderForSummary(turns: Turn[], maxChars: number): string {
  const lines: string[] = [];
  for (const t of turns) {
    if (t.role === "user") lines.push(`OWNER: ${t.text}`);
    else if (t.role === "assistant") {
      if (t.text) lines.push(`JARVIS: ${t.text}`);
      for (const c of t.toolCalls) lines.push(`JARVIS called ${c.name} ${JSON.stringify(c.input).slice(0, 400)}`);
    } else {
      for (const r of t.results) lines.push(`RESULT of ${r.name}${r.isError ? " (error)" : ""}: ${r.content.slice(0, 1500)}`);
    }
  }
  const all = lines.join("\n");
  return all.length <= maxChars ? all : `…(oldest part omitted)\n${all.slice(all.length - maxChars)}`;
}

const SUMMARISER = [
  "You compress an AI assistant's conversation history so it can continue with less context.",
  "Write a dense summary in plain text. Keep: the owner's goals and requests, decisions made, facts learned,",
  "file paths, URLs, commands, names, numbers, what was done and its results, and anything left to do.",
  "Drop chit-chat and repetition. Do not invent anything. No preamble — start with the summary.",
].join(" ");

/** Ask the model for a summary of `turns`. Throws on a failed call; returns "" on an empty answer. */
export async function summarise(provider: Provider, turns: Turn[], window: number, signal: AbortSignal): Promise<string> {
  // The summariser's own request must fit the window too.
  const maxChars = Math.max(4000, (window > 0 ? window - 6000 : 40_000) * CHARS_PER_TOKEN * 0.8);
  const res = await provider.turn({
    system: SUMMARISER,
    turns: [{ role: "user", text: `Summarise this conversation history:\n\n${renderForSummary(turns, maxChars)}` }],
    tools: [],
    signal,
    effort: "low",
    onText: () => {},
    onThinking: () => {},
  });
  return res.text.trim();
}

/**
 * Replace turns[0, cut) with a summary note. The note is a user turn; when
 * the kept tail starts with a user turn an assistant acknowledgement goes
 * between them, so roles still alternate. `request` (the run's own request)
 * is carried verbatim when its turn is among those replaced. Returns how
 * many turns the head now has (to re-index anything after the cut).
 */
export function replaceHead(turns: Turn[], cut: number, summary: string, request: string | null): number {
  const parts = [SUMMARY_HEAD, summary || "(The earlier turns were dropped; no summary was available.)"];
  if (request) parts.push(`[The owner's current request, verbatim]\n${request.slice(0, 4000)}`);
  const head: Turn[] = [{ role: "user", text: parts.join("\n\n") }];
  if (turns[cut]?.role === "user") head.push({ role: "assistant", text: "Noted.", toolCalls: [] });
  turns.splice(0, cut, ...head);
  return head.length;
}
