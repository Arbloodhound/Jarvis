> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/jarvis-native/tools/REAL_TASKS.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# 20 real tasks — phone checklist

Type or say each one with the cloud brain **off** (the default). Mark it **Pass** only if the phone
actually did the thing, not just if Jarvis said it did. Pass rate = passes ÷ 20.

The app-side routing for all 20 is checked by `RealTasksTest` (20/20 on the right path when this
sheet was written; that result was not re-verified in the 2026-10-19 audits, which were static,
grep-level reading; it was re-run 2026-10-20 after the merge and passes. `HistoryPolicyTest.kt` and
`MemoryBriefTest.kt` also compile and pass under `testDebugUnitTest`). This sheet covers what that test can't:
whether the model picks the right tool and the tool works on the phone. On-device behaviour
itself was not re-verified in the 2026-10-19 audits. The merged tree compiles (all 1644 JVM tests pass, 2026-10-20).

| # | Say / type | Path | Pass when… | Pass? |
|---|---|---|---|---|
| 1 | turn on the flashlight | instant | light comes on | |
| 2 | what's my battery level | instant | right % shown | |
| 3 | set the volume to 30 percent | instant | volume moves to ~30% | |
| 4 | turn off wifi | instant | Wi-Fi panel/off | |
| 5 | call mom | instant + Confirm | Confirm card, then it dials Mom | |
| 6 | text Sam I'm running late | tool + Confirm | Confirm card, then the text is sent to Sam | |
| 7 | set an alarm for 7am | tool | alarm at 7:00 in Clock | |
| 8 | make a note: buy milk | tool | note saved | |
| 9 | open spotify | tool | Spotify opens | |
| 10 | search the web for the weather in Miami | tool | real results (not made up) | |
| 11 | what's on my screen | tool | describes the screen correctly | |
| 12 | open settings and turn on dark mode | screen driver | Settings opens and dark mode turns on | |
| 13 | open youtube and search for cat videos | screen driver | YouTube shows cat video results | |
| 14 | call mom and then text Sam I'm on my way | chain + Confirm | Confirm call → after OK, it goes on to the text | |
| 15 | take a screenshot | instant | screenshot saved | |
| 16 | go on google and search for pizza near me | screen driver | browser shows pizza results | |
| 17 | what's the capital of France | answer | "Paris" | |
| 18 | write me a short poem about the sea | answer | a short poem | |
| 19 | hang up (during a call) | instant | call ends | |
| 20 | turn on do not disturb | instant | DND on | |

**Path:** instant = no model run (should take about a second). Tool = the model picks one tool.
Screen driver = the model taps through the app. Answer = a text reply.

For any **Fail**, tap DIAG right after it and send the file. It shows which step went wrong.
