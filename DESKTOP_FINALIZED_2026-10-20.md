# DESKTOP_FINALIZED_2026-10-20 — **PARTIAL (blocked, nothing landed)**

Written 2026-10-21 by a Claude Code cloud session (branch `claude/jarvis-desktop-finalize-3ifkur`).
Status: **CANNOT VERIFY FROM CURRENT ARTIFACTS / blocked by environment.** Desktop is **not** finalized.

## Exact blocker

The task said to execute `~/workspace/jarvis-rebuild/FINISH_DESKTOP_2026-10-20.md` and to consume the staged
resolutions in `prep-a/ … prep-d/`. On this VM, **`~/workspace/jarvis-rebuild/` does not exist**. These are all
missing: the FINISH doc, `prep-a..d/`, `ghapi.py`, `desktop-fix-push.py`, the `*-fetch.py` scripts and
`PARITY_MERGE_CHAIN_2026-10-20.md`. A search of `/home`, `/root`, `/tmp`, `/opt`, `/srv` and `/mnt` found none of
them, and the repo has no copy. They lived only on the replaced VM.

Without them, three of the inputs the plan depends on are unknown:
1. **Execution order.** The prep resolutions were staged against specific intermediate main trees, so landing in
   the wrong order could invalidate them.
2. **The parked list.** Some outstanding branches may be deliberately parked, and landing one would be a defect.
3. **Expected test count per landing.**

The 8 conflicting merges below would have to be composed from scratch, which the rules forbid while staged
resolutions exist. So **no merge, push to main or branch deletion was done.**

To unblock: restore `~/workspace/jarvis-rebuild/` on the executing VM, or commit the FINISH doc and `prep-a..d/` to
the repo (for example under `archive/2026-10-20/finish/`). Then re-run the task.

## Live state verified (2026-10-21)

- `origin/main` = **9d71ceeb00f269641d6447f7f00fcdd3387aba02**. There are no landings newer than 9d71ceeb. No chain
  log was reachable, but the refs show no newer landing.
- Gate on fresh main (desktop/): `npm install` ok, `tsc --noEmit` clean, **`npm test`: 187/187 pass**.

## Outstanding branches (all still present, none deleted)

| Branch | Tip | Ahead / behind main | Trial merge onto 9d71ceeb |
|---|---|---|---|
| desktop-memory-claims | 20308f39 | 2 / 10 | clean → tsc ok, **200/200** (tree 470e19f3) |
| desktop-parity-humaninput | a24ef02a | 4 / 19 | clean → tsc ok, **207/207** (tree 57b58c94) |
| desktop-stack-work (investigate+skills+scheduler, pre-composed on fa5e16e9) | 8e6ad128 | 3 / 2 | clean → tsc ok, **238/238** (tree 14e02321) |
| desktop-stack-screen (browse+humaninput+synergy squash on fa5e16e9) | 449ee537 | 1 / 2 | CONFLICT: tools/index.ts |
| desktop-parity-echolalia | de051c85 | 4 / 19 | CONFLICT: agent/loop.ts, config.ts, runtime.ts, tools/index.ts |
| desktop-parity-investigate (in stack-work) | 59fe5456 | 3 / 19 | CONFLICT: agent/loop.ts, tools/index.ts |
| desktop-parity-notifications | e5f047e0 | 1 / 19 | CONFLICT: config.ts, runtime.ts, server.ts, tools/index.ts |
| desktop-parity-scheduler (in stack-work) | e9021e5f | 1 / 19 | CONFLICT: cli.ts, runtime.ts |
| desktop-parity-skills (in stack-work) | 7f0160c3 | 3 / 19 | CONFLICT: agent/loop.ts, config.ts, runtime.ts, tools/index.ts |
| desktop-parity-synergy (in stack-screen) | 830c8920 | 4 / 19 | CONFLICT: tools/index.ts |
| desktop-subagents | 9585ce93 | 1 / 10 | CONFLICT: agent/loop.ts, config.ts, runtime.ts |

All branches are `claude/<name>-2026-10-20`, and all paths are under `desktop/src/`. The trial merges were done
in local worktrees only and were not pushed; each was gated on its own, not stacked. Other refs not touched:
`ccr-c9b6f798-uako9v` (PR #22) and `claude/new-session-8lnisn` (PR #20).

## Feature inventory on main (9d71ceeb), from merge history

Shopping + marketplace/reviews port, Tor transport + settings toggle, onion pin scoped to the shopping layer,
uploads/attachments + @-references + conversation branching, thinking tiers + Claim 10 dual-router, NLI gate,
voice (STT + TTS), autonomous browsing (full-page contract). This is grep/commit-level, consistent with CLAUDE.md;
there was no runtime proof beyond the 187 unit tests.

## Final numbers

- Final main SHA: **9d71ceeb** (unchanged). Final test count: **187** (unchanged).
- Branches deleted: **none**.
- Parked items: **unknown**, because the list was in the missing FINISH doc.
