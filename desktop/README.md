# Jarvis Desktop

Jarvis on your computer. This branches off the Android app (`muse/v5-delta-port`). It keeps the same idea and the same safety rules, rebuilt for a desktop or laptop. It runs as a small program on your machine, and you chat with it in your browser.

It follows OpenClaw's design (a local gateway that clients connect to) and adds the safety layer the phone app already had.

## What it can do

| Area | Tools |
|---|---|
| Files | read, list, find, write (writes go to its own workspace unless you approve a card) |
| Computer | run commands (you confirm each one, except `ls`, `git status` and a few other read-only ones), open apps, open links, clipboard, notifications, screenshots, system info |
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

## Run it

You need Node.js 22 or newer.

```bash
cd desktop
npm install
npm run build
node dist/cli.js serve        # prints a link with your access token; open it
```

Other ways to use it:

```bash
node dist/cli.js chat                       # talk in the terminal
node dist/cli.js ask "what's in ~/Downloads?"
node dist/cli.js where                      # where config, memory and logs live
```

**Local brain:** install Ollama, then run `ollama pull qwen3:8b`. To use another model, set `local.model` in `~/.jarvis/config.json`.

**Cloud brain:** set `ANTHROPIC_API_KEY` (or `cloud.apiKey` in the config), then pick **Cloud** in the page header.

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

- **Proven by tests:** the gate rules, the scam and secret detectors, fast commands, and one full run through the real gateway, agent loop, gate and local-model client against a fake model server. That run covers a confirm card, parallel reads, a declined command and an honest final answer. There are 19 tests and all pass. The chat page was also driven in headless Chromium at desktop and phone widths.
- **Checked, not run live:** the Claude client's request shape, against a mock server. No API key was available.
- **Not yet tried on a real machine:** opening apps, the clipboard, notifications and screenshots on macOS, Windows and Linux desktops. Also untested: how well a real Ollama model calls tools, and whether the DuckDuckGo results parser still matches the live page.

## Next

1. The phone as a thin client of this gateway: the Android app connects over the home network or Tailscale instead of running everything on the phone.
2. Voice ("Hey Jarvis") with local Whisper and Piper, as on the phone.
3. Routines and scheduled jobs.
4. Screen understanding (the model sees screenshots) and UI control.
5. Better memory recall (embeddings).
