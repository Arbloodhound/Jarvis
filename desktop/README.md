> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/desktop/README.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# Jarvis Desktop

Jarvis on your computer. This is the TypeScript desktop app in `desktop/` — a native desktop app for Windows, macOS and Linux, with its own window, a tray icon, and a hotkey that brings it up from anywhere. It shares ideas and safety rules with the Android app (`jarvis-native/`), not code.

It follows OpenClaw's design (a local gateway that clients connect to) and adds the safety gate the phone app already had.

> Repository state: `desktop/` arrived via the `ccr-c9b6f798-uako9v` branch merge during the 2026-10-19 full-branch consolidation and is now on `main` like everything else (main tip `c8043e1c`). CI has not yet compiled the merged tree.

## What it can do

| Area | Tools |
|---|---|
| Files | read, list, find, write (writes go to its own workspace unless you approve a card) |
| Computer | run commands (you confirm each one, except a few read-only ones), open apps, open links, clipboard, notifications, screenshots, system info, screen control where available |
| Web | search, read pages |
| Vision | look at screenshots and images via a local vision model or the cloud provider |
| Memory | remember, recall, forget (forgotten facts go to a trash first) |

**Fast commands** skip the model completely: obvious commands such as opening a site or app, remembering a fact, recalling memory, taking a screenshot, or asking the time are handled directly. Questions and multi-step requests go to the model through the agent loop.

**Two brains:**

- **On this computer** (default): any OpenAI-compatible model server, such as Ollama, llama.cpp `llama-server` or LM Studio. Private, and works offline.
- **Cloud**: Claude, using your own API key.
- **Offline only** blocks the cloud brain and every web tool.

Provider selection is per run. There is **no fallback between providers**: if the selected provider fails a turn, the run ends — the desktop app does not silently retry the turn on the other provider. That behaviour was confirmed in the 2026-10-19 supplement audit.

Tor is **not** part of the desktop app. Tor belongs to the Kotlin Android app (`jarvis-native/`); do not describe desktop as having Tor.

## How it works

### Agent loop

The agent loop in `src/agent/loop.ts` has two paths: the fast-command path (no model call) and the model path. The model path is capped by `maxTurns`. A `settle()` step repairs dangling tool calls so an interrupted or truncated turn does not leave the conversation with an unmatched tool call. Every tool call goes through the gate; no tool runs any other way.

### Gate

The gate runs **8 ordered checks** before any tool call executes:

1. secret egress
2. scam follow-up
3. paths
4. commands
5. provenance
6. leak
7. trifecta
8. lasting effects

Confirm cards carry **redacted** arguments. The audit log and confirm cards redact; see the open issue below about the separate broadcast path. The audits describe the desktop gate architecture as strong: layered checks, SSRF defence, and redaction in the audit log and confirm cards (FACTS: "Desktop gate architecture strong").

Standing rule, shared with the rest of Jarvis: the agent informs and asks; it does not permit or forbid. Consequential actions stop at confirmation; the user decides. Purchases always need fresh approval.

### Memory

Memory uses an embeddings store for recall, with word-overlap fallback as described in the pre-merge docs where still applicable. `forget` moves facts to a trash capped at 500 entries; the user empties trash in settings — the agent has no purge path. Details beyond store/recall and the 500-cap trash were not re-verified in the 2026-10-19 audits.

### Gateway

The gateway server binds to 127.0.0.1 with a token, plus Host and Origin checks. The CLI (`serve`, `chat`, `ask`, `where`) and the Electron app share the same agent, gate, and providers.

## Audited state (2026-10-19)

The supplement audit covered the ccr branch snapshot that brought `desktop/` in: 118 files, **87 findings — 0 critical, 12 high, 31 medium, 44 low** (plus 6 overflow candidates in `desktop/src`). Verdict for the desktop app: **works with defects**. Verdict for desktop tests: **works as designed**, with 1 low finding.

Open desktop highs from that audit — still open post-merge unless a later fix is shown:

- The desktop **broadcasts unredacted tool arguments to all clients**. Confirm cards and the audit log redact; the broadcast path does not. Treat connected clients as able to see raw tool args until this is fixed.
- **Screenshots sent to cloud Vision are ungated.** A screenshot can leave the machine to the cloud provider without a gate check.
- **macOS and Windows screen actions report false success** — they report success for actions they did not perform. Screen control on those platforms is therefore not proven by a success return.
- No cross-provider fallback (see above) — listed here because it surprises users; it is the audited behaviour, not a hidden fallback.

CI has not yet compiled the merged tree, so none of the above is a compilation or runtime pass claim. Audit verification was grep-level (definitions, call sites, imports).

## Where it beats OpenClaw

This comparison is carried over from the pre-merge README where it describes the gate design; competitive claims about other products were **not re-verified in the 2026-10-19 audits**.

| | OpenClaw | Jarvis Desktop |
|---|---|---|
| Prompt injection (a web page or email telling the agent what to do) | exec approval policies | **Provenance gate**: a link to open or a file to change must come from *your* words, or you confirm it on a card. |
| Keys and one-time codes | not specially handled | Detected in what it reads, hidden from the cloud model, and blocked from egress by the gate's secret-egress check. |
| Scams | none | Gate includes a scam follow-up check (ported scam signals). |
| Local network | depends on tools | Web tools are defended against reaching localhost / LAN (SSRF defence, noted as strong in the audits). |
| Gateway | token | token, bound to 127.0.0.1, plus Host and Origin checks. |
| Obvious commands | go through the model | skip the model (instant, fast-command path). |
| Audit | logs | tool decisions recorded in `~/.jarvis/audit.jsonl`. |

What OpenClaw has that this does **not** have: chat-app channels (WhatsApp, Telegram, Slack…), a plugin and skills ecosystem, voice, scheduled jobs as a mature feature, phone clients, browser automation at OpenClaw's breadth, and years of real-world use. This gap list is from the pre-merge README and was not re-verified in the 2026-10-19 audits.

## Install

Download the installer for your computer from CI artifacts or a `desktop-v*` draft release, as in the pre-merge README. Signing and notarization status was not re-verified in the 2026-10-19 audits.

| Computer | File | Note |
|---|---|---|
| Windows | `Jarvis-…-win-x64.exe` | Not code-signed yet in the pre-merge docs: SmartScreen shows "unknown publisher"; choose **More info → Run anyway**. Not re-verified in the 2026-10-19 audits. |
| Mac | `Jarvis-…-mac-arm64.dmg` (Apple silicon) or `…-x64.dmg` (Intel) | Not notarized yet in the pre-merge docs: first time, right-click the app → **Open**. For screenshots, allow Jarvis under System Settings → Privacy & Security → Screen Recording. Not re-verified in the 2026-10-19 audits. |
| Linux | `Jarvis-…-x86_64.AppImage` or `…-amd64.deb` | AppImage: `chmod +x` and run. |

## Using it

- **Ctrl+Shift+J** (Cmd+Shift+J on a Mac) shows or hides Jarvis from anywhere.
- Closing the window keeps Jarvis running in the **tray** (menu bar on a Mac), except where there is no tray (see Chromebook below).
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

Chrome OS runs Linux apps inside a container called **Crostini** ("Linux development environment", turned on once in Settings → Advanced → Developers). Jarvis runs there two ways:

**Simplest — run it, open it in Chrome (best for most Chromebooks):**

```bash
# inside the Linux terminal (Crostini):
cd desktop && npm install && npm run build
node dist/cli.js serve        # prints http://127.0.0.1:PORT/#token=…
```

Open that link in the Chrome browser. This skips the desktop app entirely, so there is no tray or hotkey to worry about, and it is light on RAM. The gateway only listens on this computer and needs the token.

**As the desktop app:** install the Linux build (the `.deb`, or the AppImage with `chmod +x`) in Crostini. It runs, but note:

- Crostini often has **no system tray** — Jarvis detects this, keeps the window open, and **closing the window quits** (instead of hiding to a tray that isn't there).
- The **global hotkey** and **start-at-login** may not work inside the container.
- **Screenshots** capture only the Linux container's display, not the whole Chrome OS screen, so screen tools see just the container there.

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

## Status summary

- Desktop app: **works with defects** (supplement audit; 0 criticals in that snapshot; post-merge there are 0 known criticals overall).
- Desktop tests: **works as designed** (1 low). The pre-merge README and handoff cited 19 and then 40 passing tests at different points; the exact current count was not re-verified in the 2026-10-19 audits — run `npm test` for the current count.
- Gate architecture: **strong** per the audits, with the broadcast, Vision, and screen-action highs above still open.
- Not proven in the audits: day-to-day use on a real desktop, real-model tool calling quality, installer signing/notarization, and any CI compilation of the merged tree.
