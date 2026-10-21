import assert from "node:assert/strict";
import { appendFileSync, existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { createServer as netServer } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { defaults, loadConfig, paths } from "../src/config.ts";
import { AuditLog } from "../src/gate/audit.ts";
import { NotificationFilter, OWN_PACKAGE } from "../src/notifications/filter.ts";
import { NotificationInbox } from "../src/notifications/inbox.ts";
import { Runtime } from "../src/runtime.ts";
import { startServer } from "../src/server.ts";
import { ALL_TOOLS, toolsFor, type ToolContext } from "../src/tools/index.ts";

/**
 * Inbound notifications: the phone filter's three gates, ported 1:1
 * (features/notifications.py), plus the desktop intakes — the token-gated
 * gateway endpoint and the inbox file drop — which both feed the same
 * filter choke point. Drops never reach the agent but are always audited.
 *
 * Times are pinned with local Date construction, so the quiet-hours
 * expectations hold in any timezone.
 */

/** Epoch ms at a given local hour on a fixed day. */
const atHour = (h: number, minute = 0) => new Date(2026, 0, 15, h, minute, 0, 0).getTime();
const NOON = atHour(12);

function ctxStub(): ToolContext {
  return {
    cwd: "/tmp",
    workspace: "/tmp",
    memory: null as unknown as ToolContext["memory"],
    embedder: null,
    vision: null,
    signal: new AbortController().signal,
  };
}

function tempHome(prefix: string) {
  const home = mkdtempSync(path.join(tmpdir(), prefix));
  return { home, p: paths(home) };
}

// ---------- the filter: gate 1 ----------

test("gate 1: an allowed app passes; a non-allowed app is dropped as not_allowed", () => {
  const f = new NotificationFilter({ allowedApps: ["com.example.mail"] });
  const ok = f.check({ app: "com.example.mail", title: "Hi", at: NOON });
  assert.equal(ok.verdict.allowed, true);
  const no = f.check({ app: "com.example.spam", title: "Hi", at: NOON });
  assert.deepEqual(no.verdict, { allowed: false, reason: "not_allowed", gate: 1 });
});

test("gate 1: own package, own name and the phone own package are always excluded — even when allowed", () => {
  const f = new NotificationFilter({ allowedApps: [OWN_PACKAGE, "Jarvis", "ai.jarvis.app"] });
  for (const app of [OWN_PACKAGE, "Jarvis", "ai.jarvis.app"]) {
    const v = f.check({ app, title: "Self", at: NOON });
    assert.deepEqual(v.verdict, { allowed: false, reason: "own_app", gate: 1 }, app);
  }
});

test("gate 1: a custom own package is honoured", () => {
  const f = new NotificationFilter({ allowedApps: ["com.example.self"], ownPackage: "com.example.self" });
  assert.equal(f.check({ app: "com.example.self", title: "Self", at: NOON }).verdict.allowed, false);
});

// ---------- the filter: gate 2 ----------

test("gate 2: default quiet hours 22–07 wrap around midnight", () => {
  const f = new NotificationFilter({ allowedApps: ["a"] });
  assert.equal(f.inQuietHours(atHour(22)), true, "start is inclusive");
  assert.equal(f.inQuietHours(atHour(23)), true);
  assert.equal(f.inQuietHours(atHour(0)), true);
  assert.equal(f.inQuietHours(atHour(6)), true);
  assert.equal(f.inQuietHours(atHour(7)), false, "end is exclusive");
  assert.equal(f.inQuietHours(atHour(12)), false);
  assert.equal(f.inQuietHours(atHour(21)), false);
  const dropped = f.check({ app: "a", title: "Late", at: atHour(23) });
  assert.deepEqual(dropped.verdict, { allowed: false, reason: "quiet_hours", gate: 2 });
});

test("gate 2: a non-wrapping quiet window works the same way", () => {
  const f = new NotificationFilter({ allowedApps: ["a"], quietStart: 9, quietEnd: 17 });
  assert.equal(f.inQuietHours(atHour(9)), true);
  assert.equal(f.inQuietHours(atHour(16)), true);
  assert.equal(f.inQuietHours(atHour(17)), false);
  assert.equal(f.inQuietHours(atHour(8)), false);
});

// ---------- the filter: gate 3 ----------

test("gate 3: five per minute pass, the sixth is rate_limited, the next minute resets", () => {
  const f = new NotificationFilter({ allowedApps: ["a"] });
  for (let i = 0; i < 5; i++) {
    assert.equal(f.allow({ app: "a", title: `n${i}`, at: NOON + i * 1000 }), true, `n${i}`);
  }
  const sixth = f.check({ app: "a", title: "n5", at: NOON + 5000 });
  assert.deepEqual(sixth.verdict, { allowed: false, reason: "rate_limited", gate: 3 });
  // A new fixed window: the same app passes again.
  assert.equal(f.allow({ app: "a", title: "next minute", at: NOON + 60_000 }), true);
});

test("gate 3: the burst limit is per app, and drops at earlier gates consume no slots", () => {
  const f = new NotificationFilter({ allowedApps: ["a", "b"] });
  for (let i = 0; i < 5; i++) f.allow({ app: "a", title: `n${i}`, at: NOON });
  assert.equal(f.allow({ app: "a", title: "over", at: NOON }), false);
  assert.equal(f.allow({ app: "b", title: "other app", at: NOON }), true, "b has its own bucket");

  // Drops at gate 1 never reach the burst check, so they consume no slots:
  // ten not-allowed drops in the same minute leave a's five slots intact.
  const h = new NotificationFilter({ allowedApps: ["a"] });
  for (let i = 0; i < 10; i++) h.allow({ app: "zzz", title: "no", at: NOON });
  for (let i = 0; i < 5; i++) assert.equal(h.allow({ app: "a", title: `n${i}`, at: NOON }), true);
});

test("filter: the injectable clock supplies `at` when a notification has none", () => {
  const f = new NotificationFilter({ allowedApps: ["a"], now: () => NOON });
  const { verdict, notification } = f.check({ app: "a", title: "No time given" });
  assert.equal(verdict.allowed, true);
  assert.equal(notification.at, NOON);
  assert.equal(notification.text, "");
});

test("filter(): returns only the passing notifications, in order (the Python filter's shape)", () => {
  const f = new NotificationFilter({ allowedApps: ["a"] });
  const out = f.filter([
    { app: "a", title: "one", at: NOON },
    { app: "nope", title: "two", at: NOON },
    { app: "a", title: "three", at: NOON },
  ]);
  assert.deepEqual(out.map((n) => n.title), ["one", "three"]);
});

// ---------- config ----------

test("config: notification defaults are the phone filter's (quiet 22–07, 5/min, nothing allowed)", () => {
  const { p } = tempHome("jarvis-notif-cfg-");
  const d = defaults(p);
  assert.deepEqual(d.notifications, { allowedApps: [], quietStart: 22, quietEnd: 7, maxPerMinutePerApp: 5 });
  const loaded = loadConfig(p);
  assert.deepEqual(loaded.notifications, d.notifications);
  assert.equal(p.notificationsInbox, path.join(p.home, "notifications-inbox.jsonl"));
});

test("config: a saved notifications section merges over the defaults", () => {
  const { p } = tempHome("jarvis-notif-cfg2-");
  writeFileSync(p.config, JSON.stringify({ notifications: { allowedApps: ["Slack"], quietStart: 23 } }) + "\n");
  const loaded = loadConfig(p);
  assert.deepEqual(loaded.notifications, { allowedApps: ["Slack"], quietStart: 23, quietEnd: 7, maxPerMinutePerApp: 5 });
});

test("config: a null allowedApps falls back to the default, so the runtime's state() still builds", () => {
  const { home, p } = tempHome("jarvis-notif-cfg3-");
  writeFileSync(p.config, JSON.stringify({ notifications: { allowedApps: null } }) + "\n");
  const loaded = loadConfig(p);
  assert.deepEqual(loaded.notifications.allowedApps, []);
  const rt = new Runtime(loaded, p, home);
  assert.equal(rt.state().notificationsAllowedApps, 0);
});

test("config: a string allowedApps is not split into characters; non-string entries are dropped", () => {
  const { p } = tempHome("jarvis-notif-cfg4-");
  writeFileSync(p.config, JSON.stringify({ notifications: { allowedApps: "com.whatsapp" } }) + "\n");
  assert.deepEqual(loadConfig(p).notifications.allowedApps, []);
  writeFileSync(p.config, JSON.stringify({ notifications: { allowedApps: ["com.whatsapp", 7, null, "Slack"] } }) + "\n");
  assert.deepEqual(loadConfig(p).notifications.allowedApps, ["com.whatsapp", "Slack"]);
});

test("config: quiet hours must be integers 0–23 and the rate a positive integer, else the default", () => {
  const { p } = tempHome("jarvis-notif-cfg5-");
  const d = defaults(p).notifications;
  // Raw JSON text, so 1e400 (parses to Infinity) and fractional values reach loadConfig as written.
  for (const bad of ["24", "-1", "7.5", '"8"', "null"]) {
    writeFileSync(p.config, `{"notifications":{"quietStart":${bad},"quietEnd":${bad}}}\n`);
    const n = loadConfig(p).notifications;
    assert.equal(n.quietStart, d.quietStart, `quietStart ${bad}`);
    assert.equal(n.quietEnd, d.quietEnd, `quietEnd ${bad}`);
  }
  for (const bad of ["0", "-3", "2.5", '"10"', "null", "1e400"]) {
    writeFileSync(p.config, `{"notifications":{"maxPerMinutePerApp":${bad}}}\n`);
    assert.equal(loadConfig(p).notifications.maxPerMinutePerApp, d.maxPerMinutePerApp, `maxPerMinutePerApp ${bad}`);
  }
  writeFileSync(p.config, JSON.stringify({ notifications: { quietStart: 0, quietEnd: 23, maxPerMinutePerApp: 1 } }) + "\n");
  assert.deepEqual(loadConfig(p).notifications, { allowedApps: [], quietStart: 0, quietEnd: 23, maxPerMinutePerApp: 1 });
});

// ---------- the inbox choke point ----------

test("inbox: passing notifications queue and are delivered exactly once; drops are audited, never queued", () => {
  const { p } = tempHome("jarvis-notif-inbox-");
  const inbox = new NotificationInbox({
    filter: new NotificationFilter({ allowedApps: ["com.example.mail"] }),
    audit: new AuditLog(p.audit),
    inboxFile: p.notificationsInbox,
  });
  const ok = inbox.ingest({ app: "com.example.mail", title: "Hello", text: "Body", at: NOON }, "gateway");
  assert.equal(ok.allowed, true);
  const dropped = inbox.ingest({ app: "com.example.spam", title: "Buy", at: NOON }, "gateway");
  assert.equal(dropped.allowed, false);
  assert.equal(inbox.pendingCount, 1);

  const audit = readFileSync(p.audit, "utf8");
  assert.ok(audit.includes('"kind":"notification"') || audit.includes('"kind": "notification"') || audit.includes("notification"), audit);
  assert.ok(audit.includes("not_allowed"), audit);
  assert.ok(audit.includes("com.example.spam"), audit);
  assert.ok(!audit.includes("Hello"), "passing notifications are not audit drops");

  const first = inbox.takePending();
  assert.equal(first.length, 1);
  assert.equal(first[0]!.app, "com.example.mail");
  assert.equal(first[0]!.title, "Hello");
  assert.equal(first[0]!.text, "Body");
  assert.equal(first[0]!.at, NOON);
  assert.deepEqual(inbox.takePending(), [], "delivered-once: the second take is empty");
});

test("inbox file drop: lines are drained through the same filter, the file is emptied, malformed lines are audited", () => {
  const { p } = tempHome("jarvis-notif-file-");
  const inbox = new NotificationInbox({
    filter: new NotificationFilter({ allowedApps: ["Slack"] }),
    audit: new AuditLog(p.audit),
    inboxFile: p.notificationsInbox,
  });
  appendFileSync(p.notificationsInbox, JSON.stringify({ app: "Slack", title: "Standup", text: "in 10", at: NOON }) + "\n");
  appendFileSync(p.notificationsInbox, JSON.stringify({ app: "Ads", title: "Sale", at: NOON }) + "\n");
  appendFileSync(p.notificationsInbox, "this is not json\n");

  const got = inbox.takePending();
  assert.equal(got.length, 1, "only the allowed line is delivered");
  assert.equal(got[0]!.title, "Standup");
  assert.equal(readFileSync(p.notificationsInbox, "utf8"), "", "the file is emptied by the drain");
  assert.deepEqual(inbox.takePending(), [], "nothing is re-delivered");

  const audit = readFileSync(p.audit, "utf8");
  assert.ok(audit.includes("not_allowed"), audit);
  assert.ok(audit.includes("invalid"), audit);
  assert.ok(existsSync(p.notificationsInbox));
});

// ---------- the agent surface ----------

test("read_notifications tool: bound to the runtime's inbox, returns app/title/text/time, delivered once", async () => {
  const { home, p } = tempHome("jarvis-notif-tool-");
  const cfg = loadConfig(p);
  // A quiet window that cannot contain the test's "now": two hours wide,
  // starting three hours from the current hour.
  const h = new Date().getHours();
  cfg.notifications = { allowedApps: ["com.example.mail"], quietStart: (h + 3) % 24, quietEnd: (h + 5) % 24, maxPerMinutePerApp: 5 };
  const rt = new Runtime(cfg, p, home);
  rt.ingestNotification({ app: "com.example.mail", title: "Report ready", text: "See attached" }, "gateway");

  const tools = toolsFor({ offlineOnly: false, screenControl: false, notifications: rt.notifications });
  const tool = tools.find((t) => t.name === "read_notifications");
  assert.ok(tool, "read_notifications is in the tool list");
  const out = await tool!.run({}, ctxStub());
  assert.ok(out.includes("com.example.mail"), out);
  assert.ok(out.includes("Report ready"), out);
  assert.ok(out.includes("See attached"), out);
  const again = await tool!.run({}, ctxStub());
  assert.equal(again, "No new notifications.");
});

test("the outbound notify tool is untouched and read_notifications is read-only", () => {
  const notify = ALL_TOOLS.find((t) => t.name === "notify");
  assert.ok(notify);
  assert.equal(notify!.description, "Show a desktop notification.");
  const read = ALL_TOOLS.find((t) => t.name === "read_notifications");
  assert.ok(read);
  assert.deepEqual(read!.policy.effects, ["read"]);
  assert.equal(read!.parallelSafe, true);
});

// ---------- the gateway endpoint ----------

async function freePort(): Promise<number> {
  return new Promise((resolve) => {
    const s = netServer().listen(0, "127.0.0.1", () => {
      const a = s.address();
      s.close(() => resolve(typeof a === "object" && a ? a.port : 0));
    });
  });
}

test("gateway POST /api/notifications: token required, batch filtered, drops audited", async () => {
  const { home, p } = tempHome("jarvis-notif-http-");
  const cfg = loadConfig(p);
  const h = new Date().getHours();
  cfg.notifications = { allowedApps: ["com.example.mail"], quietStart: (h + 3) % 24, quietEnd: (h + 5) % 24, maxPerMinutePerApp: 5 };
  const rt = new Runtime(cfg, p, home);
  const TOKEN = "test-token-notif";
  const port = await freePort();
  const gw = await startServer(rt, TOKEN, port);
  try {
    const url = `http://127.0.0.1:${port}/api/notifications`;
    const batch = [
      { app: "com.example.mail", title: "Allowed one", text: "yes" },
      { app: "com.example.spam", title: "Dropped one" },
      { app: OWN_PACKAGE, title: "Own one" },
    ];
    // No token: refused, and nothing is ingested.
    const noAuth = await fetch(url, { method: "POST", body: JSON.stringify(batch) });
    assert.equal(noAuth.status, 401);
    assert.equal(rt.notifications.pendingCount, 0);
    // Wrong token: refused.
    const wrong = await fetch(url, { method: "POST", headers: { authorization: "Bearer nope" }, body: JSON.stringify(batch) });
    assert.equal(wrong.status, 401);
    assert.equal(rt.notifications.pendingCount, 0);
    // The gateway token: the batch goes through the filter.
    const res = await fetch(url, { method: "POST", headers: { authorization: `Bearer ${TOKEN}` }, body: JSON.stringify(batch) });
    assert.equal(res.status, 200);
    const body = await res.json() as { ok: boolean; accepted: number; dropped: number; results: Array<{ allowed: boolean; reason?: string }> };
    assert.equal(body.ok, true);
    assert.equal(body.accepted, 1);
    assert.equal(body.dropped, 2);
    assert.equal(body.results[1]!.reason, "not_allowed");
    assert.equal(body.results[2]!.reason, "own_app");
    assert.equal(rt.notifications.pendingCount, 1);

    const audit = readFileSync(p.audit, "utf8");
    assert.ok(audit.includes("not_allowed"), audit);
    assert.ok(audit.includes("own_app"), audit);
  } finally {
    gw.server.close();
  }
});
