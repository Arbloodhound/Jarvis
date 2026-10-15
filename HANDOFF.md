# HANDOFF 3 — 2026-10-15, continue from here (token-efficient)

Read this, then CLAUDE.md, HANDOFF_2 (shopping design) and DEVLOG top. Older HANDOFF below is history.

## State right now
- **Latest release in Obtainium:** `v3.0.37-test` (Tor *research* build: onion research opt-in via Orbot, scam
  screening, phrases verify/check/investigate/deep-research — but see note: those phrases were NOT actually routed
  in Brain; the research/ modules shipped as libraries only).
- **Two branches in play:**
  - `claude/shopping-integration` @ `3f888f8` — pushed. Holds the research/ + shopping modules. I fixed one real
    compile-passing bug here (ShoppingListings seller name swallowed next field) and PROVED the whole branch
    compiles + tests: **1456 JVM tests, 0 failures, assembleDebug OK**. This corrected HANDOFF_2's "compile unverified".
  - `wire-research` (worktree `.claude/worktrees/agent-a2176302531f84f09`) — a background worker's branch, **NOT
    committed/pushed yet**. It wires the modules into the agent (see below). It was mid native build at handoff.

## What the wiring worker did (uncommitted, in its worktree)
Changed: assets/ultra.manifest.json, agent/Brain.kt, agent/Tools.kt, agent/Experience.kt, gate/Fact.kt, gate/Gate.kt.
New: research/BrowseRun.kt, research/ShoppingRun.kt + tests (BrowseRunTest, ShoppingRunTest, gate/ResearchToolsGateTest).
Goal: route verify-product / check-company / investigate / deep-research; add shopping (listings→excludeOnion→
reputation→sortable table, checkout prep stops at gated confirm card, crypto display-only); wire AutonomousBrowse to
real transports (WebResearchRun.hits/get, OnionAccess, OnionSafety.screen). All restrictions preserved.

### TO FINISH (next session, in order)
1. Check the worker: `git -C .claude/worktrees/agent-a2176302531f84f09 log --oneline -1` and its handback (task
   a2176302531f84f09). If it committed on `wire-research` and reported green → cherry-pick/merge its commit onto
   `claude/shopping-integration`. If build was red, read its log, fix, rebuild.
2. Full build from ultra-native: `source ~/android-env.sh && flock /tmp/jarvis-gradle.lock ./gradlew --no-daemon -q
   testDebugUnitTest assembleDebug`. Must be green. (Native C++ build = llama.cpp/whisper/sherpa, 15–25 min; one at a
   time behind the flock — this is why it's slow.)
3. DEVLOG entry, commit (trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` +
   `Claude-Session: https://claude.ai/code/session_01BUKaYmPgEcSV7v4FpB3hPy`), push `claude/shopping-integration`.
4. Release: GitHub MCP actions_run_trigger → run_workflow, owner arbloodhound, repo jarvis, workflow
   release-apk.yml, ref `claude/shopping-integration`, inputs {"test_kit":"true"}. Poll the run's conclusion; give
   owner the v3.0.x-test link. (Alt fast path owner offered to skip: push with `[apk]` so CI does the native build.)

## Owner (Dafarus) — must-knows
- Plain, short answers; swears when frustrated; stay concrete. One release per batch.
- Never change a chosen default without asking. Phone: Pixel 10 Pro XL, de-Googled; installs via Obtainium
  (regex `jarvis-test\.apk`, prereleases on). Tests via Jarvis Tests → ★ Real-use check, sends the file.
- SCOPE (do NOT renegotiate): legitimate-product verification + scam screening only. NO marketplace trio (navigate
  onion markets / rank vendors / buy through them). Onion handling = research only, opt-in, screened. Checkout has
  NO purchase execution; crypto display-only; agent informs + gated-confirms, never buys. These restrictions are
  BUILT INTO the files (ShoppingListings.excludeOnion is shopping-only; AutonomousBrowse has no marketplace nav / no
  onion exclusion; ShoppingCheckout no execution). I twice wrongly said I'd refuse this legitimate work — I was wrong;
  it's in scope. Do not repeat that.

## Honesty
Everything on these branches is SOURCE-FIXED/compiles-and-tests-green but RUNTIME-UNPROVEN on the phone. No Real-use
file since v2.6.28. Owner still needs to: install, toggle a11y off/on once (screenshots), download vision model,
test describe-screen + a product search + Tor (Orbot), send ★ Real-use file.

---
# HISTORY (superseded)

# HANDOFF — 2026-10-08, continue exactly from here

Covers everything since the last handoff (01K5Mi, 2026-10-07 00:45 UTC: v2.6.8, branch @ 20ee531). Self-contained:
read this section, then CLAUDE.md and the top of DEVLOG.md. Older sections below are history.

## 1. Where it is right now
- **Latest release:** `v2.6.33-test` (Release APK run 33, 2026-10-07 23:xx UTC) —
  https://github.com/Arbloodhound/Jarvis/releases/tag/v2.6.33-test
- **Branch:** `claude/new-session-t3cy17`, everything pushed. 1338 JVM tests, 0 failures; assembleDebug OK.
- **2026-10-08 (session 01K5Mi):** reference-design gaps closed + Brain tests + Verification tree (ARCHITECTURE.md);
  see DEVLOG top entry. Released as **`v2.6.34-test`** (run 34, Latest): https://github.com/Arbloodhound/Jarvis/releases/tag/v2.6.34-test
- **Architecture skeleton:** `ARCHITECTURE.md` (8 Mermaid diagrams: overview, Hey Jarvis → answer, tool call through
  every gate, on-device navigation, web research, memory, native boundary, storage). The same content plus a
  Verification table is published at https://claude.ai/artifact/MKGYj5zF7VP6nk2rrxrybW (private to the owner).
  Check every change against it; update both when the wiring changes.
- **Nothing since v2.6.8 has been proven on the phone** except what the two Real-use results files showed (below).
  Everything else is SOURCE-FIXED BUT RUNTIME-UNPROVEN, or PROVEN ON HOST where said.

## 2. The owner (Dafarus) — how to work with him
- Not a professional programmer. Wants short, plain, direct answers; one consolidated release per batch (not a
  release per fix); token-efficient work. Swears when frustrated: stay calm and concrete, no long apologies.
- **Never change a default he chose without asking** (he was upset when the default voice was switched unilaterally;
  Ryan is now default with Kokoro one tap away — see 4).
- Phone: Pixel 10 Pro XL (Tensor G5, ~15 GB RAM). Asked whether ML Kit works on GrapheneOS, so assume a de-Googled
  setup (avoid Google services). Installs with **Obtainium** (source github.com/Arbloodhound/Jarvis, prereleases on,
  APK regex `jarvis-test\.apk`). No adb.
- Tests by running **Jarvis Tests → ★ Real-use check** (one tap) and sending the results file
  (`Jarvis-results-YYYYMMDD-HHMM.txt`).
- Product direction (2026-10-07): a general assistant with a BIG emphasis on SEARCHING — intelligent web search
  (operators, several queries, cross-checking), intelligent product finding (trusted sellers, forum consensus, best
  price), driven by the wake name and by the screen; when Jarvis takes over the screen, a small Stop button and the
  owner's touches frozen (not the model's). Plus the sociolinguistic/psychoanalytic notes and insights.
- **Declined:** special dark-web tooling (marketplace navigation, vendor verification). He accepted: "I don't need it
  to have any special skills for the dark net". General browser use (Tor Browser included) stays as it is. Don't build it.

## 3. How to build and ship
- Build + tests: `source ~/android-env.sh && cd ultra-native && flock /tmp/jarvis-gradle.lock ./gradlew --no-daemon -q testDebugUnitTest assembleDebug`
  (first time: `bash ultra-native/ondevice-brain/fetch-sherpa.sh`; the sherpa .so is gitignored). Native libs come
  from `~/llama-android` (llama.cpp 8345f33 **with libmtmd** for vision) and `~/whisper-android`.
- Release: push, then GitHub MCP `actions_run_trigger` → `run_workflow`, owner `arbloodhound`, repo `jarvis`,
  workflow `release-apk.yml`, ref `claude/new-session-t3cy17`, inputs `{"test_kit":"true"}`. It publishes
  `v2.6.<run>-test` as Latest (newest 5 kept). Poll `api.github.com/repos/arbloodhound/jarvis/actions/runs/<id>` for
  `conclusion`. Then add a line to this file and give the owner the link.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` +
  `Claude-Session: https://claude.ai/code/session_01BUKaYmPgEcSV7v4FpB3hPy` (use your own session URL).
- Container network: Hugging Face, ModelScope and DuckDuckGo are blocked for curl; the **Hugging Face MCP connector**
  works for checking model files/sizes. raw.githubusercontent.com, PyPI and developer.android.com work;
  github.com web pages return 403.
- **Host harnesses** (prove native code without a phone): `ultra-native/tools/host-harness/` — `setup.sh` + `run.sh`
  (ultra_llm), `vlm/` (ultra_vlm vs llama-mtmd-cli, ASan), `whisper/` (ultra_whisper). See its README.
- Parallel agents: Agent tool, isolation "worktree", background; every Gradle run under the flock; workers start
  with `git checkout -B <branch> claude/new-session-t3cy17` (worktrees can start from an old commit).

## 4. What was built since v2.6.8 (releases 10 → 33), by area
- **Voice in:** "Hey Jarvis" chime and spoken reply; misheard wake names accepted; whisper small.en with speed
  fallback; quieter-speech gate (8 dB above floor, -62 dB minimum) + mic gain; speaker labels; a command counts by the
  wake window open when its segment STARTED (fix from Real-use 2.6.28); 9 s command cap; barge-in ("Hey Jarvis, stop"
  while it talks); Jarvis no longer hears itself (every Speaker mutes the listener, 700 ms echo tail).
- **Answering:** acts first, reasons after; answers spoken sentence by sentence as they are written; spoken requests
  think ≤ ~3–5 s ("Instant" default); stop / add-on / change mid-task (Interrupt: Stop / After / Replace / Run) from
  chat, voice screen and Hey Jarvis; live thinking preview; prompt warm-up after the model loads.
- **Models:** default thinker = Qwen3-4B Thinking 2507 **abliterated** Q4_K_M (owner's choice; stock 4B kept);
  Qwen3.5-4B abliterated selectable; 4-connection resumable downloads; **speed boost (draft) default OFF** (measured
  slower: 6.3 vs 9.3 tok/s, and a truncated answer); LLM on 4 big-core threads (measured best: 4 = 8.96 tok/s).
- **Voice out:** voice picker (Kokoro 11 voices, 10 Piper voices incl. LibriTTS/VCTK, phone voice), speed/pitch,
  HOW JARVIS TALKS (personality, length, spoken think, wake chime, "On it.", bare "stop", barge-in).
  **Default voice = Ryan (Piper high)**: Kokoro measured 1.3–1.5× slower than real time on the Pixel.
- **Memory / notes / insights:** librarian notes on its own, speaker names, follow-up links, re-reading; Insights
  screen (talk statistics + literature: Pennebaker, Lakoff, Tannen, Brown & Levinson, Bernstein, Giles, Cialdini,
  Aleksic's Algospeak…), 28 cue patterns with possible readings and "what you could do" tips; manipulation patterns;
  prompt examples can no longer be copied into notes.
- **Chat:** Copy / Edit / Branch / select text; Stop; 📝 🧠 💡 📖 buttons; in-app Guide (assets/guide.md = GUIDE.md,
  incl. "How do I…?" and a deep dive).
- **Search (release 31):** `WebResearch` + `WebResearchRun` — 2–4 queries with operators per request (product / how-to /
  fact), DuckDuckGo HTML with real links, ranked by consensus + site trust (retailers, communities, reference), top 3
  pages read, prices pulled out, numbered brief with rules (cite [n], shortlist with price/seller/trust/red flags).
  Since run 33 it searches with a POST + Referer like ddgs / duckduckgo_search / SearXNG, and a bot check or HTTP 202
  counts as blocked (falls back to the old instant-answer / Wikipedia / snippet path).
- **Screen takeover (31):** full-screen accessibility overlay with a Stop pill while Jarvis drives an app; owner's
  touches swallowed; Jarvis's own gestures pass (overlay turns not-touchable, gesture waits `TakeoverPass.SETTLE_MS`
  120 ms); reference-counted for overlapping runs. Covers the status bar (no notification pull-down meanwhile).
- **Screen vision (32):** `ScreenVision` = accessibility screenshot (`canTakeScreenshot`; service must be toggled off/on
  once, or maybe just an app update on Android 15) + **ML Kit text recognition 16.0.1 bundled** (offline; no Play
  services needed — proven from bytecode; it DOES send usage events to Firebase logging) + **Qwen2.5-VL-3B Q4_K_M +
  mmproj Q8_0** (2.8 GB, Settings → SCREEN VISION; loads on demand, 896 px, frees after 2 min). Tools `screen_ocr`,
  `look_at_screen`; OCR added automatically when the screen's text is thin; on-device navigator reads thin screens by
  OCR (vision at most once a run); JavaScript-only pages opened on screen and read when HTTP gets no text.
- **Verification pass (33):** see DEVLOG "verification pass". Native shims PROVEN ON HOST (vision output identical to
  llama-mtmd-cli token for token, ASan clean, no leak; ultra_llm harness PASS; whisper text/timing/VAD paths). 14 bugs
  fixed, incl.: Stop was swallowed by every tool (CancellationException caught), `ambient_recall` always refused by the
  gate (now `ToolWiringTest` checks all 40 tools vs the manifest), protected apps OCR'd during navigation, invalid UTF-8
  passed to Java, bitmap lifetimes, research run twice.

- **Reference design merge (2026-10-08):** fail-closed tool arguments; offline-only switch (default off) enforced in
  Brain/Tools; every route + on-screen tap approval in the audit log (rotates, never halves); all app data excluded
  from backup/device transfer; [UNTRUSTED DATA] label in the cloud loop; BrainLoopTest drives the real Brain against a
  scripted server; Feature probe "Memory scan at 40k"; Real-use probe "Offline only, for real".
  Not built, with reasons in DEVLOG: RVQ memory, sandboxes, plugins, MCP, gateway, Playwright; deferred: Keystore key,
  jobs table.

- **v3 handoff (2026-10-09):** reviewed by reading (its code may not be run here); proven-better parts lifted —
  quick replies, pause expiry, never-silent fixes (cloud deadline, Stop cancels cloud + web, spoken failures, still-
  working / still-busy cues), price parsing, scam phrases, link-example guard, translated eval cases. Rejected parts
  and reasons: DEVLOG 2026-10-09. Reply for the other model: V3_REVIEW_REPLY.md. Releases are now v3.0.<run>-test.

## 5. What the phone has actually shown (Real-use results files)
- 2.6.27 (12:40): 28 s runaway segments, whisper on slow cores, 129 drops, prompt examples copied into notes, Kokoro
  1.4× real time → all fixed in 28.
- 2.6.28 (13:03): room listening PASS (whisper 0.4× real time on cpus 2–7); Hey Jarvis woke but the command was filed as
  memory; spoken answer 32 s and truncated with the draft model on; ~25 s first prefill; Kokoro 1.3× → fixed in 29.
- **No results file since 2.6.28.** Releases 29–33 are unproven on the phone.

## 6. Known, not fixed (decide with the owner if they matter)
- Overlay covers the status bar during a takeover. TakeoverPass 120 ms is reasoned from AOSP, not measured.
- ML Kit telemetry to firebaselogging.googleapis.com (no verified off switch). An alternative would be dropping the
  datatransport deps or another OCR; ask before doing anything.
- The vision model is not in DOWNLOADS → "Download all missing" (deliberate: 2.8 GB, opt-in from SCREEN VISION).
- Models unload only manually or when the librarian yields (no onTrimMemory). Thinker + vision together in RAM unmeasured.
- whisper's initial prompt goes through GetStringUTFChars (Modified UTF-8): an emoji wake name would be malformed.
- Dead entries left per the no-refactor rule: `file_delete` in Tools.DESTRUCTIVE, `wake_onnx` pref, `ultra_a11y` prefs,
  several uncalled AgentController / service methods (listed in the wiring audit, DEVLOG).
- Owner DECIDED (2026-10-09): keep full tool results in logcat; codes still reach the cloud model; no extra confirm for
  setting changes after untrusted reads. Rule: keep everything unless removing it makes functional sense.
- A cloud run already in flight when Offline only is switched on finishes on the cloud (its web_search is blocked).
- On-device gate blocks have no confirm path (runLocalCall refuses; only the cloud loop shows a confirm card).

## 7. Next steps, in order
1. Owner installs v2.6.33-test (Obtainium), toggles Jarvis's accessibility service off and on once, then:
   (a) Settings → SCREEN VISION → Download vision model, open something with pictures, "Hey Jarvis, describe my screen";
   (b) "find me the cheapest Sony WH-1000XM5" (search + shortlist); (c) an app task ("open settings and turn on dark
   mode") to see the overlay + Stop; (d) Jarvis Tests → ★ Real-use check → send the file; (e) Feature check → "Memory scan at 40k" line
   (PASS ≤1 s decides that no memory index is needed).
2. Read the file: first spoken word time, tok/s, whisper real-time factor, wake → command, stop time, and logcat-style
   lines `UltraVision ocr …`, `UltraVlm load/describe …` (time + memory), `UltraWeb research … pages=` and
   `search blocked` (DuckDuckGo bot check from the phone?), `GESTURE: sent … accepted=` (takeover taps).
3. Fix what the file proves, one batch, one release. Candidates depending on results: settle time, vision memory fit,
   whether DuckDuckGo blocks the POST from the phone (then add a fallback engine), Kokoro vs Ryan.
4. Keep `ARCHITECTURE.md` + the page's Verification table current; add a host-harness case for any new native code.

## Release log since the last handoff
### LATEST+12 (session 01K5Mi, 2026-10-09 02:07 UTC)
`v3.0.35-test` (run 35) is Latest: first v3.0 tag. v3 handoff lifts (quick replies, pause expiry, never-silent fixes,
price parsing, scam phrases, link-example guard, eval cases) + push rules. 1388 JVM tests. Unproven on the phone.

### LATEST+11 (session 01K5Mi, 2026-10-08 16:41 UTC)
`v2.6.34-test` (run 34) is Latest: reference-design merge (fail-closed tool args, offline-only switch, route audit,
backup/transfer exclusions, [UNTRUSTED DATA] label, BrainLoopTest, Verification tree, probes "Memory scan at 40k" and
"Offline only, for real"). 1338 JVM tests. Unproven on the phone.

### LATEST+10 (session 01BUKa, 2026-10-07 late night)
`v2.6.33-test` (run 33) is Latest: verification pass. ARCHITECTURE.md (the code skeleton, 8 Mermaid diagrams); native
shims PROVEN on host (vision output identical to llama-mtmd-cli, ASan clean; harnesses in ultra-native/tools/host-harness);
14 bugs fixed (Stop swallowed by tools, ambient_recall always refused, protected-app OCR, takeover ref-count and tap
settle, DDG POST + bot-check detection, invalid UTF-8, bitmap lifetimes…). Phone runtime still unproven. Next: owner
runs describe-my-screen, a product search, an app task, and ★ Real-use check.

### LATEST+9 (session 01BUKa, 2026-10-07 night)
`v2.6.32-test` (run 32) is Latest: screen vision — ML Kit OCR built in, Qwen2.5-VL 3B vision model (2.8 GB, Settings →
SCREEN VISION), screen_ocr / look_at_screen tools, OCR in thin screens and the offline navigator, browser fallback for
JavaScript pages. `v2.6.31-test`: search-first research engine + takeover with Stop. The accessibility service must be
toggled once for screenshots. SOURCE-FIXED BUT RUNTIME-UNPROVEN (no image described anywhere yet). Next: owner downloads
the vision model, says "describe my screen", runs ★ Real-use check and sends the file.

### LATEST+8 (session 01BUKa, 2026-10-07 evening)
`v2.6.30-test` (run 30) is Latest (Jarvis no longer hears itself: every Speaker mutes the listener). Before: `v2.6.29-test` (second Real-use fixes: window by segment start, speed boost off, prompt warm-up, Ryan default voice). Before: `v2.6.28-test` (fixes from the first Real-use check: runaway segments, slow-core whisper, copied prompt examples, Kokoro threads). Before: `v2.6.27-test` (adds Insights, literature patterns, Algospeak, tips, notes copy/share). Before: v2.6.26. Since v2.6.24: Real-use check (Jarvis Tests ★, one tap), screen tests (ScreensTest,
Robolectric, in CI), act first, live thinking preview, Instant spoken think, transcript only when needed, chat Copy /
Edit / Branch / select, speech in fuller pieces, Tor Browser in prompts. All SOURCE-FIXED BUT RUNTIME-UNPROVEN. Next:
owner runs ★ Real-use check and sends the file (voice speed, first spoken word, stop time, download speed).

### LATEST+7 (session 01BUKa, 2026-10-07 ~11:00 UTC)
`v2.6.24-test` (run 24, 31255f4) is Latest (run 23 built but failed to publish in a GitHub outage). Also since: abliterated 4B default, 4-connection model downloads, accurate-speech download from Settings. Since v2.6.20: answers spoken sentence by sentence, spoken
requests think ≤5 s, stop / add-on / change mid-task (chat, voice screen, Hey Jarvis barge-in), whisper small.en +
speed fallback + looser quiet-speech filters, speaker labels on by default, voice picker (Kokoro George default + 10
Piper voices + phone voice; speed, pitch), HOW JARVIS TALKS settings, in-app Guide (assets/guide.md = GUIDE.md).
All SOURCE-FIXED BUT RUNTIME-UNPROVEN. Next: owner installs, Settings → JARVIS'S VOICE (Kokoro downloads ~100 MB),
Preview; Guide button; results file.

### LATEST+6 (session 01BUKa, 2026-10-07 ~09:00 UTC)
`v2.6.20-test` (run 20, 5eedc70) is Latest; runs 18 and 19 were cancelled because 20 contains them. Since v2.6.17:
Hey Jarvis no longer waits for the memory model (it stops it), live "Hey Jarvis status" + "Test Jarvis's voice"
in Settings, speech plays as media with audio focus and speak-answers on by default, on-device brain searches the
web ("Web results" block) and is told it can see and use the screen, unrestricted access switch (default on),
spoken chat requests send at once, far speech: gate 8 dB above floor / -58 dB min, AGC effect, MicGain boost
before whisper and the wake detector. All SOURCE-FIXED BUT RUNTIME-UNPROVEN. Next: owner installs 2.6.20 →
Jarvis Tests 1 shows "App: 2.6.20" → voice test, Hey Jarvis status, Hey Jarvis screen-off, web search, far speech.

### LATEST+5 (session 01BUKa, 2026-10-07 ~08:00 UTC)
`v2.6.17-test` (run 17, b81193e) is Latest. Since v2.6.15: notes on their own (summaries, who + tags, follow-up
links, re-read of old memory, off-charger at ≥50%, Organise now, chat turns remembered, "make a note of what Sam
said…"), "Hey Jarvis" speaks back ("Yes?", "On it."), locked-phone policy, full device-control tools offline,
PEOPLE (owner name, voices) and SYSTEM PROMPT settings, voice names inferred/told. See DEVLOG. All
SOURCE-FIXED BUT RUNTIME-UNPROVEN. Next: owner installs 2.6.17 → Jarvis Tests 1 shows "App: 2.6.17" → 3 → 5 → file.

### LATEST+4 (session 01BUKa, 2026-10-07 ~07:00 UTC)
`v2.6.15-test` (run 15, 05abe67) is Latest. Since v2.6.13: the offline brain is told what it can do (memory,
notes, phone actions, Now line); "what do you remember?" overview brief; nothing-found brief; REAL feature-check
probes (memory in the real chat, notes from real speech, live wake through the running service); Notes and Memory
screens, Focus menu (Normal/Memory/Notes/Both), notes/memory tools by voice or text ("note: …", "search my notes
for …", "open my notes"), one notes store (old note files imported). All SOURCE-FIXED BUT RUNTIME-UNPROVEN.
The owner had still not installed anything newer than ≤2.6.8 at the last results file (21:02). First check
"App: 2.6.15" in Jarvis Tests 1.

### LATEST+3 (session 01BUKa, 2026-10-07 ~04:45 UTC)
`v2.6.13-test` (run 13, 40d4479) is Latest: + listening resumes after restart/update (ResumeNotice, Jarvis Tests
starts it), misspelt name after a wake word wakes ("Hey Joris"). The owner's 21:02 file was STILL an old build
(≤2.6.8): Obtainium has not installed 2.6.10/2.6.12 (verified the 2.6.12 APK itself has the new code, versionCode
29). Next: confirm the phone shows "App: 2.6.13" in Jarvis Tests 1 before reading any results.

### LATEST+2 (session 01BUKa, 2026-10-07 ~03:00 UTC)
`v2.6.12-test` (run 12, 7ffe265) is the Latest release: everything below plus the wake chime, own-voice wake
probe graded on ambient's decode, results file stamped with the app version, ambient whisper on the idle big
cores + per-utterance whisper timing. The owner's 19:36/19:41 files were from an old build (no new tests ran).
Next: owner updates → Jarvis Tests 1 (header must say App: 2.6.12) → 3 → 5 → Save → send. Then check: tok/s at
4 threads ≈ 9; "Wake phrase (your voice)"; pattern notes; `whisper audioMs= ms=` lines (ms < audioMs = keeps up);
any "backlog full" lines.

### LATEST+1 (session 01BUKa, 2026-10-07 ~01:30 UTC)
Owner's results `Jarvis-results-20261006-1758.txt`: Feature check PASS 17 / WARN 2 / FAIL 1. Commit 229dfb0
(1167 JVM tests, 0 failures; assembleDebug OK) fixes from it: LLM decodes with 4 threads (speed test 4=8.96,
5=7.23, 6=4.79 tok/s; pool still 6 workers), "I hate/hi/hay Jarvis" wakes, new probe "Wake phrase (your
voice)", observation without "text" kept with a plain summary. Merged `claude/qwen35-preset` (host-harness
PASS on a tiny qwen35 model, see DEVLOG). Then 876c4ac: wake chime (owner request). Run 9 failed in CI setup (pip download timeout, no build ran); run 10 (37556147640) for 876c4ac succeeded → published `v2.6.10-test` (Latest).
Next: owner updates via Obtainium → Jarvis Tests 3 (speed) + 5 (Feature check, says "Hey Jarvis" when asked)
→ sends file. Check: tok/s ≈ 9 at 4 threads; own-voice wake result; pattern notes now PASS.


---
# History (older handoffs, superseded by the section above)

## LATEST (session 01K5Mi, 2026-10-07 ~00:45 UTC) — read this first
**Owner status:** on the Pixel via **Obtainium** (works: fine-grained read-only PAT; app source
github.com/Arbloodhound/Jarvis; APK regex `jarvis-test\.apk`). Installed 2.6.7 (test build), all
downloads done, brain = Qwen3-4B-Thinking-2507 Q4_0. Brain test: 6.0 tok/s steady, majfaults 0
(a first run gave 3.4 tok/s with majfaults 19/108 = model still paging in after load; Thinking depth
does not affect tok/s). Owner wants DEPLOYMENT + high reasoning; no new features unless asked.
To deploy: in Obtainium change the regex to `jarvis-release\.apk` (installs over, models stay).

**Branch `claude/new-session-t3cy17` @ 20ee531** (1156 JVM tests, 0 failures):
- Speed boost = speculative decoding with Qwen3-0.6B Q4_0 draft (382,156,480 B), Settings switch
  "Speed boost (draft model)" default on, DOWNLOADS item; host-proven identical token streams;
  phone speed-up UNMEASURED (estimate 1.0–1.6×, Leviathan et al.). Bench (Jarvis Tests 3) now sweeps
  4/5/6 threads × draft off/on → gives best thread count.
- Brain-test verdict never recommends a weaker model for the 4B Thinking.
- Release APK run 8 (37552322105) building → publishes `v2.6.8-test` as GitHub **Latest** (test builds
  are now normal releases marked Latest, tag v2.6.<run>-test, newest 5 kept). CI caches natives:
  builds ~6–15 min.

**In progress:** branch `claude/qwen35-preset` (agent; may be WIP): adds selectable (NOT default)
preset Huihui-Qwen3.5-4B-abliterated Q4_K_M (mradermacher repo, 2,707,514,688 B). Official Qwen3.5-4B
card: GPQA-D 76.2 vs 65.8 for the current 4B Thinking. Hybrid (Gated DeltaNet) → the native KV
prefix reuse must fall back to a full re-read when partial seq_rm is unsupported; Qwen3-0.6B draft
won't match its vocab (must fall back cleanly). Finish, review, merge, build, run Release APK
(workflow_dispatch, ref claude/new-session-t3cy17, test_kit=true), tell the owner.

**Next for the owner:** update via Obtainium to 2.6.8 (or later) → DOWNLOADS → Download all missing
(gets the draft) → airplane on, plugged in → Jarvis Tests 3 (speed test, ~15 min) and 5 (Feature check)
→ Save results → send both files. Then: lock the best thread count; compare Qwen3.5-4B vs 4B Thinking
on the Feature check; keep the better as default.

**Known small:** DOWNLOADS shows 0% for the voice while it downloads (no progress callback); an old
saved label for the 1.7B preset may still say "MoE stand-in". Debug-apk/local builds (versionCode 17)
can no longer install over CI builds (versionCode 17+run).
Postponed by the owner: GPU (Vulkan), LoRA fine-tune.

## CURRENT (session 01K5Mi, 2026-10-06 ~23:00 UTC) — read this first
- All three fix branches from "STOPPED" are implemented and merged, plus fast CI/one-tap updates.
  `claude/new-session-t3cy17` @ ce3896d. 1133 JVM tests, 0 failures; assembleDebug OK.
  - download-all-impl: Settings DOWNLOADS + "Download all missing", first-run card, Jarvis Tests button 0.
  - broken-fixes-impl: ambient hold cap 15 s (no thermal drops below CRITICAL), librarian when/todo
    normaliser, model-aware verdict, thermal WARM 0.75 / HOT 0.87 / CRIT 0.95.
  - fast-reasoning-impl: default thinker Qwen3-4B-Thinking-2507 Q4_0 (8B kept selectable), Thinking
    depth Quick/Balanced/Deep + "Answer now", ctx sized from free memory.
  - fast-updates: native-lib + gradle caches in both workflows; release-apk publishes each test_kit
    run as pre-release `v2.6.<run>-test` (jarvis-test.apk, jarvis-release.apk; newest 5 kept);
    versionCode 17+run, versionName 2.6.<run> on CI. Debug-apk/local builds stay versionCode 17 and
    can no longer install over a CI build (owner must stick to Release APK runs).
- Release APK run 6 (37543938421) started 22:59 → should publish `v2.6.6-test` (first run fills caches).
- Owner set up Obtainium with a read-only fine-grained PAT. Once v2.6.6-test exists: Add App
  https://github.com/Arbloodhound/Jarvis, Include prereleases ON, APK regex `jarvis-test\.apk`.
  Then: pick 4B Thinking in ON-DEVICE MODEL → DOWNLOADS → Download all missing → Feature check → file.
- All of the above: SOURCE-FIXED BUT RUNTIME-UNPROVEN.

## STOPPED (session 01BUKa, 2026-10-06 ~23:00 UTC, session limit) — read this first
The three workers below were told to STOP and push their work as "WIP (stopped, not fully tested): …"
to their branches. Read each branch's last commit message for what's done, then finish, test, merge,
build and release as listed. The owner's next step after that: install as an Update → Settings →
DOWNLOADS → Download all missing → Jarvis Tests 5 · Feature check → send the file.
Owner's preferences: short plain answers; the guide is `DEPLOY_GUIDE.md`; update it if a button or
section name changes (e.g. the new DOWNLOADS section replaces the per-model download steps in Part 2).

**All three workers stopped before writing any code.** Each branch holds only one empty
"WIP (stopped, not fully tested)" commit; its message records the findings below. Nothing has been
built or tested on them. So all of the implementation is still to do, starting from the current
`claude/new-session-t3cy17`. The branches are only notes; there is nothing to cherry-pick.
None of the findings below has been checked on the phone. They come from reading the code and from the owner's results file.
- **`claude/download-all` @ 2282bb5**
  - **Voice:** the TTS voice downloads only when Jarvis is about to speak an answer, and spoken replies
    are off by default, so it never downloads.
  - **Embedder:** it needs at least one ambient line, plus Wi‑Fi, plus the retry backoff to have passed, plus an indexer trigger.
  - **Sizes:** whisper 59.7 MB, VAD 0.9, wake 1.1+1.3+1.3, voice 21.0, speaker 40.3, embedder 23.0 + 0.2 vocab,
    8B 4.8 GB, 1.7B 1.06 GB.
  - **To build:**
    - a Settings DOWNLOADS section at the top, listing every asset with its size and status, plus "Download all missing";
    - a first-run card;
    - a Jarvis Tests "Download everything missing" button;
    - Feature-check MISSING lines that point to it.
- **`claude/broken-fixes` @ 00b98ed**
  - (1) Ambient holds transcription whenever either model is generating, loading or warming, with no
    time cap. At WARM/HOT it silently drops overheard speech, a thermal rule written before whisper got
    its own cores [0,1]. Fix: cap the hold, and don't drop on thermal now that whisper is on separate cores.
  - (2) The librarian parser never fills `when` from the note text and never turns a fact into a to-do
    ("need to …"). The prompt's example is also the probe's own test case, so change the example.
  - (3) The brain-test verdict text (it wrongly suggests the IQ2_S MoE) was not started; make it model-aware.
  - (4) A cool, idle phone already reads headroom 0.58 against WARM at 0.65, so the thresholds need retuning.
- **`claude/fast-reasoning` @ 193bb59**
  - **Benchmarks:** in Qwen's published thinking-mode numbers (4B-2507 model card, and Qwen3 Tech Report
    arXiv 2505.09388 Table 17), the **4B-Thinking-2507 beats the 8B**:
    - GPQA 65.8 vs 62.0
    - AIME25 81.3 vs 67.3
    - LiveBench 71.8 vs 67.1
    - IFEval 87.4 vs 85.0
    - BFCL-v3 71.2 vs 68.1
    - The 8B is ahead only on MMLU-Redux, 87.5 vs 86.1.
  - **So:** make the 4B-Thinking-2507 the default thinker. It runs at 8–11 tok/s on the Pixel, against 2–4 for the 8B. That answers the owner's
    "too slow but I need powerful reasoning".
  - **Think budgets are tiny:** WaitBudget's 20 s / 60 s wait caps, then the 0.6 scaling at WARM. Add the
    Thinking depth setting (Quick/Balanced/Deep), with Deep lifting those caps.
  - **8B paging:** its ctx is sized from total RAM, so it loaded resident at 16384 and left about 2.4 GB free.
    Size it from the model plus free memory instead.
  - **Later candidate:** Qwen3.5-4B (Q4_0 2.58 GB; card GPQA 76.2). llama.cpp 8345f33 supports its architecture. It is
    untested on the phone, and it would re-read the whole prompt every turn (no prefix reuse), so don't
    switch to it yet.

## IN PROGRESS (session 01BUKa, 2026-10-06 ~22:40 UTC)
Owner's Feature-check file: `/root/.claude/uploads/54f3fe27-91b3-59c8-8eda-5b26d60c8d8d/4a86451b-Jarvis-results-20261006-1528.txt`.
Summary of it:
- The Jarvis TTS voice and the MiniLM embedder were never downloaded.
- Ambient "last heard=never", with "backlog full: dropped" all through the log.
- Librarian notes 1/3 (the time was in the text not `when`; the to-do came back as fact).
- The 8B ran at 2–4 tok/s with paging, against 8–11 tok/s for the 4B-Thinking-2507.
- Think budgets were tiny (38–342 tokens).
Three workers, each on its own branch from c852b73 (not merged):
- `claude/download-all`: Settings "DOWNLOADS" section + "Download all missing" + a first-run card; root cause of the voice/embedder not downloading.
- `claude/broken-fixes`: ambient holding/dropping speech, the librarian note normaliser, the model-aware brain-test verdict, thermal thresholds.
- `claude/fast-reasoning`: Qwen model-card benchmarks (4B-Thinking-2507 vs 8B), default thinker choice, a "Thinking depth" Quick/Balanced/Deep setting, 8B ctx sizing so it doesn't page.
To finish:
1. Merge all three.
2. Run the full build.
3. Add a DEVLOG entry and push.
4. Run Release APK (workflow_dispatch, test_kit=true).
5. Send the owner the link, then have him: Update → Settings → DOWNLOADS → Download all → Feature check → send the file.

## NOW (session 01BUKa, 2026-10-06 21:45 UTC) — read this first; the sections below are history
**State:** `claude/new-session-t3cy17` @ a1aaced. Everything is merged and pushed:
- the two models (thinker Qwen3-8B Q4_K_S + librarian Qwen3-1.7B Q4_0);
- semantic memory, librarian notes, pattern notes (hedged, no diagnoses), HistoryPolicy (the Q8/Q11
  copy fix), offline phone control;
- transcript cues: pauses/rate/loudness tags, plus speaker tags that are OFF until the owner records
  their voice;
- the Jarvis Tests "Feature check" (button 5);
- the cue fixes (a1aaced): enrolment via the ambient tap, no "[Unknown]" with tags off, a smaller
  cue queue, and probes that follow the live path.
Local JVM tests: 1066, 0 failures; the debug APK builds.

**Builds:**
- **Release APK run 37535503016** (test_kit=true, a1aaced): https://github.com/Arbloodhound/Jarvis/actions/runs/37535503016
  **SUCCEEDED 22:02 UTC** (app-test-realkey 47.7 MB, app-release 35.2 MB; expire 2026-10-20). Link sent to the owner with DEPLOY_GUIDE.md. A self check-in was set for ~22:14 UTC (trigger trig_01CaTfzy1MHX46ZiSyNcBuet).
  - If it's green, give the owner the link. He installs `app-test-realkey` as an Update (models stay),
    then Jarvis Tests → 5 · Feature check (airplane on, say Yes to the flashlight), Save results, and
    pastes the file. `app-release` comes later, as an Update.
  - If it failed, read the job logs, fix, push, then re-run Release APK (workflow_dispatch, ref
    `claude/new-session-t3cy17`, test_kit=true).
- Previous good build: 37527886135 (e475273), the same minus the cue fixes.

**Next, once the owner's Feature-check file arrives:**
1. Read each PASS/WARN/FAIL line.
2. Tune the first-guess thresholds: WER 10/25%, 5 tok/s, pause 1.3–2.3 s (note: a 1.5 s pause
   splits the speech into two lines; the tag lands at the start of the second line), voice match 0.5,
   top-3 ≥ 80%, whisper MIN_TOKEN_P 0.45 (the wake probe prints confidence with and without the
   filler prompt and timing).
3. Fix what fails.

**Known, not fixed:**
- "pay 240 pounds" doesn't trigger the wrong-amount refusal (`Disagreement.statedByUser` needs £/GBP
  or "pay 240").
- If Jarvis is speaking when voice enrolment starts, the end of its speech can lift the enrolment
  mute early.
- Lines stored before a1aaced with tags off still read "[Unknown]".

**Postponed by the owner:** GPU (Vulkan), the LoRA fine-tune kit, the release build install.

**Owner:** Pixel 10 Pro XL, Chromebook, no adb. Wants plain, short answers. Installs by downloading
the artifact on the phone and choosing Update, never uninstall. Commit footer: see earlier sections.

## FINAL (session 01BUKa, 2026-10-06 20:40 UTC) — read this first
- Everything is merged on `claude/new-session-t3cy17` @ e475273: offline navigation, transcript cues
  (speakers/pauses; speaker tags OFF by default), the Jarvis Tests "Feature check" (button 5; 15 probes,
  PASS/WARN/FAIL/MISSING + measured scores, summary in Save results). Locally: 1011 tests passed on the
  cues merge; the feature-check worker reports 1056 tests, 0 failures, plus assembleDebug and
  compileReleaseKotlin.
- **Final signed build: SUCCEEDED 21:08 UTC** (artifacts app-test-realkey 47.7 MB, app-release 35.2 MB; expire 2026-10-20). Release APK run 37527886135 (test_kit=true) at
  https://github.com/Arbloodhound/Jarvis/actions/runs/37527886135. When it's green, give the owner
  `app-test-realkey` (installs as an Update over run 37499999756's test build). Then:
  1. Settings → download Qwen3-8B + the memory model.
  2. Airplane mode on.
  3. Jarvis Tests → 5 · Feature check.
  4. Save results and paste the file.
- Known gap (not fixed): `Disagreement.statedByUser` misses "240 pounds" (needs £/GBP or "pay 240").
- Feature-check thresholds are first guesses: WER 10/25%, 5 tok/s, pause 1.3–2.3 s, top-3 ≥ 80%.
  Tune them from the first file.

## LATEST+1 (session 01BUKa, 2026-10-06 ~20:15 UTC) — read this before "LATEST"
- `claude/offline-navigation` (ebe2a2a) is MERGED into `claude/new-session-t3cy17` and pushed.
  The local test + assembleDebug run of that merge was in progress.
- `claude/transcript-cues`: a worker is finishing the WIP (ce5624c), which needs review, tests and a
  build. If it isn't pushed, the worktree is at
  `/tmp/claude-0/-workspace-Jarvis/54f3fe27-91b3-59c8-8eda-5b26d60c8d8d/scratchpad/cues`
  (lost with the container). Otherwise restart from `origin/claude/transcript-cues-wip`.
- NEW owner request: "a proper way to test all features, if present and how good they are".
  A worker is building `claude/feature-check`: a "Feature check" button in Jarvis Tests with a
  PASS/WARN/FAIL/MISSING line + measured score per feature. It covers native/CPU, thinker quality +
  the Q8/Q11 regression, librarian notes, memory search hit rate, history policy, cue-taxonomy (no
  diagnoses), TTS→whisper WER + wake phrase, offline flashlight on/off, safety gates and ambient
  status. It writes a summary into Save results. The transcript-cue probe is to be added after
  transcript-cues merges.
- Then: merge both, run the full build, DEVLOG, push, and trigger **Release APK** (workflow_dispatch,
  ref `claude/new-session-t3cy17`, test_kit=true). Send the owner the run link (`app-test-realkey` =
  install as an Update over run 37499999756's test build).

## LATEST (session 01K5Mi, 2026-10-06 ~17:00 UTC) — read this first
- **FEATURE FREEZE** (owner): finish only what's listed here, then deploy. GPU (Vulkan) and the LoRA
  fine-tune kit are postponed ("another day"); not started, nothing to merge.
- Branch head `4f7ff10`: two models + librarian + semantic recall + brain memory + HistoryPolicy +
  two-model fixes/thermal (merged), and release-apk.yml `test_kit` input (test build signed with the
  REAL key → owner downloads models once; the release later installs over it as an update).
- **APK for the owner:** Release APK workflow_dispatch on `claude/new-session-t3cy17` with
  test_kit=true was started ~16:58 (Actions → Release APK, newest run). Artifacts:
  `app-test-realkey` (install now, has Jarvis Tests) and `app-release` (later, as Update).
  Owner must UNINSTALL the old test-key Jarvis first (loses only the useless 30B). Earlier trial
  run 37498543984 failed on an NDK download glitch (before signing) and was re-run once.
  Do NOT tell the owner to install the test-key debug run 37497677277.
- **Agent branches to merge (each based on 6544e3e), then rerun Release APK with test_kit=true:**
  - `claude/cue-taxonomy` @ 53c275a — pushed; observations + hedged psychological "readings",
    no diagnoses; agent's report wasn't delivered: review the commit message, build, test.
  - `claude/transcript-cues` — speaker/pause/overlap/rate/loudness tags; was still running.
  - `claude/offline-navigation` — offline multi-step phone control; was still running.
  If a branch isn't pushed, its worktree is under `.claude/worktrees/` (may be lost with the container).
- Thermal: the Pixel slowdown was most likely headroom ≥0.80 → HOT → 2 threads (not status LIGHT);
  tune WARM_AT/HOT_AT only after a run logs `UltraThermal` + `UltraLlm threads=` lines.
- Owner then: install `app-test-realkey`, download Qwen3-8B + memory model (Qwen3-1.7B), run Jarvis
  Tests (hardware, speed, full brain test), Save results, send file; then install `app-release`.
- Status of everything above: SOURCE-FIXED BUT RUNTIME-UNPROVEN (nothing has run on the Pixel).

Read `CLAUDE.md` (honesty statuses, no drift, no proof-free claims) and the top ~8 entries of
`DEVLOG.md` first. Repo `Arbloodhound/Jarvis`, app in `ultra-native/`, working branch
**`claude/new-session-t3cy17`** (draft PR #1 → main; do not merge). Last commit on it: `9411941`.

## UPDATE 2026-10-06 (later session 01BUKa) — read this first, it supersedes "Next steps" below

**Owner's decision:** small model for memory (Qwen3-1.7B Q4_0 "librarian"), big model for reasoning
(Qwen3-8B Q4_K_S "thinker"), both loaded (~6 GB of ~8.6 GB free). "Push the apk when done."

**Four workers were building it and were STOPPED at the owner's request; each pushed WIP (not fully
tested) to its own branch, draft PRs into `claude/new-session-t3cy17` may or may not exist:**
1. `claude/two-models` — `LocalModelEngine.librarian(ctx)` (own prefs "ultra_local_model_librarian"),
   ONE process-wide native mutex for both engines (shim globals are single-flight), Qwen3-1.7B Q4_0
   preset (verify size with the HF MCP), thinker default = Qwen3-8B on ≥14 GB RAM, librarian yields
   memory to the thinker, `complete(prompt,maxTokens)` non-thinking via `LocalPrompt.buildNoThink`
   ("/no_think"), Settings "MEMORY MODEL" section.
2. `claude/semantic-memory` (built on semantic-memory-wip) — MiniLM qint8 ONNX embedder + WordPiece,
   vectors in SQLite, `MemoryRecall.relevant(query, now, limit)` = semantic + FTS + recency + TimeWindow,
   `RecallSource` extension point, `brief(hits)`; keyword fallback when no embedder.
3. `claude/memory-librarian` (built on memory-librarian-wip) — `MemoryNotes` SQLite store + `Librarian`
   job (charging & idle & engine not busy) using `fun interface SmallModel`; setting "Organise memory
   while charging"; `MemoryNotes.search` → `NoteHit`.
4. `claude/brain-memory` — `HistoryPolicy` (offline history only when the request refers back; none for
   long pastes → fixes Q8/Q11 copying), stable history block for KV prefix reuse, `MemoryBrief.provider`
   hook that puts "What I remember…" into the offline prompt when the request needs memory.

**To finish:** read each branch's last commit message, finish/fix + test each, merge all four into
`claude/new-session-t3cy17`, then WIRE: `Librarian.model` → `LocalModelEngine.librarian(ctx).complete`;
`MemoryRecall.extraSources` += adapter over `MemoryNotes.search`; `MemoryBrief.provider` →
`MemoryRecall.brief(MemoryRecall.relevant(q, now))`; optionally the cloud `ambient_recall` tool too.
Build (`source ~/android-env.sh`, `fetch-sherpa.sh` once, flock gradle; baseline 2026-10-06 passed),
DEVLOG entry, commit with `[apk]`, push, send the owner the Actions run link when green.

**Worker reports at stop:**
- **Gotcha for every branch:** `app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so` is not in git. Run
  `bash ultra-native/ondevice-brain/fetch-sherpa.sh` in each checkout, or Gradle stops at `:app:checkSherpaLib`
  before compiling.
- `claude/memory-librarian` @ 1d84b5e: NOT compiled or tested (stopped at that check). API:
  - `Librarian.model: SmallModel?`, where `fun interface SmallModel { suspend fun complete(prompt: String, maxTokens: Int): Result<String> }`
    gets a full ChatML no-think prompt.
  - Start it with `Librarian.start(ctx, scope)` from `AmbientListenService.onStartCommand`. WorkManager is not a dependency.
  - `MemoryNotes.get(ctx).search(query, limit): List<NoteHit(text, ts, kind)>`.
  - If the small model sets `LocalModelEngine.busy`, also set `Librarian.brainBusy`, or the job cancels itself.

- `claude/two-models` @ 6978796: NOT compiled or tested (same sherpa stop). API:
  - `LocalModelEngine.librarian(context)`;
  - `suspend fun complete(prompt, maxTokens): Result<String>`, with the prompt from `LocalPrompt.buildNoThink(system, user)`;
  - `shared(ctx)` is still the thinker.
  - 1.7B Q4_0 size confirmed on the Hub: 1,056,782,912 B.
  - **Open code-review findings to fix:**
    - the thinker can load next to a librarian that reloaded after being unloaded twice;
    - the resident "keep in memory" check ignores the librarian's mapped weights;
    - a file both engines use is counted twice;
    - ambient only watches the thinker's `busy`;
    - the new 8B default strands a half-downloaded 4B file.

- `claude/brain-memory` @ d4029d5: agent tests pass (98, `--tests com.agent.ultra.agent.*`); the full
  suite and assembleDebug were NOT run.
  - Done: `HistoryPolicy` (only Q10 of the 11 test questions gets history), `HistoryPolicy.window`
    (drops old turns in blocks of 4 from an anchor, for prefix reuse), and `MemoryBrief.provider`,
    `wants()`, `cap` 800, `recall(q, 4000 ms)`.
  - The brief lands via `LocalPrompt.build(..., memory)` as a second system block just before the user
    turn, and `emitLocal` is wired to it.
  - **Open review findings to fix:**
    - `window` can drop the only assistant turn that fits;
    - "rewrite that in this style: …" counts as a self-contained paste, so it gets no history;
    - plain-"it" questions like "what time is it" get history;
    - possessive names, "recall" and "earlier" start memory searches for general questions;
    - for referring-back requests the memory search query has no real content.

- `claude/semantic-memory` @ e4fc27e: NOT compiled or tested (same sherpa stop).
  - Has: `MemoryRecall.relevant`/`brief`, `RecallSource`, `Hit`, `extraSources`, a background embedding
    indexer, and resumable model download.
  - Hub sizes verified: model 23,026,053 B, vocab 231,508 B.
  - 10 open review findings, all listed in that commit message. The worst three:
    - erasing memory mid-index leaves stale vectors attached to new rows;
    - the CPU fallback can close an ONNX session still in use;
    - the indexer downloads 23 MB hourly even though nothing calls `MemoryRecall` yet.

**Owner-approved order after the librarian + thinker build lands (2026-10-06, session 01K5Mi):**
1. Make cues visible in transcripts: speaker tags (sherpa-onnx speaker embeddings, enrol owner, "that was Sam"),
   pause lengths ([pause 1.8s] from whisper word timestamps + VAD), overlaps/interruptions, speech rate,
   loudness change, laughter, and keep fillers (whisper prompt/verbatim). On-device only (biometrics).
2. A fixed taxonomy of OBSERVABLE patterns (commitments vs vague promises, hedging, topic avoidance/shifts,
   interruptions/dominance, politeness shifts, agreement/disagreement, tone change, stress markers, decisions,
   recurring themes); each note = observation + exact quote/timestamp evidence + confidence. Observations,
   never verdicts/diagnoses.
3. Zero-shot first: put the taxonomy in the librarian prompt; owner lives with it ~1 week.
4. Only then fine-tune the 1.7B librarian (LoRA) on labelled examples: public corpora with labels (AMI, ICSI,
   Switchboard DA, DailyDialog, MELD, Stanford Politeness, ConvoKit, AnnoMI — check each license) plus
   teacher-labelled owner-format transcripts reviewed by a human; quantize Q4_0; add preset.
5. Evaluate on held-out labelled conversations. Remind owner: consent laws for recording others.

**Queued by the owner after that build:**
- Pause/timing tags in transcripts: whisper.cpp word timestamps (today `no_timestamps`) + VAD gaps →
  `[pause 1.8s]`, speech-rate/loudness tags, so the models can see hesitation.
- Speaker tags: sherpa-onnx (already bundled for TTS) speaker-embedding model → voiceprint per
  utterance, cluster into Speaker 1/2…, owner names them once ("that was Sam"), owner's voice enrolled;
  on-device only (biometric data). Accuracy caveats told to the owner.
- Owner asked about fine-tuning the memory model on sociolinguistics/psychology: told it only sees text,
  so tags above must come first, needs labelled examples not textbooks, report observations not verdicts.

**Pending owner test:** run 37486113860 (`9411941`) APK → switch to Qwen3-4B Q4_0 → Jarvis Tests 2 + 3.
His last file (0859) was still the 30B on the newer build: warm worked, still 0.7–1.0 tok/s (doesn't fit).

## Who you're working with
- Dafarus: not a professional programmer; wants plain, short, direct answers, no markdown guide files
  unless asked ("just tell me"). Swears when frustrated; stay calm and concrete.
- He tests on a **Pixel 10 Pro XL** (Tensor G5, 15,081 MB total RAM, Android 17, de-Googled intent:
  avoid Google services). He's on a **Chromebook with no adb**: install = download the `app-debug`
  artifact on the phone's browser (GitHub Actions run page → Artifacts; "Desktop site" if needed),
  Files → Extract → tap APK → **Update** (never uninstall: the debug key is now fixed, updates keep
  the downloaded models). Testing = the debug-only **"Jarvis Tests"** app icon (hardware check, quick
  3-question brain test, full 11-question test, speed test, Save results → he pastes the file here).
- Asked for parallel agents for speed (Agent tool, isolation "worktree", background). Workers must
  `flock /tmp/jarvis-gradle.lock` every Gradle run and use their own scratchpad subfolder.

## How to build / ship
- `source ~/android-env.sh && cd ultra-native && flock /tmp/jarvis-gradle.lock ./gradlew -q testDebugUnitTest assembleDebug`
  (needs `bash ultra-native/ondevice-brain/fetch-sherpa.sh` once). Native compile can exceed 10 min.
  Last result: 766 unit tests, 0 failures.
- A CI debug APK builds on push to `claude/**` when the commit message contains `[apk]`
  (`.github/workflows/debug-apk.yml`, ~18 min). Check runs with `mcp__github__actions_list`
  (resource_id `debug-apk.yml`) and send him the run URL when `conclusion: success`.
- **Pending right now:** CI runs for `4c4ef42` and `9411941` (both `[apk]`). `9411941` is the one to
  give him (verified model links). He has not installed either yet.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01K5MiduHeAWehbQ3gsEnbHY`. Update DEVLOG.md per session.
- Hugging Face: shell curl is blocked (proxy), but the **Hugging Face MCP connector** works
  (`mcp__Hugging_Face__hf_fs` → `ls hf://models/<repo>`) — use it to verify any model URL/size.

## Where things stand (honest)
- **PROVEN on the Pixel (old APK, before the memory fixes):** the Qwen3-30B-A3B IQ2_M MoE (9.7 GB)
  runs at 0.4–1.6 tok/s because only ~8.6 GB is ever available, so weights are re-read from storage
  (25k–227k major faults/answer). It cannot fit this phone; not tunable. Thermal policy also dropped
  to 2 threads (HOT at status 1). NNAPI exposes **"google-edgetpu"** (TPU reachable, no Google
  services) but it rejected 2 of 3 wake models and gave no speed-up on the third. OpenCL/Vulkan load.
- **Response (in 4c4ef42/9411941):** new presets, verified on the Hub: Qwen3-4B Thinking 2507 Q4_0
  (2,375,773,312 B, now the default on ≥8 GB phones), its abliterated Q4_K_M, Qwen3-8B Q4_K_S
  (4,802,012,992 B). Q4_0/Q4_K are repacked for i8mm by llama.cpp; IQ2 never is. Bench now uses
  exact thread counts (`exactThreads`).
- **Built this session, all SOURCE-FIXED BUT RUNTIME-UNPROVEN:** ANR fix (model load off main),
  resumable download + preload, page-cache warm, resident ("keep whole brain in memory") load,
  "Loading the brain…/Thinking… N s" chat status, offline tool calls for thinking models
  (`LocalToolCall`), wait-bounded think budgets (`WaitBudget`/`DecodeSpeed`), ambient yields to the
  brain (`AmbientBacklog`), context size by RAM (`contextFor`), option B (`DirectRequest`: no
  confirm card when the user asked for it; money/delete/new recipient/unreadable still ask; Settings
  switch), Jarvis Tests quick test + Stop + live timer.
- Release signing: he was given a keystore + password + key.txt (my copies deleted) and told to add 4
  GitHub secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`=ultra, `KEY_PASSWORD`) and
  publish a Release with tag `v2.6.0-rc1` targeting this branch to prove `release-apk.yml`. Unknown if done.
  Don't install the release build on the Pixel yet (different key → reinstall + re-download).

## Next steps, in order
1. When CI for `9411941` succeeds, send him the link. Ask him to: Update; Settings → ON-DEVICE MODEL →
   Change model → **Qwen3-4B Thinking 2507 (Q4_0)** → Download; ambient listening OFF, airplane ON;
   Jarvis Tests → **2 · Quick brain test**, then **3 · Speed test**; also "turn on the flashlight" in
   chat; Save results and paste. (Optional comparison: same quick test with the 30B on the new build —
   the log now shows `load resident=…` and `warm …` lines.)
2. Decide the model setup from the numbers: one 4B for everything, or 8B thinker + Qwen3-1.7B Q4_0
   (1,056,782,912 B, verified) as fast/librarian model (~6 GB together). The owner wants "fast but the
   most powerful reasoning possible on this phone".
3. **Owner-approved architecture to finish** (librarian + thinker): two agents were stopped mid-task
   at the handoff. Their partial, NOT built/tested code is on:
   - `claude/semantic-memory-wip` (5d4c929): all-MiniLM-L6-v2 qint8 ONNX embedder
     (`hf://models/sentence-transformers/all-MiniLM-L6-v2/onnx/model_qint8_arm64.onnx`, 23,026,053 B),
     WordPiece tokenizer, vector table, time-window parser; try NNAPI/edgetpu placement like WakeGate.
     Goal: `MemoryRecall.relevant(query)` (semantic + keyword + recency) feeding a short "what I overheard"
     brief into offline `Brain.emitLocal` and the cloud `ambient_recall` tool.
   - `claude/memory-librarian-wip` (f745fc7): note parser, merge, time resolver. Goal:
     `listen/MemoryNotes.kt` store + `Librarian` job that, while charging & idle and the engine isn't
     busy, turns `heard` rows into dated notes (people/plans/todos/facts) with the small model; Settings
     switch "Organise memory while charging" + note count. MemoryRecall should search notes as a 2nd source.
   Re-spawn workers from those branches (or finish yourself), build, review, merge, `[apk]`.
4. Open quality bugs seen in the Pixel test: an answer leaked "17x23 (391)" from earlier history; the
   long-paste summary (Q11) repeated the previous answer instead of summarising — investigate
   history/prompt building in `Brain.emitLocal`/`LocalPrompt` and KV prefix reuse.
5. Later / optional: GPU (llama.cpp Vulkan on PowerVR) for faster prompt reading — experiment only
   after the 4B numbers; ThermalPolicy may be too aggressive (HOT at Android status 1); multi-step
   offline phone navigation (ReActNavigator is cloud-bound; speed is the blocker); fine-tune last;
   release build at the end.
