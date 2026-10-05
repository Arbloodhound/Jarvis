# MINT-TRINITY — Build & Deploy Follow-Along

Ordered, do-this-then-that. Each step says what success looks like before moving on.
Target: Pixel 10 Pro (GrapheneOS), Agent Ultra fork, abliterated Qwen3-30B-A3B-Thinking MoE,
always-listening ambient layer. Assistant-only environment (search + screen control).

====================================================================
PHASE 0 — Get the code into YOUR GitHub (/arbloodhound)
====================================================================
I cannot push for you (no GitHub tool in this chat — connecting it in org settings
authorizes OTHER systems, not this sandbox). These commands do it from your machine:

    # 1. Fork the base on github.com: dafarusd/Ultra-Agent  (keeps AGPL-3.0; you publish source)
    #    OR create an empty repo under your org: github.com/arbloodhound/mint-trinity
    git clone https://github.com/dafarusd/Ultra-Agent.git
    cd Ultra-Agent
    git remote set-url origin https://github.com/arbloodhound/<repo>.git   # your repo
    git checkout native
    git checkout -b mint-trinity
    git am /path/to/0001-ambient-listen.patch      # applies all 4 commits
    git push -u origin mint-trinity

SUCCESS: your repo shows the `listen/` package, cpp/ultra_whisper.cpp, and the model presets.
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
2a. llama.cpp -> ~/llama-android/{lib,include}
    ./build-llama-android.sh          # from this bundle (several minutes)
2b. whisper.cpp (new, for ambient listening):
    ./build-whisper-android.sh        # from this bundle; writes ~/whisper-android/{lib,include}

SUCCESS: both lib dirs contain libllama.a / libwhisper.a and matching libggml*.a, and
    ~/whisper-android/include has ggml-cpu.h + ggml-backend.h (verified build: NDK r27c, Oct 2026).

====================================================================
PHASE 3 — First build, stock small model (prove the app runs)
====================================================================
    cd ultra-native && ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk
On phone: open app, Settings -> download "Gemma 3 1B" (default), enable Accessibility service.

SUCCESS: you can chat, and it can read/tap another app's screen.

====================================================================
PHASE 4 — Put the real brain on the phone (no training yet)
====================================================================
Model is already wired. Two ways to load the abliterated IQ2_M (10.2GB):
  A) In-app: Settings -> model list -> "Qwen3-30B-A3B Thinking ABLITERATED (IQ2_M)" -> Download.
  B) Sideload: download the GGUF on a PC, then
     adb push Huihui-...i1-IQ2_M.gguf /sdcard/Android/data/com.agent.ultra/files/qwen3moe-abliterated-iq2m.gguf
Set context 4k. Close all other apps (assistant-only). 

SUCCESS: it answers from the MoE with the phone offline. Watch `adb shell dumpsys meminfo com.agent.ultra`
— resident set stays stable, no repeated large page-ins (that would be thrashing -> switch to IQ2_S).

====================================================================
PHASE 5 — Ambient listening (always-on, local, wake-gated)
====================================================================
5a. Models download automatically the first time ambient listening starts (patch 0009):
    whisper base.en (~60 MB, required), Silero VAD, and the openWakeWord trio (hey_jarvis).
    The phone needs internet for that first start only; after that it's fully offline.
5a-wake. Custom phrase ("hey blood"): train on the openWakeWord Colab (synthetic TTS, ~1h), then
      adb push hey_blood.onnx /data/local/tmp/ && adb shell run-as com.agent.ultra cp /data/local/tmp/hey_blood.onnx files/
    and set prefs key ambient/wake_onnx to hey_blood.onnx. Custom files are never overwritten.
    Shapes/names VERIFIED against the real models + upstream reference (patch 0002).
    Tuning: WakeGate.lastScore exposes the live score; raise threshold (0.5 -> 0.6-0.7) if it false-fires.
5b. Settings -> "Ambient listening" ON; set a distinctive wake name (NOT "Ultra" — too common).
5c. GrapheneOS: Settings -> Apps -> Agent Ultra -> Battery -> Unrestricted.

SUCCESS: mic indicator stays on; speaking stores transcript (searchable via the `ambient_recall`
tool); saying "<wake name>, <request>" triggers an answer; ordinary talk does NOT.

====================================================================
PHASE 6 — YOUR specialized + abliterated model (path 2b)
====================================================================
On Colab with an 80GB-class GPU (>=64GB VRAM; a 40GB A100 will OOM), open Qwen3_30B_A3B_LoRA_Colab.ipynb:
  1. Put train.jsonl (chat format; mix your 6 domains: psych/psychoanalysis, sociolinguistics,
     chemistry, coding, pharmacology, opsec) in Google Drive at MyDrive/ultra-train/train.jsonl
     (survives a runtime reset), or upload it to /content. Examples without a system turn get the app's
     own offline system prompt added automatically.
  2. Optional: Colab key icon -> secret HF_TOKEN = a WRITE token from huggingface.co/settings/tokens.
  3. Run top-to-bottom: preflight -> data check -> LoRA on the abliterated model -> merge ->
     imatrix on your formatted chats -> quantize to IQ2_M (+ IQ2_S). Most answers need <think> traces; no refusals.
     Watch the ETA printed after 13 steps; interrupt if it's over budget. Training checkpoints go to Drive:
     a disconnect resumes (re-run sections 0-6), and section 6b restores a finished adapter.
  4. Section 15 uploads both GGUFs to huggingface.co/arbloodhound/ultra-qwen3moe-abliterated-gguf (public).
  5. On the phone: Settings -> model list -> "Ultra fine-tune (IQ2_M)" or "(IQ2_S)", whichever size the
     Pixel test chose -> Download (or adb push the file). No code change or rebuild, unless you changed REPO.

SUCCESS: on-device answers reflect your domains; offline; uncensored; ~1.6GB RAM free.

====================================================================
PHASE 7 — Still to build (not in the bundle yet; next sessions)
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
DONE & solid: ambient capture+filter+memory+recall, wake (text-match), offline-first grounding,
  novelty rule, premise-verification loop, provenance tags, model wiring + fit math, 2b training notebook.
NOT YET OPTIMAL: no thinking cap yet;
  G2 not connected. None blocks a working build — they're the Phase-7 polish.
