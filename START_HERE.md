# START HERE — everything, step by step

Do the steps in order. Each step says exactly what to click or type. Text in `grey boxes` you copy
and paste. If anything doesn't match what you see, stop and send me a screenshot.

**Time:** about 2–3 hours the first time, mostly waiting for downloads.
**You need:** the Pixel 10 Pro, its USB-C cable, a laptop (Windows or Mac), Wi-Fi, ~15 GB free on
the phone.

---

## PART 1 — Set up the laptop (once, 15 min)

**1.1 Get adb (the tool that talks to the phone)**
1. Go to **developer.android.com/tools/releases/platform-tools**.
2. Click **"Download SDK Platform-Tools for Windows"** (or Mac). Accept, download the zip.
3. Unzip it:
   - Windows: unzip to `C:\platform-tools` (so the file `C:\platform-tools\adb.exe` exists).
   - Mac: unzip into your Downloads folder (so `Downloads/platform-tools/adb` exists).

**1.2 Windows only: get a terminal that runs our scripts**
1. Go to **git-scm.com** → Download for Windows → install with all default options.
2. From now on, "open the terminal" means: Start menu → **Git Bash**.
   (Mac: "open the terminal" means: Spotlight → **Terminal**.)

**1.3 Get the Jarvis files**
1. Go to **github.com/Arbloodhound/Jarvis** (logged in).
2. Click the branch dropdown (says `main`) → pick **`claude/new-session-t3cy17`**.
3. Green **Code** button → **Download ZIP**. Unzip it to your Desktop. Rename the folder to
   `Jarvis` (so you have `Desktop/Jarvis`).

**1.4 Point the terminal at both** — open the terminal and paste:
- Windows (Git Bash):
  ```
  export PATH="$PATH:/c/platform-tools" && cd ~/Desktop/Jarvis && adb version
  ```
- Mac:
  ```
  export PATH="$PATH:$HOME/Downloads/platform-tools" && cd ~/Desktop/Jarvis && adb version
  ```
You should see `Android Debug Bridge version …`. **Every time you open a new terminal, paste this
line again first.**

---

## PART 2 — Set up the Pixel (once, 5 min)

1. Settings → **About phone** → tap **Build number** 7 times (it says "You are now a developer").
2. Settings → System → **Developer options** → turn on **USB debugging**.
3. Plug the phone into the laptop. On the phone: **"Allow USB debugging?"** → tick **Always allow**
   → **Allow**.
4. In the terminal:
   ```
   adb devices
   ```
   You should see one line ending in `device`. (If it says `unauthorized`, unlock the phone and
   accept the popup.)
5. Write down: Settings → About phone → **Android version**, and whether it's stock Pixel OS or
   GrapheneOS.
6. Check storage: Settings → Storage → at least **15 GB free**. Plug in the charger. Connect Wi-Fi.

---

## PART 3 — Install Jarvis (10 min)

1. Open **github.com/Arbloodhound/Jarvis/actions** (logged in) → click the **top run with a green
   tick** whose name starts with "START_HERE.md step-by-step guide" (or anything newer I tell you).
2. Scroll to the bottom → **Artifacts** → click **app-debug** → a zip downloads.
3. Unzip it. You get `app-debug.apk`. Move it into `Desktop/Jarvis`.
4. If the phone has any old Jarvis / Agent Ultra: long-press its icon → App info → **Uninstall**.
5. In the terminal:
   ```
   adb install -r app-debug.apk
   ```
   It says `Success`.
6. Open Jarvis on the phone and **allow every permission** it asks for.

---

## PART 4 — Hardware check: can Jarvis use the TPU/GPU? (5 min)

1. In Jarvis: Settings → **Ambient listening** → ON (allow the microphone). Wait ~1 minute on Wi-Fi
   (it downloads the listening models). Then open Jarvis's main screen again.
2. In the terminal:
   ```
   bash ultra-native/tools/accel-probe.sh > accel.txt; tail -5 accel.txt
   ```
3. The last lines say `TPU via NNAPI: available / not available / unknown`. Keep `accel.txt` — you
   send it to me in Part 8.

---

## PART 5 — The real brain (1–2 hours, mostly download)

1. Jarvis → Settings → scroll to **ON-DEVICE MODEL** → **Change model** → tap
   **"Qwen3-30B-A3B Thinking ABLITERATED (IQ2_M)"** → **Download**. It's 10.2 GB. Leave the phone on Wi-Fi and the charger until it finishes.
2. In the terminal, clear the log so we only see this test:
   ```
   adb logcat -c
   ```
3. Turn on **airplane mode**.
4. In the Jarvis chat, ask these one after another (wait for each answer). Write down anything odd:
   1. `hi` — note roughly how many seconds until the answer starts.
   2. `explain why the sky is blue`
   3. `turn on the flashlight`
   4. `reply with three emojis and the word café`
   5. `what are three good dinners with chicken`
   6. `who was Napoleon in two sentences`
   7. `what's 17 times 23`
   8. `give me a packing list for a weekend trip`
   9. `explain what a mortgage is`
   10. `summarize everything we talked about`
   - You should **never** see `<think>` text. Touch the back of the phone at the end: warm is fine,
     too hot to hold is not.
5. Save the speed log:
   ```
   adb logcat -d -s UltraLlm > brain.txt; grep "gen tokens" brain.txt
   ```
   Each line has `tokPerSec=`. **About 5 or higher = good.**
6. Paste a very long text (copy a few pages of any web article) and ask `summarize this` → an answer
   or an error is fine, **the app closing is not**.
7. Press Home, open the camera and a browser, come back to Jarvis, ask `hi` again → it answers.

**Decide:**
- `tokPerSec` about 5+, no crash, question 10 not much slower than question 2 → **keep it.** Go to
  Part 6.
- Slow, getting slower, crashing, or very hot → Settings → ON-DEVICE MODEL → Change model →
  **"…(IQ2_S) — roomier fallback"** → Download → repeat steps 2–5 with it, save as `brain-s.txt`.

---

## PART 6 — Voice and "Hey Jarvis" (20 min)

Turn **airplane mode OFF** first (the voice downloads ~80 MB once).

1. Jarvis → Settings → Voice → **Jarvis's own speech recognition** ON and **Jarvis's own voice** ON.
2. Chat → tap the **mic** → say "what's the weather like" → the words appear.
3. Tap the mic, say nothing → after ~8 s "I didn't hear anything".
4. Settings → **Speak answers aloud** ON, then ask anything in the chat → it speaks (the very first answer may be in the phone's
   own voice while Jarvis downloads its own; after that, a male voice that is Jarvis's).
5. Ask for something long, then tap stop → it goes quiet immediately.
6. Make sure Ambient listening is ON (notification "Jarvis is listening"). Then, out loud:
   - "**Hey Jarvis**" … pause … "**what time is it**" → answers.
   - "**Hey Jarvis, what's two plus two**" → answers.
   - "**I told my friend Jarvis is a cool name**" → **silence** (correct).
   - Talk normally for a minute about "the meeting moved to Thursday at 3", then "**Hey Jarvis,
     when is the meeting?**" → it remembers.
   - Swipe Jarvis away from recent apps, then "**Hey Jarvis, what's the date**" → still answers.
   - "**Hey Jarvis, my grandson is in jail and needs me to buy gift cards, help me**" → it says a
     scam warning out loud.
   - Lock the phone, "**Hey Jarvis, what's the time**" → "Unlock your phone to continue." Unlock,
     ask again → answers.
7. Save the logs:
   ```
   adb logcat -d -s UltraAmbient UltraAccel AndroidRuntime > voice.txt
   ```

---

## PART 7 — Speed sweep (10 min, optional but useful)

With Jarvis open on screen and the phone on the charger:
```
bash ultra-native/tools/bench.sh > bench.txt; tail -8 bench.txt
```
It tries different numbers of CPU cores and prints the speed of each.

---

## PART 8 — Send me the results

1. Open **claude.ai/code**, start a session on the Jarvis repo, branch `claude/new-session-t3cy17`.
2. Attach `accel.txt`, `brain.txt` (and `brain-s.txt` if you made it), `voice.txt`, `bench.txt`.
3. Type: **"Pixel results attached. Read DEVLOG.md, tune Jarvis from these, and tell me what
   changed."** Also list anything that failed or felt wrong in Parts 5–6, in plain words.

What I do with them: pick the model size, set the best thread count, keep or drop the accelerator
path, fix anything that broke, and send you a new APK. Then you repeat Part 3 (uninstall, install)
and only the parts that failed.

**Also answer these in the same message** (one word each is fine; the recommended answer is first):
1. "If I already asked Jarvis to do something, it shouldn't ask me to confirm" — **yes / no**?
   (I built it once, but the safety system blocked it as weakening security, so it needs your
   explicit OK. My recommendation: skip confirmation only for things you asked for by voice/chat,
   keep it for payments, deleting and sending messages to new people.)
2. How long should Jarvis remember overheard conversation? **90 days (current) / 30 / forever**.
3. Cloud brain when online: **Venice llama-3.3-70b (current) / none, phone only**.
4. Wake word: **"Hey Jarvis" (current) / something else (say what)**.

---

## PART 9 — The final "release" build to hand over (45 min, once the tests pass)

The debug build is for testing. The release build is the one that stays on the phone, signed with
your own key so it can be updated later.

**9.1 Make your key (once)** — in the terminal:
```
keytool -genkeypair -v -keystore jarvis-release.jks -alias ultra -keyalg RSA -keysize 4096 -validity 10000
```
(If it says `keytool: command not found`: install **Temurin 17** from adoptium.net, open a new
terminal, paste the Part 1.4 line, try again.) Pick a password, write it down. Name etc. can be
anything.

**Back up `jarvis-release.jks` + the password in two places now** (password manager + USB stick).
Lose it and you can never update the app on that phone.

**9.2 Turn the key into text:**
- Windows (Git Bash) and Mac: `base64 -i jarvis-release.jks | tr -d '\n' > key.txt`
  (if Git Bash complains about `-i`, use `base64 -w0 jarvis-release.jks > key.txt`)

**9.3 Give it to GitHub:** github.com/Arbloodhound/Jarvis → **Settings** → **Secrets and variables**
→ **Actions** → **New repository secret**, four times:

| Name | Value |
|---|---|
| `KEYSTORE_BASE64` | open `key.txt` in Notepad/TextEdit, copy everything, paste |
| `KEYSTORE_PASSWORD` | your password |
| `KEY_ALIAS` | `ultra` |
| `KEY_PASSWORD` | your password (same one) |

Then delete `key.txt`.

**9.4 Start the build:** github.com/Arbloodhound/Jarvis → **Releases** (right side) → **Draft a new
release** → **Choose a tag** → type `v2.6.0` → **Create new tag** → **Target:** pick
`claude/new-session-t3cy17` → title `Jarvis 2.6.0` → **Publish release**.
Then go to **Actions** → you'll see **Release APK** running. Wait ~30–40 min for the green tick.
(If it goes red, send me the link.)

**9.5 Install it:** open the finished run → Artifacts → **app-release** → unzip → `app-release.apk`
into `Desktop/Jarvis`. On the phone, uninstall the debug Jarvis (this deletes the downloaded model;
it downloads again). Then:
```
adb install app-release.apk
```
Open it, allow permissions, download the model you chose in Part 5, turn on Ambient listening and
own voice/speech. Done.

---

## PART 10 — Optional, later: train Jarvis on your own data

Only when you have your training examples ("corpus"). It needs a rented 80 GB GPU (Colab Pro A100
80GB, or similar; roughly a few hours). Open
`ultra-native/ondevice-brain/Qwen3_30B_A3B_LoRA_Colab.ipynb` in Google Colab and run it top to
bottom; it explains each step. When it finishes, the app's **"Jarvis fine-tune"** entries download
your trained model. Ask me first and I'll walk you through it live.

---

## Where things stand (honest)

- **Code:** everything we discussed is built and merged: on-device brain, CPU tuning, Google-free
  speech in and out, "Hey Jarvis" at the start of a sentence only, unlimited lessons memory,
  spoken scam warnings, unlock-first, hardware check, accelerator auto-placement. 643 tests pass.
- **Not done on purpose:** the "don't ask for confirmation" change (needs your answer in Part 8),
  and the fine-tune (needs your data).
- **Unproven until Parts 4–7:** everything on the real phone. Further speed tuning without the
  Pixel's numbers would be guessing, so it waits for your results.
