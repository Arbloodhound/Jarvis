> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/DEPLOY_GUIDE.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# Jarvis: test, set up, benchmark, deploy

*Last updated: 2026-10-19.*

Follow the steps in order. Each step says what you should see. If you see something else, stop and send a screenshot or the results file.

You need: the Pixel, Wi-Fi, a charger, and time for downloads plus the Feature check running by itself the first time. (Exact durations, APK sizes, and version-number schemes from the previous version of this guide were **not re-verified in the 2026-10-19 audits** and are no longer stated as facts — read sizes, times, and version strings off your actual build.)

> **Consolidation note (2026-10-19).** All work is now on `main` (tip `c8043e1c`). Use only builds produced from `main`. **CI has not yet compiled the merged tree** — until a CI run for the merged tree succeeds, there is no verified deployable build of it, and a failing CI run is a finding to report, not a step to skip. The audits also leave three known breakages in the merged tree that can surface during this guide: two Kotlin unit-test files are broken, `SchedulerReceiver` and `SpotifyRedirectActivity` are not registered in `AndroidManifest.xml`, and the TypeScript `server/` under `src/` is orphaned (it plays no part in phone deployment — ignore it).

---

## Part 1: Install the new build

1. On the phone, open the build link for the current `main` build (Chrome).
   - If you don't see an **Artifacts** section at the bottom, tap ⋮ → **Desktop site**, then scroll down.
2. Tap the test artifact to download it. It downloads as a .zip. *(Artifact names were not re-verified in the 2026-10-19 audits; use the test-build artifact from the `main` run and note its exact name in your results.)*
3. Open **Files** → Downloads → tap the .zip → **Extract**.
4. Tap the **.apk** inside → **Update**.
   - Never choose Uninstall. Updating keeps your downloaded models.
   - If Android warns about unknown apps, allow it for Files.
5. A test build gives you two icons: **Jarvis** and **Jarvis Tests**. If you only have one icon, you installed a non-test build — Parts 3–4 need the test build.

✅ Done when Jarvis opens normally.

---

## Updates in one tap with Obtainium

Set this up once and you never do the zip / Extract / tap-the-APK dance again. Every time a **Release APK** build with the test kit finishes, GitHub publishes it as its own release with plain .apk files (not zipped): the test build (with the Jarvis Tests app) and the release build. Obtainium watches for them and offers the update.

Versioning and retention specifics from the previous version (exact tag patterns, how many releases are kept, build-number schemes) were not re-verified in the 2026-10-19 audits. Before setting the filters below, open the repo's Releases page and copy the **current** tag pattern and APK file names from a real, recent `main` release.

**1. Install Obtainium** (no Google needed): from **F-Droid**, or download the APK from its GitHub releases page (github.com/ImranR98/Obtainium/releases).

**2. Make a read-only key for this one repo** (skip steps 2 and 3 if you've already pasted a token into Obtainium). The Jarvis repo is private, so Obtainium needs a key to see it. In a browser, signed in to GitHub:
1. Your picture → **Settings** → **Developer settings** → **Personal access tokens** → **Fine-grained tokens** → **Generate new token**.
2. Name: `Obtainium`. Expiration: up to a year (put a reminder in your calendar to make a new one).
3. **Repository access** → **Only select repositories** → **Jarvis**.
4. **Permissions** → **Repository permissions** → **Contents** → **Read-only**. Leave everything else as it is.
5. **Generate token** and copy it.

This token can only **read** the Jarvis repo. It cannot change anything, and it cannot see your other repos. If you ever lose the phone, delete the token on that same GitHub page. Never paste the token into chat, an issue, or a results file.

**3. Give the key to Obtainium:** Obtainium → **Settings** → **Source-specific** → **GitHub** → paste it into **GitHub personal access token**.

**4. Add Jarvis:** Obtainium → **Add app** → URL `https://github.com/Arbloodhound/Jarvis`, then in the options below it:
- **Include prereleases**: set to match how current releases are actually published (check the Releases page — see the note above).
- **Filter APKs by regular expression**: match the test APK's exact current file name, e.g. `jarvis-test\.apk` if that is still the published name — confirm it on the Releases page first.
- Tap **Add**.

**After that:** Obtainium notifies you → tap **Update**. Your models and settings stay, because every build is signed with the same key. Each build has a higher version number, so Android always accepts it as an update.

**Check it really installed:** Android shows its own "Update this app?" box after you tap Update in Obtainium. Tap **Update** there too, then wait for "App installed". Then open **Jarvis Tests** → **1 · Hardware check**: the first line shows the app version. If there's no version line, the new build isn't installed. Pull down in Obtainium to refresh and try again.

The time-limited build links (Part 1) still work too, if you ever need them.

---

## Part 2: One-time setup (mostly downloading)

Stay on Wi-Fi and keep the phone plugged in for this part.

### 2a. Download everything
1. Pick the brain first: Jarvis → ⚙ **Settings** → **ON-DEVICE MODEL** → **Change model** → pick the recommended on-device model in the picker. *(The previous version named specific models and sizes here, including an optional newer brain; model catalogue and recommendations were not re-verified in the 2026-10-19 audits. Use the picker's recommendation in your build, and record which model you picked.)*
   - Audit caution: GGUF downloads are verified by size only, with no integrity check before native load (open high-severity finding). If a downloaded model fails to load or behaves strangely, delete and re-download it before drawing conclusions.
2. Then ⚙ **Settings** → **DOWNLOADS** (at the top) → **Download all missing**.
   - It lists every model with its size.
   - The chat screen's **Download all** card does the same thing.
3. Keep the screen on and Wi-Fi on. Leaving Settings doesn't stop it.
4. Done when every line shows **✓**. If a line says **failed**, tap **Download all missing** again (big files carry on where they stopped).

### 2b. Listening and voice
1. Settings → **VOICE** → turn on **Ambient listening**. Allow the microphone when asked.
2. Check the **Wake name**. When you say the wake phrase, a short beep plays: it heard you, say what you want (it uses the media volume). *(Wake-name default was "Jarvis" in the previous version; defaults were not re-verified in the 2026-10-19 audits — confirm what your build shows.)*
3. Optional, for "who said what" tags: tap **Record my voice** and talk normally for 10 seconds. This turns speaker tags on.
   - ⚠️ **Audit finding (open): voiceprint speaker tags default ON against their own doc comment.** Recording your voice is therefore not the only way they can be on — check the setting explicitly, and turn tags off if you don't want voiceprints stored.
   - Don't press it while Jarvis is talking.
   - Pause and timing tags work without voiceprints.
4. Leave **Organise memory while charging** ON. The small model sorts what it heard into notes while the phone charges and sits idle.
5. **Note conversation patterns**: ON if you want the pattern notes. They're written as observations only, never diagnoses.

### 2c. Phone control
1. On the main chat screen, if you see a red **"agent: a11y off"**, tap it and switch Jarvis **on** in Accessibility.
2. Settings → **WHERE THE AGENT MAY GO**: tick the apps Jarvis may open and control.

✅ Done when Settings → DOWNLOADS shows ✓ on every line, ambient listening is on, and there's no red a11y warning.

---

## Part 3: Test and benchmark (runs by itself)

1. Turn **airplane mode ON**. This proves everything works offline.
2. Keep the phone **plugged in** and somewhere cool (not under a pillow). Heat makes it slow itself down.
3. Open **Jarvis Tests** and run these in order:

| Button | What it tells us |
|---|---|
| **1 · Hardware check** | chip, memory, which fast CPU build loaded |
| **2 · Quick brain test** | does the big model answer, and how fast |
| **3 · Speed test** | words per second at different CPU core counts |
| **5 · Feature check** | every feature: PASS / WARN / FAIL / MISSING, with a score |

Button labels, question counts, and durations were not re-verified in the 2026-10-19 audits — follow your build's labels and record differences. Separately: the repo's Kotlin *unit* tests are broken in 2 files (`HistoryPolicyTest.kt`, `MemoryBriefTest.kt`, debug-only import) — that is a repo problem, not something the on-phone Feature check can pass or fail.

4. During **5 · Feature check**:
   - Near the end it asks you to say the wake phrase. Tap **Yes**, then say the wake phrase plus a simple question ("what time is it?") to the phone in your normal voice. Jarvis's own listening should beep and then answer out loud. Wait for the answer.
   - When it asks about the flashlight, tap **Yes, go ahead**. It turns the light on and off by itself to test phone control.
   - If one part looks stuck for a long time, tap **Skip this part**. Don't tap Stop unless you want to end the whole run.
5. When it finishes, tap **4 · Save results to Downloads + share**.
6. Send the file.

✅ Done when you've sent the results file.

### What the Feature check covers
Parts marked **(test data)** check one piece with fixed sample input. The rest use the real thing: your real memory and notes (read only), the real chat, real listening and the real flashlight.
- **Real memory in chat:** asks the chat "What do you remember?" and about something it really heard; fails if it says it has no memory.
- **Notes from your real speech:** how many notes the memory model made this week.
- **Live wake:** you say the wake phrase + a question; it must beep and answer correctly.
- **Brains:** load time, speed, a quiz, and the "copies old answers" bug check.
- **Memory model:** does it turn a sample conversation into the right notes?
- **Memory search:** does it find the right memory by meaning, keyword and "yesterday"?
- **Pattern notes:** observations only, no diagnoses.
- **Voice:** Jarvis speaks a sentence and speech recognition writes it down (accuracy score). Plus wake-phrase detection, in Jarvis's voice and then in yours.
- **Transcript tags:** pause detection, word timing, speaker matching.
- **Phone control offline:** flashlight on and off.
- **Safety:** refuses wrong payment amounts, asks before money or delete actions.
- **Listening status, CPU and heat.**

Two coverage caveats from the audits: (a) claim promotion — part of how heard lines become kept memory — can never supersede and stores a model paraphrase as the claim's verbatim quote (open finding), so "memory works" results should be read with that defect in mind; (b) scheduled behaviour cannot be fully proven while `SchedulerReceiver` is unregistered in the manifest.

---

## Part 4: Fix loop (then you again)

1. The results file gets read. Every FAIL or WARN gets fixed or tuned. The pass marks are first guesses until the phone's real numbers are seen.
2. A new build link comes back, built from **`main`**.
3. You repeat **Part 1** (Update) and **Part 3**. Part 2 isn't needed again; your models and settings stay.
4. Repeat until everything you care about is PASS.

Standing rule for the fix loop: fixes land one defect at a time with proof (see `CLAUDE.md`). The audit backlogs (119 defects in pass 2; 87 findings in the supplement; 206 across both snapshots, not deduplicated) are not a bulk-fix list.

---

## Notes and memory (how to use them)

- **📝 Notes** (chat screen, top row): every note in one place — the ones the memory model makes from what Jarvis hears, plus the ones you make.
  - Filter by To-dos, Plans, People, Facts or Patterns.
  - Search, add or delete notes.
  - Tap **Ask Jarvis about my notes** to ask questions about them.
- **🧠 Memory**: everything Jarvis heard, by day, searchable. **Ask Jarvis about my memory** lets you ask questions about it.
- **Focus** (the chip above the text box): **Normal**, **Memory**, **Notes** or **Memory + notes**.
  - In Memory or Notes, every question is answered from that and nothing else. It shows which note or moment it used, and says so when the answer isn't there.
  - Set it back to Normal for everyday questions.
- **Notes are made on their own.** With the screen off and Jarvis idle (on the charger, or above 50% battery), the memory model writes:
  - a **summary** of every conversation;
  - every plan, to-do, person, fact and decision in it, each labelled with **who said it** and **#tags**;
  - **follow-up** notes when something links to an older note: brought up again, a plan changed, a to-do still open.
  - It also goes back over older memories once and re-notes them this fuller way.
  - **Organise now** on the Notes screen does it straight away.
- **What you tell Jarvis** is remembered and noted too. Settings → VOICE → "Remember what I tell Jarvis" turns that off.
- **By voice or typing**, no menu needed:
  - "make a note of what Sam said about the boiler" searches what it heard, writes one note and saves it.
  - "save that as a note" saves its last answer.
  - "note: buy milk" or "remember that the gate code is 4512" saves a note.
  - "search my notes for Lisbon", "what's in my memory about Sam" or "read me my to-do list" answers from them.
  - "open my notes" or "show my memory" opens the screen. If you say it to the phone with the app closed, Jarvis reads them out.

Harness note (2026-10-19): underneath, the harness memory layer (`MemoryClaims`) stores claims with provenance and supersede chains in `JarvisDatabase` v9, and forgetting moves items to a trash that only **you** can empty in settings — the agent cannot purge it. If forget / trash behaviour differs from that, report it as a bug.

---

## People and your own instructions

- **Settings → PEOPLE**
  - **Your name:** Jarvis calls you by it, and notes say it instead of "Me".
  - **Voices heard:** Speaker 2, Speaker 3 and so on, with a name box for each. Give two voices the same name to join them.
- **Jarvis names people itself:**
  - when someone says "I'm Sam" or "my name is Sam";
  - when you keep calling someone by name ("thanks, Sam") and that voice answers. That needs a few times, so it doesn't guess from one remark.
  - It never renames a voice you named.
- **Or tell it**, by voice or typing: "Speaker 2 is Sam", "the person I was just talking to is Anna".
- **Settings → SYSTEM PROMPT:** type your own instructions, e.g. "keep answers short, I work nights". Both brains follow them. **Show the built-in instructions** shows what Jarvis is told by default.

People / naming behaviour details were not re-verified in the 2026-10-19 audits; treat this section as operating instructions for the build you have, and report mismatches with the exact wording you used.

---

## "Hey Jarvis"

- Say the wake phrase on its own: it beeps and says **"Yes?"**. Then say what you want.
- Or say it all at once: **"Hey Jarvis, take a screenshot."** If the answer takes a few seconds, it says **"On it."** first.
- It has full control of the phone within the apps you allowed (§2c): screenshots, home, back, recent apps, notifications, quick settings, reading the screen, music, settings pages, location, locking the phone, plus everything it could already do. Screen actions are bound to the screen they were planned from; if the screen changed, the action is refused — that refusal is by design.
- With the phone **locked**, it does harmless things only: flashlight, music, volume, alarms, the time, general questions and adding a note. For anything private (memory, notes, messages, the screen, apps, location) it says "Unlock your phone for that one."
- It never types into password fields, and one-time codes never reach a cloud model. If it ever asks you to read out a code for the cloud, stop and report it.

---

## Part 5: Real-life trial (1–2 days)

Before calling it finished, use it normally:
1. Leave ambient listening on during a normal day.
2. Talk to it: "Hey Jarvis, what did I say about … earlier?", "remind me what Sam said yesterday", "turn on the flashlight", "summarize this: <paste>".
3. Charge it overnight so the memory model can sort the day into notes.
4. Next day, ask about yesterday.
5. Report anything that was wrong, slow or creepy. Exact wording of the question and answer helps most.

---

## Part 6: Final deployment

When you're happy with Parts 3–5:
1. Open the **latest `main`** build link again and download the **release** artifact instead of the test artifact.
2. Extract it and tap the .apk → **Update**. Same key, so models and settings stay.
3. **Jarvis Tests** disappears, which is normal: the release build is the clean, everyday version.
4. Keep the release .apk file somewhere safe (Google Drive is fine) as your backup installer.
   - Build links expire, so download it promptly. *(The 14-day expiry figure in the previous version was not re-verified in the 2026-10-19 audits.)*

✅ Deployed.

To test again later, install the test artifact from a newer `main` build as an Update.

---

## If something goes wrong

| Problem | Do this |
|---|---|
| Android says "Jarvis isn't responding" | Tap **Wait**. If it keeps happening, Save results in Jarvis Tests and send them. |
| A download fails or stops | Make sure Wi-Fi is on and airplane mode is off, then Settings → DOWNLOADS → **Download all missing** again. |
| Install says "App not installed" | You probably have an old build signed with a different key. Report it before uninstalling anything. |
| The phone gets hot or slow | Normal under heavy load. Let it cool, keep it plugged in, then run the test again. |
| A test is stuck | **Skip this part**. Still stuck → **Stop the test** → Save results anyway. |
| The flashlight test does nothing | Check the red a11y warning (Part 2c). |
| Scheduled things never fire / Spotify connect loops | Known merged-tree gap: `SchedulerReceiver` / `SpotifyRedirectActivity` are not registered in `AndroidManifest.xml` (2026-10-19 audits). Report it as that known issue; no Settings change fixes it. |
| CI for `main` is red | There is no verified merged-tree build yet (CI has not compiled the merged tree). Keep the run link with your report. |
