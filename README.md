> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/README.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# Jarvis (Jarvis)

*Last updated: 2026-10-21 (self-hosted rig and desktop features added; the rest is the 2026-10-19 audited state).*

An Android agent that drives real apps on the phone. A language model runs on the device itself, reads the screen through the accessibility service, and taps. When a step is beyond it, it can escalate to a cloud model. A policy gate on the device can refuse the model before anything destructive happens.

This repository is the consolidated Jarvis codebase. After the 2026-10-19 full-branch consolidation, **all work is on `main`** (tip `c8043e1c`). Earlier branch-specific states described in older versions of this file no longer apply.

> **Status caveat — read first.** CI has not yet compiled the merged tree. Kotlin is source-only until CI runs. The 2026-10-19 audits verified the code at grep level (definitions, call sites, imports), not by compilation or by running it on a phone. Treat every status claim in this file with that caveat.

## What's in here

- `jarvis-native/` — the current app. Native Kotlin Android app; this is the shipping app. It contains the harness package `com.agent.jarvis.harness` (see below).
- `desktop/` — a TypeScript desktop app: agent loop, gate, providers, memory, tools, gateway server and CLI, with tests in `desktop/test`.
- `src/` — the TypeScript app of the Expo / React Native lineage. Its live path is `AgentCore` / `BrainExecutor` / `TaskExecutor`; its `server/` directory is orphaned (see "Known open issues").
- `tools/runpod/` — `start.sh`, a one-command self-hosted rig for a rented GPU (RunPod / Vast.ai): local brain + vision on llama.cpp, Jarvis Desktop on a virtual desktop it can drive, Tor, private search, a forecasting server. See `tools/runpod/README.md`.
- `jarvis-native/app/src/main/cpp/` — native bridge code, including llama.cpp through JNI, so on-device models run without a server. *(Build details for the native libraries were not re-verified in the 2026-10-19 audits.)*
- `DEVLOG.md` — what changed, why, and the bugs that mattered. Append-only (started 2026-10-21).
- `HANDOFF_2026-10-21.md` — the latest session handoff (pod rig + desktop features); `HANDOFF.md` — the 2026-10-19 post-merge state.
- `CLAUDE.md`, `SKILLS_REFERENCE.md` — how this repo is worked on.
- `archive/2026-10-19/` — the pre-replacement versions of the living docs, preserved during the 2026-10-19 doc replacement.

Older versions of this file described everything outside `jarvis-native/` as "history, not the product." That framing is superseded: the post-merge tree deliberately carries the Kotlin app, the desktop TypeScript app, and the `src/` TypeScript app together on `main`. The `src/` lineage is not the shipping Android app, but it is part of the consolidated tree and is described as such in `ARCHITECTURE.md`.

## Self-hosted rig and desktop features (2026-10-21)

Jarvis Desktop now runs fully self-hosted on a rented GPU: `tools/runpod/start.sh` brings up a GLM-5.2 brain and a Qwen3-VL vision model on llama.cpp, a noVNC virtual desktop (Chrome, Tor Browser, files, terminal, the Jarvis app), Tor, SearXNG and a Timestral forecasting server, and was used on a RunPod B300 on 2026-10-21.

Desktop features built for it are in open draft PRs (Arbloodhound/Jarvis#25–#31), each with its own tests; the pod runs them as patches until they merge:

- **Always allow** (`jarvis chat --yes`, `autoApprove`) — except purchases and scam follow-ups, which always ask.
- **Tor auto mode** — Tor only for `.onion` or when asked; still fail-closed.
- **Interrupt** — type while it works to switch to a new message.
- **Context compression** — older turns are summarised before the model's window fills; an overflow error is compressed and retried once; `/compress` on demand.
- **Whole-page reading** — `read_browser_page` reads an entire Chrome tab at once; `scroll_read` scrolls and reads any app in one step.
- **Prediction layer** — `record_number` / `forecast` track numbers over time (from the owner, results or memory) and forecast them with ranges via Timestral; it informs only.
- **`/refresh`** — restart the chat in place with the latest build and config, keeping the conversation.

Status and evidence per feature: `HANDOFF_2026-10-21.md`. CI's macOS/Windows test jobs are red on these PRs for a reason not yet known (Linux is green).

## How it works

- **On-device brain.** On-device models run through llama.cpp via JNI (`local/`). The Brain runs a 12-turn loop (`agent/`).
- **Cloud escalation.** When the on-device model cannot do a step, the request can go to a cloud model on your own API key (`provider/`: `OpenAiClient`, `ProviderConfig`). No key ships in this repo. The desktop app uses Claude cloud plus a local provider, with no cross-provider fallback — a failed turn ends the run.
- **The gate.** Tool calls pass a policy gate that runs on the phone (`gate/`: Gate, Manifest, contracts, taint — deny-by-default). It refuses destructive calls rather than asking the model to behave. Manifest load failure means deny-all. Taint blocks can neither be auto-approved nor operator-overridden. When a tap would commit something — delete, send, pay — the flow stops and asks; the agent informs and asks, and the user decides. Purchases always need fresh approval; no lasting grant covers a purchase, anywhere.
- **The harness.** The package `com.agent.jarvis.harness` inside `jarvis-native` carries the Muse-style harness layer: memory claims with provenance and supersede chains, approval grants, scheduler, lazy skills, notification forwarding policy, screen guard, trash and forget, depth-1 subagents, feed, ideas, artifacts, proactivity, claim verification, an NLI head, a bandit for the For You feed, and connectors (Gmail, Google Calendar / Docs / Drive, Spotify) with a Keystore AES-GCM credential vault and a secret scrubber. See `ARCHITECTURE.md` for the full map.
- **Steps it was shown.** A job a stronger model finished once, and a check outside the app confirmed, can be kept as a short list of steps (recipes / experience). The next request of the same kind plays those steps with your own details. Every played step still goes through the gate. *Note from the audits: recipe runs can self-confirm steps past the Gate — this is a known open high-severity finding, not a guarantee. See "Audit state."*

## Repository state (2026-10-19)

- Consolidation: 58 branches were swept. 51 were already contained in `main`; 2 were merged during consolidation (`muse/v5-delta-port` → `claude/shopping-integration`, `4cff5d07`; `ccr-c9b6f798-uako9v` → `claude/shopping-integration`, `adbd9e84`; then `claude/shopping-integration` → `main`, `ae030b9f`). `claude/shopping-integration` (`adbd9e84`) is also current.
- 4 branches remain unmerged **by design**: `claude/broken-fixes`, `claude/download-all`, `claude/fast-reasoning` (empty tip commits), and `claude/offline-navigation-wip` (a superseded work-in-progress snapshot). No branch was deleted; no history was rewritten.
- Full detail is in `MERGE_LOG_2026-10-19.md`.

## Audit state (2026-10-19, code-only)

Two code-only audits cover different snapshots; their counts must not be added up as a count for the merged tree:

- Pass 2 (main lineage + harness delta): 533 of 536 files read; **119 defects — 1 critical, 16 high, 41 medium, 61 low**; 4 broken-reference clusters; ~70 dead-code candidates.
- Supplement (the `ccr` branch, 118 of 118 files): **87 findings — 0 critical, 12 high, 31 medium, 44 low**, plus 6 overflow candidates in `desktop/src`.

Combined framing: 206 findings across two audits of different snapshots — **not** a deduplicated count of the merged tree.

**Post-merge, there are 0 known criticals.** The pass-2 critical — harness database wiring missing — is resolved in the merged tree: `JarvisDatabase` is v9, its entities include `ClaimEntity` / `GrantEntity` / `MemoryEmbeddingEntity` / `BanditArmEntity`, its DAO accessors include `claims()` / `claimForget()` / `grants()` / `embeddings()` / `banditArms()`, and `MIGRATION_7_8` + `MIGRATION_8_9` are defined and registered.

### Checked clean (verified in the audits)

- Tor is fail-closed, verified in both audits with three independent guards in `TorProxy`; no clearnet fallback anywhere; the only fallback is in-app Tor → Orbot. Tests assert it. Tor is opt-in and off by default.
- Gate is deny-by-default (see above).
- Shopping: onion exclusion is a separate function (`excludeOnion` / `isOnionUrl`) applied by `ShoppingRun`; `ShoppingCheckout` has **no** purchase path (prepare / display / track only), by design.
- Harness: purchases are never grant-coverable at either layer; approval tokens are bound to the exact sorted-args key; Trash has no agent-facing empty / purge; subagents are depth-1 by construction; the secret scrubber and 10-minute placeholder expiry work as documented; the Keystore vault uses AES-GCM; no hardcoded secrets were found in the 45 harness files; claim-verifier lenses fail closed.
- Screen reads and actions fail closed on protected apps; screen memory stores structure only, no screen text or values.
- The desktop gate architecture is strong (layered checks, SSRF defence, redaction in the audit log and confirm cards), despite the desktop findings listed in `ARCHITECTURE.md`.

### Known open issues (post-merge)

- `SchedulerReceiver` and `SpotifyRedirectActivity` exist in the harness but are **not** registered in `AndroidManifest.xml` (harness manifest wiring is broken for these two components).
- Kotlin unit tests are broken in 2 files: `HistoryPolicyTest.kt` and `MemoryBriefTest.kt` import `com.agent.jarvis.bench.TestKitActivity`, which exists only in `src/debug`.
- The TypeScript `server/` under `src/` is orphaned: nothing calls it, it registers no routes, and it crashes at startup in the audited tree.
- ~70 dead-code candidates (zero call sites) — candidates only; reflection, JNI, and framework callbacks can make some of them live.
- Further high-severity findings (Gate secret patterns, plaintext cloud key storage, model-download integrity, voiceprint defaults, desktop redaction and screen-control issues, and others) are listed in `ARCHITECTURE.md` and in the audit reports. Do not treat this README as the full defect list.

## Build it

The native library needs llama.cpp built for arm64 first. It is not vendored here. *(The detailed llama.cpp build steps in the previous version of this file were not re-verified in the 2026-10-19 audits; the outline below is retained from that version.)*

Clone llama.cpp, build it with the Android NDK's CMake toolchain for `arm64-v8a` with `-DGGML_OPENMP=OFF`, then copy `libllama.a` and the `libggml*.a` libraries into `~/llama-android/lib` and the headers into `~/llama-android/include` — that is the path `jarvis-native/app/src/main/cpp/CMakeLists.txt` reads. The llama.cpp commit behind earlier published APKs is not pinned.

```
cd jarvis-native
./gradlew assembleDebug
```

A fresh clone signs debug builds with the Android SDK's own debug key. Release signing needs a `keystore.properties` that is not in this repo, and a release build **fails** without it rather than falling back to a debug key.

Version numbers, APK download locations, test counts, and benchmark results stated in the previous version of this file are **not re-verified in the 2026-10-19 audits** and have been removed rather than repeated. Do not cite them from this file.

## Honest limits

- **CI has not compiled the merged tree.** Nothing in the merged tree should be described as building or passing until CI runs.
- Screen reading depends on what each app exposes to the accessibility service. Apps that draw their own widgets are harder.
- Cloud escalation sends screen context to whichever provider you configure. That is your decision to make per task, and the gate is what stands between a cloud model and a destructive action. Two related audit findings qualify this: the Kotlin `screenshot` tool bypasses the protected-app check, and the desktop app sends screenshots to cloud Vision ungated. Both are open.
- The on-device model's output quality, speed, and phone behaviour cannot be established from the 2026-10-19 code audits. Claims about them require phone evidence, labelled as such.

## Standing rules

These rules govern the agent's behaviour and any description of it:

- The agent informs and asks; it does not permit or forbid. Consequential actions (send, post, purchase, delete) stop at confirmation; the user decides.
- Products / shopping is legitimate-product and company verification plus scam screening only. "Plugs" means known non-scammers / trusted sources.
- Tor is opt-in, default off. Onion URLs never fall back to clearnet. Onion exclusion stays a separate shopping-layer function.
- Human verification (CAPTCHA and similar) is the user's: park with the exact link / action, never solve or bypass.
- Never type into password fields. Screen actions are bound to the snapshot they were planned from; stale screens are refused.
- SMS / email codes and reset links never reach the cloud model (opaque placeholder references only).
- Purchases always need fresh approval. The agent cannot see the approval card and cannot revoke grants; the user manages grants in settings.
- Credentials live in the platform vault; the agent sees opaque handles only. Trash is emptied by the user in settings, never by the agent.

## License

License terms were not re-verified in the 2026-10-19 audits. See `LICENSE` and `NOTICE` in this repository for the current terms before relying on any summary here.
