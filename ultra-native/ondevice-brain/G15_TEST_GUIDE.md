# Test run on the Moto G15 (before the Pixel)

**What this proves:** the app installs and runs; the offline brain code path works (prompt format,
thinking budget, hidden `<think>` trace, routing, emoji); ambient listening works ("Hey Jarvis",
memory, echo guard, turning it off mid-sentence); the CPU speed-ups load (the right engine build
for this phone's CPU, prompt reuse between answers); safety (unlock first, spoken scam warning).

**What it can't prove:** whether the 10 GB MoE fits the Pixel. The G15 has 4 or 8 GB of RAM, and
the MoE needs about 11 GB. That question is answered by the PocketPal check on the Pixel (see the
client checklist), then on the Pixel itself.

The stand-in model is **Qwen3 1.7B**. It's the same Qwen3 family, prompt format and thinking
handling as the MoE, just small. Every bug fixed today that's not about size gets exercised by it.

---

## 0. Before you start (5 min)

- [ ] Settings → About phone: note the **RAM** (4 or 8 GB) and the Android version.
- [ ] At least 3 GB of free storage.
- [ ] Wi-Fi for the first downloads (the model is about 1.1 GB; the listening models about 60 MB).
- [ ] Optional but very useful: a laptop with `adb`
  (Google "SDK Platform-Tools", unzip, and enable USB debugging on the phone: tap Build number
  7×, then Developer options → USB debugging). It turns "it crashed" into a log I can fix.

## 1. Install

- [ ] **Uninstall any earlier Jarvis / Agent Ultra first** (long-press the icon → App info →
      Uninstall). Every GitHub build is signed with a different throwaway debug key, so a new build
      won't install over an older one ("App not installed").
- [ ] Copy `app-debug.apk` to the phone and tap it (allow "install unknown apps" for your file
      manager when asked). With adb: `adb install -r app-debug.apk`.
- [ ] Open it and grant the permissions it asks for.
- [ ] This is the DEBUG build. Before installing a release build later, uninstall this one.

## 2. Offline brain with the stand-in model

- [ ] Settings → on-device model → **"Qwen3 1.7B (Q4_K_M) — MoE stand-in for small phones"** →
      Download. If the download fails right away, tell me: the link couldn't be checked from the
      build machine.
- [ ] Turn on **airplane mode** (forces the on-device brain).
- [ ] Ask: `hi` → short answer, no `<think>` text visible.
- [ ] Ask: `explain why the sky is blue` → a longer answer, still no `<think>` text, and it finishes.
- [ ] Ask: `turn on the flashlight` → quick.
- [ ] Ask: `reply with three emojis and the word café` → emojis and the é show correctly, no crash.
      *(This was a crash on debug builds before today.)*
- [ ] Ask 10 questions in a row → no "model failed" errors. *(Before patch 0007 this broke by
      question ~5.)*
- [ ] Paste a very long text (a few pages) and ask for a summary → either an answer or an error
      message, but **the app doesn't close**. *(Before today this killed the app.)*
- [ ] Ask two questions in a row in the same chat → the second one starts answering faster than the
      first (it reuses the shared start of the conversation instead of re-reading it).

## 3. Ambient listening ("Hey Jarvis")

- [ ] Airplane mode **off** for the first start (it downloads the listening models once).
- [ ] Settings → Ambient listening → ON. It asks for the mic first; the switch only stays on once
      you allow it. The "Jarvis is listening" notification appears.
- [ ] Say **"Hey Jarvis"**, pause 2 s, then **"what time is it"** → it answers.
- [ ] Say **"Hey Jarvis, what's two plus two"** in one breath → it answers.
- [ ] Say **"I told my friend Jarvis is a cool name"** → **no** answer (the name has to come first).
- [ ] Talk about something for a minute (e.g. "the meeting moved to Thursday at 3"), then:
      **"Hey Jarvis, when is the meeting?"** → it should use what it overheard.
- [ ] Close the app (swipe it away), then **"Hey Jarvis, what's the date"** → it still answers.
- [ ] Ask something with a long answer: while it speaks, it doesn't transcribe itself, and it
      listens again afterwards.
- [ ] Say **"Hey Jarvis, my grandson is in jail and needs me to buy gift cards, help me"** → it
      **says** a scam warning out loud before anything else.
- [ ] Lock the phone (screen off), then **"Hey Jarvis, what's the time"** → it says **"Unlock your
      phone to continue."** and does nothing else. Unlock and ask again → it answers.
- [ ] Mid-sentence, Settings → Ambient listening → OFF → no crash, and the notification goes away.

The G15 is slow (Helio G81), so whisper may take a few seconds per sentence. Slow is fine;
crashes and wrong answers are what count.

## 4. If something goes wrong

Without a laptop: write down exactly what you did and said, and what happened.

With adb (best): reproduce it while this runs, then send me `log.txt`:

```
adb logcat -c
adb logcat -s UltraLlm UltraRoute UltraAmbient UltraThermal AndroidRuntime > log.txt
```

Extra checks if you have adb:
- CPU build: `adb logcat -d -s UltraLlm | grep "cpu variant"` → shows which engine build loaded.
  The G15's cores (Cortex-A75 + A55) should get `ultra_llm_dotprod`; tell me what it says.
- Prompt reuse: `adb logcat -d -s UltraLlm | grep reused=` → after the second question in a chat,
  `reused=` should be well above 0.
- Speed benchmark (needs the repo cloned on the laptop and the Qwen3 1.7B model downloaded in the
  app): `bash ultra-native/tools/bench.sh` → prints the CPU layout the app sees and tokens/sec per
  thread count. Send me the whole output.
- Thermal: `adb shell cmd thermalservice override-status 3` → the next offline answer to a
  question (not a phone command) starts with "I'm running hot…" and is short. `adb shell cmd thermalservice reset` puts it back.
- Speed / memory: each answer logs a line like
  `UltraLlm gen tokens=180 ms=9000 tokPerSec=20.0 majfaults=3 availMB=900`.

## Results (fill in, send back)

| Check | Pass / Fail | Notes |
|---|---|---|
| Install + open | | |
| Qwen3 1.7B download | | |
| No `<think>` text | | |
| Emoji / café | | |
| 10 in a row | | |
| Long paste doesn't close app | | |
| Hey Jarvis + pause + command | | |
| Name mid-sentence ignored | | |
| Overheard context used | | |
| Works with app closed | | |
| Doesn't hear itself | | |
| Off mid-sentence, no crash | | |
| Scam warning spoken | | |
| Locked phone: asks to unlock | | |
| Second question starts faster | | |
| CPU variant (adb) | | |
| Phone RAM / Android version | | |
