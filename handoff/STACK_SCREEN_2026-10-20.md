# STACK A (screen stack) — browse + humaninput + synergy as ONE branch — 2026-10-20

**Result: DONE.** One stack branch pushed, fully gated. Main was NOT touched.

- **Branch:** `claude/desktop-stack-screen-2026-10-20`
- **Commit:** `449ee53782051f9621c69d9fc07f108167c73f5d` (single commit, parented on the base tip below; remote desktop/ tree verified blob-for-blob == the locally gated tree, 65 files)

## Base used

- Chain log at start of work: main = **fa5e16e951fc647de8c472e752c24db30c45a944** (router/nli/voice merged, 177/177; browse NOT yet landed) → stack = **all three** branches.
- Fork base for every parity branch: `fc22170776e7b50ab4002b1568ed14bc93c5d0ec` (resolved via compare API merge-base).
- Branch tips combined: browse `389274f59d330a1e9af3c77e9bee4cc9ce595d8b`, humaninput `a24ef02ab7bd3cc6b4fd862f41391758139cd555`, synergy `830c8920f9e92c122fd641fa3be38d50bc23abd8`.
- **⚠ Drift note:** while this stack was being built, the merge coordinator landed browse on main individually — main is now `9d71ceeb` (187 tests, per the 17:50 handoff note in MEMORY.md). This stack's browse content is the identical branch lineage (same tip 389274f5), so merging the stack into current main should reduce to the humaninput + synergy delta; the tools/index.ts composition (below) already includes browse from the same source.

## Method

Local git repo (`~/workspace/jarvis-rebuild/stack-screen/repo`): base commit = fc221707 desktop tree; each branch tip committed off base; main-tip tree committed off base; the three branch merges applied one at a time onto the main-tip tree (true three-way merges, merge-base = fork base). prep-c had fetched trees only (identical to fresh fetches, verified by diff) — no staged resolutions existed to reuse.

## Conflicts + resolutions (per file)

- **`desktop/src/tools/index.ts` — the ONLY conflicted file** (browse: 1 hunk; synergy: 3 hunks; humaninput: none).
  - browse hunk: import region — main's shopping import + re-export block vs browse's import line. **Composed:** both kept (`shoppingTools` + shopping re-exports AND `BROWSE_DEFAULT_STEPS, BROWSE_MAX_STEPS, renderBrowseReport, runBrowseTask` from `./browse.js`).
  - synergy hunk 1: import region — added `import { seeActVerifyTool } from "./screenact.js"` alongside the shopping + browse imports; shopping re-export block kept whole.
  - synergy hunk 2: `ALL_TOOLS` — composed to `rememberTool, recallTool, forgetTool, seeActVerifyTool, ...shoppingTools(),` (synergy's tool AND main's voice row + shopping spread all present; browseTaskTool already registered from the browse merge).
  - synergy hunk 3: `toolsFor` gating — synergy's widened condition adopted (`control_screen` **or** `see_act_verify` withheld when screenControl is off) AND main's voice line kept (`speak` withheld when `ttsEnabled === false`).
- **`desktop/src/tools/platform.ts`** (humaninput): auto-merged; verified a true composition, not an overwrite — the lines git removed vs main were exactly humaninput's own rewrite of the one-shot type/click calls into humanized ones, everything humaninput added is present verbatim, and main's voice additions (`speakText`, `runBinary`, TTS hint) survive (grep-verified). Main's mac/win honesty behavior lives in the same functions humaninput upgraded (eased paths, jittered cadence, incremental scroll).
- **`desktop/src/vision.ts` + `desktop/test/vision.test.ts`** (synergy): auto-merged (main had not touched either since the fork base).
- Everything else is pure additions: `src/tools/browse.ts`, `src/tools/human.ts`, `src/tools/screenact.ts`, `test/browse.test.ts`, `test/humaninput.test.ts`, `test/screenact.test.ts`.
- Total delta vs base tip fa5e16e9: **10 files** (6 added, 4 modified), all inside `desktop/`. No feature or test dropped anywhere; no integration fix was needed.

## Final gate (on the combined tree, before push)

- `npm install` (306 packages) → `npx tsc --noEmit` **clean** → `npm test`: **217/217 pass, 0 fail** — exactly the expected count: 177 (main tip) + 10 browse + 20 humaninput + 10 synergy.

## Landing note for whoever merges this

Merge `claude/desktop-stack-screen-2026-10-20` into main with the standard ritual (merges API or local three-way; re-gate on main after). Given the drift above, expect the browse portion to come in as already-present; the real new content vs current main is humaninput + synergy (+30 tests → main would go 187 → 217).
