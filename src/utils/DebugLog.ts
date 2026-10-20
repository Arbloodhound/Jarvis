import { Platform } from 'react-native';
import * as ExpoFileSystem from 'expo-file-system/legacy';
import { LogFolder } from '@/src/services/LogFolder';
import { UltraDevLog } from './UltraDevLog';

const FileSystem: any = Platform.OS !== 'web' ? ExpoFileSystem : null;

export interface DebugEntry {
  ts: string;
  t: number;
  cat: string;
  data: Record<string, unknown>;
}

export class DebugLog {
  private static entries: DebugEntry[] = [];
  private static readonly MAX_MEMORY = 15000;
  private static writing = false;
  private static pendingFlush = false;
  private static sessionId = Date.now().toString(36);

  private static push(cat: string, data: Record<string, unknown>) {
    const entry: DebugEntry = {
      ts: new Date().toISOString(),
      t: Date.now(),
      cat,
      data,
    };
    DebugLog.entries.push(entry);
    if (DebugLog.entries.length > DebugLog.MAX_MEMORY) {
      DebugLog.entries = DebugLog.entries.slice(-DebugLog.MAX_MEMORY);
    }
    DebugLog.scheduleFlush();
  }

  // ===========================================================
  // VAULT -- every read, write, delete, cache hit/miss
  // ===========================================================
  static vaultGet(key: string, value: string | null, source: 'cache' | 'store') {
    DebugLog.push('VAULT_GET', { key, hasValue: value !== null, valueLen: value?.length ?? 0, source });
  }
  static vaultSet(key: string, valueLen: number) {
    DebugLog.push('VAULT_SET', { key, valueLen });
  }
  static vaultDelete(key: string) {
    DebugLog.push('VAULT_DEL', { key });
  }
  static vaultError(op: string, key: string, error: string) {
    DebugLog.push('VAULT_ERR', { op, key, error });
  }

  // ===========================================================
  // MODEL ROUTER -- discovery, selection, switching, API calls
  // ===========================================================
  static modelDiscoveryStart(baseUrl: string) {
    DebugLog.push('MODEL_DISC_START', { baseUrl });
  }
  static modelDiscoveryResult(count: number, modelIds: string[]) {
    DebugLog.push('MODEL_DISC_DONE', { count, first10: modelIds.slice(0, 10) });
  }
  static modelDiscoveryError(error: string) {
    DebugLog.push('MODEL_DISC_ERR', { error });
  }
  static modelSetDefault(modelId: string, previousModel: string, source: string) {
    DebugLog.push('MODEL_SET_DEFAULT', { modelId, previousModel, source });
  }
  static modelSetDefaultError(modelId: string, error: string) {
    DebugLog.push('MODEL_SET_DEFAULT_ERR', { modelId, error });
  }
  static modelGetDefault(modelId: string) {
    DebugLog.push('MODEL_GET_DEFAULT', { modelId });
  }
  static modelApiRequest(model: string, taskId: string, promptTokens: number, maxTokens: number) {
    DebugLog.push('MODEL_API_REQ', { model, taskId, promptTokens, maxTokens });
  }
  static modelApiResponse(model: string, taskId: string, inputTokens: number, outputTokens: number, cost: number, durationMs: number) {
    DebugLog.push('MODEL_API_RESP', { model, taskId, inputTokens, outputTokens, cost, durationMs });
  }
  static modelApiError(model: string, taskId: string, error: string, durationMs: number) {
    DebugLog.push('MODEL_API_ERR', { model, taskId, error, durationMs });
  }
  static modelAbort(model: string) {
    DebugLog.push('MODEL_ABORT', { model });
  }
  static modelImageRequest(model: string, promptLen: number) {
    DebugLog.push('MODEL_IMG_REQ', { model, promptLen });
  }
  static modelImageResponse(model: string, imageCount: number, cost: number, durationMs: number) {
    DebugLog.push('MODEL_IMG_RESP', { model, imageCount, cost, durationMs });
  }
  static modelImageError(model: string, error: string) {
    DebugLog.push('MODEL_IMG_ERR', { model, error });
  }

  // ===========================================================
  // AGENT CORE -- init, mode detection, 9-step loop, each phase
  // ===========================================================
  static agentInitStart() {
    DebugLog.push('AGENT_INIT_START', {});
  }
  static agentInitSubsystem(name: string, success: boolean, durationMs: number, error?: string) {
    DebugLog.push('AGENT_INIT_SUB', { name, success, durationMs, error });
  }
  static agentInitComplete(durationMs: number) {
    DebugLog.push('AGENT_INIT_DONE', { durationMs });
  }
  static agentExecuteStart(taskId: string, conversationId: string, inputLen: number, replay: boolean) {
    DebugLog.push('AGENT_EXEC_START', { taskId, conversationId, inputLen, replay });
  }
  static agentStep(taskId: string, phase: string, detail: string, success: boolean) {
    DebugLog.push('AGENT_STEP', { taskId, phase, detail, success });
  }
  static modeDetected(taskId: string, mode: string, userInput: string) {
    DebugLog.push('MODE', { taskId, mode, inputPreview: userInput.slice(0, 500) });
  }
  static planResult(taskId: string, capability: string | null, params: Record<string, unknown> | null, deterministic: boolean) {
    DebugLog.push('PLAN', { taskId, capability, params, deterministic });
  }
  static safetyCheck(taskId: string, risk: string, allowed: boolean, reasons: string[]) {
    DebugLog.push('SAFETY', { taskId, risk, allowed, reasons });
  }
  static verification(taskId: string, verified: boolean, issues: string[]) {
    DebugLog.push('VERIFY', { taskId, verified, issues });
  }
  static execResult(taskId: string, capability: string, success: boolean, resultPreview: string) {
    DebugLog.push('EXEC_RESULT', { taskId, capability, success, resultPreview: resultPreview.slice(0, 1000) });
  }

  // ===========================================================
  // COST TRACKER -- recording, limits, budget
  // ===========================================================
  static costRecord(model: string, cost: number, taskId: string) {
    DebugLog.push('COST', { model, cost, taskId });
  }
  static costLimitCheck(type: 'daily' | 'task', limit: number, spent: number, allowed: boolean) {
    DebugLog.push('COST_LIMIT', { type, limit, spent, allowed });
  }

  // ===========================================================
  // CONVERSATIONS -- create, load, save, delete, messages
  // ===========================================================
  static conversationCreated(conversationId: string, title: string) {
    DebugLog.push('CONV_NEW', { conversationId, title });
  }
  static conversationLoaded(conversationId: string, messageCount: number) {
    DebugLog.push('CONV_LOAD', { conversationId, messageCount });
  }
  static conversationSaved(conversationId: string, messageCount: number) {
    DebugLog.push('CONV_SAVE', { conversationId, messageCount });
  }
  static conversationDeleted(conversationId: string) {
    DebugLog.push('CONV_DEL', { conversationId });
  }
  static conversationMessage(conversationId: string, role: string, contentLen: number, source?: string) {
    DebugLog.push('CONV_MSG', { conversationId, role, contentLen, source });
  }
  static conversationList(count: number) {
    DebugLog.push('CONV_LIST', { count });
  }
  static conversationError(op: string, conversationId: string, error: string) {
    DebugLog.push('CONV_ERR', { op, conversationId, error });
  }

  // ===========================================================
  // USER INPUT & AI RESPONSE -- the actual chat flow
  // ===========================================================
  static userMessage(conversationId: string, content: string) {
    DebugLog.push('USER_MSG', { conversationId, content });
  }
  static aiResponse(conversationId: string, model: string, content: string, cost: number | undefined, tokens?: { input?: number; output?: number }) {
    DebugLog.push('AI_RESPONSE', { conversationId, model, content, cost, tokens });
  }

  // ===========================================================
  // API CALL -- raw HTTP-level logging
  // ===========================================================
  static apiCall(taskId: string, model: string, promptTokens: number, completionTokens: number, cost: number, durationMs: number) {
    DebugLog.push('API_CALL', { taskId, model, promptTokens, completionTokens, cost, durationMs });
  }

  // ===========================================================
  // UI STATE -- defaults, mode switches, picker, pill, settings
  // ===========================================================
  static uiInit(phase: string, detail: string) {
    DebugLog.push('UI_INIT', { phase, detail });
  }
  static uiDefaultsLoaded(source: string, defaults: Record<string, string>) {
    DebugLog.push('UI_DEFAULTS_LOADED', { source, defaults });
  }
  static uiDefaultsSaved(defaults: Record<string, string>) {
    DebugLog.push('UI_DEFAULTS_SAVED', { defaults });
  }
  static uiModeSwitch(from: string, to: string, source: string) {
    DebugLog.push('UI_MODE_SWITCH', { from, to, source });
  }
  static uiModelApply(modelId: string, mode: string, source: string, success: boolean, error?: string) {
    DebugLog.push('UI_MODEL_APPLY', { modelId, mode, source, success, error });
  }
  static uiPillUpdate(modelId: string, displayName: string) {
    DebugLog.push('UI_PILL', { modelId, displayName });
  }
  static uiPickerOpen(initialFilter: string, currentMode: string, activeModelId: string) {
    DebugLog.push('UI_PICKER_OPEN', { initialFilter, currentMode, activeModelId });
  }
  static uiPickerSelect(modelId: string, previousModelId: string) {
    DebugLog.push('UI_PICKER_SELECT', { modelId, previousModelId });
  }
  static uiPickerClose() {
    DebugLog.push('UI_PICKER_CLOSE', {});
  }
  static uiPlusMenuOpen() {
    DebugLog.push('UI_PLUS_OPEN', {});
  }
  static uiPlusMenuSelect(type: string, hasDefault: boolean, defaultModelId: string | null) {
    DebugLog.push('UI_PLUS_SELECT', { type, hasDefault, defaultModelId });
  }
  static uiSettingsNav(direction: 'enter' | 'leave') {
    DebugLog.push('UI_SETTINGS', { direction });
  }
  static uiFocusEffect(trigger: string, currentMode: string, savedDefaultsKeys: string[], activeModelId: string) {
    DebugLog.push('UI_FOCUS', { trigger, currentMode, savedDefaultsKeys, activeModelId });
  }
  static uiSendMessage(inputLen: number, mode: string, modelId: string, isProcessing: boolean) {
    DebugLog.push('UI_SEND', { inputLen, mode, modelId, isProcessing });
  }
  static uiStopRequest(hadActiveController: boolean) {
    DebugLog.push('UI_STOP', { hadActiveController });
  }
  static uiConvSwitch(fromId: string, toId: string) {
    DebugLog.push('UI_CONV_SWITCH', { fromId, toId });
  }
  static uiError(component: string, error: string) {
    DebugLog.push('UI_ERROR', { component, error });
  }

  // ===========================================================
  // SETTINGS PAGE -- API config, defaults config, cost limits
  // ===========================================================
  static settingsApiSave(apiId: string, baseUrl: string) {
    DebugLog.push('SETTINGS_API_SAVE', { apiId, baseUrl });
  }
  static settingsApiDelete(apiId: string) {
    DebugLog.push('SETTINGS_API_DEL', { apiId });
  }
  static settingsDefaultPick(role: string, modelId: string, modelName: string) {
    DebugLog.push('SETTINGS_DEFAULT_PICK', { role, modelId, modelName });
  }
  static settingsDefaultsSave(defaults: Record<string, string>) {
    DebugLog.push('SETTINGS_DEFAULTS_SAVE', { defaults });
  }
  static settingsCostLimitSave(daily: string, task: string) {
    DebugLog.push('SETTINGS_COST_SAVE', { daily, task });
  }

  // ===========================================================
  // BUILD SYSTEM -- app build lifecycle
  // ===========================================================
  static buildStart(taskId: string, description: string) {
    DebugLog.push('BUILD_START', { taskId, descriptionLen: description.length });
  }
  static buildPhase(taskId: string, phase: string, message: string) {
    DebugLog.push('BUILD_PHASE', { taskId, phase, message });
  }
  static buildComplete(taskId: string, success: boolean, durationMs: number, error?: string) {
    DebugLog.push('BUILD_DONE', { taskId, success, durationMs, error });
  }

  // ===========================================================
  // GENOME -- self-improvement, mutation, replication
  // ===========================================================
  static genomeStart(type: 'evolve' | 'replicate', taskId: string) {
    DebugLog.push('GENOME_START', { type, taskId });
  }
  static genomePhase(taskId: string, phase: string, message: string) {
    DebugLog.push('GENOME_PHASE', { taskId, phase, message });
  }
  static genomeComplete(taskId: string, success: boolean, generation: number, fitness: number | null) {
    DebugLog.push('GENOME_DONE', { taskId, success, generation, fitness });
  }
  static genomeError(taskId: string, error: string) {
    DebugLog.push('GENOME_ERR', { taskId, error });
  }

  // ===========================================================
  // SAFETY -- checks, blocks, permissions
  // ===========================================================
  static permissionCheck(capability: string, granted: boolean) {
    DebugLog.push('PERM_CHECK', { capability, granted });
  }
  static permissionRequest(capability: string, result: string) {
    DebugLog.push('PERM_REQUEST', { capability, result });
  }

  // ===========================================================
  // STATE SNAPSHOTS -- full state capture at critical moments
  // ===========================================================
  static snapshot(label: string, state: Record<string, unknown>) {
    DebugLog.push('STATE', { label, ...state });
  }

  static uiState(label: string, state: {
    currentMode?: string;
    activeModelId?: string;
    isProcessing?: boolean;
    conversationId?: string | null;
    messageCount?: number;
    savedDefaults?: Record<string, string>;
    modelsLoaded?: number;
    hasApiKey?: boolean;
    status?: string;
    buildPhase?: string | null;
    genomePhase?: string | null;
    pendingReplay?: boolean;
    pickerVisible?: boolean;
    plusMenuVisible?: boolean;
    convListVisible?: boolean;
  }) {
    DebugLog.push('UI_STATE', { label, ...state });
  }

  static settingsState(label: string, state: {
    tab?: string;
    defaults?: Record<string, string>;
    apiCount?: number;
    availableModelsCount?: number;
    defaultsExpanded?: boolean;
    editingApi?: boolean;
    isNewApi?: boolean;
    dailyLimit?: string;
    taskLimit?: string;
  }) {
    DebugLog.push('SETTINGS_STATE', { label, ...state });
  }

  static modelState(label: string, state: {
    discoveredCount?: number;
    defaultModel?: string;
    hasApiKey?: boolean;
    baseUrl?: string;
    modelIds?: string[];
  }) {
    DebugLog.push('MODEL_STATE', { label, ...state });
  }

  // ===========================================================
  // GENERAL -- system events, errors, model switches (legacy compat)
  // ===========================================================
  static systemEvent(context: string, message: string, meta?: Record<string, unknown>) {
    DebugLog.push('SYSTEM', { context, message, ...meta });
  }
  static error(context: string, message: string, stack?: string) {
    DebugLog.push('ERROR', { context, message, stack });
  }
  static modelSwitch(taskId: string, from: string, to: string, reason: string) {
    DebugLog.push('MODEL_SWITCH', { taskId, from, to, reason });
  }

  // ===========================================================
  // FILE SYSTEM -- flush, export, read, clean
  // ===========================================================
  private static getDir(): string {
    if (!FileSystem || !FileSystem.documentDirectory) return '';
    return `${FileSystem.documentDirectory}debug_logs/`;
  }

  private static getFilePath(): string {
    const dir = DebugLog.getDir();
    if (!dir) return '';
    return `${dir}debug_${DebugLog.sessionId}.jsonl`;
  }

  private static scheduleFlush() {
    if (Platform.OS === 'web') return;
    if (DebugLog.pendingFlush) return;
    DebugLog.pendingFlush = true;
    setTimeout(() => {
      DebugLog.pendingFlush = false;
      DebugLog.flushToFile();
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
      await LogFolder.writeLog(`debug-log.jsonl`, lines);
    } catch (err) {
      console.error('[DebugLog] flush failed:', err);
    } finally {
      DebugLog.writing = false;
    }
  }

  static async forceFlush(): Promise<void> {
    await DebugLog.flushToFile();
  }

  static async exportAll(): Promise<string> {
    const ultraEntries = UltraDevLog.getEntries().map(e => JSON.stringify({ _src: 'ultra', ...e })).join('\n');

    if (Platform.OS === 'web') {
      const base = DebugLog.entries.map(e => JSON.stringify(e)).join('\n');
      return ultraEntries ? base + '\n' + ultraEntries : base;
    }
    try {
      const filePath = DebugLog.getFilePath();
      if (!filePath) {
        const base = DebugLog.entries.map(e => JSON.stringify(e)).join('\n');
        return ultraEntries ? base + '\n' + ultraEntries : base;
      }
      const info = await FileSystem.getInfoAsync(filePath);
      let content = '';
      if (info.exists) {
        content = await FileSystem.readAsStringAsync(filePath);
      } else {
        content = DebugLog.entries.map(e => JSON.stringify(e)).join('\n');
      }
      if (ultraEntries) content = content + '\n' + ultraEntries;
      await LogFolder.writeLog(`debuglog-session-export.jsonl`, content);
      return content;
    } catch {}
    const base = DebugLog.entries.map(e => JSON.stringify(e)).join('\n');
    const combined = ultraEntries ? base + '\n' + ultraEntries : base;
    await LogFolder.writeLog(`debuglog-session-export.jsonl`, combined);
    return combined;
  }

  static async listLogFiles(): Promise<string[]> {
    if (Platform.OS === 'web') return [];
    try {
      const dir = DebugLog.getDir();
      if (!dir) return [];
      const dirInfo = await FileSystem.getInfoAsync(dir);
      if (!dirInfo.exists) return [];
      const files = await FileSystem.readDirectoryAsync(dir);
      return files.filter((f: string) => f.startsWith('debug_')).sort().reverse();
    } catch {
      return [];
    }
  }

  static async readLogFile(filename: string): Promise<string> {
    if (Platform.OS === 'web') return '';
    try {
      const dir = DebugLog.getDir();
      return await FileSystem.readAsStringAsync(`${dir}${filename}`);
    } catch {
      return '';
    }
  }

  static async cleanOldLogs(maxAgeDays: number = 7): Promise<number> {
    if (Platform.OS === 'web') return 0;
    try {
      const dir = DebugLog.getDir();
      if (!dir) return 0;
      const dirInfo = await FileSystem.getInfoAsync(dir);
      if (!dirInfo.exists) return 0;
      const files = await FileSystem.readDirectoryAsync(dir);
      const cutoff = Date.now() - maxAgeDays * 86400000;
      let cleaned = 0;
      for (const file of files) {
        const match = file.match(/debug_([a-z0-9]+)\.jsonl/);
        if (match) {
          const sessionTs = parseInt(match[1], 36);
          if (sessionTs < cutoff) {
            await FileSystem.deleteAsync(`${dir}${file}`);
            cleaned++;
          }
        }
      }
      return cleaned;
    } catch {
      return 0;
    }
  }

  static getMemoryEntries(limit?: number): DebugEntry[] {
    return limit ? DebugLog.entries.slice(-limit) : [...DebugLog.entries];
  }

  static getMemoryEntriesFormatted(limit: number = 500): string {
    const entries = DebugLog.entries.slice(-limit);
    return entries.map(e => {
      const d = e.data;
      switch (e.cat) {
        case 'VAULT_GET':
          return `[${e.ts}][VAULT] GET ${d.key} -> ${d.hasValue ? `found (${d.valueLen} chars, ${d.source})` : 'null'}`;
        case 'VAULT_SET':
          return `[${e.ts}][VAULT] SET ${d.key} (${d.valueLen} chars)`;
        case 'VAULT_DEL':
          return `[${e.ts}][VAULT] DEL ${d.key}`;
        case 'VAULT_ERR':
          return `[${e.ts}][VAULT][ERR] ${d.op} ${d.key}: ${d.error}`;

        case 'MODEL_DISC_START':
          return `[${e.ts}][MODEL] Discovery starting -> ${d.baseUrl}`;
        case 'MODEL_DISC_DONE':
          return `[${e.ts}][MODEL] Discovered ${d.count} models: [${(d.first10 as string[]).join(', ')}${(d.count as number) > 10 ? '...' : ''}]`;
        case 'MODEL_DISC_ERR':
          return `[${e.ts}][MODEL][ERR] Discovery failed: ${d.error}`;
        case 'MODEL_SET_DEFAULT':
          return `[${e.ts}][MODEL] Default changed: ${d.previousModel} -> ${d.modelId} (via ${d.source})`;
        case 'MODEL_SET_DEFAULT_ERR':
          return `[${e.ts}][MODEL][ERR] setDefault(${d.modelId}) failed: ${d.error}`;
        case 'MODEL_GET_DEFAULT':
          return `[${e.ts}][MODEL] getDefault() -> ${d.modelId}`;
        case 'MODEL_API_REQ':
          return `[${e.ts}][API] -> ${d.model} (task=${d.taskId}, ~${d.promptTokens} prompt tokens, max=${d.maxTokens})`;
        case 'MODEL_API_RESP':
          return `[${e.ts}][API] ← ${d.model} (in=${d.inputTokens}, out=${d.outputTokens}, $${d.cost}, ${d.durationMs}ms)`;
        case 'MODEL_API_ERR':
          return `[${e.ts}][API][ERR] ${d.model} (${d.durationMs}ms): ${d.error}`;
        case 'MODEL_ABORT':
          return `[${e.ts}][API] ABORT ${d.model}`;
        case 'MODEL_IMG_REQ':
          return `[${e.ts}][IMG] -> ${d.model} (prompt ${d.promptLen} chars)`;
        case 'MODEL_IMG_RESP':
          return `[${e.ts}][IMG] ← ${d.model} (${d.imageCount} images, $${d.cost}, ${d.durationMs}ms)`;
        case 'MODEL_IMG_ERR':
          return `[${e.ts}][IMG][ERR] ${d.model}: ${d.error}`;

        case 'AGENT_INIT_START':
          return `[${e.ts}][AGENT] === Initialization starting ===`;
        case 'AGENT_INIT_SUB':
          return `[${e.ts}][AGENT] Init ${d.name}: ${d.success ? 'OK' : 'FAIL'} (${d.durationMs}ms)${d.error ? ' -- ' + d.error : ''}`;
        case 'AGENT_INIT_DONE':
          return `[${e.ts}][AGENT] === Initialization complete (${d.durationMs}ms) ===`;
        case 'AGENT_EXEC_START':
          return `[${e.ts}][AGENT] === Execute: task=${d.taskId}, conv=${d.conversationId}, input=${d.inputLen} chars, replay=${d.replay} ===`;
        case 'AGENT_STEP':
          return `[${e.ts}][STEP][${d.phase}] ${d.success ? 'OK' : 'FAIL'} ${d.detail}`;
        case 'MODE':
          return `[${e.ts}][MODE] ${d.mode} | ${d.inputPreview}`;
        case 'PLAN':
          return `[${e.ts}][PLAN] cap=${d.capability} det=${d.deterministic} params=${JSON.stringify(d.params).slice(0, 200)}`;
        case 'SAFETY':
          return `[${e.ts}][SAFETY] risk=${d.risk} allowed=${d.allowed} reasons=${(d.reasons as string[]).join('; ')}`;
        case 'VERIFY':
          return `[${e.ts}][VERIFY] ${d.verified ? 'OK' : 'FAIL'} issues=${(d.issues as string[]).join('; ') || 'none'}`;
        case 'EXEC_RESULT':
          return `[${e.ts}][EXEC] ${d.capability} ${d.success ? 'OK' : 'FAIL'} ${d.resultPreview}`;

        case 'COST':
          return `[${e.ts}][COST] $${d.cost} ${d.model} task=${d.taskId}`;
        case 'COST_LIMIT':
          return `[${e.ts}][COST] ${d.type} limit check: limit=$${d.limit}, spent=$${d.spent}, ${d.allowed ? 'OK' : 'BLOCKED'}`;

        case 'CONV_NEW':
          return `[${e.ts}][CONV] Created: ${d.conversationId} "${d.title}"`;
        case 'CONV_LOAD':
          return `[${e.ts}][CONV] Loaded: ${d.conversationId} (${d.messageCount} msgs)`;
        case 'CONV_SAVE':
          return `[${e.ts}][CONV] Saved: ${d.conversationId} (${d.messageCount} msgs)`;
        case 'CONV_DEL':
          return `[${e.ts}][CONV] Deleted: ${d.conversationId}`;
        case 'CONV_MSG':
          return `[${e.ts}][CONV] Msg: ${d.conversationId} role=${d.role} (${d.contentLen} chars) src=${d.source}`;
        case 'CONV_LIST':
          return `[${e.ts}][CONV] Listed ${d.count} conversations`;
        case 'CONV_ERR':
          return `[${e.ts}][CONV][ERR] ${d.op} ${d.conversationId}: ${d.error}`;

        case 'USER_MSG':
          return `[${e.ts}][USER] ${d.content}`;
        case 'AI_RESPONSE':
          return `[${e.ts}][AI][${d.model}] cost=$${d.cost ?? 0} | ${(d.content as string).slice(0, 300)}`;
        case 'API_CALL':
          return `[${e.ts}][API] ${d.model} in=${d.promptTokens} out=${d.completionTokens} cost=$${d.cost} ${d.durationMs}ms`;

        case 'UI_INIT':
          return `[${e.ts}][UI] Init: ${d.phase} -- ${d.detail}`;
        case 'UI_DEFAULTS_LOADED':
          return `[${e.ts}][UI] Defaults loaded (${d.source}): ${JSON.stringify(d.defaults)}`;
        case 'UI_DEFAULTS_SAVED':
          return `[${e.ts}][UI] Defaults saved: ${JSON.stringify(d.defaults)}`;
        case 'UI_MODE_SWITCH':
          return `[${e.ts}][UI] Mode: ${d.from} -> ${d.to} (via ${d.source})`;
        case 'UI_MODEL_APPLY':
          return `[${e.ts}][UI] Model apply: ${d.modelId} for ${d.mode} (via ${d.source}) ${d.success ? 'OK' : 'FAIL'}${d.error ? ' -- ' + d.error : ''}`;
        case 'UI_PILL':
          return `[${e.ts}][UI] Pill: ${d.modelId} -> "${d.displayName}"`;
        case 'UI_PICKER_OPEN':
          return `[${e.ts}][UI] Picker opened: filter=${d.initialFilter}, mode=${d.currentMode}, active=${d.activeModelId}`;
        case 'UI_PICKER_SELECT':
          return `[${e.ts}][UI] Picker selected: ${d.previousModelId} -> ${d.modelId}`;
        case 'UI_PICKER_CLOSE':
          return `[${e.ts}][UI] Picker closed`;
        case 'UI_PLUS_OPEN':
          return `[${e.ts}][UI] Plus menu opened`;
        case 'UI_PLUS_SELECT':
          return `[${e.ts}][UI] Plus menu: type=${d.type}, hasDefault=${d.hasDefault}, default=${d.defaultModelId}`;
        case 'UI_SETTINGS':
          return `[${e.ts}][UI] Settings: ${d.direction}`;
        case 'UI_FOCUS':
          return `[${e.ts}][UI] FocusEffect: trigger=${d.trigger}, mode=${d.currentMode}, savedKeys=[${d.savedDefaultsKeys}], active=${d.activeModelId}`;
        case 'UI_SEND':
          return `[${e.ts}][UI] Send: ${d.inputLen} chars, mode=${d.mode}, model=${d.modelId}, processing=${d.isProcessing}`;
        case 'UI_STOP':
          return `[${e.ts}][UI] Stop: hadController=${d.hadActiveController}`;
        case 'UI_CONV_SWITCH':
          return `[${e.ts}][UI] Conv switch: ${d.fromId} -> ${d.toId}`;
        case 'UI_ERROR':
          return `[${e.ts}][UI][ERR] ${d.component}: ${d.error}`;

        case 'SETTINGS_API_SAVE':
          return `[${e.ts}][SETTINGS] API saved: ${d.apiId} -> ${d.baseUrl}`;
        case 'SETTINGS_API_DEL':
          return `[${e.ts}][SETTINGS] API deleted: ${d.apiId}`;
        case 'SETTINGS_DEFAULT_PICK':
          return `[${e.ts}][SETTINGS] Default picked: ${d.role} -> ${d.modelId} "${d.modelName}"`;
        case 'SETTINGS_DEFAULTS_SAVE':
          return `[${e.ts}][SETTINGS] Defaults saved: ${JSON.stringify(d.defaults)}`;
        case 'SETTINGS_COST_SAVE':
          return `[${e.ts}][SETTINGS] Cost limits: daily=$${d.daily}, task=$${d.task}`;

        case 'BUILD_START':
          return `[${e.ts}][BUILD] Started: task=${d.taskId} (${d.descriptionLen} chars)`;
        case 'BUILD_PHASE':
          return `[${e.ts}][BUILD][${d.phase}] ${d.message}`;
        case 'BUILD_DONE':
          return `[${e.ts}][BUILD] ${d.success ? 'OK' : 'FAIL'} (${d.durationMs}ms)${d.error ? ' -- ' + d.error : ''}`;

        case 'GENOME_START':
          return `[${e.ts}][GENOME] ${d.type} started: task=${d.taskId}`;
        case 'GENOME_PHASE':
          return `[${e.ts}][GENOME][${d.phase}] ${d.message}`;
        case 'GENOME_DONE':
          return `[${e.ts}][GENOME] ${d.success ? 'OK' : 'FAIL'} gen=${d.generation} fitness=${d.fitness}`;
        case 'GENOME_ERR':
          return `[${e.ts}][GENOME][ERR] ${d.error}`;

        case 'PERM_CHECK':
          return `[${e.ts}][PERM] ${d.capability}: ${d.granted ? 'granted' : 'denied'}`;
        case 'PERM_REQUEST':
          return `[${e.ts}][PERM] Request ${d.capability}: ${d.result}`;

        case 'STATE': {
          const { label: sl, ...rest } = d;
          return `[${e.ts}][STATE] ${sl}: ${JSON.stringify(rest).slice(0, 500)}`;
        }
        case 'UI_STATE': {
          const { label: ul, ...urest } = d;
          return `[${e.ts}][UI_STATE] ${ul}: ${JSON.stringify(urest).slice(0, 500)}`;
        }
        case 'SETTINGS_STATE': {
          const { label: stl, ...srest } = d;
          return `[${e.ts}][SETTINGS_STATE] ${stl}: ${JSON.stringify(srest).slice(0, 500)}`;
        }
        case 'MODEL_STATE': {
          const { label: ml, ...mrest } = d;
          return `[${e.ts}][MODEL_STATE] ${ml}: ${JSON.stringify(mrest).slice(0, 500)}`;
        }

        case 'SYSTEM':
          return `[${e.ts}][SYS][${d.context}] ${d.message}`;
        case 'ERROR':
          return `[${e.ts}][ERROR][${d.context}] ${d.message}${d.stack ? '\n' + d.stack : ''}`;
        case 'MODEL_SWITCH':
          return `[${e.ts}][MODEL_SWITCH] ${d.from} -> ${d.to} reason=${d.reason}`;

        default:
          return `[${e.ts}][${e.cat}] ${JSON.stringify(d).slice(0, 300)}`;
      }
    }).join('\n');
  }
}
