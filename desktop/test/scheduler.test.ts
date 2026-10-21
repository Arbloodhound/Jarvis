import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { loadConfig, paths } from "../src/config.ts";
import { Runtime } from "../src/runtime.ts";
import {
  JobStore, Scheduler, computeNextRun, createJob, cronMatches, jobFromJson,
  nextCron, parseCron, parseIntervalMs, seedDefaultHarnessJobs,
} from "../src/scheduler.ts";

/**
 * Scheduler cluster: cron matcher (fake-clock), job model + persistence,
 * owner requirement, first-init seeding, firing bookkeeping, the missed-job
 * grace policy, the headless confirm-decline status, and file-watch hooks.
 */

const T0 = Date.parse("2026-10-20T12:00:00.000Z"); // a Tuesday, local getters aside: cron uses local time
const local = (y: number, mo: number, d: number, h: number, mi: number) => new Date(y, mo - 1, d, h, mi, 0, 0).getTime();

function tmpStore(): { store: JobStore; file: string; dir: string } {
  const dir = mkdtempSync(path.join(tmpdir(), "jarvis-sched-"));
  const file = path.join(dir, "jobs.json");
  return { store: new JobStore(file), file, dir };
}

function captureAudit() {
  const entries: Array<Record<string, unknown>> = [];
  return { entries, log: { write: (e: Record<string, unknown>) => { entries.push(e); } } };
}

// ------------------------------------------------------------- cron math ---

test("cron: every-15-minutes expression matches and advances by 15 minutes", () => {
  const p = parseCron("*/15 * * * *");
  const start = local(2026, 10, 20, 12, 7);
  const next = nextCron(p, start)!;
  assert.equal(new Date(next).getMinutes(), 15);
  assert.equal(next - start, 8 * 60_000);
  assert.ok(cronMatches(p, new Date(next)));
  assert.ok(!cronMatches(p, new Date(start)));
});

test("cron: weekday 9:30 job skips the weekend", () => {
  const p = parseCron("30 9 * * 1-5");
  // Friday 2026-10-23 09:30 local -> next is Monday 2026-10-26 09:30.
  const fri = local(2026, 10, 23, 9, 30);
  assert.equal(new Date(fri).getDay(), 5);
  const next = new Date(nextCron(p, fri)!);
  assert.equal(next.getDay(), 1);
  assert.equal(next.getDate(), 26);
  assert.equal(next.getHours(), 9);
  assert.equal(next.getMinutes(), 30);
});

test("cron: ranges, lists, steps and month/day names", () => {
  const p = parseCron("0 9-17/2 * jan,mar mon");
  assert.deepEqual([...p.hour].sort((a, b) => a - b), [9, 11, 13, 15, 17]);
  assert.deepEqual([...p.month].sort((a, b) => a - b), [1, 3]);
  assert.deepEqual([...p.dow], [1]);
  const named = parseCron("0 0 29 feb *");
  const leap = nextCron(named, local(2027, 3, 1, 0, 0))!;
  const d = new Date(leap);
  assert.equal(d.getFullYear(), 2028); // 2028 is the next leap year with a Feb 29
  assert.equal(d.getMonth(), 1);
  assert.equal(d.getDate(), 29);
});

test("cron: day-of-month OR day-of-week when both are restricted (standard rule)", () => {
  const p = parseCron("0 12 15 * 3"); // the 15th OR any Wednesday
  assert.ok(cronMatches(p, new Date(local(2026, 10, 15, 12, 0)))); // Thursday the 15th
  assert.ok(cronMatches(p, new Date(local(2026, 10, 21, 12, 0)))); // Wednesday the 21st
  assert.ok(!cronMatches(p, new Date(local(2026, 10, 22, 12, 0)))); // Thursday the 22nd
  assert.ok(!cronMatches(p, new Date(local(2026, 10, 15, 13, 0))));
});

test("cron: aliases and invalid expressions", () => {
  assert.equal(nextCron("@daily", local(2026, 10, 20, 0, 0)), local(2026, 10, 21, 0, 0));
  assert.throws(() => parseCron("not a cron"), /5 fields/);
  assert.throws(() => parseCron("61 * * * *"), /out of range/);
  assert.throws(() => parseCron("* * * *"), /5 fields/);
  assert.throws(() => parseCron("*/0 * * * *"), /step/);
});

test("interval shorthand: units, @every, and bare numbers rejected", () => {
  assert.equal(parseIntervalMs("every 30m"), 30 * 60_000);
  assert.equal(parseIntervalMs("every 3600s"), 3_600_000);
  assert.equal(parseIntervalMs("1h"), 3_600_000);
  assert.equal(parseIntervalMs("@every 2d"), 2 * 86_400_000);
  assert.equal(parseIntervalMs("3600"), null); // no unit, no "every": not an interval
  assert.equal(parseIntervalMs("*/5 * * * *"), null);
});

// ------------------------------------------------------------ job model ---

test("owner is required: createJob rejects an ownerless job, and so does the store loader", () => {
  assert.throws(() => createJob({ owner: "", kind: "once", schedule: new Date(T0).toISOString(), task: "x" }, T0), /owner/);
  assert.throws(() => createJob({ owner: "  ", kind: "cron", schedule: "* * * * *", task: "x" }, T0), /owner/);
  assert.equal(jobFromJson({ id: "j1", owner: "", kind: "cron", schedule: "* * * * *", task: "x" }), null);
  assert.equal(jobFromJson({ id: "j1", owner: "a", kind: "bogus", schedule: "* * * * *", task: "x" }), null);
  assert.equal(jobFromJson({ id: "j1", owner: "a", kind: "cron", schedule: "nope", task: "x" }), null);
});

test("createJob computes nextRun per kind", () => {
  const once = createJob({ owner: "me", kind: "once", schedule: new Date(T0 + 60_000).toISOString(), task: "t" }, T0);
  assert.equal(once.nextRun, new Date(T0 + 60_000).toISOString());
  assert.equal(once.lastRun, null);
  const interval = createJob({ owner: "me", kind: "cron", schedule: "every 30m", task: "t" }, T0);
  assert.equal(interval.nextRun, new Date(T0 + 30 * 60_000).toISOString());
  const hook = createJob({ owner: "me", kind: "hook", schedule: "/tmp/watched.txt", task: "t" }, T0);
  assert.equal(hook.nextRun, null);
  assert.equal(computeNextRun("once", new Date(T0).toISOString(), T0, T0), null); // already run once jobs stay done
});

test("store persists jobs to jobs.json and reloads them; malformed entries are skipped", () => {
  const { store, file } = tmpStore();
  const job = store.add({ owner: "goal:x", kind: "cron", schedule: "15 8 * * *", task: "morning check" }, T0);
  assert.ok(existsSync(file));
  const back = new JobStore(file).load();
  assert.equal(back.length, 1);
  assert.equal(back[0]!.id, job.id);
  assert.equal(back[0]!.owner, "goal:x");
  // A hand-corrupted entry (ownerless) in the file is dropped, not guessed.
  const raw = JSON.parse(readFileSync(file, "utf8")) as unknown[];
  raw.push({ id: "bad", owner: "", kind: "cron", schedule: "* * * * *", task: "x" });
  writeFileSync(file, JSON.stringify(raw));
  assert.equal(new JobStore(file).load().length, 1);
  assert.equal(store.remove(job.id), true);
  assert.equal(store.remove(job.id), false);
  assert.equal(store.load().length, 0);
});

// ---------------------------------------------------------------- seeding ---

test("seeding: first init seeds the six harness jobs, idempotently, and never resurrects a removed one", () => {
  const { store, file } = tmpStore();
  const added = seedDefaultHarnessJobs(store, T0);
  assert.deepEqual(added, ["harness:memory-upkeep", "harness:heartbeat", "harness:relationships", "harness:ideas", "harness:skill-review", "harness:dreaming"]);
  const jobs = store.load();
  assert.equal(jobs.length, 6);
  assert.ok(jobs.every((j) => j.owner === "harness"));
  const hb = jobs.find((j) => j.id === "harness:heartbeat")!;
  assert.equal(Date.parse(hb.nextRun!) - T0, 1800_000); // first run one period out, not a burst at startup
  // Second seed attempt (same process, file now exists): nothing added.
  assert.deepEqual(seedDefaultHarnessJobs(store, T0 + 1000), []);
  assert.equal(store.load().length, 6);
  // Remove one, "restart" (fresh store object on the same file): it stays removed.
  assert.equal(store.remove("harness:heartbeat"), true);
  assert.deepEqual(seedDefaultHarnessJobs(new JobStore(file), T0 + 2000), []);
  assert.equal(new JobStore(file).load().length, 5);
});

// ----------------------------------------------------------------- firing ---

function makeScheduler(runJob: (j: { task: string }) => Promise<{ detail: string; confirmDeclined?: boolean }>, graceMs?: number) {
  const { store, file, dir } = tmpStore();
  const { entries, log } = captureAudit();
  const sched = new Scheduler({ store, audit: log as never, runJob: runJob as never, graceMs, cwd: dir });
  return { store, file, dir, entries, sched };
}

test("a due once job fires once, is marked run, disabled, and audit-logged with its owner", async () => {
  const seen: string[] = [];
  const { store, entries, sched } = makeScheduler(async (j) => { seen.push(j.task); return { detail: "done it" }; });
  const job = store.add({ owner: "parity-test", kind: "once", schedule: new Date(T0 + 1000).toISOString(), task: "do the thing" }, T0);
  assert.deepEqual(await sched.tick(T0 + 500), []); // not due yet
  const out = await sched.tick(T0 + 2000);
  assert.equal(out.length, 1);
  assert.equal(out[0]!.status, "ran");
  assert.deepEqual(seen, ["do the thing"]);
  const after = store.get(job.id)!;
  assert.equal(after.enabled, false);
  assert.equal(after.nextRun, null);
  assert.equal(after.lastRun, new Date(T0 + 2000).toISOString());
  assert.deepEqual(await sched.tick(T0 + 999_000), []); // never fires twice
  const fire = entries.find((e) => e.event === "scheduler_fire");
  assert.equal(fire?.jobId, job.id);
  assert.equal(fire?.owner, "parity-test");
  const run = entries.find((e) => e.event === "scheduler_run");
  assert.equal(run?.owner, "parity-test");
  assert.equal(run?.status, "ran");
});

test("a cron interval job fires when due, advances nextRun, and a failing runner is marked run (no tight loop)", async () => {
  let calls = 0;
  const { store, sched } = makeScheduler(async () => { calls++; throw new Error("model down"); });
  const job = store.add({ owner: "me", kind: "cron", schedule: "every 60s", task: "tick" }, T0);
  const out = await sched.tick(T0 + 61_000);
  assert.equal(out[0]!.status, "failed");
  assert.match(out[0]!.detail, /model down/);
  const after = store.get(job.id)!;
  assert.equal(after.lastRun, new Date(T0 + 61_000).toISOString());
  assert.equal(Date.parse(after.nextRun!) - (T0 + 61_000), 60_000);
  assert.deepEqual(await sched.tick(T0 + 62_000), []);
  assert.equal(calls, 1);
});

test("a disabled job never fires; re-enabling re-bases its next run on now", async () => {
  const { store, sched } = makeScheduler(async () => ({ detail: "x" }));
  const job = store.add({ owner: "me", kind: "cron", schedule: "every 60s", task: "t", enabled: false }, T0);
  assert.equal(job.nextRun, null);
  assert.deepEqual(await sched.tick(T0 + 600_000), []);
  const on = store.setEnabled(job.id, true, T0 + 600_000)!;
  assert.equal(Date.parse(on.nextRun!) - (T0 + 600_000), 60_000);
  const off = store.setEnabled(job.id, false, T0 + 700_000)!;
  assert.equal(off.nextRun, null);
});

test("missed jobs: within the grace window a due job fires once on catch-up; beyond it, it is skipped and logged", async () => {
  const { store, entries, sched } = makeScheduler(async () => ({ detail: "ran" }), 60_000);
  const recent = store.add({ owner: "me", kind: "once", schedule: new Date(T0 + 1000).toISOString(), task: "recent" }, T0);
  const second = store.add({ owner: "me", kind: "once", schedule: new Date(T0 + 2000).toISOString(), task: "second" }, T0);
  const out = await sched.tick(T0 + 30_000, { catchUp: true });
  const byId = new Map(out.map((o) => [o.jobId, o.status]));
  assert.equal(byId.get(recent.id), "ran"); // 29s late: inside the 60s grace
  assert.equal(byId.get(second.id), "ran"); // 28s late: also inside the grace
  // A job hours past due at catch-up is skipped, disabled (once), and logged.
  const old = store.add({ owner: "me", kind: "once", schedule: new Date(T0).toISOString(), task: "old" }, T0 - 10_000);
  const out2 = await sched.tick(T0 + 3_600_000, { catchUp: true });
  assert.equal(out2.find((o) => o.jobId === old.id)?.status, "skipped-missed");
  assert.equal(store.get(old.id)!.enabled, false);
  const skip = entries.find((e) => e.event === "scheduler_missed_skipped" && e.jobId === old.id);
  assert.equal(skip?.owner, "me");
});

test("missed cron job beyond grace advances to its next future time instead of bursting", async () => {
  let calls = 0;
  const { store, sched } = makeScheduler(async () => { calls++; return { detail: "x" }; }, 60_000);
  const job = store.add({ owner: "me", kind: "cron", schedule: "every 60s", task: "t" }, T0);
  const out = await sched.tick(T0 + 3_600_000, { catchUp: true });
  assert.equal(out.find((o) => o.jobId === job.id)?.status, "skipped-missed");
  assert.equal(calls, 0); // coalesced away entirely, not fired once per missed period
  const after = store.get(job.id)!;
  assert.ok(Date.parse(after.nextRun!) > T0 + 3_600_000);
  assert.equal(after.lastRun, null); // a skip is not a run
});

test("a run that needed a confirmation is reported declined-confirm and audit-logged, never silently run", async () => {
  const { store, entries, sched } = makeScheduler(async () => ({ detail: "The owner declined this.", confirmDeclined: true }));
  const job = store.add({ owner: "parity-test", kind: "once", schedule: new Date(T0).toISOString(), task: "delete something" }, T0);
  const out = await sched.tick(T0 + 1000);
  assert.equal(out[0]!.status, "declined-confirm");
  const dec = entries.find((e) => e.event === "scheduler_confirm_declined");
  assert.equal(dec?.jobId, job.id);
  assert.equal(dec?.owner, "parity-test");
  assert.equal(store.get(job.id)!.enabled, false); // still marked run: no retry loop
});

/** A promise the test releases by hand: stands in for a slow job run, no real sleep. */
function deferred(): { p: Promise<void>; release: () => void } {
  let release!: () => void;
  const p = new Promise<void>((r) => { release = r; });
  return { p, release };
}

test("overlapping ticks never fire a job twice: a tick blocked on a slow job does not re-fire one another tick ran", async () => {
  const fired: string[] = [];
  const gate = deferred();
  const { store, sched } = makeScheduler(async (j) => { fired.push(j.task); if (j.task === "a") await gate.p; return { detail: "x" }; });
  store.add({ owner: "me", kind: "once", schedule: new Date(T0 + 1000).toISOString(), task: "a" }, T0);
  const b = store.add({ owner: "me", kind: "once", schedule: new Date(T0 + 1000).toISOString(), task: "b" }, T0);
  const first = sched.tick(T0 + 2000); // blocks inside job a
  const second = await sched.tick(T0 + 3000); // the next timer tick, while the first is still running
  gate.release();
  await first;
  assert.deepEqual(fired, ["a", "b"]); // b once, never a,b,b
  assert.deepEqual(second, []); // skipped: a tick never overlaps another
  assert.equal(store.get(b.id)!.enabled, false);
});

test("a tick re-reads each job before firing it: a job disabled while an earlier job ran is not fired from a stale copy", async () => {
  const fired: string[] = [];
  const gate = deferred();
  const { store, sched } = makeScheduler(async (j) => { fired.push(j.task); if (j.task === "a") await gate.p; return { detail: "x" }; });
  store.add({ owner: "me", kind: "once", schedule: new Date(T0 + 1000).toISOString(), task: "a" }, T0);
  const b = store.add({ owner: "me", kind: "cron", schedule: "every 1s", task: "b" }, T0);
  const first = sched.tick(T0 + 2000);
  store.setEnabled(b.id, false, T0 + 2000); // e.g. `jarvis jobs disable` from another process
  gate.release();
  await first;
  assert.deepEqual(fired, ["a"]);
  assert.equal(store.get(b.id)!.enabled, false);
  assert.equal(store.get(b.id)!.lastRun, null);
});

/** Let pending promise callbacks run (no timers involved), until `done()` or a bounded number of turns. */
async function settle(done: () => boolean): Promise<void> {
  for (let i = 0; i < 200 && !done(); i++) await new Promise((r) => setImmediate(r));
}

const DAY = 86_400_000;

test("start(): while the catch-up is blocked on a slow job, timer ticks do not fire a job missed beyond the grace window", async (t) => {
  t.mock.timers.enable({ apis: ["setInterval"] });
  const fired: string[] = [];
  const gate = deferred();
  const { store, dir } = tmpStore();
  const { entries, log } = captureAudit();
  let now = T0;
  const sched = new Scheduler({
    store, audit: log as never, graceMs: 60_000, tickMs: 1000, now: () => now, cwd: dir,
    runJob: async (j) => { fired.push(j.task); if (j.task === "a") await gate.p; return { detail: "x" }; },
  });
  store.add({ owner: "me", kind: "once", schedule: new Date(T0 - 10_000).toISOString(), task: "a" }, T0 - 2 * DAY); // inside grace
  const b = store.add({ owner: "me", kind: "once", schedule: new Date(T0 - DAY).toISOString(), task: "b" }, T0 - 2 * DAY); // a day late
  sched.start(); // catch-up starts, blocks inside a
  for (let i = 0; i < 3; i++) { now += 1000; t.mock.timers.tick(1000); await settle(() => false); }
  gate.release();
  await settle(() => sched.recentRuns.some((r) => r.jobId === b.id));
  sched.stop();
  assert.deepEqual(fired, ["a"]); // b was missed by a day: never fired
  assert.equal(sched.recentRuns.find((r) => r.jobId === b.id)?.status, "skipped-missed");
  assert.ok(entries.some((e) => e.event === "scheduler_missed_skipped" && e.jobId === b.id));
  assert.equal(store.get(b.id)!.enabled, false);
});

test("a catch-up pass does not save a stale copy over a firing written meanwhile (lastRun is kept)", async () => {
  const gate = deferred();
  const { store, sched } = makeScheduler(async (j) => { if (j.task === "a") await gate.p; return { detail: "x" }; }, 60_000);
  store.add({ owner: "me", kind: "once", schedule: new Date(T0 - 10_000).toISOString(), task: "a" }, T0 - 2 * DAY);
  const b = store.add({ owner: "me", kind: "cron", schedule: "every 1h", task: "b" }, T0 - 2 * DAY); // nextRun long past
  const catchUp = sched.tick(T0, { catchUp: true });
  // Meanwhile b is fired elsewhere: its real firing writes lastRun and a future nextRun.
  const ran = store.get(b.id)!;
  ran.lastRun = new Date(T0).toISOString();
  ran.nextRun = new Date(T0 + 3_600_000).toISOString();
  store.put(ran);
  gate.release();
  const out = await catchUp;
  assert.equal(store.get(b.id)!.lastRun, new Date(T0).toISOString());
  assert.equal(store.get(b.id)!.nextRun, new Date(T0 + 3_600_000).toISOString());
  assert.equal(out.find((o) => o.jobId === b.id), undefined); // not due any more: nothing to skip
});

test("start(): a job due before the scheduler started is held to the grace rule even if the catch-up pass never saw it", async (t) => {
  t.mock.timers.enable({ apis: ["setInterval"] });
  const fired: string[] = [];
  const { store, dir } = tmpStore();
  const { entries, log } = captureAudit();
  let now = T0;
  const sched = new Scheduler({
    store, audit: log as never, graceMs: 60_000, tickMs: 1000, now: () => now, cwd: dir,
    runJob: async (j) => { fired.push(j.task); return { detail: "x" }; },
  });
  store.add({ owner: "me", kind: "once", schedule: new Date(T0 + DAY).toISOString(), task: "later" }, T0); // job file exists: no seeding
  sched.start();
  await settle(() => false); // catch-up done (nothing due)
  // Written after the catch-up, e.g. by `jarvis jobs add` in another process, with a time a day before start.
  const old = store.add({ owner: "me", kind: "once", schedule: new Date(T0 - DAY).toISOString(), task: "old" }, T0);
  now += 1000; t.mock.timers.tick(1000);
  await settle(() => sched.recentRuns.some((r) => r.jobId === old.id));
  sched.stop();
  assert.deepEqual(fired, []);
  assert.equal(sched.recentRuns.find((r) => r.jobId === old.id)?.status, "skipped-missed");
  assert.ok(entries.some((e) => e.event === "scheduler_missed_skipped" && e.jobId === old.id));
});

test("a job removed while it fires stays removed: no save after the run, and the removal is audited", async () => {
  let id = "";
  let store!: JobStore;
  const made = makeScheduler(async () => { store.remove(id); return { detail: "x" }; }); // `jarvis jobs remove` mid-run
  store = made.store;
  id = store.add({ owner: "me", kind: "cron", schedule: "every 60s", task: "t" }, T0).id;
  const out = await made.sched.tick(T0 + 61_000);
  assert.equal(out[0]?.status, "ran");
  assert.equal(store.get(id), null); // not written back
  assert.equal(store.load().length, 0);
  const gone = made.entries.find((e) => e.event === "scheduler_job_removed_during_run");
  assert.equal(gone?.jobId, id);
  assert.equal(gone?.owner, "me");
  assert.ok(made.entries.some((e) => e.event === "scheduler_run" && e.jobId === id)); // the run itself is still logged
});

// ------------------------------------------------------------------ hooks ---

test("hook job fires when its watched file changes, and not before", { timeout: 30_000 }, async () => {
  const { store, dir, sched } = makeScheduler(async () => ({ detail: "hook ran" }));
  const watched = path.join(dir, "watched.txt");
  writeFileSync(watched, "one\n");
  const job = store.add({ owner: "me", kind: "hook", schedule: watched, task: "file changed" }, Date.now());
  sched.syncWatchers();
  await new Promise((r) => setTimeout(r, 300));
  assert.equal(sched.recentRuns.length, 0);
  writeFileSync(watched, "two — changed\n");
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline && !sched.recentRuns.some((r) => r.jobId === job.id)) {
    await new Promise((r) => setTimeout(r, 100));
  }
  sched.stop();
  const hit = sched.recentRuns.find((r) => r.jobId === job.id);
  assert.ok(hit, "the hook job should have fired after the file changed");
  assert.equal(hit!.status, "ran");
  assert.equal(store.get(job.id)!.enabled, true); // hooks stay enabled after firing
  assert.ok(store.get(job.id)!.lastRun);
});

// ------------------------------------------------------- runtime + CLI ---

test("runtime state exposes the scheduler and the seeded job list", () => {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-sched-rt-"));
  const p = paths(home);
  const cfg = loadConfig(p);
  const rt = new Runtime(cfg, p, home);
  const s = rt.state() as unknown as { scheduler: { running: boolean; jobs: number }; jobs: Array<{ id: string; owner: string }> };
  assert.equal(s.scheduler.jobs, 6);
  assert.equal(s.scheduler.running, false); // constructed, not started: no timer leaks into other runs
  assert.ok(s.jobs.every((j) => j.owner === "harness"));
  assert.ok(existsSync(p.jobs));
});

const PKG = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const CLI = path.join(PKG, "src", "cli.ts");

function runCli(args: string[], home: string): Promise<{ status: number | null; out: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ["--import", "tsx", CLI, ...args], {
      cwd: PKG,
      env: { PATH: process.env.PATH ?? "", HOME: process.env.HOME ?? "", JARVIS_HOME: home },
    });
    let buf = "";
    child.stdout.on("data", (d) => (buf += d));
    child.stderr.on("data", (d) => (buf += d));
    const timer = setTimeout(() => { child.kill("SIGKILL"); reject(new Error(`CLI timed out: ${args.join(" ")}\n${buf}`)); }, 60_000);
    child.on("error", (err) => { clearTimeout(timer); reject(err); });
    child.on("close", (status) => { clearTimeout(timer); resolve({ status, out: buf }); });
    child.stdin.end("");
  });
}

test("CLI: jobs add requires --owner; add/list/disable/enable/remove round-trip", { timeout: 120_000 }, async () => {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-sched-cli-"));
  const noOwner = await runCli(["jobs", "add", "--kind", "once", "--schedule", "2099-01-01T09:00:00", "--task", "hi"], home);
  assert.equal(noOwner.status, 1);
  assert.match(noOwner.out, /--owner/);

  const added = await runCli(["jobs", "add", "--owner", "parity-test", "--kind", "once", "--schedule", "2099-01-01T09:00:00", "--task", "say hi", "--id", "job:cli-test"], home);
  assert.equal(added.status, 0, added.out);
  assert.match(added.out, /Added job:cli-test/);

  const list = await runCli(["jobs", "list"], home);
  assert.equal(list.status, 0, list.out);
  assert.match(list.out, /job:cli-test\s+owner=parity-test/);
  assert.match(list.out, /harness:heartbeat/); // seeded on first init

  const dis = await runCli(["jobs", "disable", "job:cli-test"], home);
  assert.equal(dis.status, 0, dis.out);
  const list2 = await runCli(["jobs", "list"], home);
  assert.match(list2.out, /job:cli-test.*DISABLED/);

  const en = await runCli(["jobs", "enable", "job:cli-test"], home);
  assert.equal(en.status, 0, en.out);
  const rm = await runCli(["jobs", "remove", "job:cli-test"], home);
  assert.equal(rm.status, 0, rm.out);
  const list3 = await runCli(["jobs", "list"], home);
  assert.ok(!list3.out.includes("job:cli-test"));
  const rmAgain = await runCli(["jobs", "remove", "job:cli-test"], home);
  assert.equal(rmAgain.status, 1);
});
