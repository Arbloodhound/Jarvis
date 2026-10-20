> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/PROCEED_GUIDE.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# Jarvis — how to proceed

*Last updated: 2026-10-19.*

This is the plain-steps guide for getting the latest build running and set up. It matches the consolidated state on **`main`**.

> **What changed in this replacement.** The previous version said it matched branch `muse/v5-delta-port` and pointed at `v3.0.x-test` releases and specific cloud model IDs. All of that is superseded: work was consolidated onto `main` on 2026-10-19 (tip `c8043e1c`), and release tags, APK file names, and provider model IDs were **not re-verified in the 2026-10-19 audits**. Steps below therefore tell you how to recognise the right build in your installed app / repo UI instead of asserting a tag or model name. **CI has not yet compiled the merged tree** — if no CI build of the merged tree exists yet, there is no verified "latest build" to install; say so rather than installing an old branch build.

## 1. Install the APK

- Open the latest release / build for **`main`**: https://github.com/Arbloodhound/Jarvis/releases (or the Actions run for `main`, if releases are not being cut).
- Download the **everyday (release) APK** — or the **test APK** if you want the "Jarvis Tests" screen (hardware checks, on-phone probes, etc.). Exact APK file names were not re-verified in the 2026-10-19 audits; pick by the release notes / artifact description, and note the exact file name you installed in your results.
- Tap the downloaded file to install. If Android blocks it, allow "install unknown apps" for your browser, then tap it again. It installs as an update over the old build; your models stay.
- Standing caution from the audits: model (GGUF) downloads are verified by size only, with no integrity check before native load (open finding). That is about in-app model downloads, not the APK — but if a freshly downloaded model misbehaves, re-download it before troubleshooting anything else.

## 2. Fix the cloud brain (key rejected / 401)

- Make a fresh key in your provider's console.
- In Jarvis: **Settings → AI PROVIDER**
  - Base URL: your provider's API base URL.
  - Model: a current model ID offered by that provider. *(The specific model IDs listed in the previous version of this guide were not re-verified in the 2026-10-19 audits and have been removed — copy the ID from the provider's own console / docs, not from memory.)*
  - Key: paste the whole key.
  - Offline-only: **off** (if you want the cloud brain at all).
- The app strips stray spaces; if it still says "key rejected", the key itself is wrong / expired.
- Never paste the key into chat, an issue, or a results file. Audit note: in the audited Kotlin code the cloud API key was stored in plaintext SharedPreferences — an open high-severity finding. Treat the phone as holding that key in the clear until the finding is fixed: use a revocable, scoped key where the provider allows it, and rotate it if the phone is lost.
- Desktop note: the `desktop/` app's providers are Claude cloud + local, with **no cross-provider fallback** — a failed turn ends the run. A key fix on the phone does not fix the desktop app, or vice versa.

## 3. Switch models with the picker

- On the chat screen, tap the **model chip** at the top (shows the current brain).
- Pick:
  - **Cloud** — uses your configured provider (Settings → AI PROVIDER).
  - **On-device** — any on-device model you've downloaded (Settings → DOWNLOADS to get more).
- It switches the brain for both chat and voice. Switching cloud *provider* means a different base URL + key — set that in Settings → AI PROVIDER, then pick its model in the chip.
- Picker labels and layout were not re-verified in the 2026-10-19 audits; if your build's picker differs, follow the build and note the difference.

## 4. Grant permissions (for full function)

- **Accessibility** (Settings → accessibility): screen reading, tapping, app control — without it the screen tools are offered but can't work. Screen reads / actions fail closed on protected apps by design; that refusal is correct behaviour, not a bug.
- **Microphone**: voice + live transcription in the mic button.
- **Notifications**: proactive "For you" cards. (Harness forwarding goes through a 3-gate NotificationForwardingPolicy.)
- Check **speaker / voiceprint tags** while you are in Settings: in the audited code they default ON against their own doc comment (open high-severity finding). Turn them off if you do not want voiceprints stored.

Known wiring gap that can look like a permissions problem: `SchedulerReceiver` and `SpotifyRedirectActivity` exist in the harness but are **not registered in `AndroidManifest.xml`** in the merged tree. Scheduled wake-ups and the Spotify redirect cannot fire as wired — no permission grant fixes that; it needs a code / manifest fix.

## 5. The latency ("acts late") — one capture needed

- Tap the floating **DIAG** button (bottom-right, draggable). It glows green with a countdown.
- Use voice / do a couple of actions for ~1 minute, then tap it again to stop (or let it finish).
- Tap **SEND** on the button → share the file. That log shows exactly where the time goes (model think time, screen reads, thermal throttling) so the real cause can be targeted.
- DIAG button behaviour was not re-verified in the 2026-10-19 audits (the audits were code-only and grep-level). If the button is missing or behaves differently in your build, record that as the finding instead of working around it.

## 6. Still needs your accounts (only you can do these)

Connector code exists in the harness (`connectors/`: Gmail, Google Calendar / Docs / Drive, Spotify; credentials in a Keystore AES-GCM vault, seen by the agent only as opaque handles) — but code existing is not an account connected. Setup still needs you:

- **Connect Google**: an Android OAuth client ID (Google Cloud console) for Gmail / Calendar / Drive. Note the manifest gap above for the Spotify redirect before troubleshooting a Spotify connection failure.
- **Spotify**: a Spotify app client ID (developer.spotify.com) for the Spotify connector.
- **Obtainium**: a GitHub token (next section).

Human verification during any of these setups (CAPTCHA, "verify it's you") is yours to complete on the phone — the agent parks and waits; it never solves or bypasses it.

## 7. Put Jarvis on Obtainium (auto-updates)

The repo is **private**, so Obtainium needs a token to see releases.

1. GitHub → Settings → Developer settings → **Fine-grained personal access token**:
   - Resource owner: **Arbloodhound**
   - Repository access: **Only select repositories → Jarvis**
   - Permissions → Repository → **Contents: Read-only**
   - Generate, copy the token. (If Arbloodhound is an org, an owner must approve the token.)
2. Obtainium → menu → **Settings → GitHub** → paste the token (or set it under the source's credentials). This is what fixes "APK download no work" on a private repo.
3. Obtainium → **Add App**:
   - App URL: `https://github.com/Arbloodhound/Jarvis`
   - Leave the GitHub **API / base URL blank** (defaults to api.github.com).
   - **Include prereleases:** set this to match how current releases are actually tagged — check the repo's Releases page first. The previous version's tag pattern (`v3.0.x-test`) was not re-verified in the 2026-10-19 audits.
   - APK filter (regex): set it to the exact APK file name shown on the current release (see §1). Do not copy a filter from an old version of this guide.
4. Add → it finds the latest release → Install. Obtainium then checks for new releases and offers the update.

Token hygiene: the token above is read-only for one repo by design. Keep it that way. If you lose the phone, revoke the token on the same GitHub page.
