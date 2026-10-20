# HANDOFF — Ultra → Jarvis rename, for the repo build session (2026-10-20)

**Branch:** `claude/ultra-to-jarvis-rename` (from `main` @ `b7ad77af`). Full record:
`RENAME_2026-10-20.md` on this branch. Nothing here is merged yet.

## Commits

`a4e26a470f` stage 1 package · `48c2b4e7da` stage 2 identifiers+tags · `77f5601316` stage 3
on-disk names · `af760c17d2` stage 4 TS/RN · `49c334084f` stage 4b compat reads ·
`02eccddcb7` stage 5 Expo/plugin · `e973ef8e43` stage 6 sweep + `ultra-native/` →
`jarvis-native/` · plus the docs commit carrying `RENAME_2026-10-20.md` and this handoff.

## ⚠ SEQUENCING WARNING — merge the DIAG fix FIRST

`claude/diag-button-fix` (4 commits, from main `b7ad77af`) touches
`diag/LiveDiagnostic.kt` (its capture state machine) and `ui/DiagnosticFab.kt`.
This rename branch ALSO changes both files — mechanically only: package line/path
(`com.agent.jarvis.*`) and, in `LiveDiagnostic.kt`, the `TAGS` list strings
(`Ultra*` → `Jarvis*`, stage 2, deliberately in the same commit as every tag constant).

**Recommended order: merge `claude/diag-button-fix` into `main` first, verify it, then merge
this branch.** Reasons: the fix is small and behavior-critical, and its base is current main,
so it lands clean. The rename's edits to those two files are pure string/path mechanics, so
the conflicts the second merge produces are confined and mechanical — in
`LiveDiagnostic.kt` expect them around the imports/package line and the `TAGS` block:
resolve by keeping the fix's logic exactly and taking the rename's strings. The alternative
(rename first, then re-apply the fix by hand onto renamed code) re-does human work for no
gain. After both merges, sanity-check that the `TAGS` list in the merged `LiveDiagnostic.kt`
still equals the tag constants used across the tree (they were renamed by one identical
mechanical rule, so they match unless a conflict resolution mixes generations).

## ⚠ REQUIRED FOLLOW-UP — workflow files (blocked for the rename token)

The API token used for this rename lacks the `workflow` scope, so these two files still say
`ultra-native` while the directory is now `jarvis-native`. **CI will fail on the merged tree
until this is done** (path filters won't match, `working-directory: ultra-native` won't
exist). Mechanical edit, no judgment calls:

- `.github/workflows/release-apk.yml` — replace every `ultra-native` → `jarvis-native`
  (path filters ~lines 51–54, `D=` line 81, `hashFiles(...)` cache keys 93/100/109,
  build-script paths 115/119/122, signing lines 174/180/181, `working-directory` 185,
  APK paths 196/215/216/227/236) and the line-133 comment `ultra_vlm.cpp` → `jarvis_vlm.cpp`,
  `ultra_llm` → `jarvis_llm`.
- `.github/workflows/debug-apk.yml` — same replacement (path filters ~lines 36–39,
  `D=` line 60, cache keys 72/79/88, script paths 94/98/101, keystore/env lines 146/149,
  `working-directory` 152, APK paths 157/169/174/180) and the line-112 comment
  `ultra_vlm.cpp`/`ultra_llm` likewise.
- `.github/workflows/desktop.yml` needs nothing (no `ultra` references).

## Verify (standard, after both merges above)

```
cd jarvis-native && flock /tmp/jarvis-gradle.lock ./gradlew -q --no-daemon \
  compileDebugKotlin testDebugUnitTest
```

Expect **1644 tests, 0 failures** (no tests were added or removed; .bak deletions were RN
dead files, not tests). Watch points the compiler will NOT catch:

- **JNI symbols:** `Java_com_agent_jarvis_*` in `cpp/` must match the Kotlin `external fun`
  declarations — a mismatch is a runtime `UnsatisfiedLinkError`, not a compile error. The
  rename was one mechanical rule on both sides.
- **CMake ↔ loadLibrary:** targets `jarvis_llm`/`jarvis_vlm`/`jarvis_whisper` and the
  `loadLibrary` arguments were renamed in the same commit (stage 3).
- **Room:** the DB file is now `jarvis.db`; per the owner's fresh-install decision there is
  deliberately **no** migration from `ultra.db`.

## Test files changed by the rename (all of them, with why)

198 files in the `test`/`androidTest`/`debug` source sets were examined against pre-rename
main: **8 unchanged, 160 changed only in package/import/fully-qualified-name lines, 24 with
content changes beyond package lines** (plus 6 whose only extra diff line is the blank line
inside the package/import block — no action). The 24, every one a mechanical lockstep move
with the code it tests — no assertion logic, values, or expectations were altered in
substance:

- `androidTest/…/gate/SharedVectorsDeviceTest.kt` — log tag `UltraGateVectors` → `JarvisGateVectors`.
- `debug/AndroidManifest.xml` — `@style/Theme.AgentUltra` → `Theme.Jarvis`.
- `debug/…/bench/AccelProbeReceiver.kt`, `BenchReceiver.kt`, `RealUseProbes.kt`,
  `FeatureProbes.kt`, `TestKitActivity.kt` — log-tag constants and logcat filter lists
  (`Ultra*` → `Jarvis*`, matching stage 2), `UltraPrefs` → `JarvisPrefs` call,
  `AgentUltraTheme` → `JarvisTheme` call, asset name `ultra.manifest.json` →
  `jarvis.manifest.json` (TestKitActivity ×2).
- `test/…/DataExtractionRulesTest.kt` — the lockstep one to eyeball: asserts the database
  list now `["jarvis.db", "ambient.db", "memory_notes.db"]`, the prefs list `jarvis_*`, and
  its source-scan regex `(ultra_[a-z_]+)` → `(jarvis_[a-z_]+)` (it scans the app sources for
  prefs-name literals; it must match the renamed constants or it correctly fails).
- `test/…/ExperienceTest.kt` — lesson UID fixtures `ultra-self-*` → `jarvis-self-*` and the
  `startsWith("jarvis-deadend-")` assertion, mirroring production `Experience.uid()` which
  now returns `"jarvis-$prefix-$h"`.
- `test/…/WakeFilterTest.kt` — wake-name fixture `"Ultra"` → `"Jarvis"` (the *Ultraviolet*
  content-sentence line is untouched — English word, expected survivor).
- `test/…/local/CpuVariantTest.kt` — native candidate names `ultra_whisper*` →
  `jarvis_whisper*` (lockstep with the `.so` rename); `TwoModelsTest.kt` — `prefsName`
  assertion `"ultra_local_model"` → `"jarvis_local_model"`.
- `test/…/gate/ScamGateTest.kt`, `ToolWiringTest.kt`, `ResearchToolsGateTest.kt`,
  `bench/CloudBrainReportTest.kt` — asset path `ultra.manifest.json` → `jarvis.manifest.json`
  (ToolWiringTest also reads `Tools.kt` at its new `com/agent/jarvis/` path).
- `test/…/bench/KitReportTest.kt` — log-line fixtures `I/UltraLlm(…)` → `I/JarvisLlm(…)` and
  `ultra_llm_i8mm` → `jarvis_llm_i8mm`.
- `test/…/harness/HarnessDatabaseTest.kt`, `HarnessDatabaseV9Test.kt` — `UltraDatabase` →
  `JarvisDatabase` type + `MIGRATION_7_8`/`MIGRATION_8_9` references.
- `test/resources/gate_vectors.json` — the `"why"` caller strings
  `"Agent Ultra GateTest.wwwPrefixNormalizedOnDeviceCase, 2026-08-27"` ×4 and the `"about"`
  text → `Jarvis …`. Input and expectation live in the same file and moved together.
- `test/…/DemonstrationTest.kt`, `ThermalPolicyTest.kt`, `WhisperParseTest.kt` — comment
  text only (`Agent Ultra` → `Jarvis`, `ultra_llm.cpp`/`ultra_whisper.cpp` file names).
- `test/…/ScamSignalsTest.kt` — env vars `ULTRA_SMS_CORPUS` / `ULTRA_SMS_DEBUG` →
  `JARVIS_SMS_CORPUS` / `JARVIS_SMS_DEBUG` (whoever runs that corpus test must set the new
  names).

RN/TS side has no Gradle verify; if a TS typecheck is run, note `MessageSource` is now
`'jarvis' | 'model' | 'system'` and the two legacy-alias reads cast through `string`
deliberately (stage 4b) — that is the compat mechanism, not a mistake.

## Release note (when this ships)

New `applicationId` = **new app on the phone**. Obtainium will add it as a new app; the old
app remains installed until Rafael uninstalls it; models re-download on first run; memory
and settings start fresh (owner's explicit choice — no migration code exists, do not write
any). One release per batch, with the APK link, per standing owner rules.
