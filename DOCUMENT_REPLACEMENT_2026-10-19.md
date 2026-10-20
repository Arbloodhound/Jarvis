# Document Replacement Log — 2026-10-19

Replacement of all documentation in **Arbloodhound/Jarvis**, executed
2026-10-19 PDT (2026-10-20 UTC) at Rafael's instruction: repo documents only;
new versions written from the audited, post-merge state of the codebase; old
iterations archived inside the repo; nothing deleted.

**Method.** Every `.md` file on `main` was inventoried from the git tree
(55 files at tip `c8043e1c`). Each document to be replaced was first copied,
byte-identical, to `archive/2026-10-19/<original path>` and the archive copy
verified before the original path was overwritten. New content was grounded
only in the 2026-10-19 code audits (`AUDIT_REPORT.md` — 119 findings;
`AUDIT_SUPPLEMENT.md` — 87 findings) and `MERGE_LOG_2026-10-19.md`; claims the
audits do not cover were dropped or marked "not re-verified in the 2026-10-19
audits". Six parallel workers handled disjoint file groups; the in-app
`assets/guide.md` was synced by the coordinator.

**Final verification (full-tree sweep, blob-sha comparison):** 54/54 archives
byte-identical to the pre-replacement originals; 54/54 live files
byte-identical to their new versions; `MERGE_LOG_2026-10-19.md` untouched;
109 `.md` files on `main` (55 inventoried + 54 archives) before this log was
added. **0 problems, 0 outstanding failures.** Transient HTTP 409 conflicts
from concurrent pushes occurred on several files and all succeeded on retry.

**Counts: 55 inventoried · 54 replaced · 1 kept current · 0 failed.**

## Kept current (not replaced)

| Document | Size | Disposition |
|---|---|---|
| `MERGE_LOG_2026-10-19.md` | 12,812 | Kept — created today, already current. Blob verified unchanged after the replacement. No archive copy (nothing to archive). |

## Replaced (54)

Treatment classes — **A**: full rewrite. **A2**: targeted refresh (intro/status
rewritten, body/procedures preserved, contradicted claims corrected).
**B**: framed (new status section + original body verbatim). **C**: log
(framing/new top entry prepended, history byte-identical). **E**: evidence
(header + status note only, body byte-identical). **F**: app asset (format
preserved, facts refreshed, header as HTML comment). **R**: research (findings
preserved, implementation-status claims corrected by a superseding section).

### Root — living docs (Class A)

| Document | Old → New (bytes) | Archive | What changed |
|---|---|---|---|
| `README.md` | 6,084 → 11,889 | `archive/2026-10-19/README.md` | Expo-as-history framing replaced with the post-merge three-tree map (ultra-native + harness, desktop/, src/); consolidation state, audit totals, resolved DB critical, checked-clean properties, open issues; unverified claims dropped/flagged. |
| `START_HERE.md` | 8,836 → 12,304 | `archive/2026-10-19/START_HERE.md` | Stale branch refs → `main`; unverified model/size/timing specifics genericised; step flow kept; CI-not-compiled stop condition and audit cautions added. |
| `APP_OVERVIEW.md` | 7,371 → 13,402 | `archive/2026-10-19/APP_OVERVIEW.md` | Rebuilt on the audited subsystem map, harness + UltraDatabase v9, verified safety properties, open highs, per-subsystem verdicts; unverified counts removed. |
| `ARCHITECTURE.md` | 30,792 → 15,764 | `archive/2026-10-19/ARCHITECTURE.md` | Un-re-verified file-level map replaced with the audited package-level map (Kotlin app, harness, desktop 8-check gate, src/ live path + orphaned server); storage corrected to UltraDatabase v9. |
| `CLAUDE.md` | 7,739 → 13,791 | `archive/2026-10-19/CLAUDE.md` | Project identity corrected from "built on Expo/EAS" to the consolidated three-tree repo; operating rules and two-Claude workflow preserved; audited subsystem map + standing behaviour rules added. |
| `CONTRIBUTING.md` | 1,587 → 5,815 | `archive/2026-10-19/CONTRIBUTING.md` | Target branch → `main`; known-breakage block (2 broken test files, 2 unregistered manifest components, CI caveat); gate/shopping/Tor/vault invariants for PRs. |
| `.github/pull_request_template.md` | 914 → 2,526 | `archive/2026-10-19/.github/pull_request_template.md` | Structure/evidence labels kept; tree + `main` targeting, CI-not-compiled warning, known-breakage alternative to "tests green", manifest-gap checklist items added. |
| `PROCEED_GUIDE.md` | 3,799 → 8,060 | `archive/2026-10-19/PROCEED_GUIDE.md` | Branch/version/model-ID claims removed as not re-verified; flow kept; plaintext-key and voiceprint audit warnings and manifest-gap explanation added. |
| `DEPLOY_GUIDE.md` | 14,735 → 18,969 | `archive/2026-10-19/DEPLOY_GUIDE.md` | Unverified version/size/model specifics removed; structure kept; CI gate, GGUF/voiceprint cautions, claim-promotion and SchedulerReceiver caveats added. |

### Root — point-in-time reports & reviews (Class B)

| Document | Old → New (bytes) | Archive | What changed |
|---|---|---|---|
| `AGENT-ULTRA-BUG-REPORT.md` | 24,469 → 26,663 | `archive/2026-10-19/AGENT-ULTRA-BUG-REPORT.md` | Status section: the audits do not speak to Bugs 1–8 individually (not re-verified); TS lineage and orphaned `server/` state noted. Body verbatim. |
| `COLD-REVIEW.md` | 46,668 → 49,124 | `archive/2026-10-19/COLD-REVIEW.md` | Status section: Gate checked clean in the merged view; related Gate highs still open; claim-promotion supersede defect; device-proven claims not re-verified. Body verbatim. |
| `JARVIS_BUILD_REPORT.md` | 27,893 → 30,409 | `archive/2026-10-19/JARVIS_BUILD_REPORT.md` | Status section: merged-tree verdicts; GGUF size-only verification, plaintext key, voiceprint-default highs still open; hardware claims not covered. Body verbatim. |
| `LOG-FILE-PROLIFERATION-REPORT.md` | 13,873 → 16,183 | `archive/2026-10-19/LOG-FILE-PROLIFERATION-REPORT.md` | Status section: the proliferation topic and its documented fix were not covered by the audits. Body verbatim. |
| `V3_REVIEW_REPLY.md` | 6,483 → 8,845 | `archive/2026-10-19/V3_REVIEW_REPLY.md` | Status section: v3 decisions not re-verified; TS live path bypasses its own SafetyChecker/PermissionBroker; harness grant keying is the weak core. Body verbatim. |
| `V4_REVIEW_REPLY.md` | 10,907 → 13,370 | `archive/2026-10-19/V4_REVIEW_REPLY.md` | Status section: CI has not compiled the merged tree; unit tests still broken (2 files); manifest, grant-keying, claim items still open. Body verbatim. |
| `DIAGNOSTICS_AND_FIXES.md` | 4,356 → 6,725 | `archive/2026-10-19/DIAGNOSTICS_AND_FIXES.md` | Status section: phone/DIAG results not re-verified by the code-only audits; recipe self-confirm-past-Gate high noted. Body verbatim. |
| `SOLUTION_react_navigate_planning_step.md` | 11,727 → 14,010 | `archive/2026-10-19/SOLUTION_react_navigate_planning_step.md` | Status section: whether `planSteps()` was implemented is not covered by the audits; TS live-path verdict noted. Body verbatim. |
| `PROMPT_adb_diagnostic.md` | 2,907 → 5,214 | `archive/2026-10-19/PROMPT_adb_diagnostic.md` | Status section: topic not covered by the audits; the procedure stands as un-run, not a result. Body verbatim. |

### Root — handoffs, prompt, research

| Document | Class | Old → New (bytes) | Archive | What changed |
|---|---|---|---|---|
| `HANDOFF.md` | A | 6,351 → 6,495 | `archive/2026-10-19/HANDOFF.md` | Rewritten as the current post-merge handoff: main tip, audit totals, DB-wiring critical resolved, open items (2 manifest registrations, 2 broken tests, TS server orphaned, ~70 dead-code candidates, CI pending). |
| `HANDOFF_2026-10-19.md` | A | 10,320 → 7,101 | `archive/2026-10-19/HANDOFF_2026-10-19.md` | Rewritten: consolidation merges, audited codebase map, per-subsystem verdicts, audit highs; stale release/test-count/phone claims dropped to archive-only. |
| `MUSE_HANDOFF.md` | A | 4,690 → 5,540 | `archive/2026-10-19/MUSE_HANDOFF.md` | L1/L2/L3 layering kept; the harness package now exists (no longer "not wired/awaiting"); DB wiring resolved; remaining wiring/CI gaps stated. |
| `HANDOFF_V5_MISSING.md` | A | 5,821 → 5,999 | `archive/2026-10-19/HANDOFF_V5_MISSING.md` | Converted from build request to status doc: harness now contains claims/approvals/scheduler/skills/trash/forget/subagents/feed/ideas/artifacts/proactivity/connectors; still-open items listed. |
| `MUSE_PROMPT.md` | B | 2,597 → 4,550 | `archive/2026-10-19/MUSE_PROMPT.md` | Status section (the rebuild it asked for exists; audits done; open items) + original prompt body verbatim. |
| `MUSE_RESEARCH.md` | R | 40,637 → 43,182 | `archive/2026-10-19/MUSE_RESEARCH.md` | Research preserved verbatim; correction section supersedes status claims — grants/scheduler/skills/trash/forget/subagents/connectors/vault, previously "missing/proposed", now exist in the harness; plaintext-key high flagged. |
| `OPENCLAW_FINDINGS.md` | R | 38,913 → 41,416 | `archive/2026-10-19/OPENCLAW_FINDINGS.md` | Findings preserved verbatim; correction section: the §3 patterns were substantially adopted into the harness (snapshot-bound actions, exact-operation grants, 3-gate notifications, depth-1 subagents), with grant-keying/verbatim-quote caveats. |
| `CROSSEVAL_OPENCLAW.md` | R | 8,321 → 10,597 | `archive/2026-10-19/CROSSEVAL_OPENCLAW.md` | Evaluation preserved verbatim; correction section updates the Top-5 implementation status (ScreenGuard/NotificationForwardingPolicy/SkillStore/MemoryClaims now exist); latency findings not re-verified. |

### Root — reference & logs

| Document | Class | Old → New (bytes) | Archive | What changed |
|---|---|---|---|---|
| `SKILLS_REFERENCE.md` | A2 | 85,618 → 89,783 | `archive/2026-10-19/SKILLS_REFERENCE.md` | Front matter replaced (what this reference is + audited repo state; Expo/EAS framing remapped to the `src/` lineage, shipping app = Kotlin ultra-native + harness); skill-by-skill body preserved verbatim. |
| `GUIDE.md` | A2 | 117,508 → 128,045 | `archive/2026-10-19/GUIDE.md` | Intro/§1 rewritten to the audited state; new §11e (claims memory, approvals, scheduler, skills, trash, forget, subagents, feed, ideas, artifacts, proactivity, connectors + still-open manifest items); §13 audited approval rules; §14 corrected (connectors exist; memory deletion no longer all-or-nothing); unaudited detail flagged. |
| `CHANGELOG.md` | C | 28,819 → 31,168 | `archive/2026-10-19/CHANGELOG.md` | New top entry dated 2026-10-19: consolidation (58 branches → main; merges 4cff5d07/adbd9e84/ae030b9f), audits (119 + 87 findings, 0 critical post-merge), DB wiring resolved, still-open items, this doc replacement. History preserved verbatim. |
| `DEVLOG.md` | C | 407,612 → 409,307 | `archive/2026-10-19/DEVLOG.md` | Framing section prepended by script (consolidation + doc replacement; pre-2026-10-19 entries are the historical record, branch refs predate consolidation). Body byte-identical. |

### desktop/

| Document | Class | Old → New (bytes) | Archive | What changed |
|---|---|---|---|---|
| `desktop/README.md` | A | 8,824 → 11,892 | `archive/2026-10-19/desktop/README.md` | Full rewrite: desktop on `main` via the ccr merge; agent loop / 8-check gate / providers / memory as audited; verdict "works with defects" (87 supplement findings) and the 3 desktop highs. |
| `desktop/CLAUDE.md` | A | 2,294 → 6,552 | `archive/2026-10-19/desktop/CLAUDE.md` | Desktop contributor rules rewritten: gate order, no cross-provider fallback, Tor is not part of desktop, do-not-regress list from the audit highs. |
| `desktop/HANDOFF_DESKTOP.md` | A | 9,365 → 8,402 | `archive/2026-10-19/desktop/HANDOFF_DESKTOP.md` | Rewritten as a current handoff: what desktop is, consolidation state, audited verdicts, open queue (broadcast redaction, Vision gating, mac/win false success). |
| `desktop/HANDOFF_PROMPT.md` | B | 2,365 → 4,361 | `archive/2026-10-19/desktop/HANDOFF_PROMPT.md` | Status section (branch/PR instructions historical; gate strong; 3 highs; no fallback; tests sound) + prompt body verbatim. |
| `desktop/REVIEW.md` | B | 8,406 → 10,669 | `archive/2026-10-19/desktop/REVIEW.md` | Status section (point-in-time record; supplement audit; 0 known criticals post-merge; 3 highs qualifying the fix picture) + review body verbatim. |

### ultra-native/

| Document | Class | Old → New (bytes) | Archive | What changed |
|---|---|---|---|---|
| `ultra-native/README.md` | A | 5,099 → 7,836 | `archive/2026-10-19/ultra-native/README.md` | Full rewrite to the post-merge state of the shipping Kotlin app (packages, harness, audit verdicts, CI caveat). |
| `ultra-native/ci/README.md` | A | 688 → 1,473 | `archive/2026-10-19/ultra-native/ci/README.md` | Full rewrite; notes CI has not yet compiled the merged tree. |
| `ultra-native/ondevice-brain/README.md` | A | 5,983 → 6,371 | `archive/2026-10-19/ultra-native/ondevice-brain/README.md` | Full rewrite to audited state; runtime claims flagged as not re-verified by the static audits. |
| `ultra-native/tools/host-harness/README.md` | A | 1,508 → 2,402 | `archive/2026-10-19/ultra-native/tools/host-harness/README.md` | Full rewrite to post-merge state. |
| `ultra-native/ondevice-brain/BUILD_GUIDE.md` | A2 | 9,078 → 10,724 | `archive/2026-10-19/ultra-native/ondevice-brain/BUILD_GUIDE.md` | Targeted refresh: header + current repo state; build procedures preserved; stale state claims corrected. |
| `ultra-native/ondevice-brain/G15_TEST_GUIDE.md` | A2 | 8,501 → 9,375 | `archive/2026-10-19/ultra-native/ondevice-brain/G15_TEST_GUIDE.md` | Targeted refresh; test procedures preserved; device/runtime results marked not re-verified by the code-only audits. |
| `ultra-native/ondevice-brain/PIXEL_TEST_GUIDE.md` | A2 | 7,364 → 8,513 | `archive/2026-10-19/ultra-native/ondevice-brain/PIXEL_TEST_GUIDE.md` | Targeted refresh; procedures preserved; runtime claims flagged as not re-verified. |
| `ultra-native/ondevice-brain/RELEASE_SIGNING.md` | A2 | 3,213 → 4,181 | `archive/2026-10-19/ultra-native/ondevice-brain/RELEASE_SIGNING.md` | Targeted refresh; signing procedure preserved (audits do not cover signing — marked as such). |
| `ultra-native/tools/BRAINBENCH.md` | A2 | 4,049 → 4,964 | `archive/2026-10-19/ultra-native/tools/BRAINBENCH.md` | Targeted refresh to post-merge state; benchmark claims not re-verified by the audits. |
| `ultra-native/tools/REAL_TASKS.md` | A2 | 2,211 → 2,934 | `archive/2026-10-19/ultra-native/tools/REAL_TASKS.md` | Targeted refresh to post-merge state. |
| `ultra-native/tools/androidworld.md` | A2 | 7,558 → 8,643 | `archive/2026-10-19/ultra-native/tools/androidworld.md` | Targeted refresh to post-merge state. |
| `ultra-native/ondevice-brain/CLAUDE_CODE_BRIEF.md` | B | 5,299 → 7,040 | `archive/2026-10-19/ultra-native/ondevice-brain/CLAUDE_CODE_BRIEF.md` | Status section + original brief body verbatim. |
| `ultra-native/tools/androidworld-results.md` | B | 3,469 → 4,848 | `archive/2026-10-19/ultra-native/tools/androidworld-results.md` | Status section (results record; not re-run or re-verified by the audits) + original body verbatim. |
| `ultra-native/ondevice-brain/evidence/2026-10-06-pixel10pro-anr.md` | E | 40,157 → 41,235 | `archive/2026-10-19/ultra-native/ondevice-brain/evidence/2026-10-06-pixel10pro-anr.md` | Header + short status note only (predates the consolidation; audits were static, no on-device re-verification). Evidence body byte-identical. |
| `ultra-native/app/src/main/assets/connectors/gmail.md` | F | 1,542 → 2,281 | `archive/2026-10-19/ultra-native/app/src/main/assets/connectors/gmail.md` | Asset refreshed: the Gmail connector now exists in the harness (vault-held credentials, opaque handles, sends stop at confirmation); format preserved. |
| `ultra-native/app/src/main/assets/connectors/google_calendar.md` | F | 1,506 → 2,255 | `archive/2026-10-19/ultra-native/app/src/main/assets/connectors/google_calendar.md` | Asset refreshed to the implemented connector flow; format preserved. **Process note:** this file's archive verification initially failed and its replacement was pushed first; the archive was then re-created from the preserved original and verified byte-identical (1,506). End state verified correct; the ordering deviation is recorded here deliberately. |
| `ultra-native/app/src/main/assets/connectors/google_docs.md` | F | 1,373 → 2,118 | `archive/2026-10-19/ultra-native/app/src/main/assets/connectors/google_docs.md` | Asset refreshed to the implemented connector flow; format preserved. |
| `ultra-native/app/src/main/assets/connectors/google_drive.md` | F | 1,349 → 2,095 | `archive/2026-10-19/ultra-native/app/src/main/assets/connectors/google_drive.md` | Asset refreshed to the implemented connector flow; format preserved. |
| `ultra-native/app/src/main/assets/guide.md` | A2 (asset) | 117,508 → 128,106 | `archive/2026-10-19/ultra-native/app/src/main/assets/guide.md` | In-app copy synced to the new `GUIDE.md`; the replacement header is an HTML comment so it does not render in-app (the only intentional divergence from `GUIDE.md`). Archive verified byte-identical (blob 8426a563, same as `GUIDE.md`'s original). |

## Notes

- **Nothing was deleted.** Every replaced document's previous version is in
  `archive/2026-10-19/` at its original relative path.
- **Grounding discipline.** New versions assert only what the two audits and
  the merge log establish. Two content flags from the work: (1) the archived
  HANDOFF originals contained an embedded "scope constraint" passage that
  originated in forwarded material — it was not carried into any replacement;
  (2) on-device/runtime numbers (benchmarks, timings, test counts) from the
  old docs were consistently demoted to "not re-verified" because the audits
  were static, code-only reviews.
- **Standing caveat, repeated in every replacement:** CI has not yet compiled
  the merged tree; audit verification was grep-level, not compilation.
- This log itself is the 110th `.md` file on `main` (55 inventoried + 54
  archives + this log). A local copy is kept at
  `~/workspace/jarvis-rebuild/DOCUMENT_REPLACEMENT_2026-10-19.md`.
