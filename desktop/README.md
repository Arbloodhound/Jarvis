# Jarvis Desktop

Jarvis on your computer. This branches off the Android app (`muse/v5-delta-port`). It keeps the same idea and the same safety rules, rebuilt for a desktop or laptop. It is a **native desktop app** for Windows, macOS and Linux, with its own window, a tray icon, and a hotkey that brings it up from anywhere.

It follows OpenClaw's design (a local gateway that clients connect to) and adds the safety layer the phone app already had.

## What it can do

| Area | Tools |
|---|---|
| Files | read, list, find, write (writes go to its own workspace unless you approve a card) |
| Computer | run commands (you confirm each one, except a few read-only ones like `ls`, `pwd` and `df`), open apps, open links, clipboard, notifications, screenshots, system info |
| Web | search (DuckDuckGo), read pages |
| Memory | remember, recall, forget (forgotten facts go to a trash first) |

**Fast commands** skip the model completely: "open github.com", "open Spotify", "remember that…", "what do you remember", "take a screenshot", "what time is it". Questions and multi-step requests go to the model.

**Two brains**, the same as on the phone:
- **On this computer** (default): any OpenAI-compatible model server, such as [Ollama](https://ollama.com), llama.cpp `llama-server` or LM Studio. Private, and works offline.
- **Cloud**: Claude (default model `claude-opus-5-5`), using your own API key.
- **Offline only** blocks the cloud brain and every web tool.

## Where it beats OpenClaw

| | OpenClaw | Jarvis Desktop |
|---|---|---|
| Prompt injection (a web page or email telling the agent what to do) | exec approval policies | **Provenance gate**: a link to open or a file to change must come from *your* words, or you confirm it on a card. Text the agent read from your files can't be sent out without a card. |
| Keys and one-time codes | not specially handled | Detected in anything it reads, hidden from the cloud model, and **never** sent out, even if you click Allow |
| Scams | none | Checks emails and pages for scam patterns (ported from the phone) and won't contact anything in a flagged message without you |
| Local network | depends on tools | Web tools can't reach `localhost` or your LAN, so a web page can't use it to reach your router or the gateway itself |
| Gateway | token | token, bound to 127.0.0.1, plus Host and Origin checks (blocks other websites and DNS rebinding) |
| Setup | large monorepo, many extensions | one folder, three runtime dependencies |
| Obvious commands | go through the model | skip the model (instant) |
| Audit | logs | every tool decision in `~/.jarvis/audit.jsonl` (the call, the gate's verdict, your answer) |

What OpenClaw has that this does **not** have yet: chat-app channels (WhatsApp, Telegram, Slack…), a plugin and skills ecosystem, voice, scheduled jobs, phone clients, browser automation, and years of real-world use.

## Install

Download the installer for your computer from the PR's CI run (artifacts `jarvis-Windows`, `jarvis-macOS`, `jarvis-Linux`). Once a `desktop-v*` tag is pushed, the installers are also attached to a draft GitHub release.

| Computer | File | Note |
|---|---|---|
| Windows | `Jarvis-…-win-x64.exe` | Not code-signed yet: SmartScreen shows "unknown publisher"; choose **More info → Run anyway**. |
| Mac | `Jarvis-…-mac-arm64.dmg` (Apple silicon) or `…-x64.dmg` (Intel) | Not notarized yet: the first time, right-click the app → **Open**. For screenshots, allow Jarvis under System Settings → Privacy & Security → Screen Recording. |
| Linux | `Jarvis-…-x86_64.AppImage` or `…-amd64.deb` | AppImage: `chmod +x` and run. |

## Using it

- **Ctrl+Shift+J** (Cmd+Shift+J on a Mac) shows or hides Jarvis from anywhere.
- Closing the window keeps Jarvis running in the **tray** (menu bar on a Mac). The tray menu has: Show, Stop, the brain choice, Offline only, Start at login (Windows and Mac), Open Jarvis folder, and Quit.
- When Jarvis needs your OK and its window is hidden, it comes to the front and posts a notification.
- Clipboard, notifications, screenshots and opening links use the operating system directly through the app. The command-line version falls back to system tools.

**Local brain:** install Ollama, then run `ollama pull qwen3:8b`. To use another model, set `local.model` in `~/.jarvis/config.json`.

**Cloud brain:** set `ANTHROPIC_API_KEY` (or `cloud.apiKey` in the config), then pick **Cloud** in the header or the tray menu.

## From source

You need Node.js 22 or newer.

```bash
cd desktop
npm install
npm run app          # build and open the desktop app
npm run dist         # build an installer for this computer into release/
npm run app:self-test  # start the app, check the native pieces, quit
```

Without the app window, the same agent runs as a terminal program or a local web page:

```bash
node dist/cli.js chat                       # talk in the terminal
node dist/cli.js ask "what's in ~/Downloads?"
node dist/cli.js serve                      # chat page in your browser
node dist/cli.js where                      # where config, memory and logs live
```

## Chromebook / Chrome OS (and Chrome OS Flex)

Chrome OS runs Linux apps inside a container called **Crostini** ("Linux development environment", which you turn on once in Settings → Advanced → Developers). Jarvis runs there two ways:

**Simplest — run it, open it in Chrome (best for most Chromebooks):**
```bash
# inside the Linux terminal (Crostini):
cd desktop && npm install && npm run build
node dist/cli.js serve        # prints http://127.0.0.1:PORT/#token=…
```
Open that link in the Chrome browser. This skips the desktop app entirely, so there's no tray or hotkey to worry about, and it's light on RAM. The gateway only listens on this computer and needs the token, so it's safe.

**As the desktop app:** install the Linux build (the `.deb`, or the AppImage with `chmod +x`) in Crostini. It runs, but note:
- Crostini often has **no system tray** — Jarvis detects this, keeps the window open, and **closing the window quits** (instead of hiding to a tray that isn't there).
- The **global hotkey** and **start-at-login** may not work inside the container.
- **Screenshots** capture only the Linux container's display, not the whole Chrome OS screen, so `look_at_screen` sees just Jarvis's own window there.

**Which brain:** most Chromebooks don't have the RAM or GPU for a large local model. Use the **Cloud** brain (your Claude key) or a small quantized local model (e.g. a 1–3B model in Ollama). Chrome OS Flex on older PCs is the same story — if it has Crostini, the above works; otherwise use the cloud brain.

## Your files

Everything lives in `~/.jarvis` (or wherever `JARVIS_HOME` points):

| File | What |
|---|---|
| `config.json` | brain, models, port, writable folders, allowed commands |
| `token` | the gateway's access token (only your user account can read it) |
| `memory.json` | what it remembers, plus the trash |
| `audit.jsonl` | every tool decision |
| `workspace/` | where it can write without asking |
| `SOUL.md`, `USER.md` | optional: its personality and who you are; both are added to its instructions |

## Status

- **Proven by tests:** the gate rules, the scam and secret detectors, fast commands, and one full run through the real gateway, agent loop, gate and local-model client against a fake model server. That run covers a confirm card, parallel reads, a declined command and an honest final answer. There are 19 tests and all pass.
- **Proven on Windows, macOS and Linux (CI, run 39335363833):** the desktop app starts, its window connects, and the OS clipboard, screen capture, notification, tray and global hotkey all work (`--self-test`). The installers build: Windows `.exe`, macOS `.dmg` (Apple silicon and Intel), Linux AppImage and `.deb`. The packaged AppImage also passed the self-test here.
- **Not proven:** the macOS Screen Recording permission prompt on a real Mac (the CI Mac allowed capture without it).
- **Checked, not run live:** the Claude client's request shape, against a mock server. No API key was available.
- **Not yet tried:** using the app day to day on a real desktop; opening apps by name on each OS; how well a real Ollama model calls tools; whether the DuckDuckGo results parser still matches the live page.
- **Not done:** code signing (Windows) and notarization (macOS), which need paid certificates; auto-update; Start at login on Linux.

## Next

1. The phone as a thin client of this gateway: the Android app connects over the home network or Tailscale instead of running everything on the phone.
2. Voice ("Hey Jarvis") with local Whisper and Piper, as on the phone.
3. Routines and scheduled jobs.
4. Screen understanding (the model sees screenshots) and UI control.
5. Better memory recall (embeddings).
