/**
 * The NLI gate: a contradiction check before a claim reaches the owner.
 *
 * Faithful TypeScript port of the phone reference implementation
 * (features/nli_gate.py). The deterministic layer is the reference:
 *
 * - No evidence -> FAIL, closed. An unchecked numeric claim is not a pass.
 * - Direct contradiction: the claim and an evidence text carry the same
 *   numbers but opposite negation polarity -> FAIL.
 * - Number mismatch: the claim asserts a number and an evidence text
 *   carries numbers of its own, none of them the claim's -> FAIL.
 *   (The reference checks this per evidence text, in order, and returns on
 *   the first mismatch; this port keeps that exact behaviour.)
 * - The claim's numbers appear somewhere in the evidence -> PASS,
 *   "numbers corroborated".
 * - Anything else -> PASS, "no contradiction found (weak support)".
 *
 * A failed gate does not delete the claim — the caller routes the claim and
 * its evidence back for re-checking (see the agent loop).
 *
 * The optional model-backed layer sits behind the same interface and is
 * one-directional: it runs only on a claim the deterministic layer already
 * passed, and it can only add scrutiny (turn that pass into a fail when the
 * model reports a contradiction). A deterministic FAIL is never overridden
 * by the model, and a model error or an unparseable verdict changes nothing.
 */

/** The reference negation set, verbatim. Matching is on whole whitespace-separated words. */
export const NEGATIONS = new Set([
  "not", "no", "never", "n't", "none", "without", "isn't",
  "aren't", "wasn't", "weren't", "don't", "doesn't", "didn't",
  "cannot", "can't", "won't",
]);

/** Integers and decimals, as in the reference (`\d+(?:\.\d+)?`). */
const NUM_RE = /\d+(?:\.\d+)?/g;

export interface NliVerdict {
  passed: boolean;
  reason: string;
  /** Which layer produced the verdict. */
  source: "deterministic" | "model";
}

/** True when [text] contains a negation word (reference `_negated`). */
export function isNegated(text: string): boolean {
  return text.toLowerCase().split(/\s+/).some((w) => NEGATIONS.has(w));
}

/** The set of number strings in [text] (reference `_numbers`). */
export function numbersIn(text: string): Set<string> {
  return new Set(text.match(NUM_RE) ?? []);
}

function sameNumbers(a: Set<string>, b: Set<string>): boolean {
  return a.size === b.size && [...a].every((n) => b.has(n));
}

function overlaps(a: Set<string>, b: Set<string>): boolean {
  return [...a].some((n) => b.has(n));
}

/** Python `repr` of the first 80 chars, close enough for the reason string. */
function repr80(text: string): string {
  return `'${text.slice(0, 80)}'`;
}

/**
 * The deterministic checker, ported 1:1 in behaviour from
 * `check(claim, evidence_texts)` in features/nli_gate.py.
 */
export function checkClaim(claim: string, evidenceTexts: string[]): NliVerdict {
  if (!evidenceTexts.length) {
    return { passed: false, reason: "no evidence to check against", source: "deterministic" };
  }
  const claimNeg = isNegated(claim);
  const claimNums = numbersIn(claim);
  for (const ev of evidenceTexts) {
    const evNums = numbersIn(ev);
    // Direct contradiction: same numbers, opposite polarity.
    if (claimNums.size && sameNumbers(claimNums, evNums) && claimNeg !== isNegated(ev)) {
      return { passed: false, reason: `contradiction with evidence: ${repr80(ev)}`, source: "deterministic" };
    }
    // Claim asserts a number the evidence denies sharing.
    if (claimNums.size && !overlaps(claimNums, evNums) && evNums.size) {
      return { passed: false, reason: `number mismatch with evidence: ${repr80(ev)}`, source: "deterministic" };
    }
  }
  // Support: claim's key numbers appear somewhere in evidence.
  if (claimNums.size && evidenceTexts.some((ev) => overlaps(claimNums, numbersIn(ev)))) {
    return { passed: true, reason: "numbers corroborated", source: "deterministic" };
  }
  return { passed: true, reason: "no contradiction found (weak support)", source: "deterministic" };
}

/**
 * Checkable claims in a final answer, by the loop's explicit extraction rule:
 * a sentence is checkable when it contains at least one number (the same
 * number pattern the checker uses). Sentences split on `.`/`!`/`?` followed
 * by whitespace, or on newlines; a trailing fragment counts as a sentence.
 */
export function extractCheckableClaims(answer: string): string[] {
  return answer
    .split(/(?<=[.!?])\s+|\n+/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0 && /\d+(?:\.\d+)?/.test(s));
}

/** What a model-backed entailment check returns. `null` means "no verdict" (error / unparseable). */
export type ModelEntailment = "entailed" | "contradicted" | "neutral";

/** The model-backed layer's seam: given a claim and its evidence, judge entailment. */
export type ModelChecker = (claim: string, evidenceTexts: string[]) => Promise<ModelEntailment | null>;

/**
 * Deterministic check first; only a deterministic PASS may be escalated to
 * [modelCheck], and only a model verdict of "contradicted" changes the
 * outcome. A deterministic FAIL is returned untouched — the model can add
 * scrutiny, never remove it.
 */
export async function checkClaimWithModel(
  claim: string,
  evidenceTexts: string[],
  modelCheck?: ModelChecker | null,
): Promise<NliVerdict> {
  const det = checkClaim(claim, evidenceTexts);
  if (!det.passed || !modelCheck) return det;
  let verdict: ModelEntailment | null;
  try {
    verdict = await modelCheck(claim, evidenceTexts);
  } catch {
    return det; // A broken model path must not change the deterministic verdict.
  }
  if (verdict === "contradicted") {
    return { passed: false, reason: "model contradiction: the entailment model judged the claim contradicted by the evidence", source: "model" };
  }
  return det;
}

/** The strict entailment prompt the local model is judged by. Exported so tests pin its shape. */
export function entailmentPrompt(claim: string, evidenceTexts: string[]): string {
  return [
    "You are a strict entailment checker. Judge ONLY whether the evidence contradicts the claim.",
    "Answer with exactly one word: ENTAILED, CONTRADICTED, or NEUTRAL.",
    "ENTAILED means the evidence supports the claim. CONTRADICTED means the evidence states the opposite of the claim. NEUTRAL means the evidence neither supports nor contradicts the claim.",
    "",
    `Claim: ${claim}`,
    "Evidence:",
    ...evidenceTexts.map((ev, i) => `${i + 1}. ${ev}`),
  ].join("\n");
}

/** Parse a model reply into an entailment verdict: the first of the three words wins; anything else is no verdict. */
export function parseEntailment(reply: string): ModelEntailment | null {
  const m = reply.toUpperCase().match(/\b(ENTAILED|CONTRADICTED|NEUTRAL)\b/);
  if (!m) return null;
  return m[1]!.toLowerCase() as ModelEntailment;
}

export interface LocalModelCheckerConfig {
  /** OpenAI-compatible base URL of the configured local provider (e.g. cfg.local.baseUrl). */
  baseUrl: string;
  model: string;
  /** Injectable for tests; defaults to global fetch. */
  fetchFn?: typeof fetch;
}

/**
 * A ModelChecker backed by the configured local provider: one non-streaming
 * chat completion with the strict entailment prompt, temperature 0. Any
 * transport or shape failure yields `null` (no verdict), never a pass/fail.
 */
export function createLocalModelChecker(cfg: LocalModelCheckerConfig): ModelChecker {
  const doFetch = cfg.fetchFn ?? fetch;
  return async (claim, evidenceTexts) => {
    const res = await doFetch(`${cfg.baseUrl.replace(/\/$/, "")}/chat/completions`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        model: cfg.model,
        stream: false,
        temperature: 0,
        messages: [{ role: "user", content: entailmentPrompt(claim, evidenceTexts) }],
      }),
    });
    if (!res.ok) return null;
    const body = (await res.json()) as { choices?: Array<{ message?: { content?: string } }> };
    const text = body.choices?.[0]?.message?.content;
    return text ? parseEntailment(text) : null;
  };
}

/** Check every claim against the evidence, in order, returning each claim with its verdict. */
export async function checkClaims(
  claims: string[],
  evidenceTexts: string[],
  modelCheck?: ModelChecker | null,
): Promise<Array<{ claim: string; verdict: NliVerdict }>> {
  const out: Array<{ claim: string; verdict: NliVerdict }> = [];
  for (const claim of claims) {
    out.push({ claim, verdict: await checkClaimWithModel(claim, evidenceTexts, modelCheck) });
  }
  return out;
}
