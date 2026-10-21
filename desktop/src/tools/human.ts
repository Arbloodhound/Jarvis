/**
 * Human-quality input planning.
 *
 * A person does not teleport the cursor, dump a whole sentence in one
 * instant, or spin the wheel in a single jump. This module turns a screen
 * action into a *plan* — a list of small steps with the wait before each
 * one — and the per-platform executors in platform.ts replay the plan.
 *
 * The planners are pure: every random choice comes from an injectable
 * `rand` (or a `seed`), so tests can pin a plan exactly. Executors hold
 * no timing logic of their own; they sleep `delayMs`, then emit the step.
 *
 * Duration caps (typing/mouse/scroll stay practical, never theatrical):
 * - Mouse: total path time is distance-based, clamped to 260–950 ms
 *   (hard ceiling 1 200 ms via `durationMs`).
 * - Typing: natural cadence averages ~75 ms per key; a long paste is
 *   scaled down so the whole plan never exceeds 20 s, with a 15 ms
 *   per-key floor after scaling. A 200-character type therefore takes
 *   roughly 15 s worst case, never a minute.
 * - Scroll: at most 60 wheel clicks per action, split into increments
 *   of 1–3 clicks with 55–130 ms pauses; total plan capped at 4 s.
 */

/** Random source: returns a number in [0, 1). */
export type Rand = () => number;

export interface PlannerOptions {
  /** Injectable randomness (wins over `seed`). */
  rand?: Rand;
  /** Seed for the built-in PRNG, for reproducible plans. */
  seed?: number;
}

/** mulberry32: tiny deterministic PRNG, good enough for jitter. */
export function mulberry32(seed: number): Rand {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function resolveRand(opts: PlannerOptions): Rand {
  if (opts.rand) return opts.rand;
  if (opts.seed !== undefined) return mulberry32(opts.seed);
  return Math.random;
}

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/** Total wait of a plan, in milliseconds. */
export function planDuration(steps: Array<{ delayMs: number }>): number {
  return steps.reduce((sum, s) => sum + s.delayMs, 0);
}

// ---------- mouse ----------

export interface MouseStep {
  x: number;
  y: number;
  /** Wait this long, then move to (x, y). */
  delayMs: number;
}

export interface MousePathOptions extends PlannerOptions {
  /** Max perpendicular wander from the straight line, in px. Default 3.5. */
  maxJitterPx?: number;
  /** Total path duration override, clamped to 120–1 200 ms. */
  durationMs?: number;
}

/** Ease-in-out cubic: slow at both ends, fast in the middle. */
export function easeInOutCubic(t: number): number {
  return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
}

/**
 * Plan a smooth cursor path from `from` to `to`. Points lie on the
 * ease-in-out curve (so steps bunch up at the ends and spread out
 * mid-path — acceleration, then deceleration), with a small
 * perpendicular jitter that fades to zero at both ends so the path
 * still starts on `from`'s line and lands exactly on `to`.
 * A sub-2px hop is a single step: no theatre for a nudge.
 */
export function planMousePath(
  from: { x: number; y: number },
  to: { x: number; y: number },
  opts: MousePathOptions = {},
): MouseStep[] {
  const rand = resolveRand(opts);
  const dx = to.x - from.x;
  const dy = to.y - from.y;
  const dist = Math.hypot(dx, dy);
  if (dist < 2) return [{ x: to.x, y: to.y, delayMs: 0 }];

  const count = clamp(Math.round(dist / 14), 8, 48);
  const total = clamp(opts.durationMs ?? 220 + dist * 0.55, 120, 1200);
  const maxJitter = opts.maxJitterPx ?? 3.5;
  // Unit vector perpendicular to the travel direction.
  const px = -dy / dist;
  const py = dx / dist;

  const steps: MouseStep[] = [];
  for (let i = 1; i <= count; i++) {
    const t = i / count;
    const e = easeInOutCubic(t);
    // Jitter is strongest mid-path and zero at the ends (sin envelope).
    const jitter = (rand() * 2 - 1) * maxJitter * Math.sin(Math.PI * t);
    const isLast = i === count;
    steps.push({
      // `+ 0` normalises a rounded -0 so plans compare cleanly.
      x: isLast ? to.x : Math.round(from.x + dx * e + px * jitter) + 0,
      y: isLast ? to.y : Math.round(from.y + dy * e + py * jitter) + 0,
      delayMs: 0, // filled in below, once the raw weights exist
    });
  }
  // Roughly even waits (the easing lives in the spacing, as it does for a
  // hand), with ±25% variation; normalised so the waits sum to `total`.
  const weights = steps.map(() => 0.75 + rand() * 0.5);
  const wSum = weights.reduce((a, b) => a + b, 0);
  steps.forEach((s, i) => {
    s.delayMs = Math.round((total * weights[i]!) / wSum);
  });
  return steps;
}

// ---------- typing ----------

export interface TypingStep {
  /** The characters to emit at this step (usually one). */
  text: string;
  /** Wait this long, then type `text`. */
  delayMs: number;
}

export interface TypingOptions extends PlannerOptions {
  /** Hard cap on the whole plan's duration. Default 20 000 ms. */
  maxTotalMs?: number;
}

/** Caps a single key's wait may reach (long "thinking" pause). */
export const MAX_KEY_DELAY_MS = 620;

/**
 * Plan typing with a natural cadence: a base 48–103 ms per key with
 * jitter, a longer breath after spaces and sentence punctuation, and a
 * rare longer pause — never one uniform metronome, never one burst.
 * If the raw plan would exceed `maxTotalMs` it is scaled down evenly
 * (15 ms per-key floor), so long text stays practical.
 */
export function planTyping(text: string, opts: TypingOptions = {}): TypingStep[] {
  const rand = resolveRand(opts);
  const chars = [...text];
  const steps: TypingStep[] = chars.map((ch, i) => {
    let delay = 48 + rand() * 55;
    const prev = i > 0 ? chars[i - 1]! : "";
    if (prev === " ") delay += 40 + rand() * 110; // word boundary breath
    if (".!?;:,".includes(prev)) delay += 90 + rand() * 160; // punctuation pause
    if (i > 0 && rand() < 0.04) delay += 150 + rand() * 170; // occasional think
    if (i === 0) delay = Math.min(delay, 40); // first key lands promptly
    return { text: ch, delayMs: Math.round(delay) };
  });
  const maxTotal = opts.maxTotalMs ?? 20_000;
  const total = planDuration(steps);
  if (total > maxTotal && total > 0) {
    const f = maxTotal / total;
    for (const s of steps) s.delayMs = Math.max(15, Math.round(s.delayMs * f));
  }
  return steps;
}

// ---------- scroll ----------

export interface ScrollStep {
  /** Wheel clicks in this increment (sign = direction: + down, − up). */
  steps: number;
  /** Wait this long, then emit this increment. */
  delayMs: number;
}

/** Most wheel clicks a single scroll action may emit. */
export const MAX_SCROLL_CLICKS = 60;

/**
 * Plan a scroll of `amount` wheel clicks (positive = down, negative =
 * up) as several small increments of 1–3 clicks with short pauses —
 * the way a finger or wheel actually moves — never one jump.
 */
export function planScroll(amount: number, opts: PlannerOptions & { maxTotalMs?: number } = {}): ScrollStep[] {
  const rand = resolveRand(opts);
  const total = clamp(Math.trunc(amount), -MAX_SCROLL_CLICKS, MAX_SCROLL_CLICKS);
  if (total === 0) return [];
  const sign = Math.sign(total);
  let remaining = Math.abs(total);
  const steps: ScrollStep[] = [];
  while (remaining > 0) {
    const chunk = Math.min(remaining, 1 + Math.floor(rand() * 3));
    const first = steps.length === 0;
    steps.push({
      steps: sign * chunk,
      delayMs: Math.round(first ? 30 + rand() * 40 : 55 + rand() * 75),
    });
    remaining -= chunk;
  }
  const maxTotal = opts.maxTotalMs ?? 4000;
  const sum = planDuration(steps);
  if (sum > maxTotal && sum > 0) {
    const f = maxTotal / sum;
    for (const s of steps) s.delayMs = Math.max(25, Math.round(s.delayMs * f));
  }
  return steps;
}
