> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/APP_OVERVIEW.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# Jarvis (Agent Ultra): plain-language overview

*Written 2026-10-19 for the owner. Short rule of thumb: **built and audited in code** is not the same as **proven on your phone**. Everything below is the first kind unless it says otherwise. Your phone test is what proves it.*

## 1. What it is

An AI assistant that lives on your Pixel (GrapheneOS), written mainly in Kotlin in `ultra-native/`. It can hear you, talk, run tools on the phone, and drive other apps by tapping and typing through Android's accessibility service. It can think with a model that runs **on the phone** (no internet) or with a **cloud** model when you choose it.

The repo now holds three codebases together on `main`:

- **`ultra-native/`** — the Kotlin Android app. This is the shipping app, and the subject of most of this overview.
- **The harness** — a package inside the Android app (`com.agent.ultra.harness`) that carries memory, approvals, scheduling, skills, and connectors in a Muse-style harness layer. It is built and wired into the database (see §5), with two manifest gaps still open (see §8).
- **`desktop/`** — a TypeScript desktop app with its own agent loop, gate, providers, memory, and tools.
- **`src/`** — the older TypeScript app (Expo / React Native lineage). Its live path still exists; its `server/` folder is orphaned and crashes at startup — do not rely on it.

## 2. The brains

The audited code supports these routes. Specific model names, sizes, and speeds from the previous version of this overview were **not re-verified in the 2026-10-19 audits** and are not restated here as facts — check the model picker in your installed build for what is actually offered.

| Brain | What it is | Strengths | Weakness |
|---|---|---|---|
| On-device model | A model running on the phone through llama.cpp (JNI) | Private, works offline | Phone speed and quality cannot be established from a code audit; needs phone evidence |
| Cloud model | A cloud provider reached with your own key (`provider/`: `OpenAiClient`, `ProviderConfig`) | Stronger, faster when the network and key work | Needs internet and a working key; screen context leaves the phone (see §6) |
| Desktop providers | The `desktop/` app: Claude cloud plus a local provider | Desktop gate architecture audited as strong | No cross-provider fallback — a failed turn ends the run |

**Which answers:** routing lives in the Brain (`agent/`, a 12-turn loop). The previous version's claims about which model is the default, and about a separate small "action model," were not re-verified in the 2026-10-19 audits — treat them as *not re-verified* and check Settings in your build.

## 3. What you can ask it to do

At the level the audits verified, the Android app is organised as:

- **`agent/`** — Brain (12-turn loop), Tools, navigation.
- **`gate/`** — the policy Gate, Manifest, contracts, and taint tracking. Deny-by-default.
- **`listen/`** — ambient listening, speech, notes, the Librarian, and claim promotion.
- **`ui/` + `data/`** — screens and Room storage (`UltraDatabase`, now v9 in the merged tree).
- **`local/`** — on-device models via llama.cpp JNI, plus downloads.
- **`net/`** — Tor support (`TorProxy`, `EmbeddedTor`, `TorConnect`, `TorBrowserGuard`). Tor is opt-in and off by default.
- **`provider/`** — cloud provider client and configuration.
- **`research/`** — browse, shopping, and investigate.
- **`bench/`**, vendored sherpa-onnx, unit tests in `src/test/`, and debug bench code in `src/debug/`.

Exact tool counts and tool-by-tool lists from the previous version (including "40 tools" / "53 tools" figures) were not re-verified in the 2026-10-19 audits and have been removed. The honest summary the audits support:

- Phone and screen tools exist and are gated; screen reads and actions fail closed on protected apps.
- Shopping research exists, and checkout has **no purchase path** by design — it can prepare, display, and track only. Shopping means legitimate-product and company verification plus scam screening; the agent informs and asks, and you decide.
- Research / investigate exists (`research/`), including over Tor when Tor is on. Onion URLs never fall back to clearnet.
- Memory, notes, trash, and forget exist, including in the harness layer (see §5).

## 4. Voice

The `listen/` subsystem covers ambient listening, speech, notes, the Librarian, and claim promotion, and the app ships vendored sherpa-onnx. Detailed voice behaviour claims from the previous version (wake-phrase handling, locked-phone behaviour, spoken replies, live transcription details) were not re-verified in the 2026-10-19 audits.

One voice-related finding **was** verified, and it matters: **voiceprint speaker tags default ON against their own doc comment.** That is an open high-severity finding. Until it is fixed, check the speaker-tag setting yourself if you do not want voiceprints stored.

## 5. Memory

Two layers, both present in the merged tree:

- **App memory (`listen/` + `data/`)** — heard lines become notes through the Librarian; claim promotion decides what gets kept.
- **Harness memory (`com.agent.ultra.harness`)** — memory claims carry kind, salience, and confidence, with provenance and supersede chains, hybrid search, and query-time temporal decay. `UltraDatabase` v9 stores them: entities include `ClaimEntity`, `GrantEntity`, `MemoryEmbeddingEntity`, and `BanditArmEntity`, with DAO accessors `claims()`, `claimForget()`, `grants()`, `embeddings()`, and `banditArms()`. This wiring was the audits' one critical defect on the old lineage and is **resolved in the merged tree**.

Known memory defects (open, from the audits): claim promotion can never supersede, and it stores a model paraphrase as the claim's verbatim quote. A claim checker and a "For You" feed exist in the harness (ClaimVerifier lenses fail closed; the feed uses a bandit), but do not over-read that: promotion quality is exactly where the defects sit.

You can tell it to forget something; deleted items sit in the trash until **you** empty it in settings. The agent has no way to empty or purge the trash itself — that is structural and reflection-tested.

## 6. Safety built in

Verified in the 2026-10-19 audits:

- A **policy gate** checks every tool call, deny-by-default. If the manifest fails to load, everything is denied. Taint blocks can neither be auto-approved nor overridden by the operator.
- **Consequential actions stop at confirmation.** Calls, texts, sends, posts, purchases, and deletes: the agent informs and asks; you decide. Purchases always need fresh approval, and no lasting grant covers a purchase at either layer. Approval tokens are bound to the exact sorted-args key.
- **Typing into password fields is refused.** Screen taps are tied to the snapshot they were planned from; stale screens are refused.
- **One-time codes are hidden from the cloud model.** SMS / email codes and reset links travel only as opaque placeholder references (10-minute expiry), scrubbed by the harness SecretScrubber. Credentials live in the platform vault (Keystore, AES-GCM); the agent sees opaque handles only. No hardcoded secrets were found in the 45 harness files.
- **Tor fails closed.** Three independent guards in `TorProxy`; no clearnet fallback anywhere; the only fallback is in-app Tor → Orbot. Tests assert it.
- **Human verification is yours.** CAPTCHAs and similar checks are parked for you with the exact link / action — never solved or bypassed.
- The agent **cannot see the approval card and cannot revoke grants**; you manage grants in settings. Subagents are depth-1 by construction.

Open safety findings you should also know (all from the audits, all still open post-merge): recipe runs can self-confirm steps past the Gate; Gate secret patterns miss phone-shaped secrets (bare one-time codes / cards never taint); the cloud API key sits in plaintext SharedPreferences; model (GGUF) downloads are verified by size only, with no integrity check before native load; the Kotlin `screenshot` tool bypasses the protected-app check; the ActionGate has a single global waiter, so concurrent Brains can complete each other's questions; raw-coordinate taps are ungated when no label is within 200px. On desktop: tool arguments are broadcast unredacted to all clients, screenshots go to cloud Vision ungated, and macOS / Windows screen control reports success for actions it did not perform. The TypeScript live path bypasses its own SafetyChecker / PermissionBroker.

**Not built, on purpose:** the shopping layer has no purchase path, and products / shopping stays verification and scam screening for legitimate products and companies only. "Plugs" means known non-scammers / trusted sources.

## 7. Audit state (2026-10-19)

- Pass 2 (main lineage + harness delta): 533 of 536 files read — **119 defects (1 critical, 16 high, 41 medium, 61 low)**, 4 broken-reference clusters, ~70 dead-code candidates.
- Supplement (the `ccr` branch, 118 files): **87 findings (0 critical, 12 high, 31 medium, 44 low)**.
- Together that is 206 findings across two audits of *different* snapshots — not a single deduplicated count for the merged tree.
- Post-merge: **0 known criticals** (the one critical was the database wiring in §5, now resolved).
- Per-subsystem verdicts in one line: Tor (`net/`) and `UltraDatabase` (`data/`) work as designed; `agent/`, `gate/`, `listen/`, `local/`, `provider/`, `research/`, bench / tools, harness internals, the desktop app, and the TypeScript live path all **work with defects**; Kotlin unit tests (2 files), the TypeScript `server/`, and harness manifest wiring are **broken**.

Earlier claims in this overview about test totals (including a "1633 passing checks" figure) were not re-verified in the 2026-10-19 audits and have been removed. **CI has not compiled the merged tree** — no test total for the merged tree exists yet.

## 8. What it cannot do (yet) / known gaps

- Nothing in the merged tree is proven to compile or run until CI runs. That is gap zero.
- Two harness components — `SchedulerReceiver` and `SpotifyRedirectActivity` — exist but are **not registered** in `AndroidManifest.xml`, so those paths cannot fire as wired.
- Kotlin unit tests cannot run clean: two test files import a debug-only class.
- The TypeScript `server/` cannot be used: orphaned, no routes, crashes at startup.
- About 70 dead-code candidates exist (zero call sites). Some may still be live through reflection, JNI, or framework callbacks — candidates, not confirmed dead code.
- Connector setup (Google, Spotify) still needs account setup only you can do; the harness connectors exist in code (Gmail, Google Calendar / Docs / Drive, Spotify), but code existing is not an account connected.
- Phone performance, voice quality, and on-device screen-driving accuracy cannot be judged from the audits at all. Any claim about them needs labelled phone evidence.

Problems reported from phone logs in the previous version (slow replies, speech-recognition CPU contention, voice stutter, a rejected cloud key) were not re-verified in the 2026-10-19 audits; they are kept here only as *not re-verified* history, not as current status.

## 9. Settings worth knowing

Setting names and defaults from the previous version were not re-verified in the 2026-10-19 audits, with two exceptions worth checking first in any build:

- **Speaker / voiceprint tags** — default ON in audited code, against the doc comment. Turn off if unwanted.
- **Tor** — opt-in, default OFF. Leave off unless you are deliberately using the Tor research paths.

For everything else (offline-only behaviour, model choice, permissions for phone / texts / contacts / voice / location, thinking and voice options), read the Settings screen in your installed build; this overview no longer asserts defaults the audits did not check.

## 10. Where things live

- Repo: **github.com/Arbloodhound/Jarvis**, branch **`main`** (tip `c8043e1c` after consolidation). The previous version pointed at a feature branch and a draft PR; both are superseded by the consolidation. `claude/shopping-integration` is also current.
- Consolidation record: `MERGE_LOG_2026-10-19.md`. Audit reports: the 2026-10-19 code audits (pass 2 + supplement) — see `ARCHITECTURE.md` for the merged verdicts.
- Handoff docs: `HANDOFF_2026-10-19.md`, `DEVLOG.md`, `CLAUDE.md` (the rules the AI follows).
- Four branches remain unmerged by design (three empty tip commits; one superseded work-in-progress snapshot). No branch was deleted.

## 11. Next steps (smallest first)

1. Run CI on the merged tree. Until it compiles, every other status is provisional.
2. Register the two missing manifest components, or remove them deliberately — do not leave them half-wired.
3. Fix the two broken Kotlin unit-test files so the test suite can run.
4. Install a CI-built build on the phone and run the on-phone checks (`START_HERE.md` / `DEPLOY_GUIDE.md`). Phone evidence is the only thing that can promote any claim in this overview from "audited in code" to "proven."
