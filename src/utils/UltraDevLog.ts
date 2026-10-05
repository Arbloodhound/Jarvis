/**
 * UltraDevLog v3 -- Runtime Diagnostic System for Agent Ultra
 * ============================================================
 *
 * v3 CHANGES (all from ULTRADEVLOG_CAPABILITY_MAP.txt):
 *
 * NEW SENSORS:
 *   N1. COMPONENT_LIFECYCLE  -- proves ChatScreen mount/unmount
 *   N2. SETTINGS_SAVE        -- save button tap + vault write result
 *   N3. EXECUTE_PHASE        -- named phase entry, narrows crash location
 *   N4. NAV_CHANGE           -- navigation events, identifies remount trigger
 *   N5. FOCUS_EFFECT_DEPS    -- which dep changed on useFocusEffect trigger
 *   N6. PROCESS_RESTART      -- Android process kill vs suspension detection
 *   N7. PICKER_CONTENT       -- model list render count inside sheet
 *   N8. CONTEXT_PROVIDER     -- AgentCoreProvider verification sensor
 *
 * DATA QUALITY FIXES:
 *   Q1. listHeightPx=0: messageRendered() warns when listHeight unavailable
 *   Q2. VAULT_WRITE success is now boolean, not byte count
 *   Q3. APP_STATE_CHANGE elapsed correct on first entry (init to Date.now())
 *   Q4. PICKER_ANIMATE mount noise suppressed with 500ms mountedAt guard
 *   Q5. uiState() deduplicates -- skips identical snapshots
 *   Q6. pickerOpen() warns correctly; slideAnim should be read before setValue()
 *
 * NOISE REDUCTION:
 *   R1. conversationList / conversationLoaded only log on count change
 *   R2. High-freq vault reads (api key, base url) deduplicated within 1s
 *   R3. processingState() includes durationMs since last transition
 */

import { Platform, AppState, AppStateStatus } from 'react-native';
import * as ExpoFileSystem from 'expo-file-system/legacy';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { LogFolder } from '@/src/services/LogFolder';
import { CorrIdScope } from './CorrIdScope';

const FileSystem: any = Platform.OS !== 'web' ? ExpoFileSystem : null;
const PROCESS_RESTART_KEY = 'ultra_last_background_ts';

export type UltraLogCat =
  // ── Agent / execution ──
  | 'AGENT_EXEC_START' | 'AGENT_INIT_DONE' | 'AGENT_INIT_START'
  | 'AGENT_INIT_SUB' | 'AGENT_STEP'
  | 'CORE_INSTANCE' | 'EXECUTOR_BRANCH' | 'EXECUTE_PHASE'
  | 'INIT_CHECKPOINT' | 'INIT_FATAL'
  | 'TASK_STORE' | 'TASK_WATCHDOG'
  // ── AI / model ──
  | 'AI_CALL' | 'AI_REQUEST' | 'AI_RESPONSE'
  | 'API_CALL' | 'API_STATE_SNAPSHOT'
  | 'MODEL_ABORT' | 'MODEL_API_ERR' | 'MODEL_API_REQ'
  | 'MODEL_API_RESP' | 'MODEL_DISCOVERY_DETAIL'
  | 'MODEL_DISC_DONE' | 'MODEL_DISC_ERR' | 'MODEL_DISC_START'
  | 'MODEL_GET_DEFAULT' | 'MODEL_IMG_ERR' | 'MODEL_IMG_REQ'
  | 'MODEL_IMG_RESP' | 'MODEL_INVENTORY_SYNC'
  | 'MODEL_SET_DEFAULT' | 'MODEL_SET_DEFAULT_ERR'
  | 'MODEL_STATE' | 'MODEL_SWITCH'
  | 'ROUTING_ASSIGNMENT' | 'ROUTING_ASSIGNMENT_CHANGE'
  // ── App / launch ──
  | 'APP_INTEL_EXTRACT' | 'APP_INTEL_LAUNCH'
  | 'APP_INTEL_READAPP' | 'APP_INTEL_SEARCH'
  | 'APP_LAUNCH_AI' | 'APP_LAUNCH_BEGIN'
  | 'APP_LAUNCH_DEVICE' | 'APP_LAUNCH_FAIL'
  | 'APP_LAUNCH_FIRE' | 'APP_LAUNCH_MATCH'
  | 'APP_LAUNCH_RESUME' | 'APP_STATE_CHANGE'
  // ── Brain / cortex ──
  | 'CORTEX_DECOMPOSE' | 'CORTEX_REPLAN' | 'CORTEX_RESULT'
  | 'CORTEX_ROUTE' | 'CORTEX_STEP'
  | 'REACT_LOOP_DIFF' | 'REACT_LOOP_STEP'
  | 'CTX_AGGREGATE' | 'PLAN' | 'MODE'
  // ── Build ──
  | 'BUILD_DONE' | 'BUILD_PHASE' | 'BUILD_START'
  // ── Conversation ──
  | 'CONV_CONTEXT_SENT' | 'CONV_DEL' | 'CONV_ERR'
  | 'CONV_LIST' | 'CONV_LOAD' | 'CONV_MSG'
  | 'CONV_NEW' | 'CONV_SAVE'
  // ── Cost / budget ──
  | 'BUDGET_CHECK' | 'COST' | 'COST_LIMIT'
  // ── Debug ──
  | 'BUG_REPORT_WRITE'
  | 'DEBUG_SCREENSHOT' | 'DEBUG_SCREENSHOT_FAIL'
  // ── Device / hardware / sensors ──
  | 'A11Y_CLICK' | 'A11Y_CONTENT' | 'A11Y_DUMPSYS'
  | 'A11Y_HEARTBEAT' | 'A11Y_NOTIF' | 'A11Y_QS_TRACE'
  | 'A11Y_STATE' | 'A11Y_WINDOW'
  | 'ACTIVITY_STACK' | 'ALARM_STATE' | 'AUDIO_STATE'
  | 'CAMERA_RECOVERY' | 'CPU_STATE' | 'DEVICE_IDLE'
  | 'DEVICE_INFO' | 'INPUT_STATE' | 'NOTIFICATION_STATE'
  | 'PACKAGE_STATE' | 'POWER_STATE' | 'PROC_STATE'
  | 'THERMAL' | 'WIFI_STATE' | 'WINDOW_STATE'
  | 'CRASH_NATIVE' | 'PROCESS_RESTART'
  // ── Genome ──
  | 'GENOME_DONE' | 'GENOME_ERR' | 'GENOME_PHASE'
  | 'GENOME_START'
  // ── Grid ──
  | 'GRID_FAV_TOGGLE' | 'GRID_LOAD' | 'GRID_SAVE'
  | 'GRID_TAP'
  // ── Knowledge graph ──
  | 'KG_ENTITY' | 'KG_PERSIST' | 'KG_RELATION'
  | 'KG_RESOLVE' | 'KG_SEED'
  // ── Memory / learning ──
  | 'LEARN_PACKAGE' | 'MEMORY'
  // ── Network ──
  | 'NET_CONNECTIVITY' | 'NET_DETAIL' | 'NET_MODELS_RAW'
  | 'NET_STATS' | 'NETWORK_STATUS'
  // ── Permissions ──
  | 'PERMISSION_CONTRADICTION' | 'PERMISSION_RECHECK_RUNTIME'
  | 'PERMISSION_SNAPSHOT_STARTUP' | 'PERMISSION_STATUS'
  | 'PERM_CHECK' | 'PERM_REQUEST'
  // ── Proactive ──
  | 'PROACTIVE_ACT' | 'PROACTIVE_DISMISS'
  | 'PROACTIVE_EVAL' | 'PROACTIVE_SUGGEST'
  // ── Settings ──
  | 'SETTINGS_API_DEL' | 'SETTINGS_API_SAVE'
  | 'SETTINGS_COST_SAVE' | 'SETTINGS_DEFAULTS_SAVE'
  | 'SETTINGS_DEFAULT_PICK' | 'SETTINGS_INTENT'
  | 'SETTINGS_SAVE' | 'SETTINGS_SAVE_RESULT'
  | 'SETTINGS_SAVE_START' | 'SETTINGS_STATE'
  // ── Sidebar ──
  | 'SIDEBAR_CONV_SELECT' | 'SIDEBAR_OPEN' | 'SIDEBAR_QUICK'
  // ── Signal ──
  | 'SIGNAL_PATTERN' | 'SIGNAL_READ'
  // ── SMS ──
  | 'SMS_FIRE' | 'SMS_RESOLVE' | 'SMS_RESULT'
  // ── Storage ──
  | 'STORAGE_DELETE_ERROR' | 'STORAGE_MIGRATE'
  | 'STORAGE_READ_ERROR' | 'STORAGE_WRITE_ERROR'
  // ── System ──
  | 'DEEP_LINK' | 'ERROR' | 'ERROR_BOUNDARY' | 'SAFETY' | 'SYSTEM'
  | 'SYSTEM_ACTION' | 'SYSTEM_INFO'
  | 'BG_SERVICE' | 'HEADLESS_TASK'
  | 'VERSION_RESET'
  // ── UI ──
  | 'BUBBLE_DIAG' | 'COMPONENT_LIFECYCLE'
  | 'CONTEXT_PROVIDER' | 'CONTEXT_TAP'
  | 'CHAIN' | 'EFFECT' | 'FIRED' | 'POST'
  | 'FOCUS_EFFECT_DEPS' | 'NAV_CHANGE' | 'ROUTE'
  | 'PICKER_ANIMATE' | 'PICKER_CLOSE' | 'PICKER_CONTENT'
  | 'PICKER_FILTER_CHANGE' | 'PICKER_OPEN' | 'PICKER_SELECT'
  | 'RENDER_PERF' | 'RENDER_STATS'
  | 'SESSION_SUMMARY' | 'SLASH_CMD' | 'TOGGLE_TAP'
  | 'UI_CONV_SWITCH' | 'UI_DEFAULTS_LOADED'
  | 'UI_DEFAULTS_SAVED' | 'UI_ERROR' | 'UI_FOCUS'
  | 'UI_INIT' | 'UI_MESSAGE_RENDERED' | 'UI_MODAL'
  | 'UI_MODEL_APPLY' | 'UI_MODE_SWITCH'
  | 'UI_PICKER_CLOSE' | 'UI_PICKER_OPEN'
  | 'UI_PICKER_SELECT' | 'UI_PILL'
  | 'UI_PLUS_OPEN' | 'UI_PLUS_SELECT'
  | 'UI_PROCESSING' | 'UI_RENDER_MSG'
  | 'UI_SEND' | 'UI_SEND_ATTEMPT' | 'UI_SEND_COMPLETE'
  | 'UI_SETTINGS' | 'UI_SNAPSHOT' | 'UI_STATE' | 'UI_STOP'
  // ── Parse ──
  | 'PARSE_COMPOUND' | 'PARSE_INPUT'
  // ── State tracking ──
  | 'STATE' | 'STATE_AFTER' | 'STATE_BEFORE' | 'STATE_DELTA'
  // ── Vault ──
  | 'VAULT_DEL' | 'VAULT_ERR' | 'VAULT_GET'
  | 'VAULT_READ' | 'VAULT_SET' | 'VAULT_WRITE'
  // ── Vision ──
  | 'VISION_ANALYZE' | 'VISION_CAPTURE'
  // ── Misc capabilities ──
  | 'NEWS_FETCH' | 'PREFERENCE_BACKUP' | 'WEATHER'
  | 'EXEC_RESULT' | 'USER_MSG' | 'VERIFY';

interface UltraLogEntry {
  ts: string;
  t: number;
  cat: UltraLogCat;
  data: Record<string, unknown>;
  seq: number;
  coreId?: string;
}

interface WatchdogEntry {
  taskId: string;
  phase: string;
  startedAt: number;
  timeoutHandle: ReturnType<typeof setTimeout>;
}

export class UltraDevLog {
  private static entries: UltraLogEntry[] = [];
  private static readonly MAX_MEMORY = 3000;
  private static seq = 0;
  private static sessionId = Date.now().toString(36);
  private static writing = false;
  private static lastFlushedSeq = 0;
  private static pendingFlush = false;
  private static activeCoreId: string | null = null;
  private static watchdogs = new Map<string, WatchdogEntry>();
  private static appStateListener: ReturnType<typeof AppState.addEventListener> | null = null;
  private static lastAppStateChangeAt = Date.now();
  private static lastActiveAt = Date.now();
  private static lastVaultReadTs = new Map<string, number>();
  private static lastUiStateHash = '';
  private static lastConvListCount = -1;
  private static lastConvLoadedCounts = new Map<string, number>();
  private static processingStartedAt = 0;

  // ── Envelope context (collected once at startup) ──
  private static _deviceInfo: Record<string, unknown> = {};
  private static appVersion = '';
  private static buildId = '';
  private static rnVersion = '';
  private static hermesEnabled = false;
  private static currentScreen = 'unknown';
  private static isBackground = false;
  private static runCounter = 0;
  private static currentRunId = '';
  private static currentCorrId = '';
  private static monoStartMs = Date.now();
  private static perfNowAvailable = typeof performance !== 'undefined' && typeof performance.now === 'function';

  private static getMonoMs(): number {
    if (UltraDevLog.perfNowAvailable) return Math.round(performance.now());
    return Date.now() - UltraDevLog.monoStartMs;
  }

  private static getLevel(cat: string, data: Record<string, unknown>): string {
    if (cat === 'ERROR' || cat === 'ERROR_BOUNDARY' || cat === 'CRASH_NATIVE') return 'ERROR';
    if (cat === 'PROCESS_RESTART' && data.event === 'warm_restart') return 'WARN';
    if ((data.note as string)?.startsWith?.('WARN')) return 'WARN';
    if ((data.note as string)?.startsWith?.('BUG')) return 'ERROR';
    if (cat === 'TASK_WATCHDOG' && data.event === 'FIRED') return 'ERROR';
    if (cat === 'APP_LAUNCH_FAIL') return 'ERROR';
    if (cat === 'EXEC_RESULT' && data.success === false) return 'WARN';
    if (cat === 'DEBUG_SCREENSHOT_FAIL') return 'WARN';
    if (cat === 'VAULT_WRITE' && data.success === false) return 'WARN';
    if (cat === 'SESSION_SUMMARY' || cat === 'DEVICE_INFO') return 'INFO';
    if (cat === 'BUBBLE_DIAG' || cat === 'UI_MESSAGE_RENDERED' || cat === 'A11Y_CONTENT') return 'DEBUG';
    return 'INFO';
  }

  static async collectEnvelopeContext(): Promise<void> {
    try {
      const Device = require('expo-device');
      const Constants = require('expo-constants').default;

      UltraDevLog._deviceInfo = {
        model: Device.modelName || 'unknown',
        manufacturer: (Device.manufacturer || 'unknown').toLowerCase(),
        sdk_int: Device.platformApiLevel || 0,
        os_release: Device.osVersion || '',
        ram_mb: Device.totalMemory ? Math.round(Device.totalMemory / 1048576) : 0,
      };

      UltraDevLog.appVersion = Constants.expoConfig?.version || Constants.manifest?.version || 'unknown';
      UltraDevLog.buildId = `${UltraDevLog.appVersion}-${Constants.expoConfig?.android?.versionCode || '?'}`;
      try {
        const rnv = require('react-native/Libraries/Core/ReactNativeVersion');
        UltraDevLog.rnVersion = rnv?.version
          ? `${rnv.version.major}.${rnv.version.minor}.${rnv.version.patch}`
          : 'unknown';
      } catch {
        UltraDevLog.rnVersion = 'unknown';
      }

      UltraDevLog.hermesEnabled = typeof (global as any).HermesInternal !== 'undefined';

      UltraDevLog.runCounter++;
      UltraDevLog.currentRunId = `run_${UltraDevLog.runCounter}_${Date.now().toString(36)}`;
    } catch (e: any) {
      console.warn('[UltraDevLog] Envelope context collection failed:', e?.message);
    }
  }

  static setCurrentScreen(screen: string): void {
    UltraDevLog.currentScreen = screen;
  }

  static setIsBackground(bg: boolean): void {
    UltraDevLog.isBackground = bg;
  }

  static setCorrId(corrId: string): void {
    UltraDevLog.currentCorrId = corrId;
  }

  static clearCorrId(): void {
    UltraDevLog.currentCorrId = '';
  }

  static newRun(): string {
    UltraDevLog.runCounter++;
    UltraDevLog.currentRunId = `run_${UltraDevLog.runCounter}_${Date.now().toString(36)}`;
    return UltraDevLog.currentRunId;
  }

  static push(cat: UltraLogCat, data: Record<string, unknown>): void {
    const level = UltraDevLog.getLevel(cat, data);

    let errorEnvelope: Record<string, unknown> | undefined;
    if (level === 'ERROR' || level === 'WARN' || level === 'FATAL') {
      const recent = UltraDevLog.entries.slice(-20).map(e => ({
        seq: e.seq,
        event: e.cat,
        ts: e.ts,
        summary: JSON.stringify(e.data).slice(0, 100),
      }));
      errorEnvelope = {
        error_type: data.context || cat,
        error_code: data.error_code || cat,
        message: data.message || data.error || data.note || '',
        stack_js: data.stack || data.componentStack || '',
        cause_chain: data.cause_chain || [],
        last_20_events: recent,
      };
    }

    const entry: UltraLogEntry = {
      ts: new Date().toISOString(),
      t: Date.now(),
      cat,
      seq: ++UltraDevLog.seq,
      data: {
        ts_iso: new Date().toISOString(),
        mono_ms: UltraDevLog.getMonoMs(),
        session_id: UltraDevLog.sessionId,
        run_id: UltraDevLog.currentRunId,
        build_id: UltraDevLog.buildId,
        app_version: UltraDevLog.appVersion,
        rn_version: UltraDevLog.rnVersion,
        hermes: UltraDevLog.hermesEnabled,
        device: UltraDevLog._deviceInfo,
        is_background: UltraDevLog.isBackground,
        screen: UltraDevLog.currentScreen,
        event: cat,
        level,
        corr_id: UltraDevLog.currentCorrId || CorrIdScope.current() || (data.taskId as string) || '',
        brain_corr: CorrIdScope.current() || '',
        payload: data,
        ...(errorEnvelope ? { error: errorEnvelope } : {}),
      },
      coreId: UltraDevLog.activeCoreId ?? undefined,
    };

    UltraDevLog.entries.push(entry);
    if (UltraDevLog.entries.length > UltraDevLog.MAX_MEMORY) {
      UltraDevLog.entries = UltraDevLog.entries.slice(-UltraDevLog.MAX_MEMORY);
    }
    UltraDevLog.scheduleFlush();
  }

  static userMessage(conversationId: string, content: string): void {
    const wordCount = content.trim().split(/\s+/).filter(Boolean).length;
    const hasImage = content.includes('[image]') || content.includes('data:image');
    UltraDevLog.push('USER_MSG', {
      conversationId,
      userInputSummary: `${hasImage ? 'image request' : 'text request'}, ${wordCount} words`,
      contentLength: content.length,
    });
  }

  static aiResponse(conversationId: string, model: string, content: string, cost: number | undefined, tokens?: { input?: number; output?: number }): void {
    UltraDevLog.push('AI_RESPONSE', {
      conversationId, model,
      responseSummary: `assistant replied, ${content.length} chars`,
      responseLength: content.length,
      cost, tokens,
    });
  }

  static agentStep(taskId: string, phase: string, detail: string, success: boolean): void {
    UltraDevLog.push('AGENT_STEP', { taskId, phase, detail, success });
  }

  static modeDetected(taskId: string, mode: string, userInput: string): void {
    UltraDevLog.push('MODE', { taskId, mode, inputPreview: userInput.slice(0, 500) });
  }

  static planResult(taskId: string, capability: string | null, params: Record<string, unknown> | null, deterministic: boolean): void {
    UltraDevLog.push('PLAN', { taskId, capability, params, deterministic });
  }

  static safetyCheck(taskId: string, risk: string, allowed: boolean, reasons: string[]): void {
    UltraDevLog.push('SAFETY', { taskId, risk, allowed, reasons });
  }

  static apiCall(taskId: string, model: string, promptTokens: number, completionTokens: number, cost: number, durationMs: number): void {
    UltraDevLog.push('API_CALL', { taskId, model, promptTokens, completionTokens, cost, durationMs });
  }

  static execResult(taskId: string, capability: string, success: boolean, resultPreview: string): void {
    UltraDevLog.push('EXEC_RESULT', { taskId, capability, success, resultPreview: resultPreview.slice(0, 2000) });
  }

  static verification(taskId: string, verified: boolean, issues: string[]): void {
    UltraDevLog.push('VERIFY', { taskId, verified, issues });
  }

  static error(context: string, message: string, stack?: string): void {
    UltraDevLog.push('ERROR', { context, message, stack: stack?.slice(0, 1000) });
  }

  static systemEvent(context: string, message: string, meta?: Record<string, unknown>): void {
    UltraDevLog.push('SYSTEM', { context, message, ...meta });
  }

  static modelSwitch(taskId: string, from: string, to: string, reason: string): void {
    UltraDevLog.push('MODEL_SWITCH', { taskId, from, to, reason });
  }

  static costRecord(model: string, cost: number, taskId: string): void {
    UltraDevLog.push('COST', { model, cost, taskId });
  }

  static conversationCreated(conversationId: string, title: string): void {
    UltraDevLog.push('CONV_NEW', { conversationId, title });
  }

  static conversationDeleted(conversationId: string): void {
    UltraDevLog.push('CONV_DEL', { conversationId });
  }

  static gridTap(capability: string, params: Record<string, unknown>): void {
    UltraDevLog.push('GRID_TAP', { capability, params });
  }

  static contextTap(capability: string, params: Record<string, unknown>): void {
    UltraDevLog.push('CONTEXT_TAP', { capability, params });
  }

  static slashCommand(command: string, result: string, durationMs: number): void {
    UltraDevLog.push('SLASH_CMD', { command, resultPreview: result.slice(0, 200), durationMs });
  }

  static modalEvent(modalName: string, action: 'open' | 'close', extra?: Record<string, unknown>): void {
    UltraDevLog.push('UI_MODAL', { modalName, action, ...extra });
  }

  static processingState(isProcessing: boolean, source: string, taskId?: string): void {
    const now = Date.now();
    const durationMs = !isProcessing && UltraDevLog.processingStartedAt > 0
      ? now - UltraDevLog.processingStartedAt : 0;
    if (isProcessing) UltraDevLog.processingStartedAt = now;
    else UltraDevLog.processingStartedAt = 0;
    UltraDevLog.push('UI_PROCESSING', { isProcessing, source, taskId, durationMs });
  }

  static messageListUpdate(prevCount: number, nextCount: number, source: string, conversationId: string | null): void {
    UltraDevLog.push('UI_RENDER_MSG', { prevCount, nextCount, diff: nextCount - prevCount, source, conversationId });
  }

  static sendAttempt(input: string, mode: string, modelId: string, conversationId: string | null, messageCount: number, isProcessing: boolean): void {
    UltraDevLog.push('UI_SEND_ATTEMPT', { inputLen: input.length, inputPreview: input.slice(0, 200), mode, modelId, conversationId, messageCount, isProcessing });
  }

  static sendComplete(taskId: string, success: boolean, durationMs: number, isProcessingBefore: boolean, isProcessingAfter: boolean): void {
    UltraDevLog.push('UI_SEND_COMPLETE', { taskId, success, durationMs, isProcessingBefore, isProcessingAfter });
  }

  static appLaunchBegin(taskId: string, target: string, targetLower: string): void {
    UltraDevLog.push('APP_LAUNCH_BEGIN', { taskId, target, targetLower });
  }

  static appLaunchDeviceQuery(taskId: string, appCount: number, queryDurationMs: number, error?: string): void {
    UltraDevLog.push('APP_LAUNCH_DEVICE', { taskId, appCount, queryDurationMs, error });
  }

  static appLaunchMatch(taskId: string, target: string, matchType: 'exact' | 'partial' | 'none', matchedAppName?: string, resolvedPkg?: string): void {
    UltraDevLog.push('APP_LAUNCH_MATCH', { taskId, target, matchType, matchedAppName, resolvedPkg });
  }

  static appLaunchAiFallback(taskId: string, target: string, prompt: string, resolvedPkg: string, durationMs: number): void {
    UltraDevLog.push('APP_LAUNCH_AI', { taskId, target, prompt: prompt.slice(0, 200), resolvedPkg, durationMs });
  }

  static appLaunchFire(taskId: string, pkg: string, target: string): void {
    UltraDevLog.push('APP_LAUNCH_FIRE', { taskId, pkg, target, note: 'JS thread suspends after this.' });
    UltraDevLog.scheduleFlush();
  }

  static appLaunchResume(lastKnownPkg: string, resumeTs: number): void {
    UltraDevLog.push('APP_LAUNCH_RESUME', { lastKnownPkg, suspendDuration: Date.now() - resumeTs });
  }

  static appLaunchFail(taskId: string, target: string, pkg: string | undefined, error: string, stage: 'device_query' | 'ai_fallback' | 'no_package' | 'intent_launch'): void {
    UltraDevLog.push('APP_LAUNCH_FAIL', { taskId, target, pkg, error, stage });
  }

  static smsResolve(taskId: string, inputName: string, resolvedNumber: string | null, contactsSearched: number, error?: string): void {
    UltraDevLog.push('SMS_RESOLVE', {
      taskId, inputName,
      resolvedNumber: resolvedNumber ? resolvedNumber.slice(0, 6) + '****' : null,
      contactsSearched, resolved: !!resolvedNumber, error,
    });
  }

  static smsFire(taskId: string, toNumber: string, messagePreview: string): void {
    UltraDevLog.push('SMS_FIRE', {
      taskId, toNumberLen: toNumber.length,
      toIsPhone: /^\+?\d[\d\s\-()]{6,}/.test(toNumber),
      messageLen: messagePreview.length, messagePreview: messagePreview.slice(0, 50),
    });
  }

  static smsResult(taskId: string, result: string, success: boolean): void {
    UltraDevLog.push('SMS_RESULT', { taskId, result, success });
  }

  static vaultRead(key: string, found: boolean, valuePreview?: string): void {
    const HIGH_FREQ = new Set(['preferred_model']);
    if (HIGH_FREQ.has(key)) {
      const last = UltraDevLog.lastVaultReadTs.get(key) ?? 0;
      if (Date.now() - last < 1000) return;
      UltraDevLog.lastVaultReadTs.set(key, Date.now());
    }
    UltraDevLog.push('VAULT_READ', { key, found, valuePreview: valuePreview ? valuePreview.slice(0, 80) : undefined });
  }

  static vaultWrite(key: string, success: boolean, valuePreview?: string): void {
    UltraDevLog.push('VAULT_WRITE', { key, success, valuePreview: valuePreview ? valuePreview.slice(0, 80) : undefined });
  }

  static parseInput(input: string, matchedPattern: string | null, capability: string | null, extractedParams: Record<string, unknown> | null, isCompound: boolean): void {
    UltraDevLog.push('PARSE_INPUT', { inputPreview: input.slice(0, 200), matchedPattern, capability, extractedParams, isCompound });
    if (isCompound) {
      UltraDevLog.push('PARSE_COMPOUND', { input: input.slice(0, 200), warning: 'Multi-intent. Target param may contain trailing intent.' });
    }
  }

  static pickerOpen(modelCount: number, currentModelId: string, slideAnimValue: number, filter: string): void {
    UltraDevLog.push('PICKER_OPEN', {
      modelCount, currentModelId, slideAnimCurrentValue: slideAnimValue, filter,
      note: slideAnimValue > 0 && slideAnimValue < 100
        ? 'WARN: slideAnim partially open before reset -- sheet may be mid-animation'
        : 'ok',
    });
  }

  static pickerClose(how: 'backdrop' | 'close_button' | 'model_select' | 'programmatic'): void {
    UltraDevLog.push('PICKER_CLOSE', { how });
  }

  static pickerAnimate(direction: 'open' | 'close', fromValue: number, toValue: number, animationType: 'spring' | 'timing', mountedAt?: number): void {
    if (direction === 'close' && mountedAt !== undefined && (Date.now() - mountedAt) < 500) {
      return;
    }
    UltraDevLog.push('PICKER_ANIMATE', { direction, fromValue, toValue, animationType });
  }

  static pickerSelect(modelId: string, modelName: string, previousModelId: string): void {
    UltraDevLog.push('PICKER_SELECT', { modelId, modelName, previousModelId });
  }

  static coreCreated(reason: string): string {
    const instanceId = `core_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`;
    UltraDevLog.activeCoreId = instanceId;
    const prior = UltraDevLog.entries.filter(e => e.cat === 'CORE_INSTANCE' && e.data.event === 'created').length;
    UltraDevLog.push('CORE_INSTANCE', {
      event: 'created', instanceId, reason, priorCreationsThisSession: prior,
      note: prior > 0 ? `WARN: Re-initialization #${prior + 1}.` : 'First core this session.',
    });
    return instanceId;
  }

  static coreDestroyed(instanceId: string, reason: string): void {
    if (UltraDevLog.activeCoreId === instanceId) UltraDevLog.activeCoreId = null;
    UltraDevLog.push('CORE_INSTANCE', { event: 'destroyed', instanceId, reason, wasActive: UltraDevLog.activeCoreId === null });
  }

  static taskCoreStamp(taskId: string, instanceId: string): void {
    const isStale = UltraDevLog.activeCoreId !== null && UltraDevLog.activeCoreId !== instanceId;
    UltraDevLog.push('CORE_INSTANCE', {
      event: 'task_stamped', taskId, instanceId,
      activeCoreId: UltraDevLog.activeCoreId, isStale,
      note: isStale ? `BUG: running on ${instanceId} but active is ${UltraDevLog.activeCoreId}.` : 'ok',
    });
  }

  static executorBranch(taskId: string, capability: string, branch: string, meta?: Record<string, unknown>): void {
    UltraDevLog.push('EXECUTOR_BRANCH', { taskId, capability, branch, coreId: UltraDevLog.activeCoreId, ...meta });
  }

  static executorEnter(taskId: string, capability: string): void {
    UltraDevLog.executorBranch(taskId, capability, 'ENTER', { note: 'Executor entered. No EXIT = short-circuited.' });
  }

  static executorExit(taskId: string, capability: string, success: boolean, path: string): void {
    UltraDevLog.executorBranch(taskId, capability, 'EXIT', { success, path });
  }

  static convContextSent(taskId: string, model: string, breakdown: { system_prompt_chars: number; history_message_count: number; history_chars: number; capability_context_chars: number; user_message_chars: number; estimated_total_tokens: number }): void {
    const total = breakdown.system_prompt_chars + breakdown.history_chars + breakdown.capability_context_chars + breakdown.user_message_chars;
    const sysPct = Math.round((breakdown.system_prompt_chars / total) * 100);
    const histPct = Math.round((breakdown.history_chars / total) * 100);
    const capPct = Math.round((breakdown.capability_context_chars / total) * 100);
    const usrPct = Math.round((breakdown.user_message_chars / total) * 100);
    UltraDevLog.push('CONV_CONTEXT_SENT', {
      taskId, model, ...breakdown, totalChars: total,
      systemPct: sysPct, historyPct: histPct, capabilityPct: capPct, userPct: usrPct,
      contaminationRisk: capPct > 40, bloatRisk: histPct > 60,
      note: [capPct > 40 ? `WARN: cap=${capPct}% contamination risk` : null, histPct > 60 ? `WARN: hist=${histPct}% bloat risk` : null].filter(Boolean).join('; ') || 'ok',
    });
  }

  private static lastRenderedHeights: Map<string, number> = new Map();

  static messageRendered(messageId: string, role: 'user' | 'assistant' | 'system', contentLength: number, measuredHeightPx: number, indexInList: number, listScrollOffsetPx: number, listHeightPx: number): void {
    const lastH = UltraDevLog.lastRenderedHeights.get(messageId);
    if (lastH !== undefined && Math.abs(lastH - measuredHeightPx) < 2) return;
    UltraDevLog.lastRenderedHeights.set(messageId, measuredHeightPx);
    if (UltraDevLog.lastRenderedHeights.size > 100) {
      const first = UltraDevLog.lastRenderedHeights.keys().next().value;
      if (first) UltraDevLog.lastRenderedHeights.delete(first);
    }
    const isVisible = true;
    const tallWarning = measuredHeightPx > 300;
    UltraDevLog.push('UI_MESSAGE_RENDERED', {
      messageId, role, contentLength, measuredHeightPx, indexInList,
      listScrollOffsetPx, listHeightPx, isVisible, tallWarning,
      listHeightKnown: listHeightPx > 0,
      note: tallWarning ? `WARN: ${measuredHeightPx}px tall -- possible oversized bubble bug`
        : listHeightPx === 0 ? 'WARN: listHeightPx=0 -- add FlatList onLayout to populate listHeightRef'
        : 'ok',
    });
  }

  static listScrolled(scrollOffsetY: number, viewportHeight: number, contentHeight: number): number {
    const scrollPct = contentHeight > 0 ? Math.round((scrollOffsetY / contentHeight) * 100) : 0;
    const isAtBottom = scrollOffsetY + viewportHeight >= contentHeight - 20;
    const last = UltraDevLog.entries.filter(e => e.cat === 'UI_MESSAGE_RENDERED').slice(-1)[0];
    const lastOff = (last?.data.listScrollOffsetPx as number) ?? 0;
    if (Math.abs(scrollOffsetY - lastOff) > 50) {
      UltraDevLog.push('UI_MESSAGE_RENDERED', { event: 'scroll', scrollOffsetY, viewportHeight, contentHeight, scrollPct, isAtBottom });
    }
    return scrollOffsetY;
  }

  static watchdogArm(taskId: string, phase: string, timeoutMs = 15000): void {
    UltraDevLog.watchdogDisarm(taskId, phase);
    const key = `${taskId}::${phase}`;
    const startedAt = Date.now();
    const handle = setTimeout(() => {
      UltraDevLog.push('TASK_WATCHDOG', { event: 'FIRED', taskId, phase, stalenessMs: Date.now() - startedAt, timeoutMs, activeCoreId: UltraDevLog.activeCoreId, note: `BUG: ${taskId} stuck in ${phase}.` });
      UltraDevLog.watchdogs.delete(key);
    }, timeoutMs);
    UltraDevLog.watchdogs.set(key, { taskId, phase, startedAt, timeoutHandle: handle });
  }

  static watchdogDisarm(taskId: string, phase: string): void {
    const key = `${taskId}::${phase}`;
    const entry = UltraDevLog.watchdogs.get(key);
    if (entry) {
      clearTimeout(entry.timeoutHandle);
      UltraDevLog.push('TASK_WATCHDOG', { event: 'disarmed', taskId, phase, elapsedMs: Date.now() - entry.startedAt, note: 'ok' });
      UltraDevLog.watchdogs.delete(key);
    }
  }

  static watchdogDisarmAll(taskId: string): void {
    for (const [key, entry] of UltraDevLog.watchdogs.entries()) {
      if (entry.taskId === taskId) { clearTimeout(entry.timeoutHandle); UltraDevLog.watchdogs.delete(key); }
    }
  }

  static installAppStateListener(): void {
    if (UltraDevLog.appStateListener) return;
    UltraDevLog.lastAppStateChangeAt = Date.now();
    UltraDevLog.lastActiveAt = Date.now();
    UltraDevLog.appStateListener = AppState.addEventListener('change', (nextState: AppStateStatus) => {
      const now = Date.now();
      UltraDevLog.isBackground = (nextState === 'background' || nextState === 'inactive');
      const elapsed = now - UltraDevLog.lastAppStateChangeAt;
      const bgDuration = nextState === 'active' ? now - UltraDevLog.lastActiveAt : 0;
      // Capture active operation context when backgrounding
      let activeOperation: Record<string, unknown> | undefined;
      if ((nextState === 'background' || nextState === 'inactive') && UltraDevLog.processingStartedAt > 0) {
        const processingForMs = now - UltraDevLog.processingStartedAt;
        const watchdogEntries: Array<{ taskId: string; phase: string; elapsedMs: number }> = [];
        for (const [, wd] of UltraDevLog.watchdogs) {
          watchdogEntries.push({ taskId: wd.taskId, phase: wd.phase, elapsedMs: now - wd.startedAt });
        }
        activeOperation = { processingForMs, watchdogs: watchdogEntries };
      }

      UltraDevLog.push('APP_STATE_CHANGE', {
        nextState, elapsedSinceLastChange: elapsed,
        backgroundDurationMs: nextState === 'active' ? bgDuration : undefined,
        activeCoreId: UltraDevLog.activeCoreId, activeWatchdogs: UltraDevLog.watchdogs.size,
        ...(activeOperation ? { activeOperation } : {}),
        note: nextState === 'active' && bgDuration > 0
          ? `App back after ${bgDuration}ms. If CORE_INSTANCE follows, re-init triggered here.`
          : (nextState === 'background' || nextState === 'inactive') && activeOperation
          ? `Going to background with active operation (${(activeOperation as any).processingForMs}ms). Capability may report user_cancelled.`
          : (nextState === 'background' || nextState === 'inactive')
          ? 'Going to background. Watch for CORE_INSTANCE after next active.'
          : 'ok',
      });
      if (nextState === 'background' || nextState === 'inactive') {
        UltraDevLog.lastActiveAt = now;
        try { AsyncStorage.setItem(PROCESS_RESTART_KEY, String(now)); } catch {}
        UltraDevLog.sessionSummary();
        UltraDevLog.scheduleFlush();
      }
      UltraDevLog.lastAppStateChangeAt = now;
    });
  }

  static removeAppStateListener(): void {
    if (UltraDevLog.appStateListener) { UltraDevLog.appStateListener.remove(); UltraDevLog.appStateListener = null; }
  }

  private static a11yDrainTimer: ReturnType<typeof setInterval> | null = null;
  private static heartbeatTimer: ReturnType<typeof setInterval> | null = null;

  static startA11yDrain(intervalMs = 3000): void {
    if (UltraDevLog.a11yDrainTimer) return;
    const { default: AppController } = require('../native/AppController');
    UltraDevLog.a11yDrainTimer = setInterval(async () => {
      try {
        const logs: string[] = await AppController.drainAccessibilityLogs();
        for (const raw of logs) {
          try {
            const parsed = JSON.parse(raw);
            const cat = parsed.cat as UltraLogCat;
            UltraDevLog.push(cat, parsed.data ?? parsed);
          } catch { UltraDevLog.push('A11Y_CONTENT', { raw: raw.slice(0, 200) }); }
        }
      } catch {}
    }, intervalMs);
  }

  static stopA11yDrain(): void {
    if (UltraDevLog.a11yDrainTimer) { clearInterval(UltraDevLog.a11yDrainTimer); UltraDevLog.a11yDrainTimer = null; }
  }

  private static lastHeartbeatAlive: boolean | null = null;
  private static lastHeartbeatLoggedAt = 0;

  static startHeartbeat(intervalMs = 10000): void {
    if (UltraDevLog.heartbeatTimer) return;
    const { default: AppController } = require('../native/AppController');
    UltraDevLog.heartbeatTimer = setInterval(async () => {
      try {
        const hb = await AppController.heartbeatPing();
        const now = Date.now();
        const stateChanged = UltraDevLog.lastHeartbeatAlive !== hb.alive;
        const minuteElapsed = (now - UltraDevLog.lastHeartbeatLoggedAt) >= 60000;
        if (stateChanged || minuteElapsed) {
          UltraDevLog.push('A11Y_HEARTBEAT', { ...hb, stateChanged, minuteLog: minuteElapsed && !stateChanged });
          UltraDevLog.lastHeartbeatAlive = hb.alive;
          UltraDevLog.lastHeartbeatLoggedAt = now;
        }
        const crash = await AppController.readCrashLog();
        if (crash && crash.length > 0) {
          UltraDevLog.push('CRASH_NATIVE', { log: crash.slice(0, 2000) });
          await AppController.clearCrashLog();
        }
      } catch {}
    }, intervalMs);
  }

  static stopHeartbeat(): void {
    if (UltraDevLog.heartbeatTimer) { clearInterval(UltraDevLog.heartbeatTimer); UltraDevLog.heartbeatTimer = null; }
  }

  // ─── v3 NEW SENSORS ────────────────────────────────────────────────────────

  static componentMount(componentName: string, props?: Record<string, unknown>): string {
    const componentId = `${componentName}_${Date.now().toString(36)}`;
    const prior = UltraDevLog.entries.filter(e => e.cat === 'COMPONENT_LIFECYCLE' && e.data.componentName === componentName && e.data.event === 'mount').length;
    UltraDevLog.push('COMPONENT_LIFECYCLE', {
      event: 'mount', componentName, componentId, priorMountsThisSession: prior,
      activeCoreId: UltraDevLog.activeCoreId, props,
      note: prior > 0 ? `WARN: ${componentName} mounted ${prior + 1} times. Check for unnecessary remounts.` : 'First mount.',
    });
    return componentId;
  }

  static componentUnmount(componentName: string, componentId: string, reason?: string): void {
    UltraDevLog.push('COMPONENT_LIFECYCLE', {
      event: 'unmount', componentName, componentId,
      reason: reason ?? 'unknown', activeCoreId: UltraDevLog.activeCoreId,
    });
  }

  static settingsSaveTap(section: 'defaults' | 'api' | 'limits', payload: Record<string, unknown>): void {
    UltraDevLog.push('SETTINGS_SAVE', { event: 'tap', section, payload, note: 'Save tapped. Watch for write_result.' });
  }

  static settingsSaveResult(section: string, success: boolean, savedKeys: string[], error?: string): void {
    UltraDevLog.push('SETTINGS_SAVE', {
      event: 'write_result', section, success, savedKeys, error,
      note: success ? `Written: ${savedKeys.join(', ')}` : `FAIL: ${error ?? 'unknown'}`,
    });
  }

  static executePhase(taskId: string, phase: 'INGEST' | 'ROUTE' | 'PLAN' | 'VERIFY' | 'APPROVE' | 'EXECUTE' | 'VERIFY_RESULT' | 'RESPOND' | string): void {
    UltraDevLog.push('EXECUTE_PHASE', { taskId, phase, coreId: UltraDevLog.activeCoreId });
  }

  static navChange(routeName: string, action: string, stackDepth: number, params?: Record<string, unknown>): void {
    UltraDevLog.push('NAV_CHANGE', { routeName, action, stackDepth, params, activeCoreId: UltraDevLog.activeCoreId });
  }

  static focusEffectTriggered(currentDeps: Record<string, unknown>, previousDeps: Record<string, unknown> | null): void {
    const changed: Record<string, { from: unknown; to: unknown }> = {};
    for (const [k, v] of Object.entries(currentDeps)) {
      if (previousDeps === null || previousDeps[k] !== v) changed[k] = { from: previousDeps?.[k], to: v };
    }
    UltraDevLog.push('FOCUS_EFFECT_DEPS', {
      changedDeps: Object.keys(changed), changes: changed, isFirstTrigger: previousDeps === null,
      note: Object.keys(changed).includes('currentMode')
        ? 'WARN: currentMode in changed deps -- + menu triggers unnecessary focusEffect vault reads.'
        : Object.keys(changed).length > 0 ? `Changed: ${Object.keys(changed).join(', ')}` : 'No deps changed.',
    });
  }

  static focusEffectSuppressed(elapsedMs: number): void {
    UltraDevLog.push('FOCUS_EFFECT_DEPS', { event: 'suppressed', elapsedMs, note: `Throttle ok (${elapsedMs}ms < 2000ms).` });
  }

  static async checkProcessRestart(): Promise<void> {
    try {
      const raw = await AsyncStorage.getItem(PROCESS_RESTART_KEY);
      const now = Date.now();
      if (raw) {
        const lastTs = parseInt(raw, 10);
        const bgDuration = now - lastTs;
        UltraDevLog.push('PROCESS_RESTART', {
          event: 'warm_restart', lastBackgroundTs: lastTs, backgroundDurationMs: bgDuration,
          note: `Process killed after ${bgDuration}ms in background. ref guard resets on kill. Fix: React Context for AgentCore.`,
        });
      } else {
        UltraDevLog.push('PROCESS_RESTART', { event: 'cold_start', note: 'First launch or manual kill.' });
      }
      await AsyncStorage.removeItem(PROCESS_RESTART_KEY);
    } catch (err: any) {
      UltraDevLog.push('PROCESS_RESTART', { event: 'detection_failed', error: err.message });
    }
  }

  static pickerContentRender(filteredCount: number, totalCount: number, activeFilter: string, currentModelId: string): void {
    UltraDevLog.push('PICKER_CONTENT', {
      filteredCount, totalCount, activeFilter, currentModelId,
      note: filteredCount === 0
        ? `WARN: 0 models for filter "${activeFilter}" -- sheet open but list empty.`
        : `ok -- ${filteredCount}/${totalCount} for "${activeFilter}"`,
    });
  }

  static contextProviderRender(providerName: string, coreInstanceId: string, renderCount: number): void {
    UltraDevLog.push('CONTEXT_PROVIDER', {
      providerName, coreInstanceId, renderCount, activeCoreId: UltraDevLog.activeCoreId,
      note: renderCount > 1 && coreInstanceId === UltraDevLog.activeCoreId
        ? `ok -- same core on render #${renderCount}. Fix working.`
        : renderCount === 1 ? 'First render.' : `WARN: coreId changed on render #${renderCount}.`,
    });
  }

  static deviceInfo(info: { os: string; osVersion: string; model: string; screenWidth: number; screenHeight: number; totalMemory?: number }): void {
    UltraDevLog.push('DEVICE_INFO', info);
  }

  private static lastNetworkKey = '';
  static networkStatus(isConnected: boolean, type: string, note?: string): void {
    const key = `${isConnected}:${type}`;
    if (key === UltraDevLog.lastNetworkKey && !note) return;
    UltraDevLog.lastNetworkKey = key;
    UltraDevLog.push('NETWORK_STATUS', { isConnected, type, note: note ?? (isConnected ? 'ok' : 'WARN: device is offline') });
  }

  static permissionStatus(permission: string, status: string): void {
    UltraDevLog.push('PERMISSION_STATUS', { permission, status, note: status === 'granted' ? 'ok' : `WARN: ${permission} is ${status}` });
  }

  static errorBoundary(error: string, componentStack: string): void {
    UltraDevLog.push('ERROR_BOUNDARY', { error: error.slice(0, 500), componentStack: componentStack.slice(0, 1000) });
  }

  static settingsIntent(query: string, matched: boolean, action?: string, label?: string): void {
    UltraDevLog.push('SETTINGS_INTENT', {
      query,
      matched,
      action: action ?? null,
      label: label ?? null,
      note: matched ? `ok -- matched "${label}"` : `WARN: no settings match for "${query}"`,
    });
  }

  static deepLink(query: string, matched: boolean, uri?: string, label?: string): void {
    UltraDevLog.push('DEEP_LINK', {
      query,
      matched,
      uri: uri ?? null,
      label: label ?? null,
      note: matched ? `ok -- "${label}" → ${uri}` : `WARN: no deep link match for "${query}"`,
    });
  }

  static systemAction(trigger: string, routedToSettings: boolean, message: string, success: boolean): void {
    UltraDevLog.push('SYSTEM_ACTION', {
      trigger,
      routedToSettings,
      message,
      success,
      note: routedToSettings ? `Routed to settings panel` : `Executed directly`,
    });
  }

  static systemInfo(values: {
    batteryPct?: number;
    batteryState?: string;
    lowPower?: boolean;
    ramUsedMB?: number;
    ramTotalMB?: number;
    storageFreeGB?: number;
    storageTotalGB?: number;
    cpuTempC?: number;
    failedReads: string[];
  }): void {
    UltraDevLog.push('SYSTEM_INFO', {
      ...values,
      note: values.failedReads.length > 0
        ? `WARN: failed reads: ${values.failedReads.join(', ')}`
        : 'ok',
    });
  }

  static preferenceBackup(operation: 'export' | 'import', success: boolean, keysCount: number, error?: string): void {
    UltraDevLog.push('PREFERENCE_BACKUP', {
      operation,
      success,
      keysCount,
      error: error ?? null,
      note: success ? `${operation} ok -- ${keysCount} keys` : `WARN: ${operation} failed: ${error}`,
    });
  }

  static learnPackage(trigger: string, packageName: string, wasUpdate: boolean): void {
    UltraDevLog.push('LEARN_PACKAGE', {
      trigger,
      packageName,
      wasUpdate,
      note: wasUpdate
        ? `Updated existing pattern for "${trigger}" → ${packageName}`
        : `New pattern stored: "${trigger}" → ${packageName} at confidence 1.0`,
    });
  }

  static sessionSummary(): void {
    const entries = UltraDevLog.entries;
    const errors = entries.filter(e => e.cat === 'ERROR' || e.cat === 'ERROR_BOUNDARY').length;
    const apiCalls = entries.filter(e => e.cat === 'API_CALL').length;
    const appLaunches = entries.filter(e => e.cat === 'APP_LAUNCH_BEGIN').length;
    const appLaunchFails = entries.filter(e => e.cat === 'APP_LAUNCH_FAIL').length;
    const uptime = entries.length > 0 ? Date.now() - entries[0].t : 0;
    UltraDevLog.push('SESSION_SUMMARY', {
      totalEntries: entries.length,
      errors,
      apiCalls,
      appLaunches,
      appLaunchFails,
      uptimeMs: uptime,
      note: errors > 0 ? `WARN: ${errors} error(s) this session` : 'ok',
    });
  }

  // ─── Logging truth pass: new structured sensors ───────────────────────────

  static pickerFilterChange(params: {
    fromFilter: string;
    toFilter: string;
    rawCountBefore: number;
    rawCountAfter: number;
    displayedCountAfter: number;
    sourceOfModels: string;
  }): void {
    UltraDevLog.push('PICKER_FILTER_CHANGE', {
      ...params,
      note: params.displayedCountAfter === 0
        ? `WARN: 0 models after filter change to "${params.toFilter}"`
        : `ok — ${params.displayedCountAfter} models for "${params.toFilter}"`,
    });
  }

  static settingsSaveStart(params: {
    screen: string;
    section: string;
    action: string;
    targetKey: string;
    before: unknown;
    after: unknown;
  }): void {
    UltraDevLog.push('SETTINGS_SAVE_START', {
      ...params,
      note: `save intent: ${params.section}.${params.action} key=${params.targetKey}`,
    });
  }

  static settingsSaveResult2(params: {
    screen: string;
    section: string;
    action: string;
    targetKey: string;
    success: boolean;
    errorMessage?: string;
    persistedValue?: unknown;
    durationMs: number;
  }): void {
    UltraDevLog.push('SETTINGS_SAVE_RESULT', {
      ...params,
      note: params.success
        ? `ok — ${params.section}.${params.action} in ${params.durationMs}ms`
        : `WARN: ${params.section}.${params.action} failed: ${params.errorMessage ?? 'unknown'}`,
    });
  }

  static routingAssignmentChange(params: {
    operation: string;
    beforeGroupId: string | null;
    afterGroupId: string | null;
    eligibleGroupIds: string[];
    success: boolean;
    errorMessage?: string;
    saveSource: string;
  }): void {
    UltraDevLog.push('ROUTING_ASSIGNMENT_CHANGE', {
      ...params,
      note: params.success
        ? `ok — ${params.operation}: ${params.beforeGroupId ?? 'none'} → ${params.afterGroupId ?? 'none'} via ${params.saveSource}`
        : `WARN: routing assignment failed for ${params.operation}: ${params.errorMessage ?? 'unknown'}`,
    });
  }

  static apiStateSnapshot(params: {
    reason: string;
    sourceOfTruth: 'startup' | 'bridge_refresh' | 'provider_probe' | 'picker_open' | 'routing_change' | 'settings_save' | 'manual';
    providerCount: number;
    activeProviderCount: number;
    providerIds: string[];
    activeProviderIds: string[];
    providerModelCounts: Record<string, number>;
    providerBackedModelCount: number;
    legacyModelCount: number;
    defaultModel: string | null;
    selectedModel: string | null;
    bridgeAttached: boolean;
    bridgeHasActiveProvider: boolean;
    operationMapping?: Record<string, string | null>;
    assignedGroupForCurrentOperation?: string | null;
    eligibleGroupIdsForCurrentOperation?: string[];
    routeRestricted?: boolean;
    currentMode?: string;
    note?: string;
  }): void {
    UltraDevLog.push('API_STATE_SNAPSHOT', {
      ...params,
      note: params.note ?? (params.activeProviderCount === 0
        ? 'WARN: no active providers'
        : `ok — ${params.activeProviderCount}/${params.providerCount} providers active, ${params.providerBackedModelCount} providerModels legacy=${params.legacyModelCount} bridge=${params.bridgeAttached}`),
    });
  }

  static modelInventorySync(params: {
    reason: string;
    source: 'provider_bridge' | 'legacy_cache' | 'mixed' | 'empty';
    providerBackedModelCount: number;
    legacyModelCount: number;
    returnedToPickerCount: number;
    currentMode?: string;
    requestedFilter?: string;
    effectiveFilter?: string;
    rawFilteredCount?: number;
    displayedCount?: number;
    fallbackUsed?: boolean;
    routeRestricted?: boolean;
    assignedGroupId?: string | null;
    eligibleGroupIds?: string[];
    selectedModel?: string | null;
    defaultModel?: string | null;
    note?: string;
  }): void {
    UltraDevLog.push('MODEL_INVENTORY_SYNC', {
      ...params,
      note: params.note ?? `ok — src=${params.source} total=${params.providerBackedModelCount + params.legacyModelCount} backed=${params.providerBackedModelCount} legacy=${params.legacyModelCount} shown=${params.displayedCount ?? params.returnedToPickerCount} reason=${params.reason}`,
    });
  }

  static routingAssignment(params: {
    reason: string;
    operation: string;
    groupId: string | null;
    success: boolean;
    eligibleGroupIds: string[];
    mappingAfter: Record<string, string | null>;
    note?: string;
    error?: string;
  }): void {
    UltraDevLog.push('ROUTING_ASSIGNMENT', {
      ...params,
      note: params.note ?? (params.success
        ? `ok — ${params.operation} → ${params.groupId ?? 'none'} eligible=[${params.eligibleGroupIds.join(',')}] reason=${params.reason}`
        : `WARN: routing assignment failed for ${params.operation}: ${params.error ?? 'unknown'}`),
    });
  }

  static pickerOpenDetailed(params: {
    requestedFilter: string;
    effectiveFilter: string;
    currentMode: string;
    totalModels: number;
    rawFilteredCount: number;
    displayedCount: number;
    fallbackUsed: boolean;
    source: 'provider_bridge' | 'legacy_cache' | 'mixed' | 'empty';
    /** What the router/ModelRouter reports as the current model (may differ from UI) */
    selectedModel: string | null;
    defaultModel: string | null;
    slideAnimCurrentValue: number;
    note: string;
    /** The model displayed in the UI pill (React state activeModelId) — distinct source from router */
    uiSelectedModel?: string | null;
    /** The model the router/ModelRouter would resolve for this operation */
    routerSelectedModel?: string | null;
    selectedModelMismatch?: boolean;
    /** Whether this picker entry point is intentionally unrestricted, operation-restricted, or fallback-all */
    entryPoint?: 'manual_model_browser' | 'operation_restricted' | 'fallback_all';
    /** The AllowedOperation this picker was opened for (undefined = intentionally unrestricted) */
    requestedOperation?: string;
    /** Whether task defaults exist and restrict which route is taken automatically */
    routeRestricted?: boolean;
    /** The primary task-default candidate(s) for this operation, as "providerId/modelId" strings */
    assignedTaskDefaultCandidates?: string[];
    /** How many candidates are eligible for this operation from task defaults */
    eligibleCandidateCount?: number;
  }): void {
    const uiSel = params.uiSelectedModel ?? params.selectedModel;
    const routerSel = params.routerSelectedModel ?? params.selectedModel;
    const mismatch = params.selectedModelMismatch ?? (uiSel !== routerSel && uiSel !== null && routerSel !== null);
    UltraDevLog.push('PICKER_OPEN', {
      ...params,
      uiSelectedModel: uiSel,
      routerSelectedModel: routerSel,
      selectedModelMismatch: mismatch,
    });
  }

  static pickerContentDetailed(params: {
    requestedFilter: string;
    effectiveFilter: string;
    currentMode: string;
    rawFilteredCount: number;
    displayedCount: number;
    totalModels: number;
    fallbackUsed: boolean;
    source: 'provider_bridge' | 'legacy_cache' | 'mixed' | 'empty';
    /** What the router/ModelRouter reports as the current model (may differ from UI) */
    selectedModel: string | null;
    defaultModel: string | null;
    note: string;
    /** The model displayed in the UI pill (React state activeModelId) — distinct source from router */
    uiSelectedModel?: string | null;
    /** The model the router/ModelRouter would resolve for this operation */
    routerSelectedModel?: string | null;
    selectedModelMismatch?: boolean;
    /** The AllowedOperation this picker was opened for (undefined = intentionally unrestricted) */
    requestedOperation?: string;
    /** Whether task defaults exist and restrict which route is taken automatically */
    routeRestricted?: boolean;
    /** The primary task-default candidate(s) for this operation, as "providerId/modelId" strings */
    assignedTaskDefaultCandidates?: string[];
    /** How many candidates are eligible for this operation from task defaults */
    eligibleCandidateCount?: number;
  }): void {
    const uiSel = params.uiSelectedModel ?? params.selectedModel;
    const routerSel = params.routerSelectedModel ?? params.selectedModel;
    const mismatch = params.selectedModelMismatch ?? (uiSel !== routerSel && uiSel !== null && routerSel !== null);
    UltraDevLog.push('PICKER_CONTENT', {
      ...params,
      uiSelectedModel: uiSel,
      routerSelectedModel: routerSel,
      selectedModelMismatch: mismatch,
    });
  }

  static permissionSnapshotStartup(permissions: Record<string, string>): void {
    UltraDevLog.push('PERMISSION_SNAPSHOT_STARTUP', {
      permissions,
      note: `startup snapshot: ${Object.keys(permissions).length} permissions`,
    });
  }

  static permissionRecheckRuntime(params: {
    capability: string;
    permission: string;
    startupStatus: string;
    runtimeStatus: string;
    granted: boolean;
  }): void {
    UltraDevLog.push('PERMISSION_RECHECK_RUNTIME', {
      ...params,
      note: params.granted
        ? `ok — ${params.permission} granted at runtime`
        : `WARN: ${params.permission} not granted at runtime for capability ${params.capability}`,
    });
  }

  static permissionContradiction(params: {
    capability: string;
    permission: string;
    startupStatus: string;
    runtimeStatus: string;
    contradictionType: string;
  }): void {
    UltraDevLog.push('PERMISSION_CONTRADICTION', {
      ...params,
      note: `WARN: permission contradiction for ${params.permission} on ${params.capability}: startup=${params.startupStatus} runtime=${params.runtimeStatus}`,
    });
  }

  // ─── Legacy compatibility stubs ────────────────────────────────────────────

  static agentExecuteStart(taskId: string, conversationId: string, inputLength: number, isReplay: boolean): void {
    UltraDevLog.push('AGENT_STEP', { event: 'execute_start', taskId, conversationId, inputLength, isReplay });
  }

  static agentInitStart(): void { UltraDevLog.push('SYSTEM', { event: 'agent_init_start' }); }
  static agentInitComplete(durationMs?: number): void { UltraDevLog.push('SYSTEM', { event: 'agent_init_complete', durationMs }); }
  static agentInitSubsystem(subsystem: string, status: string | boolean): void { UltraDevLog.push('SYSTEM', { event: 'agent_init_subsystem', subsystem, status }); }
  static buildStart(taskId: string, description: string): void { UltraDevLog.push('SYSTEM', { event: 'build_start', taskId, description: description.slice(0, 200) }); }
  static buildPhase(taskId: string, phase: string, message: string): void { UltraDevLog.push('SYSTEM', { event: 'build_phase', taskId, phase, message }); }
  static buildComplete(taskId: string, success: boolean, details?: string): void { UltraDevLog.push('SYSTEM', { event: 'build_complete', taskId, success, details }); }

  static conversationError(conversationId: string, error: string): void { UltraDevLog.push('SYSTEM', { event: 'conversation_error', conversationId, error }); }

  static conversationList(count: number, activeId?: string | null): void {
    if (count === UltraDevLog.lastConvListCount) return;
    UltraDevLog.lastConvListCount = count;
    UltraDevLog.push('SYSTEM', { event: 'conversation_list', count, activeId });
  }

  static conversationLoaded(conversationId: string, messageCount: number): void {
    if (UltraDevLog.lastConvLoadedCounts.get(conversationId) === messageCount) return;
    UltraDevLog.lastConvLoadedCounts.set(conversationId, messageCount);
    UltraDevLog.push('SYSTEM', { event: 'conversation_loaded', conversationId, messageCount });
  }

  static conversationMessage(conversationId: string, role: string, contentLenOrPreview: number | string, source?: string): void {
    const preview = typeof contentLenOrPreview === 'number' ? `[${contentLenOrPreview} chars]` : (contentLenOrPreview ?? '').slice(0, 200);
    UltraDevLog.push('SYSTEM', { event: 'conversation_message', conversationId, role, contentPreview: preview, source });
  }

  static conversationSaved(conversationId: string, messageCount?: number): void { UltraDevLog.push('SYSTEM', { event: 'conversation_saved', conversationId, messageCount }); }
  static costLimitCheck(model: string, withinLimit: boolean, spent?: number, limit?: number): void { UltraDevLog.push('SYSTEM', { event: 'cost_limit_check', model, withinLimit, spent, limit }); }
  static flushToFile(): void { UltraDevLog.scheduleFlush(); }
  static getDir(): string { return UltraDevLog.getLogDir(); }
  static getFilePath(): string { return UltraDevLog.getSessionFilePath(); }
  static getMemoryEntriesFormatted(limit?: number): string { return UltraDevLog.getFormattedLog(limit); }
  static modelAbort(taskId: string, reason?: string): void { UltraDevLog.push('SYSTEM', { event: 'model_abort', taskId, reason }); }
  static modelApiError(model: string, taskId: string, error: string, durationMs: number): void { UltraDevLog.push('SYSTEM', { event: 'model_api_error', model, taskId, error, durationMs }); }
  static modelApiRequest(model: string, taskId: string, promptTokens: number, maxTokens: number): void { UltraDevLog.push('API_CALL', { event: 'request', model, taskId, promptTokens, maxTokens }); }
  static modelApiResponse(model: string, taskId: string, inputTokens: number, outputTokens: number, cost: number, durationMs: number): void { UltraDevLog.push('API_CALL', { event: 'response', model, taskId, inputTokens, outputTokens, cost, durationMs }); }
  static modelDiscoveryStart(baseUrl?: string): void { UltraDevLog.push('SYSTEM', { event: 'model_discovery_start', baseUrl }); }
  static modelDiscoveryResult(modelCount: number, modelIds?: string[]): void { UltraDevLog.push('SYSTEM', { event: 'model_discovery_result', modelCount, modelIds }); }
  static modelDiscoveryError(error: string): void { UltraDevLog.push('SYSTEM', { event: 'model_discovery_error', error }); }
  static modelImageRequest(model: string, promptLength: number): void { UltraDevLog.push('SYSTEM', { event: 'model_image_request', model, promptLength }); }
  static modelImageResponse(model: string, imageCount: number, cost: number, durationMs: number): void { UltraDevLog.push('SYSTEM', { event: 'model_image_response', model, imageCount, cost, durationMs }); }
  static modelImageError(model: string, error: string): void { UltraDevLog.push('SYSTEM', { event: 'model_image_error', model, error }); }
  static modelSetDefault(modelId: string, prev: string, source: string): void { UltraDevLog.push('SYSTEM', { event: 'model_set_default', modelId, prev, source }); }
  static modelSetDefaultError(modelId: string, error: string): void { UltraDevLog.push('SYSTEM', { event: 'model_set_default_error', modelId, error }); }
  private static lastModelStateHash = new Map<string, string>();
  static modelState(key: string, value: unknown): void {
    const hash = JSON.stringify(value);
    if (UltraDevLog.lastModelStateHash.get(key) === hash) return;
    UltraDevLog.lastModelStateHash.set(key, hash);
    UltraDevLog.push('SYSTEM', { event: 'model_state', key, value });
  }
  static permissionCheck(permission: string, status: string): void { UltraDevLog.push('SYSTEM', { event: 'permission_check', permission, status }); }
  static settingsApiSave(key: string, success: boolean): void { UltraDevLog.push('SYSTEM', { event: 'settings_api_save', key, success }); }
  static settingsApiDelete(key: string, success: boolean): void { UltraDevLog.push('SYSTEM', { event: 'settings_api_delete', key, success }); }
  static settingsCostLimitSave(limit: number, success: boolean): void { UltraDevLog.push('SYSTEM', { event: 'settings_cost_limit_save', limit, success }); }
  static settingsDefaultPick(mode: string, modelId: string): void { UltraDevLog.push('SYSTEM', { event: 'settings_default_pick', mode, modelId }); }
  static settingsDefaultsSave(defaults: Record<string, unknown>): void { UltraDevLog.push('SYSTEM', { event: 'settings_defaults_save', defaults }); }
  static settingsState(key: string, value: unknown): void { UltraDevLog.push('SYSTEM', { event: 'settings_state', key, value }); }
  static uiInit(stage: string, message: string): void { UltraDevLog.push('SYSTEM', { event: 'ui_init', stage, message }); }

  static uiState(label: string, state: Record<string, unknown>): void {
    const TRACKED = ['isProcessing', 'conversationId', 'modelsLoaded', 'hasApiKey', 'activeModelId', 'savedDefaults', 'pickerVisible', 'currentMode'];
    const hash = TRACKED.map(k => `${k}:${JSON.stringify(state[k])}`).join('|');
    if (hash === UltraDevLog.lastUiStateHash && !['init_complete', 'before_send'].includes(label)) return;
    UltraDevLog.lastUiStateHash = hash;
    UltraDevLog.push('SYSTEM', { event: 'ui_state', label, state });
  }

  static uiError(context: string, message: string): void { UltraDevLog.push('SYSTEM', { event: 'ui_error', context, message }); }
  static uiConvSwitch(fromId: string, toId: string): void { UltraDevLog.push('SYSTEM', { event: 'ui_conv_switch', fromId, toId }); }
  static uiDefaultsLoaded(source: string, defaults: Record<string, unknown>): void { UltraDevLog.push('SYSTEM', { event: 'ui_defaults_loaded', source, defaults }); }
  static uiModelApply(modelId: string, mode: string, source: string, success: boolean, error?: string): void { UltraDevLog.push('SYSTEM', { event: 'ui_model_apply', modelId, mode, source, success, error }); }
  static uiModeSwitch(from: string, to: string, source: string): void { UltraDevLog.push('SYSTEM', { event: 'ui_mode_switch', from, to, source }); }
  static uiPickerOpen(filter: string, mode: string, activeModelId: string | null): void { UltraDevLog.push('SYSTEM', { event: 'ui_picker_open', filter, mode, activeModelId }); }
  static uiPickerSelect(modelId: string, previousModelId: string | null): void { UltraDevLog.push('SYSTEM', { event: 'ui_picker_select', modelId, previousModelId }); }
  static uiPlusMenuSelect(type: string, hasDefault: boolean, defaultModel: string | null): void { UltraDevLog.push('SYSTEM', { event: 'ui_plus_menu_select', type, hasDefault, defaultModel }); }
  static uiSendMessage(inputLength: number, mode: string, modelId: string | null, isProcessing: boolean): void { UltraDevLog.push('SYSTEM', { event: 'ui_send_message', inputLength, mode, modelId, isProcessing }); }
  static uiStopRequest(hasCore: boolean): void { UltraDevLog.push('SYSTEM', { event: 'ui_stop_request', hasCore }); }
  static uiFocusEffect(trigger: string, mode: string, savedKeys: string[], activeModelId: string | null): void { UltraDevLog.push('SYSTEM', { event: 'ui_focus_effect', trigger, mode, savedKeys, activeModelId }); }

  static vaultGet(key: string, found: boolean, valuePreview?: string): void { UltraDevLog.vaultRead(key, found, valuePreview); }
  static vaultSet(key: string, success: boolean, valuePreview?: string): void { UltraDevLog.vaultWrite(key, success, valuePreview); }
  static vaultDelete(key: string, success?: boolean): void { UltraDevLog.push('SYSTEM', { event: 'vault_delete', key, success: success ?? true }); }
  static vaultError(operation: string, key: string, error: string): void { UltraDevLog.push('SYSTEM', { event: 'vault_error', operation, key, error }); }

  // ─── Format & Export ───────────────────────────────────────────────────────

  static getMemoryEntries(limit?: number): UltraLogEntry[] {
    return limit ? UltraDevLog.entries.slice(-limit) : [...UltraDevLog.entries];
  }

  static getFormattedLog(limit = 500): string {
    return UltraDevLog.entries.slice(-limit).map(e => UltraDevLog.formatEntry(e)).join('\n');
  }

  static getEntries(limit?: number): UltraLogEntry[] {
    if (limit !== undefined) return UltraDevLog.entries.slice(-limit);
    return [...UltraDevLog.entries];
  }

  private static bubbleDiagState = new Map<string, { count: number; lastHeight: number }>();

  static bubbleDiag(
    messageId: string,
    role: string,
    height: number,
    width: number,
    textLength: number,
    msgIndex: number,
    msgStyle: string,
  ): void {
    const prev = UltraDevLog.bubbleDiagState.get(messageId);
    if (prev) {
      prev.count++;
      const delta = Math.abs(height - prev.lastHeight);
      if (prev.count % 10 !== 0 && delta < 50) return;
      prev.lastHeight = height;
    } else {
      UltraDevLog.bubbleDiagState.set(messageId, { count: 1, lastHeight: height });
    }
    if (UltraDevLog.bubbleDiagState.size > 100) {
      const first = UltraDevLog.bubbleDiagState.keys().next().value;
      if (first) UltraDevLog.bubbleDiagState.delete(first);
    }
    UltraDevLog.push('BUBBLE_DIAG', { messageId, role, height, width, textLength, msgIndex, msgStyle });
  }

  private static formatEntry(e: UltraLogEntry | null | undefined): string {
    const safe = e ?? ({
      ts: '',
      t: 0,
      cat: 'SYSTEM',
      data: {},
      seq: 0,
    } as UltraLogEntry);

    const d = (safe.data?.payload as Record<string, unknown>) || safe.data || {};
    const t = typeof safe.ts === 'string' && safe.ts.length >= 23
      ? safe.ts.slice(11, 23)
      : '??:??:??.???';
    const cat = safe.cat ?? 'SYSTEM';
    const c = safe.coreId ? ` [${safe.coreId.slice(-6)}]` : '';
    const w = (s: unknown) =>
      (typeof s === 'string' && (s.startsWith('WARN') || s.startsWith('BUG'))) ? ' *** ' : ' ';

    switch (cat) {
      case 'USER_MSG': return `${t} [USER    ]${c} ${d.userInputSummary ?? `[${d.contentLength ?? '?'} chars]`}`;
      case 'AI_RESPONSE': return `${t} [AI      ]${c} model=${d.model} cost=$${d.cost} | ${d.responseSummary ?? `${d.responseLength ?? '?'} chars`}`;
      case 'AGENT_STEP':
        if (d.event === 'execute_start') return `${t} [EXEC_ST ]${c} task=${d.taskId} len=${d.inputLength} replay=${d.isReplay}`;
        return `${t} [STEP    ]${c} [${d.phase}] ${d.success ? 'OK' : 'FAIL'} ${d.detail}`;
      case 'MODE': return `${t} [MODE    ]${c} ${d.mode} | "${d.inputPreview}"`;
      case 'PLAN': return `${t} [PLAN    ]${c} cap=${d.capability} det=${d.deterministic} params=${JSON.stringify(d.params)}`;
      case 'SAFETY': return `${t} [SAFETY  ]${c} risk=${d.risk} allowed=${d.allowed}`;
      case 'EXEC_RESULT': return `${t} [EXEC    ]${c} ${d.capability} ${d.success ? 'OK' : 'FAIL'} ${(d.resultPreview as string).slice(0, 200)}`;
      case 'VERIFY': return `${t} [VERIFY  ]${c} ${d.verified ? 'OK' : 'FAIL'} issues=[${(d.issues as string[]).join('; ') || 'none'}]`;
      case 'ERROR': return `${t} [ERROR   ]${c} [${d.context}] ${d.message}${d.stack ? ' | ' + (d.stack as string).slice(0, 250) : ''}`;
      case 'SYSTEM': return `${t} [SYSTEM  ]${c} ${d.event ?? d.context ?? ''} ${d.message ?? d.stage ?? JSON.stringify(d).slice(0, 180)}`;
      case 'COST': return `${t} [COST    ] $${d.cost} ${d.model}`;
      case 'API_CALL':
        if (d.event === 'request') return `${t} [API_REQ ]${c} ${d.model} promptTok=${d.promptTokens}`;
        if (d.event === 'response') return `${t} [API_RES ]${c} ${d.model} ${d.durationMs}ms cost=$${d.cost ?? 0}`;
        return `${t} [API     ]${c} ${d.model} in=${d.promptTokens} out=${d.completionTokens} ${d.durationMs}ms`;
      case 'MODEL_SWITCH': return `${t} [MODEL   ]${c} ${d.from} -> ${d.to}`;
      case 'CONV_NEW': return `${t} [CONV_NEW] ${d.conversationId} "${d.title}"`;
      case 'CONV_DEL': return `${t} [CONV_DEL] ${d.conversationId}`;
      case 'UI_MODAL': return `${t} [MODAL   ]${c} ${d.modalName} ${d.action}`;
      case 'UI_PROCESSING': return `${t} [PROC    ]${c} processing=${d.isProcessing} src=${d.source}${d.durationMs ? ` dur=${d.durationMs}ms` : ''}`;
      case 'UI_RENDER_MSG': return `${t} [MSGS    ]${c} ${d.prevCount}->${d.nextCount} src=${d.source}`;
      case 'UI_SEND_ATTEMPT': return `${t} [SEND?   ]${c} mode=${d.mode} model=${d.modelId} msgs=${d.messageCount} locked=${d.isProcessing} "${d.inputPreview}"`;
      case 'UI_SEND_COMPLETE': return `${t} [SENT    ]${c} task=${d.taskId} ${d.success ? 'OK' : 'FAIL'} ${d.durationMs}ms`;
      case 'APP_LAUNCH_BEGIN': return `${t} [LAUNCH> ]${c} "${d.target}"`;
      case 'APP_LAUNCH_DEVICE': return `${t} [LAUNCH? ]${c} ${d.appCount} apps ${d.queryDurationMs}ms${d.error ? ' ERR:' + d.error : ''}`;
      case 'APP_LAUNCH_MATCH': return `${t} [LAUNCH= ]${c} "${d.target}" ${d.matchType} pkg=${d.resolvedPkg || 'NONE'}`;
      case 'APP_LAUNCH_AI': return `${t} [LAUNCH_AI]${c} "${d.target}" -> "${d.resolvedPkg}" ${d.durationMs}ms`;
      case 'APP_LAUNCH_FIRE': return `${t} [LAUNCH! ]${c} pkg="${d.pkg}" <- SUSPENDS`;
      case 'APP_LAUNCH_RESUME': return `${t} [LAUNCH^ ]${c} resumed after ${d.suspendDuration}ms`;
      case 'APP_LAUNCH_FAIL': return `${t} [LAUNCH_X]${c} stage=${d.stage} "${d.error}"`;
      case 'SMS_RESOLVE': return `${t} [SMS?    ]${c} "${d.inputName}" resolved=${d.resolved} num=${d.resolvedNumber || 'NOT FOUND'}${d.error ? ' ERR:' + d.error : ''}`;
      case 'SMS_FIRE': return `${t} [SMS!    ]${c} isPhone=${d.toIsPhone}${d.toIsPhone ? '' : ' <- NOT A PHONE'}`;
      case 'SMS_RESULT': return `${t} [SMS_OK  ]${c} ${d.result} ok=${d.success}`;
      case 'VAULT_READ': return `${t} [VAULT_R ] ${d.key} found=${d.found}${d.valuePreview ? ` "${d.valuePreview}"` : ''}`;
      case 'VAULT_WRITE': return `${t} [VAULT_W ] ${d.key} ok=${d.success}${d.valuePreview ? ` "${d.valuePreview}"` : ''}`;
      case 'PARSE_INPUT': return `${t} [PARSE   ]${c} pat=${d.matchedPattern || 'NONE'} cap=${d.capability || 'NONE'} compound=${d.isCompound}`;
      case 'PARSE_COMPOUND': return `${t} [COMPOUND]${c} "${d.input}"`;
      case 'PICKER_OPEN': {
        if (d.requestedFilter !== undefined) {
          const mismatch = d.selectedModelMismatch ? ' *** MODEL_MISMATCH' : '';
          const ep = d.entryPoint ? ` ep=${d.entryPoint}` : '';
          const op = d.requestedOperation ? ` op=${d.requestedOperation}` : '';
          const asgn = (d.assignedTaskDefaultCandidates as unknown[] | undefined)?.length
            ? ` tdAssigned=[${(d.assignedTaskDefaultCandidates as string[]).join(',')}]`
            : (d.assignedGroupId ? ` assigned=${d.assignedGroupId}` : ' assigned=none');
          const elig = d.eligibleCandidateCount !== undefined ? ` elig=${d.eligibleCandidateCount}` : '';
          return `${t} [PICKER> ]${ep}${op} req=${d.requestedFilter} eff=${d.effectiveFilter} total=${d.totalModels} raw=${d.rawFilteredCount} shown=${d.displayedCount} fallback=${d.fallbackUsed} src=${d.source}${asgn}${elig} routeRestricted=${d.routeRestricted ?? false} ui=${d.uiSelectedModel ?? 'none'} router=${d.routerSelectedModel ?? 'none'} default=${d.defaultModel ?? 'none'} anim=${d.slideAnimCurrentValue}${mismatch} ${d.note}`;
        }
        return `${t} [PICKER> ] ${d.modelCount} models filter=${d.filter} anim=${d.slideAnimCurrentValue} ${d.note}`;
      }
      case 'PICKER_CLOSE': return `${t} [PICKER< ] via ${d.how}`;
      case 'PICKER_ANIMATE': return `${t} [PICKER~ ] ${d.direction} ${d.fromValue}->${d.toValue} ${d.animationType}`;
      case 'PICKER_SELECT': return `${t} [PICKER+ ] ${d.modelId} was=${d.previousModelId}`;
      case 'CORE_INSTANCE': {
        const icon = d.event === 'created' ? 'NEW' : d.event === 'destroyed' ? 'DEL' : 'TAG';
        return `${t} [CORE_${icon}]${w(d.note)}id=${(d.instanceId as string)?.slice(-8)} ${d.event} ${d.reason ?? ''} ${d.note}`;
      }
      case 'EXECUTOR_BRANCH': return `${t} [EXEC_BR ]${c} [${d.capability}] ${d.branch}${d.success !== undefined ? ` ok=${d.success}` : ''}${d.path ? ` path=${d.path}` : ''}`;
      case 'CONV_CONTEXT_SENT': return `${t} [CTX_SENT]${c} tok~${d.estimated_total_tokens} sys=${d.systemPct}% hist=${d.historyPct}% cap=${d.capabilityPct}% usr=${d.userPct}%${(d.contaminationRisk || d.bloatRisk) ? ' *** ' + d.note : ''}`;
      case 'UI_MESSAGE_RENDERED':
        if (d.event === 'scroll') return `${t} [SCROLL  ] y=${d.scrollOffsetY}px ${d.scrollPct}%${d.isAtBottom ? ' [BOT]' : ''}`;
        return `${t} [RENDERED]${c} ${d.messageId} h=${d.measuredHeightPx}px idx=${d.indexInList} vis=${d.isVisible} hKnown=${d.listHeightKnown}${d.tallWarning || !d.listHeightKnown ? ' *** ' + d.note : ''}`;
      case 'TASK_WATCHDOG': {
        const bang = d.event === 'FIRED' ? '!!! ' : '';
        return `${t} [WATCHDOG] ${bang}${d.taskId} ${d.phase} ${d.event}${d.stalenessMs ? ` stale=${d.stalenessMs}ms` : ''}${d.elapsedMs ? ` ok=${d.elapsedMs}ms` : ''}`;
      }
      case 'APP_STATE_CHANGE': return `${t} [APPSTATE] ${d.nextState} +${d.elapsedSinceLastChange}ms${d.backgroundDurationMs ? ` bg=${d.backgroundDurationMs}ms` : ''} core=${d.activeCoreId ?? 'none'}${(d.note as string) !== 'ok' ? ' *** ' + d.note : ''}`;
      case 'COMPONENT_LIFECYCLE': {
        const warn = (d.priorMountsThisSession as number) > 0 && d.event === 'mount' ? ' *** ' : ' ';
        return `${t} [COMP_LC ]${warn}${d.event} ${d.componentName} id=${(d.componentId as string)?.slice(-6)}${d.reason ? ` reason=${d.reason}` : ''} ${d.note ?? ''}`;
      }
      case 'SETTINGS_SAVE': {
        const icon = d.event === 'tap' ? '>' : d.success === false ? 'FAIL' : d.success === true ? 'OK' : 'info';
        return `${t} [SETSAVE ] ${icon} ${d.section} ${d.event}${d.savedKeys ? ` keys=${(d.savedKeys as string[]).join(',')}` : ''}${d.error ? ` ERR=${d.error}` : ''}`;
      }
      case 'PICKER_FILTER_CHANGE': return `${t} [PICK_FLT] ${d.fromFilter} → ${d.toFilter} displayed=${d.displayedCountAfter} raw=${d.rawCountAfter} route=${d.routeRestrictionActive}${(d.note as string)?.startsWith('WARN') ? ' *** ' + d.note : ''}`;
      case 'SETTINGS_SAVE_START': return `${t} [SAVE_ST ] ${d.screen}.${d.section}.${d.action} key=${d.targetKey}`;
      case 'SETTINGS_SAVE_RESULT': return `${t} [SAVE_RS ] ${d.success ? 'OK' : 'FAIL'} ${d.screen}.${d.section}.${d.action} ${d.durationMs}ms${d.errorMessage ? ' ERR=' + d.errorMessage : ''}`;
      case 'ROUTING_ASSIGNMENT_CHANGE': return `${t} [ROUTE_CH] ${d.success ? 'OK' : 'FAIL'} op=${d.operation} ${d.beforeGroupId ?? 'none'} → ${d.afterGroupId ?? 'none'} via=${d.saveSource}${(d.note as string)?.startsWith('WARN') ? ' *** ' + d.note : ''}`;
      case 'ROUTING_ASSIGNMENT': return `${t} [ROUTE_AS] ${d.success ? 'OK' : 'FAIL'} op=${d.operation} group=${d.groupId ?? 'none'} eligible=[${(d.eligibleGroupIds as string[])?.join(',') ?? ''}] reason=${d.reason}${(d.note as string)?.startsWith('WARN') ? ' *** ' + d.note : ''}`;
      case 'API_STATE_SNAPSHOT': return `${t} [API_ST  ] ${d.reason} providers=${d.activeProviderCount}/${d.providerCount} provModels=${d.providerBackedModelCount} legacy=${d.legacyModelCount} selected=${d.selectedModel ?? 'none'} default=${d.defaultModel ?? 'none'} bridge=${d.bridgeAttached} routeRestricted=${d.routeRestricted} assigned=${d.assignedGroupForCurrentOperation ?? 'none'} mode=${d.currentMode ?? '?'}${(d.note as string)?.startsWith('WARN') ? ' *** ' + d.note : ''}`;
      case 'MODEL_INVENTORY_SYNC': return `${t} [MDL_SYN ] ${d.reason} src=${d.source} backed=${d.providerBackedModelCount} legacy=${d.legacyModelCount} shown=${d.displayedCount ?? d.returnedToPickerCount} req=${d.requestedFilter ?? '?'} eff=${d.effectiveFilter ?? '?'} fallback=${d.fallbackUsed ?? false} routeRestricted=${d.routeRestricted ?? false} assigned=${d.assignedGroupId ?? 'none'}${(d.note as string)?.startsWith('WARN') ? ' *** ' + d.note : ''}`;
      case 'PERMISSION_SNAPSHOT_STARTUP': return `${t} [PERM_SS ] ${JSON.stringify(d.permissions).slice(0, 200)}`;
      case 'PERMISSION_RECHECK_RUNTIME': return `${t} [PERM_RC ] cap=${d.capability} perm=${d.permission} startup=${d.startupStatus} runtime=${d.runtimeStatus} granted=${d.granted}${(d.note as string)?.startsWith('WARN') ? ' *** ' + d.note : ''}`;
      case 'PERMISSION_CONTRADICTION': return `${t} [PERM_!! ] *** cap=${d.capability} perm=${d.permission} startup=${d.startupStatus} runtime=${d.runtimeStatus} type=${d.contradictionType}`;
      case 'EXECUTE_PHASE': return `${t} [EX_PHASE]${c} ${d.phase} task=${d.taskId}`;
      case 'NAV_CHANGE': return `${t} [NAV     ]${c} route=${d.routeName} action=${d.action} depth=${d.stackDepth}`;
      case 'FOCUS_EFFECT_DEPS':
        if (d.event === 'suppressed') return `${t} [FOCUS_T ] throttle ok ${d.elapsedMs}ms`;
        return `${t} [FOCUS_D ] changed=[${(d.changedDeps as string[]).join(',')}]${d.isFirstTrigger ? ' (first)' : ''}${(d.note as string)?.startsWith('WARN') ? ' *** ' + d.note : ''}`;
      case 'PROCESS_RESTART': return `${t} [PROC_RS ] ${d.event}${d.backgroundDurationMs ? ` bg=${d.backgroundDurationMs}ms` : ''} ${d.note}`;
      case 'PICKER_CONTENT': {
        if (d.requestedFilter !== undefined) {
          const mismatch = d.selectedModelMismatch ? ' *** MODEL_MISMATCH' : '';
          const op = d.requestedOperation ? ` op=${d.requestedOperation}` : '';
          const asgn = (d.assignedTaskDefaultCandidates as unknown[] | undefined)?.length
            ? ` tdAssigned=[${(d.assignedTaskDefaultCandidates as string[]).join(',')}]`
            : (d.assignedGroupId ? ` assigned=${d.assignedGroupId}` : ' assigned=none');
          const elig = d.eligibleCandidateCount !== undefined ? ` elig=${d.eligibleCandidateCount}` : '';
          return `${t} [PICK_CT ]${op} req=${d.requestedFilter} eff=${d.effectiveFilter} raw=${d.rawFilteredCount} shown=${d.displayedCount} total=${d.totalModels} fallback=${d.fallbackUsed} src=${d.source}${asgn}${elig} routeRestricted=${d.routeRestricted ?? false} ui=${d.uiSelectedModel ?? 'none'} router=${d.routerSelectedModel ?? 'none'} default=${d.defaultModel ?? 'none'}${mismatch}${(d.note as string)?.startsWith('WARN') ? ' *** ' + d.note : ''}`;
        }
        return `${t} [PICK_CT ] filter=${d.activeFilter} ${d.filteredCount}/${d.totalCount}${(d.note as string)?.startsWith('WARN') ? ' *** ' + d.note : ''}`;
      }
      case 'CONTEXT_PROVIDER': return `${t} [CTX_PRV ] ${d.providerName} render#${d.renderCount} core=${(d.coreInstanceId as string)?.slice(-6)} ${d.note}`;
      case 'SESSION_SUMMARY': return `${t} [SUMMARY ] ${JSON.stringify(d)}`;
      case 'DEVICE_INFO': return `${t} [DEVICE  ] os=${d.os} ver=${d.osVersion} model=${d.model} screen=${d.screenWidth}x${d.screenHeight} ram=${d.totalMemory ?? 'unknown'}`;
      case 'NETWORK_STATUS': return `${t} [NETWORK ] ${d.isConnected ? 'online' : 'OFFLINE'} type=${d.type}${d.note ? ' ' + d.note : ''}`;
      case 'PERMISSION_STATUS': return `${t} [PERM    ] ${d.permission} status=${d.status}`;
      case 'ERROR_BOUNDARY': return `${t} [REACT_ERR]${c} ${d.error} | stack=${String(d.componentStack).slice(0, 300)}`;
      case 'SETTINGS_INTENT': return `${t} [SETTINGS ]${c} ${d.matched ? 'HIT' : 'MISS'} query="${d.query}" action=${d.action ?? 'none'} label=${d.label ?? 'none'}`;
      case 'DEEP_LINK': return `${t} [DEEPLINK ]${c} ${d.matched ? 'HIT' : 'MISS'} query="${d.query}" uri=${d.uri ?? 'none'}`;
      case 'SYSTEM_ACTION': return `${t} [SYS_ACT  ]${c} trigger="${d.trigger}" routed=${d.routedToSettings} ok=${d.success}`;
      case 'SYSTEM_INFO': return `${t} [SYS_INFO ]${c} bat=${d.batteryPct ?? '?'}% ram=${d.ramUsedMB ?? '?'}/${d.ramTotalMB ?? '?'}MB storage=${d.storageFreeGB ?? '?'}/${d.storageTotalGB ?? '?'}GB temp=${d.cpuTempC ?? '?'}°C${(d.failedReads as unknown[] | undefined)?.length ? ' WARN:failed=' + d.failedReads : ''}`;
      case 'PREFERENCE_BACKUP': return `${t} [PREF_BAK ]${c} ${d.operation} ${d.success ? 'OK' : 'FAIL'} keys=${d.keysCount}${d.error ? ' err=' + d.error : ''}`;
      case 'LEARN_PACKAGE': return `${t} [LEARN_PKG]${c} "${d.trigger}" → ${d.packageName} update=${d.wasUpdate}`;
      case 'A11Y_WINDOW': return `${t} [A11Y_WIN]${c} pkg=${d.pkg} cls=${d.cls}`;
      case 'A11Y_NOTIF': return `${t} [A11Y_NTF]${c} pkg=${d.pkg} "${d.text}"`;
      case 'A11Y_CLICK': return `${t} [A11Y_CLK]${c} pkg=${d.pkg} cls=${d.cls} "${d.text}" desc="${d.desc}"`;
      case 'A11Y_CONTENT': return `${t} [A11Y_CT ]${c} pkg=${d.pkg}`;
      case 'STATE_BEFORE': return `${t} [STATE<  ]${c} ${d.capability} ${JSON.stringify(d.state).slice(0, 200)}`;
      case 'STATE_AFTER': return `${t} [STATE>  ]${c} ${d.capability} ${JSON.stringify(d.state).slice(0, 200)}`;
      case 'STATE_DELTA': return `${t} [DELTA   ]${c} ${d.capability} changed=${JSON.stringify(d.changed).slice(0, 200)} nothing=${d.nothingChanged}`;
      case 'A11Y_HEARTBEAT': return `${t} [HEART   ] alive=${d.alive} fg=${d.foregroundPackage}`;
      case 'CRASH_NATIVE': return `${t} [CRASH!! ] thread=${d.thread} ${d.error}`;
      case 'NET_DETAIL': return `${t} [NET     ]${c} ${d.method} ${d.status} ${d.durationMs}ms bytes=${d.bodyBytes} model=${d.model}`;
      case 'UI_SNAPSHOT': return `${t} [UISNAP ]${c} ${d.capability} pkg=${d.package} nodes=${d.nodeCount} click=${d.clickable}`;
      case 'BUBBLE_DIAG': return `${t} [BUBBLE  ]${c} id=${String(d.messageId).slice(-8)} role=${d.role} h=${d.height}px w=${d.width}px len=${d.textLength} idx=${d.msgIndex} style=${d.msgStyle}`;
      case 'A11Y_QS_TRACE': return `${t} [QS_TRACE]${c} step=${d.step} tile=${d.tile} ${d.found !== undefined ? 'found=' + d.found : ''} ${d.bounds || ''} ${d.error || ''}`;
      case 'GRID_TAP': return `${t} [GRID_TAP]${c} cap=${d.capability} params=${JSON.stringify(d.params).slice(0, 100)}`;
      case 'CONTEXT_TAP': return `${t} [CTX_TAP ]${c} cap=${d.capability} params=${JSON.stringify(d.params).slice(0, 100)}`;
      case 'SLASH_CMD': return `${t} [SLASH   ]${c} ${d.command} ${d.durationMs}ms result=${d.resultPreview}`;
      case 'CHAIN': return `${t} [CHAIN   ]${c} ${d.component}.${d.action} → ${d.outcome}`;
      case 'EFFECT': return `${t} [EFFECT  ]${c} ${d.component}.${d.action} ${d.success !== undefined ? (d.success ? 'OK' : 'FAIL') : ''} ${JSON.stringify(d).slice(0, 200)}`;
      case 'BUG_REPORT_WRITE': return `${t} [BUG_RPT ] ${d.success ? 'OK' : 'FAIL'} mode=${d.mode} src=${d.source} entries=${d.entryCount} sessions=${d.distinctSessionCount} target=${d.targetSessionId}`;
      default: return `${t} [${cat.padEnd(8)}]${c} ${JSON.stringify(d).slice(0, 300)}`;
    }
  }

  // ─── Session helpers ───────────────────────────────────────────────────────

  static getSessionId(): string { return UltraDevLog.sessionId; }
  static getLastFlushedSeq(): number { return UltraDevLog.lastFlushedSeq; }

  /**
   * Parse all entries in the durable session file, group by session_id, and
   * return a map from sessionId → entries[], ordered oldest-first within each session.
   */
  static async buildSessionIndexFromDurableFile(): Promise<{
    allEntries: UltraLogEntry[];
    sessionIndex: Map<string, UltraLogEntry[]>;
    sessionIds: string[];
    currentSessionId: string;
    prevSessionId: string | null;
  }> {
    const currentSessionId = UltraDevLog.sessionId;
    const empty = {
      allEntries: [] as UltraLogEntry[],
      sessionIndex: new Map<string, UltraLogEntry[]>(),
      sessionIds: [],
      currentSessionId,
      prevSessionId: null,
    };
    if (Platform.OS === 'web' || !FileSystem) return empty;
    try {
      const sessionPath = UltraDevLog.getSessionFilePath();
      if (!sessionPath) return empty;
      const info = await FileSystem.getInfoAsync(sessionPath);
      if (!info.exists) return empty;
      const content = await FileSystem.readAsStringAsync(sessionPath);
      const allEntries: UltraLogEntry[] = content
        .split('\n')
        .filter(Boolean)
        .map((line: string) => { try { return JSON.parse(line) as UltraLogEntry; } catch { return null; } })
        .filter((e: UltraLogEntry | null): e is UltraLogEntry => e !== null);

      const sessionIndex = new Map<string, UltraLogEntry[]>();
      for (const e of allEntries) {
        const sid: string = (e.data?.session_id as string) ?? (e.data?.payload as any)?.session_id ?? 'unknown';
        if (!sessionIndex.has(sid)) sessionIndex.set(sid, []);
        sessionIndex.get(sid)!.push(e);
      }
      const sessionIds = [...sessionIndex.keys()];
      // "previous" = most recent session that is NOT the current session
      const prevSessionId = sessionIds.filter(s => s !== currentSessionId).slice(-1)[0] ?? null;
      return { allEntries, sessionIndex, sessionIds, currentSessionId, prevSessionId };
    } catch {
      return empty;
    }
  }

  static generateBugReport(
    entriesOverride?: UltraLogEntry[],
    sourceLabel?: string,
    opts?: {
      targetSessionId?: string;
      mode?: 'current_session_only' | 'previous_session_only' | 'combined';
      allSessionIds?: string[];
      otherSessionPickerEvidence?: Array<{ sessionId: string; pickerCount: number }>;
    },
  ): string {
    const entries = entriesOverride ?? [...UltraDevLog.entries];
    const source = sourceLabel ?? 'memory_fallback';
    const mode = opts?.mode ?? 'current_session_only';
    const targetSessionId = opts?.targetSessionId ?? UltraDevLog.sessionId;
    const allSessionIds = opts?.allSessionIds ?? [UltraDevLog.sessionId];
    const otherSessionPickerEvidence = opts?.otherSessionPickerEvidence ?? [];
    const lines: string[] = [];
    const hr = '='.repeat(52);
    // Helper: reads original payload regardless of envelope wrapping
    const p = (e: UltraLogEntry | null | undefined) =>
      (e?.data?.payload as Record<string, unknown>) || e?.data || {};

    const seqs = entries.map(e => e.seq);
    const minSeq = seqs.length > 0 ? Math.min(...seqs) : 0;
    const maxSeq = seqs.length > 0 ? Math.max(...seqs) : 0;
    const expectedCount = maxSeq - minSeq + 1;
    const missingSeqEstimate = Math.max(0, expectedCount - entries.length);
    const coveredTimeStart = entries.length > 0 ? entries[0].ts : 'n/a';
    const coveredTimeEnd = entries.length > 0 ? entries[entries.length - 1].ts : 'n/a';
    const coveredCats = [...new Set(entries.map(e => e.cat))].sort().join(', ');

    const pickerCats = new Set(['PICKER_OPEN','PICKER_CLOSE','PICKER_ANIMATE','PICKER_SELECT','PICKER_CONTENT','PICKER_FILTER_CHANGE']);
    const settingsCats = new Set(['SETTINGS_SAVE','SETTINGS_SAVE_START','SETTINGS_SAVE_RESULT','ROUTING_ASSIGNMENT_CHANGE']);
    const allPickerEntries = entries.filter(e => pickerCats.has(e.cat));
    const allSettingsEntries = entries.filter(e => settingsCats.has(e.cat));
    const allRoutingEntries = entries.filter(e => e.cat === 'ROUTING_ASSIGNMENT_CHANGE');
    const allApiSyncEntries = entries.filter(e => ['API_STATE_SNAPSHOT','MODEL_INVENTORY_SYNC','ROUTING_ASSIGNMENT'].includes(e.cat));
    const allPermContradictions = entries.filter(e => e.cat === 'PERMISSION_CONTRADICTION');

    lines.push(hr);
    lines.push(`AGENT ULTRA -- BUG REPORT v5`);
    lines.push(`Generated:         ${new Date().toISOString()}`);
    lines.push(`Target session:    ${targetSessionId}`);
    lines.push(`Report mode:       ${mode}`);
    lines.push(`Source:            ${source}`);
    lines.push(`Durable sessions:  ${allSessionIds.length} distinct session(s) in durable file`);
    lines.push(`All session ids:   [${allSessionIds.join(', ')}]`);
    lines.push(`EntryCount:        ${entries.length}`);
    lines.push(`SeqRange:          ${minSeq}..${maxSeq} (expected=${expectedCount} missing≈${missingSeqEstimate})`);
    lines.push(`CoveredTime:       ${coveredTimeStart} → ${coveredTimeEnd}`);
    lines.push(`ReportLimits:      picker=ALL settings=ALL routing=ALL (no arbitrary caps)`);
    lines.push(hr);

    lines.push('');
    lines.push('-- COVERAGE STATEMENT --------------------------------');
    lines.push(`  Source:            ${source === 'session_file' ? 'Session file on disk (full durable log)' : 'In-memory entries (session file unavailable or unread)'}`);
    lines.push(`  Target session:    ${targetSessionId}`);
    lines.push(`  Report mode:       ${mode}`);
    lines.push(`  Entries:           ${entries.length} entries, seq ${minSeq}..${maxSeq}`);
    lines.push(`  Missing seqs:      ≈${missingSeqEstimate} (cap evictions or gaps)`);
    lines.push(`  Picker trace in selected session: ${allPickerEntries.length} entries — ALL included, uncapped`);
    if (allPickerEntries.length === 0 && otherSessionPickerEvidence.length > 0) {
      lines.push(`  NOTE: Picker trace is 0 in this session but other sessions in the durable file contain picker evidence:`);
      otherSessionPickerEvidence.forEach(ev => {
        lines.push(`    Session ${ev.sessionId}: ${ev.pickerCount} picker entries`);
      });
      lines.push(`  To see those entries, generate a Previous Session Bug Report.`);
    }
    lines.push(`  Settings trace:    ${allSettingsEntries.length} entries — ALL included, uncapped`);
    lines.push(`  Routing trace:     ${allRoutingEntries.length} entries — ALL included, uncapped`);
    lines.push(`  API/sync trace:    ${allApiSyncEntries.length} entries — ALL included (API_STATE_SNAPSHOT + MODEL_INVENTORY_SYNC + ROUTING_ASSIGNMENT)`);
    lines.push(`  Perm contradict:   ${allPermContradictions.length} entries`);
    lines.push(`  Categories:        ${coveredCats}`);

    // ── Failure classification ────────────────────────────────────────────────
    // RULE: Only true runtime failures belong in the FAILURES section.
    // Expected / normal conditions (warm_restart, camera_cancelled, etc.) go to EXPECTED CONDITIONS.

    const EXPECTED_CONDITION_EVENTS = new Set([
      'warm_restart', 'camera_cancelled', 'picker_cancelled', 'user_cancelled',
      'app_backgrounded', 'app_foregrounded',
    ]);

    const failures = entries.filter(e => {
      const d = p(e);
      // Explicitly excluded from failures: PROCESS_RESTART (any variant), camera/user cancel
      if (e.cat === 'PROCESS_RESTART') return false;
      if (typeof d.event === 'string' && EXPECTED_CONDITION_EVENTS.has(d.event)) return false;
      if (d.cancelled === true) return false;
      return (
        e.cat === 'ERROR' ||
        e.cat === 'APP_LAUNCH_FAIL' ||
        (e.cat === 'EXEC_RESULT' && d.success === false) ||
        (e.cat === 'SMS_FIRE' && d.toIsPhone === false) ||
        (e.cat === 'TASK_WATCHDOG' && d.event === 'FIRED') ||
        (e.cat === 'CORE_INSTANCE' && (d.note as string)?.startsWith('WARN')) ||
        (e.cat === 'COMPONENT_LIFECYCLE' && (d.note as string)?.startsWith('WARN')) ||
        (e.cat === 'SETTINGS_SAVE' && d.event === 'write_result' && d.success === false) ||
        (e.cat === 'SETTINGS_SAVE_RESULT' && d.success === false) ||
        (e.cat === 'ROUTING_ASSIGNMENT_CHANGE' && d.success === false) ||
        (e.cat === 'PICKER_CONTENT' && (d.note as string)?.startsWith('WARN')) ||
        (e.cat === 'PICKER_FILTER_CHANGE' && (d.note as string)?.startsWith('WARN')) ||
        (e.cat === 'UI_MESSAGE_RENDERED' && (d.tallWarning || (d.note as string)?.includes('listHeightPx=0'))) ||
        (e.cat === 'VAULT_WRITE' && d.success === false) ||
        (e.cat === 'FOCUS_EFFECT_DEPS' && (d.changedDeps as string[])?.includes('currentMode')) ||
        (e.cat === 'EFFECT' && d.success === false) ||
        (e.cat === 'PERMISSION_CONTRADICTION') ||
        (e.cat === 'API_STATE_SNAPSHOT' && (d.note as string)?.startsWith('WARN')) ||
        (e.cat === 'MODEL_INVENTORY_SYNC' && d.success === false)
      );
    });

    const expectedConditions = entries.filter(e => {
      const d = p(e);
      return (
        e.cat === 'PROCESS_RESTART' ||
        (typeof d.event === 'string' && EXPECTED_CONDITION_EVENTS.has(d.event)) ||
        (d.cancelled === true && e.cat !== 'ERROR')
      );
    });

    lines.push(''); lines.push(`-- FAILURES (${failures.length}) --- only true runtime failures ---`);
    failures.length === 0 ? lines.push('  None.') : failures.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e)));

    if (expectedConditions.length > 0) {
      lines.push(''); lines.push(`-- EXPECTED CONDITIONS (${expectedConditions.length}) --- not failures ---`);
      lines.push('  (warm restart, user cancel, background/foreground — these are informational)');
      expectedConditions.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e)));
    }

    const lastUser = [...entries].reverse().find(e => e.cat === 'USER_MSG');
    lines.push(''); lines.push('-- LAST USER INPUT --------------------------------');
    lines.push(lastUser ? `  "${p(lastUser).content}"` : '  (none)');

    const lastExec = [...entries].reverse().find(e => p(e).taskId);
    if (lastExec) {
      const taskId = p(lastExec).taskId as string;
      const te = entries.filter(e => p(e).taskId === taskId);
      lines.push(''); lines.push(`-- LAST TASK CHAIN (${taskId}) ----------------------`);
      te.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e)));
      const PHASES = ['INGEST','ROUTE','PLAN','VERIFY','APPROVE','EXECUTE','VERIFY_RESULT','RESPOND'];
      // Reconstruct phase evidence from EXECUTE_PHASE and AGENT_STEP categories
      const reachedFromPhase = te.filter(e => e.cat === 'EXECUTE_PHASE').map(e => p(e).phase as string);
      const reachedFromStep = te.filter(e => e.cat === 'AGENT_STEP' && p(e).phase).map(e => p(e).phase as string);
      const allReachedPhases = [...new Set([...reachedFromPhase, ...reachedFromStep])];
      const last = reachedFromPhase[reachedFromPhase.length - 1] || reachedFromStep[reachedFromStep.length - 1];
      // Classify terminal states — these are NOT errors
      const isCancelled = te.some(e => p(e).cancelled === true);
      const requiresDisambig = te.some(e => p(e).requiresDisambiguation === true || (e.cat === 'AGENT_STEP' && (p(e).detail as string)?.includes('disambiguation')));
      const requiresConfirm = te.some(e => p(e).requiresConfirmation === true || p(e).requiresApproval === true);
      const terminalEvidence = te.some(e =>
        ['ERROR', 'EXEC_RESULT', 'AI_RESPONSE'].includes(e.cat) ||
        isCancelled || requiresDisambig || requiresConfirm ||
        (e.cat === 'EFFECT' && (p(e).success === false || p(e).success === true))
      );
      if (isCancelled) {
        lines.push(''); lines.push(`  TERMINAL STATE: cancelled — user cancelled the operation (not a defect).`);
      } else if (requiresDisambig) {
        lines.push(''); lines.push(`  TERMINAL STATE: requires_disambiguation — agent asked a clarifying question.`);
      } else if (requiresConfirm) {
        lines.push(''); lines.push(`  TERMINAL STATE: requires_confirmation — agent requested approval before proceeding.`);
      } else if (last && !terminalEvidence && last !== 'RESPOND' && last !== 'VERIFY_RESULT') {
        const lastIndex = PHASES.indexOf(last);
        const hasLaterPhase = allReachedPhases.some(ph => PHASES.indexOf(ph) > lastIndex);
        const next = PHASES[lastIndex + 1];
        if (next && !hasLaterPhase) {
          lines.push('');
          lines.push(`  PHASE GAP: last confirmed phase=${last}; no confirmed evidence of ${next}. No terminal result logged.`);
          lines.push(`  NOTE: this indicates an actual gap, not an invented crash. Evidence from EXECUTE_PHASE + AGENT_STEP: [${allReachedPhases.join(', ')}]`);
        }
      }
      const enter = te.filter(e => e.cat === 'EXECUTOR_BRANCH' && p(e).branch === 'ENTER').length;
      const exit = te.filter(e => e.cat === 'EXECUTOR_BRANCH' && p(e).branch === 'EXIT').length;
      if (enter > exit) { lines.push(''); lines.push(`  EXECUTOR MISFIRE: ENTER=${enter} EXIT=${exit}.`); }
    }

    const compEv = entries.filter(e => e.cat === 'COMPONENT_LIFECYCLE').slice(-8);
    lines.push(''); lines.push(`-- COMPONENT LIFECYCLE (last ${compEv.length}) ---------------`);
    compEv.length === 0 ? lines.push('  None -- componentMount() not instrumented.') : compEv.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e)));

    const restarts = entries.filter(e => e.cat === 'PROCESS_RESTART');
    if (restarts.length > 0) { lines.push(''); lines.push('-- PROCESS RESTART -----------------------------------'); restarts.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e))); }

    lines.push(''); lines.push(`-- SETTINGS SAVE TRACE (ALL ${allSettingsEntries.length} entries, uncapped) ---`);
    allSettingsEntries.length === 0 ? lines.push('  None -- settings save not instrumented.') : allSettingsEntries.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e)));

    lines.push(''); lines.push(`-- ROUTING ASSIGNMENT TRACE (ALL ${allRoutingEntries.length} entries, uncapped) ---`);
    allRoutingEntries.length === 0 ? lines.push('  None -- no routing changes this session.') : allRoutingEntries.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e)));

    lines.push(''); lines.push(`-- API / MODEL / ROUTING STATE (${allApiSyncEntries.length}) -----------`);
    allApiSyncEntries.length === 0
      ? lines.push('  None -- no API/model/routing snapshots this session.')
      : allApiSyncEntries.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e)));

    if (allPermContradictions.length > 0) {
      lines.push(''); lines.push(`-- PERMISSION CONTRADICTIONS (${allPermContradictions.length}) *** --------`);
      allPermContradictions.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e)));
    }

    const proc = entries.filter(e => e.cat === 'UI_PROCESSING').slice(-10);
    lines.push(''); lines.push('-- isProcessing TRANSITIONS (last 10) ---------------');
    proc.length === 0 ? lines.push('  None.') : proc.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e)));
    if (p(proc[proc.length-1] as UltraLogEntry)?.isProcessing === true) lines.push('  *** isProcessing=true at end -- UI locked.');

    const cores = entries.filter(e => e.cat === 'CORE_INSTANCE').slice(-10);
    lines.push(''); lines.push(`-- CORE INSTANCE TRACE (last ${cores.length}) ------------------`);
    cores.length === 0 ? lines.push('  None.') : cores.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e)));
    const creates = entries.filter(e => e.cat === 'CORE_INSTANCE' && p(e).event === 'created').length;
    if (creates > 1) lines.push(`  *** ${creates} cores created. Move AgentCore to React Context.`);

    const appSt = entries.filter(e => e.cat === 'APP_STATE_CHANGE').slice(-6);
    if (appSt.length > 0) { lines.push(''); lines.push(`-- APP STATE (last ${appSt.length}) -------------------------`); appSt.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e))); }

    const focusDeps = entries.filter(e => e.cat === 'FOCUS_EFFECT_DEPS').slice(-6);
    if (focusDeps.length > 0) { lines.push(''); lines.push(`-- FOCUS EFFECT DEPS (last ${focusDeps.length}) ----------------`); focusDeps.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e))); }

    const errs = entries.filter(e => e.cat === 'ERROR').slice(-5);
    if (errs.length > 0) { lines.push(''); lines.push('-- ERRORS --------------------------------------------'); errs.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e))); }

    const chainFails = entries.filter(e => e.cat === 'CHAIN' && ((p(e).outcome as string)?.startsWith('FAIL') || (p(e).outcome as string)?.startsWith('EMPTY')));
    if (chainFails.length > 0) {
      lines.push(''); lines.push(`-- CHAIN FAILURES (${chainFails.length}) -----------------------`);
      chainFails.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e)));
    }

    const vf = entries.filter(e => e.cat === 'VAULT_WRITE' && p(e).success === false).slice(-5);
    if (vf.length > 0) { lines.push(''); lines.push(`-- VAULT WRITE FAILURES (${vf.length}) --------------------`); vf.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e))); }

    const launch = entries.filter(e => ['APP_LAUNCH_BEGIN','APP_LAUNCH_DEVICE','APP_LAUNCH_MATCH','APP_LAUNCH_AI','APP_LAUNCH_FIRE','APP_LAUNCH_RESUME','APP_LAUNCH_FAIL'].includes(e.cat)).slice(-12);
    if (launch.length > 0) { lines.push(''); lines.push(`-- APP LAUNCH TRACE (last ${launch.length}) ----------------`); launch.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e))); }

    lines.push(''); lines.push(`-- PICKER TRACE (ALL ${allPickerEntries.length} entries, uncapped) ---`);
    allPickerEntries.length === 0 ? lines.push('  None.') : allPickerEntries.forEach(e => lines.push('  ' + UltraDevLog.formatEntry(e)));

    const shots = entries
      .filter(e => e?.cat === 'DEBUG_SCREENSHOT' || e?.cat === 'DEBUG_SCREENSHOT_FAIL')
      .slice(-10);
    if (shots.length > 0) {
      lines.push('');
      lines.push(`-- DEBUG SCREENSHOTS (${shots.length}) -----------------------`);
      shots.forEach(e => {
        const d = p(e);
        const tag = e?.cat === 'DEBUG_SCREENSHOT_FAIL' ? '[FAIL]' : '[OK]';
        lines.push(`  ${tag} reason=${d.reason ?? ''} ts=${d.timestamp ?? ''} path=${d.filepath ?? d.error ?? ''}`);
      });
      const screenshotDir = p(entries.find(e => e?.cat === 'DEBUG_SCREENSHOT')).filepath;
      if (screenshotDir) {
        const dir = String(screenshotDir).split('/').slice(0, -1).join('/');
        lines.push(`  Screenshots dir: ${dir}/`);
      }
    }

    lines.push(''); lines.push(hr); lines.push(`END -- paste to Replit`); lines.push(hr);
    return lines.join('\n');
  }

  // ─── File I/O ──────────────────────────────────────────────────────────────

  private static getLogDir(): string {
    if (!FileSystem?.documentDirectory) return '';
    return `${FileSystem.documentDirectory}ultra_dev_logs/`;
  }

  private static getSessionFilePath(): string {
    const dir = UltraDevLog.getLogDir();
    return dir ? `${dir}session_${UltraDevLog.sessionId}.jsonl` : '';
  }

  static async flushNow(): Promise<void> {
    await UltraDevLog.doFlush();
  }

  private static scheduleFlush(): void {
    if (Platform.OS === 'web') return;
    if (UltraDevLog.pendingFlush) return;
    UltraDevLog.pendingFlush = true;
    setTimeout(() => { UltraDevLog.pendingFlush = false; UltraDevLog.doFlush(); }, 2000);
  }

  static async forceFlush(): Promise<void> { await UltraDevLog.doFlush(); }

  static writeBugReportFile(): void {
    setTimeout(() => UltraDevLog.doWriteBugReport('current'), 0);
  }

  static async generateBugReportFile(): Promise<boolean> {
    return UltraDevLog.doWriteBugReport('current');
  }

  /** Generate a bug report covering the PREVIOUS session in the durable file.
   *  Returns ok=false and prevSessionId=null if no prior session exists. */
  static async generateBugReportFilePrevSession(): Promise<{ ok: boolean; prevSessionId: string | null }> {
    const idx = await UltraDevLog.buildSessionIndexFromDurableFile();
    if (!idx.prevSessionId) return { ok: false, prevSessionId: null };
    const ok = await UltraDevLog.doWriteBugReport('previous', idx.prevSessionId);
    return { ok, prevSessionId: idx.prevSessionId };
  }

  private static async doWriteBugReport(
    targetMode: 'current' | 'previous' | 'combined' = 'current',
    explicitTargetSessionId?: string,
  ): Promise<boolean> {
    if (Platform.OS === 'web' || !FileSystem) return false;
    try {
      // 1. Flush first so session file is up to date
      await UltraDevLog.doFlush();

      // 2. Read durable session file and build session index
      const { sessionIndex, sessionIds, currentSessionId, prevSessionId } =
        await UltraDevLog.buildSessionIndexFromDurableFile();

      const mode: 'current_session_only' | 'previous_session_only' | 'combined' =
        targetMode === 'previous' ? 'previous_session_only' :
        targetMode === 'combined' ? 'combined' : 'current_session_only';

      const targetSessionId =
        explicitTargetSessionId ??
        (targetMode === 'previous' ? prevSessionId : currentSessionId) ??
        currentSessionId;

      // 3. Select entries for the chosen session
      let reportEntries: UltraLogEntry[] | undefined;
      let sourceLabel = 'memory_fallback';

      if (sessionIndex.size > 0) {
        if (mode === 'combined') {
          reportEntries = [...sessionIndex.values()].flat().sort((a, b) => a.seq - b.seq);
        } else {
          reportEntries = sessionIndex.get(targetSessionId) ??
            (mode === 'current_session_only' ? [...UltraDevLog.entries] : []);
        }
        sourceLabel = 'session_file';
      } else {
        // Fallback to in-memory entries for current session
        reportEntries = mode === 'current_session_only' ? [...UltraDevLog.entries] : [];
        sourceLabel = 'memory_fallback';
      }

      // 4. Build other-session picker evidence disclosure
      const pickerCats = new Set(['PICKER_OPEN','PICKER_CLOSE','PICKER_ANIMATE','PICKER_SELECT','PICKER_CONTENT','PICKER_FILTER_CHANGE']);
      const otherSessionPickerEvidence: Array<{ sessionId: string; pickerCount: number }> = [];
      for (const [sid, entries] of sessionIndex.entries()) {
        if (sid === targetSessionId) continue;
        const pc = entries.filter(e => pickerCats.has(e.cat)).length;
        if (pc > 0) otherSessionPickerEvidence.push({ sessionId: sid, pickerCount: pc });
      }

      // 5. Generate the report
      const report = UltraDevLog.generateBugReport(reportEntries, sourceLabel, {
        targetSessionId,
        mode,
        allSessionIds: sessionIds.length > 0 ? sessionIds : [currentSessionId],
        otherSessionPickerEvidence,
      });

      // 6. Write to file
      const filename = targetMode === 'previous'
        ? `bug-report-prev-${targetSessionId.slice(-8)}.txt`
        : 'bug-report.txt';
      const ok = await LogFolder.writeLog(filename, report);

      // 7. Emit BUG_REPORT_WRITE durable event with all required fields
      UltraDevLog.push('BUG_REPORT_WRITE', {
        event: 'bug_report_write',
        success: ok,
        targetSessionId,
        mode,
        source: sourceLabel,
        entryCount: reportEntries?.length ?? 0,
        distinctSessionCount: sessionIds.length > 0 ? sessionIds.length : 1,
        allSessionIds: sessionIds.length > 0 ? sessionIds : [currentSessionId],
        reportChars: report.length,
        filename,
        note: ok ? `${filename} written ok` : `WARN: ${filename} write failed`,
      });
      return ok;
    } catch (err: any) {
      UltraDevLog.push('BUG_REPORT_WRITE', {
        event: 'bug_report_write',
        success: false,
        targetSessionId: explicitTargetSessionId ?? UltraDevLog.sessionId,
        mode: targetMode === 'previous' ? 'previous_session_only' : 'current_session_only',
        source: 'error',
        entryCount: 0,
        distinctSessionCount: 0,
        allSessionIds: [],
        note: `WARN: bug report write threw: ${err?.message ?? 'unknown'}`,
      });
      return false;
    }
  }

  private static async doFlush(): Promise<void> {
    if (Platform.OS === 'web' || !FileSystem) return;
    if (UltraDevLog.writing) return;
    UltraDevLog.writing = true;
    try {
      const dir = UltraDevLog.getLogDir();
      if (!dir) return;
      const info = await FileSystem.getInfoAsync(dir);
      if (!info.exists) await FileSystem.makeDirectoryAsync(dir, { intermediates: true });
      const newEntries = UltraDevLog.entries.filter(e => e.seq > UltraDevLog.lastFlushedSeq);
      if (newEntries.length === 0) return;
      const appendContent = newEntries.map(e => JSON.stringify(e)).join('\n') + '\n';
      const maxSeqFlushed = newEntries[newEntries.length - 1].seq;

      await LogFolder.appendLog(`ultra-devlog.jsonl`, appendContent);

      const sessionPath = UltraDevLog.getSessionFilePath();
      try {
        const sessionInfo = await FileSystem.getInfoAsync(sessionPath);
        if (sessionInfo.exists) {
          const existing = await FileSystem.readAsStringAsync(sessionPath);
          await FileSystem.writeAsStringAsync(sessionPath, existing + appendContent);
        } else {
          await FileSystem.writeAsStringAsync(sessionPath, appendContent);
        }
      } catch {
        const fallbackContent = UltraDevLog.entries.map(e => JSON.stringify(e)).join('\n') + '\n';
        await FileSystem.writeAsStringAsync(UltraDevLog.getSessionFilePath(), fallbackContent);
      }

      UltraDevLog.lastFlushedSeq = maxSeqFlushed;
    } catch {} finally { UltraDevLog.writing = false; }
  }

  static async exportAll(): Promise<string> {
    return UltraDevLog.exportSessionNow();
  }

  static async exportSessionNow(filename = 'raw-export.jsonl'): Promise<string> {
    await UltraDevLog.doFlush();
    const allEntries = UltraDevLog.entries;
    const seqs = allEntries.map(e => e.seq);
    const minSeq = seqs.length > 0 ? Math.min(...seqs) : 0;
    const maxSeq = seqs.length > 0 ? Math.max(...seqs) : 0;
    const expectedCount = maxSeq - minSeq + 1;
    const missingSeqEstimate = Math.max(0, expectedCount - allEntries.length);
    const meta = {
      exportKind: 'full_session_export',
      sessionId: UltraDevLog.sessionId,
      entryCount: allEntries.length,
      minSeq,
      maxSeq,
      missingSeqEstimate,
      generatedAt: new Date().toISOString(),
    };
    const metaLine = JSON.stringify({ _meta: meta }) + '\n';

    if (Platform.OS === 'web' || !FileSystem) {
      return metaLine + allEntries.map(e => JSON.stringify(e)).join('\n');
    }
    try {
      const fp = UltraDevLog.getSessionFilePath();
      const info = await FileSystem.getInfoAsync(fp);
      const persisted = info.exists ? await FileSystem.readAsStringAsync(fp) : '';
      const pending = allEntries.filter(e => e.seq > UltraDevLog.lastFlushedSeq).map(e => JSON.stringify(e)).join('\n');
      const separator = persisted && pending ? '\n' : '';
      const content = metaLine + (`${persisted}${separator}${pending}${pending ? '\n' : ''}` || allEntries.map(e => JSON.stringify(e)).join('\n') + '\n');
      await LogFolder.writeLog(filename, content);
      return content;
    } catch {}
    return metaLine + allEntries.map(e => JSON.stringify(e)).join('\n');
  }

  static clear(): void {
    UltraDevLog.entries = [];
    UltraDevLog.seq = 0;
    UltraDevLog.activeCoreId = null;
    UltraDevLog.lastUiStateHash = '';
    UltraDevLog.lastConvListCount = -1;
    UltraDevLog.lastConvLoadedCounts.clear();
    UltraDevLog.lastVaultReadTs.clear();
    UltraDevLog.processingStartedAt = 0;
    UltraDevLog.lastRenderedHeights.clear();
    UltraDevLog.bubbleDiagState.clear();
    UltraDevLog.lastHeartbeatAlive = null;
    UltraDevLog.lastHeartbeatLoggedAt = 0;
    for (const e of UltraDevLog.watchdogs.values()) clearTimeout(e.timeoutHandle);
    UltraDevLog.watchdogs.clear();
  }

  static scheduleStartupSnapshot(): void {
    if (Platform.OS === 'web' || !FileSystem) return;
    setTimeout(async () => {
      try {
        await UltraDevLog.exportSessionNow('startup-snapshot.jsonl');
      } catch {}
    }, 5000);
  }

  static scheduleStartupRawExport(): void {
    UltraDevLog.scheduleStartupSnapshot();
  }

  static async cleanOldLogs(maxAgeDays = 7): Promise<number> {
    if (Platform.OS === 'web' || !FileSystem) return 0;
    try {
      const dir = UltraDevLog.getLogDir();
      if (!dir) return 0;
      const info = await FileSystem.getInfoAsync(dir);
      if (!info.exists) return 0;
      const files = await FileSystem.readDirectoryAsync(dir);
      const cutoff = Date.now() - maxAgeDays * 86400000;
      let n = 0;
      for (const f of files) {
        const m = f.match(/session_([a-z0-9]+)\.jsonl/);
        if (m && parseInt(m[1], 36) < cutoff) { await FileSystem.deleteAsync(`${dir}${f}`); n++; }
      }
      return n;
    } catch { return 0; }
  }
}

