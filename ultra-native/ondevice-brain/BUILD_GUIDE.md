> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/jarvis-native/ondevice-brain/BUILD_GUIDE.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# MINT-TRINITY — Build & Deploy Follow-Along

Ordered, do-this-then-that. Each step says what success looks like before moving on.
Target: Pixel 10 Pro (GrapheneOS), Jarvis fork, abliterated Qwen3-30B-A3B-Thinking MoE,
always-listening ambient layer. Assistant-only environment (search + screen control).

## Status as of 2026-10-19

This guide's procedures are preserved from the pre-consolidation bundle and were **not re-run against the merged tree** in the 2026-10-19 audits, which were static, grep-level reading; CI has not yet compiled the merged tree. All work is now consolidated on `main` in Arbloodhound/Jarvis. Two audit findings bear on following it: GGUF downloads are verified by size only, with no integrity check before native load; and two Kotlin unit-test files are broken in the merged tree (`HistoryPolicyTest.kt`, `MemoryBriefTest.kt` import a debug-only class) — the app build phases below do not depend on them, but a full unit-test run will hit them. On-device claims in this guide (model fit, memory headroom, speeds, ambient-listening behaviour) are the bundle's own figures and were not re-verified in the 2026-10-19 audits.

====================================================================
PHASE 0 — Get the code into YOUR GitHub (/arbloodhound)
====================================================================
These commands do it from your machine. (This phase records how the bundle was originally imported into the fork; the repo's work has since been consolidated onto `main` — see the status section above.)

    # 1. Fork the base on github.com: dafarusd/Ultra-Agent  (keeps AGPL-3.0; you publish source)
    #    OR create an empty repo under your org: github.com/arbloodhound/mint-trinity
    git clone https://github.com/dafarusd/Ultra-Agent.git
    cd Ultra-Agent
    git remote set-url origin https://github.com/arbloodhound/<repo>.git   # your repo
    git checkout native
    git checkout -b mint-trinity
    git am /path/to/0001-ambient-listen.patch      # applies all 4 commits
    git push -u origin mint-trinity

SUCCESS: your repo shows the `listen/` package, cpp/jarvis_whisper.cpp, and the model presets.
If `git am` complains, use: git apply --reject 0001-ambient-listen.patch  then resolve .rej files.

====================================================================
PHASE 1 — Toolchain (one-time)
====================================================================
Install: Android Studio + SDK 35, NDK r26+, JDK 17 (a full JDK; a JRE can't compile).
    CMake: app/build.gradle.kts pins CMake 3.28.3 exactly (CXX1300 without it).
    `cmake;3.28.3` is NOT an SDK package (sdkmanager has 3.22.1, 3.30.x, ...), so put
    3.28.3 on PATH: Ubuntu 24.04 `apt install cmake`, or `pip install cmake==3.28.3 ninja`.
    NDK: Gradle builds the JNI libs with its default NDK 27.0.12077973; the static
    libs were built with r27c (27.2.12479018). Both linked fine.
    export ANDROID_NDK=~/Android/Sdk/ndk/<version>

SUCCESS: `echo $ANDROID_NDK` prints a real path; `sdkmanager --list` shows platform 35.

====================================================================
PHASE 2 — Native libraries (BOTH required or the app won't link)
====================================================================
2a. llama.cpp -> ~/llama-android/{lib,lib-dotprod,lib-i8mm,include}
    ./build-llama-android.sh          # from this bundle (three CPU variants, several minutes each)
    # lib = armv8-a fallback, lib-dotprod = +dotprod+fp16, lib-i8mm = +dotprod+i8mm+fp16 (Pixel 10).
    # The app ships one shim per variant and loads the best the phone supports (logcat: "cpu variant=").
2b. whisper.cpp (new, for ambient listening) -> ~/whisper-android/{lib,lib-dotprod,lib-i8mm,include}
    ./build-whisper-android.sh        # from this bundle; the same three CPU variants as llama
    # The app ships one jarvis_whisper shim per variant and loads the best (logcat: "whisper cpu variant=").

SUCCESS: all lib dirs contain libllama.a / libwhisper.a and matching libggml*.a, and
    ~/whisper-android/include has ggml-cpu.h + ggml-backend.h (verified build: NDK r27c, Oct 2026).

====================================================================
PHASE 3 — First build, stock small model (prove the app runs)
====================================================================
    cd jarvis-native && ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk
On phone: open app, Settings -> download "Gemma 3 1B" (default), enable Accessibility service.

SUCCESS: you can chat, and it can read/tap another app's screen.

====================================================================
PHASE 4 — Put the real brain on the phone (no training yet)
====================================================================
Model is already wired. Two ways to load the abliterated IQ2_M (10.2GB):
  A) In-app: Settings -> model list -> "Qwen3-30B-A3B Thinking ABLITERATED (IQ2_M)" -> Download.
  B) Sideload: download the GGUF on a PC, then
     (debug build only; the app reads only its private files/models/ folder, not /sdcard)
     adb push qwen3moe-abliterated-iq2m.gguf /data/local/tmp/ && adb shell run-as com.agent.jarvis sh -c 'mkdir -p files/models && cp /data/local/tmp/qwen3moe-abliterated-iq2m.gguf files/models/' && adb shell rm /data/local/tmp/qwen3moe-abliterated-iq2m.gguf
     (rename the downloaded Huihui-...i1-IQ2_M.gguf to qwen3moe-abliterated-iq2m.gguf first),
     then Settings -> model list -> that entry -> Use this.
Set context 4k. Close all other apps (assistant-only). 

SUCCESS: it answers from the MoE with the phone offline. Watch `adb shell dumpsys meminfo com.agent.jarvis`
— resident set stays stable, no repeated large page-ins (that would be thrashing -> switch to IQ2_S).

====================================================================
PHASE 5 — Ambient listening (always-on, local, wake-gated)
====================================================================
5a. Models download automatically the first time ambient listening starts (patch 0009):
    whisper base.en (~60 MB, required), Silero VAD, and the openWakeWord trio (hey_jarvis).
    The phone needs internet for that first start only; after that it's fully offline.
5a-wake. Custom phrase ("hey blood"): train on the openWakeWord Colab (synthetic TTS, ~1h), then
      adb push hey_blood.onnx /data/local/tmp/ && adb shell run-as com.agent.jarvis cp /data/local/tmp/hey_blood.onnx files/
    and set prefs key ambient/wake_onnx to hey_blood.onnx. Custom files are never overwritten.
    Shapes/names VERIFIED against the real models + upstream reference (patch 0002).
    Tuning: WakeGate.lastScore exposes the live score; raise threshold (0.5 -> 0.6-0.7) if it false-fires.
5b. Settings -> "Ambient listening" ON; set a distinctive wake name (NOT "Ultra" — too common).
5c. GrapheneOS: Settings -> Apps -> Jarvis -> Battery -> Unrestricted.

SUCCESS: mic indicator stays on; speaking stores transcript (searchable via the `ambient_recall`
tool); saying "<wake name>, <request>" triggers an answer; ordinary talk does NOT.

====================================================================
PHASE 6 — YOUR specialized + abliterated model (path 2b)
====================================================================
On Colab with an 80GB-class GPU (>=64GB VRAM; a 40GB A100 will OOM), open Qwen3_30B_A3B_LoRA_Colab.ipynb:
  1. Put train.jsonl (chat format; mix your 6 domains: psych/psychoanalysis, sociolinguistics,
     chemistry, coding, pharmacology, opsec) in Google Drive at MyDrive/jarvis-train/train.jsonl
     (survives a runtime reset), or upload it to /content. Examples without a system turn get the app's
     own offline system prompt added automatically.
  2. Optional: Colab key icon -> secret HF_TOKEN = a WRITE token from huggingface.co/settings/tokens.
  3. Run top-to-bottom: preflight -> data check -> LoRA on the abliterated model -> merge ->
     imatrix on your formatted chats -> quantize to IQ2_M (+ IQ2_S). Most answers need <think> traces; no refusals.
     Watch the ETA printed after 13 steps; interrupt if it's over budget. Training checkpoints go to Drive:
     a disconnect resumes (re-run sections 0-6), and section 6b restores a finished adapter.
  4. Section 15 uploads both GGUFs to huggingface.co/arbloodhound/jarvis-qwen3moe-abliterated-gguf (public).
  5. On the phone: Settings -> model list -> "Jarvis fine-tune (IQ2_M)" or "(IQ2_S)", whichever size the
     Pixel test chose -> Download (or adb push it as in Phase 4B, debug build). No code change or rebuild, unless you changed REPO.

SUCCESS: on-device answers reflect your domains; offline; uncensored; ~1.6GB RAM free.

====================================================================
PHASE 7 — Still to build (not in the bundle yet; next sessions)

(Checklist states below are as documented pre-consolidation; they were not re-verified in the 2026-10-19 audits.)
====================================================================
[x] openWakeWord acoustic gate — WIRED + VERIFIED (WakeGate.kt, patch 0002).
[x] thinking-budget cap + intent routing — wired into engine + offline Brain (patches 0003, 0007)
[ ] web tools polish: fetch_page + readability (search is in; offline-aware)
[x] thermal guard — engine threads, think budgets, ambient whisper throttle (0005, 0007, 0008)
[ ] Even G2 connection (BLE or Even Hub plugin) — route glasses mic into the same Segmenter
[ ] prosody / social-cue branch — experimental, reports measured signal not verdicts

====================================================================
WHAT'S DONE vs OPTIMAL (honest status)
====================================================================
(Pre-consolidation status, carried over as written; not re-verified in the 2026-10-19 audits. The merged-tree audit verdicts for the packages involved — `agent/`, `gate/`, `listen/`, `local/` — are "works with defects"; the post-merge status section at the top of this guide governs.)
DONE & solid: ambient capture+filter+memory+recall, wake (text-match), offline-first grounding,
  novelty rule, premise-verification loop, provenance tags, model wiring + fit math, 2b training notebook.
NOT YET OPTIMAL: no thinking cap yet;
  G2 not connected. None blocks a working build — they're the Phase-7 polish.
