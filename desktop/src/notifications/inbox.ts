import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { AuditLog } from "../gate/audit.js";
import {
  NotificationFilter,
  type DropReason,
  type Notification,
  type NotificationInput,
} from "./filter.js";

/**
 * The notification inbox: the single choke point every inbound notification
 * passes through, whatever intake produced it.
 *
 * A desktop has no OS notification-listener like Android's
 * NotificationListenerService (the phone's UltraNotificationService), so
 * notifications arrive two ways and both land here:
 *
 * 1. The gateway endpoint (POST /api/notifications in server.ts, token-gated
 *    exactly like the gateway's other channels).
 * 2. The inbox file drop: `notifications-inbox.jsonl` in the app home, one
 *    JSON notification per line, appended by the Electron shell or any local
 *    process and drained by the runtime (drainInboxFile).
 *
 * ingest() runs the three-gate filter. Passing notifications queue for the
 * read_notifications tool and are delivered exactly once (takePending
 * drains the queue). Dropped notifications never reach the agent — but
 * unlike the phone's silent drop, every drop is written to the audit log
 * with its gate and reason, so the owner can see what was held back.
 */

export type IntakeSource = "gateway" | "inbox_file" | "direct";

export interface IngestResult {
  notification: Notification;
  allowed: boolean;
  reason?: DropReason;
  gate?: 1 | 2 | 3;
}

export interface NotificationInboxOptions {
  filter: NotificationFilter;
  /** Where drops are recorded. Null in tests that only check verdicts. */
  audit?: AuditLog | null;
  /** The inbox file drop path (notifications-inbox.jsonl in the app home). */
  inboxFile?: string | null;
}

export class NotificationInbox {
  private pending: Notification[] = [];

  constructor(private readonly opts: NotificationInboxOptions) {}

  get filter(): NotificationFilter {
    return this.opts.filter;
  }

  /** Notifications waiting for the agent, not yet delivered. */
  get pendingCount(): number {
    return this.pending.length;
  }

  /**
   * The choke point. Every intake calls this and nothing else touches the
   * filter or the queue, so no path can bypass the gates. A drop is audited
   * and returns allowed:false; a pass queues for one delivery.
   */
  ingest(input: NotificationInput, source: IntakeSource = "direct"): IngestResult {
    const { verdict, notification } = this.opts.filter.check(input);
    if (!verdict.allowed) {
      this.opts.audit?.write({
        kind: "notification",
        event: "dropped",
        source,
        gate: verdict.gate,
        reason: verdict.reason,
        app: notification.app,
        title: notification.title,
        at: new Date(notification.at).toISOString(),
      });
      return { notification, allowed: false, reason: verdict.reason, gate: verdict.gate };
    }
    this.pending.push(notification);
    return { notification, allowed: true };
  }

  /** Audit a malformed intake item as a drop (it never reaches the filter or the agent). */
  auditInvalid(source: IntakeSource, summary: string): void {
    this.opts.audit?.write({
      kind: "notification",
      event: "dropped",
      source,
      reason: "invalid",
      line: summary.slice(0, 200),
    });
  }

  /** Ingest a batch, in order (the rate gate is order-sensitive). */
  ingestAll(inputs: NotificationInput[], source: IntakeSource): IngestResult[] {
    return inputs.map((n) => this.ingest(n, source));
  }

  /**
   * Drain the inbox file drop: read notifications-inbox.jsonl, ingest every
   * line through the same choke point, then empty the file so each line is
   * processed once. Malformed lines are audited as drops (reason "invalid")
   * and never reach the agent. Returns the per-line results.
   */
  drainInboxFile(): IngestResult[] {
    const file = this.opts.inboxFile;
    if (!file || !existsSync(file)) return [];
    let raw: string;
    try {
      raw = readFileSync(file, "utf8");
    } catch {
      return [];
    }
    if (!raw.trim()) return [];
    // Empty the file before ingesting, so a crash mid-drain can't re-deliver;
    // dropped lines are in the audit log if the owner wants them.
    try { writeFileSync(file, ""); } catch { /* a failed truncate surfaces on the next drain */ }
    const results: IngestResult[] = [];
    for (const line of raw.split("\n")) {
      const trimmed = line.trim();
      if (!trimmed) continue;
      let parsed: unknown;
      try {
        parsed = JSON.parse(trimmed);
      } catch {
        parsed = null;
      }
      const input = asNotificationInput(parsed);
      if (!input) {
        this.auditInvalid("inbox_file", trimmed);
        continue;
      }
      results.push(this.ingest(input, "inbox_file"));
    }
    return results;
  }

  /**
   * The agent's view: drain the inbox file first (so a file drop is never
   * stranded), then hand over every queued notification and clear the queue.
   * Delivered-once: a second call returns only what arrived since.
   */
  takePending(): Notification[] {
    this.drainInboxFile();
    const out = this.pending;
    this.pending = [];
    return out;
  }

  /** Queued notifications without delivering them (status/diagnostics). */
  peekPending(): Notification[] {
    return [...this.pending];
  }
}

/** A JSON value -> a NotificationInput, or null when it can't be one. Shared by both intakes. */
export function asNotificationInput(v: unknown): NotificationInput | null {
  if (!v || typeof v !== "object" || Array.isArray(v)) return null;
  const o = v as Record<string, unknown>;
  if (typeof o.app !== "string" || !o.app) return null;
  if (typeof o.title !== "string" || !o.title) return null;
  const input: NotificationInput = { app: o.app, title: o.title };
  if (typeof o.text === "string") input.text = o.text;
  // `at` is epoch ms; `time` is accepted as an ISO string or epoch ms from friendlier producers.
  if (typeof o.at === "number" && Number.isFinite(o.at)) input.at = o.at;
  else if (typeof o.time === "number" && Number.isFinite(o.time)) input.at = o.time;
  else if (typeof o.time === "string") {
    const ms = Date.parse(o.time);
    if (Number.isFinite(ms)) input.at = ms;
  }
  return input;
}

/** One queued notification as the read_notifications tool shows it: app, title, text, time. */
export function formatNotification(n: Notification): string {
  const when = new Date(n.at).toISOString();
  return n.text
    ? `[${when}] ${n.app} — ${n.title}: ${n.text}`
    : `[${when}] ${n.app} — ${n.title}`;
}

export function formatPending(list: Notification[]): string {
  if (!list.length) return "No new notifications.";
  return list.map(formatNotification).join("\n");
}
