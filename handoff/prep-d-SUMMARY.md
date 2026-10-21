# PREP-D SUMMARY — memory-claims landing staged

**Branch:** `claude/desktop-memory-claims-2026-10-20` @ `20308f39305f0141e7363b30e8dd3f00ac699837` (fork base `c60f6e06`)
**Staged tree:** `prep-d/claims/tree/desktop/` (62 files) · **Scratch repo:** `prep-d/claims/repo/` · **Full notes:** `prep-d/claims/NOTES.md`
**Nothing merged, pushed, or deleted.**

## Snapshots

| Role | Commit | Tests at tip |
|---|---|---|
| Base | `c60f6e06602a0e345e0a2a53f4ac1851b7304471` | 125 |
| Ours (chain-log tip at prep start) | `fa5e16e951fc647de8c472e752c24db30c45a944` | 177 |
| Ours (live main at prep end — browse landed during prep) | `9d71ceeb00f269641d6447f7f00fcdd3387aba02` | 187 |
| Theirs (claims) | `20308f39305f0141e7363b30e8dd3f00ac699837` | 138 on its own base |

## Result: ZERO conflicts — against both main snapshots

Claims' entire delta is 3 files (`src/memory.ts` rewrite 142→331 lines, the remember/recall block of `src/tools/index.ts`, new `test/claims.test.ts`). `git merge` auto-composed it with current main both times:

- **memory.ts lands byte-identical to the claims version** — no landed parity branch (router/nli/voice/browse) has touched memory.ts at all.
- **tools/index.ts composed cleanly** — claims' `CLAIM_KINDS` import + claims-tagged remember + `includeSuperseded`/reinforce-on-recall coexist with voice's transcriber/speak tools and browse's tool block (disjoint regions; verified by grep in the resolved file).

**Gate on the resolved tree (tsc + full npm test):**

| Against | tsc | Tests |
|---|---|---|
| main fa5e16e9 | clean | **190/190** (= 177 + 13 ✓) |
| main 9d71ceeb (staged tree) | clean | **200/200** (= 187 + 13 ✓) |

## The one forward risk — echolalia (lands before claims, via stack-comms)

Echolalia's memory.ts footprint is exactly 2 hunks / 3 sites (Fact `provenance?: string` at base L22; `remember(text, provenance?: string)` signature at L56; fact-literal spread at L60 — references exact, prep-b's base memory.ts verified byte-identical to c60f's). When claims lands on a main that already has echolalia:

1. **Fact interface:** both insert at the same point → add/add conflict → keep both field blocks.
2. **remember() signature — the real collision:** echolalia's positional `provenance?: string` vs claims' `opts: RememberOptions`. Compose: `provenance?: string` joins `RememberOptions`, the literal spreads `opts.provenance`, and exactly **one call site** changes — `src/echolalia.ts` `promote()` (~L322) becomes `remember(entry.claim, { provenance: \`echolalia: …\` })`. No test call sites affected.
3. **rememberTool is rewritten by both branches** (echolalia: `source`/`source_text` + pending-store routing; claims: `kind`/`salience`/`supersedes`) → composed schema = union of all six fields, echolalia routing first. Flagged decision: under verify-before-store the claims tags have no pending-entry home and are dropped on that path — coordinator to confirm that's the intended behavior.
4. Migration `norm()`, recall/search, forget: no echolalia interaction (details in NOTES.md).

Full hunk-by-hunk inventory of claims' memory.ts (4 hunks, with line numbers) and the composed resolutions are in `prep-d/claims/NOTES.md`.
