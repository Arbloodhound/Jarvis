# MUSE HANDOFF — 2026-10-15 (from Claude Code session 01UX5qeo)

Read this first, then HANDOFF.md (top section), CLAUDE.md and the top of DEVLOG.md. Everything below is checked
against GitHub as of 2026-10-15 03:40 UTC.

## 1. State right now
- **Latest release: `v3.0.40-test`** (Latest; prerelease off) —
  https://github.com/Arbloodhound/Jarvis/releases/tag/v3.0.40-test
  - Built by Release APK run 38675679758 from `claude/shopping-integration` @ `7974947`.
  - Assets: `jarvis-test.apk` (with the Jarvis Tests app — the one Obtainium installs) and `jarvis-release.apk`.
  - Contains: shopping + autonomous-browse wiring (cloud tools `shop_search`, `web_browse`, `shop_checkout_prep`),
    plus verify-product / check-company / investigate / deep-research from v3.0.37.
- **Working branch:** `claude/shopping-integration` @ `1f9c293` (pushed). Draft PR #12 → base
  `claude/new-session-t3cy17`: https://github.com/Arbloodhound/Jarvis/pull/12 — CI green, waiting on the owner.
- **Repo moved:** now owned by the **Arbloodhound organization** and **private**. Owner's personal account was
  renamed to **ArBl-raffleraffle**. Nothing else changed.

## 2. Evidence (honesty statuses)
- PROVEN: JVM unit tests + release build passed in CI (they gate the build); signing, 16 KB alignment and CPU-variant
  checks passed; the release published both APKs.
- PROVEN: the artifact-quota fix works — run 38675679758 deleted its own artifacts after publishing (0 left).
- SOURCE-FIXED BUT RUNTIME-UNPROVEN: every shopping / browse / Tor feature on the phone. **No Real-use results file
  since v2.6.28.**

## 3. What happened this session (2026-10-09 → 2026-10-15)
1. 2026-10-09: started the shopping build here; three builder agents died at once on a usage limit before writing
   anything. The shopping build was then finished in other sessions (HANDOFF 3/4: modules on
   `claude/shopping-integration`, wiring merged at `db4ce0c`, 1468 JVM tests / 0 failures).
2. Release run 38665897524 (@ db4ce0c) **failed** — not the code: build/tests/signing all passed, then
   `upload-artifact` hit GitHub's **artifact storage quota** (82 old artifacts, ~3.5 GB, 14-day retention).
3. Fix in `.github/workflows/release-apk.yml` (commit `7974947`): release artifacts keep **1 day**; the publish job
   (now `actions: write`) **deletes its own run's artifacts after publishing** (only on success, so a failed publish
   can still be re-run).
4. With the owner's go-ahead, deleted 77 old artifacts, keeping the last 2 runs (37988531842, 37910233779).
5. Re-run 38669863426 was **cancelled** mid-build when the repo was moved to the org (not a code failure).
6. Re-run 38675679758 → **success** → v3.0.40-test published.
7. Removed three empty stale agent worktrees (`.claude/worktrees/`) — they held no work.

## 4. Owner setup still to do
- **Claude GitHub App:** claude.ai → Settings → Connectors → GitHub shows both installations (ArBl-raffleraffle and
  Arbloodhound) as "Needs reconnecting". Tap **Fix** on Arbloodhound (include Jarvis), then use "Check repository
  status" with `Arbloodhound/Jarvis`. (This session can read and push right now; Fix keeps new sessions working.)
- **Obtainium (repo is private now, so a token is required):**
  - PAT: **fine-grained**, resource owner **Arbloodhound** (NOT the personal account — a fine-grained token only works
    for the one owner it was made for), only repo **Jarvis**, permission **Contents: Read-only** (Metadata read is
    automatic), nothing else. The org may need to approve it (org Settings → Personal access tokens → Pending).
    A classic token with `repo` scope also works (it acts as the owner's account).
  - Obtainium → Settings → GitHub → paste the token. Add App: `https://github.com/Arbloodhound/Jarvis`,
    "Include prereleases" on, APK filter regex `jarvis-test\.apk`.
- Then: install v3.0.40-test → Jarvis Tests → **★ Real-use check** → send `Jarvis-results-YYYYMMDD-HHMM.txt`.

## 5. Rules that still hold
- Scope (do not renegotiate): legitimate product verification + scam screening; onion = research-only, opt-in,
  screened; checkout **never executes a purchase**, crypto is **display-only**, every buy/pay/send tap goes through
  the ActionGate confirm card.
- Plain short answers; one release per batch; never change a default the owner chose without asking.
- Release: GitHub MCP `actions_run_trigger` → `run_workflow`, owner `arbloodhound`, repo `jarvis`, workflow
  `release-apk.yml`, ref `claude/shopping-integration`, inputs `{"test_kit":"true"}`; it publishes `v3.0.<run>-test`
  as Latest and keeps the newest 5.
- Commit trailers: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` + `Claude-Session: <your session url>`.

## 6. Open items
- Owner: review/merge PR #12; Fix the GitHub App; set up Obtainium; run ★ Real-use check.
- Known gaps (from DEVLOG): `open_url` still hands `.onion` to the browser; an onion address in an ordinary question
  still goes into the DuckDuckGo query text; full Tor runs tested in parts only.
