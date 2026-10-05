// Provider Manager — authoritative store for provider connections

import { AppStorage } from '../../utils/AppStorage';
import { SecureVault } from '../../security/SecureVault';
import { probeProvider } from './ProviderProbe';
import { normalizeProviderUrl } from './UrlNormalize';
import { UltraDevLog } from '../../utils/UltraDevLog';
import type {
  ApiProvider,
  DiscoveredModel,
  ProviderStatus,
  STORAGE_KEYS,
  VAULT_KEYS,
} from '../../types/provider';
import {
  DEFAULT_PROVIDER_CAPABILITIES,
  STORAGE_KEYS as SK,
  VAULT_KEYS as VK,
} from '../../types/provider';

function generateId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 9);
}

export class ProviderManager {
  private vault: SecureVault;
  private providers: Map<string, ApiProvider> = new Map();
  private modelsByProvider: Map<string, DiscoveredModel[]> = new Map();
  private initialized = false;

  constructor(vault: SecureVault) {
    this.vault = vault;
  }

  async initialize(): Promise<void> {
    if (this.initialized) return;
    await this.load();
    this.initialized = true;
    UltraDevLog.push('SYSTEM', { event: 'provider_manager_init', count: this.providers.size });
  }

  private async load(): Promise<void> {
    try {
      const raw = await AppStorage.get(SK.providers);
      if (raw) {
        const list: ApiProvider[] = JSON.parse(raw);
        this.providers.clear();
        for (const p of list) {
          if (p && p.id) {
            if (!p.capabilities) p.capabilities = { ...DEFAULT_PROVIDER_CAPABILITIES };
            this.providers.set(p.id, p);
          }
        }
      }
      for (const [id] of this.providers) {
        try {
          const raw = await AppStorage.get(SK.providerModels(id));
          if (raw) {
            const models: DiscoveredModel[] = JSON.parse(raw);
            this.modelsByProvider.set(id, models);
          }
        } catch {
          // ignore model load failures per provider
        }
      }
    } catch (e: any) {
      UltraDevLog.push('ERROR', { event: 'provider_manager_load_failed', error: e?.message });
    }
  }

  private async save(): Promise<void> {
    const list = Array.from(this.providers.values());
    await AppStorage.set(SK.providers, JSON.stringify(list));
  }

  getAll(): ApiProvider[] {
    return Array.from(this.providers.values());
  }

  getActive(): ApiProvider[] {
    return this.getAll().filter(p => p.isActive);
  }

  getById(id: string): ApiProvider | null {
    return this.providers.get(id) ?? null;
  }

  getModels(providerId: string): DiscoveredModel[] {
    return this.modelsByProvider.get(providerId) ?? [];
  }

  getAllModels(): DiscoveredModel[] {
    const result: DiscoveredModel[] = [];
    for (const models of this.modelsByProvider.values()) {
      result.push(...models);
    }
    return result;
  }

  getModelsForProvider(providerId: string): DiscoveredModel[] {
    const provider = this.getById(providerId);
    if (!provider) return [];
    const discovered = this.modelsByProvider.get(providerId) ?? [];
    if (!provider.manualModelIds?.length) return discovered;
    const discoveredIds = new Set(discovered.map(m => m.id));
    const manualModels: DiscoveredModel[] = (provider.manualModelIds ?? [])
      .filter(id => !discoveredIds.has(id))
      .map(id => ({
        id,
        name: id,
        providerId,
        contextWindow: 0,
        maxTokens: 0,
        raw: { manual: true },
      }));
    return [...discovered, ...manualModels];
  }

  hasModel(providerId: string, modelId: string): boolean {
    const provider = this.getById(providerId);
    if (!provider) return false;
    if (provider.manualModelIds?.includes(modelId)) return true;
    return this.modelsByProvider.get(providerId)?.some(m => m.id === modelId) ?? false;
  }

  async getApiKey(provider: ApiProvider): Promise<string | null> {
    return this.vault.get(VK.providerApiKey(provider.id));
  }

  async getPassword(provider: ApiProvider): Promise<string | null> {
    if (!provider.passwordRef) return null;
    return this.vault.get(VK.providerPassword(provider.id));
  }

  async updatePassword(id: string, password: string): Promise<void> {
    const provider = this.providers.get(id);
    if (!provider) throw new Error(`Provider ${id} not found`);
    await this.vault.set(VK.providerPassword(id), password);
    await this.updateProvider(id, { passwordRef: VK.providerPassword(id) });
    UltraDevLog.push('SYSTEM', { event: 'provider_password_updated', id });
  }

  async addProvider(input: {
    name: string;
    baseUrl: string;
    apiKey: string;
    password?: string;
    authMode?: ApiProvider['authMode'];
    customAuthHeaderName?: string;
    customAuthHeaderPrefix?: string;
  }): Promise<ApiProvider> {
    const normalized = normalizeProviderUrl(input.baseUrl);
    if (!normalized.ok) throw new Error(normalized.error ?? 'Invalid provider URL');

    const id = generateId();
    const now = Date.now();
    const hasPassword = !!(input.password && input.password.trim());
    const provider: ApiProvider = {
      id,
      name: input.name,
      baseUrl: normalized.url,
      apiKeyRef: VK.providerApiKey(id),
      passwordRef: hasPassword ? VK.providerPassword(id) : undefined,
      authMode: input.authMode ?? 'bearer',
      customAuthHeaderName: input.customAuthHeaderName,
      customAuthHeaderPrefix: input.customAuthHeaderPrefix,
      isActive: true,
      createdAt: now,
      updatedAt: now,
      status: 'unknown',
      capabilities: { ...DEFAULT_PROVIDER_CAPABILITIES },
      metadata: {},
    };
    await this.vault.set(VK.providerApiKey(id), input.apiKey);
    if (hasPassword) {
      await this.vault.set(VK.providerPassword(id), input.password!.trim());
    }
    this.providers.set(id, provider);
    await this.save();
    UltraDevLog.push('SYSTEM', { event: 'provider_added', id, name: provider.name, baseUrl: provider.baseUrl, authMode: provider.authMode });
    return provider;
  }

  async updateProvider(id: string, updates: Partial<Omit<ApiProvider, 'id' | 'createdAt' | 'apiKeyRef'>>): Promise<void> {
    const existing = this.providers.get(id);
    if (!existing) throw new Error(`Provider ${id} not found`);
    if (updates.baseUrl) {
      const normalized = normalizeProviderUrl(updates.baseUrl);
      if (!normalized.ok) throw new Error(normalized.error ?? 'Invalid URL');
      updates.baseUrl = normalized.url;
    }
    const updated: ApiProvider = { ...existing, ...updates, updatedAt: Date.now() };
    this.providers.set(id, updated);
    await this.save();
    UltraDevLog.push('SYSTEM', { event: 'provider_updated', id });
  }

  async updateApiKey(id: string, apiKey: string): Promise<void> {
    const provider = this.providers.get(id);
    if (!provider) throw new Error(`Provider ${id} not found`);
    await this.vault.set(VK.providerApiKey(id), apiKey);
    await this.updateProvider(id, { status: 'unknown', lastVerifiedAt: undefined, probeError: undefined });
  }

  async setActive(id: string, active: boolean): Promise<void> {
    await this.updateProvider(id, { isActive: active });
    UltraDevLog.push('SYSTEM', { event: 'provider_toggle', id, active });
  }

  async deleteProvider(id: string): Promise<void> {
    this.providers.delete(id);
    this.modelsByProvider.delete(id);
    await this.vault.delete(VK.providerApiKey(id)).catch(() => {});
    await this.vault.delete(VK.providerPassword(id)).catch(() => {});
    await AppStorage.remove(SK.providerModels(id)).catch(() => {});
    await AppStorage.remove(SK.providerProbe(id)).catch(() => {});
    await AppStorage.remove(SK.providerAccount(id)).catch(() => {});
    await this.save();
    UltraDevLog.push('SYSTEM', { event: 'provider_deleted', id });
  }

  async probe(id: string): Promise<{ status: ProviderStatus; modelsFound: number; error?: string; requiresStrongerScope?: boolean }> {
    const provider = this.providers.get(id);
    if (!provider) throw new Error(`Provider ${id} not found`);
    const apiKey = await this.getApiKey(provider);
    const password = await this.getPassword(provider);
    UltraDevLog.push('SYSTEM', { event: 'provider_probe_start', id, name: provider.name });
    try {
      const output = await probeProvider(provider, apiKey, password);
      const { summary, models } = output;
      await this.updateProvider(id, {
        status: summary.status,
        lastVerifiedAt: summary.probedAt,
        lastProbeSummary: summary,
        probeError: summary.error,
      });
      if (models.length > 0) {
        this.modelsByProvider.set(id, models);
        await AppStorage.set(SK.providerModels(id), JSON.stringify(models));
      }
      if (output.accountData) {
        await AppStorage.set(SK.providerAccount(id), JSON.stringify(output.accountData));
      }
      await AppStorage.set(SK.providerProbe(id), JSON.stringify(summary));
      UltraDevLog.push('SYSTEM', {
        event: 'provider_probe_done', id,
        status: summary.status, modelsFound: summary.modelsFound,
        hasAccount: summary.hasAccountData, requiresStrongerScope: summary.requiresStrongerScope,
      });
      return {
        status: summary.status,
        modelsFound: summary.modelsFound,
        error: summary.error,
        requiresStrongerScope: summary.requiresStrongerScope,
      };
    } catch (e: any) {
      const error = e?.message || 'Probe failed';
      await this.updateProvider(id, { status: 'error', probeError: error });
      UltraDevLog.push('ERROR', { event: 'provider_probe_failed', id, error });
      return { status: 'error', modelsFound: 0, error };
    }
  }
}
