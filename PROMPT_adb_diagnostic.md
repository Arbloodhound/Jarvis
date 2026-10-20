> **Post-merge replacement — 2026-10-19.** This is a point-in-time document. Its body is preserved verbatim as the historical record; the status section below states what the 2026-10-19 code audits and the full-branch consolidation (all work now on `main`) changed. The previous version is preserved at `archive/2026-10-19/PROMPT_adb_diagnostic.md`. CI has not yet compiled the merged tree.

## Status as of 2026-10-19

This report predates the 2026-10-19 consolidation. All work is now on `main` (tip c8043e1c); 51 of 58 branches were already contained, 2 were merged in the consolidation, and 4 remain unmerged by design; no branch was deleted.
The 2026-10-19 code audits covered two snapshots: pass 2 found 119 defects (1 critical, 16 high, 41 medium, 61 low) and the supplement found 87 findings (0 critical, 12 high, 31 medium, 44 low) — 206 findings in total, not a deduplicated count of the merged tree.
Post-merge there are 0 known criticals: the pass-2 critical (harness DB wiring) is resolved — JarvisDatabase is v9, with the harness entities, DAO accessors, and MIGRATION_7_8 + MIGRATION_8_9 registered.
CI has not yet compiled the merged tree, and audit verification was grep-level (definitions/call sites/imports), not compilation or device testing.
This document is a diagnostic prompt: run `adb logcat` against a Samsung device to confirm whether `NativeModules.AppController.moveTaskToBack` exists at runtime, and whether `react_navigate` is selected.
The audits were code-only and grep-level, with no `adb`, logcat, or device testing: they do not cover `moveTaskToBack`, AppController bridge exposure, or the diagnostic outcomes this prompt is designed to distinguish — that topic is not covered and is not re-verified.
In the merged tree the surrounding lineage is the `src/` TS app (live path AgentCore / BrainExecutor / TaskExecutor, verdict: works with defects); the shipping app is the `jarvis-native/` Kotlin app.
Still open on that TS live path generally: it bypasses its own SafetyChecker / PermissionBroker, and the TS `server/` is orphaned.
Standing rule relevant to any follow-up: human verification is the user's — park it with the exact link/action, never solve or bypass it.
No result is recorded in the body below; it remains a procedure to run, not a finding.

---

# PROMPT: adb logcat diagnostic — moveTaskToBack

## Context

Session 5 preview APK has diagnostic logging added to AppController.ts and TaskExecutor.ts (commit 21d8016). We need to confirm whether `NativeModules.AppController.moveTaskToBack` exists at JS runtime.

The user will:
1. Install the new APK on their Samsung device
2. Grant accessibility permissions
3. Issue a voice/text command like "open YouTube and search for cats"

## Your job

Run adb logcat, capture the output, and diagnose.

### Step 1 — Start capture

```bash
APPID=$(adb shell pidof com.agent.jarvis)
timeout 120 adb logcat --pid=$APPID -s AgentA11y:* ReactNativeJS:* 2>&1 | tee /tmp/agent_logcat.txt
```

If the PID isn't found yet, wait and retry:
```bash
while [ -z "$(adb shell pidof com.agent.jarvis)" ]; do sleep 2; echo "Waiting for app..."; done
```

### Step 2 — Watch for these signals

**Success (moveTaskToBack works):**
- `MOVE_TO_BACK: activity=true result=true` in AgentA11y
- `SCREEN_FLAT: root_pkg=com.google.android.youtube` (or any non-jarvis app)

**Failure — method missing:**
- `moveTaskToBack not found on native module` in ReactNativeJS
- Or `moveTaskToBack exists: false` in any log

**Failure — method exists but errors:**
- `moveTaskToBack failed:` with an error message

**Failure — react_navigate never selected:**
- No `Calling moveTaskToBack` log at all after 60+ seconds of agent activity
- Only PKG_CHANGE and SCREEN_FLAT lines, no TAP/TEXT/SCROLL actions

### Step 3 — Stop capture

Stop the logcat capture (Ctrl+C or let timeout kill it) once you see ANY of:
- A `MOVE_TO_BACK` entry (success or failure)
- A `moveTaskToBack not found` console.warn
- A `moveTaskToBack failed` error
- The ReActLoop has run 5+ iterations without any moveTaskToBack log (proves it was never called)
- 90 seconds of agent activity with no react_navigate entry

### Step 4 — Analyze and report

Read `/tmp/agent_logcat.txt` and report:

1. **Did moveTaskToBack fire?** (yes/no, with the exact log line)
2. **Did SCREEN_FLAT read a non-jarvis window?** (yes/no, which pkg)
3. **Was react_navigate selected as the tool?** (look for TAP/TEXT/SCROLL actions after a planning phase)
4. **What is the diagnosis?** One of:
   - METHOD_WORKS — both fixes confirmed working
   - METHOD_MISSING — native bridge doesn't expose moveTaskToBack, needs investigation into Expo config plugin prebuild
   - METHOD_ERRORS — method exists but throws, report exact error
   - TOOL_NOT_SELECTED — LLM never picked react_navigate, moveTaskToBack was never reached
   - UNKNOWN — not enough signal, describe what you saw

5. **Recommended next step** based on diagnosis.

## Important

- Do NOT modify any source files. This is a read-only diagnostic.
- Kill the logcat process once you have enough signal — don't let it run indefinitely.
- Save the raw capture to `/tmp/agent_logcat.txt` for reference.
