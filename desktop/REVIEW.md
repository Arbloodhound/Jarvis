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
