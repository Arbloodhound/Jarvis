> **Post-merge replacement — 2026-10-19.** This is a point-in-time document. Its body is preserved verbatim as the historical record; the status section below states what the 2026-10-19 code audits and the full-branch consolidation (all work now on `main`) changed. The previous version is preserved at `archive/2026-10-19/ultra-native/ondevice-brain/CLAUDE_CODE_BRIEF.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

## Status as of 2026-10-19

- This brief was written on 2026-10-05 against an upstream base snapshot, before the 2026-10-19 full-branch consolidation. All work is now on `main`; the brief's assumptions about the base tree, patch application, and a byte-identical resulting tree describe that snapshot, not the merged tree.
- The build and test results marked PROVEN in the body (compile, 482 unit tests, `assembleDebug`, native-lib alignment) were proven for that snapshot only. They were **not re-verified in the 2026-10-19 audits**, which were static, grep-level reading; CI has not yet compiled the merged tree, and two Kotlin unit-test files (`HistoryPolicyTest.kt`, `MemoryBriefTest.kt`) are currently broken in it because they import a debug-only class.
- The subsystems the brief touches — `local/`, `listen/`, and the Brain's offline path in `agent/` — carry the merged-tree audit verdict "works with defects". The one critical audit finding (harness database wiring) is resolved in the merged tree; post-merge there are 0 known criticals.
- The brief's own runtime table said it plainly at the time — "a build passing is not behavioral proof" — and that still holds: no on-device runtime behaviour in the body was re-tested by the 2026-10-19 audits.

---

# INTENT HEADER (read before applying — per CLAUDE.md "the bridge")

**Source:** Chat Claude, 2026-10-05. Supersedes any earlier copy of this brief.
**Goal lock:** make the on-device Qwen3-30B-A3B Thinking MoE usable as the offline brain:
ambient listening plus acoustic wake word, a capped thinking trace, thermal throttling, and
correct per-call KV state.
**Subsystems in scope (crossing stated):** B (model / engine: `local/`, `cpp/ultra_llm.cpp`) →
A (Brain offline path only: `Brain.emitLocal`) → D (ambient listening: `listen/`) → H (build:
CMake, Gradle dependency). Dependency order is B → A → D, with H applied alongside.
**Out of scope:** the cloud brain path, tool-loop prompts, UI, the Even G2, prosody. Do not
touch them.

**Assumptions, verified against the real repo** (upstream `dafarusd/Ultra-Agent` at `f7cbb91`,
the base of the arbloodhound fork):
- All 9 patch files apply cleanly with plain `git am`, in order, on that base. The resulting
  `app/src` tree is byte-identical to the tree that was verified.
- `./gradlew :app:compileDebugKotlin`: **PROVEN** (JDK 17, SDK 35, AGP 8.7.3).
- `./gradlew :app:testDebugUnitTest`: **PROVEN**, 482 tests, 0 failures (the repo's own suite
  plus 35 new tests). `assembleDebug`: **PROVEN**, and every native lib in the APK is 16 KB-aligned.
- `ultra_llm.cpp` compiles with `-Wall` against current llama.cpp: **PROVEN**.
- `libultra_whisper.so` builds against current whisper.cpp with NDK r27c, exports only its 3
  JNI symbols, and has 16 KB LOAD alignment: **PROVEN**.
- WakeGate's tensor contract matches upstream openWakeWord (0.998 / 0.000 on
  positive/negative clips): **PROVEN** in a Python mirror. The Kotlin port is
  **SOURCE-FIXED BUT RUNTIME-UNPROVEN**.
- Everything on-device: **CANNOT VERIFY FROM CURRENT ARTIFACTS**. No phone was available.

If your check of the real tree contradicts any assumption above, stop and report. Do not
improvise.

---

## Session start (per CLAUDE.md)

Read `DEVLOG.md`. Goal, scope and proof locks are as stated in the header.

## Step 1 — apply

```bash
git checkout -b ultra-ondevice-brain
git am 0001-ambient-listen.patch            # 5 commits
git am 0002-*.patch 0003-*.patch 0004-*.patch 0005-*.patch 0006-*.patch 0007-*.patch 0008-*.patch 0009-*.patch
```

Then copy `README.md`, `BUILD_GUIDE.md`, `build-whisper-android.sh`, `build-llama-android.sh` and
`Qwen3_30B_A3B_LoRA_Colab.ipynb` into place (find the existing copies with `git ls-files`) and
commit them as one "docs/training" commit.

## Step 2 — prove the build on this machine

```bash
./build-llama-android.sh          # skip if ~/llama-android/lib-i8mm already has libllama.a
./build-whisper-android.sh        # fixed: now ships ggml-cpu.h etc., which whisper.h needs
# Gradle pins CMake 3.28.3 (CXX1300 otherwise); not an SDK package: put it on PATH (apt / pip cmake==3.28.3)
# Gradle uses its default NDK 27.0.12077973 for JNI libs; static libs used r27c; both linked
cd ultra-native
./gradlew :app:testDebugUnitTest  # expect 482 passed, 0 failed
./gradlew :app:assembleDebug
```

Check that every native lib in the APK is 16 KB-aligned (use the NDK's `llvm-readelf`):

```bash
unzip -o app/build/outputs/apk/debug/*.apk 'lib/arm64-v8a/*' -d /tmp/apk
for f in /tmp/apk/lib/arm64-v8a/*.so; do echo $f; llvm-readelf -lW $f | grep LOAD | awk '{print $NF}' | sort -u; done
```

Every lib must show `0x4000`.

## Step 3 — no code left to write

The ambient thermal hook is done in patch 0008 (`AmbientThrottle`). Review it, but don't
rewrite it. Note its deliberate rule: with no acoustic gate loaded, nothing is skipped, because
the wake name is then only detectable in the transcript. Do not "simplify" that away.

## Step 4 — runtime proof (user + phone)

```bash
./gradlew :app:installDebug
adb logcat -s UltraRoute UltraThermal UltraLlm
```

| Check | PROVEN when |
|---|---|
| Offline answer from the MoE (airplane mode) | A reply appears, no `<think>` text in chat or speech, and logs show `tier=` and `thinkTokens=` |
| Repeated use | 10 offline questions in a row with no `decode failed` / `prompt too long`. Before 0007, the KV cache grew every call |
| Budget forcing | A DEEP question logs `forced=true` at most at its budget, and the answer still completes |
| Thermal | `adb shell cmd thermalservice override-status 3` logs `level=CRITICAL` and replies get short, and the wake word still works. `adb shell cmd thermalservice reset` brings them back |
| First start | Enabling ambient listening on a fresh install downloads the models (logcat `UltraAmbient` shows no fetch failures), then listening starts |
| Wake gate | "hey jarvis" opens a command window (no manual model install needed) |
| Echo guard | Ultra doesn't transcribe its own spoken reply, and the mic resumes after long answers |
| Memory | `adb shell dumpsys meminfo com.agent.jarvis` stays stable across the 10 questions |

Report each row with an honesty status. A build passing is not behavioral proof.

## Session end (per CLAUDE.md)

1. Add a `DEVLOG.md` entry: what was applied, what was proven at which status, and what is open
   (runtime rows, training run, G2).
2. Commit, then `git push -u origin ultra-ondevice-brain`. Open a PR into the fork's main branch
   and do not merge.
3. State the overall status honestly.
