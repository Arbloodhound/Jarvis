> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/ultra-native/ondevice-brain/PIXEL_TEST_GUIDE.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# Test run on the Pixel 10 Pro (replaces the G15 run)

**What this proves:** the real on-device brain, the 10 GB Qwen3-30B-A3B MoE, fits and runs on
this phone at a usable speed; whether the phone has a TPU/GPU path Jarvis can use; the
Google-free voice (own speech recognition, own voice); ambient listening ("Hey Jarvis"); the
safety checks (unlock first, spoken scam warning).

It replaces both the G15 run and the PocketPal check: we test the real model in Jarvis itself.

Use the **debug APK** for this run (the accelerator check and the benchmark only exist in the
debug build). The release build comes after, for handover.

> **Status as of 2026-10-19:** preserved as a test procedure. What this run would prove — the 10 GB MoE fitting and running at usable speed, accelerator paths, voice, ambient listening — are objectives, not recorded results: on-device runtime behaviour was **not re-verified in the 2026-10-19 audits** (static, grep-level reading only), and CI has not yet compiled the merged tree. Model sizes, memory figures, and speed expectations in this guide are the pre-consolidation bundle's own and stand until a run on the merged build replaces them. One audit finding matters before the download step: GGUF downloads are verified by size only, with no integrity check before native load.

---

## 0. Before you start (5 min)

- [ ] Settings → About phone: note the **Android version** and the **OS** (stock Pixel OS or
      GrapheneOS / other).
- [ ] **At least 15 GB free storage** (the model is ~10.2 GB; the listening and voice models ~85 MB).
- [ ] Wi-Fi, and the charger plugged in for the download.
- [ ] Strongly recommended: a laptop with `adb` (Platform-Tools; on the phone tap Build number 7×,
      then Developer options → USB debugging). Sections 2 and 5 need it.
- [ ] Close other heavy apps. Jarvis needs ~11 GB of the 16 GB with the MoE loaded.

## 1. Install

- [ ] Uninstall any earlier Jarvis / Agent Ultra that was NOT installed from a GitHub debug
      build (GitHub debug builds share the test debug keystore in `ci/`, so they install over
      each other; other builds may use a different key and won't).
- [ ] Install `app-debug.apk` (tap it, or `adb install -r app-debug.apk`). Open it and grant the
      permissions it asks for.

## 2. Accelerator check (2 min, adb) — do this first

- [ ] Settings → Ambient listening → ON once (downloads the wake models the check times), then
      bring Jarvis back to the front.
- [ ] On the laptop, from the repo: `bash ultra-native/tools/accel-probe.sh > accel.txt`
- [ ] Send me `accel.txt`. The last lines say `TPU via NNAPI: available / not available /
      unknown (why)` and whether OpenCL (GPU) is there. This is the one answer only the phone
      can give.

## 3. The real brain (MoE IQ2_M)

- [ ] Settings → on-device model → **"Qwen3-30B-A3B Thinking ABLITERATED (IQ2_M)"** → Download
      (~10.2 GB, on Wi-Fi; can take a while).
- [ ] Turn on **airplane mode** (forces the on-device brain).
- [ ] Ask: `hi` → short answer, no `<think>` text visible. Note how long the first answer takes
      (loading 10 GB the first time is slow; later answers shouldn't be).
- [ ] Ask: `explain why the sky is blue` → longer answer, no `<think>` text, and it finishes.
- [ ] Ask: `turn on the flashlight` → quick.
- [ ] Ask: `reply with three emojis and the word café` → shows correctly, no crash.
- [ ] Ask **10 questions in a row** in one chat (anything: recipes, history, maths). Note whether
      answers get slower, whether the phone gets very hot, and whether anything crashes.
- [ ] Paste a very long text (a few pages) and ask for a summary → an answer or an error message,
      but **the app doesn't close**.
- [ ] Leave the app, open a few other apps (camera, browser), come back and ask again → it still
      answers (if it reloads the model first, that's fine; a crash isn't).

**Speed and memory (adb):** after the 10 questions:
`adb logcat -d -s UltraLlm | grep "gen tokens"` → one line per answer like
`tokPerSec=… majfaults=… availMB=…`. Send me these lines.

**Which model to keep:**

| What you saw | What we do |
|---|---|
| About 5 tokens/sec or faster, question 10 not much slower than question 2, no crash | IQ2_M it is. |
| Works but slow, slows down over the 10 questions, `majfaults` in the thousands, or very hot | Download **IQ2_S** (the "roomier fallback" entry), repeat this section, compare. |
| IQ2_M crashes or freezes, and IQ2_S does too | Too big. We pick a smaller model together; nothing else changes. |

## 4. Voice (no Google)

- [ ] Settings → Voice: **Jarvis's own speech recognition** and **Jarvis's own voice** are ON.
- [ ] Airplane mode **off** for the first voice use (speech model ~60 MB and voice ~21 MB download
      once).
- [ ] Chat → tap the mic → say **"what's the weather like"** → the text appears.
- [ ] Tap the mic and say nothing → after ~8 s "I didn't hear anything".
- [ ] With answers spoken aloud: the very first answer may use the phone's built-in voice (if it has
      one) while Jarvis downloads its own; from then on it speaks in its own male voice.
- [ ] Long answer, then stop / ask something new → it stops talking at once.

## 5. Ambient listening ("Hey Jarvis")

- [ ] Settings → Ambient listening → ON. The "Jarvis is listening" notification appears.
- [ ] **"Hey Jarvis"**, pause 2 s, **"what time is it"** → it answers.
- [ ] **"Hey Jarvis, what's two plus two"** in one breath → it answers.
- [ ] **"I told my friend Jarvis is a cool name"** → **no** answer.
- [ ] Talk for a minute ("the meeting moved to Thursday at 3"), then **"Hey Jarvis, when is the
      meeting?"** → it uses what it overheard.
- [ ] Swipe the app away, then **"Hey Jarvis, what's the date"** → it still answers.
- [ ] While it speaks a long answer it doesn't transcribe itself, and listens again afterwards.
- [ ] **"Hey Jarvis, my grandson is in jail and needs me to buy gift cards, help me"** → it
      **says** a scam warning first.
- [ ] Lock the phone, **"Hey Jarvis, what's the time"** → **"Unlock your phone to continue."**
      Unlock, ask again → it answers.
- [ ] Mid-sentence, turn ambient listening OFF → no crash, notification goes away.
- [ ] With ambient listening ON, ask the MoE 3 questions in the chat → still answers (both fit in
      memory together).

## 6. If something goes wrong / extra adb checks

Reproduce it while this runs, then send me `log.txt`:

```
adb logcat -c
adb logcat -s UltraLlm UltraRoute UltraAmbient UltraAccel UltraThermal AndroidRuntime > log.txt
```

- CPU build: `adb logcat -d -s UltraLlm | grep "cpu variant"` → the Tensor G5 should get
  `ultra_llm_i8mm`.
- Wake models: `adb logcat -d -s UltraAmbient | grep -E "wake model|wake gate on"` → which of
  CPU / XNNPACK / NNAPI each model kept.
- Prompt reuse: `adb logcat -d -s UltraLlm | grep reused=` → above 0 from the second question on.
- Speed sweep (MoE downloaded): `bash ultra-native/tools/bench.sh` → tokens/sec per thread count.
- Thermal: `adb shell cmd thermalservice override-status 3` → the next offline answer starts with
  "I'm running hot…" and is short. `adb shell cmd thermalservice reset` puts it back.

## Results (fill in, send back with accel.txt and the logs)

| Check | Pass / Fail | Notes |
|---|---|---|
| Android version / OS | | |
| Install + open | | |
| Accelerator check (accel.txt) | | |
| MoE IQ2_M download | | |
| First answer time | | |
| No `<think>` text | | |
| Emoji / café | | |
| 10 in a row (speed Q2 vs Q10) | | |
| tokens/sec, majfaults, availMB | | |
| Long paste doesn't close app | | |
| Survives switching apps | | |
| Phone temperature | | |
| IQ2_S needed? | | |
| Own speech recognition (mic) | | |
| Own voice speaks | | |
| Hey Jarvis + pause + command | | |
| Name mid-sentence ignored | | |
| Overheard context used | | |
| Works with app closed | | |
| Doesn't hear itself | | |
| Scam warning spoken | | |
| Locked phone: asks to unlock | | |
| Ambient + MoE together | | |
| Off mid-sentence, no crash | | |
| CPU variant / wake placement (adb) | | |
