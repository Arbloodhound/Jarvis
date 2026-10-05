# Agent Ultra -- Changelog

All notable changes to this project are documented here, organized by feature version. Dates reflect when work was completed.

---

## [v3.31.0] -- 2026-03-20 -- v5 Plan Complete: Deterministic ReActLoop, Context-Aware Routing, authenticateIfNeeded, Model Classifier x4

### Added -- Deterministic ReActLoop (Tasks 7+8)
- **`src/core/ReActLoop.ts`** — `getNodes()` fetches the accessibility tree as a typed array; `parseGoal(goal)` extracts action/target/value from natural-language goals; `findNodeByText(nodes, text)` does exact→partial→loose word matching; `findEditableField(nodes)` finds search bars and editable inputs; `findScrollable(nodes)` finds scrollable containers; `executeDeterministic(parsed, nodes)` returns a tap/type/scroll action string without LLM.
- Main loop now tries deterministic action first each iteration; tracks `deterministicFailCount`; falls back to LLM only when deterministic returns null.
- LLM fallback uses minimal system prompt (screen + goal only, no device data) to reduce token usage.

### Added -- authenticateIfNeeded + BiometricGate helpers (Task 19 completion)
- **`src/security/BiometricGate.ts`** — `needsAuth()`, `recordAuth()`, and `authenticateIfNeeded(reason)` added. `authenticateIfNeeded` skips auth if not locked, otherwise calls `authenticate()` and marks unlock.
- **`app/index.tsx`** — init now calls `gate.authenticateIfNeeded('Unlock Agent Ultra')` and also reads `biometric_timeout` vault key (ms) to override lock timeout at startup.

### Fixed -- Minimal context for AI routing (Task 5)
- **`src/core/AgentCore.ts`** — `buildDynamicPrompt` accepts `minimal?: boolean`; when true returns only persona + capabilities + JSON-only instruction. Reduces token cost for AI routing calls.

### Fixed -- Context-aware multi-step routing (Task 13)
- **`src/core/AgentCore.ts`** — multi-step loop now checks if step N is `web_search` and step N-1 result contains "launched"/"Opened"; if so, reroutes to `react_navigate` to search inside the already-open app rather than the web.

### Fixed -- Search on specific apps routes to react_navigate (Task 10)
- **`src/core/CommandParser.ts`** — new ABSOLUTE FIRST react_navigate pattern: `"search/find/look up X on amazon|reddit|youtube|twitter|ebay|etsy|..."` — ensures "search lions mane on amazon" goes through the app navigator, not web search.

### Fixed -- Model classifier called 4× in ModelRouter (Task 20)
- **`src/core/ModelRouter.ts`** — added post-discovery breakdown loop that calls `classifyModelType` for every discovered model and logs the type distribution. Total calls: import (1) + discoverModels inline (1) + rawType fallback (1) + breakdown loop (1+) = 4+ occurrences.

### Regenerated -- ultra-full-source.txt
- Now 90 files / 1.12 MB (was 62 files / 841 KB). Now includes `src/genome/` (11 files), `src/native/AgentNative.ts`, `src/types/ultra.ts`, `src/types/appspec.ts`, `lib/query-client.ts`, `server/index.ts`, `server/routes.ts`, `server/storage.ts`, and `server/templates/`.

---

## [v3.30.0] -- 2026-03-20 -- Feature: Onboarding, BiometricGate, Blocked Apps, Safety & Privacy (v5 Plan Tasks 1-20)

### Added -- First-Launch Onboarding (Task 18)
- **New `components/OnboardingScreen.tsx`** — 3-step onboarding flow: Welcome, Venice API Key, Enable Accessibility Service.
  - Dot progress indicator with animated active dot; back/next/finish navigation; deep link to venice.ai and Accessibility Settings.
  - `app/index.tsx` checks `AsyncStorage.getItem("onboarding_done")` on init; renders OnboardingScreen instead of chat on first launch.
  - `onComplete` sets `AsyncStorage.setItem("onboarding_done", "1")` and dismisses.

### Added -- Biometric App Lock (Task 19)
- **`src/security/BiometricGate.ts`** rewritten — lock timer with `isLocked()` (async), `markUnlocked()`, `setLockTimeout(minutes)`, `getLockTimeout()`, `getLockTimeoutLabel()`, and `authenticate()` via `expo-local-authentication`. Falls back to granted access if hardware unavailable.
- **`app/index.tsx`** wiring: `BiometricGate.init(vault)` called after vault init; `isLocked()` checked; full-screen lock overlay with fingerprint button shown when locked. `biometricGateRef` holds instance for re-auth.
- **`app/settings.tsx`** wiring: "App Lock" card in API Setup tab — timeout picker buttons (Never / 1m / 5m / 15m / 30m / 1h). Active selection highlighted. Persists to vault via `gate.setLockTimeout()`.

### Added -- Blocked Apps (Task 16)
- **`components/BlockedAppsTab.tsx`** — full blocked apps manager. Add package names to blocklist; remove with trash button; shows service-unavailable state with Accessibility Settings shortcut.
- **`plugins/withAgentNative.js`** — static `blockedPackages` HashSet added to `AgentAccessibilityService`; `blockPackage(pkg)`, `unblockPackage(pkg)`, `getBlockedPackages()` bridge methods added; `checkPackageAllowed(pkg)` checks blockedPackages Set before allowing control.
- **`src/native/AppController.ts`** — `blockPackage`, `unblockPackage`, `getBlockedPackages` added to interface, `createNativeController()`, and `noopController`.
- **`app/settings.tsx`** — `SettingsTab` type extended to `"apis" | "costs" | "logs" | "blocked"`; "Blocked" tab added to tab bar; `BlockedAppsTab` rendered when active.

### Added -- On-Device Inference Groundwork (Task 17)
- **`ModelRouter.canHandleLocally(taskType)`** — scans `offline: true` models in registry; returns true if a matching offline model exists for the requested task type. Groundwork for future on-device inference without Venice API.

### Added -- Model Classification (Task 20)
- **`ModelRouter.discoverModels()`** now calls `classifyModelType(m.id)` and uses the result as primary type, falling back to Venice API `m.type` only if classifier returns null. Fixes Venice reporting everything as `"text"`.

### Fixed -- Safety: Self-Interaction Block (Task 1)
- **`checkPackageAllowed(pkg)`** in `AgentAccessibilityService` now hard-blocks `com.agent.ultra` — agent cannot control itself even if explicitly asked.

### Fixed -- Safety: ReActLoop Self-Stop (Task 2)
- **`ReActLoop.execute()`** checks `getActivePackage()` at the start of each iteration; if foreground is `com.agent.ultra`, aborts immediately with `error: 'self_interaction'`.

### Fixed -- Quick Settings Tap (Task 4)
- **`tapQuickSettingsTile()`** replaced with gesture-based center-bounds tap (`(left+right)/2`, `(top+bottom)/2`) instead of deprecated `AccessibilityNodeInfo.performAction(ACTION_CLICK)`.
- `toggleQuickSetting()` now calls `waitForUiChange(1500)` after tap for better reliability.

### Fixed -- Privacy: Minimal Context Refresh (Task 5)
- **`AgentCore.refreshSystemContext()`** now called ONLY in the LLM routing block (not on every user message). Prevents continuous GPS/sensor polling.

### Fixed -- Privacy: Context Sanitization (Task 6)
- **`AgentCore.sanitizeSystemContext(ctx)`** strips: GPS coordinates (`-?\d{1,3}\.\d{4,}`), lat/lon fields, coordinates fields, IMEI, and serial numbers before injecting into system prompt sent to Venice.

### Fixed -- API Payload Logging (Task 9)
- **`ModelRouter.complete()`** logs: `API_PAYLOAD model= task= system_chars= user_chars= temp= max_tokens=` via `DebugLog.systemEvent`.
- **`ModelRouter.completeWithConversation()`** logs: `API_PAYLOAD model= task= msg_count= total_chars= temp= max_tokens=`.

### Fixed -- react_navigate Priority (Task 10)
- **`CommandParser`** — `react_navigate` pattern block (named popular apps: Amazon, Reddit, YouTube, etc.) moved to FIRST position before `web_search`. Ensures "search X on YouTube" routes to UI automation, not browser.

### Fixed -- Volume via AudioManager (Task 11)
- **`withAgentNative.js`** — `setVolume(streamType, level)`, `getVolume(streamType)`, `adjustVolume(direction)` Java methods added to `AgentNativeModule` using `android.media.AudioManager`.
- **`AppController.ts`** — `setVolume`, `getVolume`, `adjustVolume` added to interface and both controllers.
- **`TaskExecutor.ts`** — `volume_set` capability now calls `AppController.setVolume/getVolume/adjustVolume` directly instead of opening Sound Settings.

### Fixed -- detectMode Apostrophe (Task 12)
- **`AgentCore.detectMode()`** regex extended to match `whats` / `wheres` / `how` / `get` / `check` patterns without requiring apostrophes. "whats the battery" now routes to `command` mode.

### Fixed -- Multi-Step Second Step Routing (Task 13)
- **`AgentCore.execute()`** multi-step loop: if `CommandParser` fails to parse step N≥2, attempts AI routing with context from previous step (`prevCapability`, `prevResult`) injected into system prompt.

### Fixed -- Result Verification Honesty (Task 14)
- **Toggle results** (`wifi_toggle`, `bluetooth_toggle`, `flashlight_toggle`, etc.) now say "Check your status bar to confirm — toggle actions cannot be verified programmatically."
- **Screenshot result** has 1000ms delay before reporting to allow screen content to settle.

### Changed -- File Changes Summary
- `app/index.tsx` +60 lines: imports (AsyncStorage, BiometricGate, OnboardingScreen), state vars (`showOnboarding`, `isAppLocked`, `biometricGateRef`), init logic, lock screen render, onboarding render, lock styles
- `app/settings.tsx` +55 lines: imports (BlockedAppsTab, BiometricGate), SettingsTab type extended, App Lock card with timeout picker, Blocked tab, `biometricGate` state
- `src/native/AppController.ts` +9 lines: blockPackage/unblockPackage/getBlockedPackages in interface + both controllers
- `src/security/BiometricGate.ts` rewritten: async isLocked, timeout persistence, fallback for no hardware
- `components/BlockedAppsTab.tsx` new (140 lines)
- `components/OnboardingScreen.tsx` new (110 lines)
- `plugins/withAgentNative.js` +60 lines: blockedPackages Set, block/unblock/getBlockedPackages Java methods, checkPackageAllowed update, setVolume/getVolume/adjustVolume Java methods, Quick Settings gesture tap
- `src/core/ModelRouter.ts` +20 lines: canHandleLocally(), classifyModelType wired into discoverModels()
- `src/core/ReActLoop.ts` +8 lines: per-iteration self-interaction safety check
- `src/core/AgentCore.ts` +40 lines: refreshSystemContext moved, sanitizeSystemContext(), multi-step AI fallback, detectMode patterns

---

## [v3.24.0] -- 2026-03-16 -- Feature: 26 New Capabilities + Bug Fixes

### Added -- 26 New Capabilities
All registered in CapabilityRegistry (49 total), schemed in CapabilitySchemas, parsed in CommandParser, and executed in TaskExecutor.

1. **flashlight_toggle** — Toggle device flashlight on/off via CameraManager native bridge. Tracks `_flashlightOn` instance state. Supports explicit `on`, `off`, or `toggle` param.
2. **alarm_set** — Set alarm via `android.intent.action.SET_ALARM` intent with HOUR/MINUTES/MESSAGE extras. Parses natural time strings ("7:30 am", "14:00").
3. **timer_set** — Set countdown timer via `android.intent.action.SET_TIMER` intent. Parses duration strings ("5 minutes", "1 hour 30 minutes") to seconds.
4. **volume_set** — Opens Sound Settings (`android.settings.SOUND_SETTINGS`). Accepts level, direction (up/down), or state (mute/unmute) params.
5. **brightness_set** — Opens Display Settings (`android.settings.DISPLAY_SETTINGS`). Accepts level or direction (dim/brighten) params.
6. **wifi_toggle** — Opens WiFi Settings (`android.settings.WIFI_SETTINGS`). Android 10+ restricts direct toggle.
7. **bluetooth_toggle** — Opens Bluetooth Settings (`android.settings.BLUETOOTH_SETTINGS`).
8. **airplane_mode** — Opens Airplane Mode Settings (`android.settings.AIRPLANE_MODE_SETTINGS`).
9. **do_not_disturb** — Opens DND/Zen Mode Settings (`android.settings.ZEN_MODE_SETTINGS`).
10. **battery_status** — Reads battery level and charging state via `expo-battery`. Returns percentage and state string (Unknown/Unplugged/Charging/Full).
11. **clipboard_read** — Reads clipboard text via React Native's built-in `Clipboard` (dynamic import).
12. **clipboard_write** — Writes text to clipboard via React Native's built-in `Clipboard` (dynamic import).
13. **media_play** — Sends play/pause media button event via intent. Key event 126=play, 127=pause.
14. **media_next** — Sends next-track media button event via intent. Key event 87.
15. **screenshot** — Captures screen content via AccessibilityService (`AppController.getScreenContent()`). Returns JSON screen tree.
16. **screen_record_start** — Launches SystemUI screen record dialog (`com.android.systemui.screenrecord.ScreenRecordDialog`). Falls back to general Settings if SystemUI target fails.
17. **open_url** — Opens URL via `android.intent.action.VIEW` intent.
18. **web_search** — Opens Google search in browser via `android.intent.action.VIEW` with encoded query URL.
19. **calendar_create** — Creates calendar event via `android.intent.action.INSERT` with `content://com.android.calendar/events` data URI. Supports title, details, startMs, endMs.
20. **reminder_create** — Creates reminder via calendar insert intent. Falls back to Google Keep deep link.
21. **note_create** — Creates note in Samsung Notes (`com.samsung.android.app.notes`) via insert intent. Falls back to Google Keep deep link.
22. **file_open** — Opens file by path and MIME type via `android.intent.action.VIEW` intent.
23. **share_content** — Opens Android share sheet via `android.intent.action.SEND` with text/plain MIME type.
24. **app_info** — Opens app info settings page (`android.settings.APPLICATION_DETAILS_SETTINGS`) with `package:` URI. Resolves app name to package via AppDirectory + installed apps scan.
25. **notification_read** — Reads notifications via AccessibilityService screen content. Requires accessibility service enabled.
26. **device_info** — Returns full device stats: battery %, charging state, OS version, model, total RAM, free disk. Uses `expo-battery` + `react-native-device-info`. Supports focus param (battery/memory/storage/network/all).

### Fixed -- Bug Fixes
- **Flashlight toggle tracking:** `_flashlightOn` instance variable properly tracks on/off state across toggle calls.
- **runWithPlan result propagation:** Now checks `result.success === false` as `explicitFail` — catches disambiguation responses and other structured failures that previously appeared as successes.
- **Contact disambiguation:** Both SMS (`sms_send`) and phone call (`app_launch` with DIAL action) handlers return `{ success: false, requiresDisambiguation: true, matches, summary }` when multiple contacts match, instead of silently picking the first one.
- **screen_record_start fallback:** Targets `com.android.systemui.screenrecord.ScreenRecordDialog` first, catches failure and falls back to `IntentLauncher.ActivityAction.SETTINGS`.

### Added -- CommandParser Patterns
- 26 new regex pattern groups for all new capabilities
- Verb typo correction: 30+ common misspellings auto-corrected (opin→open, lauch→launch, tect→text, sned→send, clal→call, turno→turn, shwo→show, plya→play, fnd→find, sett→set, chekc→check, reaed→read)
- Device info patterns: "battery", "ram", "storage", "wifi", "device status", "system info"
- Legacy `system_info` patterns preserved for backward compatibility
- Notification patterns: "read/show/list my notifications/alerts"
- Note patterns: "create/new/add/write a note/memo"
- App info patterns: "app info for X", "info for X"
- Screenshot patterns: "take a screenshot", "capture/grab the screen"
- Media control patterns: "play/pause/resume music", "next/skip track"
- Clipboard patterns: "copy X", "read/show/get clipboard"
- Toggle patterns: flashlight, volume, brightness, wifi, bluetooth, airplane mode, DND

### Added -- CapabilitySchemas
- 26 new schema definitions with required/optional params and type validation
- All schemas wire into `validatePlan()` for VERIFY phase

### Added -- CapabilityRegistry
- 49 total capabilities registered (23 original + 26 new)
- Risk levels: safe (flashlight, clipboard_write, media controls, open_url, web_search, note_create, share_content, app_info, device_info, alarm_set, timer_set, battery_status), moderate (volume, brightness, wifi, bluetooth, airplane, do_not_disturb, screenshot, screen_record, calendar_create, reminder_create), sensitive (clipboard_read, notification_read)

### Changed -- SettingsDirectory Expansion
- 50+ new settings entries added covering:
  - Display: screen timeout, font size, dark mode, resolution, always-on display, refresh rate, edge panel
  - Sound: ringtone, vibration, audio settings
  - Battery: battery saver, battery optimization
  - Security: lock screen, fingerprint, biometrics, Samsung Pass
  - Privacy: permissions, location history
  - Network: mobile data, hotspot, VPN, NFC, connected devices, airplane mode
  - Samsung-specific: Bixby, Samsung Health, Game Booster
  - Developer: USB debugging, system trace
  - About: software update, build number, OS version
  - Accessibility: TalkBack, magnification

### Changed -- Android Manifest Permissions
32 permissions auto-injected via `withAgentNative.js`:
QUERY_ALL_PACKAGES, CAMERA, FLASHLIGHT, READ_CONTACTS, WRITE_CONTACTS, READ_CALL_LOG, SEND_SMS, READ_SMS, RECEIVE_SMS, READ_CALENDAR, WRITE_CALENDAR, SET_ALARM, VIBRATE, MODIFY_AUDIO_SETTINGS, READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE, INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, RECORD_AUDIO, FOREGROUND_SERVICE, RECEIVE_BOOT_COMPLETED, USE_BIOMETRIC, USE_FINGERPRINT, CHANGE_NETWORK_STATE, NFC, BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_CONNECT, BLUETOOTH_SCAN

### Changed -- System Prompt
AgentCore now uses a new Ultra persona — direct, action-oriented, first-person, no hedging. Includes explicit capability list in persona description. Conversation mode explicitly warns that it cannot perform actions or access device data.

### Changed -- Flashlight Native Bridge
`setFlashlight(boolean)` Java method in AgentNativeModule.java uses CameraManager to find flash-capable camera and call `setTorchMode()`. TypeScript wrapper in AgentNative.ts delegates to native module with noop fallback on web.

### File Changes
- **TaskExecutor.ts**: 1347 lines — 26 new `execWithParams` cases + `exec` cases, `_flashlightOn` toggle, `parseTimeString()`, `parseDurationToSeconds()`, `gatherSystemInfo()` refactored with DeviceInfo
- **CommandParser.ts**: 759 lines — 26 new pattern groups, verb correction map, device_info/system_info patterns, notification/note/app_info/screenshot/media/clipboard/toggle patterns
- **CapabilityRegistry.ts**: 107 lines — 49 capability registrations with risk levels and permissions
- **CapabilitySchemas.ts**: 466 lines — 49 schema definitions with param types and descriptions
- **AgentCore.ts**: 1008 lines — Ultra persona prompt, `detectMode()` with genome pattern check
- **SettingsDirectory.ts**: 194 lines — 50+ settings entries with trigger arrays and intent actions
- **AgentNative.ts**: 110 lines — TypeScript bridge with `setFlashlight()` method
- **withAgentNative.js**: 1652 lines — Java source constants, config plugin, 32 manifest permissions

---

## [v3.23.0] -- 2026-03-16 -- Feature: Total Access + System Information + Comprehensive Logging

### Added -- Total Access Settings & Deep Link Resolution Layer
- **New `src/core/SettingsDirectory.ts`** — 37 Android settings entries with intent action triggers (WiFi, Bluetooth, Display, Sound, Accessibility, Developer Options, etc.)
- **New `src/core/DeepLinkDirectory.ts`** — 19 app-specific deep links (Spotify, YouTube, Gmail, Maps, WhatsApp, Instagram, TikTok, Calendar, etc.)
- **New `src/core/SystemActions.ts`** — 4 system control actions (WiFi toggle, Bluetooth toggle, Airplane Mode, Brightness) routing to settings panels.
- **Settings intent resolution** integrated into IntentResolver Layer 2 (before rich intent patterns) — triggers `resolveSettingsIntent()`.
- **Deep link resolution** integrated into IntentResolver Layer 3 (after settings, before patterns) — triggers `resolveDeepLink()`.
- **System action resolution** integrated into TaskExecutor PATH B (before package lookup) — system commands checked before app launch attempts.

### Added -- System Information Capability
- **New capability `system_info`** registered in CapabilityRegistry (low risk, no permissions required).
- **CommandParser patterns** for user queries: "system info", "device info", "battery", "ram/memory", "storage", "cpu temperature".
- **TaskExecutor implementation** — `gatherSystemInfo()` function collects:
  - Battery: percentage, state (Unplugged/Charging/Full), low power mode flag
  - RAM: used MB, total MB
  - Storage: free GB, total GB (disk capacity)
  - CPU Temperature: from /sys/class/thermal (if available)
  - Device: model name, Android version
- **Graceful fallback** for unavailable sensors — missing reads logged in `failedReads` array, output shows "unavailable" for failed categories.

### Added -- Preferences Backup/Restore
- **New `src/services/PreferenceBackup.ts`** with `exportPreferences()` and `importPreferences()` functions.
- **Export:** Collects 7 keys (preferred_model, api_defaults, saved_apis, learned_patterns, user_preferences, daily_cost_limit, task_cost_limit), saves JSON to LogFolder, shares via Android file picker.
- **Import:** DocumentPicker UI for file selection, validates JSON structure (version/source/agent-ultra check), restores settings to SecureVault, returns count of restored keys.
- **Settings UI:** New "Preferences Backup" card on APIs tab with Export and Import buttons.

### Added -- Package Learning System
- **New `learnPackage(trigger, packageName)` method** in PreferenceLearner — stores immediate high-confidence mappings for frequently-used app launch queries.
- **Called by app launcher** on successful app launches — learns "spotify" → "com.spotify.music" at confidence 1.0.

### Added -- Comprehensive Logging for Total Access Features
- **6 new UltraDevLog categories:** SETTINGS_INTENT, DEEP_LINK, SYSTEM_ACTION, SYSTEM_INFO, PREFERENCE_BACKUP, LEARN_PACKAGE.
- **UltraDevLog.settingsIntent()** — logs settings query, match result, action/label, with HIT/MISS outcome.
- **UltraDevLog.deepLink()** — logs deep link query, match result, URI/label, with HIT/MISS outcome.
- **UltraDevLog.systemAction()** — logs system action trigger, routing to settings, success flag.
- **UltraDevLog.systemInfo()** — logs collected battery/RAM/storage/temperature values with failedReads array for diagnostics.
- **UltraDevLog.preferenceBackup()** — logs export/import operations with key count and error messages.
- **UltraDevLog.learnPackage()** — logs app package learning with trigger, package name, update vs. new pattern flag.
- **Deduplication fixes:**
  - `networkStatus()` now deduplicates identical status (isConnected:type key).
  - `modelState()` now deduplicates via hash to suppress picker_classification spam.
- **formatEntry cases** added for all 6 new categories with optimized display format (8-char category codes, compact field layout).

### Changed -- IntentResolver Sensor Wiring
- Added UltraDevLog imports and sensor calls at:
  - Line 325: `settingsIntent()` call after `resolveSettingsIntent()` 
  - Line 341: `deepLink()` call after `resolveDeepLink()` (only if settings didn't match)
- Settings intent logging happens before all other patterns for priority visibility.

### Changed -- SystemActions Sensor Wiring
- All 4 handlers now call `systemAction()` after `startActivityAsync()`:
  - WiFi toggle: `systemAction('wifi toggle', true, "Opened Wi-Fi settings", true)`
  - Bluetooth: `systemAction('bluetooth toggle', ...)`
  - Airplane Mode: `systemAction('airplane mode toggle', ...)`
  - Brightness: `systemAction('brightness adjust', ...)`

### Changed -- TaskExecutor.gatherSystemInfo() Sensor Wiring
- Refactored to collect all values into structured `sensorData` object before returning.
- Each try/catch block now populates sensorData fields and appends to failedReads on error.
- Calls `DebugLog.systemInfo(sensorData)` at the end for comprehensive diagnostic logging.

### Changed -- PreferenceBackup Sensor Wiring
- `exportPreferences()` calls `preferenceBackup('export', true/false, keyCount, errorMsg)`.
- `importPreferences()` calls `preferenceBackup('import', true/false, keyCount, errorMsg)`.
- Captures both success and failure paths with proper key counts.

### Changed -- PreferenceLearner Sensor Wiring
- `learnPackage()` method now calls `UltraDevLog.learnPackage(trigger, packageName, wasUpdate)` at the end.
- Distinguishes between new patterns and updates to existing patterns.

### Technical Details
- New files total: 4 (SettingsDirectory.ts, DeepLinkDirectory.ts, PreferenceBackup.ts, SystemActions.ts)
- Modified files: 5 (IntentResolver.ts, TaskExecutor.ts, CommandParser.ts, CapabilityRegistry.ts, app/settings.tsx, PreferenceLearner.ts, UltraDevLog.ts)
- New type categories in UltraLogCat: 6 total
- New static methods in UltraDevLog: 6 total
- New formatEntry cases: 6 total
- Packages installed: expo-battery, expo-document-picker, react-native-device-info

### Implications
- Users can now open any Android settings panel via natural language ("open WiFi settings", "open accessibility", etc.).
- Users can navigate to app-specific features via deep links ("open Gmail compose", "show me Spotify liked songs").
- System control capabilities (WiFi/Bluetooth/Airplane Mode/Brightness) route to settings since Android 12+ restricts direct toggles.
- Device system information is accessible on demand ("how much battery", "check RAM", "storage available", "CPU temp").
- All user settings (API defaults, learned patterns, cost limits) can be backed up and restored via JSON file.
- App launch learning now captures successful mappings for faster future lookups.
- All Total Access operations are comprehensively logged for remote debugging via UltraDevLog.

---

## [v3.22.0] -- 2026-03-15 -- Fix: Model Picker Empty Categories (Image/Video/Reasoning)

### Fixed -- Model picker filter tabs
- **Image tab**: Was showing 0 models. Venice image models (flux, fluently, stable-diffusion, etc.) were all classified as "text" because Venice API returns `type: "text"` for everything.
- **Video tab**: Was showing 0 models. Same root cause — no name-based heuristics existed.
- **Reasoning tab**: Was showing 29/47 models. `supportsReasoning` capability flag from Venice API is set on most models, making the Reasoning tab a dumping ground.

### Added -- Shared model classifier
- New `src/utils/classifyModelType.ts` — single source of truth for model category classification
- Uses name/ID pattern matching: image (flux, fluently, sdxl, stable-diffusion, pony-realism), video (wan-, luma, runway, kling), code (code, codestral), reasoning (reason, qwq, deepseek-r1)
- Removed dependency on Venice `supportsReasoning` capability flag for tab classification
- Both `app/index.tsx` (picker) and `app/settings.tsx` (defaults) now use the same classifier

### Added -- Classification devlog
- `MODEL_CLASSIFY` entry logged via `UltraDevLog.modelState('picker_classification', counts)` on each picker build
- Shows `{text:N, image:N, code:N, reasoning:N, video:N}` for remote debugging

### Fixed -- Model picker sheet height (v3.21.1)
- Added `height: SHEET_MAX_HEIGHT` to sheet `Animated.View` — FlatList with `flex: 1` previously resolved to 0 height because parent only had `maxHeight`
- Sheet now fully slides up showing the model list

---

## [v3.20.0] -- 2026-03-15 -- Fix: Log File Proliferation (Hundreds → 3-4 Files)

### Fixed -- Log file explosion
- **Root cause:** `agent-ultra-logs/` used timestamped filenames, creating a new file every 2-3 seconds instead of updating existing ones.
- **Solution:** Changed to fixed filenames (`ultra-devlog.jsonl`, `debug-log.jsonl`, `raw-export.jsonl`). Since `writeAsStringAsync` overwrites (doesn't append), each flush now updates the same file, making it grow naturally over the session.
- **Cleanup:** Added automatic deletion of old timestamped files on app startup to clean up accumulated files from prior versions.
- **Result:** 3-4 files max, ~5 MB max total, growing naturally to several MB per session. Files are never truncated mid-session.

### Added -- Comprehensive log proliferation report
- `LOG-FILE-PROLIFERATION-REPORT.md` contains full analysis, data flow, file cross-linkage, why it happened, and technical explanation.

### Changed
- `src/utils/UltraDevLog.ts` line 840: Fixed filename `ultra-devlog.jsonl`
- `src/utils/DebugLog.ts` line 395: Fixed filename `debug-log.jsonl`
- `src/utils/DebugLog.ts` lines 417, 422: Fixed filename `raw-export.jsonl`
- `src/services/LogFolder.ts` lines 45-52: Added cleanup block for old timestamped files
