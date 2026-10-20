/**
 * The difficulty router — dual-architecture routing for every run, ported
 * from the phone/python reference (features/router.py) plus the patent
 * Claim 10 processing matrix (Heuristic vs Literal).
 *
 * Two independent, deterministic decisions, no model call, explainable:
 *
 * - Tier (how much thinking the run gets), faithful to router.py:
 *     voice channel            -> fast  (fully finished, never truncated)
 *     fast command (one-shot)  -> fast  (no model run at all; still labelled)
 *     research channel, or an explicit deep-research ask ("deep research",
 *     "investigate", "thorough", "exhaustive", "write a report")
 *                              -> deep  (full thinking budget)
 *     long/complex question    -> deep  (>500 chars, or a "?" with >40 words
 *                                         — router.py's exact condition,
 *                                         operator precedence included)
 *     everything else          -> light (standard chat, a little thinking)
 *
 * - Mode (how the answer should be processed, Claim 10):
 *     literal   — exactness-critical asks: verbatim recall, quotes,
 *                 numbers/calculation, code, step-by-step instructions.
 *                 The answer must reproduce or compute exactly.
 *     heuristic — open, creative, summary or exploratory asks, and the
 *                 default. The answer may range, paraphrase and judge.
 *
 * The route (tier + mode + effort + reason) travels with the run: it is
 * emitted in the run_start event, kept on the agent as the last route
 * (surfaced in gateway state), and attached to the run's transcript turns.
 * `effort` is the provider thinking knob the tier maps to: the cloud brain's
 * output_config.effort, and reasoning_effort on OpenAI-compatible local
 * servers that accept it.
 */

export type RouteTier = "fast" | "light" | "deep";
export type RouteMode = "heuristic" | "literal";
export type RouteChannel = "voice" | "cli" | "chat" | "research";
export type RouteEffort = "low" | "medium" | "high";

export interface Route {
  tier: RouteTier;
  mode: RouteMode;
  effort: RouteEffort;
  /** Why, in plain words: "<tier reason>; <mode reason>". Travels with the answer. */
  reason: string;
}

export interface RouteInput {
  channel?: RouteChannel;
  /** True when fastCommand() matched: the run short-circuits, tier is fast. */
  fastCommand?: boolean;
}

const EFFORT: Record<RouteTier, RouteEffort> = { fast: "low", light: "medium", deep: "high" };

/** router.py's _DEEP_PHRASES, unchanged. */
const DEEP_PHRASES = ["deep research", "investigate", "thorough", "exhaustive", "write a report"];

/** Claim 10 literal cues, checked in order; the first match names the reason. */
const LITERAL_CUES: Array<{ re: RegExp; why: string }> = [
  { re: /\bverbatim\b|word[- ]for[- ]word|exact (words|wording|quote|text)|\bexactly\b|quote|quotation|repeat (it |that |back)|read (it )?back|what did \w+ say/, why: "verbatim/exact recall asked" },
  { re: /step[- ]by[- ]step|\binstructions\b|\bprocedure\b/, why: "step-by-step instructions asked" },
  { re: /```|\bcode\b|\bfunction\b|\bscript\b|\bprogram\b|\bdebug\b|\bsyntax\b|\bregex\b|\btypescript\b|\bjavascript\b|\bpython\b|\bsql\b/, why: "code ask — output must be exact" },
  { re: /\bcalculate\b|\bcompute\b|\bconvert\b|\bsum\b|\bhow much\b|\bhow many\b|\bpercent(age)?\b|%|\d+\s*[-+*/x×=]\s*\d+|\d[^?]*\?/, why: "numbers — exact values matter" },
];

/** Open/exploratory cues: heuristic with a named reason instead of the default. */
const HEURISTIC_CUES: Array<{ re: RegExp; why: string }> = [
  { re: /\bbrainstorm\b|\bidea(s)?\b|\bimagine\b|\bstory\b|\bpoem\b|\bcreative\b|make up|invent/, why: "creative ask" },
  { re: /\bsummar/, why: "summary ask — paraphrase is the point" },
  { re: /\bexplain\b|\boverview\b|\bexplore\b|\bwhat if\b|\bopinion\b|\bsuggest\b|\brecommend\b|pros and cons|compare/, why: "open/exploratory ask" },
];

function tierFor(message: string, channel: RouteChannel, fast: boolean): { tier: RouteTier; why: string } {
  const text = message.toLowerCase();
  if (fast) return { tier: "fast", why: "fast command: one-shot, no model run" };
  if (channel === "voice") return { tier: "fast", why: "voice: fast, fully finished, never truncated" };
  if (channel === "research" || DEEP_PHRASES.some((p) => text.includes(p))) {
    return { tier: "deep", why: "deep-research ask: full thinking budget" };
  }
  // router.py exactly: len > 500 OR ("?" in text AND words > 40).
  if (text.length > 500 || (text.includes("?") && text.split(/\s+/).filter(Boolean).length > 40)) {
    return { tier: "deep", why: "long/complex question" };
  }
  return { tier: "light", why: "standard chat: light thinking" };
}

function modeFor(message: string): { mode: RouteMode; why: string } {
  const text = message.toLowerCase();
  for (const cue of LITERAL_CUES) {
    if (cue.re.test(text)) return { mode: "literal", why: `literal: ${cue.why}` };
  }
  for (const cue of HEURISTIC_CUES) {
    if (cue.re.test(text)) return { mode: "heuristic", why: `heuristic: ${cue.why}` };
  }
  return { mode: "heuristic", why: "heuristic: open ask (default)" };
}

/** Route one request. Pure and deterministic: same input, same route. */
export function routeRequest(message: string, input: RouteInput = {}): Route {
  const channel = input.channel ?? "chat";
  const tier = tierFor(message, channel, input.fastCommand === true);
  const mode = modeFor(message);
  return {
    tier: tier.tier,
    mode: mode.mode,
    effort: EFFORT[tier.tier],
    reason: `${tier.why}; ${mode.why}`,
  };
}
