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
APPID=$(adb shell pidof com.agent.ultra)
timeout 120 adb logcat --pid=$APPID -s AgentA11y:* ReactNativeJS:* 2>&1 | tee /tmp/agent_logcat.txt
```

If the PID isn't found yet, wait and retry:
```bash
while [ -z "$(adb shell pidof com.agent.ultra)" ]; do sleep 2; echo "Waiting for app..."; done
```

### Step 2 — Watch for these signals

**Success (moveTaskToBack works):**
- `MOVE_TO_BACK: activity=true result=true` in AgentA11y
- `SCREEN_FLAT: root_pkg=com.google.android.youtube` (or any non-agent-ultra app)

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
2. **Did SCREEN_FLAT read a non-agent-ultra window?** (yes/no, which pkg)
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
