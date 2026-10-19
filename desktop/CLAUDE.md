# CLAUDE.md: Jarvis Desktop

The rules in the repo root `CLAUDE.md` apply here too: no hallucination, no drift, no proof-free success claims, honesty statuses. This file adds what is specific to `desktop/`.

## Scope
`desktop/` is a separate project: a Node.js (22+) TypeScript agent with a local gateway and a browser chat page. It shares ideas with the Android app, not code. Do not edit `ultra-native/` as part of desktop work.

## Hard scope rule (same as the phone)
Do not build drug sourcing or "find me X to buy" tooling for controlled or illicit substances, safety-classifier evasion, carding / wallet sweeps / payment-gateway tooling, or onion/Tor/crypto work tied to those. Shopping help means inform and confirm, never auto-buy.

## Map
- `src/agent/loop.ts`: the agent loop. Every tool call goes through `Gate.check` here; no tool may run any other way.
- `src/gate/`: `gate.ts` (provenance rules), `scam.ts` (port of ScamSignals.kt), `secrets.ts` (keys, one-time codes), `audit.ts`.
- `src/tools/index.ts`: the tools. Each one declares its effects and which args are targets, paths or egress.
- `src/providers/`: `claude.ts` (official SDK, streaming) and `local.ts` (OpenAI-compatible local servers).
- `src/app/main.ts`: the native desktop app (Electron main process): window, tray, global hotkey, single instance, native clipboard / notifications / screen capture / browser via `useNative`, and `--self-test`.
- `src/server.ts`: the gateway (127.0.0.1, token, Host/Origin checks). `src/ui/index.html`: the chat page.

## Adding a tool
Declare `policy.effects` truthfully. List every arg that names where an effect lands (`targetArgs`), every local path (`pathArgs`) and every arg whose text leaves the computer (`egressArgs`). Set `outside: true` when the result comes from the web and `network: true` when the tool needs the network. Add a gate test for any new rule.

## Checks
`npm run typecheck && npm test`. Run `npm run build` to produce `dist/`. `npm run app:self-test` (Linux: under `xvfb-run -a`, add `--no-sandbox` as root) proves the native app. `npm run dist` builds an installer for the current OS. Pin new dependencies to versions at least two weeks old.
