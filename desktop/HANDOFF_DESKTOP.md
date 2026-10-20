# Jarvis Desktop — handoff for the next pod

Written 2026-10-19 by the Opus session that built `desktop/`. Read this, then
`desktop/CLAUDE.md`, `desktop/REVIEW.md`, and the root `CLAUDE.md`. Everything
is pushed; the working tree is clean at the commit below.

## Where things are
- Repo `Arbloodhound/Jarvis` (private). Base branch `muse/v5-delta-port`.
- **Work branch `ccr-c9b6f798-uako9v`** (develop and push here — the task rules require it). Also the mirror branch of the same name exists on `ArRaffleRaffle/Testing`; only Jarvis matters for this work.
- **Draft PR #16** (`https://github.com/Arbloodhound/Jarvis/pull/16`), base `muse/v5-delta-port`. This session is subscribed to its events.
- Head commit: **ce82b1b**. Log (newest first): ce82b1b (CI fix), bba81e6 (vision + screen control), 88353dd (semantic memory + model picker + 2nd-pass gate fixes), 2a0bea0 (20 review fixes), 589f1e1, 88b9a58 (native app), 06c34c7 (initial desktop).
- The whole project is under `desktop/`. The Android app under `ultra-native/` is untouched and must stay that way (separate project, shares ideas not code).

## What this is
A native desktop AI agent (Electron + Node/TypeScript), local-first, OpenClaw-style local gateway + the phone app's safety gate. Runs on Windows/macOS/Linux with installers. Chat page in a window (and a `serve` web mode + terminal `chat`/`ask`). Two brains: any OpenAI-compatible local server (Ollama default) or Claude cloud. See `desktop/README.md`.

## Build & test (do this first to confirm a green baseline)
```
cd desktop
npm install
npm run typecheck && npm test        # expect 40 tests, 0 fail
npm run build                        # -> dist/ (+ dist/ui)
# native app self-test (needs a display; on this Linux box use Xvfb + --no-sandbox):
JARVIS_HOME=$(mktemp -d) xvfb-run -a -s "-screen 0 1280x800x24" npx electron . --self-test --no-sandbox
#   expect {"selfTest":"passed", ...} with window/clipboard/screenshot/tray/hotkey ok
npm run dist                         # builds an installer for the current OS into release/
```
CI: `.github/workflows/desktop.yml` runs typecheck+test+self-test+installer build on ubuntu/windows/macos for every push to the PR. A `desktop-v*` tag drafts a GitHub release of the installers.

## Map of the code (all under desktop/src)
- `agent/loop.ts` — the agent loop. Every tool call goes through `Gate.check` here; nothing bypasses it. Fast commands (`agent/fast.ts`) skip the model. `agent/prompt.ts` builds the system prompt + per-request context (now async, uses the embedder for semantic recall).
- `gate/` — `gate.ts` (provenance gate: the heart of the safety model), `scam.ts` (ScamSignals port + `normHost` via WHATWG URL), `secrets.ts` (keys/OTP + assigned secrets), `audit.ts` (rotating audit log).
- `providers/` — `claude.ts` (official SDK, streaming, redacts every tool result), `local.ts` (OpenAI-compatible streaming + `listModels`), `types.ts`.
- `embeddings.ts` — `/embeddings` client (MRL truncation, cosine). `memory.ts` — facts store with optional per-embedder vectors + semantic `search()` with word-overlap fallback.
- `vision.ts` — image→text via local VLM (`/chat/completions` image_url) or Claude images.
- `tools/index.ts` — all tools + policies. `tools/platform.ts` — per-OS clipboard/notify/screenshot/open + `screenType/screenKey/screenClick` + `useNative()` hook + `psQuote`.
- `server.ts` — gateway (127.0.0.1, token, Host/Origin). `ui/index.html` — chat page (model picker, toggles). `app/main.ts` — Electron main (window, tray, hotkey, `--self-test`). `runtime.ts` — wires it all; `config.ts` — config + `startupWarnings`.

## The safety gate (don't regress this)
`Gate.check` runs, in order: (1) secrets never leave even if confirmed — now checks **decoded** forms (`egressVariants`: percent/base64/hex); (2) scam-target follow-ups always card; (2b) `alwaysConfirm` tools card every time (screen control); (3) paths — sensitive (realpath + case-insensitive, big list) card even to read, writes confined to workspace through symlinks; (4) shell commands — allowlist in `BUILTIN` with checked flags/paths, control chars rejected on the raw string, no git; (5) side-effect targets must trace to the owner's words (WHATWG host match, exact) or be from the web for a fetch; (6) private text leaving (checks decoded variants); (7) lethal-trifecta: once private + web both read, anything leaving the owner didn't type cards; (8) `lastsBeyondRequest` tools (remember/clipboard) card after web reads. Two external review passes are recorded in `desktop/REVIEW.md` — 26 findings fixed, each with a test.

## Task from the user (build in this order). Status:
1. **Model compatibility — DONE & pushed (88353dd).** Model picker (header chip → `/models` list, saved to config), semantic memory via any embedding model (EmbeddingGemma-2 etc.), word-overlap fallback. Embedder is local-only and blocked under offline-only.
2. **Vision + screen control — DONE & pushed (bba81e6).** `look_at_screen`/`look_at_image` (local VLM or cloud). `control_screen` (type/key/click) is OFF by default, experimental, every action cards (`alwaysConfirm`), uses xdotool/ydotool/osascript/SendKeys. **Screen control is RUNTIME-UNPROVEN on real desktops** — only wiring + unavailability paths tested.
3. **Chromebook / Chrome OS Flex — NOT STARTED (next).** Plan below.
4. **Full feature + competitive review across local & cloud — NOT STARTED.** Plan below.

Scope note the user set: the dark-web / Tor / onion monitoring + dark-web classifier + automated action-on-hidden-services pipeline is **excluded** (root CLAUDE.md rule + the user's own prior decision). The model-compatibility layer is generic, so legitimate embedding/vision/rerank models all work; do not build the Tor/dark-web data-source layer.

## NEXT: piece 3 — Chromebook / Chrome OS Flex
Chrome OS runs Linux apps in the **Crostini** container. Plan (small):
- The Linux `.deb`/AppImage already build and install in Crostini. Lead the docs with: for low-RAM Chromebooks, run `node dist/cli.js serve` (or the AppImage) in Crostini and open the printed `http://127.0.0.1:PORT/#token=…` link **in the Chrome browser** — avoids Electron tray/hotkey friction entirely. The gateway already binds loopback + token, so this is safe.
- Make the app degrade where Crostini lacks pieces: in `app/main.ts`, wrap `makeTray()` in try/catch and track `hasTray`; when there's no tray, closing the window should **quit** (not hide-to-tray, or the app becomes unreachable). Global hotkey already warns-not-crashes. Login-item is already hidden on Linux.
- Screenshot inside Crostini captures only the container display, not the Chrome OS host — `look_at_screen`/`screenshot` will be limited; the existing error messages already name the missing tool. Add a short note. (I had just opened `app/main.ts` at the `win.on("close")` handler, line ~109, to make this change when the handoff was requested.)
- Document all of this in a new "Chromebook / Chrome OS" section of `desktop/README.md`.
- Add a test if practical (e.g. the "no tray → close quits" logic refactored to a pure function).

## THEN: piece 4 — full feature + competitive review
- Verify every tool works on both brains (local via a stub `/chat/completions` + `/embeddings` server; cloud shape via a mock as in `test/loop.test.ts`/the claude mock). Confirm vision, embeddings, picker, screen-control-gating, offline-only all behave on each brain.
- Update `desktop/REVIEW.md`'s comparison table with the new capabilities (vision, semantic memory, model picker, screen control) and re-rank "behind" (MCP/plugins, chat channels, sandbox, scheduling remain the real gaps).
- Write a short "what we do better / what to improve" section for the user.

## Known / deferred
- Screen control unproven on real desktops; macOS/Windows backends in `platform.ts` are best-effort (osascript click/key are stubs — revisit with cliclick/nut.js if the user wants it solid).
- No code signing / notarization (needs paid certs) → installers warn "unknown publisher". No auto-update. No Linux start-at-login.
- `npm audit`: 8 moderate, all in build-only deps (electron-builder chain); runtime deps are clean.
- HawkScan: not run (no HAWK_API_KEY).

## Working rules (from root CLAUDE.md — follow them)
Never hallucinate; no drift; no proof-free success claims; give each conclusion an honesty status (PROVEN / SOURCE-FIXED BUT RUNTIME-UNPROVEN / etc.). End each session with a DEVLOG.md entry and a commit. Commit trailers currently: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01Py1dqCPSFLH5cwJry1eDeR` (match whatever the attribution reminder in-session says; it has changed between Opus 5.5 and 4.8). Keep pushing to `ccr-c9b6f798-uako9v`; PR #16 stays the PR. If #16 is already merged when you start, branch fresh from `muse/v5-delta-port` (same branch name) and open a new PR — don't stack on merged history.

## CI watch
PR #16 CI was red on an OS-specific test gap; fixed in **ce82b1b** (pushed). First thing: check PR #16's checks are green on ce82b1b (GitHub MCP `pull_request_read` method `get_check_runs`, owner `Arbloodhound`, repo `Jarvis`, pullNumber 16). If a check is red, read `get_job_logs` with `failed_only:true` and fix before continuing features.
