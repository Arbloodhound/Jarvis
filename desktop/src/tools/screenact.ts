import { z } from "zod";
import type { Vision } from "../vision.js";
import type { Tool, ToolContext } from "./index.js";
import * as os from "./platform.js";

/**
 * SEE → ACT → VERIFY as one composed, self-checking screen action.
 *
 * `look_at_screen` and `control_screen` are separate primitives: the model
 * reads, then acts, and nothing in the app checks that the action had the
 * intended effect. This module composes them into the loop the owner asked
 * for, with precision grounding and an honest report:
 *
 * 1. GROUND — a screenshot goes to the vision model with a named target.
 *    The model must say what the target is and give its centre on a
 *    0–1000 scale (`X=<n>, Y=<n>`). The grounding read is quoted in the
 *    report, never silently trusted. The same read is asked to describe
 *    any human-verification challenge on screen (what it asks, and its
 *    state, e.g. whether a box looks checked) so the system's picture of
 *    the screen includes it.
 * 2. ACT — the action executes AT the grounded point (a click there, then
 *    optional typing / key presses). No grounded point, no action.
 * 3. VERIFY — a fresh screenshot is read and compared against a NAMED
 *    expected pattern. The report always shows the comparison (expected
 *    vs what the verify read actually said), never just "it worked".
 *
 * On a verify mismatch the loop re-grounds and retries the action ONCE,
 * then reports failure with both reads if it still does not match.
 */

function tool<S extends z.ZodType>(t: Tool<S>): Tool {
  return t as unknown as Tool;
}

// ---------- pure helpers (unit-tested) ----------

/** Width/height from a PNG's IHDR chunk (screenshots are always PNG here). */
export function pngSize(buf: Buffer): { width: number; height: number } | null {
  if (buf.length < 24 || buf.readUInt32BE(0) !== 0x89504e47) return null;
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

export interface GroundedPoint {
  /** Centre on the model's 0–1000 scale. */
  relX: number;
  relY: number;
  /** The same point in screenshot pixels. */
  x: number;
  y: number;
}

/**
 * Pull the grounded centre out of a vision read. Accepts the requested
 * `X=<n>, Y=<n>` form or a parenthesised `(n, n)` pair, both on the
 * 0–1000 scale the grounding question asks for. Anything else = not grounded.
 */
export function parseGrounding(read: string, width: number, height: number): GroundedPoint | null {
  const m =
    /x\s*[:=]\s*(\d{1,4})\D{0,12}?y\s*[:=]\s*(\d{1,4})/i.exec(read) ??
    /\(\s*(\d{1,4})\s*,\s*(\d{1,4})\s*\)/.exec(read);
  if (!m) return null;
  const relX = Math.min(1000, Math.max(0, Number(m[1])));
  const relY = Math.min(1000, Math.max(0, Number(m[2])));
  return { relX, relY, x: Math.round((relX / 1000) * width), y: Math.round((relY / 1000) * height) };
}

/** Case/whitespace-insensitive containment: does the verify read show the expected pattern? */
export function verifyMatch(read: string, expected: string): boolean {
  const norm = (s: string) => s.toLowerCase().replace(/\s+/g, " ").trim();
  return norm(read).includes(norm(expected));
}

export function groundingQuestion(target: string): string {
  return [
    `Look at this screenshot. Find this target: ${target}.`,
    "Describe what the target is and where it is on the screen.",
    "Then give the centre of the target on a 0-1000 scale for the whole screen (0,0 is the top-left corner, 1000,1000 the bottom-right), in exactly this form: X=<number>, Y=<number>.",
    "If the screen shows a human-verification challenge (for example a CAPTCHA, or an \"I'm not a robot\" checkbox), also describe what it asks the user to do and its current state (for example, whether the box appears checked or unchecked).",
    "If the target is not visible, say TARGET NOT VISIBLE and give no coordinates.",
    "Answer briefly: at most 5 short lines.",
  ].join(" ");
}

export function verifyQuestion(expected: string): string {
  return [
    "Read the text on this screen exactly as written, quoting the relevant lines.",
    `Does the screen show this expected content: "${expected}"?`,
    "Quote the line that matches it (or say what is shown instead), then answer YES or NO.",
    "Answer briefly: at most 3 short lines.",
  ].join(" ");
}

// ---------- the loop ----------

export interface ScreenActSpec {
  target: string;
  clickTarget?: boolean;
  typeText?: string;
  keys?: string;
  expected: string;
}

export interface ScreenActDeps {
  vision: Vision | null;
  screenshot(): Promise<Buffer>;
  click(x: number, y: number): Promise<void>;
  type(text: string): Promise<void>;
  key(keys: string): Promise<void>;
  signal?: AbortSignal;
  /** Settle time after acting, before the verify screenshot. Injectable for tests. */
  settleMs?: number;
}

export interface SeeActVerifyReport {
  outcome: "verified" | "failed" | "not-grounded";
  /** Action attempts made (1 = first try, 2 = the single allowed retry was used). */
  attempts: number;
  target: string;
  expected: string;
  groundings: Array<{ attempt: number; read: string; point: GroundedPoint | null }>;
  actions: string[];
  verifies: Array<{ attempt: number; read: string; matched: boolean }>;
  /** The comparison, stated plainly: expected vs what the final verify read said. */
  comparison: string;
}

const MAX_ATTEMPTS = 2; // first try + exactly one re-ground-and-retry

/**
 * Short-form reads, capped so a slow local VLM stays inside the model
 * server's request window. The caps look generous for 3-5 line answers
 * because thinking VLMs spend tokens on hidden reasoning first (measured
 * live: qwen3-vl:2b burned a 700-token cap entirely on reasoning and
 * returned no visible text at all), so the cap must cover reasoning +
 * answer, not just the answer.
 */
export const GROUND_MAX_TOKENS = 1900;
export const VERIFY_MAX_TOKENS = 1400;

export async function runSeeActVerify(spec: ScreenActSpec, deps: ScreenActDeps): Promise<SeeActVerifyReport> {
  if (!deps.vision) {
    throw new Error("No vision model is set up. Set a local vision model in config (vision.model), or switch to the cloud brain.");
  }
  const vision = deps.vision;
  const settle = deps.settleMs ?? 1200;
  const report: SeeActVerifyReport = {
    outcome: "not-grounded",
    attempts: 0,
    target: spec.target,
    expected: spec.expected,
    groundings: [],
    actions: [],
    verifies: [],
    comparison: "",
  };

  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    // SEE + GROUND
    const shot = await deps.screenshot();
    const size = pngSize(shot) ?? { width: 1000, height: 1000 };
    const read = await vision.describe(
      [{ data: shot.toString("base64"), mediaType: "image/png" }],
      groundingQuestion(spec.target),
      deps.signal,
      GROUND_MAX_TOKENS,
    );
    const point = parseGrounding(read, size.width, size.height);
    report.groundings.push({ attempt, read, point });
    if (!point) continue; // re-ground once; still nothing = not-grounded, and nothing was acted on

    // ACT at the grounded location
    report.attempts = attempt;
    if (spec.clickTarget) {
      await deps.click(point.x, point.y);
      report.actions.push(`attempt ${attempt}: click at ${point.x}, ${point.y} (grounded X=${point.relX}, Y=${point.relY})`);
    }
    if (spec.typeText) {
      await deps.type(spec.typeText);
      report.actions.push(`attempt ${attempt}: typed ${spec.typeText.length} characters`);
    }
    if (spec.keys) {
      await deps.key(spec.keys);
      report.actions.push(`attempt ${attempt}: pressed ${spec.keys}`);
    }
    if (settle > 0) await new Promise((r) => setTimeout(r, settle));

    // VERIFY against the named expected pattern
    const vshot = await deps.screenshot();
    const vread = await vision.describe(
      [{ data: vshot.toString("base64"), mediaType: "image/png" }],
      verifyQuestion(spec.expected),
      deps.signal,
      VERIFY_MAX_TOKENS,
    );
    const matched = verifyMatch(vread, spec.expected);
    report.verifies.push({ attempt, read: vread, matched });
    report.comparison =
      `Expected: "${spec.expected}" | Verify read (attempt ${attempt}): "${vread.replace(/\s+/g, " ").trim().slice(0, 400)}" | Match: ${matched ? "YES" : "NO"}`;
    if (matched) {
      report.outcome = "verified";
      return report;
    }
    // Mismatch: loop re-grounds and retries ONCE, then reports failure honestly.
  }

  report.outcome = report.groundings.every((g) => !g.point) ? "not-grounded" : "failed";
  if (!report.comparison) {
    report.comparison = `Expected: "${spec.expected}" | No verify read: the target was never grounded, so no action was taken. | Match: NO`;
  }
  return report;
}

/** The tool-facing rendering: the comparison is always in it, win or lose. */
export function formatReport(r: SeeActVerifyReport): string {
  const lines = [`SEE→ACT→VERIFY — target: ${r.target}`];
  for (const g of r.groundings) {
    lines.push(`Grounding (attempt ${g.attempt}): ${g.read.replace(/\s+/g, " ").trim()}`);
    lines.push(g.point ? `  → grounded point: ${g.point.x}, ${g.point.y} px (X=${g.point.relX}, Y=${g.point.relY} on the 0-1000 scale)` : "  → no coordinates in the read: NOT GROUNDED");
  }
  lines.push(r.actions.length ? `Actions:\n${r.actions.map((a) => `  - ${a}`).join("\n")}` : "Actions: none");
  for (const v of r.verifies) lines.push(`Verify (attempt ${v.attempt}): ${v.matched ? "MATCH" : "NO MATCH"} — read: ${v.read.replace(/\s+/g, " ").trim()}`);
  lines.push(`Comparison: ${r.comparison}`);
  lines.push(
    r.outcome === "verified"
      ? `Outcome: VERIFIED on attempt ${r.attempts}.`
      : r.outcome === "not-grounded"
        ? "Outcome: NOT GROUNDED — the vision read gave no usable coordinates, so no action was taken."
        : `Outcome: FAILED — the expected pattern was not seen after ${r.attempts} attempt(s) (one re-ground + retry used). Reported as failed, not retried further.`,
  );
  return lines.join("\n");
}

// ---------- the tool ----------

export const seeActVerifyTool = tool({
  name: "see_act_verify",
  description:
    "Ground a named on-screen target with the vision model, act at the grounded location (click it, then optionally type text and/or press keys), then re-read the screen and verify the result against an expected pattern. Reports the grounding, the actions, and the expected-vs-read comparison; retries once after re-grounding if verification fails. Experimental; off unless the owner turned screen control on. Every use asks the owner first.",
  schema: z.object({
    target: z.string().min(1).max(300).describe("what to find on screen, in words (the vision model grounds it)"),
    click_target: z.boolean().optional().describe("click at the grounded point first (e.g. to focus a window)"),
    type_text: z.string().max(2000).optional().describe("text to type after grounding/clicking"),
    keys: z.string().max(40).optional().describe("key combo to press after typing, e.g. Return"),
    expected: z.string().min(1).max(300).describe("the pattern the screen must show afterwards for the action to count as verified"),
  }),
  // Same standing as control_screen: far-reaching, a confirm card for every use.
  policy: { effects: ["mutate"], alwaysConfirm: true },
  async run({ target, click_target, type_text, keys, expected }, ctx: ToolContext) {
    const report = await runSeeActVerify(
      { target, clickTarget: click_target, typeText: type_text, keys, expected },
      {
        vision: ctx.vision,
        screenshot: () => os.screenshotBuffer(),
        click: (x, y) => os.screenClick(x, y, "left"),
        type: (t) => os.screenType(t),
        key: (k) => os.screenKey(k),
        signal: ctx.signal,
      },
    );
    return formatReport(report);
  },
});
