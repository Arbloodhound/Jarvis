# PARITY MERGE CHAIN — 2026-10-20 (merge coordinator, per Rafael's approval)

**Goal:** merge all 11 desktop parity branches into main of Arbloodhound/Jarvis.
**Start:** main = c60f6e06602a0e345e0a2a53f4ac1851b7304471 (desktop suite 125/125, typecheck clean; context features merged).
**Branches:** all forked from old main fc22170776 (73-test baseline). Order: router → nli → voice → browse → humaninput → synergy → investigate → skills → scheduler → echolalia → notifications.
**Expected new tests per branch** (branch total − 73): router +14, nli +21, voice +17, browse +10, humaninput +20, synergy +10, investigate +14, skills +18, scheduler +19, echolalia +12, notifications +16. Expected final total: 296.

Ritual per branch: merges API → fresh-fetch main → full gate (tsc + npm test) → fix interactions narrowly if needed → delete branch on green → log here.

---

## 1. router — MERGED ✓
- Merges API: 409 → local three-way merge (base fc221707 / main c60f6e06 / branch a3fbc77e58).
- Conflicts: `src/agent/loop.ts` (5 hunks) + `src/cli.ts` (2 hunks) — main's `extraContext` (context features) vs router's `route` param in the same signatures. Resolved by COMPOSING: `run()` takes extraContext + channel opts; runFast/runModel take both; cli keeps --file/ownerContext/syncConversation and adds `{channel:"cli"}`.
- Interaction found by the gate (138/139): router's test calls `run(req, hooks, {channel:"voice"})` — options-shaped third argument — while context features pass a string there. Composed signature accepts `string | {channel}` as arg 3 (channel from either position); no test changed, no feature dropped. Re-gate: 139/139.
- Landed: merge commit **a8796088e9dd04a1552f51888fa469cd84f2a2f0** (two parents: c60f6e06 + a3fbc77e58). Files: 6 changed + 2 added, desktop only.
- Post-merge gate on fresh-fetched main: typecheck clean, **139/139** (= 125 + 14 ✓).
- Branch deleted: 204 ✓. Main now a8796088.

## 2. nli — MERGED ✓
- Merges API: 409 → local three-way merge (base fc221707 / main a8796088 / branch ba431f8aa2).
- loop.ts AUTO-merged (NLI wiring landed cleanly alongside router). Conflicts: `src/config.ts` (3 hunks: type, defaults, loadConfig merge) + `src/runtime.ts` state() — settings-block neighbours (tor from main vs nli from branch). Composed: both blocks survive in all four places (incl. the Tor doc comment).
- Landed: merge commit **4022799dd52f34c7dafa4d6993572f7a0343a67c** (two parents: a8796088 + ba431f8aa2). Files: 3 changed + 2 added.
- Post-merge gate on fresh-fetched main: typecheck clean, **160/160** (= 139 + 21 ✓).
- Branch deleted: 204 ✓. Main now 4022799d.

## 3. voice — MERGED ✓
- Merges API: 409 → local three-way merge (base fc221707 / main 4022799d / branch 324e2c9e5c).
- Conflicts (1 hunk each): `src/runtime.ts` (AgentDeps/deps wiring), `src/tools/index.ts` (toolsFor signature), `src/cli.ts` (imports). Composed: runtime gains `transcriber` dep + toolsFor gains `ttsEnabled` ALONGSIDE main's `tor` option; cli keeps both probeTorProxy and speak imports.
- Extra catch: the auto-merge duplicated `import path from "node:path"` in cli.ts (two import groups) — typecheck (TS2300) caught it; duplicate line removed, nothing functional dropped.
- Gate on resolved tree: typecheck clean, **177/177** (= 160 + 17 ✓).
- Landed: merge commit **fa5e16e951fc647de8c472e752c24db30c45a944** (two parents: 4022799d + 324e2c9e5c). Files: 6 changed + 2 added.
- Post-merge gate mode: **TRIMMED** (per acceleration protocol) — landed desktop subtree hash c6ffa61779 == locally gated tree hash; fresh-fetch typecheck clean.
- Branch deleted: 204 ✓. Main now fa5e16e9.

## 4. browse — MERGED ✓
- Merges API: 409 → local three-way merge (base fc221707 / main fa5e16e9 / branch 389274f59d).
- Single conflict: `src/tools/index.ts` imports — composed: main's shopping import + re-export block and the browse import both survive.
- Gate on resolved tree: typecheck clean, **187/187** (= 177 + 10 ✓).
- Landed: merge commit **9d71ceeb00f269641d6447f7f00fcdd3387aba02** (two parents: fa5e16e9 + 389274f59d). Files: 1 changed + 2 added. Landed desktop subtree fb3519db == scratch HEAD tree; scratch worktree clean (gate ran on exactly the landed content).
- Post-merge gate mode: **TRIMMED** — fresh-fetch typecheck CLEAN ✓ (landed tree hash-identical to the locally gated tree).
- Branch deleted: 204 ✓. Main now 9d71ceeb.

## PLAN CHANGE (owner: "put them all in one branch") — ACKNOWLEDGED
The sequential chain STOPS here (4 of 11 parity branches merged individually: router, nli, voice, browse). Remaining seven parity features are being composed by stack builders off current main into three stack branches:
- claude/desktop-stack-screen-2026-10-20 (humaninput + synergy; browse already landed individually)
- claude/desktop-stack-work-2026-10-20 (investigate + skills + scheduler)
- claude/desktop-stack-comms-2026-10-20 (echolalia + notifications)
My role becomes FINAL COMPOSER: when all three stack branches are pushed WITH their STACK_*.md reports on disk, merge each into main in turn (compose overlaps, never drop), run the FULL gate on main after the combined landing (expected ≈296 total = 187 + humaninput 20 + synergy 10 + investigate 14 + skills 18 + scheduler 19 + echolalia 12 + notifications 16), fix interactions narrowly if any, then delete ALL merged branches (remaining parity branches + the three stack branches) and complete this log. Composition does NOT start until all three stack reports exist.
