# MERGE PREP SUMMARY — prep-c (humaninput + synergy) — 2026-10-20

Staged resolutions only. **Nothing was merged, pushed, or deleted.** Both staged trees were gated on the exact bytes saved (`npm install` → `tsc --noEmit` → `npm test`); `node_modules` was removed after gating.

**Common snapshot**
- Fork base: `fc22170776e7b50ab4002b1568ed14bc93c5d0ec`
- "Ours": main @ **`fa5e16e951fc647de8c472e752c24db30c45a944`** — the live chain-log tip when prep started (router + nli + voice landed; 177 tests). The chain was still running (browse next), so if main has since advanced, treat these as composition references and re-run the three-way against the new tip; the per-branch notes say what carries and what to re-check.

---

## humaninput — CLEAN AUTO-MERGE, gate 197/197

- Branch tip: `a24ef02ab7bd3cc6b4fd862f41391758139cd555`
- Tree: `prep-c/humaninput/tree/desktop/` · Notes: `prep-c/humaninput/NOTES.md` · Scratch repo: `prep-c/humaninput/repo/`
- **Conflicts: none.** Git three-way merged automatically — the sides touch disjoint regions of both shared files:
  - `src/tools/platform.ts`: branch's screen-input rewrite (plan-replaying executors, glide `screenClick`, new `screenMove`/`screenScroll`) vs ours' voice/TTS block (`runBinary`, `speakText`).
  - `src/tools/index.ts`: branch's `control_screen` schema (`move`/`scroll` actions, `direction`/`amount`) vs ours' shopping/Tor/voice registrations.
  - New files: `src/tools/human.ts`, `test/humaninput.test.ts`.
- Spot-checks confirmed both sides present in the merged tree (executors + `speakText`; enum `["type","key","click","move","scroll"]`).
- **Gate: typecheck clean, 197/197** (= 177 + 20, exact).

## synergy — 3 composed hunks in one file, gate 187/187

- Branch tip: `830c8920f9e92c122fd641fa3be38d50bc23abd8`
- Tree: `prep-c/synergy/tree/desktop/` · Notes: `prep-c/synergy/NOTES.md` · Scratch repo: `prep-c/synergy/repo/`
- **Conflicts: `src/tools/index.ts` only, 3 hunks, all composed (union, nothing dropped):**
  1. Imports — ours' shopping import + re-export block **and** theirs' `seeActVerifyTool` import both kept.
  2. `ALL_TOOLS` — voice tools + memory tools + `seeActVerifyTool` + `...shoppingTools()` all present.
  3. `toolsFor` — theirs' combined `(control_screen || see_act_verify)` screenControl gate + ours' `speak`/`ttsEnabled` gate both kept.
- Clean takes: `src/tools/screenact.ts` + `test/screenact.test.ts` (new), branch versions of `src/vision.ts` + `test/vision.test.ts` (ours == base for both).
- **Gate: typecheck clean, 187/187** (= 177 + 10, exact).

### Synergy seam map (detail in synergy/NOTES.md) — re-check after browse + humaninput land

This staging is against **pre-browse main** by design; the chain lands browse → humaninput → synergy. Flagged hunks:

1. **index.ts import block — re-compose.** Browse registers `browse_task` from new `src/tools/browse.ts` in the same block as staged hunk 1; union = shopping + screenact + browse imports.
2. **ALL_TOOLS — re-compose.** Browse's entry joins the same array as staged hunk 2; keep every entry from all three sides (ordering not load-bearing).
3. **toolsFor filter — likely survives.** Browse shows no filter edit; staged hunk 3 should apply as-is — verify, don't assume.
4. **platform.ts — signature-stable, behavior changes.** `screenact.ts` calls only `os.screenshotBuffer()`, `os.screenClick(x, y, "left")`, `os.screenType(t)`, `os.screenKey(k)` (screenact.ts:253–256). Humaninput keeps those signatures, rewrites click/type internals (glide ≤1,200 ms cap; typing plan ≤20 s cap), and adds `screenMove`/`screenScroll`, which screenact never calls. No code change needed; allow human-paced durations in any live re-check.
5. **vision.ts — safe.** Synergy's change is an additive optional `maxTokens = 2048` param on `Vision.describe()`; neither browse nor humaninput touches the file.
6. **Expected count at real merge:** 197 (post-humaninput) + 10 = **207**.
