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
