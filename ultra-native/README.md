> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/ultra-native/README.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# Agent Ultra (Jarvis) — ultra-native

The native Kotlin Android app: an agent that runs on the phone, with its own
on-device model path and a cloud-provider path, driving real tools under a
deterministic policy gate. This is the shipping app in the post-merge tree,
alongside the `desktop/` TypeScript app and the older `src/` TypeScript app
(whose `server/` directory is orphaned — see *Status* below).

## What it does

- **Runs a brain loop on the phone.** `agent/` holds the Brain (a 12-turn
  loop), the tool set, and navigation. The model proposes; the program
  decides what may execute.
- **Works on-device or via a provider.** `local/` loads on-device models
  through llama.cpp JNI, with model downloads managed in-app. `provider/`
  holds the cloud client (`OpenAiClient`, `ProviderConfig`).
- **Drives other apps through the screen.** Screen reads and actions go
  through the accessibility layer and fail closed on protected apps. Screen
  actions are bound to the snapshot they were planned from; stale screens
  are refused. Screen memory stores structure only — no screen text or
  values.
- **Speaks and listens.** `listen/` covers ambient listening, speech, notes,
  the Librarian, and claim promotion into memory.
- **Remembers, with provenance.** The harness package (below) stores memory
  as claims with kind, salience, confidence, provenance, and supersede
  chains, searched with hybrid retrieval and query-time temporal decay.
- **Researches, shops, and investigates.** `research/` covers browsing,
  shopping (legitimate-product and company verification and scam screening
  only), and investigation.
- **Tor, opt-in.** `net/` (`TorProxy`, `EmbeddedTor`, `TorConnect`,
  `TorBrowserGuard`) routes through Tor only when enabled — Tor is off by
  default. Tor is fail-closed, verified in both 2026-10-19 audits by three
  independent guards in `TorProxy`: there is no clearnet fallback anywhere;
  the only fallback is in-app Tor → Orbot. Onion URLs never fall back to
  clearnet, and onion exclusion in shopping is a separate shopping-layer
  function.

## The gate

`gate/` is a deterministic enforcement layer: Gate, Manifest, contracts, and
taint tracking, deny-by-default. A tool that is not declared in the manifest
cannot run; if the manifest fails to load, everything is denied. Taint
blocks can neither be auto-approved nor operator-overridden. The model is
never trusted; the program is.

Consequential actions — send, post, purchase, delete — stop at a
confirmation card and the user decides. The agent informs and asks; it does
not permit or forbid. Some standing rules, as audited:

- Purchases always need fresh approval. No lasting grant covers a purchase,
  at either layer.
- Approval tokens are bound to the exact sorted-arguments key of the action
  they approve.
- The agent cannot see the approval card and cannot revoke grants; the user
  manages grants in Settings.
- The agent never types into password fields.
- SMS/email one-time codes and reset links never reach the cloud model —
  opaque placeholder references only, with a 10-minute expiry.
- Credentials live in the platform vault (Keystore, AES-GCM); the agent
  sees opaque handles only.
- Human verification (CAPTCHA and similar) is the user's: the agent parks
  with the exact link/action and never solves or bypasses it.
- Trash is emptied by the user in Settings, never by the agent; there is no
  agent-facing empty/purge.

## The harness package

`com.agent.ultra.harness`, inside this app, carries the Muse-style harness
layer: MemoryClaims, ApprovalGrants (grant levels plus command binding),
Scheduler with its runtime/receiver, SkillStore (lazy skills),
NotificationForwardingPolicy (three gates), ScreenGuard, Trash, Forget,
Subagents (depth-1 by construction), Feed, Ideas, Artifacts, Proactivity,
ClaimVerifier (its lenses fail closed), an NLI head, a bandit (ForYou), and
connectors for Gmail, Google Calendar, Google Docs, Google Drive, and
Spotify, backed by the CredentialVault and SecretScrubber. Post-merge, the
harness database wiring is complete: `UltraDatabase` is v9 and its entities
and DAO accessors cover claims, grants, embeddings, and bandit arms, with
migrations 7→8 and 8→9 registered.

## Status as of 2026-10-19

The 2026-10-19 consolidation merged all branches into `main`. Two code
audits (different snapshots, grep-level verification — definitions, call
sites, imports — not compilation) produced 206 findings in total; that is
not a deduplicated count for the merged tree. The one critical finding from
the first audit (harness database wiring) is resolved in the merged tree,
so post-merge there are **0 known criticals**.

Per-subsystem verdicts (merged view):

- **Works as designed:** `net/` Tor property (defects at the margins only),
  `data/` UltraDatabase (post-merge).
- **Works with defects:** `agent/`, `gate/`, `listen/`, `local/`,
  `provider/`, `research/`, `bench/` and tools, and the harness's internal
  logic.
- **Broken:** two Kotlin unit-test files (`HistoryPolicyTest.kt`,
  `MemoryBriefTest.kt` import a debug-only class the unit-test source set
  cannot resolve); the harness manifest wiring — `SchedulerReceiver` and
  `SpotifyRedirectActivity` exist but are not registered in
  `AndroidManifest.xml`.

Notable open defects include: recipe runs can self-confirm steps past the
Gate; Gate secret patterns miss phone-shaped secrets (bare one-time codes
and card numbers never taint); the cloud API key is stored in plaintext
SharedPreferences; GGUF downloads are verified by size only, with no
integrity check before native load; the Kotlin `screenshot` tool bypasses
the protected-app check; raw-coordinate taps are ungated when no label is
within 200px; and the harness grant keying is the weak core of the grant
invariants. About 70 dead-code candidates (zero call sites) were flagged —
candidates only; reflection, JNI, and framework callbacks can make some
live.

**CI has not yet compiled the merged tree.** Everything above is
source-level audit status; the Kotlin is source-only until CI runs. Build
and install procedures in this repo's guides predate the consolidation and
were not re-verified by the audits, which were static reading only —
on-device and runtime behaviour was not re-tested.

## Layout

| Path | Contents |
|---|---|
| `app/src/main/java/com/agent/ultra/agent/` | Brain loop, tools, navigation |
| `app/src/main/java/com/agent/ultra/gate/` | Gate, manifest, contracts, taint |
| `app/src/main/java/com/agent/ultra/listen/` | Ambient listening, speech, notes, Librarian, claim promotion |
| `app/src/main/java/com/agent/ultra/local/` | On-device models (llama.cpp JNI), downloads |
| `app/src/main/java/com/agent/ultra/net/` | Tor proxy / embedded Tor / browser guard |
| `app/src/main/java/com/agent/ultra/provider/` | Cloud provider client and config |
| `app/src/main/java/com/agent/ultra/research/` | Browse, shopping, investigate |
| `app/src/main/java/com/agent/ultra/ui/`, `data/` | UI; Room (`UltraDatabase`) |
| `app/src/main/java/com/agent/ultra/harness/` | The harness package described above |
| `app/src/main/java/com/agent/ultra/bench/`, `src/debug/` | Benchmarks; debug bench |
| `app/src/test/` | Unit tests (two files broken — see Status) |
| `ondevice-brain/` | On-device brain bundle: training notebook, build and test guides, signing |
| `tools/` | Host harness, benchmarks, AndroidWorld |
| `ci/` | The shared test debug keystore (see `ci/README.md`) |
