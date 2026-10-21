# FINISH THE DESKTOP APP — executable handoff (2026-10-20, ~17:50 PDT)

**Owner directive:** finalize the Jarvis desktop app "for now". Owner is at his usage
limit — whoever executes this works AUTONOMOUSLY, surfaces nothing until done or
genuinely blocked, and writes progress into THIS file + the chain log as they go.

Repo: Arbloodhound/Jarvis (private). Tools: ~/workspace/jarvis-rebuild/ghapi.py
(call()), desktop-fix-push.py (git-data push pattern), per-branch fetch scripts
(*-fetch.py) in the same dir. Merge ritual, always: three-way local merge when the
merges API 409s → compose, never drop a feature/test → land → fresh-fetch main →
npm install + `npx tsc --noEmit` + `npm test` (or trimmed gate ONLY when the landed
tree is hash-identical to a locally gated tree) → delete branch on green → log.

## STATE AT HANDOFF
- **Main = 9d71ceeb, 187 tests, typecheck clean.** Merged + branches deleted:
  desktop fixes, search fallback, shopping, Tor, pin-fix, context features
  (c60f6e06), parity router (a8796088), nli (4022799d), voice (fa5e16e9),
  browse (9d71ceeb). Chain log: PARITY_MERGE_CHAIN_2026-10-20.md.
- **In flight (wait for their report files on disk in ~/workspace/jarvis-rebuild/):**
  - STACK_SCREEN_2026-10-20.md → branch claude/desktop-stack-screen-2026-10-20
    (browse+humaninput+synergy composed on fa5e16e9 base; browse is ALSO already
    in main via 9d71ceeb — reconcile, never double-apply)
  - STACK_WORK_2026-10-20.md → claude/desktop-stack-work-2026-10-20
    (investigate+skills+scheduler)
  - STACK_COMMS_2026-10-20.md → claude/desktop-stack-comms-2026-10-20
    (echolalia+notifications; prep-b/SUMMARY.md has the collision maps + 3 traps)
  - DESKTOP_VISION_SHOP_2026-10-20.md → claude/desktop-vision-shopping-2026-10-20
    (vision on/off setting + vision-read evidence in shopping; built off c60f6e06)
  - DESKTOP_SUBAGENTS_2026-10-20.md → claude/desktop-subagents-2026-10-20 (off c60f6e06)
- **Ready and waiting:** claude/desktop-memory-claims-2026-10-20 @ 20308f39305f
  (138/138 on branch; report DESKTOP_CLAIMS_2026-10-20.md; staged resolution in
  prep-d/; echolalia collision forecast in prep-b/echolalia/NOTES.md: echolalia
  calls remember(text, "echolalia: …") positionally, claims changed the signature
  to remember(text, {options}) — compose so both work).
- Kit slots patch: DONE (runpod-glm52-kit/, PARALLEL.md). Nothing to merge (local files).

## EXECUTION ORDER
1. Wait until all three STACK_*.md reports exist (poll disk every few minutes;
   stacks were staffed 17:39, builds of this class run 15–40 min). If a report
   declares its stack STOPPED/unfixable, log it here and finish the rest; the
   final report then says PARTIAL with the exact blocker. Never fake "finalized".
2. **Parity composition:** merge into main, in order comms → work → screen
   (screen last because of the browse overlap). Full gate on main after the
   three: expect **296 tests**, typecheck clean. Then delete: the 7 remaining
   parity branches (humaninput, synergy, investigate, skills, scheduler,
   echolalia, notifications) + the 3 stack branches. Log in the chain doc.
3. **Claims:** three-way (base c60f6e06 tree / current main / claims tip) using
   prep-d's staged tree as the starting resolution; compose the echolalia
   remember() collision per prep-b's forecast. Gate: expect **309**. Delete branch.
4. **Vision-shopping:** three-way vs current main (its base is c60f6e06; NLI/router/
   voice/browse landed since — expect settings-block + tools/index neighbors;
   prep patterns apply). Gate: 309 + its report's test delta. Delete branch.
5. **Subagents:** three-way vs current main (base c60f6e06; it wires loop.ts +
   tools/index.ts + runtime — the screen stack also touched loop.ts; compose).
   Gate: prior + its delta. Delete branch.
6. **FINAL GATE:** fresh-fetch main, npm install, tsc, npm test. Record tip + count.
7. **Write DESKTOP_FINALIZED_2026-10-20.md**: feature inventory now on main,
   final SHA + test count, every branch deleted, and the PARKED list (below).
   Append the outcome to ~/MEMORY.md (Jarvis section) + close the chain log.

## PARKED (not part of finalize; do not start)
- RunPod: parked at owner's payment step (card + "go"); kit ready incl. NP_SLOTS.
- Repo description one-liner (owner manual), MCP client build (owner's word),
  cloud→local fallback policy (undecided), conversation rename UI button,
  Walmart split-price extraction shape, phone v3.0.79-test results (owner testing).

## IF YOU ARE CLAUDE CODE READING THIS
Everything above is self-contained: state, branch names, SHAs, order, gates,
and where every staged resolution/notes file lives. Execute from step 1.
The prep dirs (prep-a/b/c/d) hold resolved trees + per-file conflict maps —
consume them before composing anything yourself.
