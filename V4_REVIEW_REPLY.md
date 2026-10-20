> **Post-merge replacement — 2026-10-19.** This is a point-in-time document. Its body is preserved verbatim as the historical record; the status section below states what the 2026-10-19 code audits and the full-branch consolidation (all work now on `main`) changed. The previous version is preserved at `archive/2026-10-19/V4_REVIEW_REPLY.md`. CI has not yet compiled the merged tree.

## Status as of 2026-10-19

This report predates the 2026-10-19 consolidation. All work is now on `main` (tip c8043e1c); 51 of 58 branches were already contained, 2 were merged in the consolidation, and 4 remain unmerged by design; no branch was deleted.
The 2026-10-19 code audits covered two snapshots: pass 2 found 119 defects (1 critical, 16 high, 41 medium, 61 low) and the supplement found 87 findings (0 critical, 12 high, 31 medium, 44 low) — 206 findings in total, not a deduplicated count of the merged tree.
Post-merge there are 0 known criticals: the pass-2 critical (harness DB wiring) is resolved — UltraDatabase is v9, with the harness entities, DAO accessors, and MIGRATION_7_8 + MIGRATION_8_9 registered.
CI has not yet compiled the merged tree, and audit verification was grep-level (definitions/call sites/imports), not compilation or device testing.
This review records what v4 got right and its HIGH/MEDIUM/LOW findings (mutation-test proof, the paste guard, Kotlin ports that did not compile, Stop behaviour, scam rules, Gate, checkpoints, thermal).
On compile state, the position is now explicit: CI has not yet compiled the merged tree, so no compile claim about the merged tree is proven; audit verification was grep-level only.
Still open and aligned with this review's compile concerns: the Kotlin unit tests are broken — `HistoryPolicyTest.kt` / `MemoryBriefTest.kt` import `com.agent.jarvis.bench.TestKitActivity`, which exists only in `src/debug`.
Still open on Gate/claims ground this review raised: harness grant keying is the weak core; claim promotion can never supersede; and the TS live path bypasses its own SafetyChecker / PermissionBroker.
Also still open: `SchedulerReceiver` and `SpotifyRedirectActivity` exist in the harness but are not registered in AndroidManifest.xml; harness manifest wiring is classed broken.
The audits do not re-verify this review's findings about the v4 Python package itself (`mutate.py`, the paste guard, checkpoint HMAC/clock behaviour, thermal thresholds) — those specifics are not covered.

---

# Review of JARVIS v4: what's right, what's broken, what we took

From the Jarvis build session, 2026-10-09. We were not allowed to run your package's code here, so two reviewers read all of it by hand. Every bug below comes with the input that triggers it, traced by hand. Evidence labels: MEASURED / PROVEN / SOURCE ONLY / UNKNOWN.

## What v4 got right

You took the v3 review straight, and it shows:
- The killing watchdog is gone. The supervisor's cues and incident reports never cancel anything, and each fires once per run.
- Quick replies only fire when they match the whole utterance. ok/yes/no/sure are excluded.
- The policy matrix is now only a CI auditor. The Gate is checked deny-first, and argument validation fails closed when something is invalid.
- Thinker sleep, the 24/48 think budgets and the 0.880 claim are withdrawn, and honestly.
- `verify.sh` can now really fail: it uses `pipefail`, checks exit codes, and `check_case` returns its failures.
- The thread hysteresis is correct. NaN keeps the last state, and the phone is assumed cool at boot.
- The JNI callbacks now point the right way. You also named prefill and load progress as the real gaps; the C++ confirms both are missing.
- `irvq_analysis.md` gets the arithmetic right (15.36M MACs, 194×). Its verdict, "no index until the 40k probe says the scan is slow", is ours too.
- Two of your probes are genuinely new: **vision + thinker co-loaded** and **DuckDuckGo reachability from the phone**.

**What we lifted into Jarvis (v3.0.36):**
- 8 of your eval cases (s-001, s-002, p-004, e-001, i-002, a-006, a-007; e-002 skipped, since Jarvis has no factory reset tool), asserted against the real Brain.
- Your incident idea, as one "quiet" log line naming the stage that went silent. It never cancels anything.

## HIGH: these make headline claims false

1. **"10/10 mutations killed" isn't proven.** `mutate.py:71` copies only files ending `.py` or `.json`. `brain_eval.jsonl` ends in `.jsonl`, so it is never copied.
   - In each mutant copy, `run_eval.main()` hits FileNotFoundError at line 236, exits 1, and that counts as "killed".
   - Mutant #9 (bank-OTP rule removed) really survives. All three OTP tests and m-006 still score 0.0 without the rule, so the rule is never tested.
   - The ritual self-test plants no failing unit check. It never runs `verify.sh` or `main()`.
2. **The paste guard still attaches history to "summarize this: <paste>".** `memory.py:193` strips "summarize this:" *including the colon* first. Then `_strip_paste`'s colon-based regex (line 49) has no colon left to anchor on.
   - Trace: `"summarize this: " + "it is what it is "*20` → `\bit\b` matches → history attached.
   - This is the MEASURED failure itself. Your test passes only because "lorem ipsum" contains no it/that/they.
3. **The Kotlin ports don't compile, and the confirm path is dead.**
   - Memory.kt:142 uses `it.ts`, but the field is named `tsSec`.
   - Gate.kt:90-92 and 103-104 loop over Map *entries* and check them against key sets.
   - CheckpointStore.kt:93-100: after the JSON round trip, `saved_at` comes back as an Integer, so `as? Long` gives null. Nested maps come back as JSONObject, so the MAC never matches. Every ASK therefore ends in "Nothing was waiting for confirmation."
4. **Spoken Stop stops nothing in Kotlin.** Brain.kt:170-179 `requestStop(null)` only sets `state`. Python stops every watched run, so the port has drifted from it.
5. **The "Stop"/"cancel" check fires on any sentence starting with those words** (`brain.py:91`, Brain.kt:54). "cancel my 7am alarm", "stop the timer" and "quiet hours on" all answer "Stopped." and run nothing.
6. **REMAINING_WORK's "his machine" section is wrong** (third time we've said this). This repo's CI already does the NDK builds, tests, signing, 16 KB alignment checks and the Obtainium release. llama.cpp is already pinned (`build-llama-android.sh` `LLAMA_SHA`, enforced by CI). There is no `build.sh` and no `LLAMA_CPP_COMMIT`.

## MEDIUM

- **Scam watch.**
  - The bank-OTP rule allowlists the whole message, so a scam can hide behind it. "Your verification code is 482913. Never share it. Your parcel is held, pay the $2 fee: bit.ly/x" scores 0.0.
  - `\b(pay|fee|\$)`: there is no word boundary before `$`, so "$1.99 redelivery" never matches. That's the same bug class as `verif\b`.
  - `shortened_link` requires `https?://`, so your own "bit.ly/p9z" never triggers it.
  - "Adopted their families" overclaims. Jarvis combines a request signal with pressure signals, has a negation look-back, and has gift-card and odd-payment families.
- **Gate.**
  - Arguments with no provenance default to `"user"`, and model-derived arguments are never tagged, so the gate fails open.
  - `GateContext.origin` is never read.
  - The DENY-shadowing we reported wasn't fixed; it was defined away. The matrix DENY cells are gone, and `policy_audit.py:38-39` counts "ASK vs DENY" as agreement.
  - Cloud egress hardcodes `requested_action=True` / `"general"`. That makes the ASK branch dead code, and web content enters the cloud prompt without the gate seeing it.
  - Bare `code` counts as taint: "weather zip code 90210" → web_search DENY.
- **Checkpoints.**
  - Setting the clock back extends validity: `now - saved_at > 120` has no check for a negative difference.
  - The HMAC doesn't cover `gate_context`, so a disk edit of `offline_only` passes. Line 117's claim that it does is false.
  - A Keystore key can't be exported as the `ByteArray` the API takes.
  - "my password is hunter2" isn't seen as a secret, so it is saved to disk as-is.
  - Redacted placeholders are executed as real arguments after Confirm.
  - Our objection stands: with a 120 s expiry, surviving a process kill isn't worth putting pending texts on disk.
- **Thermal.**
  - Headroom above 1.0 is rejected as a glitch. A phone that boots already hot at 1.05 therefore runs cool on 4 threads, and `test_thermal.py:48` locks this in.
  - There is no OS status floor (MODERATE→HOT, SEVERE→CRITICAL).
  - The hot→tier-B downgrade has no hysteresis, so it flaps at 0.87. It also refuses the cloud, which is the thermally cheap path.
  - "Adopted verbatim" overclaims. Jarvis's thresholds go 4/3/2/2 threads, and nothing changes at 0.95.
- **Prices** (`search/extract.py`). These are correct: `$1,299`, `1.299,99 €`, `$299.99.`, `€10 $20`, `379,99 €`, `£5`, `10 $20`, `US$799`, `EUR 379.99`. These are broken:

  | Input | Result |
  |---|---|
  | `1.299 €` | 1.299 EUR (the European thousands case you claim) |
  | `379.99 EUR` / `49.99 USD` | dropped (a code after the number is never matched) |
  | `C$799` | USD |
  | `$1.5 billion` | 1 USD |
  | `1.299.000 €` | dropped |

  The year tag takes the newest year on the page, so a "© 2026" footer marks old prices as current.
- **Never-silent claims.**
  - `_maybe_cue` consumes the cue and only audits it; nothing returns it to the user.
  - Eval s-003 passes on a cue logged after the answer was ready.
  - Stop doesn't abort an in-flight `requests` call; it runs out its 15–20 s timeout.
  - There's no Stop check after `cloud_answer` returns.
- **Kotlin threading.** `tick()` runs on its own timer thread, but it shares a plain HashMap and unsynchronised `stopped`/`state` with everything else. It is also all blocking, while Jarvis's `Brain.run` is a suspend function.
- **MIGRATION / BUILD_ORDER would replace working Jarvis code with weaker code.**
  - Keyword `classify()` in front of `IntentRouter`/`WaitBudget`.
  - Null budgets meaning "no thinking", which goes against the owner's top priority.
  - A second ThermalPolicy.
  - The rejected tiny-model tool driver (`tinyToolDecision`).
  - Hard-deny on typed secrets: the owner decided on 2026-10-09 that those may reach the cloud.
  - The tool names don't match Jarvis (`flashlight` vs `flashlight_toggle`, `send_sms` vs `sms_send`…).
- **Tags.** `v4.0.<run>-test` would still ship versionName 3.0.x, and the release cleanup only covers v2.6/v3.0. Jarvis releases are `v3.0.<run>-test`.

## LOW

- **Wake word.** The exact-substring path is also gated on `right_after_wake`. The docstring says 0.75 but the code uses 0.70. `name="Jarvis"` (capitalised) never matches. "Wake trigger" is a bool the caller passes in, with no time window.
- **Queue.** The `dropped` list is thrown away, `Brain.queue` is unused, and a second "after …" overwrites the first.
- **Manifest mismatch.** The default `on_mismatch` does nothing, so a mismatch is silent, not a warning.
- **Supervisor.** `incidents`/`cues` grow without bound in a long-running service.
- **Tests.** "292 unit checks" includes tautologies: `test_gate.py:138`, `test_brain.py:146` (`or True`), `test_brain.py:221`, `test_ritual_selftest.py:63`. Eval c-003 asserts nothing about the outcome.
- **Doc drift.**
  - `calibrate()` doesn't exist.
  - The docs claim "only stdlib imports", but `org.json` is imported.
  - The diagrams label cloud answers `[UNTRUSTED DATA]`, but the code doesn't.
  - CLAUDE_BUILD says checkpoints are "Room-backed", but they are File/JSON.
- **JNI_SEAMS.** `setThreads`/`setThreadAffinity` aren't needed: threads and CPU affinity are already passed at `nativeLoad`/`nativeGenerate`. q8_0 KV, flash attention and n_ubatch 512 already exist.

## Numbers with the wrong label

- "~3.4 tok/s hot — MEASURED": the 3.4 is the top of a 6-thread benchmark range, not a measurement taken hot.
- "160 think tokens for 'hi' = ~27 s": derived, not measured.
- "GPU/NPU MEASURED unusable": the DEVLOG says OpenCL and Vulkan *load*. Edge TPU rejected the *wake* models. "Adreno-only" isn't anywhere in the DEVLOG.
- "Thresholds MEASURED": Jarvis's own comment says they were reasoned from one idle reading.
- "Slow ASF" / "wasn't answering" are attributed to the owner, but neither quote appears in the DEVLOG or HANDOFF.
- These are correctly sourced: 8.96/7.23/4.79, the CPU clocks, the 745-token / ~25 s first prefill, 129 drops, 6.3 vs 9.3 tok/s, and 9.7 GB > 8.6 GB.

## Where this leaves things

There is no design work left for a v5. Jarvis already has the working version of almost everything in v4, and has had it since v3.0.35/36. The useful next contributions would be:
1. A **vision + thinker co-load probe** and a **DuckDuckGo-from-the-phone probe**, as Jarvis Tests cases with pass/fail thresholds.
2. **Prefill/load progress callbacks** for `ultra_llm.cpp`: `llama_decode` split into chunks with a callback, and a `progress_callback` in `nativeLoad`. These are the real JNI gaps.
3. **Fixes to the two Jarvis edges your cases found:** SMS recipient "me" counting as traceable (Contracts.kt:68-90), and tool-output secrets shorter than 8 characters going untracked (Origins.kt:234). Any fix must respect the owner's decision that typed secrets may go to the cloud.

Please run your own ritual against an unmutated copy first, so a FileNotFoundError can't count as a kill.
