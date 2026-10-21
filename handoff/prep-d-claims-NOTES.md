# PREP-D — memory-claims landing notes

**Branch staged:** `claude/desktop-memory-claims-2026-10-20` @ `20308f39305f0141e7363b30e8dd3f00ac699837` (forked from `c60f6e06`).
**Prep type:** scratch three-way merge only — nothing merged, pushed, or deleted.
**Scratch repo:** `prep-d/claims/repo/` (commits: `6662ab9` base, `26fbd69` ours-1, `6a7bf78` ours-current, `c75c0e9` theirs, merges `f05ea68` + `f0e2e47`).
**Resolved tree (staged, = merge `f0e2e47`):** `prep-d/claims/tree/desktop/` — 62 files, no node_modules.
**Source snapshots:** `prep-d/src-base/` (c60f6e06), `prep-d/src-ours/` (fa5e16e9), `prep-d/src-ours-current/` (9d71ceeb), `prep-d/src-claims/` (20308f39).

## Snapshots used — main moved during prep

- Chain-log tip at prep start: **`fa5e16e951fc647de8c472e752c24db30c45a944`** (router+nli+voice landed, 177 tests).
- Live main at prep end: **`9d71ceeb00f269641d6447f7f00fcdd3387aba02`** (browse landed individually during prep, 187 tests; chain then switched to the 3-stack composition plan).
- The merge was staged against BOTH tips. Result identical in shape both times (see below). The staged tree is the **9d71ceeb-based** resolution.
- Browse's landing delta vs the fa5e snapshot, for the record: new `src/tools/browse.ts` + `test/browse.test.ts`; `src/tools/index.ts` hunks at imports (~L16), a browse tool block (~L577), ALL_TOOLS (~L839). **memory.ts untouched by browse — and untouched by every parity merge landed so far (router, nli, voice, browse).**

## Claims delta (theirs vs base c60f6e06) — exactly 3 files

| File | Change |
|---|---|
| `src/memory.ts` | Wholesale claims rewrite: 142 → 331 lines (4 hunks, inventoried below) |
| `src/tools/index.ts` | Import line + remember/recall tool block only |
| `test/claims.test.ts` | NEW, 13 tests |

## Merge result — ZERO conflicts, both snapshots

`git merge` (ort) auto-merged cleanly against fa5e16e9 **and** against 9d71ceeb. Merge delta is the same 3 files both times (+470/−42):

- **`src/memory.ts` lands VERBATIM as the claims version** (verified byte-identical to `src-claims` copy). No landed branch has touched memory.ts, so there is nothing to compose — as long as claims lands before any other memory.ts change, this stays a fast-forward-shaped file take. (Echolalia is the exception — see the composition section below; it lands BEFORE claims via stack-comms.)
- **`src/tools/index.ts` auto-composed.** Verified in the resolved file: claims' `CLAIM_KINDS` import (L14) + remember schema (`kind`/`salience`/`supersedes`, ~L783–789) + recall (`includeSuperseded`, reinforce-on-recall, ~L796–802) coexist with voice's `transcriber` ToolContext field (L41), `transcribe_audio` (L717), `speak` (L737), `toolsFor` `ttsEnabled` (L830), and browse's import (L19) + tool block + ALL_TOOLS entries. Ours' hunks and claims' hunks are in disjoint regions; the import block interleaved without conflict both times.

## Gate on the resolved tree (full: tsc + npm test)

| Snapshot | tsc | Tests | Expected |
|---|---|---|---|
| ours = fa5e16e9 (177) | clean | **190/190** | 177 + 13 = 190 ✓ |
| ours = 9d71ceeb (187) | clean | **200/200** | 187 + 13 = 200 ✓ |

## `src/memory.ts` — claims hunk inventory (vs base, precise)

Base file: 142 lines. Claims file: 331 lines. 4 diff hunks:

1. **Base L1–31 → claims L1–101 (header + types).** Doc comment rewritten (claims semantics); `statSync` added to the fs import; new exports `CLAIM_KINDS` (closed set: preference, fact, finding, request, completion, correction, observation), `ClaimKind`, `Salience`, `SALIENCE_WEIGHT` (0.5/1/1.5), `KIND_DEFAULT_SALIENCE`, `HALF_LIFE_DAYS = 30`; `Fact` gains, **inserted between `at` and the `vec` comment**: `kind`, `salience`, `reinforcedAt`, `evergreen?`, `supersedes?`, `supersededBy?`, `invalidatedAt?` (all required-or-optional as in DESKTOP_CLAIMS_2026-10-20.md); new `RememberOptions` interface (`kind?`, `salience?`, `evergreen?`, `supersedes?`).
2. **Base L37–51 → claims ~L107–161 (constructor + helpers).** Constructor gains injectable `now` clock; load path gains the lossless migration `norm()` (defaults kind→"fact", salience→"medium", `reinforcedAt`→`at`→file mtime→now; re-saves if migrated); new module helper `isActive`; exported `temporalFactor()` (30-day half-life from `reinforcedAt`, evergreen pinned at 1).
3. **Base L53–99 → claims ~L163–289 (write/read core — the wholesale hunk).** Private `nowIso()`, `weight()`, `reinforceFact()`; `remember(text, opts: RememberOptions = {})` — dedupe now REINFORCES instead of silently returning (and can attach a supersede link); fact literal built with kind/salience/reinforcedAt + conditional evergreen/supersedes spreads; `markSuperseded()`; public `reinforce(id)`, `byId(id)`, `chain(id)`; `recall(query, limit, { includeSuperseded })` and `search(query, embedder, limit, { includeSuperseded })` rank by content × salience weight × temporal factor and exclude superseded by default.
4. **Base L114 (one line).** `forget()`'s internal recall call gains `{ includeSuperseded: true }` so superseded facts stay forgettable.

Everything else in the file (embeddings/`ensureEmbedded`, trash handling, save) is unchanged from base.

## ⚠ Echolalia × claims — the composition the FINAL landing must do

Echolalia lands **before** claims (via `claude/desktop-stack-comms-2026-10-20`, per the chain log's stack plan), so when claims finally lands, main's memory.ts will NOT be the c60f base — it will carry echolalia's changes, and claims' three-way (base c60f / ours = main-with-echolalia / theirs = claims) will collide in exactly the places below. Prep-b verified its base memory.ts is byte-identical to the c60f base (`SAME_BASE_MEMORY`), so these line references are exact.

### Echolalia's complete memory.ts footprint (2 hunks, 3 edit sites, +2 lines)

- **E1 — Fact interface, base L22** (immediately after `at: string;`, before the `vec` comment):
  ```ts
  /** How this fact earned its place (e.g. echolalia verification), when recorded. */
  provenance?: string;
  ```
- **E2 — remember signature, base L56:** `remember(text: string)` → `remember(text: string, provenance?: string)`
- **E3 — fact literal, base L60:** appends `...(provenance ? { provenance } : {})` to `{ id, text, at }`.

Echolalia touches nothing else in memory.ts (constructor, recall, search, forget all untouched).

### Collisions and their composed resolutions

1. **E1 × claims hunk 1 — same insertion point.** Both insert fields between `at` and `vec`. Expect an add/add conflict. **Resolution: keep both blocks** — claims' seven fields plus echolalia's `provenance?: string` (order irrelevant; suggest provenance last, after `invalidatedAt?`).
2. **E2+E3 × claims hunk 3 — the one real collision.** Claims' signature is `remember(text: string, opts: RememberOptions = {})`; echolalia's positional `provenance?: string` second parameter **cannot coexist** with it (and echolalia's call site would fail typecheck against the claims signature). **Composed form:** add `provenance?: string` to `RememberOptions`; in claims' fact literal add `...(opts.provenance ? { provenance: opts.provenance } : {})`. Exactly **one call site in the whole repo must then be edited**: `src/echolalia.ts`, `promote()` (~L322 in the branch tree):
   ```ts
   // branch form (positional string — will not typecheck post-claims):
   const fact = this.memory.remember(entry.claim, `echolalia: ${why}; source: ${entry.source}; echoes: ${entry.echoes}; pending id ${entry.id}`);
   // composed form:
   const fact = this.memory.remember(entry.claim, { provenance: `echolalia: ${why}; source: ${entry.source}; echoes: ${entry.echoes}; pending id ${entry.id}` });
   ```
   No other call site changes: echolalia's only other `remember(` call is the tool's no-options fallback (`tools/index.ts`, unchanged shape), and no echolalia test calls `Memory.remember` directly (verified by grep over the branch's src+test).
3. **Migration interaction — no change needed, stated for safety.** Claims' `norm()` mutates loaded facts in place and only sets kind/salience/at/reinforcedAt when missing/invalid; an echolalia-written `provenance` field passes through untouched and survives the migration re-save.
4. **Dedupe-path nuance — acceptable as-is, stated.** On re-remember of an existing text, claims reinforces and returns the existing fact; a `provenance` passed on that path is not recorded (mirrors echolalia's old behavior, which also dropped provenance on duplicates). Echolalia's promote path always writes fresh claims, so it is not affected in practice.

### tools/index.ts × echolalia (for the same landing)

- **rememberTool is rewritten by BOTH branches — compose, don't pick.** Echolalia's version adds `source`/`source_text` schema fields and routes through `ctx.echolalia.submit(...)` when verify-before-store is on, falling back to `ctx.memory.remember(fact)`. Claims' version adds `kind`/`salience`/`supersedes` and calls `remember(fact, { kind, salience, supersedes })`. **Composed tool:** schema = union (`fact, kind, salience, supersedes, source, source_text`); run = echolalia branch first when `ctx.echolalia` is present, else the claims call. **Open decision for the coordinator (flagged, not silently resolved):** on the echolalia path the claims tags have nowhere to go — pending entries carry no kind/salience/supersedes — so under verify-before-store those tags are currently dropped. Smallest honest behavior: they apply only on the direct path, and the tool description should say so.
- **No other tools/index.ts overlap:** echolalia's import (`../echolalia.js`), its `ToolContext.echolalia` field, and its two new tools (`memory_pending`, `memory_decide` + ALL_TOOLS entries) are all in regions claims doesn't touch; claims' recallTool changes are claims-only (echolalia doesn't touch recallTool).

## Landing instruction (when the coordinator's word comes)

Re-run this exact three-way against the then-current main tip (the staged tree here is against 9d71ceeb; stacks land in between). If main's memory.ts still equals base + only the echolalia hunks above, apply the §composition resolutions and the result should be: claims memory.ts + provenance field + RememberOptions.provenance, echolalia.ts one-line call fix, composed rememberTool. Expected gate after landing: main's then-current count + 13 (claims) — plus echolalia's tests already counted in main by then.
