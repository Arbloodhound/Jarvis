> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/.github/pull_request_template.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

## What changed
<!-- One or two lines per change, in plain words. Say which tree: ultra-native/ (Kotlin),
     harness (com.agent.jarvis.harness), desktop/ (TypeScript), or src/ (TypeScript lineage).
     Target branch must be main. -->

## Evidence (label every claim)
<!-- MEASURED = a number from the owner's phone (results file) · PROVEN = JVM tests or the host harness ·
     SOURCE ONLY = written, not yet run on the phone · UNKNOWN = nobody has measured it.
     Post-merge reminder (2026-10-19): CI has not yet compiled the merged tree — do not claim
     a merged-tree build or test pass that CI has not produced. -->
- 

## Checklist
- [ ] JVM unit + screen tests green (`./gradlew testDebugUnitTest assembleDebug`), count stated — or the known breakage is named: `HistoryPolicyTest.kt` / `MemoryBriefTest.kt` import the debug-only `TestKitActivity` and the unit-test run is broken in those 2 files until fixed (2026-10-19 audits)
- [ ] Desktop tests run and reported separately, if `desktop/` changed (`desktop/test`)
- [ ] New logic has its own JVM tests (failing before, passing after, where it fixes a bug)
- [ ] Host harness green if native code (JNI / C++) changed
- [ ] Gate change (if any) shows what it now refuses and what it still allows; deny-by-default, manifest-failure = deny-all, and taint non-overridability preserved
- [ ] Shopping / Tor invariants preserved (if touched): onion exclusion stays a separate shopping-layer function; checkout keeps no purchase path; Tor stays fail-closed with no clearnet fallback; purchases still always need fresh approval
- [ ] If scheduling or the Spotify redirect is touched: how it was tested, given `SchedulerReceiver` and `SpotifyRedirectActivity` are not registered in `AndroidManifest.xml` in the merged tree
- [ ] No UI change unless the change asks for one
- [ ] No default the owner chose was changed without asking
- [ ] A phone probe added behind Jarvis Tests where only the phone can prove it
- [ ] Nothing committed that the push rules forbid: model files, keys, results files, audit logs, local.properties
- [ ] DEVLOG.md updated (append-only); HANDOFF docs updated where the change affects them
