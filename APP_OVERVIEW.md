# Jarvis (Agent Ultra): plain-language overview
Written 2026-10-19 for the owner. Short rule of thumb: **built and tested in code** is not the same as **proven on your phone**. Almost everything below is the first kind. Your phone test is what proves it.

## 1. What it is
An AI assistant that lives on your Pixel 10 Pro XL (GrapheneOS), written in Kotlin. It can hear you, talk, run tools on the phone (flashlight, calls, texts, alarms, screen reading), and drive other apps by tapping and typing through Android's accessibility service. It can think with a model that runs **on the phone** (no internet) or with a **cloud** model (Claude) when you choose it.

## 2. The three "brains"
| Brain | What it is | Strengths | Weakness |
|---|---|---|---|
| Main on-device model | Qwen3-4B Thinking, abliterated (your choice, the default) | Private, offline, thinks and answers | Slow on your phone (1 to 3 words a second in your logs) |
| Action model (new) | Small Qwen3 1.7B "memory model" | Faster. Picks tools and drives the screen | Less accurate than the 4B |
| Cloud model | Claude, via your own key | Fast, strongest, all 53 tools | Needs internet and a working key; not private |
**Which answers:** the on-device model by default. Pick a Cloud model with the model chip at the top of chat to use the cloud. Offline only (Settings) blocks the cloud and the web completely.
Other models in the picker: LFM2.5 2.6B and 8B (stock, refusals ON; not the default), Gemma, Llama, Qwen2.5, Phi and more.

## 3. What you can ask it to do
**On-device (no cloud needed), 40 tools:**
- Phone controls: flashlight, Wi-Fi, Bluetooth, Do Not Disturb, volume, alarm, battery, settings, system and device info, location.
- Apps and screen: open an app, screenshot, home/back/recents/notifications/lock, read the screen text, read it as a picture (OCR), look at it with the vision model, describe it, read a whole page.
- **Calls and texts:** call (a name is looked up in your contacts), answer, hang up, speakerphone, send a text. A **Confirm card** appears before it dials or sends.
- Web: search the web itself, open a link, browse for a task.
- Memory and notes: notes, clipboard, what the phone overheard (`ambient_recall`), forget something, the trash.
- Routines: save your last successful run as a named routine, run, list, delete. Learn by watching you (`watch_me`).
- **Screen control:** "open Chrome and search X", "go on Google and search X" (uses your default browser; there is no Google app on GrapheneOS). The on-device screen driver reads the screen, picks an action, taps/types, checks, repeats.
- **Fast commands:** "flashlight on", "volume 50", "hang up", "call mom" skip the model completely and run at once.
- **Chains:** "call mom and then text Sam" runs several tools, up to 4. A Confirm card or a failure stops the chain.
**Cloud only (the cloud model has everything above plus):** `react_navigate` full screen control, shopping research, Tor, delegating, connectors, artifacts, asking Claude.

## 4. Voice
Say "Hey Jarvis". It listens in the background (a microphone service with a wake lock) and works with the **screen off**. A **locked** phone only runs harmless requests (flashlight, volume, music, alarms, notes). Your replies are spoken with an on-device voice (Piper). The chat mic shows your words **as you speak**, and the stop button keeps what you said.

## 5. Memory
- It remembers what you say and tell it, in notes and a memory bank. A small model sorts and searches it.
- A claim checker stops weak facts from being stored.
- A "For You" feed learns what you act on or dismiss.
- You can tell it to forget something; deleted items sit in the trash until you empty it.

## 6. Safety built in
- A **policy gate** checks every tool call. Calls and texts (anything that reaches another person or leaves the phone) need your **Confirm card**.
- Typing into **password fields is refused.** Screen taps are tied to what is on screen.
- **One-time codes** are hidden from the cloud model.
- A locked phone cannot be used for private things.
- A **scam warning** appears for requests that look like scams (gift cards, "safe account" and so on).
- Everything is logged for audit. The Stop button ends any run.
- **Not built, on purpose:** drug-sourcing tools, ways around safety filters, card or crypto sweeping, and onion work tied to those. This stays excluded. Shopping is "inform and confirm, never auto-buy."

## 7. What we built in this stretch (2026-10-17 to 19)
Phone calls (call/answer/hang up/speaker). Faster speech recognition (sized to the clip). Live words while you talk. Mic stop keeps your words. Full thinking trace you can expand. Local model by default, with a picker that lists on-device first. Offline only is a plain switch again (off by default). Extra on-device tools (screen reads, calls, texts, web, memory, routines). Chains and links. The action model (small model does the actions). "Main model writes the reply" (optional, off). Honest answers when search finds nothing (no more invented sellers). Screen-off support and a battery setting. Fast commands that skip the model. The LFM2.5 models as opt-in choices.

## 8. What it cannot do (yet)
- Fast on-device conversation: replies to open questions are slow until the CPU contention is fixed.
- Talk to the other person on a call in its own voice (Android does not allow it without a phone service like Twilio).
- Screen control that is as accurate as the cloud's: the on-device driver is slow and less reliable.
- Shopping, Tor and the cloud-only tools on the on-device model (left off on purpose).
- Resume a chain after a Confirm card, or keep the thinking under the answer after it arrives.
- Read your PDFs and files; use a new embedding model.
- Use Google or Spotify: those need an account setup only you can do.

## 9. Known problems from your logs
Replies 1 to 3 words a second. Speech recognition fights the brain for CPU. The speed-up draft model often cannot load (RAM margin). The cloud key was rejected (401) and your Wi-Fi sign-in page blocks it. The voice stutters (audio underruns).

## 10. Settings worth knowing
- **Offline only**: blocks cloud and web (off by default).
- **Action model**: the small model does tools and screen (on).
- **Main model writes the reply**: slower but friendlier text after tools (off).
- **Keep working with the screen off**: lets listening survive battery saver.
- **Phone / Texts / Contacts / Voice / Location**: permission switches. "Phone" now also asks to answer calls.
- **Thinking depth**, **voice choice**, **speaker/speed**.

## 11. Where things live
- Repo: github.com/Arbloodhound/Jarvis, branch `muse/v5-delta-port`, draft PR #13.
- Builds: GitHub Actions `release-apk.yml`; downloads at github.com/Arbloodhound/Jarvis/releases (`jarvis-release.apk` everyday, `jarvis-test.apk` with the test screen).
- Handoff docs: `HANDOFF_2026-10-19.md`, `DEVLOG.md`, `CLAUDE.md` (the rules the AI follows).
- Test count: 1633 passing checks in code. None of tonight's changes has run on your phone yet.

## 12. Next steps (smallest first)
1. Install the newest build, do the 5-minute setup, run the six tests.
2. Fix the cloud key if you want the fast brain.
3. Send a DIAG file after trying it; the speed fix depends on it.
4. Setup checklist screen, resume after Confirm, streaming speech recognition, real-task test set.
