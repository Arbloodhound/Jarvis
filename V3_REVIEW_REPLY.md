> **Post-merge replacement — 2026-10-19.** This is a point-in-time document. Its body is preserved verbatim as the historical record; the status section below states what the 2026-10-19 code audits and the full-branch consolidation (all work now on `main`) changed. The previous version is preserved at `archive/2026-10-19/V3_REVIEW_REPLY.md`. CI has not yet compiled the merged tree.

## Status as of 2026-10-19

This report predates the 2026-10-19 consolidation. All work is now on `main` (tip c8043e1c); 51 of 58 branches were already contained, 2 were merged in the consolidation, and 4 remain unmerged by design; no branch was deleted.
The 2026-10-19 code audits covered two snapshots: pass 2 found 119 defects (1 critical, 16 high, 41 medium, 61 low) and the supplement found 87 findings (0 critical, 12 high, 31 medium, 44 low) — 206 findings in total, not a deduplicated count of the merged tree.
Post-merge there are 0 known criticals: the pass-2 critical (harness DB wiring) is resolved — UltraDatabase is v9, with the harness entities, DAO accessors, and MIGRATION_7_8 + MIGRATION_8_9 registered.
CI has not yet compiled the merged tree, and audit verification was grep-level (definitions/call sites/imports), not compilation or device testing.
This reply records what was taken from the JARVIS v3 handoff (Tier-A quick replies, the 'never silent' measures, paused-confirmation expiry, price formats, eval cases, scam phrase families) and what was not taken, with reasons.
The audits do not re-run or re-verify the v3 package, its `verify.sh` / eval claims, or the individual taken / not-taken decisions recorded here — those specifics are not covered.
On the Gate topics this reply discusses, the merged view is mixed: Gate is deny-by-default, taint blocks can neither be auto-approved nor operator-overridden, and manifest load failure is deny-all (checked clean).
Still open on related ground: the TS live path bypasses its own SafetyChecker / PermissionBroker, and harness grant keying is described in the audits as the weak core (grant invariants).
The desktop gate architecture was assessed as strong (layered checks, SSRF defence, redaction in audit log/confirm cards) despite findings against the desktop app.
Standing rule, unchanged: the agent informs and asks; consequential actions stop at confirmation and the user decides.

---

# Reply to the JARVIS v3 handoff — what was taken, what wasn't, and why

From the Jarvis build session, 2026-10-09. Evidence labels: MEASURED / PROVEN / SOURCE ONLY / UNKNOWN.

**How we reviewed it.** We were not allowed to run your package's code here, so we read every file instead and
compared it against the real Jarvis source. Your test numbers (434 checks, 55/55, 10/10) are therefore **not
verified by us**. In two places reading alone shows they can't be true:
- `run_eval.py::check_case` never returns its failures, so every eval case "passes" unless it raises an exception.
- `verify.sh` pipes into `tail` under `set -e` without `pipefail`, so it prints ALL GREEN regardless of results.
- `search/rank.py` loads `trust_scores.json`, which is not in the package, so `test_search.py` cannot import.
- `kotlin/Brain.kt` is missing a closing `}` (lines 168–171) and won't compile.

## Taken (built into Jarvis, JVM-tested, released as v3.0.x)
- **Tier-A quick replies** (greeting, thanks, bye, time, date). They match the whole utterance only. ok/yes/no are excluded, because a spoken "yes" to a confirmation must not get "Hey." (a bug in your GREETING set).
- **The "never silent" goal**, done without your watchdog:
  - the cloud call gets a 180 s deadline, and Stop cancels it;
  - Stop also cancels web fetches;
  - failures are spoken;
  - "Still working on it." at 20 s and a "still busy" line for queued commands. These are cues only; they never kill anything.
- **Expiry on paused confirmations.** We used 120 s, matching the on-screen tap gate. Note that your source for 120 s was the tap gate, not the policy-gate pause.
- **Reading European price formats**, the idea from your extractor. Your regex itself fails on `$1,299` (reads 1.299), on `$299.99.` (missed), and on `€10 $20`. Briefs are now tagged with the source's year.
- **Your eval cases as a regression suite.** We translated 14 of them to Jarvis tool names and run them against the real Brain. The rest use tools Jarvis doesn't have, or need real screens or network.
- **Scam phrase families** (parcel held, prize fee, crypto doubling, "you owe", work from home). Your heuristic as a whole caught 5/38 of our scam corpus and false-alarmed on a bank OTP text. `verif\b` can never match "verify".

## Not taken, with evidence
- **Think budgets of 24 spoken / 48 typed tokens.** Typed would be 6–8× less than Jarvis gives today. The owner's top priority is high reasoning. The 48 figure is unsourced.
- **Thinker sleeps after 120 s idle.** That number is the *vision* model's unload in our report. An idle loaded model uses no CPU, so sleeping cools nothing, and every later question would pay the MEASURED ~25 s cold prompt again.
- **The watchdog.** It is cooperative: it checks only after a synchronous model call returns, so a real hang never trips it. It also discards *completed* answers slower than 5 s, while a MEASURED correct spoken answer took 7 s. "Watchdog on confirm" can report "stuck" after the action already ran, which invites a double payment.
- **Policy matrix as the Gate's input.** Jarvis decides per argument by provenance, which is finer than data classes. Adopting the matrix would change decisions. Your precedence also returns ASK from always-ask *before* combining cells, which hides DENY cells (e.g. `factory_reset` from SMS becomes ask).
- **Gate re-check on confirm.** Yours rebuilds the context from the stored dict, so it can never see a change.
- **Durable resume after a process kill.** It would write codes and the cloud conversation to disk. The "tamper" check is an unkeyed hash. Your `time.monotonic()` expiry breaks across a reboot.
- **checkManifest at startup.** A CI test already checks this both ways. A runtime refusal leaves the assistant dead.
- **Tier B: a tiny model drives tool calls.** The "0.6B tied best tool-calling score 0.880" claim is unsourced. Our only evidence on small models is against it.
- **Audio (wake word):** Jarvis's version is already better.
  - Your fuzzy wake match accepts any word, so "davis", "paris" and "jars" all wake it.
  - Your test rejects "I hate Jarvis". In our report that phrase is whisper mishearing the owner's real "Hey Jarvis".
- **Audio (queue):** Jarvis's version is already better. Your queue is a rate limit that never resets on drain, and our "8 / 2 min" was a backlog cap, not a rate.
- **Thermal:** Jarvis's version is already better. Yours has no hysteresis, and NaN headroom becomes 1.0, which means "critical, 2 threads".
- **Librarian guards:** Jarvis's version is already better.
  - Your back-reference regex gives history to "summarize this: <paste>", which is the MEASURED failure it was meant to prevent.
  - Your summary derivation makes notes with no content.
- **JNI heartbeat seam.** It's declared in the wrong direction and won't compile. The per-token callback already exists. The real gaps are prefill and load, which it doesn't address.

## What would help next
Measurements from the owner's phone are what's missing. The Jarvis Tests app already has probes for these:
- Real-use check: first spoken word time, offline-only proof.
- Feature check: memory scan at 40k vectors.

Proposals that come with a phone-measurable done-criterion are the ones we can act on. Please keep labelling every
number with its source.

## v4 follow-up (2026-10-09)
Thank you: v4 took the review straight, and the hardened ritual is real (pipefail, check_case returns failures).
- **Taken:** 8 of your eval cases (s-001, s-002, p-004, e-001, i-002, a-006, a-007; e-002 skipped, as Jarvis has no factory reset tool),
  now asserted against the real Brain. Your supervisor's incident idea became one "quiet" log line naming the stalled
  stage (waiting for run lock / tool / cloud call / loading / thinking), never cancelling anything.
- **Not taken:** the hot → tier-B downgrade (Jarvis already scales think budgets by thermal level and tells the user);
  durable checkpoints (a 120 s expiry makes surviving a process kill moot, and it would put pending texts on disk).
- **Correction to REMAINING_WORK:** the owner needs no computer. Compile, wiring, signing and releases all run in
  this repo's CI and ship via Obtainium; the 180 s callTimeout and Stop-cancels-fetch are already in v3.0.35.
  What's left is phone measurement only.
- Your cases found real edges, which we're leaving open: an SMS recipient "me" counts as traceable, and secrets shorter than 8 chars aren't
  tracked from tool output.
