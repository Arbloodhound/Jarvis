# Jarvis — how to proceed

This is the plain-steps guide for getting the latest build running and set up. It matches the
features now on branch `muse/v5-delta-port`.

## 1. Install the APK
- Open the latest release: https://github.com/Arbloodhound/Jarvis/releases (newest `v3.0.x-test`).
- Download **`jarvis-release.apk`** (the everyday build) — or `jarvis-test.apk` if you want the
  "Jarvis Tests" screen (button 7 cloud stand-in, hardware checks, etc.).
- Tap the downloaded file to install. If Android blocks it, allow "install unknown apps" for your
  browser, then tap it again. It installs as an update over the old build; your models stay.

## 2. Fix the cloud brain (the 401 you saw)
- Make a fresh key at console.anthropic.com.
- In Jarvis: **Settings → AI PROVIDER**
  - Base URL: `https://api.anthropic.com/v1`
  - Model: `claude-sonnet-5-5` (or `claude-opus-5-5` / `claude-haiku-5-5`)
  - Key: paste the whole `sk-ant-…`
  - Offline-only: **off**
- The app strips stray spaces; if it still says "key rejected", the key itself is wrong/expired.

## 3. Switch models with the new picker
- On the chat screen, tap the **model chip** at the top (shows the current brain, now with a ▾).
- Pick:
  - **Cloud · <model>** — uses your provider; quick-switch between the Claude model ids.
  - **<model> (on-device)** — any on-device model you've downloaded (Settings → DOWNLOADS to get more).
- It switches the brain for **both** chat and voice. Switching cloud *provider* (e.g. Gemini instead
  of Claude) means a different base URL + key — set that in Settings → AI PROVIDER, then pick its
  model in the chip.

## 4. Grant permissions (for full function)
- **Accessibility** (Settings → accessibility): screen reading, tapping, app control — without it the
  screen tools are offered but can't work.
- **Microphone**: voice + the live transcription in the mic button.
- **Notifications**: proactive "For you" cards.

## 5. The latency ("acts late") — one capture I need
- Tap the floating **DIAG** button (bottom-right, draggable). It glows green with a 5-min countdown.
- Use voice / do a couple of actions for ~1 minute, then tap it again to stop (or let it finish).
- Tap **SEND** on the button → share the file to me. That log shows exactly where the time goes
  (model think time, screen reads, thermal throttling) so I can target the real cause.

## 6. Still needs your accounts (I can't do these)
- **Connect Google**: an Android OAuth client ID (Google Cloud console) for Gmail/Calendar/Drive.
- **Spotify**: a Spotify app client ID (developer.spotify.com) for the Spotify connector.
- **Obtainium**: a GitHub token (next section).

## 7. Put Jarvis on Obtainium (auto-updates)
The repo is **private**, so Obtainium needs a token to see releases.
1. GitHub → Settings → Developer settings → **Fine-grained personal access token**:
   - Resource owner: **Arbloodhound**
   - Repository access: **Only select repositories → Jarvis**
   - Permissions → Repository → **Contents: Read-only**
   - Generate, copy the token. (If Arbloodhound is an org, an owner must approve the token.)
2. Obtainium → menu → **Settings → GitHub** → paste the token (or set it under the source's
   credentials). This is what fixes "APK download no work" on a private repo.
3. Obtainium → **Add App**:
   - App URL: `https://github.com/Arbloodhound/Jarvis`
   - Leave the GitHub **API/base URL blank** (defaults to api.github.com).
   - **Include prereleases: ON** (releases are tagged `v3.0.x-test`, which GitHub marks prerelease).
   - APK filter (regex): `jarvis-release\.apk` — or `jarvis-test\.apk` for the test build.
4. Add → it finds the latest release → Install. Obtainium then checks for new `v3.0.x-test`
   releases and offers the update.
