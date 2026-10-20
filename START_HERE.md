> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/START_HERE.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# START HERE — everything, step by step (Pixel only, no laptop)

*Last updated: 2026-10-19.*

Do the parts in order. Each step says exactly what to tap. If something doesn't match what you see, take a screenshot and keep it with your results.

**You need:** the Pixel 10 Pro, Wi-Fi, its charger, free space on the phone for the model downloads, and your GitHub login. For Part 7 only: your Chromebook (just Chrome, nothing installed).

> **What changed in this replacement (2026-10-19).** All work is now consolidated on `main`. The previous version of this guide pointed at a feature branch (`claude/new-session-t3cy17`); that branch reference is stale and has been removed everywhere below — use `main` and the newest successful build for `main`. Specific model names, download sizes, timings, and version numbers from the previous version were **not re-verified in the 2026-10-19 audits** and are described generically below; check the model picker and the build you actually installed for the current values. **CI has not yet compiled the merged tree**, so a green build for the merged tree may not exist yet — if there is no successful run for `main`, stop and report that instead of installing an older branch build.

---

## PART 1 — Put Jarvis on the Pixel

1. On the Pixel, open the browser, go to **github.com** and log in.
2. Go to **github.com/Arbloodhound/Jarvis/actions**. Open the **newest successful run for `main`**.
   - Can't see the bottom of the page? Browser menu (⋮) → tick **Desktop site**.
3. Scroll to the bottom → **Artifacts** → tap **app-debug**. A zip downloads.
   - Artifact names were not re-verified in the 2026-10-19 audits; if the artifact has a different name, use the debug APK artifact from that run and note the name in your results.
4. Open the **Files** app → **Downloads** → tap the downloaded zip → **Extract**.
5. Tap the **.apk** file. It asks to allow installs from Files → turn that on → go back → **Install**.
   (If a "Play Protect" warning shows: **More details → Install anyway**.)
   If it says "App not installed": long-press any old Jarvis / Agent Ultra icon → App info → **Uninstall**, then tap the APK again.
   From now on, newer test versions install straight over this one and keep everything you downloaded (just tap the new APK → Update).
6. Test builds include two icons: **Jarvis** (the app) and **Jarvis Tests** (the test screen, only in test builds). If your build has only the Jarvis icon, it is not a test build — Parts 3 and 5 need a test build.
7. Open **Jarvis** and **allow every permission** it asks for.

## PART 2 — Downloads inside Jarvis

1. Jarvis → Settings → **Ambient listening** → ON (allow the microphone). The notification "Jarvis is listening" appears. Wait a minute while the listening models download.
   - Code note (2026-10-19 audits): voiceprint speaker tags default ON against their own doc comment — an open high-severity finding. If you do not want voiceprints, check the speaker-tag setting before recording your voice.
2. Jarvis → Settings → scroll to **ON-DEVICE MODEL** → **Change model** → pick the recommended on-device model shown in the picker → **Download**. Leave the phone on Wi-Fi and the charger until it says **Downloaded**.
   - The previous version named a specific 10 GB-class model here. Model catalogue, sizes, and the recommended default were not re-verified in the 2026-10-19 audits — use what the picker in your installed build recommends, and write down which model you picked in your results.
   - Audit caution: in the audited code, GGUF downloads were verified by size only, with no integrity check before native load. That finding is open. If a download behaves oddly, delete it and re-download rather than forcing it to load.
3. Jarvis → Settings → Voice: turn on Jarvis's own speech recognition and Jarvis's own voice, if those switches are present in your build.

## PART 3 — The automatic tests (open the "Jarvis Tests" icon)

The screen shows your phone's info at the top. Check it says the model is **downloaded**. Keep this screen open and the phone on the charger while each test runs.

> **Known breakage (2026-10-19 audits).** Two Kotlin unit-test files (`HistoryPolicyTest.kt`, `MemoryBriefTest.kt`) are broken in the merged tree — they import a debug-only class the unit-test source set cannot resolve. That is a repo test problem, separate from the on-phone Jarvis Tests screen. If the on-phone tests fail, record the exact wording; do not assume the two problems are the same.

1. Tap **1 · Hardware check**. It shows whether the accelerator helps. (If Jarvis closes during this one, that's the hardware answer too — just reopen Jarvis Tests and carry on.)
2. Turn **airplane mode ON** (swipe down from the top → airplane).
3. Tap **2 · Brain test**. It asks Jarvis a set of questions by itself. Each line shows the speed. At the end it says **KEEP this model** or **TRY THE SMALLER ONE**.
   - If it says TRY THE SMALLER ONE: turn airplane mode off → Jarvis → Settings → ON-DEVICE MODEL → Change model → pick the smaller fallback offered in the picker → Download → airplane mode on → Jarvis Tests → tap **2** again.
4. Turn **airplane mode OFF**.
5. Optional: tap **3 · Speed test**. It finds the best number of CPU cores.

Exact question counts, timings, and button labels were not re-verified in the 2026-10-19 audits; follow the labels on your installed build and note any differences.

## PART 4 — Voice and "Hey Jarvis" (you talk)

In the **Jarvis** app, chat screen:
1. Tap the **mic**, say "what's the weather like" → the words appear.
2. Settings → **Speak answers aloud** ON. Ask anything → it speaks.
3. Ask for something long, then tap stop → it goes quiet at once.

Then out loud, with Ambient listening ON:
4. "**Hey Jarvis**" … pause … "**what time is it**" → answers.
5. "**Hey Jarvis, what's two plus two**" → answers.
6. "**I told my friend Jarvis is a cool name**" → silence (correct).
7. Talk for a minute about "the meeting moved to Thursday at 3", then "**Hey Jarvis, when is the meeting?**" → it remembers.
8. Swipe Jarvis away from recent apps, then "**Hey Jarvis, what's the date**" → still answers.
9. "**Hey Jarvis, my grandson is in jail and needs me to buy gift cards, help me**" → it says a scam warning out loud.
10. Lock the phone, "**Hey Jarvis, what's the time**" → "Unlock your phone to continue."

Write down anything that didn't happen as described.

Standing rules behind steps 9–10: the agent informs and asks; it does not permit or forbid. A locked phone only runs harmless requests. SMS / email codes never reach the cloud model. Human verification is always yours — the app must park and ask, never solve it for you.

## PART 5 — Send the results

1. Open **Jarvis Tests** → tap **4 · Save results to Downloads + share** (use the equivalent save/share button in your build if the label differs). It saves a results text file in Downloads.
2. On the Pixel (or the Chromebook), open **claude.ai/code**, start a session on the Jarvis repo, on branch **`main`**.
3. Attach the results file and describe what you ran: which build, which model, and anything from Part 4 that went wrong.
4. If you are asked setup-preference questions in that session, answer them there. The previous version of this guide embedded four preference questions with recommended answers (confirmation behaviour, memory retention, cloud brain choice, wake word); those product decisions are **not re-verified in the 2026-10-19 audits** and are no longer stated here as current defaults — check Settings in your installed build for the values actually in effect.

The tuning then comes back as a new version (repeat Part 1 with it).

## PART 6 — When everything passes: the final version

The test version has the extra "Jarvis Tests" icon. The final ("release") version is signed with your own key. Two steps: make the key (Part 7), then build and install (Part 8).

Release-signing specifics (key file names, secret names, tag format) were not re-verified in the 2026-10-19 audits. The outline below is retained from the previous version — confirm each name against the repo's current release workflow and `ultra-native/ondevice-brain/RELEASE_SIGNING.md` before relying on it.

## PART 7 — Make your signing key (once, on the Chromebook)

1. Open **github.com/Arbloodhound/Jarvis** on branch **`main`** → folder `ultra-native/ondevice-brain` → open the release-key notebook → **Download raw file** (the ⬇ icon).
2. Go to **colab.research.google.com** → **File → Upload notebook** → pick that file.
3. Run each cell with its ▶ button, top to bottom. When it asks for a password: pick one, **write it down**. At the end, the keystore and key files download.
4. **Back up the keystore file and the password in two places** (e.g. a USB stick and a password manager). Lose them and that phone can never get updates signed with that key.
5. GitHub → the Jarvis repo → **Settings → Secrets and variables → Actions → New repository secret** — add the secrets the current release workflow requires (see `RELEASE_SIGNING.md` in the repo for the exact names).
6. Delete any plain-text key file from Downloads once the secrets are saved.

Never paste a keystore password, an API key, or a token into chat, an issue, or a results file.

## PART 8 — Build and install the final version

1. GitHub → the Jarvis repo → **Releases** → **Draft a new release** → choose / create a tag → **Target: `main`** → publish the release. (Exact tag format: follow the repo's existing release tags; the format in the previous version of this guide was not re-verified in the 2026-10-19 audits.)
2. GitHub → **Actions** → the release workflow is running. Wait for the green tick. If it goes red, keep the run link with your results.
   - Reminder: CI has not yet compiled the merged tree (2026-10-19). A red run on the merged tree is new information, not a surprise to work around silently.
3. On the Pixel: open that run → Artifacts → the release APK artifact → Files → Extract.
4. Uninstall the test Jarvis (long-press icon → App info → Uninstall). This deletes the downloaded model; it downloads again.
5. Tap the release **.apk** → Install → open → allow permissions → redo Part 2. Done.

## PART 9 — Optional, later: train Jarvis on your own data

Only when you have your training examples. It needs a rented large GPU (for example Colab Pro). The training notebook lives under `ultra-native/ondevice-brain/`; ask in the repo session first and walk through it there. Notebook file names were not re-verified in the 2026-10-19 audits.

---

## Where things stand (honest, 2026-10-19)

- **Code:** all branches are consolidated on `main` (tip `c8043e1c`). The merged tree contains the `ultra-native/` Kotlin app (including the `com.agent.ultra.harness` package), the `desktop/` TypeScript app, and the `src/` TypeScript app. The harness database wiring that was the audits' one critical defect is resolved in the merged tree (`UltraDatabase` v9); post-merge there are **0 known criticals**.
- **Not proven:** CI has not compiled the merged tree, and the on-phone behaviour in Parts 3–4 is unproven until you run it. Built and tested in code is not the same as proven on your phone.
- **Known open items that can affect this guide:** two harness components (`SchedulerReceiver`, `SpotifyRedirectActivity`) are not registered in `AndroidManifest.xml`; two Kotlin unit-test files are broken; GGUF downloads are verified by size only; voiceprint speaker tags default ON against their doc comment. Full list: `ARCHITECTURE.md` and the 2026-10-19 audit reports.
- **Laptop with adb?** Not needed. If you ever have one, `ultra-native/ondevice-brain/PIXEL_TEST_GUIDE.md` has the adb versions of the same checks (not re-verified in the 2026-10-19 audits).
