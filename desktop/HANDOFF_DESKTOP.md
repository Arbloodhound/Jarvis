> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/desktop/HANDOFF_DESKTOP.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# Jarvis Desktop — current handoff (2026-10-19)

This is a current-state handoff, rewritten after the 2026-10-19 full-branch consolidation. It replaces the previous session handoff, which described work in progress on a feature branch. That version is preserved in the archive (see header). Read this, then `desktop/CLAUDE.md`, `desktop/README.md`, and the framed `desktop/REVIEW.md` (a point-in-time review — its body is historical; its status section is current).

## Where things are

- Repo `Arbloodhound/Jarvis` (private). **All work is now on `main`** (tip `c8043e1c` — merge commits `ae030b9f` + merge log on top). `claude/shopping-integration` (`adbd9e84`) is also current.
- `desktop/` arrived via the `ccr-c9b6f798-uako9v` → shopping-integration merge (`adbd9e84`), then shopping-integration → `main` (`ae030b9f`). Do not develop on the old ccr branch or on `muse/v5-delta-port` for desktop; branch fresh from `main`.
- 58 branches were swept: 51 already contained, 2 merged during consolidation, 4 remain unmerged by design (`claude/broken-fixes`, `claude/download-all`, `claude/fast-reasoning` — empty tip commits; `claude/offline-navigation-wip` — superseded WIP snapshot). No branch was deleted; no history was rewritten.
- The whole desktop project is under `desktop/`. The Android app under `jarvis-native/` is a separate project (shares ideas, not code) and stays untouched by desktop work.
- Old PR/branch/commit details from the previous handoff (draft PR, head commits, CI run numbers) are historical only and are **not re-verified in the 2026-10-19 audits** — check GitHub for current PR state before acting on any of them.

## What this is

A native desktop AI agent (Electron + Node/TypeScript), local-first, OpenClaw-style local gateway plus the Jarvis safety gate. Runs on Windows/macOS/Linux with installers. Chat page in a window, plus a `serve` web mode and terminal `chat`/`ask` modes. Two brains: any OpenAI-compatible local server (Ollama default) or Claude cloud. See `desktop/README.md` for the user-facing description.

Tor is not part of desktop. Tor is in the Kotlin app (`jarvis-native/`).

## Audited state

Two code-only audits were run on 2026-10-19. Audit verification was grep-level (definitions/call sites/imports), not compilation. **CI has not yet compiled the merged tree.** Kotlin is source-only until CI runs; the same caution applies to any "it works" claim about the merged tree as a whole.

- Pass 2 (main lineage + harness delta): 533/536 files read; 119 defects — 1 critical, 16 high, 41 medium, 61 low. Its 1 critical (harness DB wiring) is **resolved in the merged tree** (see `FACTS.md` / merge log), so post-merge there are **0 known criticals**.
- Supplement (ccr branch, 118/118 files — the snapshot that brought `desktop/` in): **87 findings — 0 critical, 12 high, 31 medium, 44 low**, plus 6 overflow candidates in `desktop/src`.
- Combined framing: 206 findings across two audits of different snapshots — **not** a deduplicated count of the merged tree. Do not quote 206 as the merged-tree defect count.

Per-subsystem verdicts relevant here:

- Desktop app: **works with defects**.
- Desktop tests (`desktop/test`, 7 files): **works as designed**, with 1 low finding. The previous handoff cited 40 passing tests; the README cited 19 at an earlier point. The exact current count was not re-verified in the 2026-10-19 audits — run the suite (below) and quote what you observe.
- Desktop gate architecture: **strong** — layered checks, SSRF defence, redaction in audit log/confirm cards — despite the highs below.

## How the desktop app works (audited description)

- **Agent loop** (`src/agent/loop.ts`): fast-command path skips the model for obvious commands; the model path runs tool calls through the gate on every call, is capped by `maxTurns`, and `settle()` repairs dangling tool calls.
- **Gate**: 8 ordered checks — (1) secret egress → (2) scam follow-up → (3) paths → (4) commands → (5) provenance → (6) leak → (7) trifecta → (8) lasting effects. Confirm cards carry **redacted** args.
- **Providers**: Claude cloud + local (OpenAI-compatible). Selection is per run. **No fallback between them** — a failed turn ends the run.
- **Memory**: embeddings store/recall; `forget` moves facts to a trash capped at 500 entries.
- **Gateway + CLI**: gateway server (127.0.0.1, token, Host/Origin checks) plus CLI; tests live in `desktop/test` (7 files).

## What's open (desktop)

From the supplement audit's desktop highs — treat these as the priority queue until a fix is shown in code and tests:

1. **Unredacted tool args broadcast to all clients.** The gate's confirm cards and audit log redact; the broadcast path does not. Fix the broadcast payload, add a regression test.
2. **Cloud Vision screenshots ungated.** Screenshots can be sent to cloud Vision without a gate check. Gate that egress.
3. **macOS/Windows screen actions report false success.** Actions report success without being performed. Fix the backends to report honestly, or mark the actions as not performed; do not ship claims based on the current success returns.
4. **No cross-provider fallback** — audited behaviour, currently by fact if not by explicit design decision. If fallback is wanted, it needs an explicit decision; until then, docs and UX should say a failed turn ends the run.

Beyond those three highs + the fallback note, the supplement lists 9 further highs across the ccr snapshot (not all desktop-specific) and 31 medium / 44 low findings for that snapshot — read `AUDIT_SUPPLEMENT.md` for the full list before planning fix work. Do not cherry-pick a "fixed" status for any finding without checking the merged tree on `main`.

General post-merge open items that touch the repo as a whole (from FACTS, not desktop-specific): two harness components unregistered in the Android manifest, two Kotlin unit-test files importing a debug-only class, the orphaned TS `server/` at repo `src/server/` (not the desktop gateway), and ~70 dead-code candidates (candidates only). None of those is a desktop task unless you are explicitly assigned it.

Not done / not proven (carried from pre-merge docs, not re-verified in the 2026-10-19 audits): code signing (Windows) and notarization (macOS), auto-update, Linux start-at-login, day-to-day use on a real desktop, and real-model tool-calling quality. Screen control was described pre-merge as runtime-unproven on real desktops; the false-success high above reinforces that caution for macOS/Windows.

## Build & test (confirm a baseline before changing anything)

```bash
cd desktop
npm install
npm run typecheck && npm test        # quote the count you actually observe
npm run build                        # -> dist/ (+ dist/ui)
# native app self-test (needs a display; on Linux use Xvfb + --no-sandbox):
npm run app:self-test
npm run dist                         # builds an installer for the current OS into release/
```

Exact self-test invocation flags for headless Linux are in the pre-merge handoff (archived) and in `desktop/CLAUDE.md`; they were not re-verified in the 2026-10-19 audits.

## Working rules

- Follow the root `CLAUDE.md` honesty rules: no hallucination, no drift, no proof-free success claims; label anything you cannot run as runtime-unproven.
- Every tool call goes through the gate. No bypass path.
- The agent informs and asks; it does not permit or forbid. Consequential actions stop at confirmation; the user decides. Purchases always need fresh approval.
- Human verification is the user's — park it, never solve or bypass it.
- Scope exclusion carried from the previous handoff and the root rules: do not build Tor/dark-web/onion monitoring or automated action-on-hidden-services tooling in desktop. Ordinary embedding/vision/rerank model support is fine.
- End-of-session bookkeeping (DEVLOG entry, handoff updates) follows the repo's current conventions on `main` — check the root docs on `main` rather than the archived handoff's branch/PR instructions, which no longer apply.
