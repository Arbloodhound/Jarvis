# Agent Ultra — Comprehensive Bug Report

**Date:** 2026-03-15  
**Scope:** Logs tab crash (FIXED), remaining latent bugs, EAS build issues, full cross-linkage analysis  
**Purpose:** Self-contained reference for external resolution

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Bugs Fixed (Logs Tab Crash)](#2-bugs-fixed-logs-tab-crash)
3. [Remaining Bugs](#3-remaining-bugs)
4. [EAS Build Issue — metro.config.js](#4-eas-build-issue--metroconfigjs)
5. [File Cross-Linkage Map](#5-file-cross-linkage-map)
6. [Full Source: LogFolder.ts](#6-full-source-logfolderts)
7. [Full Source: UltraDevLog.ts (key sections)](#7-full-source-ultradevlogts-key-sections)
8. [Full Source: DebugLog.ts (key sections)](#8-full-source-debuglogts-key-sections)
9. [Full Source: settings.tsx (key sections)](#9-full-source-settingstsx-key-sections)
10. [Environment & Versions](#10-environment--versions)

---

## 1. Executive Summary

The Logs tab in `app/settings.tsx` crashed on load due to three bugs. All three are now fixed in the codebase. However, several latent bugs remain that affect log file output on Android (the target platform). These do not crash the app but cause logs to be written incorrectly or to unexpected directories.

### Fixed (crash-causing)
| # | Bug | File | Line(s) |
|---|-----|------|---------|
| 1 | `loadDebugLogs()` called but never defined | `app/settings.tsx` | was 121, 398 |
| 2 | `setImmediate` doesn't exist in React Native | `src/utils/UltraDevLog.ts` | was 817 |
| 3 | Dead state variables `debugLogs`/`debugLogsLoaded` | `app/settings.tsx` | removed |

### Remaining (latent, non-crashing)
| # | Bug | File | Line | Severity |
|---|-----|------|------|----------|
| 4 | `DocumentDirectoryPath` doesn't exist on `expo-file-system` | `src/services/LogFolder.ts` | 27 | Low (fallback works) |
| 5 | Double-slash in path (`documentDirectory` already ends with `/`) | `src/services/LogFolder.ts` | 29 | Low (Android tolerates) |
| 6 | Three separate log directories — Logs tab only reads one | Multiple | — | Medium (user confusion) |
| 7 | `settingsCostLimitSave` called with wrong types | `app/settings.tsx` | 338 | Low (cosmetic) |
| 8 | `metro.config.js` breaks EAS builds on Windows | root | — | Critical (build blocker) |

---

## 2. Bugs Fixed (Logs Tab Crash)

### Bug 1: `loadDebugLogs()` — function called but never defined

**Symptom:** App crashes immediately when the Logs tab is selected or when the Settings screen mounts with `?tab=logs`.

**Root cause:** Two calls to `loadDebugLogs()` existed in `settings.tsx`:
- Line 121 (in `useEffect` on mount): `if (initialTab === "logs") { loadDebugLogs(); }`
- Line 398 (in tab bar `onPress`): `if (t === "logs") { loadDebugLogs(); }`

The function `loadDebugLogs` was never defined anywhere in the file. The correct function is `loadLogs` (defined at line 348).

**Fix applied:** Replaced both calls with `loadLogs()`.

### Bug 2: `setImmediate` — doesn't exist in React Native runtime

**Symptom:** `ReferenceError: setImmediate is not defined` thrown when UltraDevLog attempts to flush logs.

**Root cause:** `UltraDevLog.flushSyncInternal()` used `setImmediate(() => UltraDevLog.doFlush())`. React Native does not polyfill `setImmediate` on all platforms (notably missing in Hermes and web).

**Fix applied:** Changed to `setTimeout(() => UltraDevLog.doFlush(), 0)` which is universally available.

### Bug 3: Dead state variables `debugLogs` / `debugLogsLoaded`

**Symptom:** TypeScript compile warnings; no runtime crash on their own, but they were remnants of the deleted `loadDebugLogs` function and indicated incomplete refactoring.

**Fix applied:** Removed both `useState` declarations.

---

## 3. Remaining Bugs

### Bug 4: `DocumentDirectoryPath` — wrong property name

**File:** `src/services/LogFolder.ts`, line 27

```typescript
const docDir = (fs as any)?.DocumentDirectoryPath || (fs as any)?.documentDirectory;
```

`DocumentDirectoryPath` is a property from the `react-native-fs` package (RNFS). This project uses `expo-file-system/legacy`, which exports `documentDirectory` (lowercase `d`). The property `DocumentDirectoryPath` will always be `undefined`, and the fallback to `documentDirectory` will always be used.

**Impact:** None at runtime (the fallback works), but the dead code is confusing and suggests a copy-paste from a `react-native-fs` example.

**Fix:** Change line 27 to:
```typescript
const docDir = fs?.documentDirectory;
```

### Bug 5: Double-slash in log directory path

**File:** `src/services/LogFolder.ts`, line 29

```typescript
this.LOGS_DIR_CACHE = `${docDir}/agent-ultra-logs`;
```

`FileSystem.documentDirectory` from expo-file-system always ends with a trailing slash (e.g., `file:///data/user/0/com.example/files/`). Adding `/agent-ultra-logs` creates `file:///data/user/0/.../files//agent-ultra-logs` (double slash).

**Impact:** Android's filesystem tolerates double slashes, so this works in practice. However, it can cause subtle bugs if paths are compared by string equality.

**Fix:** Change line 29 to:
```typescript
this.LOGS_DIR_CACHE = `${docDir}agent-ultra-logs`;
```

This matches the convention used everywhere else in the project (e.g., `UltraDevLog.ts` line 808: `` `${FileSystem.documentDirectory}ultra_dev_logs/` ``).

### Bug 6: Three separate log directories

The codebase writes logs to **three** different directories on the device. The Logs tab in Settings only reads from **one** of them.

| Directory | Writer | Reader |
|-----------|--------|--------|
| `${documentDirectory}ultra_dev_logs/` | `UltraDevLog.doFlush()` (line 808) | Nothing in the UI |
| `${documentDirectory}debug_logs/` | `DebugLog.flushToFile()` (line 362) | Nothing in the UI |
| `${documentDirectory}/agent-ultra-logs` | `LogFolder.writeLog()` (line 54), called by `UltraDevLog.doFlush()` and `DebugLog.exportAll()` | `LogFolder.listLogs()` → Settings Logs tab |

**Key observations:**
- `DebugLog.flushToFile()` (the periodic auto-flush every 3000ms) writes to `debug_logs/` but does NOT write to `agent-ultra-logs/`. Only `DebugLog.exportAll()` writes to `agent-ultra-logs/`, and `exportAll()` is never called automatically.
- `UltraDevLog.doFlush()` writes to BOTH `ultra_dev_logs/` AND `agent-ultra-logs/` (via `LogFolder.writeLog`).
- The Logs tab only shows files from `agent-ultra-logs/`.

**Impact:** DebugLog entries are silently lost from the Logs tab unless `DebugLog.exportAll()` is explicitly triggered.

**Fix options:**
1. Make `DebugLog.flushToFile()` also call `LogFolder.writeLog()` (simplest).
2. Consolidate all three directories into one.
3. Make the Logs tab read from all three directories.

### Bug 7: `settingsCostLimitSave` called with wrong argument types

**File:** `app/settings.tsx`, line 338

```typescript
DebugLog.settingsCostLimitSave(dailyLimit, taskLimit);
```

In settings.tsx, `DebugLog` is aliased to `UltraDevLog` (see line 24). `UltraDevLog.settingsCostLimitSave` is defined as:

```typescript
static settingsCostLimitSave(limit: number, success: boolean): void
```

But `dailyLimit` and `taskLimit` are both `string` state variables. So a string is passed where a number is expected, and another string where a boolean is expected.

**Impact:** The data is still logged (just with wrong types in the JSON), so no crash occurs.

**Fix:** Change line 338 to:
```typescript
UltraDevLog.settingsCostLimitSave(parseFloat(dailyLimit) || 0, true);
```

Or update `UltraDevLog.settingsCostLimitSave` to accept `(daily: string, task: string)`.

---

## 4. EAS Build Issue — metro.config.js

### Background

Replit's dev environment occasionally crashes Metro bundler because it tries to watch `.local/state/workflow-logs/` which doesn't always exist. A `metro.config.js` was created to add this path to `blockList`:

```javascript
const { getDefaultConfig } = require('expo/metro-config');
const path = require('path');
const config = getDefaultConfig(__dirname);
config.resolver.blockList = [
  /\.local\/state\/workflow-logs\/.*/,
  new RegExp(path.resolve(__dirname, '.local/state/workflow-logs') + '.*'),
];
module.exports = config;
```

### The problem

EAS Build runs on Windows CI machines. Node.js on Windows resolves `path.resolve(__dirname, ...)` to a Windows absolute path like `c:\Users\expo\workingdir\build\...`. When this is passed to `new RegExp(...)`, the resulting regex contains `c:` which Metro's regex engine interprets as a URI protocol. The error:

```
Error: EPERM: operation not permitted, lstat 'c:\Users\expo\workingdir\build\node_modules\...'
```

Both `.js` and `.cjs` extensions fail identically.

### Resolution

The `metro.config.js` file has been **removed** from the project. The Metro ENOENT crash in Replit is a transient environment issue that doesn't affect production builds. If it recurs in Replit, restart the Expo dev server.

**Do NOT add metro.config.js back.** If Metro config customization is needed for other reasons, use only static regex patterns (no `path.resolve`):

```javascript
config.resolver.blockList = [/\.local\/state\/workflow-logs\/.*/];
```

---

## 5. File Cross-Linkage Map

```
app/settings.tsx
  ├── imports LogFolder from src/services/LogFolder.ts
  ├── imports UltraDevLog (aliased as both DebugLog and UltraDevLog) from src/utils/UltraDevLog.ts
  ├── calls LogFolder.listLogs() → reads agent-ultra-logs/ directory
  ├── calls Sharing.shareAsync() for log file download
  └── does NOT import src/utils/DebugLog.ts (despite the alias name "DebugLog")

src/services/LogFolder.ts
  ├── imports expo-file-system/legacy
  ├── provides writeLog(), listLogs(), initialize()
  ├── manages agent-ultra-logs/ directory
  └── consumed by: settings.tsx, UltraDevLog.ts, DebugLog.ts

src/utils/UltraDevLog.ts (887 lines)
  ├── imports expo-file-system/legacy
  ├── imports LogFolder from src/services/LogFolder.ts
  ├── manages ultra_dev_logs/ directory (direct writes)
  ├── also writes to agent-ultra-logs/ via LogFolder.writeLog() in doFlush()
  ├── 10,000 entry in-memory ring buffer
  ├── auto-flushes every 2000ms on native
  └── consumed by: settings.tsx, index.tsx, and many components

src/utils/DebugLog.ts (666 lines)
  ├── imports expo-file-system/legacy
  ├── imports LogFolder from src/services/LogFolder.ts
  ├── manages debug_logs/ directory (direct writes)
  ├── writes to agent-ultra-logs/ ONLY via exportAll() (not auto-flush)
  ├── 15,000 entry in-memory ring buffer
  ├── auto-flushes every 3000ms on native (to debug_logs/ only)
  └── consumed by: settings.tsx (INDIRECTLY — settings.tsx uses UltraDevLog aliased as DebugLog)
```

### Data flow on native (Android):

```
User action → UltraDevLog.push() → entries[] (in-memory)
                                       │
                        every 2000ms   ▼
                              UltraDevLog.doFlush()
                                       │
                          ┌────────────┼────────────┐
                          ▼                         ▼
              ultra_dev_logs/              LogFolder.writeLog()
              session_xxx.jsonl                     │
                                                    ▼
                                          agent-ultra-logs/
                                          agent-ultra-debug-xxx.jsonl
                                                    │
                                         Settings Logs tab reads here
                                         via LogFolder.listLogs()
```

```
User action → DebugLog.push() → entries[] (in-memory)
                                      │
                       every 3000ms   ▼
                             DebugLog.flushToFile()
                                      │
                                      ▼
                           debug_logs/
                           debug_xxx.jsonl
                                      │
                            ╳ NOT visible in Settings Logs tab
                            ╳ LogFolder.writeLog() NOT called here
```

---

## 6. Full Source: LogFolder.ts

```typescript
import { Platform } from 'react-native';
import * as FileSystemModule from 'expo-file-system/legacy';

export interface LogFile {
  name: string;
  path: string;
  size: number;
  createdAt: number;
}

export class LogFolder {
  private static FS: any = null;
  private static LOGS_DIR_CACHE: string | null = null;
  private static initialized = false;

  private static getFS() {
    if (this.FS) return this.FS;
    if (Platform.OS === 'web') return null;
    this.FS = FileSystemModule;
    return this.FS;
  }

  // BUG 4: DocumentDirectoryPath is from react-native-fs, not expo-file-system.
  //         Always falls through to documentDirectory.
  // BUG 5: documentDirectory ends with '/', so the path gets a double slash.
  private static getLogsDir(): string {
    if (this.LOGS_DIR_CACHE) return this.LOGS_DIR_CACHE;
    const fs = this.getFS();
    if (!fs) return '';
    const docDir = (fs as any)?.DocumentDirectoryPath || (fs as any)?.documentDirectory;
    if (!docDir) return '';
    this.LOGS_DIR_CACHE = `${docDir}/agent-ultra-logs`;  // double slash here
    return this.LOGS_DIR_CACHE;
  }

  static async initialize() {
    const fs = this.getFS();
    const dir = this.getLogsDir();
    if (!fs || !dir || this.initialized) return;
    try {
      const info = await fs.getInfoAsync(dir);
      if (!info.exists) {
        await fs.makeDirectoryAsync(dir, { intermediates: true });
      }
      this.initialized = true;
    } catch (err) {
      console.error('[LogFolder] Init error:', err);
    }
  }

  static async writeLog(filename: string, content: string): Promise<boolean> {
    const fs = this.getFS();
    const dir = this.getLogsDir();
    if (!fs || !dir) return false;
    await this.initialize();
    try {
      const filePath = `${dir}/${filename}`;
      await fs.writeAsStringAsync(filePath, content);
      return true;
    } catch (err) {
      console.error('[LogFolder] Write error:', err);
      return false;
    }
  }

  static async listLogs(): Promise<LogFile[]> {
    try {
      const fs = this.getFS();
      const dir = this.getLogsDir();
      if (!fs || !dir) {
        console.warn('[LogFolder] No FileSystem or directory path');
        return [];
      }

      try {
        await this.initialize();
      } catch (initErr) {
        console.error('[LogFolder] Initialize error:', initErr);
        return [];
      }

      try {
        const info = await fs.getInfoAsync(dir);
        if (!info || !info.exists) {
          return [];
        }
      } catch (infoErr) {
        console.error('[LogFolder] getInfoAsync error:', infoErr);
        return [];
      }

      try {
        const files = await fs.readDirectoryAsync(dir);
        if (!Array.isArray(files)) return [];
        const logFiles: LogFile[] = [];

        for (const name of files) {
          try {
            const filePath = `${dir}/${name}`;
            const fileInfo = await fs.getInfoAsync(filePath, { size: true });
            if (fileInfo && fileInfo.exists && !fileInfo.isDirectory) {
              logFiles.push({
                name,
                path: filePath,
                size: (fileInfo.size as number) || 0,
                createdAt: fileInfo.modificationTime ? (fileInfo.modificationTime as number) * 1000 : 0,
              });
            }
          } catch (fileErr) {
            // Skip this file
          }
        }

        return logFiles.sort((a, b) => b.createdAt - a.createdAt);
      } catch (readErr) {
        console.error('[LogFolder] readDirectory error:', readErr);
        return [];
      }
    } catch (err) {
      console.error('[LogFolder] Unexpected error:', err);
      return [];
    }
  }

  static getFullPath(filePath: string): string {
    return filePath;
  }
}
```

---

## 7. Full Source: UltraDevLog.ts (key sections)

### File I/O section (lines 804–887)

```typescript
private static getLogDir(): string {
  if (!FileSystem?.documentDirectory) return '';
  return `${FileSystem.documentDirectory}ultra_dev_logs/`;  // NO double slash — correct
}

private static getSessionFilePath(): string {
  const dir = UltraDevLog.getLogDir();
  return dir ? `${dir}session_${UltraDevLog.sessionId}.jsonl` : '';
}

private static flushSyncInternal(): void {
  setTimeout(() => UltraDevLog.doFlush(), 0);  // FIXED: was setImmediate
}

private static scheduleFlush(): void {
  if (Platform.OS === 'web') return;
  if (UltraDevLog.pendingFlush) return;
  UltraDevLog.pendingFlush = true;
  setTimeout(() => { UltraDevLog.pendingFlush = false; UltraDevLog.doFlush(); }, 2000);
}

static async forceFlush(): Promise<void> { await UltraDevLog.doFlush(); }

private static async doFlush(): Promise<void> {
  if (Platform.OS === 'web' || !FileSystem) return;
  if (UltraDevLog.writing) return;
  UltraDevLog.writing = true;
  try {
    const dir = UltraDevLog.getLogDir();
    if (!dir) return;
    const info = await FileSystem.getInfoAsync(dir);
    if (!info.exists) await FileSystem.makeDirectoryAsync(dir, { intermediates: true });
    const content = UltraDevLog.entries.map(e => JSON.stringify(e)).join('\n') + '\n';
    await FileSystem.writeAsStringAsync(UltraDevLog.getSessionFilePath(), content);
    const ts = new Date().toISOString().replace(/[:.]/g, '-');
    await LogFolder.writeLog(`agent-ultra-debug-${ts}.jsonl`, content);  // ALSO writes to agent-ultra-logs/
  } catch {} finally { UltraDevLog.writing = false; }
}
```

### Legacy compatibility stubs (lines 529–612)

```typescript
static settingsApiSave(key: string, success: boolean): void { ... }
static settingsApiDelete(key: string, success: boolean): void { ... }
static settingsCostLimitSave(limit: number, success: boolean): void { ... }  // BUG 7: called with (string, string)
static settingsDefaultPick(mode: string, modelId: string): void { ... }
static settingsDefaultsSave(defaults: Record<string, unknown>): void { ... }
```

---

## 8. Full Source: DebugLog.ts (key sections)

### File I/O section (lines 360–475)

```typescript
private static getDir(): string {
  if (!FileSystem || !FileSystem.documentDirectory) return '';
  return `${FileSystem.documentDirectory}debug_logs/`;  // SEPARATE directory from LogFolder
}

private static scheduleFlush() {
  if (Platform.OS === 'web') return;
  if (DebugLog.pendingFlush) return;
  DebugLog.pendingFlush = true;
  setTimeout(() => {
    DebugLog.pendingFlush = false;
    DebugLog.flushToFile();  // does NOT call LogFolder.writeLog()
  }, 3000);
}

private static async flushToFile(): Promise<void> {
  if (Platform.OS === 'web') return;
  if (DebugLog.writing) return;
  DebugLog.writing = true;
  try {
    const dir = DebugLog.getDir();
    if (!dir) return;
    const dirInfo = await FileSystem.getInfoAsync(dir);
    if (!dirInfo.exists) {
      await FileSystem.makeDirectoryAsync(dir, { intermediates: true });
    }
    const filePath = DebugLog.getFilePath();
    const lines = DebugLog.entries.map(e => JSON.stringify(e)).join('\n') + '\n';
    await FileSystem.writeAsStringAsync(filePath, lines);
    // NOTE: Does NOT write to LogFolder — these logs are invisible in Settings Logs tab
  } catch (err) {
    console.error('[DebugLog] flush failed:', err);
  } finally {
    DebugLog.writing = false;
  }
}

static async exportAll(): Promise<string> {
  // ... reads from debug_logs/ and THEN writes to LogFolder:
  await LogFolder.writeLog(`agent-ultra-raw-${ts}.jsonl`, content);
  // But exportAll() is never called automatically
}
```

---

## 9. Full Source: settings.tsx (key sections)

### Import aliasing (line 24)

```typescript
import { UltraDevLog as DebugLog, UltraDevLog } from "@/src/utils/UltraDevLog";
```

**Important:** `DebugLog` in this file is NOT `src/utils/DebugLog.ts`. It is `UltraDevLog` aliased as `DebugLog`. The real `DebugLog` class is not imported here.

### Logs state and handlers (lines 82–375)

```typescript
const [logFiles, setLogFiles] = useState<LogFile[]>([]);
const [logsLoaded, setLogsLoaded] = useState(false);

const loadLogs = useCallback(async () => {
  try {
    const files = await LogFolder.listLogs();
    setLogFiles(files);
    setLogsLoaded(true);
  } catch (err: any) {
    console.error('[Settings] loadLogs error:', err);
    setLogFiles([]);
    setLogsLoaded(true);
  }
}, []);

const downloadLog = useCallback(async (filePath: string, filename: string) => {
  try {
    const canShare = await Sharing.isAvailableAsync();
    if (canShare) {
      await Sharing.shareAsync(filePath, { mimeType: "text/plain", dialogTitle: filename });
    } else {
      Alert.alert("Sharing unavailable", "Your device doesn't support file sharing.");
    }
  } catch (err: any) {
    Alert.alert("Error", err?.message || "Failed to share log file.");
  }
}, []);

useEffect(() => {
  if (tab === "logs" && !logsLoaded) loadLogs();
}, [tab, logsLoaded, loadLogs]);
```

### Logs tab UI (lines 699–729)

```tsx
{tab === "logs" && (
  <View style={styles.card}>
    <View style={styles.logHeader}>
      <Text style={styles.cardTitle}>Log Files</Text>
      <Pressable onPress={loadLogs} style={styles.logActionBtn}>
        <Ionicons name="refresh" size={16} color={DIM} />
      </Pressable>
    </View>
    {!logsLoaded ? (
      <Text style={styles.emptyText}>Loading logs...</Text>
    ) : logFiles.length === 0 ? (
      <Text style={styles.emptyText}>No log files yet. Tap refresh to check.</Text>
    ) : (
      <ScrollView style={styles.logScroll} nestedScrollEnabled>
        {logFiles.map((file) => (
          <Pressable key={file.path} onPress={() => downloadLog(file.path, file.name)} style={styles.logFileRow}>
            <View style={styles.logFileInfo}>
              <Text style={styles.logFileName}>{file.name}</Text>
              <Text style={styles.logFileSize}>{(file.size / 1024).toFixed(1)} KB</Text>
            </View>
            <Ionicons name="download-outline" size={16} color={ACCENT} />
          </Pressable>
        ))}
      </ScrollView>
    )}
  </View>
)}
```

---

## 10. Environment & Versions

| Item | Value |
|------|-------|
| Expo SDK | ~54.0.27 |
| expo-file-system | ^19.0.21 |
| expo-sharing | ^14.0.8 |
| React Native | (managed by Expo SDK 54) |
| JS Engine | Hermes (Android), V8 (web) |
| Target platform | Android APK (EAS Build) |
| EAS Build profile | `preview` |
| New Architecture | Enabled (`newArchEnabled: true` required by reanimated v4) |
| Build command | `eas build --platform android --profile preview` |
| Import path for file system | `expo-file-system/legacy` (SDK 54 removed `writeAsStringAsync` from main export) |

### expo-file-system/legacy exports (verified from .d.ts)

```
documentDirectory: string | null    ← correct property name
cacheDirectory: string | null
getInfoAsync(fileUri, options?)
readAsStringAsync(fileUri, options?)
writeAsStringAsync(fileUri, contents, options?)
deleteAsync(fileUri, options?)
makeDirectoryAsync(fileUri, options?)
readDirectoryAsync(fileUri)
```

**`DocumentDirectoryPath` does NOT exist** in expo-file-system. It is from `react-native-fs` (a completely different package not installed in this project).

---

## Quick-Fix Patch Summary

If you want to fix all remaining bugs in one pass:

### LogFolder.ts line 27
```diff
-    const docDir = (fs as any)?.DocumentDirectoryPath || (fs as any)?.documentDirectory;
+    const docDir = fs?.documentDirectory;
```

### LogFolder.ts line 29
```diff
-    this.LOGS_DIR_CACHE = `${docDir}/agent-ultra-logs`;
+    this.LOGS_DIR_CACHE = `${docDir}agent-ultra-logs`;
```

### settings.tsx line 338
```diff
-      DebugLog.settingsCostLimitSave(dailyLimit, taskLimit);
+      UltraDevLog.settingsCostLimitSave(parseFloat(dailyLimit) || 0, true);
```

### DebugLog.ts — make flushToFile also write to LogFolder (add after line 394)
```diff
      await FileSystem.writeAsStringAsync(filePath, lines);
+     const ts = new Date().toISOString().replace(/[:.]/g, '-');
+     await LogFolder.writeLog(`agent-ultra-raw-${ts}.jsonl`, lines);
```

### Do NOT add metro.config.js
The Metro ENOENT crash in Replit is a transient dev environment issue. Adding metro.config.js with `path.resolve` breaks EAS builds on Windows CI.
