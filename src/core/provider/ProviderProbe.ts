// Provider probe — best-effort safe probe with bounded timeout

import { buildAuthHeaders } from './AuthHeaders';
import { buildEndpointUrl } from './UrlNormalize';
import { getAdapterRegistry } from './AdapterRegistry';
import { UltraDevLog } from '../../utils/UltraDevLog';
import type { ApiProvider, DiscoveredModel, ProbeSummary, ProviderStatus, ProbeEndpointResult } from '../../types/provider';

const PROBE_TIMEOUT_MS = 12000;
const CAP_SNAP_LEN = 4000;

function capSnap(obj: unknown): string {
  try {
    const s = JSON.stringify(obj);
    return s.length > CAP_SNAP_LEN ? s.slice(0, CAP_SNAP_LEN) + '…[capped]' : s;
  } catch {
    return String(obj).slice(0, CAP_SNAP_LEN);
  }
}

function redactSecrets(obj: unknown): unknown {
  if (typeof obj !== 'object' || obj === null) return obj;
  const result: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
    const lower = k.toLowerCase();
    if (lower.includes('key') || lower.includes('secret') || lower.includes('token') || lower.includes('password')) {
      result[k] = '[REDACTED]';
    } else if (typeof v === 'object' && v !== null) {
      result[k] = redactSecrets(v);
    } else {
      result[k] = v;
    }
  }
  return result;
}

async function safeFetchJson(url: string, headers: Record<string, string>): Promise<{
  ok: boolean;
  status: number;
  data?: unknown;
  errorCode?: ProbeEndpointResult['errorCode'];
  requiresStrongerScope?: boolean;
}> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS);
  try {
    const resp = await fetch(url, { method: 'GET', headers, signal: controller.signal });
    const status = resp.status;
    if (status === 401) return { ok: false, status, errorCode: 'unauthorized' };
    if (status === 403) return { ok: false, status, errorCode: 'forbidden_scope', requiresStrongerScope: true };
    if (status === 404) return { ok: false, status, errorCode: 'not_found' };
    if (!resp.ok) return { ok: false, status, errorCode: 'unsupported' };
    try {
      const data = await resp.json();
      return { ok: true, status, data };
    } catch {
      return { ok: false, status, errorCode: 'parse_error' };
    }
  } catch (e: any) {
    if (e?.name === 'AbortError') return { ok: false, status: 0, errorCode: 'network_error' };
    return { ok: false, status: 0, errorCode: 'network_error' };
  } finally {
    clearTimeout(timer);
  }
}

function parseDiscoveredModels(data: unknown, providerId: string): DiscoveredModel[] {
  if (!data || typeof data !== 'object') return [];
  const obj = data as Record<string, unknown>;
  const rawList: unknown[] = Array.isArray(obj.data) ? obj.data : Array.isArray(data) ? (data as unknown[]) : [];
  const models: DiscoveredModel[] = [];
  for (const raw of rawList) {
    if (!raw || typeof raw !== 'object') continue;
    const m = raw as Record<string, unknown>;
    const id = String(m.id ?? m.model_id ?? m.name ?? '');
    if (!id) continue;
    const name = String(m.name ?? m.display_name ?? id);
    const contextWindow = Number(m.context_length ?? m.context_window ?? m.max_context_length ?? 0);
    const maxTokens = Number(m.max_tokens ?? m.max_completion_tokens ?? 0);
    models.push({
      id,
      name,
      providerId,
      contextWindow,
      maxTokens,
      raw: m as Record<string, unknown>,
    });
  }
  return models;
}

export interface ProbeOutput {
  summary: ProbeSummary;
  models: DiscoveredModel[];
  accountData?: Record<string, unknown>;
}

export async function probeProvider(
  provider: ApiProvider,
  apiKey: string | null,
  password: string | null
): Promise<ProbeOutput> {
  const probedAt = Date.now();

  UltraDevLog.push('SYSTEM', {
    event: 'probe_start',
    providerId: provider.id,
    providerName: provider.name,
    baseUrl: provider.baseUrl,
    authMode: provider.authMode,
    enabled: (provider as any).isActive,
    supportsModelListing: provider.capabilities.supportsModelListing,
    supportsAccountDiscovery: provider.capabilities.supportsAccountDiscovery,
    supportsBillingDiscovery: provider.capabilities.supportsBillingDiscovery,
    adapterIds: provider.capabilities.adapterIds,
    hasApiKey: !!apiKey,
  });

  const authHeaders = buildAuthHeaders({
    authMode: provider.authMode,
    apiKey,
    password,
    customAuthHeaderName: provider.customAuthHeaderName,
    customAuthHeaderPrefix: provider.customAuthHeaderPrefix,
  });

  const endpointResults: ProbeEndpointResult[] = [];
  let status: ProviderStatus = 'unknown';
  let models: DiscoveredModel[] = [];
  let hasAccountData = false;
  let hasBillingData = false;
  let hasUsageData = false;
  let requiresStrongerScope = false;
  let rawAccountSnapshotCapped: string | undefined;
  let rawBillingSnapshotCapped: string | undefined;
  let rawUsageSnapshotCapped: string | undefined;
  let probeError: string | undefined;
  let accountData: Record<string, unknown> | undefined;

  // 1. Probe adapter-specific endpoints
  const registry = getAdapterRegistry();
  for (const adapterId of provider.capabilities.adapterIds) {
    const adapter = registry.get(adapterId);
    if (!adapter) {
      UltraDevLog.push('SYSTEM', { event: 'probe_adapter_not_found', providerId: provider.id, adapterId });
      continue;
    }
    const fakeRoute = {
      providerId: provider.id,
      providerName: provider.name,
      modelId: '',
      groupId: '',
      groupName: '',
      adapterId,
      operation: 'chat' as const,
      selectionStrategy: 'priority' as const,
      apiKey: apiKey ?? '',
      baseUrl: provider.baseUrl,
      authMode: provider.authMode,
      customAuthHeaderName: provider.customAuthHeaderName,
      customAuthHeaderPrefix: provider.customAuthHeaderPrefix,
    };
    try {
      const results = await adapter.probePlan(fakeRoute);
      endpointResults.push(...results);
      UltraDevLog.push('SYSTEM', { event: 'probe_adapter_plan', providerId: provider.id, adapterId, endpointCount: results.length, statuses: results.map(r => r.errorCode) });
    } catch (err: any) {
      UltraDevLog.push('SYSTEM', { event: 'probe_adapter_plan_error', providerId: provider.id, adapterId, error: err?.message });
    }
  }

  // 2. Always try /models for model listing
  if (provider.capabilities.supportsModelListing) {
    const modelsUrl = buildEndpointUrl(provider.baseUrl, '/models');
    UltraDevLog.push('SYSTEM', { event: 'probe_models_endpoint_attempt', providerId: provider.id, url: modelsUrl });
    const modelsResult = await safeFetchJson(modelsUrl, authHeaders);
    endpointResults.push({
      url: modelsUrl,
      reachable: modelsResult.status > 0 || modelsResult.ok,
      status: modelsResult.status,
      errorCode: modelsResult.errorCode ?? (modelsResult.ok ? 'ok' : undefined),
      rawSnapshotCapped: modelsResult.data ? capSnap(redactSecrets(modelsResult.data)) : undefined,
    });
    if (modelsResult.ok && modelsResult.data) {
      models = parseDiscoveredModels(modelsResult.data, provider.id);
      status = 'ok';
      UltraDevLog.push('SYSTEM', { event: 'probe_models_ok', providerId: provider.id, modelCount: models.length });
    } else if (modelsResult.errorCode === 'unauthorized') {
      status = 'unauthorized';
      probeError = 'Invalid API key or unauthorized.';
      UltraDevLog.push('SYSTEM', { event: 'probe_models_unauthorized', providerId: provider.id });
    } else if (modelsResult.errorCode === 'network_error') {
      status = 'network_error';
      probeError = 'Could not reach provider. Check URL and network.';
      UltraDevLog.push('SYSTEM', { event: 'probe_models_network_error', providerId: provider.id });
    } else if (modelsResult.errorCode) {
      status = 'error';
      probeError = `Models endpoint: ${modelsResult.errorCode}`;
      UltraDevLog.push('SYSTEM', { event: 'probe_models_error', providerId: provider.id, errorCode: modelsResult.errorCode, httpStatus: modelsResult.status });
    }
  }

  // 3. Account discovery
  if (provider.capabilities.supportsAccountDiscovery && status === 'ok') {
    const accountCandidates = ['/account', '/me', '/user', '/dashboard/info'];
    for (const path of accountCandidates) {
      const url = buildEndpointUrl(provider.baseUrl, path);
      const res = await safeFetchJson(url, authHeaders);
      if (res.ok && res.data) {
        accountData = redactSecrets(res.data) as Record<string, unknown>;
        hasAccountData = true;
        rawAccountSnapshotCapped = capSnap(accountData);
        endpointResults.push({ url, reachable: true, status: res.status, errorCode: 'ok', rawSnapshotCapped: rawAccountSnapshotCapped });
        UltraDevLog.push('SYSTEM', { event: 'probe_account_ok', providerId: provider.id, path });
        break;
      } else if (res.requiresStrongerScope) {
        requiresStrongerScope = true;
        endpointResults.push({ url, reachable: true, status: res.status, errorCode: 'forbidden_scope', requiresStrongerScope: true });
        UltraDevLog.push('SYSTEM', { event: 'probe_account_scope_required', providerId: provider.id, path });
        break;
      } else {
        endpointResults.push({ url, reachable: res.status > 0, status: res.status, errorCode: res.errorCode });
      }
    }
  }

  // 4. Billing discovery
  if (provider.capabilities.supportsBillingDiscovery && status === 'ok') {
    const billingCandidates = ['/billing', '/billing/usage', '/usage'];
    for (const path of billingCandidates) {
      const url = buildEndpointUrl(provider.baseUrl, path);
      const res = await safeFetchJson(url, authHeaders);
      if (res.ok && res.data) {
        hasBillingData = true;
        rawBillingSnapshotCapped = capSnap(redactSecrets(res.data));
        endpointResults.push({ url, reachable: true, status: res.status, errorCode: 'ok', rawSnapshotCapped: rawBillingSnapshotCapped });
        UltraDevLog.push('SYSTEM', { event: 'probe_billing_ok', providerId: provider.id, path });
        break;
      } else if (res.requiresStrongerScope) {
        requiresStrongerScope = true;
        endpointResults.push({ url, reachable: true, status: res.status, errorCode: 'forbidden_scope', requiresStrongerScope: true });
        break;
      } else {
        endpointResults.push({ url, reachable: res.status > 0, status: res.status, errorCode: res.errorCode });
      }
    }
  }

  // 5. Usage discovery
  if (provider.capabilities.supportsUsageDiscovery && status === 'ok') {
    const usageCandidates = ['/usage/stats', '/usage/history', '/stats'];
    for (const path of usageCandidates) {
      const url = buildEndpointUrl(provider.baseUrl, path);
      const res = await safeFetchJson(url, authHeaders);
      if (res.ok && res.data) {
        hasUsageData = true;
        rawUsageSnapshotCapped = capSnap(redactSecrets(res.data));
        endpointResults.push({ url, reachable: true, status: res.status, errorCode: 'ok', rawSnapshotCapped: rawUsageSnapshotCapped });
        UltraDevLog.push('SYSTEM', { event: 'probe_usage_ok', providerId: provider.id, path });
        break;
      } else {
        endpointResults.push({ url, reachable: res.status > 0, status: res.status, errorCode: res.errorCode });
      }
    }
  }

  // Set final status if still unknown
  if (status === 'unknown') {
    if (endpointResults.some(r => r.reachable && r.errorCode === 'ok')) {
      status = 'ok';
    } else if (endpointResults.some(r => r.errorCode === 'unauthorized')) {
      status = 'unauthorized';
    } else if (endpointResults.every(r => !r.reachable)) {
      status = 'network_error';
    } else {
      status = 'error';
    }
  }

  const summary: ProbeSummary = {
    probedAt,
    status,
    modelsFound: models.length,
    hasAccountData,
    hasBillingData,
    hasUsageData,
    requiresStrongerScope,
    endpointResults,
    rawAccountSnapshotCapped,
    rawBillingSnapshotCapped,
    rawUsageSnapshotCapped,
    error: probeError,
  };

  UltraDevLog.push('SYSTEM', {
    event: 'probe_complete',
    providerId: provider.id,
    status,
    modelsFound: models.length,
    hasAccountData,
    hasBillingData,
    hasUsageData,
    requiresStrongerScope,
    error: probeError,
    endpointCount: endpointResults.length,
    durationMs: Date.now() - probedAt,
  });

  return { summary, models, accountData };
}
