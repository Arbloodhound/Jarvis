# HANDOFF — 2026-10-08, continue exactly from here

Read `CLAUDE.md` (honesty statuses, no drift, no proof-free claims) and the top ~8 entries of
`DEVLOG.md` first. Repo `Arbloodhound/Jarvis`, app in `ultra-native/`, working branch
**`claude/new-session-t3cy17`** (draft PR #1 → main; do not merge). Last commit on it: `9411941`.

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
