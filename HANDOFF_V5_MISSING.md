# HANDOFF — missing features: build these to complete the app (2026-10-15)

Branch: `muse/v5-delta-port`. The 6 harness files there are the base —
`MemoryClaims`, `ApprovalGrants`, `Scheduler`, `SkillStore`,
`NotificationForwardingPolicy`, `ScreenGuard`. Do not modify existing
files except where the wire-in section says so. Everything below is new
code. Python reference (159 checks green) available from Rafael as a zip
if you need line-level behavior.

## 0. Wire-in work first (flagged earlier)

- `MemoryClaims.kt`: Room entity + DAO are in the file; wire into
  `UltraDatabase` (needs version bump + migration).
- `Scheduler.kt`: pure policy model; firing via WorkManager/AlarmManager.
- `ScreenGuard.kt`: hook into the accessibility service; integration
  point noted in the file header.

## 1. Connectors as CLI tools

Pattern (from the reverse-engineering): every external service is a
connector — a small CLI wrapping the vendor's API. The agent never
touches vendor SDKs directly.

- `Connector` interface: `id()`, `isConnected()`, `connect()`,
  `status()` (health check), `operations()` (list of supported ops).
- Each connector ships a doc: setup flow, operations, status check,
  error handling. Status is checked before any use; if not connected,
  surface the connect flow and stop — never promise the outcome.
- Priority order: gmail, google_calendar, google_drive, google_docs.
  Then: spotify, plaid, stripe — only if the user connects them.
- Every connector call goes through the approval flow (sends need
  recipient grants; reads are approval-none).
- No credentials in code, logs, or memory. Secrets live in the
  platform credential store; the agent only sees opaque handles.

## 2. Trash (recoverable delete)

Replaces permanent delete everywhere in the app:

- `trash.move(path)` → returns a trash id, records original location
  and timestamp. Nothing is ever hard-deleted by the agent.
- `trash.list()` → entries with original paths, newest first.
- `trash.restore(id)` → moves back to the original location
  (or app-chosen location if the original is gone).
- The user empties trash in settings; the agent cannot empty it.

## 3. Forget with confirm

Privacy deletion flow for "forget X":

1. **Plan** — find every copy: memory claims, daily notes, derived
   work that could bring it back (embeddings, indexes, drafts).
2. **Show the plan** — scope and limits, in plain language.
3. **User confirms** — only then execute. No silent partial cleanup.
4. **Execute + verify** — delete, then re-search to prove it's gone.
   Report what was removed and what (if anything) couldn't be.

Memory deletion is best-effort across stores; say so honestly.

## 4. Subagent orchestration

Full lifecycle for delegated work:

- `spawn(task)` → child agent with its own context, runs async.
- `send(id, message)` / `interrupt` → steer a running child.
- `list()` → status of children (running/done/failed), no polling loops.
- `resume(id)` → restart an interrupted child on its own task.
- `close(id)` → stop one.
- Coordinator pattern: one coordinator fans out to children; children
  NEVER spawn their own (nesting stops at depth 1).
- Results arrive as handoffs; a finished child reports back, the parent
  verifies before using the result.

## 5. Muse-app surfaces (in scope per Rafael: all of it)

- **Feed** — hourly-generated personal briefing: units with source
  prompts, user ordering, engagement tracking. Generation runs on the
  scheduler; the user controls the feed prompt.
- **Ideas** — idea cards from the user's real context, ranked by
  feasibility/fit/novelty; user acts on or dismisses them.
- **Artifacts** — documents, pages, apps the user keeps and revisits.
  Built via dedicated tools, editable, shareable. Distinct from chat
  widgets (transient) — artifacts persist.
- **Proactivity** — background suggestions surfaced only when genuinely
  useful and new; never nagging. User preferences gate what surfaces.

## 6. Standing rules (do not regress — full list)

- The agent informs and asks; it does not permit or forbid.
- Products = legitimate-product/company verification and scam screening
  only. "I don't wanna find illegal items."
- Onion exclusion stays a SEPARATE shopping-layer function
  (`exclude_onion` / `isOnionUrl`) — never baked into URL extraction
  or autonomous browsing.
- Tor is opt-in, default OFF. Onion URLs NEVER fall back to clearnet.
- Human verification (CAPTCHA etc.): park with the exact link/action,
  wait for the user. Never solve, bypass, or negotiate.
- Never type into password fields. Screen actions bound to the screen
  they were read from; stale screens refused.
- SMS/email codes and reset links never reach the cloud model.
- Purchases always need fresh approval. No lasting grant, anywhere.
- The agent cannot see the approval card and cannot revoke grants;
  the user does that in settings.
- Consequential actions (send, post, purchase, delete) stop at
  confirmation. The user decides.
- GitHub is described neutrally as "GitHub", never "Portfolio".
- Only use raffleraffle1528@gmail.com unless Rafael explicitly asks
  otherwise. Never use @arbloodhound.xyz addresses or "Arbloodhound"
  as a username unasked.

## 7. Verification discipline

- Every new module ships with tests mirroring the Python reference
  behavior. Nothing merges with a red suite.
- Kotlin is source-only until CI compiles it — keep it compiling.
- Do not modify existing files except the three wire-ins in §0.
- MIT attribution: any OpenClaw-derived code keeps the copyright +
  license notice in the file. Upstream URLs are in the headers of
  `ScreenGuard.kt` / `NotificationForwardingPolicy.kt`.
- Report when done: what was added (files + one line each), what was
  deliberately not built and why.
