/**
 * Notification filter — three gates before a notification reaches the agent.
 *
 * Gate 1: allowed apps only (own package always excluded).
 * Gate 2: quiet hours respected.
 * Gate 3: burst rate limit (per-minute fixed window per app).
 *
 * A notification must pass all three. Anything else is dropped — silently
 * for the agent, but the inbox (notifications/inbox.ts) audits every drop
 * so the owner can see what was held back and why.
 *
 * Ported 1:1 from the phone's features/notifications.py (same defaults,
 * same wrap-around quiet-hours logic, same fixed-window rate limiting).
 * Gate design adapted from OpenClaw's NotificationForwardingPolicy
 * (MIT, Copyright (c) 2026 OpenClaw Foundation).
 *
 * Three desktop adaptations:
 * - Times are epoch milliseconds (JS `Date.now()`), not epoch seconds.
 *   The fixed window is `floor(at / 60000)`, the minute bucket of `at`.
 * - The clock is injectable (`now`) so tests can pin "now" — the Python
 *   filter took the time from each notification's `at` field, and this
 *   one does too; `now` only supplies `at` when a notification has none.
 * - Every gate judges `at`, the arrival time, never the time of the check.
 *   The inbox file is drained later than its lines arrived, so arrivals can
 *   reach the filter out of order; the rate gate therefore counts each
 *   minute window separately instead of keeping only the latest one, which
 *   an older arrival would reset. For in-order arrivals the result is the
 *   same as the Python filter's single window.
 */

/** This app's own package id (package.json build.appId). Its notifications never reach the agent. */
export const OWN_PACKAGE = "com.arbloodhound.jarvis";
/** This app's own display name, as an OS notification source would label it. Also always excluded. */
export const OWN_NAME = "Jarvis";
/** The phone filter's own-package default, kept so a phone-shaped source id is excluded too. */
export const PHONE_OWN_PACKAGE = "ai.jarvis.app";

export interface Notification {
  /** Source app: a package id, bundle id, or app name — matched exactly against allowedApps. */
  app: string;
  title: string;
  text: string;
  /** When the notification arrived, epoch ms. Supplied by the filter's clock when omitted. */
  at: number;
}

/** A notification as it arrives at an intake: `text` and `at` may be missing. */
export interface NotificationInput {
  app: string;
  title: string;
  text?: string;
  at?: number;
}

export type DropReason = "own_app" | "not_allowed" | "quiet_hours" | "rate_limited";

export type GateVerdict =
  | { allowed: true }
  | { allowed: false; reason: DropReason; gate: 1 | 2 | 3 };

export interface FilterOptions {
  allowedApps?: Iterable<string>;
  ownPackage?: string;
  quietStart?: number;
  quietEnd?: number;
  maxPerMinutePerApp?: number;
  /** Injectable clock, epoch ms. Defaults to Date.now. */
  now?: () => number;
}

export class NotificationFilter {
  readonly allowedApps: Set<string>;
  readonly ownPackage: string;
  readonly quietStart: number;
  readonly quietEnd: number;
  readonly maxPerMinute: number;
  private readonly now: () => number;
  /** app -> (minute window -> count). Windows a day behind the app's newest are pruned. */
  private readonly buckets = new Map<string, Map<number, number>>();

  constructor(opts: FilterOptions = {}) {
    this.allowedApps = new Set(opts.allowedApps ?? []);
    this.ownPackage = opts.ownPackage ?? OWN_PACKAGE;
    this.quietStart = opts.quietStart ?? 22;
    this.quietEnd = opts.quietEnd ?? 7;
    this.maxPerMinute = opts.maxPerMinutePerApp ?? 5;
    this.now = opts.now ?? Date.now;
  }

  /**
   * Fill in the fields an intake may omit. A missing `at` means "arrived
   * now" (the gateway's case); an intake that knows better, like the inbox
   * file drain, supplies `at` itself.
   */
  normalize(input: NotificationInput): Notification {
    return {
      app: input.app,
      title: input.title,
      text: input.text ?? "",
      at: input.at ?? this.now(),
    };
  }

  /** Local hour of `at` is inside quiet hours. Wrap-around (22 -> 7) handled exactly as the Python filter. */
  inQuietHours(at: number): boolean {
    const h = new Date(at).getHours();
    if (this.quietStart <= this.quietEnd) {
      return this.quietStart <= h && h < this.quietEnd;
    }
    return h >= this.quietStart || h < this.quietEnd;
  }

  /**
   * Gate 3's fixed window: the first maxPerMinute notifications from an app
   * in a minute window pass; the rest fail. A passing check consumes a slot —
   * this mutates, exactly like the Python `_burst_ok`, and it is only ever
   * called after gates 1 and 2 have passed. The window is the arrival
   * minute, counted on its own, so a late backlog neither borrows a newer
   * minute's slots nor resets them.
   */
  private burstOk(app: string, at: number): boolean {
    const window = Math.floor(at / 60_000);
    let windows = this.buckets.get(app);
    if (!windows) this.buckets.set(app, (windows = new Map()));
    const count = windows.get(window) ?? 0;
    if (count >= this.maxPerMinute) return false;
    windows.set(window, count + 1);
    const newest = Math.max(...windows.keys());
    for (const w of windows.keys()) if (w < newest - 1440) windows.delete(w);
    return true;
  }

  /** True only if all three gates pass. Own notifications fail gate 1 before the allowlist is even read. */
  check(input: NotificationInput): { verdict: GateVerdict; notification: Notification } {
    const notification = this.normalize(input);
    if (
      notification.app === this.ownPackage ||
      notification.app === OWN_NAME ||
      notification.app === PHONE_OWN_PACKAGE
    ) {
      return { verdict: { allowed: false, reason: "own_app", gate: 1 }, notification };
    }
    if (!this.allowedApps.has(notification.app)) {
      return { verdict: { allowed: false, reason: "not_allowed", gate: 1 }, notification };
    }
    if (this.inQuietHours(notification.at)) {
      return { verdict: { allowed: false, reason: "quiet_hours", gate: 2 }, notification };
    }
    if (!this.burstOk(notification.app, notification.at)) {
      return { verdict: { allowed: false, reason: "rate_limited", gate: 3 }, notification };
    }
    return { verdict: { allowed: true }, notification };
  }

  /** The Python filter's `allow`: True only if all three gates pass. */
  allow(input: NotificationInput): boolean {
    return this.check(input).verdict.allowed;
  }

  /** The Python filter's `filter`: the passing notifications, normalized, in order. */
  filter(inputs: NotificationInput[]): Notification[] {
    const out: Notification[] = [];
    for (const input of inputs) {
      const { verdict, notification } = this.check(input);
      if (verdict.allowed) out.push(notification);
    }
    return out;
  }
}
