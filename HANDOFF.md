# HANDOFF — Pixel 10 Pro optimization batch (2026-10-05, cloud session)

Read `CLAUDE.md` (honesty statuses, no drift) and the top of `DEVLOG.md` first. The repo is `arbloodhound/jarvis`, and the app is in `ultra-native/`. There is no phone in the cloud, so everything on-device is **SOURCE-FIXED BUT RUNTIME-UNPROVEN** until `tools/bench.sh` runs on the client's Pixel 10 Pro.

## Branches and PRs
- **`ccr-3726b695-6kt0de`**: the working branch. PR **#2** (draft) targets `claude/new-session-t3cy17`.
  - Commit 59e671f fixes the llama.cpp build:
    - **The problem:** the engine was compiled for plain ARMv8.0, so it had no SDOT, no SMMLA and no weight repacking.
    - **The fix:** three variants (armv8-a, dotprod, i8mm), with `CpuVariant` picking one at runtime and `check-cpu-variants.sh` in CI.
    - **Proven:** 556 tests and 0 failures; in the APK, the i8mm `.so` has 995 `sdot` and 260 `smmla` instructions, and the baseline has 0.
  - Merged locally on top of that and pushed with this handoff: **unit 2** (#3), **unit 1** (#6), **unit 5** (#4), **unit 6** (#5).
    - Conflicts resolved: `LocalModelEngine.baseThreads()` uses unit 1's version (`CpuTopology.current.llmThreads`); the nativeLoad log line combines unit 1's and unit 5's.
    - The host harness on the merged shim passes, pinned to CPUs 2-3 (workers stay on 2-3, and the caller is restored afterwards).
    - **The full Gradle test + assembleDebug on the merge was still running at handoff, and its result is unknown. Re-run it first.**
- **Not merged yet:**
  - **Unit 4**, PR **#7**, branch `ccr-3726b695-6kt0de-u4-kv-prefix-reuse` (4a32d22): KV prompt-prefix reuse plus `top_k(40)`. It edits `nativeGenerate` in `ultra_llm.cpp`, the same function as unit 1's pin/resume/pause, so **expect a conflict**. Keep both:
    - unit 1's per-call affinity pin and pool resume/pause;
    - unit 4's `cached` tokens, `seq_rm` and `resetCache`.
    - Unit 4 notes: differences from a fresh engine are flash-attention float order (proven); set `cparams.swa_full = true` explicitly in nativeLoad for Gemma.
  - **Unit 3**, whisper.cpp CPU variants, branch `ccr-3726b695-6kt0de-u3-whisper-variants`. It was still running at handoff. Check whether that branch exists on origin or a PR is open. If neither, redo it by mirroring commit 59e671f for whisper:
    - `build-whisper-android.sh` builds 3 variants;
    - CMake builds `ultra_whisper[_dotprod|_i8mm]`;
    - `WhisperNative` load loop;
    - `check-cpu-variants.sh` covers the whisper libs too.
    - **Caution:** unit 1 already changed `WhisperNative.nativeTranscribe` and `ultra_whisper.cpp` (CPU-list pinning), so keep those changes.

## What the user asked, and what's left
1. "**merge them all when they're done and push with [apk]**":
   - merge #7 and unit 3 into `ccr-3726b695-6kt0de`;
   - run the full verification below;
   - add a DEVLOG entry (none written yet for this batch);
   - commit with `[apk]` in the message and push.
   - The Debug APK workflow only triggers on `claude/**` branches or `debug-*` tags, so also push a tag such as `git tag debug-pixel-opt && git push origin debug-pixel-opt`. The user was told this plan.
2. The PRs merge into `ccr-3726b695-6kt0de` (#3–#7); PR #2 merges that into `claude/new-session-t3cy17`. Pushing the merges marks #3–#6 merged automatically.

## Verify (cloud container)
- **Android SDK:**
  - `cmdline-tools` into `~/Android/Sdk`, then `sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018" "ndk;27.0.12077973"`. `dl.google.com` was reachable this session.
  - cmake 3.28.3 is on PATH.
- **Native libs:** `ANDROID_NDK=~/Android/Sdk/ndk/27.2.12479018 bash ultra-native/ondevice-brain/build-llama-android.sh` and `build-whisper-android.sh`.
- **Build and tests:** `cd ultra-native && echo "sdk.dir=$HOME/Android/Sdk" > local.properties && ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`. Unit tests counted: 556 base, +4 (u2), +10 (u1), +5 (u5), +7 (u6).
- **APK checks:** unzip the APK's `lib/arm64-v8a/*`, then run `ondevice-brain/check-cpu-variants.sh <dir>` and the 16 KB `llvm-readelf -lW` loop from `.github/workflows/debug-apk.yml`.
- **Host JNI harness (no phone needed):**
  - `ultra-native/tools/host-harness/setup.sh` once;
  - then `HARNESS_CPUS="2,3" ultra-native/tools/host-harness/run.sh ultra-native/app/src/main/cpp/ultra_llm.cpp`.
  - It must print `HARNESS PASS`. It compares carried vs fresh engines, a thread change, an inject, and per-thread CPU affinity.
  - With unit 4 merged, small carried-vs-fresh mismatches can come from flash-attention float order. Unit 4's driver variant is in its PR description.
- Hugging Face is **blocked** for curl in the cloud. The HF MCP tool works for listing files.

## Client PDF
`ultra-native/ondevice-brain/client/Pixel_Model_Check.pdf`, made by `make_pdf.py` (reportlab). The steps were checked against PocketPal v1.19.1 source and the HF repo:
- Settings are global (Settings screen).
- Memory Mapping must be turned ON (PocketPal's Android default is off).
- Flash Attention should be set to Auto.
- The phone is a Pixel 10 Pro (16 GB), stated as fact.
- It includes the "On speed" caveat.

## Facts established (don't re-research)
- **GPU and TPU are not usable** for our 30B GGUF:
  - llama.cpp's OpenCL backend is Adreno-only;
  - Vulkan on the PowerVR (Tensor G5) gives wrong k-quant output;
  - the TPU is reachable only via the LiteRT Tensor SDK (Gemma-1B class) or Gemini Nano.
- **CPU:** 1× X4, 5× A725, 2× A520 (press reports; `bench.sh` confirms on device).
- **Quant formats:** Q4_0_4_4 is removed from llama.cpp (use Q4_0, which is repacked at load). Q2_K is repacked only on AVX512. IQ2_M is not repacked, but uses SDOT.
