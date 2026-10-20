> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/desktop/CLAUDE.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# CLAUDE.md: Jarvis Desktop

The rules in the repo root `CLAUDE.md` apply here too: no hallucination, no drift, no proof-free success claims, honesty statuses. This file adds what is specific to `desktop/`.

> Repository state: `desktop/` arrived via the `ccr-c9b6f798-uako9v` branch merge during the 2026-10-19 full-branch consolidation and is now on `main` like everything else. CI has not yet compiled the merged tree.

## Scope

`desktop/` is a separate project: a Node.js (22+) TypeScript agent with a local gateway and a browser chat page. It shares ideas with the Android app, not code. Do not edit `jarvis-native/` as part of desktop work.

Tor is not part of desktop. Tor lives in the Kotlin app (`jarvis-native/`). Do not add Tor claims or Tor work to desktop docs or code on the basis of the Android app's Tor stack.

## Hard scope rule (same as the phone)

Do not build drug sourcing or "find me X to buy" tooling for controlled or illicit substances, safety-classifier evasion, carding / wallet sweeps / payment-gateway tooling, or onion/Tor/crypto work tied to those. Shopping help means inform and confirm, never auto-buy. The agent informs and asks; consequential actions (send, post, purchase, delete) stop at confirmation and the user decides. Purchases always need fresh approval; no lasting grant covers a purchase.

## Map

- `src/agent/loop.ts`: the agent loop. Two paths — fast commands skip the model; the model path is capped by `maxTurns`, and `settle()` repairs dangling tool calls. Every tool call goes through `Gate.check` here; no tool may run any other way.
- `src/gate/`: the gate and its supporting detectors. The gate runs 8 ordered checks: (1) secret egress → (2) scam follow-up → (3) paths → (4) commands → (5) provenance → (6) leak → (7) trifecta → (8) lasting effects. Supporting modules from the pre-merge layout include `gate.ts`, `scam.ts` (port of the phone's scam signals), `secrets.ts` (keys, one-time codes), and `audit.ts`. Exact file splits were not re-verified in the 2026-10-19 audits beyond the gate's existence and order.
- `src/tools/index.ts`: the tools. Each one declares its effects and which args are targets, paths or egress.
- `src/providers/`: Claude cloud provider and the OpenAI-compatible local provider. Selection is per run. **There is no fallback between providers** — a failed turn ends the run. Do not document or implement a silent cross-provider fallback without an explicit product decision and a doc update.
- Memory: embeddings store/recall; `forget` moves facts to a trash capped at 500 entries. The agent has no trash purge; the user empties trash in settings.
- `src/app/behavior.ts`: pure window/tray decisions (`onWindowClose`, `isCrostini`), tested without Electron.
- `src/app/main.ts`: the native desktop app (Electron main process): window, tray, global hotkey, single instance, native clipboard / notifications / screen capture / browser via `useNative`, and `--self-test`.
- `src/server.ts`: the gateway (127.0.0.1, token, Host/Origin checks). `src/ui/index.html`: the chat page. Note: this is the desktop gateway in `desktop/src/`; the orphaned TS `server/` at repo `src/server/` is a different thing and is broken/orphaned — do not confuse them.

## Audited state — do not regress, do not over-claim

Supplement audit (ccr snapshot, 118 files): **87 findings — 0 critical, 12 high, 31 medium, 44 low**. Desktop app verdict: **works with defects**. Desktop tests verdict: **works as designed** (1 low).

Gate architecture was assessed as **strong** (layered checks, SSRF defence, redaction in audit log/confirm cards). That assessment coexists with these open highs — fixing them is priority desktop work:

1. **Unredacted tool args are broadcast to all clients.** Confirm cards carry redacted args and the audit log redacts, but the broadcast path does not. Any fix must redact broadcast payloads the same way, with a test.
2. **Cloud Vision screenshots are ungated.** Screenshots sent to cloud Vision bypass the gate. Any fix must gate that egress like any other.
3. **macOS/Windows screen actions report false success.** They report success for actions they did not perform. Do not trust or document a success return from those backends until this is fixed and proven.

No other desktop highs are listed in FACTS; do not invent additional "known highs" — check `AUDIT_SUPPLEMENT.md` for the full 12.

## Adding a tool

Declare `policy.effects` truthfully. List every arg that names where an effect lands (`targetArgs`), every local path (`pathArgs`) and every arg whose text leaves the computer (`egressArgs`). Set `outside: true` when the result comes from the web and `network: true` when the tool needs the network. Add a gate test for any new rule. Remember the broadcast-redaction issue above: a new tool's args will be broadcast — never put secrets in tool args expecting redaction to save you until that high is fixed.

## Standing safety rules that apply to desktop

- Human verification (CAPTCHA etc.) is the user's: park with the exact link/action, never solve or bypass.
- Never type into password fields.
- SMS/email codes and reset links never reach the cloud model as raw values (opaque placeholder references only, per the harness/desktop design as documented pre-merge; the exact desktop mechanism was not re-verified in the 2026-10-19 audits).
- Credentials live in the platform vault / config as documented; the agent sees opaque handles only where that design applies.
- Screen actions are bound to the snapshot they were planned from; stale screens refused — where desktop implements this, do not weaken it. (This rule is stated for the Jarvis system generally in FACTS; its desktop implementation status was not separately re-verified in the 2026-10-19 audits.)

## Checks

`npm run typecheck && npm test`. Run `npm run build` to produce `dist/`. `npm run app:self-test` (Linux: under `xvfb-run -a`, add `--no-sandbox` as root) proves the native app. `npm run dist` builds an installer for the current OS. Pin new dependencies to versions at least two weeks old.

CI has not yet compiled the merged tree — a local green run is not a CI pass claim. Say so in any status you write.
