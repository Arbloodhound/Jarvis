# DESKTOP_FINALIZED_2026-10-20 — **PARTIAL (in progress; stopped at a permission block)**

Updated 2026-10-21 by a Claude Code cloud session (branch `claude/jarvis-desktop-finalize-3ifkur`).
The plan inputs were found on `claude/handoff-docs-2026-10-20` (`handoff/FINISH_DESKTOP_2026-10-20.md`, the chain
log, the stack reports and the prep-b/c/d notes) and are being executed in the order the owner gave.

## Landed on main (each one gated; the landed tree is hash-identical to the gated tree)

| Step | Merge commit | Gate |
|---|---|---|
| comms stack (echolalia + notifications) @ 6b714ee1 | `227078f` | tsc clean, 215/215 (187 + 28) |
| work stack (investigate + skills + scheduler) @ 8e6ad128 | `de6d43a` | tsc clean, 266/266 (+51) |
| screen stack (humaninput + synergy; browse already on main) @ 449ee537 | `8eb0c6d` | tsc clean, **296/296** ✓ FINISH target |
| memory-claims @ 20308f39 | `f2ed71d` | tsc clean, **309/309** ✓ FINISH target |

**Main is now `f2ed71d`**, with 309 tests and a clean typecheck. Landed tree 3aec786e equals the locally gated tree.

### Compositions (nothing dropped)
- **comms:** `tools/index.ts` ALL_TOOLS keeps both `browseTaskTool` and `readNotificationsTool`.
- **work onto comms** (both stacks forked from fa5e16e9): add/add unions in config.ts (memory, notifications and
  skills blocks; Paths `notificationsInbox` and `jobs`), cli.ts (memoryCommand and jobs helpers) and the
  tools/index.ts imports.
  - loop.ts ToolContext carries home, echolalia, transcriber and skills.
  - runtime.ts keeps work's `newAgent()` factory. Comms' audit, pending and notifications inits come before it,
    and the factory carries the echolalia, notifications and skills deps.
  - The duplicate `audit` field (public from comms, private from work) was collapsed to the public one.
- **screen:** the browse import is identical on both sides and is kept once. `seeActVerifyTool` is added.
- **claims × echolalia** (per prep-d):
  - Fact = claims fields + `provenance?`. `provenance` joins `RememberOptions`.
  - `echolalia.ts` `promote()` now calls `remember(claim, { provenance })`.
  - `rememberTool` schema = union of six fields, with the echolalia route first.
  - Claim tags apply only on the direct path; the tool description says so. This was prep-d's flagged default.

## Remaining (blocked on permission)
1. **Subagents** @ 9585ce93: the merge into main was not started, because the local merge was denied by the
   auto-mode classifier. Expected gate: 309 + 16 = **325**.
2. **Branch deletion** was denied ("Git Destructive"). Still present:
   - parity: humaninput, synergy, investigate, skills, scheduler, echolalia, notifications
   - stacks: comms, work, screen
   - after landing: memory-claims and subagents

   Note: comms and screen were single squash-style commits, so the parity tips for echolalia, notifications,
   humaninput and synergy are **not** git ancestors of main. Their content is on main through the stacks;
   deletion is safe per the FINISH doc, but `git branch --merged` will not list them.
3. **Vision-shopping:** `claude/desktop-vision-shopping-2026-10-20` was never pushed (its build agent was stopped).
   The only spec is one line ("vision on/off setting + vision-read evidence in shopping"). **PARTIAL on this item.**
4. Final gate on fresh main, then this doc becomes final.

## PARKED (not started, per owner)
RunPod (owner's payment step), repo description one-liner, MCP client build, cloud→local fallback decision,
conversation rename UI button, Walmart split-price extraction shape, phone v3.0.79-test results.
