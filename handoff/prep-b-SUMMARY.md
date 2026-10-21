# MERGE PREP B — SUMMARY (echolalia + notifications)

**Purpose:** pre-resolved three-way merges so the merge coordinator lands the last two parity branches faster. **Nothing was merged, pushed, or deleted on GitHub** — all work is local scratch under `prep-b/`.

**"Ours" snapshot for both:** main `4022799dd52f34c7dafa4d6993572f7a0343a67c` (live ref verified at prep start; = chain log state after merge #2, suite 160/160). Base `fc22170776`. ⚠️ If main has advanced past 4022799d by landing time (voice/browse/etc. merges), the coordinator should re-check the conflicted files listed below against the newer main — the compositions are neighbor-block additions, so re-deriving them on a newer snapshot is mechanical; the traps noted per branch are the parts that bite.

| Branch | Theirs tip | Conflicts | Gate on resolved tree | Expected at landing* |
|---|---|---|---|---|
| echolalia | de051c854b | 8 hunks / 3 files (config, runtime, tools/index) | tsc clean, **172/172** | 160 + 12 = 172 ✓ |
| notifications | e5f047e01d | 11 hunks / 4 files (config, runtime, server, tools/index) | tsc clean, **176/176** | 160 + 16 = 176 ✓ |

\*Against THIS snapshot. Against the final pre-landing main, add the branch's delta (+12 / +16) to whatever main's count is then.

## Deliverables
- `prep-b/echolalia/tree/` — full resolved desktop tree (byte-identical to the gated scratch tree), `prep-b/echolalia/NOTES.md`, scratch repo with merge history at `prep-b/echolalia/repo/`.
- `prep-b/notifications/tree/`, `prep-b/notifications/NOTES.md`, `prep-b/notifications/repo/` — same shape.
- `prep-b/fetch-any.py` — parameterized tree fetcher used (any SHA → desktop/ tree).

## The three traps worth the coordinator's attention
1. **config.ts type hunk (both branches):** git splits before nli's closing `};` — the composed block must re-add it before the incoming branch's settings block.
2. **notifications config comment:** the incoming block's `/**` opener is a shared common line outside the hunk; naive concatenation drops it → tsc syntax cascade. Insert `  /**` before the comment body.
3. **notifications runtime imports:** naive concatenation duplicates `toolsFor` (TS2300). Ours' import line already carries it; drop theirs' redundant line, keep their two notification imports.

## Claims-merge forecast (from echolalia prep)
Echolalia's memory.ts delta is exactly 3 spots: `Fact.provenance?: string`, `remember(text, provenance?)`, and the construction spread — and `src/echolalia.ts` `promote()` calls `memory.remember(claim, "echolalia: …")` with a positional string. Current main's memory.ts is still base-identical, so echolalia lands clean; the claims branch (kind/salience/supersede rewrite of the same interface + signature) will collide at precisely those 3 spots when it follows. Full detail in `prep-b/echolalia/NOTES.md`.
