> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/CONTRIBUTING.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# Contributing to Jarvis (Jarvis)

*Last updated: 2026-10-19.*

Bug reports and design pushback are the most useful things you can send. All work now happens on `main` in `Arbloodhound/Jarvis` — the 2026-10-19 consolidation merged every active branch into `main` (see `MERGE_LOG_2026-10-19.md`). Do not open work against the old feature branches; the four branches that remain unmerged were left that way by design (three empty tip commits, one superseded work-in-progress snapshot).

> **Current-state warning.** CI has not yet compiled the merged tree. The 2026-10-19 code audits were grep-level (definitions, call sites, imports), not compilation. Post-merge there are 0 known criticals, but two Kotlin unit-test files are broken and two harness components are unregistered in the manifest (see "Known breakage" below). A contribution that fixes one of those, with proof, is worth more right now than any new feature.

## Reporting

- Use the issue templates. Phone model, Android version, and app version matter more than anything else.
- Say which tree your report is about: `jarvis-native/` (Kotlin Android app), the harness (`com.agent.jarvis.harness`), `desktop/` (TypeScript), or `src/` (TypeScript lineage app). Reports that don't say are hard to act on.
- **Never paste an API key, a password, or a screenshot that shows messages, contacts, or notifications.** Issues are public. One-time codes, reset links, and credentials must never appear in a report — in the app itself they only ever travel as opaque placeholder references, and reports must follow the same rule.
- Version-specific advice from older versions of this file (including withdrawn-build instructions) was not re-verified in the 2026-10-19 audits and has been removed. If in doubt, report against a build from current `main` and give its exact build identifier.

## Building

Follow **Build it** in the README. The native library needs llama.cpp built for arm64 first; it isn't vendored here. Debug builds work from a fresh clone (subject to the CI caveat above — a fresh-clone build of the merged tree has not yet been proven by CI). Release builds need a `keystore.properties` that isn't in the repo, and fail without it on purpose.

The repo has three buildable trees; say which one you built:

- `jarvis-native/` — Gradle / Android build (the shipping app, plus the harness).
- `desktop/` — TypeScript desktop app; tests in `desktop/test` (7 files).
- `src/` — TypeScript lineage app. Its `server/` directory is orphaned (nothing calls it, no routes, crashes at startup) — do not build contributions on it.

## Tests

Run the unit tests before sending a change:

```
cd jarvis-native
./gradlew :app:testDebugUnitTest
```

**Known breakage (2026-10-19, post-merge) — read before you interpret a failure:**

- `HistoryPolicyTest.kt` and `MemoryBriefTest.kt` import `com.agent.jarvis.bench.TestKitActivity`, which exists only in `src/debug`. The unit-test source set cannot resolve it, so the Kotlin unit-test run is broken in those 2 files until fixed. If your run fails there, say so explicitly in your PR instead of claiming a green run.
- `SchedulerReceiver` and `SpotifyRedirectActivity` exist in the harness but are not registered in `AndroidManifest.xml`. Anything touching scheduling or the Spotify redirect must state how it was tested given that gap.
- Older versions of the docs quoted total test counts. No total for the merged tree is stated here, because CI has not compiled the merged tree and no verified total exists yet.

For desktop changes, run the `desktop/test` suite as well and report its result separately — do not merge the two results into one "tests pass" claim.

## Pull requests

- Target `main`. Keep a change to one thing, and say what it proves — which tests, and whether it ran on a real phone. Use the evidence labels in `.github/pull_request_template.md` (MEASURED / PROVEN / SOURCE ONLY / UNKNOWN) and label every claim.
- Anything that touches the policy gate needs a test showing what it now refuses and what it still allows. The gate is deny-by-default; manifest load failure means deny-all; taint blocks can neither be auto-approved nor operator-overridden. PRs must not weaken any of those properties silently.
- Anything that touches shopping must preserve the audited invariants: onion exclusion stays a separate shopping-layer function (`excludeOnion` / `isOnionUrl`), and `ShoppingCheckout` keeps **no** purchase path (prepare / display / track only). Purchases always need fresh approval; no lasting grant may cover a purchase.
- Anything that touches Tor must preserve fail-closed behaviour: no clearnet fallback anywhere; the only fallback is in-app Tor → Orbot.
- Anything that touches credentials, codes, or secrets must preserve the vault / placeholder design: credentials live in the platform vault (Keystore AES-GCM) as opaque handles; SMS / email codes and reset links never reach the cloud model.
- Do not delete "dead code" on suspicion. The audits list ~70 dead-code candidates (zero call sites) — candidates only; reflection, JNI, and framework callbacks can make some live. Each removal needs its own proof that nothing reaches it.
- `DEVLOG.md` is append-only. Add an entry; don't rewrite old ones.

## License

License terms were not re-verified in the 2026-10-19 audits. See `LICENSE` and `NOTICE` in this repository for the current terms, including any contribution-licensing terms, before relying on a summary here.
