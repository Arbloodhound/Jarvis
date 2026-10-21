# PREP NOTES — echolalia (claude/desktop-parity-echolalia-2026-10-20)

**Snapshot used ("ours"):** main `4022799dd52f34c7dafa4d6993572f7a0343a67c` (live ref re-checked at prep start; matches chain log after merge #2 nli; suite 160).
**Base:** `fc22170776e7b50ab4002b1568ed14bc93c5d0ec`. **Theirs:** `de051c854b03c7ffed9da1e083c833d32f595aa2`.
**Method:** scratch git repo (prep-b/echolalia/repo), three commits base → ours / theirs, `git merge theirs` on ours.
**Result:** resolved tree in prep-b/echolalia/tree/ — **typecheck clean, npm test 172/172** (= 160 ours + 12 echolalia, exactly the chain log's expected +12). Nothing merged/pushed/deleted.

## Auto-merged (no conflict)
README.md, src/agent/fast.ts, src/agent/loop.ts, src/cli.ts, **src/memory.ts** (see touchpoints below), new src/echolalia.ts, new test/echolalia.test.ts.

## Conflicts — 8 hunks in 3 files, all neighbor-block compositions (nothing dropped)
1. **src/config.ts (3 hunks)** — type block, defaults, loadConfig: ours carries `tor` + `nli` blocks; theirs adds `memory: { verifyBeforeStore }`. Composed all three in all three places. Trap (same as nli's merge): git splits the type hunk before nli's closing `};` — the composed text must re-add it before the memory block.
2. **src/runtime.ts (4 hunks)** — (a) fields: ours `conversations` + `cwd` composed with theirs `readonly audit` + `readonly pending: EcholaliaStore`; (b) the Tor-probe hunk: HEAD side also carries `torReachable` + `refreshTorStatus()` above `update()` — composed signature gains BOTH `tor?: boolean` and `verifyBeforeStore?: boolean`; (c) update() body: Tor block + verifyBeforeStore one-liner both kept; (d) state(): ours `lastRoute` + theirs `verifyBeforeStore` / `pendingMemory` / `quarantinedMemory` all kept. Note: ours had NO `audit` field (Agent got `new AuditLog(...)` inline); theirs promotes it to a field — the auto-merged constructor already uses `this.audit` consistently (single `audit:` key, verified).
3. **src/tools/index.ts (1 hunk)** — ALL_TOOLS list: ours ends `rememberTool, recallTool, forgetTool, ...shoppingTools()`; theirs adds `memoryPendingTool, memoryDecideTool`. Composed: `rememberTool, recallTool, forgetTool, memoryPendingTool, memoryDecideTool, ...shoppingTools(),`.

## memory.ts touchpoints — PRECISE (for the later CLAIMS merge)
- Ours (current main) changed memory.ts **zero lines** vs base — that's why it auto-merged here.
- Echolalia's entire memory.ts delta vs base is 3 spots:
  1. `Fact` interface gains `provenance?: string` (after the existing fields, ~line 23).
  2. `remember(text: string)` → `remember(text: string, provenance?: string)`.
  3. Fact construction spreads `...(provenance ? { provenance } : {})`.
- Call site that depends on the positional string signature: `src/echolalia.ts` `promote()` calls `this.memory.remember(entry.claim, "echolalia: <why>; source: <source>; echoes: <n>; pending id <id>")`. The direct path (`src/tools/index.ts` remember tool) still calls `ctx.memory.remember(fact)` with one arg.
- **Collision forecast for claims:** the claims branch rewrites the same `Fact` interface (kind/salience/reinforcedAt/supersede fields) and the same `remember()` signature (options object). When claims merges over an echolalia-containing main, expect conflicts at exactly: Fact interface (compose: keep `provenance?` alongside the claims fields), remember() signature (must serve BOTH the claims options and echolalia's positional provenance string — either an overload or adapt the echolalia.ts call site to the options shape), and the fact-construction spread (compose both spreads). The claims report already names `Memory.remember()` as the composition seam.
