# MUSE HANDOFF — 2026-10-17 (from Claude Code session 01UX5qeo)

Read this first, then HANDOFF_V5_MISSING.md (§6 standing rules), CLAUDE.md and the top of DEVLOG.md.
Checked against GitHub on 2026-10-17.

## 0. LOCKED — every decision Muse made stays as it was (owner, 2026-10-17)
The owner's words: "I WANT EVERY DECISION THAT MUSE MADE TO STAY AS IT WAS."
- What counts as Muse's decisions: everything in `HANDOFF_V5_MISSING.md` (§0 wire-ins, §1–§5 designs, §6 standing
  rules, §7 verification discipline) and the six harness base files Muse delivered (`MemoryClaims`,
  `ApprovalGrants`, `Scheduler`, `SkillStore`, `NotificationForwardingPolicy`, `ScreenGuard`).
- Do not change, soften, tighten, "improve" or reinterpret any of them. No session decides on its own that a later
  rule supersedes one of Muse's. If something seems to conflict, **stop and ask the owner** — do not pick a side.
- New work builds on top of Muse's decisions; it does not replace them.

### Places where shipped code goes beyond what Muse wrote (owner to decide — nothing changed yet)
These were built by Claude sessions, not by Muse. They are listed so the owner can keep or revert them:
1. **On-device model can't see codes either.** Muse's rule: codes/reset links never reach the *cloud* model.
   Commit `fddb02c` also scrubs `sms_read` and `notification_read` results for **every** path (`Tools.kt`), and
   `readSms` (`AgentController.kt`, `79a438b`), so the on-device model can no longer read a code back to you.
2. **Notification log scrubbed.** `UltraNotificationService` now removes codes from `notifications.log` before
   writing it (`fddb02c`). Triggers still see the raw text. Muse did not ask for this.
3. **Raw-coordinate taps refused when nothing is under the point** (`fddb02c`). Muse's rule is "screen actions
   bound to the screen they were read from; stale screens refused". The new check also refuses taps in games,
   canvas/drawing apps and some web views where nothing on screen is listed. Possible regression — watch on device.
4. **Email for project use.** §6 says only `raffleraffle1528@gmail.com`, never `@arbloodhound.xyz`. A later
   memory note said `raffleraffle@arbloodhound.xyz`. Per the lock, §6 (Muse) is what stands until the owner says
   otherwise.

## 1. State right now
- **Latest release: `v3.0.41-test`** — https://github.com/Arbloodhound/Jarvis/releases/tag/v3.0.41-test
  - Release APK run 38999016649 (success) from `muse/v5-delta-port` @ `79a438b`.
- **Branch:** `muse/v5-delta-port`. Draft PR #13 → base `claude/shopping-integration`. PR #12
  (`claude/shopping-integration`) still open too. Both wait on the owner.
- Full JVM suite at `79a438b`: 1610 tests, 0 failures (reported by session 01BUKaYm; CI release build passed).

## 2. What's on the branch (newest first)
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
- `performSwipe`, `tapAtPoint` and the IME-enter gesture are not bound-checked.

## 5. Owner to do
- Install v3.0.41-test → Jarvis Tests → **★ Real-use check** → send the results file. Watch for refused taps in
  games/drawing apps/web views.
- Decide §0 items 1–4.
- Review/merge PR #13 and PR #12.
- Obtainium (repo is private): fine-grained PAT, owner **Arbloodhound**, only repo **Jarvis**, **Contents:
  Read-only**; Add App `https://github.com/Arbloodhound/Jarvis`, prereleases on, filter `jarvis-test\.apk`.

## 6. Working rules
- Plain short answers; one release per batch; never change a default the owner chose without asking.
- Release: GitHub MCP `actions_run_trigger` → `run_workflow`, owner `arbloodhound`, repo `jarvis`, workflow
  `release-apk.yml`, ref = the working branch, inputs `{"test_kit":"true"}`; publishes `v3.0.<run>-test`.
- MIT attribution stays in OpenClaw-derived files.
