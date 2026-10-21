> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/MUSE_HANDOFF.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# MUSE HANDOFF — current state (2026-10-19 post-merge)

Read this first, then `HANDOFF_V5_MISSING.md` (now a current status doc, not a build request), `CLAUDE.md`, and `MERGE_LOG_2026-10-19.md`.

## 0. Layering (locked 2026-10-15)

- **L1** = the Muse reverse-engineering — the v5 harness is this layer; nothing in it gets replaced, everything adapts to it.
- **L2** = OpenClaw concrete code adopted into the L1 contracts where superior (SQLite memory, credential proxy, lazy skills, owner-tagged jobs, screen guard, notification filter, command-tied approvals). OpenClaw is MIT-licensed; borrowing requires preserving its copyright/license notice.
- **L3** = project code only where L1+L2 are missing, plus unique features (voice, products, shopping, investigate, router, NLI gate).

The six harness base files named in the previous version (`MemoryClaims`, `ApprovalGrants`, `Scheduler`, `SkillStore`, `NotificationForwardingPolicy`, `ScreenGuard`) are part of the harness package described below. Decisions about changing their behaviour remain the owner's; this doc does not re-litigate them.

## 1. State right now

- **All work is on `main`** (tip `c8043e1c`). The former work branch `muse/v5-delta-port` and `ccr-c9b6f798-uako9v` were merged into `claude/shopping-integration`, which was merged into `main` on 2026-10-19. See `MERGE_LOG_2026-10-19.md` for the full 58-branch sweep.
- **CI has not compiled the merged tree.** Kotlin is source-only until CI runs. Audit verification was grep-level, not compilation.
- Release numbers, test counts, and PR states from the previous version are **not re-verified in the 2026-10-19 audits** and are preserved only in the archive copy.

## 2. What the harness package now contains

`com.agent.jarvis.harness` in the merged tree contains: MemoryClaims (kind/salience/confidence, provenance, supersede chains, hybrid search + query-time temporal decay), ApprovalGrants (grant levels + command binding), Scheduler + SchedulerRuntime/SchedulerReceiver, SkillStore (lazy skills), NotificationForwardingPolicy (3-gate), ScreenGuard, Trash, Forget, Subagents (depth-1 by construction), Feed, Ideas, Artifacts, Proactivity, ClaimVerifier, NLI head, bandit (ForYou), and `connectors/` (Gmail, Google Calendar/Docs/Drive, Spotify; CredentialVault Keystore AES-GCM; SecretScrubber).

Earlier framings of these as "proposed", "awaiting approval", or "not yet built" are superseded: the code exists in the merged tree per the audits. What remains open is wiring and verification, listed in §4 — not construction.

## 3. Resolved

- **Harness DB wiring (was the pass-2 critical):** JarvisDatabase is v9; entities include ClaimEntity/GrantEntity/MemoryEmbeddingEntity/BanditArmEntity; DAO accessors `claims()`/`claimForget()`/`grants()`/`embeddings()`/`banditArms()`; MIGRATION_7_8 + MIGRATION_8_9 defined and registered. Field-verified by the supplement audit.
- Audits: pass 2 found 119 defects (1 critical / 16 high / 41 medium / 61 low); supplement found 87 findings (0 critical / 12 high / 31 medium / 44 low). Post-merge: **0 known criticals**.

## 4. Still open

- 2 manifest registrations: `SchedulerReceiver` and `SpotifyRedirectActivity` exist but are not registered in `AndroidManifest.xml`.
- 2 broken unit tests: `HistoryPolicyTest.kt` / `MemoryBriefTest.kt` import the debug-only `com.agent.jarvis.bench.TestKitActivity`.
- TS `server/` orphaned (nothing calls it; crashes at startup in the audited tree).
- ~70 dead-code candidates (zero call sites; candidates only).
- CI compile of the merged tree pending.
- Harness grant keying is the weak core per the audits (grant invariants) — see `AUDIT_REPORT.md` / `AUDIT_SUPPLEMENT.md` for the full high-severity list.

Items the previous version listed as "not built / not wired" that are **not covered by the 2026-10-19 audits** (for example specific OAuth client registration steps and runner/generator details) are marked **not re-verified in the 2026-10-19 audits**; do not treat them as current status without a fresh check.

## 5. Checked clean (safe to state as verified)

- Gate deny-by-default; taint blocks can neither be auto-approved nor operator-overridden.
- Purchases are never grant-coverable at either layer; Trash has no agent-facing empty/purge (structural, reflection-tested); Subagents depth-1 by construction; SecretScrubber + 10-minute placeholder expiry as documented; KeystoreVault AES-GCM; no hardcoded secrets in the 45 harness files; ClaimVerifier lenses fail closed.
- Screen reads/actions fail closed on protected apps; screen memory stores structure only.

## 6. Standing rules

- The agent informs and asks; it does not permit or forbid. Consequential actions stop at confirmation; the user decides.
- Purchases always need fresh approval. No lasting grant covers a purchase, anywhere.
- SMS/email codes and reset links never reach the cloud model (opaque placeholders only).
- Never type into password fields. Screen actions bound to their snapshot; stale screens refused.
- Human verification is the user's: park, never solve or bypass.
- MIT attribution stays in OpenClaw-derived files.
