# Jarvis — Session Handoff (2026-10-18)

Self-contained pickup doc. If you're a new session (or Dafarus after a context reset), read
this first, then `CLAUDE.md` (operating rules) and the tail of `DEVLOG.md` (full history).

## Where things are

- **Repo**: `Arbloodhound/Jarvis` (private). **Work branch**: `muse/v5-delta-port` → **draft PR #13**.
- **App code**: `ultra-native/app/` (Kotlin, Jetpack Compose, Room). Package `com.agent.ultra`.
- **Build locally** (JVM tests + compile only — native libs build in CI):
  ```
  cd ultra-native && source ~/android-env.sh && export ANDROID_HOME=~/android-sdk
  flock /tmp/jarvis-gradle.lock ./gradlew -q compileDebugKotlin testDebugUnitTest
  ```
  The APK does **not** build locally (no NDK/native here) — only GitHub Actions produces it.
- **Release**: GitHub Actions workflow `release-apk.yml`, run on ref `muse/v5-delta-port` with
  input `{"test_kit":"true"}`. Publishes `jarvis-release.apk` (everyday) + `jarvis-test.apk`
  (has the Jarvis Tests screen) to a `v3.0.x-test` GitHub release.

## Latest state (this session)

Head of `muse/v5-delta-port` is **`50dfa7e`** (phone calls). Release build for it was triggered
and should publish as the next `v3.0.x-test`. Before it: `60d1c97` (model picker) building/published.
Last fully-published release at handoff time: `v3.0.55-test` (commit `2fda739`, pre-picker).

**To see the newest release + assets**: `get_latest_release` on `Arbloodhound/Jarvis`, or
https://github.com/Arbloodhound/Jarvis/releases

### What shipped recently (newest first)
1. **Phone calls** (`50dfa7e`) — `call_phone` tool; the agent can dial. See below.
2. **Model picker** (`60d1c97`) — tap the chat model chip (shows "▾") to switch the brain between
   the cloud model and any downloaded on-device model; applies to chat **and** voice. Switching
   cloud *provider* (Gemini vs Claude = different base URL + key) still happens in Settings → AI PROVIDER.
3. **Memory bank** (`0b97712`, `2fda739`) — librarian promotes durable facts through the verify +
   NLI gate into the claim store; recall reads active claims; Muse's four categories
   (experience/opinion/reflection/world); duplicate note-write retired (a fact is a claim OR a note).
4. **For-You bandit** (`d44c570`) — UCB ranking learns from act/dismiss.
5. **Expandable thinking line** (`c404f01`) — tap "Thinking…" to expand live reasoning (Claude-style).
6. **Mic** (`e6f540c`) — live partial words as you speak + a real stop-recording button.
7. **Floating live diagnostic** (`65f2fac`) — draggable DIAG button in the real app: glows green
   with a 5-min countdown, captures all app logcat + memory, writes a file to Downloads + share sheet.

## The phone-call feature (just built)

The agent can place a real call, wired exactly like the proven `sms_send` path (no new mechanism):

- `AgentController.placeCall(number)` — `Intent.ACTION_CALL` on `tel:` (+ `FLAG_ACTIVITY_NEW_TASK`).
  `CALL_PHONE` is already in `AndroidManifest.xml`.
- Tool `call_phone` in `Tools.kt` dispatch (Communication). Params: `{to: PHONE NUMBER}`.
  Given a name ("call mom") the brain calls `contacts_read` first (catalog says so).
  In `DESTRUCTIVE` and `PERSONAL_TOOLS`.
- Gate manifest `assets/ultra.manifest.json`: `call_phone = {effects:[egress],
  requires:[recipient_traceable(to), spoof_check]}`. `ToolWiringTest` enforces dispatch⇄manifest parity.
- `Brain.kt`: catalog line (COMMUNICATION, "DIALS IMMEDIATELY") + confirm-line ("Call X").
- Settings → a new **"Phone"** capability grants `CALL_PHONE`. `CloudBrainReport` NEEDS map +
  `Experience` comms family updated.
- **Safety**: `call_phone` is EGRESS (risk 40+, above the 15 auto-approve threshold) and
  `recipient_traceable` is a *confirmable* rule. So "call mom" (number from contacts, not in the
  spoken request) shows an **"About to: Call mom"** confirmation card rather than dialing silently;
  a number the user literally said can auto-approve. Identical behaviour to `sms_send`.

**To test on the phone**: install the new APK → Settings, grant **Phone** permission (and Contacts) →
say or type "call <a contact>" → confirm the card → it should dial. Status is
**SOURCE-FIXED BUT RUNTIME-UNPROVEN** until that one call is made.

## Open / next

1. **DIAG capture still needed from Dafarus** — the "acts late" latency (speech/actions slow to
   start) is NOT missing KV-cache reuse (that's already implemented; verified in source, see DEVLOG
   "Batch C"). Real suspects need a phone trace: `<think>` length on the 4B thinking model before the
   first token, cold model load, thermal throttling. **Run the floating DIAG button ~1 min during
   voice use, tap SEND, share the file.** It captures `UltraLlm` (tok/s, "reused=N of M"),
   `UltraRoute` (think budget) and `UltraThermal` — one capture points at the real bottleneck.
2. **401 cloud key** — the key Dafarus had was rejected (not a code bug; the app already trims it).
   Needs a fresh `sk-ant-…` in Settings → AI PROVIDER (base `https://api.anthropic.com/v1`,
   model e.g. `claude-sonnet-5-5`, Offline-only off). See `PROCEED_GUIDE.md`.
3. **Owner-only items** (Claude can't do these): fresh Claude API key; Android OAuth client
   (Connect Google); Spotify client ID; fine-grained GitHub PAT for Obtainium. Steps in
   `PROCEED_GUIDE.md`.

## Standing scope constraint (IMPORTANT — carries forward)

Dafarus once shared a "Muse" document with drug-trafficking / fraud / safety-classifier-evasion
content, then confirmed it was **unrelated** to Jarvis and asked only for legitimate neutral
features. **Do NOT build**: safety-classifier evasion (memory sanitizing, side-chats, codename
swaps), drug-sourcing tooling, carding / crypto-wallet-sweep / payment-gateway infrastructure, or
the onion/Tor/crypto/"cloud stand-in" work tied to that document. Build normal phone-assistant
capabilities only. This exclusion stays in force.

## Project rules (from CLAUDE.md, condensed)

- Never hallucinate; no drift across subsystems; no refactors without root-cause proof; no
  success claims without the exact path proven.
- Every conclusion gets an honesty status: PROVEN / PARTIALLY PROVEN / SOURCE-FIXED BUT
  RUNTIME-UNPROVEN / RUNTIME DISPROVES / CANNOT VERIFY.
- Session end: update DEVLOG.md, commit, state honest status. (Done for this session.)
