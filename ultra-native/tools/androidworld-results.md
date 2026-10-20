> **Post-merge replacement — 2026-10-19.** This is a point-in-time document. Its body is preserved verbatim as the historical record; the status section below states what the 2026-10-19 code audits and the full-branch consolidation (all work now on `main`) changed. The previous version is preserved at `archive/2026-10-19/ultra-native/tools/androidworld-results.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

## Status as of 2026-10-19

- These are scored benchmark rounds from September 2026, run on an emulator with cloud models behind a laptop proxy (the body says so itself). They are preserved as the historical record and were **not re-run or re-verified in the 2026-10-19 audits**, which were static, grep-level reading of the code; CI has not yet compiled the merged tree.
- The 2026-10-19 consolidation (all work now on `main`) does not change any score below. The `bench/` and tools code carries the merged-tree audit verdict "works with defects", and the Gate the rounds exercised was audited as deny-by-default in the merged tree — but neither fact re-measures a single round here.
- Treat the scores as evidence about those runs, on those seeds, with those brains — not as a current benchmark result for the merged app, and not as evidence about the on-device model, which the emulator setup never ran.

---

# AndroidWorld: routes — every scored round

Scored by AndroidWorld's own checks of the device, not by Ultra. For each experiment the prediction of an
informed skeptic was written down before the run; the result is what came back.

**Design.** Teacher: a stronger model (claude-sonnet-5) does each task through Ultra on practice seeds
(30, 32). Only runs the benchmark passes become routes. Student: llama-3.3-70b on a seed the practice
never used, clean slate, once without routes and once with. No weights change. A route is played by
Ultra's engine with the values taken from the request; every played step still goes through the action
gate and the typing check, and the model takes over where a step doesn't fit the screen.

| Experiment | Skeptic's prediction | Without routes | With routes |
|---|---|---|---|
| P8 — 6 tasks, seed 31, first player build | 2/6, 3/6 at the outside | 1/6 | 3/6 |
| P9 — 6 tasks, seed 31, routes per kind | 3/6 again | 1/6 | **4/6** |
| P10 — 6 tasks, seed 33, replication, no new practice | one lucky round: 3/6 | 1/6 | **4/6** |
| P11 — 33 tasks chosen by rule, seed 31 | +2 to +4 of 33 | 8/33 | **13/33** |

The bar for P8–P10 was +3 of 6: met in P9 and P10. The bar for P11 was +8 of 33: **not met** (+5).
In P11 the student with routes scores what the teacher scores without them (13/33 and 14/33).

**The 33 tasks (P11), fixed by rule before any result:** every `android_world` task with complexity ≤ 1.2
that is not question-answering (the adapter can't return an answer yet) and is not a "…Verify" twin.

**Honest limits.**
- P10's with-routes arm was run twice: the emulator hung after 21 hours up, was restarted, and the arm
  was re-run from its start. The 4/6 is the complete second run.
- P11 lost a task it passes alone: a 37-step route built from a flailing teacher run was played over a
  model that didn't need it. Only clean runs are played now. That change has not been re-measured.
- In the benchmark the harness answers Ultra's action gate as the person who gave the task would —
  "Do it" only when the button is the verb the task used, or the app's own confirm box is about it.
  Every answer is recorded in the episode (`ultra_gate_answers`). The gate itself is unchanged.
- Clock-task scores from 2026-09-19 were judged by a reader that returned a blank screen. They are not
  evidence of anything; see "The judge can be blind" in `androidworld.md`.
- The emulator has no on-device model (x86_64 build); both brains here are cloud models behind a laptop proxy.

## Rounds

| round | seed | score | run directory |
|---|---|---|---|
| p8-teach1 | 30 | 3/6 | `run_20260920T114511636331` |
| p8-teach2 | 32 | 3/6 | `run_20260920T115652655546` |
| p8-student-control | 31 | 1/6 | `run_20260920T121446572009` |
| p8-student-routes | 31 | 3/6 | `run_20260920T123032418949` |
| p9-teach1 | 30 | 4/6 | `run_20260920T124721019138` |
| p9-teach2 | 32 | 3/6 | `run_20260920T130111015600` |
| p9-student-control | 31 | 1/6 | `run_20260920T131830850900` |
| p9-student-routes | 31 | 4/6 | `run_20260920T133228670367` |
| p10-student-control | 33 | 1/6 | `run_20260920T134559029462` |
| p10-student-routes-rerun | 33 | 4/6 | `run_20260920T143125488712` |
| p11-teach1 | 30 | 13/33 | `run_20260920T150929838153` |
| p11-teach2 | 32 | 14/33 | `run_20260920T160502830280` |
| p11-student-control | 31 | 8/33 | `run_20260920T170811992260` |
| p11-student-routes | 31 | 13/33 | `run_20260920T183753816406` |
