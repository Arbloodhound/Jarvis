# Agent Ultra — Log File Proliferation Report

**Date:** 2026-03-15  
**Issue:** Logging system creates 100s of individual files instead of 3-4 persistent, growing log files  
**Status:** Root cause identified, fix documented, code unchanged pending user decision

---

## Executive Summary

The `agent-ultra-logs/` directory receives automatic writes every 2-3 seconds with **unique timestamps**, creating new files instead of updating existing ones. After 10 minutes: ~200 files. After 1 hour: ~1,800 files. Every file is a redundant superset of the previous one (same full buffer snapshot, just timestamped differently). The user wants 3-4 files that grow naturally to several MB, not hundreds of tiny snapshots.

The root cause is intentional use of timestamped filenames when fixed filenames would be correct. Since `writeAsStringAsync` overwrites (doesn't append), changing to fixed filenames would make each flush update the same file with the latest (largest) buffer, solving the problem entirely.

---

## The Three Log Directories

The app writes logs to **three different directories**, managed by three different writers:

### Directory 1: `${documentDirectory}ultra_dev_logs/`
**Writer:** `UltraDevLog.doFlush()` (line 839)  
**File pattern:** `session_{sessionId}.jsonl`  
**Behavior:** CORRECT. Same filename per session, overwritten each flush, grows over time.  
**UI visibility:** None

### Directory 2: `${documentDirectory}debug_logs/`
**Writer:** `DebugLog.flushToFile()` (line 394)  
**File pattern:** `debug_{sessionId}.jsonl`  
**Behavior:** CORRECT. Same filename per session, overwritten each flush, grows over time.  
**UI visibility:** None

### Directory 3: `${documentDirectory}agent-ultra-logs/` (THE PROBLEM)
**Writers:** Two sources creating timestamped files:
  1. `UltraDevLog.doFlush()` line 841 → `agent-ultra-debug-{ISO timestamp}.jsonl` every ~2 seconds
  2. `DebugLog.flushToFile()` line 396 → `agent-ultra-debuglog-{ISO timestamp}.jsonl` every ~3 seconds
  3. `DebugLog.exportAll()` lines 419, 425 → `agent-ultra-raw-{ISO timestamp}.jsonl` on manual export

**Behavior:** INCORRECT. Each call generates a unique timestamp, creating a new file instead of updating existing ones.  
**UI visibility:** YES. This is the only directory that Settings > Logs reads via `LogFolder.listLogs()`.

---

## Data Flow: How Files Get Created

```
Logging event occurs (vault read, system init, etc.)
    |
    v
UltraDevLog.push() or DebugLog.push()
    |
    +-- Add entry to in-memory array (MAX_MEMORY = 3000 or 15000)
    +-- Call scheduleFlush() to defer actual write
    |
    v (after debounce: 2000ms for Ultra, 3000ms for Debug)
UltraDevLog.doFlush() / DebugLog.flushToFile()
    |
    +-- Dump ENTIRE buffer to ${docDir}ultra_dev_logs/session_XXX.jsonl
    |     OR ${docDir}debug_logs/debug_XXX.jsonl
    |     (OVERWRITES same file, correct behavior)
    |
    +-- ALSO dump ENTIRE buffer to ${docDir}agent-ultra-logs/agent-ultra-debug-2026-03-15T06-37-45-581Z.jsonl
          (CREATES NEW FILE with timestamp, incorrect behavior)
          |
          v
          LogFolder.writeLog() called with timestamped filename
          (uses FileSystem.writeAsStringAsync which overwrites)
```

**The paradox:** `writeAsStringAsync` is the right API (it overwrites the entire file, which is what we want for a growing buffer). But it's being called with a filename that includes a timestamp, making it write to a different file each time instead of overwriting the same one.

---

## Why Every File Is Redundant

Both loggers maintain an in-memory ring buffer:

| Logger | MAX_MEMORY | Behavior when full |
|--------|-----------|-------------------|
| UltraDevLog | 3,000 entries | `entries.slice(-3000)` drops oldest (line 106-107) |
| DebugLog | 15,000 entries | `entries.slice(-15000)` drops oldest (line 29-30) |

Each flush writes the **entire current buffer** to the file. No appending, no selective writes. So:

- File created at T=0s: entries 1-50 written
- File created at T=2s: entries 1-80 written (superset, contains everything from T=0 plus new entries)
- File created at T=4s: entries 1-110 written (superset, contains everything from T=2 plus new entries)
- ...continues...

**Only the latest file contains complete information.** All prior files are subsets and can be safely deleted.

---

## Memory Buffer and File Growth

Each entry is ~150-200 bytes of JSON JSONL. With MAX_MEMORY limits:

| Logger | Entries | Bytes/entry | Max file size |
|--------|---------|------------|--------------|
| UltraDevLog | 3,000 | ~200 | ~600 KB |
| DebugLog | 15,000 | ~150 | ~2.2 MB |
| raw-export (DebugLog) | 15,000 | ~150 | ~2.2 MB |

**Total max:** ~5 MB for all logs in the entire session.

This is small by phone storage standards. The user has "huge files" on their phone. Growing logs to several MB is not a storage problem.

---

## All Files Involved

| File | Lines | Purpose |
|------|-------|---------|
| `src/services/LogFolder.ts` | 125 | Manages `agent-ultra-logs/` directory. `writeLog()` creates/updates files. `listLogs()` reads for Settings UI. |
| `src/utils/UltraDevLog.ts` | 887 | Runtime diagnostic logger. Every 2s, `doFlush()` writes to both `ultra_dev_logs/` (fixed name, correct) and `agent-ultra-logs/` (timestamped name, incorrect). |
| `src/utils/DebugLog.ts` | 669 | Vault/system event logger. Every 3s, `flushToFile()` writes to both `debug_logs/` (fixed name, correct) and `agent-ultra-logs/` (timestamped name, incorrect). Also has `exportAll()` which writes another timestamped file. |
| `app/settings.tsx` | 852 | Settings UI. Logs tab (lines 696-725) calls `LogFolder.listLogs()` to display files. `downloadLog()` (line 357) uses `Sharing.shareAsync()` to share individual files. |

### Specific Call Sites Creating Timestamped Files

| File | Function | Line | Creates | Frequency |
|------|----------|------|---------|-----------|
| `src/utils/UltraDevLog.ts` | `doFlush()` | 841 | `agent-ultra-debug-{timestamp}.jsonl` | every ~2s (debounced) |
| `src/utils/DebugLog.ts` | `flushToFile()` | 396 | `agent-ultra-debuglog-{timestamp}.jsonl` | every ~3s (debounced) |
| `src/utils/DebugLog.ts` | `exportAll()` | 419 | `agent-ultra-raw-{timestamp}.jsonl` | on manual export |
| `src/utils/DebugLog.ts` | `exportAll()` | 425 | `agent-ultra-raw-{timestamp}.jsonl` | fallback path on manual export |

### How Timestamps Are Generated

All use the same pattern:
```typescript
const ts = new Date().toISOString().replace(/[:.]/g, '-');
// Example: "2026-03-15T06-37-45-581Z"
```

This ensures each call produces a different filename.

---

## How This Happened: Past Fixes That Contributed

### v3.18.0 (2026-03-15) — Created the LogFolder system

**Why:** Clipboard had size limits when sharing logs via copy-paste. Solution: save logs to disk and use Android file picker.

**What changed:**
- Created `src/services/LogFolder.ts` to manage `agent-ultra-logs/` directory
- Added `LogFolder.writeLog()` calls in `UltraDevLog.doFlush()` line 841 and `DebugLog.exportAll()` lines 419, 425
- At this point, only `UltraDevLog` auto-flushed to LogFolder (every 2s). `DebugLog` only wrote to LogFolder on manual `exportAll()`.
- This introduced ~30 new files per minute (from UltraDevLog auto-flush only).

### v3.19.0 (2026-03-15) — Bug fixes including Bug 6

**Bug 6 fix:** "Three separate log directories — Logs tab only reads one"

**The fix:** Made `DebugLog.flushToFile()` also call `LogFolder.writeLog()` (line 396), so DebugLog entries would be visible in the Settings > Logs tab.

**Unintended consequence:** Added a **second source** of timestamped file creation. Now both loggers create files every 2-3 seconds, doubling the file creation rate.

**Result:** ~50+ new files per minute instead of ~30.

This fix was well-intentioned but introduced the proliferation problem because it used the same timestamped filename pattern as UltraDevLog.

---

## Existing Cleanup Mechanisms

### UltraDevLog.cleanOldLogs() (lines 870-886)
- Designed to clean up old session files from `ultra_dev_logs/`
- Deletes files older than 7 days (configurable)
- Uses regex: `/session_([a-z0-9]+)\.jsonl/` to identify session files
- Called during AgentCore initialization as LogCleanup subsystem
- **Does not clean `agent-ultra-logs/`** — the proliferating directory

### No cleanup for agent-ultra-logs/
- `LogFolder` class has no cleanup, rotation, or count limit
- Timestamped files accumulate indefinitely on the device
- User can only delete them manually or via "Clear App Data"

---

## What the User Sees

### In Settings > Logs tab
- Calls `LogFolder.listLogs()` (line 345 of settings.tsx)
- Reads all files from `agent-ultra-logs/` directory
- Displays filename, size in KB, sorted newest first (line 711-722)
- Each file can be tapped to trigger `Sharing.shareAsync()` which opens native share sheet
- User can send to email, messaging apps, cloud storage, etc.

### File display example (after 30 minutes of use)
```
Ultra Debug Log [2026-03-15T06-37-45-581Z.jsonl] - 245 KB
Ultra Debug Log [2026-03-15T06-37-47-124Z.jsonl] - 248 KB
Ultra Debug Log [2026-03-15T06-37-49-653Z.jsonl] - 251 KB
Ultra Debug Log [2026-03-15T06-37-51-987Z.jsonl] - 254 KB
... (many more)
```

All files contain the same or overlapping data. Only the last one (newest) contains complete information.

---

## The Correct Fix (Documented, Not Yet Implemented)

### Change 1: Use Fixed Filenames

**File:** `src/utils/UltraDevLog.ts` line 841
```
OLD: await LogFolder.writeLog(`agent-ultra-debug-${ts}.jsonl`, content);
NEW: await LogFolder.writeLog(`ultra-devlog.jsonl`, content);
```

**File:** `src/utils/DebugLog.ts` line 396
```
OLD: await LogFolder.writeLog(`agent-ultra-debuglog-${ts}.jsonl`, lines);
NEW: await LogFolder.writeLog(`debug-log.jsonl`, lines);
```

**File:** `src/utils/DebugLog.ts` lines 419, 425
```
OLD: await LogFolder.writeLog(`agent-ultra-raw-${ts}.jsonl`, content);
NEW: await LogFolder.writeLog(`raw-export.jsonl`, content);
```

**Result:** Each flush now overwrites the same file (since `writeAsStringAsync` overwrites). The file grows as the buffer grows.

### Change 2: Add Cleanup for Old Files

**File:** `src/services/LogFolder.ts` in `initialize()` method (after line 41)

Add a cleanup pass to delete any old timestamped files from previous app versions:

```typescript
// Clean up old timestamped files (pre-fix naming pattern)
try {
  const files = await fs.readDirectoryAsync(dir);
  for (const f of files) {
    // Delete files matching the old pattern: agent-ultra-*.jsonl with ISO timestamps
    if (/^agent-ultra-(debug|debuglog|raw)-\d{4}-\d{2}-\d{2}T/.test(f)) {
      try {
        await fs.deleteAsync(`${dir}/${f}`);
      } catch {}
    }
  }
} catch {}
```

This runs once on app startup and cleans up accumulated files from before the fix.

### Result After Fix

**In `agent-ultra-logs/`:**
- `ultra-devlog.jsonl` — grows from ~0 to ~600 KB over the session
- `debug-log.jsonl` — grows from ~0 to ~2.2 MB over the session
- `raw-export.jsonl` — created only on manual export, ~2.2 MB

**Total:** 3 files max, ~5 MB max, growing naturally, never truncated mid-session.

**In Settings > Logs tab:**
```
Ultra Dev Log [ultra-devlog.jsonl] - 145 KB  (grows as you use the app)
Debug Log [debug-log.jsonl] - 1,250 KB       (grows as you use the app)
```

Clean, simple, easy to share one file per log type.

---

## Why This Happens at the Technical Level

### writeAsStringAsync Behavior

From `expo-file-system/legacy`, `writeAsStringAsync(filePath, content)`:
- **Overwrites** the entire file at `filePath` with `content`
- Does NOT append
- Does NOT merge
- Does NOT check if file exists (creates it if missing, overwrites if present)

This is perfect for our use case — we want the latest buffer snapshot to overwrite all prior snapshots. But the current code defeats this by using a different filename each time, creating a new file instead of overwriting.

### Analogy

**Current behavior:** "Take a photo of my phone screen every 2 seconds, save each to a new file with timestamp. I want to look at the most recent photo."
- Result: thousands of photos, 99% are outdated.

**Desired behavior:** "Take a photo of my phone screen every 2 seconds, save each to the same file (overwrite). I want to look at the most recent photo."
- Result: one file, always the latest state.

The technical mechanism (`writeAsStringAsync`) is correct. The filename strategy (timestamp-based) is wrong.

---

## Timeline of Changes

| Date | Version | Event | Impact |
|------|---------|-------|--------|
| 2026-03-15 | v3.18.0 | Created LogFolder system with timestamped filenames | ~30 new files/minute |
| 2026-03-15 | v3.19.0 | Bug 6 fix: added DebugLog to LogFolder auto-flush | ~50+ new files/minute |
| 2026-03-15 | NOW | User reports hundreds of files after short sessions | Problem identified |

---

## What Does NOT Need to Change

- **Flush frequency:** 2-3 second debounce is appropriate
- **JSONL format:** Structured, parseable, correct
- **Buffer sizes:** MAX_MEMORY limits (3,000 / 15,000 entries) are reasonable
- **Settings UI:** Listing and sharing via `Sharing.shareAsync()` works correctly
- **ultra_dev_logs/ and debug_logs/:** These already use fixed session filenames and work correctly
- **Memory management:** Ring buffer behavior is correct

The **only** thing that needs to change is the filename strategy in the three LogFolder.writeLog() calls.

---

## Size Estimates (Post-Fix)

If a user runs the app for different durations:

| Duration | Ultra Log | Debug Log | Raw Export | Total |
|----------|-----------|-----------|-----------|-------|
| 15 min | ~150 KB | ~375 KB | 0 | ~525 KB |
| 1 hour | ~600 KB (max) | ~2.2 MB (max) | 0 | ~2.8 MB |
| 1 hour + export | ~600 KB | ~2.2 MB | ~2.2 MB | ~5 MB |

All well within typical phone storage.
