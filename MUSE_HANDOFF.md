# MUSE HANDOFF — 2026-10-17 (from Claude Code session 01UX5qeo)

Read this first, then HANDOFF_V5_MISSING.md (§6 standing rules), CLAUDE.md and the top of DEVLOG.md.
Checked against GitHub on 2026-10-17.

## 0. LOCKED — Muse's code stays as Muse made it (owner, 2026-10-17)
The owner: "I WANT EVERY DECISION THAT MUSE MADE TO STAY AS IT WAS" and "make sure Muse's code stays mostly the
same unless need to optimize — we rebuilt the app on top of OpenClaw."
- Muse's work = everything in `HANDOFF_V5_MISSING.md` and the six harness base files (`MemoryClaims`,
  `ApprovalGrants`, `Scheduler`, `SkillStore`, `NotificationForwardingPolicy`, `ScreenGuard`). The app is built on
  OpenClaw (MIT); keep attribution.
- Keep Muse's code and decisions as they are. Change them only to optimize (same behaviour, faster/leaner), never to
  add rules Muse didn't make. Anything that would change behaviour → ask the owner first.
- 2026-10-17: the owner rejected three additions another session made beyond Muse; all removed (commit after
  `8eb2ecd`): code-hiding on the on-device path and in `notifications.log` (OtpScrubber), and refusing raw taps with
  nothing listed under them (ScreenBounds/requireScreenBound; `ScreenGuard.kt` back to Muse's version). The cloud
  model still never gets codes (Muse's rule, `Brain.resultFeedback`).
- Still in Muse's files from other sessions (additive, kept): `MemoryClaims.writeVerified` (+ minimal compile fixes
  in `MemoryClaims`/`SkillStore`).

## 1. State right now
- **Latest release: `v3.0.41-test`** — https://github.com/Arbloodhound/Jarvis/releases/tag/v3.0.41-test
  - Release APK run 38999016649 (success) from `muse/v5-delta-port` @ `79a438b`.
- **Branch:** `muse/v5-delta-port`. Draft PR #13 → base `claude/shopping-integration`. PR #12
  (`claude/shopping-integration`) still open too. Both wait on the owner.
- Full JVM suite after the removal: 1578 tests, 0 failures (was 1610; the 32 removed tests covered the removed code).
  v3.0.41-test still contains the removed code — the next release won't.

## 2. What's on the branch (newest first)
- (next commit) Removed the three additions the owner rejected (§0).
- `79a438b` readSms: codes scrubbed before the 160-char cut (so a code at the cut can't slip past).
- `fddb02c` ScreenGuard tap binding + OtpScrubber wired into device paths (see §0 items 1–3).
- `c28f861` On-device prompt in a fixed fragment order (log line `UltraPrompt reusable fragments=N/4`);
  deferred tool namespaces built but **off** — every on-device tool still loads as before.
- `8c798f3` On-device `LlmNliHead` + `MemoryClaims.writeVerified` (check a memory before saving).
  **Idle:** nothing in the app saves memories this way yet.
- `02001d6` ClaimVerifier, KV prefix, deferred tools, BanditArmState (suggestion scoring — **not connected**:
  Ideas/Proactivity cards aren't shown anywhere yet), OpenClaw primitives. DB v9.
- `ff9d15d` (Oct 16) Snippet prices never evidence; cloud model never gets codes/reset links.
- `05b04ee` (Oct 15) v5 delta port: §0 wire-ins + connectors, trash, forget, subagents, feed/ideas/artifacts/
  proactivity.

## 3. Evidence (honesty statuses)
- PROVEN: JVM tests + release build pass in CI; v3.0.41-test published.
- SOURCE-FIXED BUT RUNTIME-UNPROVEN: everything since v3.0.40 on the phone. **Nothing in v3.0.41 has been tested on
  the phone.** No Real-use results file since v2.6.28.

## 4. Not built / not wired
- v5 modules are not wired into the app (tool manifest, Tools.kt, Brain prompt, settings UI for grants and trash,
  Trash replacing existing delete sites). Waiting on the owner's go-ahead — it touches core files.
- Google OAuth redirect activity (needs an owner-registered client ID); Spotify/Plaid/Stripe connectors.
- LLM runners/generators for subagents, feed and ideas.
- Notification filter (allowlist → quiet hours → rate limit): waiting on the owner's yes/no and quiet hours.

## 5. Owner to do
- Install the next release → Jarvis Tests → **★ Real-use check** → send the results file.
- Review/merge PR #13 and PR #12.
- Obtainium (repo is private): fine-grained PAT, owner **Arbloodhound**, only repo **Jarvis**, **Contents:
  Read-only**; Add App `https://github.com/Arbloodhound/Jarvis`, prereleases on, filter `jarvis-test\.apk`.

## 6. Working rules
- Plain short answers; one release per batch; never change a default the owner chose without asking.
- Release: GitHub MCP `actions_run_trigger` → `run_workflow`, owner `arbloodhound`, repo `jarvis`, workflow
  `release-apk.yml`, ref = the working branch, inputs `{"test_kit":"true"}`; publishes `v3.0.<run>-test`.
- MIT attribution stays in OpenClaw-derived files.
