# Jarvis: test, set up, benchmark, deploy

Follow the steps in order. Each step says what you should see. If you see something else, stop and send me a screenshot or the results file.

You need: the Pixel, Wi‑Fi, a charger, and about 1½ hours the first time (most of it is downloads and the Feature check running by itself).

---

## Part 1: Install the new build (5 min)

1. On the phone, open the build link I sent you (Chrome).
   - If you don't see an **Artifacts** section at the bottom, tap ⋮ → **Desktop site**, then scroll down.
2. Tap **app-test-realkey** to download it (about 48 MB). It downloads as a .zip.
3. Open **Files** → Downloads → tap the .zip → **Extract**.
4. Tap the **.apk** inside → **Update**.
   - Never choose Uninstall. Updating keeps your downloaded models.
   - If Android warns about unknown apps, allow it for Files.
5. You should now have two icons: **Jarvis** and **Jarvis Tests**.

✅ Done when Jarvis opens normally.

---

## Updates in one tap with Obtainium

Set this up once and you never do the zip / Extract / tap-the-APK dance again. Every time a **Release APK** build with the test kit finishes, GitHub publishes it as its own pre-release, named like **v2.6.42-test**, with two plain .apk files (not zipped): **jarvis-test.apk** (with the Jarvis Tests app) and **jarvis-release.apk**. The newest 5 are kept. Obtainium watches for them and offers the update.

**1. Install Obtainium** (no Google needed): from **F-Droid**, or download the APK from its GitHub releases page (github.com/ImranR98/Obtainium/releases).

**2. Make a read-only key for this one repo** (skip steps 2 and 3 if you've already pasted a token into Obtainium). The Jarvis repo is private, so Obtainium needs a key to see it. In a browser, signed in to GitHub:
1. Your picture → **Settings** → **Developer settings** → **Personal access tokens** → **Fine-grained tokens** → **Generate new token**.
2. Name: `Obtainium`. Expiration: up to a year (put a reminder in your calendar to make a new one).
3. **Repository access** → **Only select repositories** → **Jarvis**.
4. **Permissions** → **Repository permissions** → **Contents** → **Read-only**. Leave everything else as it is.
5. **Generate token** and copy it.

This token can only **read** the Jarvis repo. It cannot change anything, and it cannot see your other repos. If you ever lose the phone, delete the token on that same GitHub page.

**3. Give the key to Obtainium:** Obtainium → **Settings** → **Source-specific** → **GitHub** → paste it into **GitHub personal access token**.

**4. Add Jarvis:** Obtainium → **Add app** → URL `https://github.com/Arbloodhound/Jarvis`, then in the options below it:
- **Include prereleases**: either way (test builds are now normal releases marked Latest).
- **Filter APKs by regular expression**: `jarvis-test\.apk` (the test build, with the Jarvis Tests app).
- Tap **Add**.

**After that:** Obtainium notifies you → tap **Update**. Your models and settings stay, because every build is signed with the same key. Each build has a higher version number (2.6.&lt;build number&gt;), so Android always accepts it as an update.

**Check it really installed:** Android shows its own "Update this app?" box after you tap Update in Obtainium. Tap **Update** there too, then wait for "App installed". Then open **Jarvis Tests** → **1 · Hardware check**: the first line says **App: 2.6.&lt;number&gt;**. If there's no "App:" line, the new build isn't installed. Pull down in Obtainium to refresh and try again.

The 14-day build links (Part 1) still work too, if you ever need them.

---

## Part 2: One-time setup (20–40 min, mostly downloading)

Stay on Wi‑Fi and keep the phone plugged in for this part.

### 2a. Download everything
1. Pick the brain first: Jarvis → ⚙ **Settings** → **ON-DEVICE MODEL** → **Change model** → **Qwen3-4B Thinking 2507 (Q4_0)**. It's faster than the 8B and scores higher on Qwen's reasoning tests. (Already have the 8B? Pick the 4B anyway; the 8B stays on the phone.)
   - Optional, to try a newer and possibly stronger brain: ⚙ **Settings** → **ON-DEVICE MODEL** → **Change model** → **Qwen3.5-4B abliterated (Q4_K_M) — strongest reasoning, new** → **Download & use** (2.7 GB). Not yet tested on this phone and it gets no speed boost; the 4B Thinking stays the safe default, so switch back if it is slow or answers worse.
2. Then ⚙ **Settings** → **DOWNLOADS** (at the top) → **Download all missing**.
   - It lists every model with its size. The two brains are the big ones (about 3.5 GB together); the rest are small.
   - The chat screen's **Download all** card does the same thing.
3. Keep the screen on and Wi‑Fi on. Leaving Settings doesn't stop it.
4. Done when every line shows **✓**. If a line says **failed**, tap **Download all missing** again (big files carry on where they stopped).

### 2b. Listening and voice
1. Settings → **VOICE** → turn on **Ambient listening**. Allow the microphone when asked.
2. Check that **Wake name** says **Jarvis**. When you say "Hey Jarvis", a short beep plays: it heard you, say what you want (it uses the media volume).
3. Optional, for "who said what" tags: tap **Record my voice** and talk normally for 10 seconds. This turns speaker tags on.
   - Don't press it while Jarvis is talking.
   - Skip it if you don't want voiceprints stored; pause and timing tags work without it.
4. Leave **Organise memory while charging** ON. The small model sorts what it heard into notes while the phone charges and sits idle.
5. **Note conversation patterns**: ON if you want the pattern notes (hedging, promises, stress markers). They're written as observations only, never diagnoses.

### 2c. Phone control
1. On the main chat screen, if you see a red **"agent: a11y off"**, tap it and switch Jarvis **on** in Accessibility.
2. Settings → **WHERE THE AGENT MAY GO**: tick the apps Jarvis may open and control.

✅ Done when Settings → DOWNLOADS shows ✓ on every line, ambient listening is on, and there's no red a11y warning.

---

## Part 3: Test and benchmark (30–60 min, runs by itself)

1. Turn **airplane mode ON**. This proves everything works offline.
2. Keep the phone **plugged in** and somewhere cool (not under a pillow). Heat makes it slow itself down.
3. Open **Jarvis Tests** and run these in order:

| Button | Time | What it tells us |
|---|---|---|
| **1 · Hardware check** | 1 min | chip, memory, which fast CPU build loaded |
| **2 · Quick brain test** | ~5 min | 3 questions: does the big model answer, and how fast |
| **3 · Speed test** | ~10 min | words per second at 4, 5 and 6 CPU cores |
| **5 · Feature check** | 20–40 min | every feature: PASS / WARN / FAIL / MISSING, with a score |

4. During **5 · Feature check**:
   - Near the end it asks you to say the wake phrase. Tap **Yes**, then straight away say **"Hey Jarvis, what time is it?"** in your normal voice, about an arm's length from the phone. It records 5 seconds. This tests your real voice; the earlier wake test can only use Jarvis's own voice.
   - When it asks about the flashlight, tap **Yes, go ahead**. It turns the light on and off by itself to test phone control.
   - If one part looks stuck for more than about 5 minutes, tap **Skip this part**. Don't tap Stop unless you want to end the whole run.
5. When it finishes, tap **4 · Save results to Downloads + share**.
6. Send me the file (paste it or attach it here).

✅ Done when you've sent me the results file.

### What the Feature check covers
- **Brains:** load time, speed, a 6-question quiz, and the "copies old answers" bug check.
- **Memory model:** does it turn a sample conversation into the right notes?
- **Memory search:** does it find the right memory by meaning, keyword and "yesterday"?
- **Pattern notes:** observations only, no diagnoses.
- **Voice:** Jarvis speaks a sentence and Whisper writes it down (accuracy score). Plus "Hey Jarvis" detection, in Jarvis's voice and then in yours.
- **Transcript tags:** pause detection, word timing, speaker matching.
- **Phone control offline:** flashlight on and off.
- **Safety:** refuses wrong payment amounts, asks before money or delete actions.
- **Listening status, CPU and heat.**

---

## Part 4: Fix loop (me, then you again)

1. I read your file. Every FAIL or WARN gets fixed or tuned. The pass marks are first guesses until we see your phone's real numbers.
2. I send you a new build link.
3. You repeat **Part 1** (Update) and **Part 3**. Part 2 isn't needed again; your models and settings stay.
4. We repeat until everything you care about is PASS.

---

## Part 5: Real-life trial (1–2 days)

Before calling it finished, use it normally:
1. Leave ambient listening on during a normal day.
2. Talk to it: "Hey Jarvis, what did I say about … earlier?", "remind me what Sam said yesterday", "turn on the flashlight", "summarize this: <paste>".
3. Charge it overnight so the memory model can sort the day into notes.
4. Next day, ask about yesterday.
5. Tell me anything that was wrong, slow or creepy. Exact wording of the question and answer helps most.

---

## Part 6: Final deployment

When you're happy with Parts 3–5:
1. Open the **latest** build link again and download **app-release** instead of app-test-realkey.
2. Extract it and tap the .apk → **Update**. Same key, so models and settings stay.
3. **Jarvis Tests** disappears, which is normal: the release build is the clean, everyday version.
4. Keep the app-release .apk file somewhere safe (Google Drive is fine) as your backup installer.
   - Build links expire after 14 days, so download it before then.

✅ Deployed.

To test again later, install **app-test-realkey** from a newer build as an Update.

---

## If something goes wrong

| Problem | Do this |
|---|---|
| Android says "Jarvis isn't responding" | Tap **Wait**. If it keeps happening, Save results in Jarvis Tests and send them to me. |
| A download fails or stops | Make sure Wi‑Fi is on and airplane mode is off, then Settings → DOWNLOADS → **Download all missing** again. |
| Install says "App not installed" | You probably have an old build signed with a different key. Tell me before uninstalling anything. |
| The phone gets hot or slow | Normal under heavy load. Let it cool, keep it plugged in, then run the test again. |
| A test is stuck | **Skip this part**. Still stuck → **Stop the test** → Save results anyway. |
| The flashlight test does nothing | Check the red a11y warning (Part 2c). |
