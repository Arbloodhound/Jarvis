# DESKTOP_FINALIZED_2026-10-20 — **PARTIAL (2 open items: vision-shopping not built; branch deletion not permitted)**

Written 2026-10-21 by a Claude Code cloud session (record branch `claude/jarvis-desktop-finalize-3ifkur`, PR #23).
The plan was `handoff/FINISH_DESKTOP_2026-10-20.md` (on `claude/handoff-docs-2026-10-20`), executed in the owner's order.
The prep notes from prep-b, prep-c and prep-d were the composition maps. The staged trees did not survive the VM swap.

## Final state

- **Final main SHA: `bde1dbb`** (tree 21962a1b). Every landing was pushed only after a full gate on the exact tree,
  and the tree read back from main was hash-identical to the gated tree.
- **Final test count: 325**, typecheck clean. The final gate ran on a fresh checkout of main `bde1dbb` with a fresh
  `npm install`: tsc clean, 325 pass, 0 fail, 0 skipped.
- Status per CLAUDE.md: **SOURCE-FIXED BUT RUNTIME-UNPROVEN**. Unit and integration tests pass; there was no live
  desktop run; CI has not run on these merges.

## Landings

| # | Merge commit | Content | Gate (tsc clean, all pass) |
|---|---|---|---|
| 1 | `227078f` | stack-comms @ 6b714ee1 (echolalia verify-before-store + inbound notifications) | 215 (187 + 28) |
| 2 | `de6d43a` | stack-work @ 8e6ad128 (investigate + skills + scheduler) | 266 (+51) |
| 3 | `8eb0c6d` | stack-screen @ 449ee537 (humaninput + synergy; browse was already on main) | **296** ✓ FINISH target |
| 4 | `f2ed71d` | memory-claims @ 20308f39 (kind/salience/decay/supersede) | **309** ✓ FINISH target |
| 5 | `bde1dbb` | subagents @ 9585ce93 (spawn/status/steer/stop, depth 1, cap 3) | **325** (+16) ✓ |

### Compositions (both sides kept; no feature or test dropped)
1. **comms:** ALL_TOOLS keeps both `browseTaskTool` and `readNotificationsTool`.
2. **work onto comms** (both stacks forked from fa5e16e9):
   - add/add unions in config.ts, cli.ts and the tools imports
   - the loop.ts ToolContext carries home, echolalia, transcriber and skills
   - runtime.ts keeps the `newAgent()` factory, with comms' inits ahead of it
   - the duplicate `audit` field was collapsed to the public one
3. **screen:** the identical browse import is kept once, and `seeActVerifyTool` is added.
4. **claims × echolalia** (prep-d's map):
   - Fact = claims fields + `provenance?`, and `provenance` moves into `RememberOptions`
   - `echolalia.ts` `promote()` now calls `remember(claim, { provenance })`
   - the `rememberTool` schema is the union of six fields, with the echolalia route first
   - **Decision taken (prep-d's flagged default):** under verify-before-store, kind, salience and supersedes are
     not stored, because pending entries have no slot for them. The tool description says so.
5. **subagents:** `newAgent(tools = baseTools, extra = {})` is the one agent builder.
   - The top agent gets the base tools, the four orchestration tools and the hand-off inbox.
   - Children get the base tools, the steering inbox and a systemSuffix.
   - Scheduled runs get the defaults, so they cannot spawn.
   - Every agent keeps transcriber, skills, echolalia, notifications, NLI and TTS.
   - In loop.ts, inbox hand-offs join main's string `extraContext`; the router's options-shaped argument is still
     honoured.

## Feature inventory now on main (desktop/)

- **Earlier landings:** desktop fixes, search fallback, shopping (marketplace + reviews), Tor transport + toggle,
  the onion pin scoped to the shopping layer, context features (uploads/attachments, @-references, conversation
  branching), thinking tiers + Claim 10 dual-router, NLI gate, voice (STT + TTS), autonomous browsing.
- **This finalize:**
  - echolalia verify-before-store (pending / promote / quarantine, and the `jarvis memory` CLI)
  - inbound notifications (3-gate filter, authenticated `POST /api/notifications`, `read_notifications`)
  - investigate (draft-only)
  - lazy skills (allowlist / roots)
  - scheduler (cron / once / hook jobs, default harness jobs seeded, and the `jarvis jobs` CLI)
  - humanized screen input (glide, jittered typing, move / scroll)
  - see-act-verify screen synergy
  - memory claims (kinds, salience, 30-day decay, reinforcement, supersede chains, lossless migration)
  - subagents (spawn / status / steer / stop, depth cap 1, concurrency cap 3, ordered hand-offs, cascading stop)

## Branches

**Deleted: none.** Every delete was refused by the session's auto-mode permission classifier ("Git Destructive").
The owner, or a session with permission, should delete these 12 branches. Each one's content is on main:
- `claude/desktop-parity-{humaninput,synergy,investigate,skills,scheduler,echolalia,notifications}-2026-10-20`
- `claude/desktop-stack-{comms,work,screen}-2026-10-20`
- `claude/desktop-memory-claims-2026-10-20`
- `claude/desktop-subagents-2026-10-20`

The comms and screen stacks were single combined commits, so the parity tips for echolalia, notifications,
humaninput and synergy are not git ancestors of main. Their content arrived through the stacks.

## Open item — vision-shopping (PARTIAL)

`claude/desktop-vision-shopping-2026-10-20` was never pushed, because its build agent was stopped, and no
DESKTOP_VISION_SHOP report exists. The FINISH doc's only spec is one line: "vision on/off setting + vision-read
evidence in shopping; built off c60f6e06". It was not built from that line, because that would be guessing at the
design. It needs a build brief, or a restart of its build agent.

## PARKED (not started, per owner)

RunPod (owner's payment step), repo description one-liner, MCP client build, cloud→local fallback decision,
conversation rename UI button, Walmart split-price extraction shape, phone v3.0.79-test results.
