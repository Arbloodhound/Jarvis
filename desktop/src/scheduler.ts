import { randomUUID } from "node:crypto";
import { existsSync, readFileSync, renameSync, statSync, watch, writeFileSync, type FSWatcher } from "node:fs";
import path from "node:path";
import { startupWarnings } from "./config.js";
import type { AuditLog } from "./gate/audit.js";

/**
 * The scheduler: cron (time-based), once (run-once), and hook
 * (file-change) jobs, ported from the harness schedule plane
 * (harness/schedule.py; phone Scheduler.kt + JobBook.kt + SchedulerRuntime.kt).
 *
 * Rules that carry over, unchanged:
 * - EVERY job has an owner. An ownerless job is a routing error and is
 *   rejected at creation and skipped when loading a malformed file entry.
 * - The harness's own background jobs are ordinary jobs with owner
 *   "harness"; they are seeded on FIRST init only (a job file that
 *   already exists is never re-seeded, so a removed harness job stays
 *   removed) and idempotently.
 * - A job with no runnable path is never silently re-fired in a loop:
 *   a firing is marked run whether it ran, failed, or was declined.
 *
 * Desktop execution semantics (new here, stated plainly):
 * - A fired job runs its task through a FRESH agent run, headlessly.
 *   The gate is fully active. There is no user interface attached to a
 *   scheduled run, so a confirm card can never be answered: the runner
 *   declines it (the same rule as piped stdin in cli.ts) and the loop
 *   logs that decline in the audit log. A scheduled job therefore never
 *   gains silent privileges.
 * - Every firing is audit-logged with the job id and its owner.
 * - Missed jobs (the app was off when they came due): on start, a job
 *   whose scheduled time is within MISSED_GRACE_MS (1 hour) fires ONCE
 *   (multiple missed periods coalesce into that one firing). A job
 *   older than the grace window is skipped and logged
 *   (`scheduler_missed_skipped`): a once job is disabled as expired,
 *   a cron/interval job advances to its next future time. The rule
 *   covers every job due before the start time, whichever tick finds it.
 * - Hook jobs are the minimal honest phone-hook: watch one file (or a
 *   not-yet-existing file's parent folder) with fs.watch and fire when
 *   its mtime changes. No command/URL polling is claimed.
 * Times are local time, as with standard cron.
 */

export type JobKind = "cron" | "once" | "hook";

export interface Job {
  id: string;
  /** REQUIRED. "harness", "goal:<slug>", "item:<slug>", or a plain name. */
  owner: string;
  kind: JobKind;
  /**
   * cron: a 5-field cron expression ("30 9 * * 1-5"), an interval
   * shorthand ("every 30m", "every 3600s", "1h"), or an alias (@daily).
   * once: an ISO timestamp. hook: the path of the file to watch.
   */
  schedule: string;
  /** The prompt/task text handed to the agent when the job fires. */
  task: string;
  enabled: boolean;
  created: string;
  lastRun: string | null;
  nextRun: string | null;
}

/** Missed-job grace window: fire once on start if due within this long. */
export const MISSED_GRACE_MS = 60 * 60 * 1000;

/** The harness's own jobs (harness/schedule.py default_harness_jobs). */
export const DEFAULT_HARNESS_JOBS: Array<Pick<Job, "id" | "owner" | "kind" | "schedule" | "task">> = [
  { id: "harness:memory-upkeep", owner: "harness", kind: "cron", schedule: "every 3600s", task: "memory upkeep: consolidate new signal, decay old claims" },
  { id: "harness:heartbeat", owner: "harness", kind: "cron", schedule: "every 1800s", task: "heartbeat: liveness check" },
  { id: "harness:relationships", owner: "harness", kind: "cron", schedule: "every 3600s", task: "relationship pages refresh" },
  { id: "harness:ideas", owner: "harness", kind: "cron", schedule: "every 86400s", task: "idea curation" },
  { id: "harness:skill-review", owner: "harness", kind: "cron", schedule: "every 86400s", task: "skill review" },
  { id: "harness:dreaming", owner: "harness", kind: "cron", schedule: "every 86400s", task: "goal research + dreaming" },
];

// ---------------------------------------------------------------- cron ---

export interface ParsedCron {
  minute: Set<number>;
  hour: Set<number>;
  dom: Set<number>;
  month: Set<number>;
  dow: Set<number>;
  domStar: boolean;
  dowStar: boolean;
}

const MONTHS: Record<string, number> = { jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12 };
const DOWS: Record<string, number> = { sun: 0, mon: 1, tue: 2, wed: 3, thu: 4, fri: 5, sat: 6 };

export const CRON_ALIASES: Record<string, string> = {
  "@yearly": "0 0 1 1 *", "@annually": "0 0 1 1 *", "@monthly": "0 0 1 * *",
  "@weekly": "0 0 * * 0", "@daily": "0 0 * * *", "@midnight": "0 0 * * *", "@hourly": "0 * * * *",
};

function fieldValues(raw: string, min: number, max: number, names?: Record<string, number>): Set<number> {
  const out = new Set<number>();
  const norm = (tok: string): number => {
    const t = tok.trim().toLowerCase();
    if (names && t in names) return names[t]!;
    if (!/^\d+$/.test(t)) throw new Error(`bad cron value "${tok}"`);
    return Number(t);
  };
  for (const part of raw.split(",")) {
    const [rangePart, stepPart] = part.split("/");
    const step = stepPart === undefined ? 1 : Number(stepPart);
    if (!Number.isInteger(step) || step < 1) throw new Error(`bad cron step in "${part}"`);
    let lo: number, hi: number;
    if (rangePart === "*") { lo = min; hi = max; }
    else if (rangePart!.includes("-")) {
      const [a, b] = rangePart!.split("-");
      lo = norm(a!); hi = norm(b!);
    } else { lo = norm(rangePart!); hi = stepPart === undefined ? lo : max; }
    if (lo < min || hi > max || lo > hi) throw new Error(`cron value out of range in "${part}" (${min}-${max})`);
    for (let v = lo; v <= hi; v += step) out.add(v);
  }
  if (!out.size) throw new Error(`empty cron field "${raw}"`);
  return out;
}

/** Parse a standard 5-field cron expression. Throws on anything invalid. */
export function parseCron(expr: string): ParsedCron {
  const e = (CRON_ALIASES[expr.trim().toLowerCase()] ?? expr.trim());
  const f = e.split(/\s+/);
  if (f.length !== 5) throw new Error(`cron expression needs 5 fields, got ${f.length}: "${expr}"`);
  const dowRaw = fieldValues(f[4]!, 0, 7, DOWS);
  const dow = new Set<number>([...dowRaw].map((d) => (d === 7 ? 0 : d)));
  return {
    minute: fieldValues(f[0]!, 0, 59),
    hour: fieldValues(f[1]!, 0, 23),
    dom: fieldValues(f[2]!, 1, 31),
    month: fieldValues(f[3]!, 1, 12, MONTHS),
    dow,
    domStar: f[2] === "*",
    dowStar: f[4] === "*",
  };
}

/** Does this local date match? Day rule is standard cron: dom/dow OR when both are restricted. */
export function cronMatches(p: ParsedCron, d: Date): boolean {
  if (!p.minute.has(d.getMinutes()) || !p.hour.has(d.getHours()) || !p.month.has(d.getMonth() + 1)) return false;
  const domHit = p.dom.has(d.getDate());
  const dowHit = p.dow.has(d.getDay());
  if (p.domStar && p.dowStar) return true;
  if (p.domStar) return dowHit;
  if (p.dowStar) return domHit;
  return domHit || dowHit;
}

/** The next match strictly after `afterMs`, or null if none within ~5 years. Local time. */
export function nextCron(expr: string | ParsedCron, afterMs: number): number | null {
  const p = typeof expr === "string" ? parseCron(expr) : expr;
  const d = new Date(afterMs);
  d.setSeconds(0, 0);
  d.setMinutes(d.getMinutes() + 1);
  const limit = afterMs + 5 * 366 * 24 * 3600 * 1000;
  while (d.getTime() <= limit) {
    if (!p.month.has(d.getMonth() + 1)) { d.setDate(1); d.setHours(0, 0, 0, 0); d.setMonth(d.getMonth() + 1); continue; }
    const dayOk = p.domStar && p.dowStar ? true : p.domStar ? p.dow.has(d.getDay()) : p.dowStar ? p.dom.has(d.getDate()) : p.dom.has(d.getDate()) || p.dow.has(d.getDay());
    if (!dayOk) { d.setDate(d.getDate() + 1); d.setHours(0, 0, 0, 0); continue; }
    if (!p.hour.has(d.getHours())) { d.setHours(d.getHours() + 1, 0, 0, 0); continue; }
    if (!p.minute.has(d.getMinutes())) { d.setMinutes(d.getMinutes() + 1, 0, 0); continue; }
    return d.getTime();
  }
  return null;
}

/** Interval shorthand: "every 30m", "every 3600s", "1h", "every 2d", "@every 45s". Returns ms or null. */
export function parseIntervalMs(schedule: string): number | null {
  const m = /^(?:@every\s+|every\s+)?(\d+(?:\.\d+)?)\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days)?$/i.exec(schedule.trim());
  if (!m) return null;
  // A bare number with no unit and no "every" is not an interval (it would be a broken cron field).
  if (!m[2] && !/every/i.test(schedule)) return null;
  const n = Number(m[1]);
  const unit = (m[2] ?? "s").toLowerCase();
  const mult = unit.startsWith("d") ? 86_400_000 : unit.startsWith("h") ? 3_600_000 : unit.startsWith("m") ? 60_000 : 1000;
  const ms = Math.round(n * mult);
  return ms >= 1000 ? ms : null;
}

const iso = (ms: number): string => new Date(ms).toISOString();

/** The next run for a job of this shape, strictly after `fromMs` (creation/last-run time). */
export function computeNextRun(kind: JobKind, schedule: string, fromMs: number, lastRunMs: number | null): string | null {
  if (kind === "hook") return null;
  if (kind === "once") return lastRunMs === null ? iso(Date.parse(schedule)) : null;
  const every = parseIntervalMs(schedule);
  if (every !== null) return iso(fromMs + every);
  const next = nextCron(schedule, fromMs);
  return next === null ? null : iso(next);
}

// ---------------------------------------------------------------- jobs ---

export interface NewJob {
  id?: string;
  owner: string;
  kind: JobKind;
  schedule: string;
  task: string;
  enabled?: boolean;
}

/** Validate and build a job. Ownerless jobs are rejected, always. */
export function createJob(input: NewJob, nowMs: number): Job {
  const owner = (input.owner ?? "").trim();
  if (!owner) throw new Error("a job needs an owner (--owner): an ownerless job is a routing error");
  const task = (input.task ?? "").trim();
  if (!task) throw new Error("a job needs task text (what the agent should do when it fires)");
  if (input.kind !== "cron" && input.kind !== "once" && input.kind !== "hook") throw new Error(`bad job kind: ${String(input.kind)}`);
  const schedule = (input.schedule ?? "").trim();
  if (!schedule) throw new Error("a job needs a schedule");
  if (input.kind === "cron" && parseIntervalMs(schedule) === null) parseCron(schedule); // throws when invalid
  if (input.kind === "once") {
    if (!Number.isFinite(Date.parse(schedule))) throw new Error(`a once job's schedule must be an ISO time, got "${schedule}"`);
  }
  const job: Job = {
    id: input.id?.trim() || `job:${randomUUID().slice(0, 12)}`,
    owner,
    kind: input.kind,
    schedule,
    task,
    enabled: input.enabled !== false,
    created: iso(nowMs),
    lastRun: null,
    nextRun: null,
  };
  job.nextRun = job.enabled ? computeNextRun(job.kind, job.schedule, nowMs, null) : null;
  return job;
}

/** A persisted entry back into a job, or null when malformed (no owner, unknown kind, bad schedule). */
export function jobFromJson(raw: unknown): Job | null {
  if (!raw || typeof raw !== "object") return null;
  const o = raw as Record<string, unknown>;
  try {
    const job: Job = {
      id: String(o.id ?? ""),
      owner: String(o.owner ?? "").trim(),
      kind: o.kind as JobKind,
      schedule: String(o.schedule ?? ""),
      task: String(o.task ?? ""),
      enabled: o.enabled !== false,
      created: String(o.created ?? ""),
      lastRun: typeof o.lastRun === "string" ? o.lastRun : null,
      nextRun: typeof o.nextRun === "string" ? o.nextRun : null,
    };
    if (!job.id || !job.owner || !job.task || !job.schedule) return null;
    if (job.kind !== "cron" && job.kind !== "once" && job.kind !== "hook") return null;
    if (job.kind === "cron" && parseIntervalMs(job.schedule) === null) parseCron(job.schedule);
    if (job.kind === "once" && !Number.isFinite(Date.parse(job.schedule))) return null;
    return job;
  } catch {
    return null;
  }
}

/** Durable job book: jobs.json under the app home, written atomically like memory.json. */
export class JobStore {
  constructor(private readonly file: string | null) {}

  get path(): string | null { return this.file; }

  hasFile(): boolean { return this.file !== null && existsSync(this.file); }

  load(): Job[] {
    if (!this.file || !existsSync(this.file)) return [];
    try {
      const raw = JSON.parse(readFileSync(this.file, "utf8")) as unknown;
      const arr = Array.isArray(raw) ? raw : (raw as { jobs?: unknown[] })?.jobs ?? [];
      return arr.map(jobFromJson).filter((j): j is Job => j !== null);
    } catch (err) {
      const aside = `${this.file}.broken-${Date.now()}`;
      try { renameSync(this.file, aside); } catch { /* keep going empty */ }
      startupWarnings.push(`jobs.json could not be read (${err instanceof Error ? err.message : String(err)}). It was moved to ${aside}; the scheduler starts empty.`);
      return [];
    }
  }

  save(jobs: Job[]): void {
    if (!this.file) return;
    const tmp = `${this.file}.tmp`;
    writeFileSync(tmp, JSON.stringify(jobs, null, 2) + "\n", { mode: 0o600 });
    renameSync(tmp, this.file);
  }

  list(): Job[] { return this.load(); }

  get(id: string): Job | null { return this.load().find((j) => j.id === id) ?? null; }

  /** Add or replace by id. Re-validates ownership on the way in. */
  put(job: Job): Job {
    if (!job.owner?.trim()) throw new Error("a job needs an owner");
    const all = this.load().filter((j) => j.id !== job.id);
    all.push(job);
    this.save(all);
    return job;
  }

  add(input: NewJob, nowMs: number): Job {
    return this.put(createJob(input, nowMs));
  }

  remove(id: string): boolean {
    const all = this.load();
    const kept = all.filter((j) => j.id !== id);
    if (kept.length === all.length) return false;
    this.save(kept);
    return true;
  }

  setEnabled(id: string, enabled: boolean, nowMs: number): Job | null {
    const job = this.get(id);
    if (!job) return null;
    job.enabled = enabled;
    // A re-enabled job bases its next run on now, not on a stale schedule.
    job.nextRun = enabled ? computeNextRun(job.kind, job.schedule, nowMs, job.lastRun ? Date.parse(job.lastRun) : null) : null;
    return this.put(job);
  }
}

/**
 * Seed the harness's own jobs on FIRST init only: when there is no job
 * file yet. Idempotent — ids already present are never duplicated, and a
 * file that exists (even with a harness job removed) is left alone.
 * Returns the ids added.
 */
export function seedDefaultHarnessJobs(store: JobStore, nowMs: number): string[] {
  if (store.hasFile()) return [];
  const existing = new Set(store.load().map((j) => j.id));
  const added: string[] = [];
  for (const def of DEFAULT_HARNESS_JOBS) {
    if (existing.has(def.id)) continue;
    store.put(createJob({ ...def }, nowMs));
    added.push(def.id);
  }
  return added;
}

// ------------------------------------------------------------ scheduler ---

export interface RunnerResult {
  detail: string;
  /** The run needed a confirmation and, headless, it was declined. */
  confirmDeclined?: boolean;
}

export type JobRunner = (job: Job) => Promise<RunnerResult>;

export type FireStatus = "ran" | "failed" | "declined-confirm" | "skipped-missed" | "already-running";

export interface FireOutcome {
  jobId: string;
  owner: string;
  status: FireStatus;
  at: string;
  detail: string;
}

export interface SchedulerDeps {
  store: JobStore;
  runJob: JobRunner;
  audit?: AuditLog | null;
  /** Poll/timer period for start(); default 1000ms. */
  tickMs?: number;
  /** Missed-job grace for the start() catch-up; default MISSED_GRACE_MS. */
  graceMs?: number;
  /** Injectable clock for tests. */
  now?: () => number;
  cwd?: string;
}

interface AuditLike { write(entry: Record<string, unknown>): void }

export class Scheduler {
  private timer: NodeJS.Timeout | null = null;
  private ticking = false;
  /** When start() ran: a job due before this is a missed job, whichever tick finds it. */
  private startedAtMs: number | null = null;
  private readonly firing = new Set<string>();
  private readonly watchers = new Map<string, FSWatcher>();
  private readonly watchedSig = new Map<string, string>();
  private readonly lastMtime = new Map<string, number>();
  private readonly debounce = new Map<string, NodeJS.Timeout>();
  readonly recentRuns: FireOutcome[] = [];

  constructor(private readonly deps: SchedulerDeps) {}

  get running(): boolean { return this.timer !== null; }

  private now(): number { return this.deps.now ? this.deps.now() : Date.now(); }

  private audit(entry: Record<string, unknown>): void {
    (this.deps.audit as AuditLike | null | undefined)?.write(entry);
  }

  status(): { running: boolean; jobs: number; enabled: number; nextRun: string | null; recentRuns: FireOutcome[] } {
    const jobs = this.deps.store.load();
    const next = jobs.filter((j) => j.enabled && j.nextRun).map((j) => j.nextRun!).sort()[0] ?? null;
    return { running: this.running, jobs: jobs.length, enabled: jobs.filter((j) => j.enabled).length, nextRun: next, recentRuns: [...this.recentRuns] };
  }

  /**
   * Start the timer. The first tick is a catch-up (missed-job grace applies).
   * Timer ticks are skipped while it runs (ticks never overlap), and any
   * later tick still holds a job due before the start time to the grace
   * rule, so a job missed days ago is skipped, never fired late.
   */
  start(): void {
    if (this.timer) return;
    const startMs = this.now();
    this.startedAtMs = startMs;
    seedDefaultHarnessJobs(this.deps.store, startMs);
    void this.tick(startMs, { catchUp: true });
    const ms = this.deps.tickMs ?? 1000;
    this.timer = setInterval(() => { void this.tick(); }, ms);
    this.timer.unref?.();
    this.syncWatchers();
  }

  stop(): void {
    if (this.timer) { clearInterval(this.timer); this.timer = null; }
    this.startedAtMs = null;
    for (const w of this.watchers.values()) { try { w.close(); } catch { /* already closed */ } }
    this.watchers.clear();
    this.watchedSig.clear();
    for (const t of this.debounce.values()) clearTimeout(t);
    this.debounce.clear();
  }

  /**
   * One evaluation pass. Returns what fired (or was skipped) this pass.
   * Ticks never overlap: start() runs one every tickMs without awaiting it,
   * and a tick blocked on a slow job must not race a newer tick over the
   * jobs after it. A tick that finds one already running is skipped ([]).
   */
  async tick(nowMs: number = this.now(), opts: { catchUp?: boolean } = {}): Promise<FireOutcome[]> {
    if (this.ticking) return [];
    this.ticking = true;
    try {
      return await this.evaluate(nowMs, opts);
    } finally {
      this.ticking = false;
    }
  }

  private async evaluate(nowMs: number, opts: { catchUp?: boolean }): Promise<FireOutcome[]> {
    const outcomes: FireOutcome[] = [];
    const grace = this.deps.graceMs ?? MISSED_GRACE_MS;
    for (const { id } of this.deps.store.load()) {
      // Re-read each job right before acting on it: an earlier job in this
      // pass may have run for minutes, and another process (`jarvis jobs`)
      // may have disabled, moved or removed this one meanwhile.
      const job = this.deps.store.get(id);
      if (!job || !job.enabled || job.kind === "hook") continue;
      if (job.nextRun === null) {
        // A time job with no next run (hand-edited file, re-enabled once job):
        // give a once job its time back; re-base a cron job on now. Never fire blind.
        if (job.kind === "once" && job.lastRun === null) {
          job.nextRun = job.schedule;
          this.deps.store.put(job);
        } else if (job.kind === "cron") {
          job.nextRun = computeNextRun(job.kind, job.schedule, nowMs, null);
          this.deps.store.put(job);
        }
        continue;
      }
      const dueMs = Date.parse(job.nextRun);
      if (!Number.isFinite(dueMs) || dueMs > nowMs) continue;
      // Missed: judged at the catch-up time, or at the start time for a job
      // that was already due before start() but that this tick finds later.
      const missedRef = opts.catchUp ? nowMs : this.startedAtMs !== null && dueMs < this.startedAtMs ? this.startedAtMs : null;
      if (missedRef !== null && missedRef - dueMs > grace) {
        // Missed beyond the grace window: skip, log, advance — never burst.
        const scheduledFor = job.nextRun;
        // A skip is not a run: lastRun is deliberately left untouched.
        if (job.kind === "once") { job.enabled = false; job.nextRun = null; }
        else job.nextRun = computeNextRun(job.kind, job.schedule, nowMs, null);
        this.deps.store.put(job);
        this.audit({ event: "scheduler_missed_skipped", jobId: job.id, owner: job.owner, kind: job.kind, scheduledFor, nextRun: job.nextRun });
        const o: FireOutcome = { jobId: job.id, owner: job.owner, status: "skipped-missed", at: iso(nowMs), detail: `due at ${scheduledFor}, beyond the missed-job grace window` };
        this.remember(o);
        outcomes.push(o);
        continue;
      }
      outcomes.push(await this.fire(job, nowMs, job.nextRun));
    }
    this.syncWatchers();
    return outcomes;
  }

  /** Fire one job now, through the runner, with audit + bookkeeping. */
  async fire(job: Job, nowMs: number = this.now(), scheduledFor: string | null = job.nextRun): Promise<FireOutcome> {
    if (this.firing.has(job.id)) {
      return { jobId: job.id, owner: job.owner, status: "already-running", at: iso(nowMs), detail: "previous firing still running" };
    }
    this.firing.add(job.id);
    this.audit({ event: "scheduler_fire", jobId: job.id, owner: job.owner, kind: job.kind, scheduledFor });
    let status: FireStatus = "ran";
    let detail = "";
    try {
      const res = await this.deps.runJob(job);
      detail = (res.detail ?? "").slice(0, 400);
      if (res.confirmDeclined) {
        status = "declined-confirm";
        this.audit({ event: "scheduler_confirm_declined", jobId: job.id, owner: job.owner, kind: job.kind, detail: "a confirmation was needed and no user interface was attached; declined and logged" });
      }
    } catch (err) {
      status = "failed";
      detail = (err instanceof Error ? err.message : String(err)).slice(0, 400);
    } finally {
      this.firing.delete(job.id);
    }
    // Bookkeeping: a firing counts as a run whether it ran, failed or was
    // declined — a broken or card-blocked job retries on its next period,
    // never in a tight loop. A once job never fires twice.
    const fresh = this.deps.store.get(job.id) ?? job;
    fresh.lastRun = iso(nowMs);
    if (fresh.kind === "once") { fresh.enabled = false; fresh.nextRun = null; }
    else if (fresh.kind === "cron" && fresh.enabled) fresh.nextRun = computeNextRun(fresh.kind, fresh.schedule, nowMs, null);
    else fresh.nextRun = null;
    this.deps.store.put(fresh);
    this.audit({ event: "scheduler_run", jobId: job.id, owner: job.owner, kind: job.kind, status, detail });
    const outcome: FireOutcome = { jobId: job.id, owner: job.owner, status, at: iso(nowMs), detail };
    this.remember(outcome);
    return outcome;
  }

  private remember(o: FireOutcome): void {
    this.recentRuns.push(o);
    if (this.recentRuns.length > 50) this.recentRuns.splice(0, this.recentRuns.length - 50);
  }

  // -- hooks: fs.watch on the job's schedule path -------------------------

  /** Reconcile live watchers with the enabled hook jobs in the store. */
  syncWatchers(): void {
    const hooks = this.deps.store.load().filter((j) => j.kind === "hook" && j.enabled);
    const wanted = new Set(hooks.map((j) => j.id));
    for (const [id, w] of this.watchers) {
      if (!wanted.has(id)) { try { w.close(); } catch { /* */ } this.watchers.delete(id); this.watchedSig.delete(id); }
    }
    for (const job of hooks) {
      const sig = job.schedule;
      if (this.watchers.has(job.id) && this.watchedSig.get(job.id) === sig) continue;
      if (this.watchers.has(job.id)) { try { this.watchers.get(job.id)!.close(); } catch { /* */ } this.watchers.delete(job.id); }
      this.armWatcher(job);
    }
  }

  private armWatcher(job: Job): void {
    const target = path.resolve(this.deps.cwd ?? process.cwd(), job.schedule);
    const recordMtime = () => {
      try { this.lastMtime.set(job.id, statSync(target).mtimeMs); }
      catch { this.lastMtime.delete(job.id); }
    };
    recordMtime();
    const onEvent = (_event: string, filename: string | Buffer | null) => {
      // Events for other files in a watched parent folder are not ours.
      if (filename && path.basename(String(filename)) !== path.basename(target)) {
        // A watch on the file itself reports its own name/null; a parent
        // watch reports the child's name. Anything else is unrelated.
        try { if (statSync(target).mtimeMs === this.lastMtime.get(job.id)) return; }
        catch { return; }
      }
      const prev = this.debounce.get(job.id);
      if (prev) clearTimeout(prev);
      this.debounce.set(job.id, setTimeout(() => {
        this.debounce.delete(job.id);
        let mtime: number;
        try { mtime = statSync(target).mtimeMs; } catch { return; }
        if (mtime === this.lastMtime.get(job.id)) return; // duplicate event / no real change
        this.lastMtime.set(job.id, mtime);
        const fresh = this.deps.store.get(job.id);
        if (fresh && fresh.enabled && fresh.kind === "hook") void this.fire(fresh, this.now(), null);
        // Atomic saves replace the file: the old watch may be dead. Re-arm.
        try { this.watchers.get(job.id)?.close(); } catch { /* */ }
        this.watchers.delete(job.id);
        const cur = this.deps.store.get(job.id);
        if (cur && cur.enabled && cur.kind === "hook") this.armWatcher(cur);
      }, 120));
    };
    try {
      let watcher: FSWatcher;
      if (existsSync(target)) {
        watcher = watch(target, { persistent: false }, onEvent);
      } else {
        // Not there yet: watch the parent folder for its appearance/change.
        watcher = watch(path.dirname(target), { persistent: false }, onEvent);
      }
      watcher.on("error", (err) => {
        this.audit({ event: "scheduler_hook_error", jobId: job.id, owner: job.owner, detail: String(err).slice(0, 200) });
        this.watchers.delete(job.id);
      });
      this.watchers.set(job.id, watcher);
      this.watchedSig.set(job.id, job.schedule);
    } catch (err) {
      this.audit({ event: "scheduler_hook_error", jobId: job.id, owner: job.owner, detail: String(err).slice(0, 200) });
    }
  }
}

