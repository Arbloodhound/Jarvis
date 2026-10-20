> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/HANDOFF_V5_MISSING.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# HANDOFF — v5 feature status (current state, 2026-10-19 post-merge)

The previous version of this file (2026-10-15) was a build request: it listed the v5 features as missing and to-build on branch `muse/v5-delta-port`. That framing is superseded. After the 2026-10-19 full-branch consolidation, the harness package contains those features and the DB wiring is resolved in the merged tree. This file is now a **current status doc**, not a build request.

**CI has not yet compiled the merged tree.** Kotlin is source-only until CI runs. Audit verification was grep-level (definitions/call sites/imports), not compilation.

## 0. What now exists — harness package `com.agent.jarvis.harness`

Per the 2026-10-19 audits, the package contains:

- **MemoryClaims** — claims with kind/salience/confidence, provenance, supersede chains, hybrid search + query-time temporal decay.
- **ApprovalGrants** — grant levels + command binding.
- **Scheduler** — plus SchedulerRuntime / SchedulerReceiver.
- **SkillStore** — lazy skills.
- **NotificationForwardingPolicy** — 3-gate filter.
- **ScreenGuard.**
- **Trash** — recoverable delete; no agent-facing empty/purge (structural, reflection-tested). The user empties trash in settings.
- **Forget** — privacy deletion flow support in the harness (see also the `claimForget()` DAO accessor below).
- **Subagents** — depth-1 by construction (children cannot spawn children).
- **Feed, Ideas, Artifacts, Proactivity** — the Muse-app surfaces requested in the original handoff now exist as harness modules.
- **ClaimVerifier** and the **NLI head**.
- **Bandit (ForYou)** — suggestion ranking state.
- **Connectors** — `connectors/` with Gmail, Google Calendar/Docs/Drive, Spotify; CredentialVault (Keystore AES-GCM); SecretScrubber. No hardcoded secrets were found in the 45 harness files; SecretScrubber + 10-minute placeholder expiry work as documented; ClaimVerifier lenses fail closed.

## 1. Wire-in status

- **DB wiring — RESOLVED in the merged tree** (was the pass-2 audit's 1 critical): UltraDatabase is v9; entities include ClaimEntity/GrantEntity/MemoryEmbeddingEntity/BanditArmEntity; DAO accessors `claims()`/`claimForget()`/`grants()`/`embeddings()`/`banditArms()`; MIGRATION_7_8 + MIGRATION_8_9 defined and registered. Field-verified against the harness entities by the supplement audit. Post-merge: **0 known criticals**.
- **Manifest wiring — STILL OPEN (2 registrations):** `SchedulerReceiver` and `SpotifyRedirectActivity` exist in the harness but are not registered in `AndroidManifest.xml`.

## 2. Still open (complete post-merge list from FACTS)

- 2 manifest registrations (above).
- 2 broken unit tests: `HistoryPolicyTest.kt` / `MemoryBriefTest.kt` import `com.agent.jarvis.bench.TestKitActivity`, which exists only in `src/debug` — the unit-test source set cannot resolve it.
- TS `server/` orphaned: nothing calls it, registers no routes, crashes at startup in the audited tree.
- ~70 dead-code candidates (zero call sites) — candidates only; reflection/JNI/framework callbacks can make some live.
- CI compile pending.
- Audit highs that touch this area include: harness grant keying is the weak core (grant invariants); claim promotion can never supersede and stores a model paraphrase as the claim's verbatim quote. Full lists are in `AUDIT_REPORT.md` and `AUDIT_SUPPLEMENT.md`.

## 3. Audit totals

- Pass 2 (main lineage + harness delta): 533/536 files read; 119 defects — 1 critical, 16 high, 41 medium, 61 low; 4 broken-reference clusters; ~70 dead-code candidates.
- Supplement (ccr branch, 118/118 files): 87 findings — 0 critical, 12 high, 31 medium, 44 low.
- Combined framing: 206 findings across two audits of different snapshots (not a deduplicated count of the merged tree).

## 4. Standing rules (unchanged — do not regress)

- The agent informs and asks; it does not permit or forbid.
- Products = legitimate-product/company verification and scam screening only. "Plugs" = known non-scammers/trusted sources.
- Onion exclusion stays a separate shopping-layer function (`excludeOnion`/`isOnionUrl`) — never baked into URL extraction or autonomous browsing. ShoppingCheckout has no purchase path (prepare/display/track only), by design.
- Tor is opt-in, default OFF. Onion URLs never fall back to clearnet. Tor fail-closed was verified in both audits (three independent guards in TorProxy; only fallback is in-app Tor → Orbot).
- Human verification (CAPTCHA etc.): park with the exact link/action, wait for the user. Never solve or bypass.
- Never type into password fields. Screen actions bound to the screen they were read from; stale screens refused. Screen reads/actions fail closed on protected apps.
- SMS/email codes and reset links never reach the cloud model (opaque placeholder references only).
- Purchases always need fresh approval. No lasting grant covers a purchase, anywhere. Purchases are never grant-coverable at either layer; approval tokens are bound to the exact sorted-args key.
- The agent cannot see the approval card and cannot revoke grants; the user does that in settings.
- Consequential actions (send, post, purchase, delete) stop at confirmation. The user decides.
- Credentials live in the platform vault; the agent sees opaque handles only.
- Gate is deny-by-default; taint blocks can neither be auto-approved nor operator-overridden; manifest load failure = deny-all.

## 5. Verification discipline (current)

- Nothing in the merged tree is compile-proven until CI runs on `main`; keep that caveat on every status claim.
- MIT attribution: OpenClaw-derived code keeps the copyright + license notice in the file.
