> **Post-merge replacement — 2026-10-19.** This is a point-in-time document. Its body is preserved verbatim as the historical record; the status section below states what the 2026-10-19 code audits and the full-branch consolidation (all work now on `main`) changed. The previous version is preserved at `archive/2026-10-19/desktop/HANDOFF_PROMPT.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

## Status as of 2026-10-19

- **Repository state has changed.** The branch and PR instructions in the body below are historical. All work was consolidated onto `main` on 2026-10-19 (tip `c8043e1c`); `desktop/` arrived via the `ccr-c9b6f798-uako9v` merge. Do not check out or push to the branch named below — branch fresh from `main`. CI has not yet compiled the merged tree.
- **Audited state.** The supplement audit of the ccr snapshot (118 files) found 87 findings — 0 critical, 12 high, 31 medium, 44 low. Desktop app verdict: works with defects. Desktop tests verdict: works as designed (1 low). The "green baseline" test count quoted below (40 tests) was not re-verified in the 2026-10-19 audits — run the suite and quote what you observe.
- **Gate architecture is strong** per the audits (layered checks, SSRF defence, redaction in audit log/confirm cards), with three desktop highs still open: (1) unredacted tool args are broadcast to all clients, (2) screenshots sent to cloud Vision are ungated, (3) macOS/Windows screen actions report false success for actions they did not perform.
- **Providers:** Claude cloud + local, selection per run, with **no cross-provider fallback** — a failed turn ends the run. Any continuation should preserve or explicitly re-decide that behaviour, not assume a fallback exists.
- The task pieces, scope exclusion (no Tor/dark-web tooling in desktop), and honesty rules in the body remain the historical record of what was asked at the time. Tor is not part of desktop; it belongs to the Kotlin app.

# Paste this into the next pod to continue Jarvis Desktop

You are continuing work on **Jarvis Desktop**, a native desktop AI agent in the `desktop/` folder of the `Arbloodhound/Jarvis` repo. A previous session ran low on usage mid-task.

**First, do exactly this:**
1. Check out branch `ccr-c9b6f798-uako9v` (develop and push there only — never another branch). Confirm head is at or after commit `2fa9284`.
2. Read `desktop/HANDOFF_DESKTOP.md` in full, then `desktop/CLAUDE.md`, `desktop/REVIEW.md`, and the root `CLAUDE.md`.
3. Run `cd desktop && npm install && npm run typecheck && npm test` — expect **40 tests, 0 fail**. That's your green baseline.
4. Check PR #16's CI is green on the latest commit (GitHub MCP `pull_request_read`, method `get_check_runs`, owner `Arbloodhound`, repo `Jarvis`, pullNumber 16). If red, read the failing job logs (`get_job_logs`, `failed_only:true`) and fix before new work. This session owns PR #16 — keep it green.

**Then continue the user's task in order.** Pieces 1 (model compatibility: picker + semantic memory) and 2 (vision + experimental screen control) are **done and pushed**. Build:
- **Piece 3 — Chromebook / Chrome OS Flex** (plan in HANDOFF_DESKTOP.md §"NEXT"): support the Crostini install path; lead docs with the `serve` + Chrome-browser route for low-RAM devices; make the Electron app degrade where Crostini has no tray (closing the window must quit, not hide); note the host-screenshot limit; document in `desktop/README.md`.
- **Piece 4 — full feature + competitive review** across both local and cloud brains: verify every tool works on each brain, confirm wiring, update `desktop/REVIEW.md`, and write "what we do better / what to improve" for the user.

**Scope rule (the user set this):** do NOT build the dark-web / Tor / onion monitoring, a dark-web classifier model, or an automated action-on-hidden-services pipeline — excluded by the root CLAUDE.md and the owner's prior decision. Ordinary embedding/vision/rerank models all work; that's fine.

**Rules:** follow the root CLAUDE.md honesty rules (no proof-free success claims; label RUNTIME-UNPROVEN where you can't test on a real desktop). Commit with the attribution trailers the in-session reminder gives you, push to `ccr-c9b6f798-uako9v`, keep PR #16 as the PR, and update `DEVLOG.md` + `desktop/HANDOFF_DESKTOP.md` as you go.
