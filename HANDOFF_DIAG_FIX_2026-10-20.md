# Handoff — DIAG button fix, for the repo build session (Claude)

**Branch:** `claude/diag-button-fix` (from `main` @ `b7ad77af`). Do the work on this
branch; merge to `main` only after the compile, tests, and phone repro below all pass.

**Commits:**
- `70b5c6220d` — `diag/LiveDiagnostic.kt`: failure-proof capture state machine
  (`NonCancellable` finally, whole-capture try, guarded `share()`, share-copy fallback).
- `7f9a10121a` — `ui/DiagnosticFab.kt`: single combined tap+drag detector; circle is
  visual-only; drag-only surface in the SEND/RESET state.
- Plus `DIAG_FIX_2026-10-20.md` (full diagnosis: 5 defects, D1 = stop-tap wedges
  `running=true` forever because the old `finally` suspended via `withContext` in a
  cancelled coroutine) and this handoff.

**Why this is the fix for "the DIAG button is broken still":** the owner's normal use is a
short capture ended with a tap. On old code that tap cancelled the job, the `finally`'s
`withContext` could never deliver, `running=false`/`lastFile` were never set — frozen
pulsing REC 0:00, no SEND/RESET, all later taps no-ops until process restart. The 15-minute
no-touch path worked, which is why it slipped through.

## 1. Compile + tests (must pass first)

```
cd ultra-native && flock /tmp/jarvis-gradle.lock ./gradlew -q --no-daemon compileDebugKotlin testDebugUnitTest
```

Expect: **1644 tests, 0 failures** (no tests added or touched).

Compile points to watch (new code, conservative but unverified here):
- `LiveDiagnostic.kt`: `withContext(NonCancellable + Dispatchers.IO)` and the two new
  imports (`kotlinx.coroutines.CancellationException`, `kotlinx.coroutines.NonCancellable`).
- `DiagnosticFab.kt`: the `diagGestures` detector uses `awaitEachGesture`,
  `awaitFirstDown(requireUnconsumed = false)`, `awaitPointerEvent()`,
  `PointerInputChange.changedToUp()`, `positionChange()`, `isConsumed`,
  `viewConfiguration.touchSlop`, `Offset.getDistance()` — all long-stable foundation/ui
  APIs, but confirm against the project's Compose version. The removed import is
  `detectDragGestures` (chips still use `detectTapGestures` — keep that import).

## 2. Phone repro (owner workflow, on the new build)

1. Tap **DIAG** → button grows, green pulsing **REC** with a counting-down timer.
2. After ~30 s, tap again → capture stops: **SEND** and **RESET** appear as two chips.
   (This exact step wedged the old build — frozen REC 0:00, chips never appeared.)
3. Repeat steps 1–2 two more times back to back. Each stop must produce the chips and,
   after RESET, a fresh DIAG button that starts a new capture. No restart of the app.
4. Tap **SEND** → the Android share sheet opens for the report file.
5. Tap **RESET** → back to the plain DIAG button.
6. **Drag** the button to a new spot, let go, then tap it (slight finger drift is fine):
   the capture must start. Dragging must never start/stop a capture by itself.
7. Start a capture, wait just over a minute, then **kill the app** (swipe from Recents)
   → a partial report must exist in **Downloads/Jarvis** (the per-minute save).
8. Leave one capture running untouched: it still ends by itself at 15:00 with SEND/RESET,
   as before.

## 3. Merge + release

- Merge `claude/diag-button-fix` → `main` only after §1 and §2 pass.
- One release per batch, with the APK link posted, per the standing release rule
  (`release-apk.yml`, ref `main`, `test_kit: true`).
- Suggested release-note line: "Fixed the DIAG button: stopping a capture early no
  longer wedges it, taps register reliably, and a finished capture always ends with a
  sendable file."

## Notes

- No user-visible strings, sizes, colors, durations, or save behavior changed; the only
  text edit anywhere is a stale "5 minutes" code comment corrected to 15 (matching
  `DURATION_SEC = 900`).
- Public surface unchanged (`LiveDiagnostic.toggle/start/stop/running/remainingSec/
  lastFile/share`, `DiagnosticFab()`), so nothing else needs updating for this fix.
- Full diagnosis and the deliberately-not-changed list: `DIAG_FIX_2026-10-20.md` on this
  branch.
