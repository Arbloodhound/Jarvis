# DESKTOP_FINALIZED_2026-10-20 — **FINAL** (all planned features on main; one housekeeping step for the owner: delete the vision-shopping branch)

Written 2026-10-21 by a Claude Code cloud session (record branch `claude/jarvis-desktop-finalize-3ifkur`, PR #23).
The plan was `handoff/FINISH_DESKTOP_2026-10-20.md` (on `claude/handoff-docs-2026-10-20`), executed in the owner's order.
The prep notes from prep-b, prep-c and prep-d were the composition maps. The staged trees did not survive the VM swap.

## Final state

- **Final main SHA: `53ce9a4`** (tree 188f6539; after the onion reversal and the Tor-routing fix below). Every landing was pushed only after a full gate on the exact tree,
  and the tree read back from main was hash-identical to the gated tree.
- **Final test count: 343**, typecheck clean. The count went 342 → 339 (onion reversal) → 343 (Tor fix). The final
  gate ran on a fresh checkout of main `53ce9a4` with a fresh `npm install`: tsc clean, 343 pass, 0 fail, 0 skipped.
- Status per CLAUDE.md: **SOURCE-FIXED BUT RUNTIME-UNPROVEN**. Unit and integration tests pass; there was no live
  desktop run; CI has not run on these merges. The vision-shopping live check (a real vision read of a real listing
  photo) is **blocked by environment**: this container has no cloud key and no local vision model.

## Landings

| # | Merge commit | Content | Gate (tsc clean, all pass) |
|---|---|---|---|
| 1 | `227078f` | stack-comms @ 6b714ee1 (echolalia verify-before-store + inbound notifications) | 215 (187 + 28) |
| 2 | `de6d43a` | stack-work @ 8e6ad128 (investigate + skills + scheduler) | 266 (+51) |
| 3 | `8eb0c6d` | stack-screen @ 449ee537 (humaninput + synergy; browse was already on main) | **296** ✓ FINISH target |
| 4 | `f2ed71d` | memory-claims @ 20308f39 (kind/salience/decay/supersede) | **309** ✓ FINISH target |
| 5 | `bde1dbb` | subagents @ 9585ce93 (spawn/status/steer/stop, depth 1, cap 3) | **325** (+16) ✓ |
| 6 | `03cdf73` | vision-shopping @ f8bcb6d0 (vision on/off setting + vision-read evidence in shopping checks; WIP ported from c60f6e06) | **342** (+17) ✓ |

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
6. **vision-shopping onto bde1dbb:** this was a WIP with no build report. It gated 142/142 on its own base (125 + 17);
   that run is the only source for the delta.
   - `visionEnabled` was added to the loop's ToolContext and to `newAgent()`.
   - The `vision` flag goes into `baseTools()`, so the top agent, subagents and scheduled runs all honour the switch.
   - `vision.enabled` (default on) sits beside the voice block, and `update()` takes both `verifyBeforeStore` and
     `vision`.
   - **Judgment call (owner rule: off means no image is read anywhere):** vision off also withholds `see_act_verify`.
     That tool landed after the WIP's base and reads the screen through vision. It is asserted in
     `vision-shop.test.ts`.
   - **Owner rule verified in source:** the vision step is a fixed part of `product_check` / `seller_check`. Neither
     schema has a vision flag, so there is no per-call bypass. Readings are reported in their own labelled block,
     never merged into the page facts; a price conflict is printed as a named CONFLICT. When vision is off, or there
     is no backend, there are zero vision calls and a one-line statement says so.
   - **Noted, not changed:** listing-image fetches go direct even when Tor is on. This matches main's existing
     shopping page fetches (Tor wraps only web_search / web_fetch); it is a pre-existing scope question.

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

- **Deleted by the owner (verified 2026-10-21):** all 12 merged branches, i.e. the 7 parity branches, the 3 stacks,
  memory-claims and subagents. This session's GitHub access refuses branch deletes (HTTP 403).
- **Still to delete (owner):** `claude/desktop-vision-shopping-2026-10-20` @ f8bcb6d0. It is landed in `03cdf73`.
  The delete was refused by the same 403. Run `git push origin --delete claude/desktop-vision-shopping-2026-10-20`
  or use the GitHub branches page.

## Owner reversal — shopping onion exclusion removed (2026-10-21)

On the owner's explicit order (2026-10-20), this reverses the former rule "onion exclusion stays a separate
shopping-layer function". Landed as merge `a0fd632` (branch `claude/desktop-remove-onion-exclusion-2026-10-20` @
67f5f5e).

- **Removed from `desktop/src/tools/shopping.ts`:**
  - `excludeOnion` and its `isOnionUrl` helper
  - the `admitRows` / `admitRow` choke point
  - `ONION_REFUSAL`, i.e. the pre-fetch refusal of onion targets in navigation, product_check, seller_check and
    product_reviews
  - both "N onion result(s) excluded" notes in shop_search and the marketplace renderer

  The header comment records the reversal. `index.ts` changed only by dropping `excludeOnion` from its re-export
  list; the transport's onion handling is untouched.
- **Test delta: 342 → 339.**
  - Removed 4 tests: the two `excludeOnion` behaviour tests, the source pin, and "check tools refuse onion
    targets outright".
  - Added 1: "rows containing .onion links pass through the search tools unchanged".
  - The nav-flow test now expects the onion row (3 rows, not 2). The nav isolation test keeps all of its
    isolation checks and lost only the onion refusal.
  - Gates: on the branch, tsc clean and 339/339. On fresh main `a0fd632`, tsc clean and 339/339, 0 skipped.
- **Resulting behaviour.** Onion listings now appear in shopping search, marketplace and navigation results,
  unchanged. Opening or checking one is the transport's decision:
  - **Tor off:** refused, verified.
  - **Tor on:** fetched through Tor. This works from `53ce9a4` on, after the Tor-routing fix below; on `a0fd632` it
    was still refused, verified by probe. Proven by the tor.test.ts test "product_check on an .onion listing: Tor on
    fetches it through the proxy; Tor off gets the transport refusal".
- **Branch:** not deleted. This session's GitHub access refuses branch deletes (HTTP 403); the owner should delete
  it.
- **Stale docs (not changed; out of this task's scope):** CLAUDE.md §Standing Behaviour Rules and ARCHITECTURE.md
  (lines ~159, 174, 190) still state the old rule. They also describe the phone app's Kotlin `ShoppingRun`
  exclusion, which this desktop-only change does not touch.

## Tor routing for every web tool (2026-10-21, at the owner's request) — `663dfa3`

- **Before:** the Tor transport was bound only to web_search and web_fetch. These fetched direct even with Tor on:
  - shopping: search, marketplace, navigation, product and seller checks, reviews, and the vision reads of listing
    photos
  - investigate_run
  - browse_task
- **Now:** with Tor on, `toolsFor` hands every tool a Tor-bound `webFetch`, `webFetchBytes` and `torEnabled` through
  ToolContext, and those tools fetch through it.
  - browse_task fetches an .onion only through Tor. With Tor off it is refused, with an accurate message; the old
    message claimed there was no Tor transport at all.
  - The transport itself is unchanged: .onion is refused unless Tor is on, and never falls back to clearnet.
- **Tests:** +4 in tor.test.ts, covering product page and photo through the proxy, an onion product_check with Tor on
  and off, and browse onion with Tor on and off. Gate: 343/343.

## see_act_verify revert (2026-10-21) — `53ce9a4`

The owner clarified that the vision rule is not literal. With vision off, only look_at_screen and look_at_image are
withheld again, as the vision-shopping build designed it; see_act_verify stays available. The two assertions that
pinned the extension were removed (no test count change). Gate: 343/343.

## Bug-review fixes (2026-10-21) — main `f18a20e`, 404/404

Three parallel reviews of today's merges found confirmed bugs. Each fix was written with a test that failed on the old
code first, and each branch was merged after a full gate:

| Branch | Fixes |
|---|---|
| fix/notif-config | notifications config validation, atomic inbox drain judged by arrival time, ASCII-safe investigate slugs |
| fix/scheduler | no overlapping ticks or double-fire, grace rule for jobs missed before start, removed jobs stay removed, DST spring gap |
| fix/tools-gate | list URL arguments gated (secret egress), see_act_verify acts once per card, notifications marked as untrusted, investigate target gated, open_url refuses .onion |
| fix/memory | supersede revival plus cycle refusal, echo matching on whole tokens with the same numbers, per-echo source check, reload across processes, honest drop/promote |
| fix/runtime | serialized terminal cards, failures reported as FAILED, hand-off notices, `ask` waits for subagents, Stop reaches scheduled runs, labelled subagent boxes, late steer read, stale cards settled |

Also: only the main agent reads notifications; subagents and scheduled runs don't (`f18a20e`).

## IN FLIGHT — automated finish (if this session stops, the scheduled routine does this)

Two branches are being built. Their agents push them to origin when they pass their gate:
- `feat/design-calls`:
  - `create_skill` tool, which needs a card
  - writes into a skills folder need a card
  - investigation draft approval needs the owner's card
  - recall no longer inflates salience
  - scheduler lock across processes
  - DST fall-back double-fire
- `feat/agents-skills-ui`:
  - Skills and Agents menus: list, create, edit and delete
  - AGENT.md definitions
  - create_agent / list_agents / delete_agent tools
  - `spawn_agent {agent}`
  - token-protected /api/skills and /api/agents

Ritual for each branch once it exists on origin:
1. Merge into fresh origin/main with `--no-ff`. Resolve conflicts by keeping both sides.
2. In desktop/: `npm install`, `npx tsc --noEmit`, `npm test`. All must pass.
3. Push to main.
4. Gate again on a fresh checkout of main.
5. Delete the branch if allowed. This session's GitHub access refused deletes with a 403; if that happens again, leave the branch and say so.
6. Record the result here.

## PARKED (not started, per owner)

RunPod (owner's payment step), repo description one-liner, MCP client build, cloud→local fallback decision,
conversation rename UI button, Walmart split-price extraction shape, phone v3.0.79-test results.
