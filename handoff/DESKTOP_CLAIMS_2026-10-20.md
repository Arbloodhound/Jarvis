# Desktop memory claims machinery — build report

**Date:** 2026-10-20 · **Branch:** `claude/desktop-memory-claims-2026-10-20` (NOT merged — owner's call)
**Base:** main @ `c60f6e06602a0e345e0a2a53f4ac1851b7304471` · **Remote tip:** `20308f39305f0141e7363b30e8dd3f00ac699837`
**Remote commits:** `3f0ecc9dc6` (feat) → `20308f3930` (tests), mirroring local `0870f36585` → `625eec8bd4`
**Files:** `desktop/src/memory.ts` (claims layer), `desktop/src/tools/index.ts` (remember/recall surface), `desktop/test/claims.test.ts` (new, 13 tests). Nothing outside `desktop/`; no workflow files.
**Gate:** baseline first (typecheck clean, 125/125), final **typecheck clean, 138/138 tests** (125 baseline + 13 new). Push verified: remote branch `desktop/` tree == local tip tree, blob-for-blob (54 files). Tip independently re-checked via `gh.py get-ref`.
**Draft PR:** attempted via API → HTTP 403 "Resource not accessible by personal access token" (expected with this token; same as prior branches).
**Sequencing:** a parity merge chain is running against main in parallel. This branch was built cleanly from `c60f6e06` and merges AFTER the chain; it does not touch main or any other branch.

## Source semantics found (phone/rebuild line)

PARITY_MAP_2026-10-20.md item #3 ("Memory: claims, kind/salience, decay, provenance, supersede — LIVE on phone, PARTIAL on desktop") names the source: **`harness/memory.py`** (the v5 Python reference), with the same design implemented in Kotlin at `jarvis-native/app/src/main/java/com/agent/jarvis/harness/MemoryClaims.kt`. Both agree on:

- A claim stores: text, **kind**, **salience**, confidence, speaker, the exact source message, what it **supersedes**, and when it was invalidated.
- **Kinds (closed set):** `preference, fact, finding, request, completion, correction, observation` — validated on write.
- **Salience:** a tier string `low | medium | high`; `write()` default `"medium"`.
- **Decay:** query-time temporal factor `exp(-ln2 * ageDays / 30)` from `reinforced_at` (30-day half-life); **evergreen** claims (curated roots) never decay. Decay never deletes.
- **Reinforce:** resets `reinforced_at` and bumps confidence (+0.05, capped) — explicit method, active claims only.
- **Supersede:** an **explicit write-time link** — `write(..., supersedes=<old id>)` marks the old claim `status="superseded"` + `invalidated_at`; retrieval returns active claims only; `history(id)` walks the supersedes chain. (The Kotlin line additionally has a verifier that *proposes* supersede targets; per the build brief, no judge was ported — the desktop takes the harness's explicit-link mechanism, with the link supplied at remember time.)

## As built — the desktop fact record

`Fact` gains (all additive; `desktop/src/memory.ts`):

| Field | Rule |
|---|---|
| `kind: ClaimKind` | The source's closed set, verbatim. `remember()` validates; an unknown kind falls back to `"fact"` (desktop stores are user data — degrade, don't throw). Default `"fact"`. |
| `salience: "low" \| "medium" \| "high"` | Explicit at remember time, else defaulted by kind (table below). Ranking weight: low 0.5 / medium 1 / high 1.5 (`SALIENCE_WEIGHT`). |
| `reinforcedAt: string` (ISO) | Set at write; moved by reinforcement. Decay runs from here. |
| `evergreen?: boolean` | Curated facts never decay (factor pinned at 1.0). |
| `supersedes?: string` | On the NEW fact: the id it replaces (the source's write-time link). |
| `supersededBy?: string` | On the OLD fact: the id that replaced it. Set ⇒ excluded from default recall. |
| `invalidatedAt?: string` | Set when a fact is superseded (the source's `invalidated_at`). |

**Kind-default salience** (desktop table; the source defines only the flat `"medium"` write default): correction → **high** (a correction should outrank what it corrects), completion → **low**, observation → **low** (background by nature), preference/fact/finding/request → medium.

**Ranking** (both recall paths): `content × SALIENCE_WEIGHT[salience] × temporalFactor(fact, now)` where content is the semantic dot product in `search()` or the word-overlap count in `recall()`. Ties: newer `at`, then id (deterministic).

**Decay:** `temporalFactor` (exported, unit-tested) = 1.0 for evergreen or age ≤ 0, else `exp(-ln2 · ageDays / 30)` from `reinforcedAt`. It only multiplies ranking weight — **it never deletes or hides**; a 10-year-old fact still surfaces when it's the only match (tested). Forgetting remains the explicit `forget()` act (trash-first, unchanged).

**Reinforcement:** `Memory.reinforce(id)` — active facts only (returns `false` for missing/superseded, mirroring the source): sets `reinforcedAt = now` and promotes salience one tier (low→medium→high). Triggered at exactly the surfaces the design names: **re-remembering** the same text (the old dedupe path now reinforces instead of silently returning) and **recall-and-use** (the recall tool reinforces every hit it returns). `Memory.search()`/`recall()` themselves stay pure — `requestContext` (agent/prompt.ts) benefits through the new ranking with no signature change (verified by typecheck; prompt.ts untouched).

**Supersede:** `remember(text, { supersedes: oldId })` — the new fact records `supersedes`, the old fact gets `supersededBy` + `invalidatedAt` (an existing `supersededBy` is never overwritten, so history keeps the first replacer). Default `recall`/`search` exclude superseded facts; `{ includeSuperseded: true }` (recall/search options, and the recall tool's `includeSuperseded` argument) includes them. Chains resolve: A→B→C surfaces only C, and `Memory.chain(id)` walks C→B→A (the source's `history()`). `forget()` reaches superseded facts both by id and by text (its internal match uses includeSuperseded), so replaced facts stay forgettable. Re-remembering a text while passing `supersedes` links from the existing fact as well.

**Tool surface (additive only):** `remember` gains optional `kind` (zod enum over CLAIM_KINDS), `salience`, `supersedes`; its reply notes `(supersedes <id>)` when a link was made. `recall` gains optional `includeSuperseded`; its output marks each hit's kind — `(id) [kind] text` — plus `(supersedes <id>)` / `(superseded by <id>)` when linked. `forget` is unchanged at the surface. Existing call shapes (`{fact}`, `{query}`) validate unchanged — gate/fast/integration/e2e suites green unmodified.

**Deviations from the source, stated:** (1) Desktop facts carry no `confidence`, `speaker`, `source_message`, or `quote` — provenance fields from the phone design were out of this build's scope, so the source's prior term (0.3 × confidence) has no desktop counterpart; content × salience × freshness is the ranking. (2) Salience tiers get numeric weights on desktop (the source stores tiers but does not score with them) — required for the salience-in-ranking rule. (3) Ids keep the desktop's 8-char scheme (not the source's `claim:`-prefixed ids) so existing tool output and stored references stay valid. (4) Forget stays trash-based rather than the source's `retracted` status.

## Migration

The constructor normalizes every loaded fact (facts and trash): missing/invalid `kind` → `"fact"`, missing/invalid `salience` → `"medium"`, missing `reinforcedAt` → the fact's own `at`, missing `at` → the store file's mtime (then now). If anything changed, the store is immediately re-saved in the new format (atomic tmp+rename, 0600, as before). Nothing is dropped: text, timestamps, vectors (`vec`/`emb`) and trash all carry over. Proven by a fixture test that writes the exact old-format JSON, loads it, and asserts the defaults, the re-saved file contents, and that recall still finds the fact.

## Echolalia seam (design note — not implemented here)

The parity echolalia branch (verify-before-store: pending → promote on echo / quarantine on contradiction) is **not** on this base and was not reimplemented. The composition point is `Memory.remember()` in `desktop/src/memory.ts`: it is the single write entry for every surface (tool, fast path, runtime). Post-merge, the echolalia flow should sit *in front of* it — candidate facts held in its pending queue, and on promotion it calls `remember(text, { kind, salience, supersedes })`, where a contradiction verdict maps directly onto the `supersedes` link this build adds (the natural target: the contradicted fact's id). Promoted facts are ordinary claims from that moment — kind/salience/decay/supersede all apply unchanged, and quarantined candidates simply never reach `remember()`, so they can never surface in recall. No field changes are needed on this side; the record was deliberately given the `supersedes` write-time link (rather than any store-internal state) so the verifier's decision lands as data, not as a second lifecycle.

## Tests — 13 new in `desktop/test/claims.test.ts` (suite 138/138)

All time control via the injected clock (`new Memory(file, () => new Date(nowMs))`) — no wall-clock sleeps:

- kind defaulting (fact default, explicit kind, invalid-kind fallback path) + salience defaults by kind + explicit-overrides-default
- salience tie-breaking in word-overlap recall and in semantic search (stub embedder, equal vectors)
- `temporalFactor`: fresh = 1, 30 days = 0.5, 60 days = 0.25, evergreen = 1 at 10 years
- decay ranking over advanced time; decayed fact never deleted (still in `all()`, still the sole match at +10 years)
- reinforcement resets decay to factor 1 and promotes low→medium→high; unknown id → false
- re-remember dedupe reinforces (same id, one fact, decay reset, case-insensitive)
- supersede: both link directions + `invalidatedAt`, default exclusion, `includeSuperseded`, A→B→C chain (only C surfaces, `chain()` order), superseded can't be reinforced
- superseded excluded from semantic search by default, included on request
- forget reaches a superseded fact by id and by text
- old-format migration fixture (defaults, re-save verified on disk, recall works)
- persistence round-trip (kind/salience/evergreen/links survive reload)
- tool surface end-to-end (remember with kind+supersedes via ALL_TOOLS, recall output marking + hidden-by-default, recall-and-use reinforcement observed via temporalFactor)

Pre-existing suites — including the memory-adjacent integration, e2e, gate, tor, shopping, and context tests — pass **unmodified**.

## Merge note

Merges after the parity chain, on the owner's word, like the other desktop branches. Expected friction: low — the branch touches `src/memory.ts` wholesale (no other in-flight branch owns it) plus the remember/recall block of `src/tools/index.ts`, which the chain may also touch; any conflict there is a compose-both-sides edit in one small region.
