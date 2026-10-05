import { SecureVault } from '../security/SecureVault';
import { CostTracker } from '../services/CostTracker';
import { Logger } from '../utils/Logger';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import { classifyModelType } from '../utils/classifyModelType';
import type { UltraModelDef } from '../types/ultra';

export interface ModelRouterBridge {
  hasActiveProvider(): boolean;
  completeConversation(
    messages: Array<{ role: string; content: string }>,
    opts: {
      model?: string;
      maxTokens?: number;
      temperature?: number;
      taskId?: string;
      agentId?: string;
      conversationId?: string;
      /** Provider-qualified routing: prefer this provider when resolving the model. */
      preferredProviderId?: string;
    }
  ): Promise<{ content: string; model: string; inputTokens: number; outputTokens: number; cost: number }>;
  completeVision?(
    textPrompt: string,
    imageBase64: string,
    mimeType: string,
    opts: { model?: string; maxTokens?: number; taskId?: string; agentId?: string; preferredProviderId?: string }
  ): Promise<{ content: string; model: string; inputTokens: number; outputTokens: number; cost: number }>;
  refreshBridgeState(): Promise<void>;
}

/** Parse a composite key "providerId::modelId" or a bare "modelId". */
function parseCompositeKey(key: string): { providerId: string; modelId: string } {
  const sep = key.indexOf('::');
  if (sep === -1) return { providerId: '', modelId: key };
  return { providerId: key.slice(0, sep), modelId: key.slice(sep + 2) };
}

/** Build a composite key. Returns bare modelId if no providerId is known. */
function buildCompositeKey(providerId: string, modelId: string): string {
  return providerId ? `${providerId}::${modelId}` : modelId;
}

export interface ModelDef {
  id: string;
  name: string;
  description: string;
  type: 'text' | 'image' | 'video' | 'audio' | 'embedding';
  costPer1kInput: number;
  costPer1kOutput: number;
  maxTokens: number;
  contextWindow: number;
  speedTier: 'fast' | 'balanced' | 'heavy';
  capabilities: {
    supportsVision: boolean;
    supportsReasoning: boolean;
    supportsFunctionCalling: boolean;
    supportsWebSearch: boolean;
    supportsMultipleImages: boolean;
    isUncensored: boolean;
  };
  offline: boolean;
}

export interface ModelRecommendation {
  recommended: string;
  reason: string;
}

interface CompletionResult {
  content: string;
  model: string;
  inputTokens: number;
  outputTokens: number;
  cost: number;
}

const REQUEST_TIMEOUT = 60000;

export interface ProviderConfig {
  id: string;
  name: string;
  baseUrl: string;
  apiKey: string;
  categories: string[];
  isActive: boolean;
  models: ModelDef[];
}

interface CategoryDefault {
  modelId: string;
  providerId: string;
}


interface RouteResolution {
  modelId: string;
  apiKey: string;
  baseUrl: string;
}


export class ModelRouter {
  private vault: SecureVault;
  private costTracker: CostTracker;
  private logger: Logger;
  private apiKey: string | null = null;
  private models: Map<string, ModelDef>;
  private defaultModel: string;
  /** Provider ID of the currently selected model — empty string when using legacy/no-provider path. */
  private defaultProviderId: string = '';
  private baseUrl: string;
  private activeController: AbortController | null = null;
  private hasDiscoveredModels = false;
  private providers: Map<string, ProviderConfig> = new Map();
  private categoryDefaults: Map<string, CategoryDefault> = new Map();
  private bridge: ModelRouterBridge | null = null;
  /** Provider-backed models populated by syncRuntimeProviders() when bridge is active. */
  private providerBackedModels: Array<ModelDef & { providerId: string; providerName: string }> = [];

  constructor(vault: SecureVault, costTracker: CostTracker) {
    this.vault = vault;
    this.costTracker = costTracker;
    this.logger = new Logger('ModelRouter');
    this.models = new Map();
    this.defaultModel = '';
    this.baseUrl = '';
    this.registerModel({
      id: 'llama-3.3-70b',
      name: 'Llama 3.3 70B',
      description: 'Balanced performance for most use cases',
      type: 'text',
      costPer1kInput: 0.00088,
      costPer1kOutput: 0.00088,
      maxTokens: 8192,
      contextWindow: 131072,
      speedTier: 'heavy',
      capabilities: {
        supportsVision: false,
        supportsReasoning: false,
        supportsFunctionCalling: true,
        supportsWebSearch: true,
        supportsMultipleImages: false,
        isUncensored: false,
      },
      offline: false,
    });
  }

  private registerModel(m: ModelDef): void {
    this.models.set(m.id, m);
  }

  setRuntimeBridge(bridge: ModelRouterBridge): void {
    this.bridge = bridge;
    DebugLog.push('SYSTEM', { event: 'model_router_bridge_set', hasActiveProvider: bridge.hasActiveProvider() });
    this.logger.info('ModelRouter runtime bridge attached');
  }

  /** Proxy bridge refresh — allows callers to re-sync providers without holding the bridge reference. */
  async refreshBridgeState(): Promise<void> {
    if (this.bridge) {
      await this.bridge.refreshBridgeState();
    } else {
      DebugLog.push('SYSTEM', { event: 'refresh_bridge_state_skipped_no_bridge' });
    }
  }

  /**
   * Populate the provider-backed model inventory from the active provider system.
   * Called by AgentCore.refreshBridgeState() after every provider save or init.
   * When this list is non-empty, getAllModelsWithProvider() returns it exclusively
   * so the picker reflects the real provider state rather than the legacy seed.
   */
  syncRuntimeProviders(
    models: Array<ModelDef & { providerId: string; providerName: string }>
  ): void {
    const legacyCount = this.models.size;
    this.providerBackedModels = models;
    // If the current defaultModel is not in the new list, clear it so the picker
    // doesn't show a stale/unavailable model as selected.
    // Use provider-qualified check when a provider is recorded: the same model ID on
    // a different provider is NOT a valid resolution for the previously selected one.
    if (this.defaultModel) {
      const stillValid = models.length > 0
        ? this.defaultProviderId
          ? models.some(m => m.id === this.defaultModel && m.providerId === this.defaultProviderId)
          : models.some(m => m.id === this.defaultModel)
        : false; // zero active providers → always clear
      if (!stillValid) {
        const prevKey = buildCompositeKey(this.defaultProviderId, this.defaultModel);
        DebugLog.push('SYSTEM', { event: 'default_model_cleared_after_sync', prev: prevKey, reason: models.length === 0 ? 'no_active_providers' : 'provider_or_model_removed' });
        this.defaultModel = '';
        this.defaultProviderId = '';
      }
    }
    DebugLog.modelInventorySync({
      reason: 'syncRuntimeProviders',
      source: models.length > 0 ? 'provider_bridge' : 'empty',
      providerBackedModelCount: models.length,
      legacyModelCount: legacyCount,
      returnedToPickerCount: models.length,
      selectedModel: this.getSelectedCompositeKey() || null,
      defaultModel: this.getSelectedCompositeKey() || null,
    });
  }

  // ── Debug / read-only helpers for logging snapshots ──────────────────────

  getProviderBackedModelCount(): number {
    return this.providerBackedModels.length;
  }

  getLegacyModelCount(): number {
    return this.models.size;
  }

  isBridgeAttached(): boolean {
    return this.bridge !== null;
  }

  /** Returns the provider-qualified composite key "providerId::modelId", or bare modelId if no provider is recorded. */
  getDefaultModelId(): string | null {
    if (!this.defaultModel) return null;
    return buildCompositeKey(this.defaultProviderId, this.defaultModel) || null;
  }

  /** Returns just the providerId portion of the current selection (empty string if none). */
  getDefaultProviderId(): string {
    return this.defaultProviderId;
  }

  /** Returns the composite key for the currently selected model. Empty string if no selection. */
  getSelectedCompositeKey(): string {
    return buildCompositeKey(this.defaultProviderId, this.defaultModel);
  }

  debugGetInventorySource(): 'provider_bridge' | 'legacy_cache' | 'mixed' | 'empty' {
    const hasBacked = this.providerBackedModels.length > 0;
    const hasLegacy = this.models.size > 0;
    if (hasBacked && this.bridge) return 'provider_bridge';
    if (hasBacked && hasLegacy) return 'mixed';
    if (hasLegacy) return 'legacy_cache';
    return 'empty';
  }

  async ensureResolvedDefaultModel(): Promise<void> {
    const available = this.providerBackedModels.length > 0
      ? this.providerBackedModels
      : [...this.models.values()];
    if (available.length === 0) return;
    // Already resolved with valid provider-qualified selection — no action needed.
    if (this.defaultModel) {
      const stillValid = this.defaultProviderId
        ? available.some(m => m.id === this.defaultModel && (m as any).providerId === this.defaultProviderId)
        : available.some(m => m.id === this.defaultModel);
      if (stillValid) return;
    }
    // Try to restore from vault (may be composite "providerId::modelId" or bare "modelId").
    const savedKey = await this.vault.get('preferred_model');
    if (savedKey) {
      const { providerId: savedProviderId, modelId: savedModelId } = parseCompositeKey(savedKey);
      const match = savedProviderId
        ? available.find(m => m.id === savedModelId && (m as any).providerId === savedProviderId)
        : available.find(m => m.id === savedModelId);
      if (match) {
        const prev = this.getSelectedCompositeKey();
        this.defaultModel = match.id;
        this.defaultProviderId = (match as any).providerId ?? savedProviderId;
        const restoredKey = buildCompositeKey(this.defaultProviderId, this.defaultModel);
        DebugLog.modelSetDefault(restoredKey, prev, 'vault_restore_ensured');
        this.logger.info(`ensureResolvedDefaultModel: restored from vault: ${restoredKey}`);
        return;
      }
    }
    // Auto-pick stable fallback — prefer first text model, else first available.
    const fallback = available.find(m => m.type === 'text') ?? available[0];
    const prev = this.getSelectedCompositeKey();
    this.defaultModel = fallback.id;
    this.defaultProviderId = (fallback as any).providerId ?? '';
    const fallbackKey = buildCompositeKey(this.defaultProviderId, this.defaultModel);
    await this.vault.set('preferred_model', fallbackKey);
    DebugLog.modelSetDefault(fallbackKey, prev, 'auto_pick_fallback_ensured');
    this.logger.info(`ensureResolvedDefaultModel: auto-picked fallback: ${fallbackKey}`);
  }

  async initialize(): Promise<void> {
    await this.refreshApiKey();
  }

  async refreshApiKey(): Promise<void> {
    // When a bridge is installed the new provider system is authoritative —
    // skip all legacy vault reads; bridge sync will call ensureResolvedDefaultModel().
    if (this.bridge) {
      DebugLog.push('SYSTEM', { event: 'refresh_api_key_skipped_bridge_active' });
      this.logger.info('ModelRouter refreshApiKey: bridge is active, delegating to ensureResolvedDefaultModel');
      await this.ensureResolvedDefaultModel();
      return;
    }

    if (!this.apiKey) {
      this.logger.warn('No AI provider API key configured');
    } else {
      this.logger.info('ModelRouter initialized with API key');
      DebugLog.systemEvent('ModelRouter', 'Initialized with API key');
      if (!this.hasDiscoveredModels) {
        await this.discoverModels();
        this.hasDiscoveredModels = true;
      }
    }
    const savedKey = await this.vault.get('preferred_model');
    if (savedKey) {
      const { modelId: savedModelId } = parseCompositeKey(savedKey);
      if (this.models.has(savedModelId)) {
        const prev = this.getSelectedCompositeKey();
        this.defaultModel = savedModelId;
        // Legacy (no-bridge) path has no providerId — leave defaultProviderId as ''.
        this.defaultProviderId = '';
        DebugLog.modelSetDefault(savedModelId, prev, 'vault_restore');
        this.logger.info(`Using preferred model: ${savedModelId}`);
      } else {
        this.logger.warn(`Skipping saved preferred model because it is not discovered: ${savedKey}`);
        DebugLog.push('SYSTEM' as any, {
          event: 'preferred_model_skipped_undiscovered',
          modelId: savedKey,
        });
      }
    }
    if (!this.defaultModel && this.hasDiscoveredModels) {
      const textModels = [...this.models.values()].filter(m => m.type === 'text');
      if (textModels.length > 0) {
        this.defaultModel = textModels[0].id;
        DebugLog.modelSetDefault(textModels[0].id, '', 'auto_pick_first');
        this.logger.info(`Auto-picked default model: ${textModels[0].id}`);
      }
    }
  }

  private inferSpeed(id: string): 'fast' | 'balanced' | 'heavy' {
    const m = id.toLowerCase();
    if (m.includes('70b') || m.includes('405b') || m.includes('72b')) return 'heavy';
    if (m.includes('8b') || m.includes('mini') || m.includes('small') || m.includes('7b')) return 'fast';
    return 'balanced';
  }

  async loadProviders(): Promise<void> {
    // When a runtime bridge is active, provider discovery is owned by ProviderManager.
    // This method becomes a no-op — the bridge.refreshBridgeState() handles reloading.
    if (this.bridge) {
      DebugLog.push('SYSTEM', { event: 'load_providers_skipped_bridge_active' });
      return;
    }
    // Legacy no-bridge path: nothing to load because saved_apis is no longer written.
    DebugLog.push('SYSTEM', { event: 'load_providers_no_bridge_no_op' });
  }

  private findProviderForModel(modelId: string): ProviderConfig | null {
    for (const [, p] of this.providers) {
      if (p.models.some(m => m.id === modelId)) return p;
    }
    return null;
  }

  getModelForCategory(category: string): { modelId: string; baseUrl: string; apiKey: string } | null {
    const catDefault = this.categoryDefaults.get(category);
    if (catDefault) {
      const provider = this.providers.get(catDefault.providerId);
      if (provider && provider.isActive && provider.apiKey) {
        return { modelId: catDefault.modelId, baseUrl: provider.baseUrl, apiKey: provider.apiKey };
      }
    }
    if (this.apiKey && this.defaultModel) {
      return { modelId: this.defaultModel, baseUrl: this.baseUrl, apiKey: this.apiKey };
    }
    return null;
  }

  getAllModelsWithProvider(): Array<ModelDef & { providerId: string; providerName: string }> {
    // Bridge mode: return provider-backed list exclusively.
    // If providers are configured but have no discovered models yet, return empty
    // so the picker shows an honest empty state rather than the legacy Llama seed.
    if (this.bridge) {
      return [...this.providerBackedModels];
    }
    // Legacy (no-bridge) path: fall back to internal models map.
    const result: Array<ModelDef & { providerId: string; providerName: string }> = [];
    for (const [, p] of this.providers) {
      for (const m of p.models) result.push({ ...m, providerId: p.id, providerName: p.name });
    }
    if (result.length === 0) {
      for (const [, m] of this.models) result.push({ ...m, providerId: 'primary', providerName: 'Primary' });
    }
    return result;
  }

  getProviders(): ProviderConfig[] {
    return Array.from(this.providers.values());
  }

  private async discoverModels(): Promise<void> {
    if (!this.apiKey) return;
    DebugLog.modelDiscoveryStart(this.baseUrl);
    try {
      const effectiveApiKey = this.apiKey!;
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 15000);
      const resp = await fetch(`${this.baseUrl}/models`, {
        headers: {
          'Authorization': `Bearer ${effectiveApiKey}`,
          'Content-Type': 'application/json',
        },
        signal: controller.signal,
      });
      clearTimeout(timeout);
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const data = await resp.json();
      const sample = (Array.isArray(data?.data) ? data.data : []).slice(0, 3).map((m: any) => ({
        id: m.id,
        type: m.type,
        object: m.object,
        specType: m.model_spec?.type,
        specName: m.model_spec?.name,
        caps: m.model_spec?.capabilities ? Object.fromEntries(Object.entries(m.model_spec.capabilities).filter(([_, v]) => v === true)) : null,
        pricing: m.model_spec?.pricing,
      }));
      DebugLog.push('NET_MODELS_RAW', { totalCount: (data?.data || []).length, sample });
      const list = Array.isArray(data?.data) ? data.data : (Array.isArray(data) ? data : []);
      for (const m of list) {
        const spec = m.model_spec || {};
        const caps = spec.capabilities || {};
        const pricing = spec.pricing || {};
        const validModelTypes: ModelDef['type'][] = ['text', 'image', 'video', 'audio', 'embedding'];
        const rawType = m.type || 'text';
        const rawApiType = m.type || spec.type || '';
        const classifiedType = classifyModelType(m.id, m.name || m.id, rawApiType, caps);
        const modelType: ModelDef['type'] = (classifiedType && validModelTypes.includes(classifiedType as ModelDef['type']))
          ? (classifiedType as ModelDef['type'])
          : validModelTypes.includes(rawType as ModelDef['type'])
            ? (rawType as ModelDef['type'])
            : 'text';
        const contextWindow = Number(spec.availableContextTokens ?? m.context_length ?? 8192) || 8192;
        const inputPrice = pricing.input?.usd ?? 0.01;
        const outputPrice = pricing.output?.usd ?? 0.01;
        const isUncensored = (m.id || '').toLowerCase().includes('uncensored') ||
          (m.id || '').toLowerCase().includes('role-play') ||
          (spec.privacy === 'unfiltered');

        const def: ModelDef = {
          id: m.id,
          name: spec.name || m.id,
          description: spec.description || '',
          type: modelType,
          costPer1kInput: inputPrice,
          costPer1kOutput: outputPrice,
          maxTokens: Math.min(contextWindow, 4096),
          contextWindow,
          speedTier: this.inferSpeed(m.id),
          capabilities: {
            supportsVision: caps.supportsVision ?? false,
            supportsReasoning: caps.supportsReasoning ?? false,
            supportsFunctionCalling: caps.supportsFunctionCalling ?? false,
            supportsWebSearch: caps.supportsWebSearch ?? false,
            supportsMultipleImages: caps.supportsMultipleImages ?? false,
            isUncensored,
          },
          offline: spec.offline ?? false,
        };

        this.models.set(m.id, def);
      }
      this.logger.info(`Discovered ${list.length} models`);

      // Log per-model classification detail
      const classificationLog: Array<{ id: string; apiType: string; classified: string; caps: string }> = [];
      for (const [modelId, model] of this.models) {
        classificationLog.push({
          id: modelId,
          apiType: model.type,
          classified: classifyModelType(modelId, model.name, model.type, model.capabilities),
          caps: Object.entries(model.capabilities).filter(([_, v]) => v === true).map(([k]) => k).join(','),
        });
      }
      DebugLog.push('MODEL_DISCOVERY_DETAIL', { models: classificationLog });

      // Log classification breakdown using classifyModelType
      const breakdown: Record<string, number> = {};
      for (const [, model] of this.models) {
        const cat = classifyModelType(model.id, model.name, model.type, model.capabilities);
        breakdown[cat] = (breakdown[cat] || 0) + 1;
      }
      DebugLog.modelState('picker_classification', breakdown);

      DebugLog.modelDiscoveryResult(list.length, list.map((m: any) => m.id));
      DebugLog.modelState("post_discovery", {
        discoveredCount: this.models.size,
        defaultModel: this.defaultModel,
        hasApiKey: !!this.apiKey,
        baseUrl: this.baseUrl,
        modelIds: Array.from(this.models.keys()).slice(0, 20),
      });
    } catch (error: any) {
      DebugLog.modelDiscoveryError(error.message);
      DebugLog.modelState("discovery_failed", {
        discoveredCount: this.models.size,
        defaultModel: this.defaultModel,
        hasApiKey: !!this.apiKey,
        baseUrl: this.baseUrl,
      });
      this.logger.warn('Model discovery failed: ' + error.message);
    }
  }


  private isValidHttpUrl(url: string | null | undefined): boolean {
    if (!url) return false;
    try {
      const parsed = new URL(url);
      return parsed.protocol === 'http:' || parsed.protocol === 'https:';
    } catch {
      return false;
    }
  }


  private normalizeBaseUrl(url: string | null | undefined): string {
    if (!this.isValidHttpUrl(url)) return '';
    return String(url).replace(/\/+$/, '');
  }


  private normalizeCategory(category: string | undefined | null): string {
    if (!category) return '';
    const raw = category.toLowerCase();
    if (raw === 'conversation' || raw === 'chat' || raw === 'text') return 'chat';
    if (raw === 'agent_action') return 'reasoning';
    return raw;
  }


  private getCategoryForAgent(agentId: string | undefined, explicitCategory?: string): string {
    const normalizedExplicit = this.normalizeCategory(explicitCategory);
    if (normalizedExplicit) return normalizedExplicit;
    const agentCategoryMap: Record<string, string> = {
      cortex_planner: 'reasoning',
      cortex_reasoner: 'reasoning',
      multistep_planner: 'reasoning',
      context_analyst: 'chat',
      diagnostician: 'chat',
      app_intel: 'chat',
      app_extractor: 'chat',
      vision: 'chat',
      kg_extractor: 'chat',
      intent_parser: 'chat',
      proactive: 'chat',
      correction: 'chat',
      chat: 'chat',
      main: 'chat',
      codegen: 'reasoning',
    };
    return agentId ? (agentCategoryMap[agentId] || '') : '';
  }


  private findRoutingForModel(modelId: string): RouteResolution | null {
    const provider = this.findProviderForModel(modelId);
    if (!provider) return null;
    return {
      modelId,
      apiKey: provider.apiKey,
      baseUrl: this.normalizeBaseUrl(provider.baseUrl),
    };
  }


  private resolveRoute(options: { requestedModel?: string; category?: string; agentId?: string }): RouteResolution {
    const requestedModel = options.requestedModel || '';
    if (requestedModel) {
      const explicit = this.findRoutingForModel(requestedModel);
      if (explicit) return explicit;
      return { modelId: requestedModel, apiKey: this.apiKey || '', baseUrl: this.baseUrl };
    }

    const category = this.getCategoryForAgent(options.agentId, options.category);
    if (category) {
      const catRoute = this.getModelForCategory(category);
      if (catRoute) {
        return {
          modelId: catRoute.modelId,
          apiKey: catRoute.apiKey,
          baseUrl: this.normalizeBaseUrl(catRoute.baseUrl),
        };
      }
    }

    const fallbackModel = this.defaultModel || requestedModel;
    const discoveredFallback = fallbackModel ? this.findRoutingForModel(fallbackModel) : null;
    if (discoveredFallback) return discoveredFallback;
    return {
      modelId: fallbackModel,
      apiKey: this.apiKey || '',
      baseUrl: this.baseUrl,
    };
  }


  private getCandidateTextModels(): ModelDef[] {
    return [...this.models.values()].filter(m => m.type === 'text');
  }


  selectModel(_taskType: string, _budget?: number): string {
    return this.defaultModel;
  }

  getModel(modelId: string): UltraModelDef | undefined {
    const m = this.models.get(modelId);
    if (!m) return undefined;
    return {
      id: m.id,
      contextWindow: m.contextWindow,
      speedTier: m.speedTier,
      strengths: [],
    };
  }

  getContextWindow(modelId?: string): number {
    const id = modelId || this.defaultModel;
    const m = this.models.get(id);
    return m?.contextWindow ?? 8192;
  }


  recommendModel(input: { taskType?: string; requiredContextTokens?: number; currentModel?: string }): ModelRecommendation | null {
    const textModels = this.getCandidateTextModels();
    if (textModels.length === 0) return null;

    const currentModelId = input.currentModel || this.defaultModel;
    const current = currentModelId ? this.models.get(currentModelId) : undefined;
    const taskType = (input.taskType || 'conversation').toLowerCase();
    const requiredContextTokens = Math.max(0, Number(input.requiredContextTokens || 0));

    let target: ModelDef | undefined = current && current.type === 'text' ? current : undefined;
    let reason = '';

    if (requiredContextTokens > 0) {
      const contextEligible = textModels
        .filter(m => m.contextWindow >= requiredContextTokens)
        .sort((a, b) => a.contextWindow - b.contextWindow || a.maxTokens - b.maxTokens);
      if (contextEligible.length > 0) {
        const bestFit = contextEligible[0];
        if (!target || target.contextWindow < requiredContextTokens || target.contextWindow > bestFit.contextWindow * 2) {
          target = bestFit;
          reason = `It better matches the required context window (${requiredContextTokens} tokens).`;
        }
      }
    }

    if (taskType === 'code' || taskType === 'agent_action' || taskType === 'reasoning') {
      const reasoningModel = textModels
        .filter(m => m.capabilities.supportsReasoning)
        .sort((a, b) => b.contextWindow - a.contextWindow || b.maxTokens - a.maxTokens)[0];
      if (reasoningModel && reasoningModel.id !== currentModelId) {
        if (!target || !target.capabilities.supportsReasoning) {
          target = reasoningModel;
          reason = 'It is better suited for structured reasoning and multi-step work.';
        }
      }
    }

    if (taskType === 'image') {
      const uncensoredTextModel = textModels.find(m => m.capabilities.isUncensored) || textModels.find(m => m.capabilities.supportsFunctionCalling);
      if (uncensoredTextModel && uncensoredTextModel.id !== currentModelId) {
        if (!target || target.type !== 'text') {
          target = uncensoredTextModel;
        }
        reason = reason || 'It is a safer text-model choice for planning image generation prompts.';
      }
    }

    if (!target) {
      target = textModels.sort((a, b) => b.contextWindow - a.contextWindow || b.maxTokens - a.maxTokens)[0];
      reason = reason || 'It is the strongest available general text model.';
    }

    if (!target || !currentModelId || target.id === currentModelId) return null;
    return { recommended: target.id, reason };
  }

  async complete(
    prompt: string,
    options: {
      model?: string;
      systemPrompt?: string;
      temperature?: number;
      maxTokens?: number;
      taskId?: string;
      agentId?: string;
      timeout?: number;
      category?: string;
    } = {}
  ): Promise<CompletionResult> {
    if (this.bridge) {
      if (!this.bridge.hasActiveProvider()) throw new Error('No AI provider configured. Open Settings → AI Providers to add one.');
      const sysPrompt = options.systemPrompt ?? "You are Agent Ultra, an autonomous AI agent on a user's Android phone.";
      return this.bridge.completeConversation(
        [{ role: 'system', content: sysPrompt }, { role: 'user', content: prompt }],
        { model: options.model || this.defaultModel, maxTokens: options.maxTokens, temperature: options.temperature, taskId: options.taskId, agentId: options.agentId, preferredProviderId: this.defaultProviderId || undefined }
      );
    }
    if (!this.apiKey) {
      throw new Error('No AI provider configured. Open Settings → AI Providers to add one.');
    }
    if (!options.model && !this.defaultModel) {
      const textModels = [...this.models.values()].filter(m => m.type === 'text');
      if (textModels.length > 0) {
        this.defaultModel = textModels[0].id;
        DebugLog.modelSetDefault(textModels[0].id, '', 'auto_pick_complete');
      } else {
        throw new Error('No model selected. Open Settings > API Setup to configure a model.');
      }
    }
    const model = options.model || this.defaultModel;
    const taskId = options.taskId || 'default';
    const agentId = options.agentId || 'main';
    const route = this.resolveRoute({ requestedModel: options.model || model, category: options.category, agentId });
    const effectiveModel = route.modelId || model;
    const effectiveApiKey = route.apiKey || this.apiKey;
    const effectiveBaseUrl = route.baseUrl || this.baseUrl;

    if (!this.costTracker.isWithinDailyLimit()) {
      throw new Error('Daily cost limit reached. Increase in Settings or wait until tomorrow.');
    }
    if (!this.costTracker.isWithinTaskLimit(taskId)) {
      throw new Error('Task cost limit reached.');
    }
    const systemPrompt =
      options.systemPrompt ||
      "You are Agent Ultra, an autonomous AI agent on a user's Android phone. You have device access including file system, contacts, SMS, camera, media, and can build Android apps on-device. Be precise, concise, action-oriented. When generating code, provide complete compilable code with no omissions.";

    const promptTokens = Math.ceil((prompt.length + systemPrompt.length) / 4);
    DebugLog.modelApiRequest(effectiveModel, taskId, promptTokens, options.maxTokens ?? 4000);
    DebugLog.convContextSent(taskId, effectiveModel, {
      system_prompt_chars: systemPrompt.length,
      history_message_count: 0,
      history_chars: 0,
      capability_context_chars: 0,
      user_message_chars: prompt.length,
      estimated_total_tokens: promptTokens,
    });
    const startTime = Date.now();
    try {
      this.logger.info(`Sending request to ${model}...`);
      const controller = new AbortController();
      this.activeController = controller;
      const timeout = setTimeout(() => controller.abort(), options.timeout || REQUEST_TIMEOUT);
      const apiPayload = {
        model: effectiveModel,
        messages: [
          { role: 'system', content: systemPrompt },
          { role: 'user', content: prompt },
        ],
        temperature: options.temperature ?? 0.7,
        max_tokens: options.maxTokens ?? 4000,
      };
      DebugLog.systemEvent('ModelRouter.complete', `API_PAYLOAD model=${effectiveModel} task=${taskId} system_chars=${systemPrompt.length} user_chars=${prompt.length} temp=${apiPayload.temperature} max_tokens=${apiPayload.max_tokens}`);
      const fetchStartMs = Date.now();
      const resp = await fetch(`${effectiveBaseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${effectiveApiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(apiPayload),
        signal: controller.signal,
      });
      clearTimeout(timeout);
      this.activeController = null;
      if (!resp.ok) {
        if (resp.status === 401) throw new Error('Invalid API key. Check your provider settings.');
        if (resp.status === 429) throw new Error('Rate limited. Wait before retrying.');
        throw new Error(`AI provider error: HTTP ${resp.status}`);
      }
      const data = await resp.json();
      const content = data.choices[0].message.content;
      const usage = data.usage || { prompt_tokens: 0, completion_tokens: 0 };
      const cost = await this.costTracker.record(effectiveModel, usage.prompt_tokens, usage.completion_tokens, taskId, agentId);
      const durationMs = Date.now() - startTime;
      const fetchDurationMs = Date.now() - fetchStartMs;
      DebugLog.modelApiResponse(effectiveModel, taskId, usage.prompt_tokens, usage.completion_tokens, cost, durationMs);
      DebugLog.push('NET_DETAIL', { method: 'POST', url: '/chat/completions', status: resp.status, durationMs: fetchDurationMs, bodyBytes: JSON.stringify(apiPayload).length, model: effectiveModel, taskId });
      this.logger.info(`${effectiveModel} responded in ${durationMs}ms, cost: $${cost.toFixed(6)}`);
      return { content, model: effectiveModel, inputTokens: usage.prompt_tokens, outputTokens: usage.completion_tokens, cost };
    } catch (error: any) {
      const durationMs = Date.now() - startTime;
      DebugLog.modelApiError(effectiveModel, taskId, error.message, durationMs);
      if (error.name === 'AbortError') {
        if (this.activeController === null) {
          throw new Error('Request stopped by user.');
        }
        throw new Error('Request timed out after 60s. Check your connection and try again.');
      }
      if (error.message.includes('AI provider') || error.message.includes('Invalid') || error.message.includes('Rate limited')) throw error;
      throw new Error('AI request failed: ' + error.message);
    }
  }

  async completeWithConversation(
    messages: Array<{ role: string; content: string }>,
    options: {
      model?: string;
      temperature?: number;
      maxTokens?: number;
      taskId?: string;
      agentId?: string;
      category?: string;
    } = {}
  ): Promise<CompletionResult> {
    if (this.bridge) {
      if (!this.bridge.hasActiveProvider()) throw new Error('No AI provider configured. Open Settings → AI Providers to add one.');
      return this.bridge.completeConversation(messages, {
        model: options.model || this.defaultModel,
        maxTokens: options.maxTokens,
        temperature: options.temperature,
        taskId: options.taskId,
        agentId: options.agentId,
        preferredProviderId: this.defaultProviderId || undefined,
      });
    }
    if (!this.apiKey) throw new Error('No AI provider configured. Open Settings → AI Providers to add one.');
    const taskId = options.taskId || 'default';
    const agentId = options.agentId || 'main';
    if (!this.costTracker.isWithinDailyLimit()) throw new Error('Daily cost limit reached.');
    const route = this.resolveRoute({ requestedModel: options.model || this.defaultModel, category: options.category, agentId });
    const model = route.modelId || options.model || this.defaultModel;
    const effectiveApiKey = route.apiKey || this.apiKey;
    const effectiveBaseUrl = route.baseUrl || this.baseUrl;

    const totalChars = messages.reduce((s, m) => s + (m.content?.length ?? 0), 0);
    const systemMsgs = messages.filter(m => m.role === 'system');
    const historyMsgs = messages.filter(m => m.role !== 'system');
    const systemChars = systemMsgs.reduce((s, m) => s + (m.content?.length ?? 0), 0);
    const historyChars = historyMsgs.reduce((s, m) => s + (m.content?.length ?? 0), 0);
    DebugLog.modelApiRequest(model, taskId, Math.ceil(totalChars / 4), options.maxTokens ?? 4000);
    DebugLog.convContextSent(taskId, model, {
      system_prompt_chars: systemChars,
      history_message_count: historyMsgs.length,
      history_chars: historyChars,
      capability_context_chars: 0,
      user_message_chars: historyMsgs.length > 0 ? (historyMsgs[historyMsgs.length - 1].content?.length ?? 0) : 0,
      estimated_total_tokens: Math.ceil(totalChars / 4),
    });
    const callStart = Date.now();
    try {
      const controller = new AbortController();
      this.activeController = controller;
      const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT);
      const convPayload = {
        model,
        messages,
        temperature: options.temperature ?? 0.7,
        max_tokens: options.maxTokens ?? 4000,
      };
      DebugLog.systemEvent('ModelRouter.completeWithConversation', `API_PAYLOAD model=${model} task=${taskId} msg_count=${messages.length} total_chars=${totalChars} temp=${convPayload.temperature} max_tokens=${convPayload.max_tokens}`);
      const convFetchStartMs = Date.now();
      const resp = await fetch(`${effectiveBaseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${effectiveApiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(convPayload),
        signal: controller.signal,
      });
      clearTimeout(timeout);
      this.activeController = null;
      if (!resp.ok) {
        if (resp.status === 401) throw new Error('Invalid API key. Check your provider settings.');
        if (resp.status === 429) throw new Error('Rate limited. Wait before retrying.');
        throw new Error(`AI provider error: HTTP ${resp.status}`);
      }
      const data = await resp.json();
      const content = data.choices[0].message.content;
      const usage = data.usage || { prompt_tokens: 0, completion_tokens: 0 };
      const cost = await this.costTracker.record(model, usage.prompt_tokens, usage.completion_tokens, taskId, agentId);
      const durationMs = Date.now() - callStart;
      const convFetchDurationMs = Date.now() - convFetchStartMs;
      DebugLog.modelApiResponse(model, taskId, usage.prompt_tokens, usage.completion_tokens, cost, durationMs);
      DebugLog.push('NET_DETAIL', { method: 'POST', url: '/chat/completions', status: resp.status, durationMs: convFetchDurationMs, bodyBytes: JSON.stringify(convPayload).length, model, taskId });
      return { content, model, inputTokens: usage.prompt_tokens, outputTokens: usage.completion_tokens, cost };
    } catch (error: any) {
      const durationMs = Date.now() - callStart;
      DebugLog.modelApiError(model, taskId, error.message, durationMs);
      if (error.name === 'AbortError') {
        if (this.activeController === null) {
          throw new Error('Request stopped by user.');
        }
        throw new Error('Request timed out after 60s. Check your connection and try again.');
      }
      if (error.message.includes('AI provider') || error.message.includes('Invalid') || error.message.includes('Rate limited')) throw error;
      throw new Error('AI conversation failed: ' + error.message);
    }
  }

  abortCurrentRequest(): void {
    if (this.activeController) {
      const model = this.defaultModel;
      this.activeController.abort();
      this.activeController = null;
      DebugLog.modelAbort(model);
      this.logger.info('Request aborted by user');
    }
  }

  /**
   * Try to escalate a response that shows uncertainty markers by re-issuing the
   * conversation to a different model (if one is available).
   *
   * Returns null when:
   *   - The response has no uncertainty markers
   *   - Only one model is configured (nothing to escalate to)
   *   - The escalation call fails
   */
  async tryEscalate(
    responseText: string,
    messages: Array<{ role: string; content: string }>,
    opts: { taskId?: string; agentId?: string; maxTokens?: number }
  ): Promise<CompletionResult | null> {
    const UNCERTAINTY_MARKERS = [
      /\bi'?m not (sure|certain|confident)\b/i,
      /\bi don'?t (know|have enough|have sufficient)\b/i,
      /\bi (cannot|can'?t) (be sure|say for certain|confirm)\b/i,
      /\bit'?s (unclear|hard to say|difficult to determine)\b/i,
      /\bI lack (the|sufficient|enough|up-to-date)\b/i,
      /\bmy (training|knowledge) (cut-?off|data)\b/i,
    ];
    const hasUncertainty = UNCERTAINTY_MARKERS.some(r => r.test(responseText));
    if (!hasUncertainty) return null;

    // Check that we have multiple models available so there's something to escalate to
    const allModels = this.providerBackedModels;
    if (allModels.length < 2) {
      DebugLog.push('SYSTEM', { event: 'escalate_skipped', reason: 'only_one_model' });
      return null;
    }

    // Pick a different model from a different provider if possible, else any different model
    const currentModel = this.defaultModel;
    const currentProvider = this.defaultProviderId;
    const candidate =
      allModels.find(m => m.id !== currentModel && m.providerId !== currentProvider && m.type === 'text') ??
      allModels.find(m => m.id !== currentModel && m.type === 'text');

    if (!candidate) {
      DebugLog.push('SYSTEM', { event: 'escalate_skipped', reason: 'no_different_model' });
      return null;
    }

    DebugLog.push('SYSTEM', {
      event: 'escalate_triggered',
      from: buildCompositeKey(currentProvider, currentModel),
      to: buildCompositeKey(candidate.providerId, candidate.id),
      uncertainty: responseText.slice(0, 120),
    });

    try {
      if (this.bridge) {
        if (!this.bridge.hasActiveProvider()) return null;
        const escalateResult = await this.bridge.completeConversation(messages, {
          model: candidate.id,
          maxTokens: opts.maxTokens,
          taskId: opts.taskId,
          agentId: opts.agentId,
          preferredProviderId: candidate.providerId,
        });
        DebugLog.push('SYSTEM', { event: 'escalate_success', model: escalateResult.model });
        return escalateResult;
      }
      // Legacy (no-bridge) path — no alternate model available
      return null;
    } catch (err: any) {
      DebugLog.push('ERROR', { event: 'escalate_failed', error: err.message });
      return null;
    }
  }

  hasApiKey(): boolean {
    if (this.bridge) return this.bridge.hasActiveProvider();
    return this.apiKey !== null;
  }

  /** Returns the bare model ID (no provider prefix) — safe to pass directly to API calls. */
  getDefaultModel(): string {
    return this.defaultModel;
  }

  /**
   * Set the default model. Accepts either:
   *   - A composite key  "providerId::modelId"  (from the model picker)
   *   - A bare model ID  "modelId"              (legacy / API-call path)
   * Stores the composite key in the vault for lossless round-trip.
   * `getDefaultModel()` always returns the bare model ID for API-call compatibility.
   */
  async setDefaultModel(key: string): Promise<void> {
    const { providerId, modelId } = parseCompositeKey(key);
    // Validate existence.
    const inProviderBacked = providerId
      ? this.providerBackedModels.some(m => m.id === modelId && m.providerId === providerId)
      : this.providerBackedModels.some(m => m.id === modelId);
    const inLegacy = this.models.has(modelId);
    if (!inProviderBacked && !inLegacy) {
      DebugLog.modelSetDefaultError(key, 'Model not available');
      throw new Error(`Model ${modelId} not available`);
    }
    const prev = this.getSelectedCompositeKey();
    this.defaultModel = modelId;
    this.defaultProviderId = providerId
      ? providerId
      // Infer provider from inventory when only a bare ID was passed.
      : (this.providerBackedModels.find(m => m.id === modelId)?.providerId ?? '');
    const compositeKey = buildCompositeKey(this.defaultProviderId, this.defaultModel);
    await this.vault.set('preferred_model', compositeKey);
    DebugLog.modelSetDefault(compositeKey, prev, 'setDefaultModel');
    this.logger.info(`Default model set to: ${compositeKey}`);
  }

  async completeWithVision(
    textPrompt: string,
    imageBase64: string,
    mimeType: string = 'image/jpeg',
    options: {
      model?: string;
      maxTokens?: number;
      taskId?: string;
      agentId?: string;
    } = {}
  ): Promise<CompletionResult> {
    if (this.bridge?.completeVision) {
      if (!this.bridge.hasActiveProvider()) throw new Error('No AI provider configured. Open Settings → AI Providers to add one.');
      return this.bridge.completeVision(textPrompt, imageBase64, mimeType, {
        model: options.model,
        maxTokens: options.maxTokens,
        taskId: options.taskId,
        agentId: options.agentId,
        preferredProviderId: this.defaultProviderId || undefined,
      });
    }
    if (!this.apiKey) throw new Error('No AI provider configured. Open Settings → AI Providers to add one.');

    let model = options.model || '';
    if (!model) {
      const visionModels = [...this.models.values()].filter(m => m.capabilities.supportsVision && m.type === 'text');
      if (visionModels.length > 0) {
        model = visionModels[0].id;
      } else {
        model = this.defaultModel;
      }
    }

    const taskId = options.taskId || 'vision';
    const agentId = options.agentId || 'vision';
    DebugLog.systemEvent('ModelRouter', `Vision request: model=${model} imageSize=${imageBase64.length} prompt="${textPrompt.slice(0, 80)}"`);

    const messages = [
      { role: 'system', content: 'You are Agent Ultra, an autonomous AI agent. Analyze the image and respond to the user\'s request precisely and concisely.' },
      {
        role: 'user',
        content: [
          { type: 'image_url', image_url: { url: `data:${mimeType};base64,${imageBase64}` } },
          { type: 'text', text: textPrompt || 'What do you see in this image?' },
        ],
      },
    ];

    const callStart = Date.now();
    try {
      const controller = new AbortController();
      this.activeController = controller;
      const timeout = setTimeout(() => controller.abort(), 90000);
      const payload = {
        model,
        messages,
        temperature: 0.4,
        max_tokens: options.maxTokens ?? 2000,
      };
      const resp = await fetch(`${this.baseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${this.apiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
        signal: controller.signal,
      });
      clearTimeout(timeout);
      this.activeController = null;
      if (!resp.ok) throw new Error(`Vision API error: HTTP ${resp.status}`);
      const data = await resp.json();
      const content = data.choices[0].message.content;
      const usage = data.usage || { prompt_tokens: 0, completion_tokens: 0 };
      const cost = await this.costTracker.record(model, usage.prompt_tokens, usage.completion_tokens, taskId, agentId);
      DebugLog.modelApiResponse(model, taskId, usage.prompt_tokens, usage.completion_tokens, cost, Date.now() - callStart);
      return { content, model, inputTokens: usage.prompt_tokens, outputTokens: usage.completion_tokens, cost };
    } catch (error: any) {
      DebugLog.modelApiError(model, taskId, error.message, Date.now() - callStart);
      throw new Error('Vision request failed: ' + error.message);
    }
  }

  getAvailableModels(): ModelDef[] {
    return Array.from(this.models.values());
  }

  canHandleLocally(taskType: string): boolean {
    const local = Array.from(this.models.values()).filter(m => m.offline);
    if (local.length === 0) return false;
    const t = taskType.toLowerCase();
    if (t === 'text' || t === 'conversation' || t === 'chat') {
      return local.some(m => m.type === 'text');
    }
    return local.some(m => m.type === t);
  }

  async setBaseUrl(url: string): Promise<void> {
    this.baseUrl = this.normalizeBaseUrl(url);
    if (this.baseUrl) {
      await this.vault.set('api_base_url', this.baseUrl);
    } else {
      await this.vault.delete('api_base_url').catch(() => {});
    }
    this.logger.info(`API base URL set to: ${this.baseUrl}`);
  }

  getBaseUrl(): string {
    return this.baseUrl;
  }

  getModelsByType(type: string): ModelDef[] {
    return Array.from(this.models.values()).filter(m => m.type === type);
  }

  async generateImage(
    prompt: string,
    options: {
      model?: string;
      width?: number;
      height?: number;
      steps?: number;
      stylePreset?: string;
      negativePrompt?: string;
      taskId?: string;
    } = {}
  ): Promise<{ images: string[]; model: string; cost: number }> {
    if (!this.apiKey) throw new Error('API key not configured.');
    const model = options.model || 'fluently-xl';
    const taskId = options.taskId || 'image';
    DebugLog.modelImageRequest(model, prompt.length);
    const startTime = Date.now();
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 120000);
      let resp: Response;
      try {
        resp = await fetch(`${this.baseUrl}/image/generate`, {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${this.apiKey}`,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            model,
            prompt,
            width: options.width || 1024,
            height: options.height || 1024,
            steps: options.steps,
            style_preset: options.stylePreset,
            negative_prompt: options.negativePrompt,
            return_binary: false,
            safe_mode: false,
          }),
          signal: controller.signal,
        });
      } finally {
        clearTimeout(timeout);
      }
      if (!resp!.ok) throw new Error(`Image generation failed: HTTP ${resp!.status}`);
      const data = await resp!.json();
      const images = data.images || [];
      const cost = await this.costTracker.record(model, 0, 0, taskId, 'image');
      DebugLog.modelImageResponse(model, images.length, cost, Date.now() - startTime);
      return { images, model, cost };
    } catch (error: any) {
      DebugLog.modelImageError(model, error.message);
      if (error.name === 'AbortError') throw new Error('Image generation timed out.');
      throw new Error('Image generation failed: ' + error.message);
    }
  }
}
