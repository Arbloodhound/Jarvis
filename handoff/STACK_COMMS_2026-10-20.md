# STACK C — memory/comms (echolalia + notifications) — 2026-10-20

**Result: ONE stack branch, fully gated. Main not touched.**

- **Branch:** `claude/desktop-stack-comms-2026-10-20`
- **Stack commit:** `6b714ee1a1a27e86d1d96f186daea6e7ba78dd48` (single commit, parent = base tip; ref re-verified via API after push; remote desktop/ subtree verified blob-for-blob == the gated local tree, 64 files)
- **Base (current main tip per PARITY_MERGE_CHAIN_2026-10-20.md at build start; live ref agreed):** `fa5e16e951fc647de8c472e752c24db30c45a944` — router/nli/voice merged, suite 177.
- **Inputs (fork base `fc22170776e7b50ab4002b1568ed14bc93c5d0ec`):**
  - `claude/desktop-parity-echolalia-2026-10-20` @ `de051c854b03c7ffed9da1e083c833d32f595aa2` (+12 tests)
  - `claude/desktop-parity-notifications-2026-10-20` @ `e5f047e01db0bc163e4d6ad69e8eb895cb1ae8aa` (+16 tests)
- **Gate on the merged tree:** `npm install` (306 pkgs) → `tsc --noEmit` CLEAN → `npm test` **205/205 pass, 0 fail** (= 177 + 12 + 16, exactly as expected; suite duration ~160.6 s, incl. slow CLI voice tests @ 3–11 s each — not a hang).
- All four input trees (base / main tip / echolalia / notifications) were fetched via the trees API and **verified blob-for-blob against GitHub** before merging (`stack-comms/verify-trees.py`, ALL OK).
- prep-b reuse: its staged repos were built against an older "ours" (c60f6e06-era, pre router/nli/voice) and shipped no SUMMARY/NOTES. I used its per-file touch lists as a conflict forecast only; the merges below are fresh three-way merges against fa5e16e9 in `stack-comms/repo` (local merge commits 5786d22 = echolalia, f018092 = notifications).

## Merge 1 — echolalia onto fa5e16e9

Auto-merged clean: `README.md`, `src/agent/fast.ts`, `src/cli.ts`, `src/memory.ts` (branch version taken wholesale — main never touched the file), new `src/echolalia.ts`, new `test/echolalia.test.ts`. The constructor in runtime.ts also auto-composed correctly (echolalia dep + `audit: this.audit` alongside main's transcriber dep, Tor toolsFor opts, nli block, conversations resume).

Conflicts (4 files) and resolutions — all composed, nothing dropped:

| File | Hunks | Resolution |
|---|---|---|
| `src/config.ts` | 3 (type / defaults / loadConfig) | Settings neighbours: HEAD's `tor` + `nli` blocks and the branch's `memory: { verifyBeforeStore }` block all kept, in all three places. |
| `src/runtime.ts` | 4 | Fields: `conversations`/`cwd` + `audit`/`pending` all kept. `update()` signature gains both `tor?: boolean` and `verifyBeforeStore?: boolean`. Body: Tor block + verifyBeforeStore line both kept. `state()`: `lastRoute` + `verifyBeforeStore`/`pendingMemory`/`quarantinedMemory` all kept. |
| `src/agent/loop.ts` | 1 | `ToolContext` literal composed: `echolalia: this.deps.echolalia?.() ?? null` AND `transcriber: this.deps.transcriber?.() ?? null`. |
| `src/tools/index.ts` | 1 | `ALL_TOOLS`: `transcribeAudioTool, speakTool` + `rememberTool, recallTool, forgetTool, memoryPendingTool, memoryDecideTool` + `...shoppingTools()`. |

Recorded slip, caught and fixed before any gate: the first runtime.ts `update()` edit consumed HEAD-side lines above the signature — the `torReachable` field and `refreshTorStatus()` method. Restored verbatim in the same pass; the gated tree contains them (`state()` exposes `torReachable`; tor tests pass).

## Merge 2 — notifications onto the above

Auto-merged clean: new `src/notifications/filter.ts`, new `src/notifications/inbox.ts`, new `test/notifications.test.ts`, and `config.ts` Paths (`notificationsInbox: path.join(home, "notifications-inbox.jsonl")`).

Conflicts (4 files) and resolutions:

| File | Hunks | Resolution |
|---|---|---|
| `src/config.ts` | 3 (type / defaults / loadConfig) | Same three places: `notifications` block composed alongside tor/nli/memory — type with the branch's doc comment (`allowedApps`, `quietStart`, `quietEnd`, `maxPerMinutePerApp`), defaults `{ allowedApps: [], quietStart: 22, quietEnd: 7, maxPerMinutePerApp: 5 }`, loadConfig spread. |
| `src/runtime.ts` | 6 | Imports: `makeWebFetch, probeTorProxy, toolsFor` kept + `NotificationFilter`/`NotificationInbox` imports added. Fields: all existing + `notifications: NotificationInbox`. Constructor: `audit`/`pending` inits kept, `NotificationInbox` init appended (kept the branch's own `audit: new AuditLog(p.audit)` exactly as the branch wrote it). Agent deps: `toolsFor({...})` = main's opts (tor, ttsEnabled) + `notifications: this.notifications`. Methods: `transcriber()` kept whole; `ingestNotification()` + `drainNotificationInbox()` added. `state()`: tor/torPort/torReachable/nli fields + `notificationsAllowedApps`/`notificationsPending`. |
| `src/server.ts` | 2 | Imports: `type Turn` kept + `asNotificationInput` added. Routes: `POST /api/upload` kept; the branch's full authenticated `POST /api/notifications` block (gateway token required — Bearer / x-jarvis-token / ?token=, 401 otherwise; 1 MB body cap; drains the inbox file first; per-item results) inserted after it, before the 404. |
| `src/tools/index.ts` | 3 | Imports: voice import kept + `formatPending, type NotificationInbox` added. `ALL_TOOLS`: `readNotificationsTool` inserted after `notifyTool`; rest as composed in Merge 1. `toolsFor` signature: main's opts + `notifications?: NotificationInbox`. Body: structural divergence (branch returned early on cloudVision; main assigns and continues) resolved in main's assign-and-return shape — notifications binding (`bindReadNotifications`) first, then the cloudVision confirm wrap, then the Tor fetch binding, single `return out`. |

## memory.ts hunk map — for the claims branch landing

Diff is against main tip fa5e16e9. Echolalia's changes to `src/memory.ts` are exactly **two hunks, +4/−2 lines**, and the merged file is byte-identical to the echolalia branch's version (blob `df48cfe2ab`). Notifications does not touch memory.ts.

- **M1 — `export interface Fact`, immediately after `at: string;` (+2 lines):**
  ```ts
  /** How this fact earned its place (e.g. echolalia verification), when recorded. */
  provenance?: string;
  ```
  Purely additive optional field; no existing field moved or retyped.
- **M2 — `Memory.remember`, signature + fact literal (2 lines changed):**
  - `remember(text: string): Fact` → `remember(text: string, provenance?: string): Fact` (optional second positional — backward compatible).
  - New-fact literal: `{ id, text: t, at: … }` → `{ id, text: t, at: …, ...(provenance ? { provenance } : {}) }` — a fact stored without provenance serializes exactly as before. The case-insensitive dedupe early-return above it is unchanged.

**Sole consumer:** `EcholaliaStore.promote()` in `src/echolalia.ts` (~line 322) calls
`this.memory.remember(entry.claim, "echolalia: <why>; source: <entry.source>; echoes: <n>; pending id <entry.id>")`.
(The remember *tool* in tools/index.ts still calls `ctx.memory.remember(fact)` single-arg.)

**Composition guidance for claims:** its `Fact` additions sit beside `provenance` with no textual or semantic interaction. Its `remember()` changes must preserve the positional `(text, provenance?)` shape — or update that one call site in echolalia.ts. Nothing else in either parity branch reads or writes `Fact.provenance`.

## Stack delta vs base tip (14 paths)

M `desktop/README.md`, M `src/agent/fast.ts`, M `src/agent/loop.ts`, M `src/cli.ts`, M `src/config.ts`, A `src/echolalia.ts`, M `src/memory.ts`, A `src/notifications/filter.ts`, A `src/notifications/inbox.ts`, M `src/runtime.ts`, M `src/server.ts`, M `src/tools/index.ts`, A `test/echolalia.test.ts`, A `test/notifications.test.ts`.
