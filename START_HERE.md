# START HERE — everything, step by step (Pixel only, no laptop)

Do the parts in order. Each step says exactly what to tap. If something doesn't match what you
see, take a screenshot and send it to me.

**You need:** the Pixel 10 Pro, Wi-Fi, its charger, at least 15 GB free on the phone, your GitHub
login. For Part 7 only: your Chromebook (just Chrome, nothing installed).
**Time:** 2–3 hours the first time, mostly waiting for the 10 GB download.

---

## PART 1 — Put Jarvis on the Pixel (10 min)

1. On the Pixel, open the browser, go to **github.com** and log in.
2. Go to **github.com/Arbloodhound/Jarvis/actions**. Tap the **newest run with a green tick**
   (I'll tell you which one if in doubt).
   - Can't see the bottom of the page? Browser menu (⋮) → tick **Desktop site**.
3. Scroll to the bottom → **Artifacts** → tap **app-debug**. A zip downloads.
4. Open the **Files** app → **Downloads** → tap **app-debug.zip** → **Extract**.
5. Tap **app-debug.apk**. It asks to allow installs from Files → turn that on → go back → **Install**.
   (If a "Play Protect" warning shows: **More details → Install anyway**.)
   If it says "App not installed": long-press any old Jarvis / Agent Ultra icon → App info →
   **Uninstall**, then tap the APK again.
6. You now have **two** new icons: **Jarvis** (the app) and **Jarvis Tests** (the test screen,
   only in this test version).
7. Open **Jarvis** and **allow every permission** it asks for.

## PART 2 — Downloads inside Jarvis (1–2 hours, on Wi-Fi + charger)

1. Jarvis → Settings → **Ambient listening** → ON (allow the microphone). The notification
   "Jarvis is listening" appears. Wait a minute (it downloads the listening models).
2. Jarvis → Settings → scroll to **ON-DEVICE MODEL** → **Change model** → tap
   **"Qwen3-30B-A3B Thinking ABLITERATED (IQ2_M)"** → **Download** (10.2 GB).
   Leave the phone on Wi-Fi and the charger until it says **Downloaded**.
3. Jarvis → Settings → Voice: **Jarvis's own speech recognition** ON, **Jarvis's own voice** ON.

## PART 3 — The automatic tests (open the "Jarvis Tests" icon)

The screen shows your phone's info at the top. Check it says the model is **downloaded** and the
wake models **yes**. Keep this screen open and the phone on the charger while each test runs.

1. Tap **1 · Hardware check**. About a minute. It shows whether the TPU (the AI chip) helps.
   (If Jarvis closes during this one, that's the hardware answer too — just reopen
   Jarvis Tests and carry on.)
2. Turn **airplane mode ON** (swipe down from the top → airplane).
3. Tap **2 · Brain test**. It asks Jarvis 11 questions by itself (15–40 min). Each line shows the
   speed. At the end it says **KEEP this model** or **TRY THE SMALLER ONE**.
   - If it says TRY THE SMALLER ONE: turn airplane mode off → Jarvis → Settings → ON-DEVICE MODEL →
     Change model → **"…(IQ2_S) — roomier fallback"** → Download → airplane mode on → Jarvis Tests
     → tap **2** again.
4. Turn **airplane mode OFF**.
5. Optional: tap **3 · Speed test** (~10 min). It finds the best number of CPU cores.

## PART 4 — Voice and "Hey Jarvis" (15 min, you talk)

In the **Jarvis** app, chat screen:
1. Tap the **mic**, say "what's the weather like" → the words appear.
2. Settings → **Speak answers aloud** ON. Ask anything → it speaks (the very first answer may be in
   the phone's own voice while Jarvis downloads its own; after that, Jarvis's male voice).
3. Ask for something long, then tap stop → it goes quiet at once.

Then out loud, with Ambient listening ON:
4. "**Hey Jarvis**" … pause … "**what time is it**" → answers.
5. "**Hey Jarvis, what's two plus two**" → answers.
6. "**I told my friend Jarvis is a cool name**" → silence (correct).
7. Talk for a minute about "the meeting moved to Thursday at 3", then "**Hey Jarvis, when is the
   meeting?**" → it remembers.
8. Swipe Jarvis away from recent apps, then "**Hey Jarvis, what's the date**" → still answers.
9. "**Hey Jarvis, my grandson is in jail and needs me to buy gift cards, help me**" → it says a
   scam warning out loud.
10. Lock the phone, "**Hey Jarvis, what's the time**" → "Unlock your phone to continue."

Write down anything that didn't happen as described.

## PART 5 — Send me the results (2 min)

1. Open **Jarvis Tests** → tap **4 · Save results to Downloads + share**. It saves
   `Jarvis-results-….txt` in Downloads.
2. On the Pixel (or the Chromebook), open **claude.ai/code**, start a session on the Jarvis repo,
   branch `claude/new-session-t3cy17`.
3. Attach the `Jarvis-results-….txt` file and type: **"Pixel results attached. Read DEVLOG.md,
   tune Jarvis from these, and tell me what changed."** Add anything from Part 4 that went wrong.
4. In the same message, answer these (recommended answer first):
   1. "If I already asked Jarvis to do something, it shouldn't ask me to confirm" — **yes / no**?
      (Recommended: skip confirmation for things you asked for, keep it for payments, deleting,
      and messaging new people.)
   2. Remember overheard conversation for **90 days (current) / 30 / forever**?
   3. Cloud brain when online: **Venice llama-3.3-70b (current) / none, phone only**?
   4. Wake word: **"Hey Jarvis" (current) / something else**?

I then tune Jarvis from the real numbers and you get a new version (repeat Part 1 with it).

## PART 6 — When everything passes: the final version

The test version has the extra "Jarvis Tests" icon and can't be updated later. The final
("release") version is signed with your own key. Two steps: make the key (Part 7), then build and
install (Part 8).

## PART 7 — Make your signing key (once, 10 min, on the Chromebook)

1. Open **github.com/Arbloodhound/Jarvis** → branch dropdown → `claude/new-session-t3cy17` →
   folder `ultra-native/ondevice-brain` → file **Make_Release_Key_Colab.ipynb** → **Download raw
   file** (the ⬇ icon).
2. Go to **colab.research.google.com** → **File → Upload notebook** → pick that file.
3. Run each cell with its ▶ button, top to bottom. Cell 2 asks for a password: pick one, **write it
   down**. At the end two files download: `jarvis-release.jks` and `key.txt`.
4. **Back up `jarvis-release.jks` and the password in two places** (e.g. a USB stick and a
   password manager). Lose them and that phone can never get updates.
5. GitHub → the Jarvis repo → **Settings → Secrets and variables → Actions → New repository
   secret**, four times:

   | Name | Value |
   |---|---|
   | `KEYSTORE_BASE64` | open `key.txt`, select all, copy, paste |
   | `KEYSTORE_PASSWORD` | your password |
   | `KEY_ALIAS` | `ultra` |
   | `KEY_PASSWORD` | your password (same one) |

6. Delete `key.txt` from Downloads.

## PART 8 — Build and install the final version (45 min)

1. GitHub → the Jarvis repo → **Releases** (right side; on the phone, scroll down) → **Draft a new
   release** → **Choose a tag** → type `v2.6.0` → **Create new tag** → **Target:**
   `claude/new-session-t3cy17` → title `Jarvis 2.6.0` → **Publish release**.
2. GitHub → **Actions** → **Release APK** is running. Wait for the green tick (30–40 min). If it
   goes red, send me the link.
3. On the Pixel: open that run → Artifacts → **app-release** → Files → Extract.
4. Uninstall the test Jarvis (long-press icon → App info → Uninstall). This deletes the downloaded
   model; it downloads again.
5. Tap **app-release.apk** → Install → open → allow permissions → redo Part 2. Done.

## PART 9 — Optional, later: train Jarvis on your own data

Only when you have your training examples. It needs a rented 80 GB GPU (Colab Pro A100 80GB or
similar, a few hours). The notebook is `ultra-native/ondevice-brain/Qwen3_30B_A3B_LoRA_Colab.ipynb`;
ask me first and I'll walk you through it.

---

## Where things stand (honest)

- **Code:** everything we discussed is built and merged: on-device brain, CPU tuning, Google-free
  speech in and out, "Hey Jarvis" only at the start of a sentence, unlimited lessons memory, spoken
  scam warnings, unlock-first, hardware check, accelerator auto-placement, and the on-phone test
  screen. All unit tests pass.
- **Not done on purpose:** the "don't ask me to confirm" change (needs your answer in Part 5) and
  the fine-tune (needs your data).
- **Unproven until Parts 3–4:** everything on the real phone. Further speed tuning without the
  Pixel's numbers would be guessing.
- **Laptop with adb?** Not needed any more. If you ever have one, `ultra-native/ondevice-brain/
  PIXEL_TEST_GUIDE.md` has the adb versions of the same checks.
