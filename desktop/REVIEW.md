> **Post-merge replacement — 2026-10-19.** This is a point-in-time document. Its body is preserved verbatim as the historical record; the status section below states what the 2026-10-19 code audits and the full-branch consolidation (all work now on `main`) changed. The previous version is preserved at `archive/2026-10-19/desktop/REVIEW.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

## Status as of 2026-10-19

- **This review is a point-in-time record.** Its findings-and-fixes tables describe the code as reviewed on 2026-10-19, before the full-branch consolidation. All work is now on `main` (tip `c8043e1c`); `desktop/` arrived via the `ccr-c9b6f798-uako9v` merge. CI has not yet compiled the merged tree, and audit verification was grep-level, not compilation.
- **Later audit result.** The supplement audit of the ccr snapshot (118 files) found 87 findings — 0 critical, 12 high, 31 medium, 44 low. Desktop app verdict: works with defects. Desktop tests verdict: works as designed (1 low). Post-merge there are 0 known criticals overall (the pass-2 critical, harness DB wiring, is resolved in the merged tree). The fix claims in the body below were not re-audited line-by-line against the merged tree in the supplement — treat them as the review's own record, not as a current pass claim.
- **Gate architecture is strong** per the audits (layered checks, SSRF defence, redaction in audit log/confirm cards), but three desktop highs remain open and qualify the body's "fixed" picture: (1) unredacted tool args are broadcast to all clients (confirm cards and the audit log redact; the broadcast path does not), (2) screenshots sent to cloud Vision are ungated, (3) macOS/Windows screen actions report false success for actions they did not perform.
- **Providers:** Claude cloud + local, selection per run, with **no cross-provider fallback** — a failed turn ends the run. The comparison table in the body predates the audits; its claims about other products were not re-verified in the 2026-10-19 audits.
- **Tests are sound** per the supplement verdict above (works as designed, 1 low) — that supports the body's "each fix has a test" approach without confirming any specific test count today.

# Jarvis Desktop: pre-release review (2026-10-19)

Four Haiku agents reviewed the code and researched the field in parallel. Every finding below was then checked against the code, and each fix has a test in `test/`.

## Problems found and fixed

| # | Severity | Problem | Fix |
|---|---|---|---|
| 1 | Critical | A web page could get code run with no card: write a `.git/config` with `core.fsmonitor` into the workspace, then `git status` (allowlisted) runs it. | git removed from the no-card list. |
| 2 | High | The no-card command list matched prefixes, so `git diff --output=FILE` overwrote files, `git diff --no-index … ~/.ssh/id_rsa` and `du --files0-from=` read credentials, and `git branch -D` deleted branches. | Each allowed command has a fixed set of flags; path arguments are checked as sensitive; quotes, globs, `~user` and env assignments need a card. The config key changed to `extraAllowedCommands` (exact text only). |
| 3 | High | Symlinks bypassed the sensitive-path and workspace checks (a downloaded repo with `keys -> ~/.ssh`). | Paths are checked after following symlinks, including links whose target doesn't exist yet. |
| 4 | High | `https://amazon.com:x@evil.example` was read as amazon.com; "github.io" in a request covered `attacker.github.io`. | User names stripped from hosts; hosts must match exactly (or with www.). |
| 5 | High | Secrets the local brain read reached Claude unmasked after switching brains. | Every tool result is masked when the conversation is sent to Claude. |
| 6 | Medium | Web tools could reach localhost and the LAN through IPv4-mapped IPv6 (`[::ffff:7f00:1]`) or DNS rebinding. | Address blocklist built on Node's `BlockList`; the address checked is the address connected to. |
| 7 | Medium | Short leaks (under ~19 characters, such as a password) weren't caught. | Password-like words from private text are tracked; leak window is 12 characters at every offset. Values after `password =`, `token:` and similar are treated as secrets. |
| 8 | Medium | Leaks by encoding or paraphrase (base64, reworded) weren't caught. | Lethal-trifecta rule: once one request has read both your files and the web, anything sent out that you didn't type needs a card. |
| 9 | Medium | A page could plant a lasting instruction with `remember`, or put a command on the clipboard. | After web content is read, `remember` and `clipboard_write` need a card unless your own words say what to store. |
| 10 | Medium | Sensitive paths were case-sensitive and missed GitHub CLI, cargo, Maven, Edge, Brave, keyrings and Windows credentials. | Case-insensitive, longer list, plus Jarvis's own token and config wherever they live. |
| 11 | Medium | "open notes.txt" opened `https://notes.txt`; "remember to call mom" stored a fake fact; "open spotify and play jazz" never reached the model. | File extensions, reminders and compound requests go to the model. |
| 12 | Medium | Two tool calls in one chunk from some local servers merged into one broken call. | Calls are keyed by index, then id. |
| 13 | Medium | A tool call cut off by the length limit could still run (a half-written file). | Cut-off calls are never run. |
| 14 | Medium | The conversation grew forever until the model's context overflowed. | Oldest whole exchanges are dropped past ~150k tokens; "New chat" button. |
| 15 | Medium | A confirm card vanished on reload, leaving Jarvis "working" forever. | Open cards are re-sent on reconnect and declined after 10 minutes. |
| 16 | Medium | A corrupt `config.json` or `memory.json` stopped Jarvis from starting. | The bad file is moved aside, defaults are used, and you are told. |
| 17 | Medium | A crash before a run started left Jarvis permanently busy. | Run setup is inside the cleanup path. |
| 18 | Low | Enter while Jarvis was working stopped the run. | Enter does nothing while busy; only Stop stops. |
| 19 | Low | Windows notifications in the terminal version could run code from the message text. | Values are quoted literals in an encoded PowerShell script. |
| 20 | Low | Web search returned empty snippets; Stop at a card read as "declined"; validation errors were raw JSON; audit log and trash grew forever. | Parser fixed; stop reported as stop; plain error wording; audit log rotates at 10 MB; trash keeps the last 500. |

Not changed: the gateway (loopback, token, Host and Origin checks), the chat page (no HTML injection; all text set as text), and the Electron window settings were reviewed and held.

## Compared with other open-source agents (October 2026)

| | OpenClaw | Goose | Hermes Agent | Jan / AnythingLLM | Jarvis Desktop |
|---|---|---|---|---|---|
| Stars | ~392k | ~55k | ~255k | ~45k / ~67k | private |
| Native app (Win/Mac/Linux) | yes | yes | no (CLI/web) | yes | yes |
| Local models | yes | yes (Ollama) | yes | bundled llama.cpp | yes (any OpenAI-compatible server) |
| MCP / plugins | skills (ClawHub) | 70+ MCP extensions | skills | MCP | **no** |
| Chat apps (WhatsApp, Telegram…) | 20+ | no | 6 | no | **no** |
| Command sandbox | optional, off by default | no | Docker/SSH/Modal | no | **no** (card per command) |
| Default for shell commands | runs without asking | asks (smart approval) | pattern-based approval | n/a | asks, except a few read-only commands |
| Prompt-injection defense | approvals; docs say not a boundary | pattern detection; optional reviewer model (fails open) | pattern-based guards (CVE-2026-9350, -9367) | n/a | deterministic provenance gate + trifecta rule |
| Known incidents | CVE-2026-25253 (token theft via missing Origin check), 341 malicious skills, sandbox escapes, agent editing its own policy | CVE-2026-72718 (malicious .git/config) | approval-code CVEs | AnythingLLM telemetry on by default | none known; this review fixed the same .git/config class before release |

**Ahead:** deterministic, code-enforced rules instead of pattern matching; no plugin marketplace (the source of OpenClaw's malware problem); a gateway that already checks Origin (OpenClaw's worst CVE); secrets blocked even when you approve; scam detection.

**Behind, in order of how much people use it:** MCP/plugins, chat-app channels, a command sandbox, scheduled jobs, browser automation and screen control, voice, embeddings memory, a bundled model engine, signed installers and auto-update.

**Worth copying next:** OpenClaw's "Allow always" bound to the exact command and folder; Claude Code's OS-level command sandbox; Goose's optional second-model reviewer (but failing closed); stripping hidden Unicode before checks (Block's red team); Jan's bundled llama.cpp; AnythingLLM's scheduled tasks.

Research sources are in the session log. Incident counts from secondary sources were not independently verified.

## Second pass: adversarial re-test after the fixes (same day)

A fresh agent attacked the patched gate. It found one critical regression I had introduced and more, all now fixed with tests:

| Severity | Problem | Fix |
|---|---|---|
| Critical | My newline check in the command allowlist was dead (the string was whitespace-normalized before the check), so `ls\nsh evil.sh` ran with no card. | Control characters are rejected on the raw command before normalizing. |
| High | A backslash in a URL (`https://evil.example\@github.com`) was read as github.com by the gate but as evil.example by a browser. | Hosts are parsed with the WHATWG URL parser (same as Node/browsers); user names and backslashes can't spoof. |
| High | `forget`/`remember` output wasn't treated as private, so a forgotten fact could be exfiltrated. | Any tool result not from the web counts as private. |
| High | A secret or private text **base64/percent/hex-encoded** into a URL query slipped past the raw-string checks, even to an owner-named host. | Egress arguments are decoded (percent, base64, base64url, hex) and every decoded form is checked for secrets and private text. |
| Medium | More credential files readable with no card (`/proc/self/environ`, rclone, doctl, GitHub CLI, Firefox logins, Jarvis's own data). | Added to the sensitive list. |
| Low | `ls /root/.s[s]h` glob dodged the path check. | `[` `]` `*` `?` rejected in path arguments. |

Held on re-test: SSRF in every address form (IPv4-mapped IPv6, decimal/octal/short IPv4, DNS rebinding via pinned lookup), workspace confinement through symlinks, sensitive-path reads, raw-secret egress, the trifecta rule, and lasting-effect cards.

## Round 3: feature + competitive review (2026-10-20)

Since the first two rounds the desktop app gained: a **model picker** (any model your server reports), **semantic memory** (any OpenAI-compatible embedding model, with word-overlap fallback), **vision** (a local VLM or the cloud brain reads screenshots and image files), **experimental screen control** (off by default, every action confirmed), and **Chromebook / Chrome OS** support.

### Every feature verified on both brains

`test/integration.test.ts` runs the real agent loop, gate and tools against both a local-shaped and a cloud-shaped model, and asserts each tool category runs identically: system info, file write (workspace), remember + recall (semantic, via a stub embedder), vision (via a stub VLM), and clipboard. It also asserts the gate still cards a sensitive read on the cloud brain and that declining turns into a tool error. Confirmed wiring:

- **Brain switch** is read per request (header chip or tray), so local ↔ cloud applies to the next message; a local turn's tool results are masked before any later cloud request.
- **Offline-only** blocks the cloud brain, the web tools, *and* a non-local embedder — all in one switch.
- **Vision** follows the brain: a configured local VLM keeps it offline; otherwise the cloud model reads the image.
- **Semantic memory** degrades safely: no embedder, a down server, or a model mismatch all fall back to word-overlap rather than failing a request.
- **Screen control** is absent from the tool list unless turned on, and every action is a confirm card.

45 host tests pass; the native app self-test (window, clipboard, screen capture, notification, tray, hotkey) passes on Windows, macOS and Linux in CI; the Chromebook path degrades gracefully (no tray → window stays, closing quits).

### What Jarvis Desktop does better than current clients

- **Injection defense is code, not a prompt or a pattern list.** The provenance gate decides from where a value came, not from how it reads, and the lethal-trifecta rule is an enforced invariant. Peer clients lean on approval prompts, a pattern matcher, or an optional reviewer model that fails open.
- **Fail-closed by default.** Shell commands and anything leaving the machine stop at a card unless they trace to the owner's own words; a declined action is never retried another way.
- **Secrets never leave, even when approved** — including base64/percent/hex-encoded forms, and they're hidden from the cloud model.
- **No plugin marketplace to poison.** Capability is a fixed, audited tool set, so there's no third-party-code supply chain to review.
- **One small, legible codebase** (one folder, three runtime dependencies) versus large monorepos — easier for the owner to actually read and trust.

### Where it's still behind, ranked by how much users rely on it

1. **MCP / tool extensions** — peers plug into dozens of external tools; Jarvis has a fixed set. This is the biggest gap and the natural next feature.
2. **Chat-app channels** (WhatsApp/Telegram/Slack) — none yet.
3. **An OS-level command sandbox** — Jarvis relies on the confirm card, not a container; a sandbox would be defense-in-depth under it.
4. **Scheduled / recurring tasks** — none yet.
5. **Browser automation** beyond fetch — no clicking through logged-in sites.
6. **Voice** — none yet (the phone app has it).
7. **A bundled local model engine** — Jarvis needs an external server (Ollama/llama.cpp); some peers ship llama.cpp in the app.
8. **Signing / notarization and auto-update** — installers are unsigned (warnings) and don't self-update.

### Recommended next steps, in order

1. **MCP client** — the single highest-leverage addition; it closes gap #1 and much of #5 at once, and every tool it adds still passes through the same gate.
2. **Scheduled tasks** — small, high-value, and the gate already covers what they'd run.
3. **An OS sandbox for `run_command`** (the macOS/Linux sandbox-exec / seccomp route), as a layer beneath the card.
4. **Code signing + auto-update**, once there's a certificate.

Honesty status: the feature wiring above is PROVEN by host tests and the cross-platform CI self-test; day-to-day use on a real desktop (and the experimental screen control on a real screen) remains SOURCE-FIXED BUT RUNTIME-UNPROVEN.
