> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/HANDOFF.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# Jarvis — Session Handoff (current state, 2026-10-19 post-merge)

Self-contained pickup doc. Read this first, then `CLAUDE.md` and `MERGE_LOG_2026-10-19.md`.

## Where things are

- **Repo:** `Arbloodhound/Jarvis` (private). **All work is now on `main`** (tip `c8043e1c`). `claude/shopping-integration` (`adbd9e84`) is also current. The old work branch `muse/v5-delta-port` was merged during consolidation (merge `4cff5d07`); do not start new work there.
- **Branches:** 58 total — 51 were already contained, 2 merged in consolidation, 4 remain unmerged by design (`claude/broken-fixes`, `claude/download-all`, `claude/fast-reasoning` — empty tip commits; `claude/offline-navigation-wip` — superseded WIP snapshot). No branch was deleted; no history was rewritten.
- **Tree:** `jarvis-native/` Kotlin Android app (the shipping app, package `com.agent.jarvis`), `desktop/` TypeScript app, `src/` TS app (Expo/RN lineage), and the harness package `com.agent.jarvis.harness` inside `jarvis-native`.
- **CI has not yet compiled the merged tree.** Kotlin is source-only until CI runs. Audit verification was grep-level (definitions / call sites / imports), not compilation.

## Audited state

- Pass 2 audit (main lineage + harness delta): 533/536 files read; 119 defects — 1 critical, 16 high, 41 medium, 61 low; 4 broken-reference clusters; ~70 dead-code candidates.
- Supplement audit (ccr branch, 118/118 files): 87 findings — 0 critical, 12 high, 31 medium, 44 low.
- Combined framing: 206 findings across two audits of different snapshots (not a deduplicated count of the merged tree). The pass-2 critical is resolved in the merged tree, so post-merge there are **0 known criticals**.

## Resolved in the merged tree

- **Harness DB wiring (was the 1 critical):** `JarvisDatabase` is v9; entities include `ClaimEntity` / `GrantEntity` / `MemoryEmbeddingEntity` / `BanditArmEntity`; DAO accessors `claims()` / `claimForget()` / `grants()` / `embeddings()` / `banditArms()`; `MIGRATION_7_8` + `MIGRATION_8_9` defined and registered. Field-verified against the harness entities by the supplement audit.

## Harness package (exists in the merged tree)

`com.agent.jarvis.harness` contains: MemoryClaims (kind/salience/confidence, provenance, supersede chains, hybrid search + query-time temporal decay), ApprovalGrants (grant levels + command binding), Scheduler + SchedulerRuntime/SchedulerReceiver, SkillStore (lazy skills), NotificationForwardingPolicy (3-gate), ScreenGuard, Trash, Forget, Subagents (depth-1 by construction), Feed, Ideas, Artifacts, Proactivity, ClaimVerifier, NLI head, bandit (ForYou), and `connectors/` (Gmail, Google Calendar/Docs/Drive, Spotify; CredentialVault Keystore AES-GCM; SecretScrubber).

## Still open (post-merge)

- `SchedulerReceiver` and `SpotifyRedirectActivity` exist in the harness but are **not registered in `AndroidManifest.xml`** (2 manifest registrations).
- `HistoryPolicyTest.kt` / `MemoryBriefTest.kt` import `com.agent.jarvis.bench.TestKitActivity`, which exists only in `src/debug` — the unit-test source set cannot resolve it (2 broken unit tests).
- TS `server/` is orphaned: nothing calls it, it registers no routes, and it crashes at startup in the audited tree.
- ~70 dead-code candidates (zero call sites) — candidates only; reflection/JNI/framework callbacks can make some live.
- CI compile of the merged tree is pending.

Earlier per-feature runtime notes, release numbers, and phone-test instructions from the previous version of this handoff are **not re-verified in the 2026-10-19 audits** and are preserved only in the archive copy.

## Standing rules (must match any behaviour statement)

- The agent informs and asks; it does not permit or forbid. Consequential actions (send, post, purchase, delete) stop at confirmation; the user decides.
- Products/shopping = legitimate-product/company verification + scam screening only. "Plugs" = known non-scammers / trusted sources.
- Tor is opt-in, default OFF. Onion URLs never fall back to clearnet. Onion exclusion stays a separate shopping-layer function.
- Human verification (CAPTCHA etc.) is the user's: park with the exact link/action, never solve or bypass.
- Never type into password fields. Screen actions are bound to the snapshot they were planned from; stale screens are refused.
- SMS/email codes and reset links never reach the cloud model (opaque placeholder references only).
- Purchases always need fresh approval. No lasting grant covers a purchase, anywhere.
- The agent cannot see the approval card and cannot revoke grants; the user manages grants in settings.
- Credentials live in the platform vault; the agent sees opaque handles only. Trash is emptied by the user in settings, never by the agent.

## Checked clean in the audits

- Tor fail-closed, verified in both audits (three independent guards in TorProxy; no clearnet fallback; only fallback is in-app Tor → Orbot; tests assert it).
- Gate deny-by-default; taint blocks can neither be auto-approved nor operator-overridden; manifest load failure = deny-all.
- Shopping: onion exclusion is a separate function (`excludeOnion`/`isOnionUrl`) applied by ShoppingRun; ShoppingCheckout has no purchase path (prepare/display/track only), by design.
- Harness: purchases are never grant-coverable at either layer; approval tokens bound to exact sorted-args key; Trash has no agent-facing empty/purge; Subagents depth-1 by construction; SecretScrubber + 10-minute placeholder expiry as documented; KeystoreVault AES-GCM; no hardcoded secrets in the 45 harness files; ClaimVerifier lenses fail closed.
- Screen reads/actions fail closed on protected apps; screen memory stores structure only, no screen text/values.

## Next steps for a new session

1. Run CI on `main` and fix compile errors first — nothing else is proven until the merged tree compiles.
2. Register `SchedulerReceiver` and `SpotifyRedirectActivity` in `AndroidManifest.xml`.
3. Fix the 2 broken unit tests (debug-only `TestKitActivity` import).
4. Decide the fate of the orphaned TS `server/` and triage the ~70 dead-code candidates and the audit highs listed in `AUDIT_REPORT.md` / `AUDIT_SUPPLEMENT.md`.
