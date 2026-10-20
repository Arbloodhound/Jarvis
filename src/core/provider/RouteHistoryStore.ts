// Route history — persists per conversation for sticky routing and last_known_good

import { AppStorage } from '../../utils/AppStorage';
import { UltraDevLog } from '../../utils/UltraDevLog';
import type { RouteHistory, ResolvedRoute } from '../../types/provider';
import { STORAGE_KEYS as SK } from '../../types/provider';

export class RouteHistoryStore {
  private history: Map<string, RouteHistory> = new Map();
  private initialized = false;

  async initialize(): Promise<void> {
    if (this.initialized) return;
    await this.load();
    this.initialized = true;
  }

  private async load(): Promise<void> {
    try {
      const raw = await AppStorage.get(SK.routeHistory);
      if (raw) {
        const list: RouteHistory[] = JSON.parse(raw);
        this.history.clear();
        for (const h of list) {
          if (h?.conversationId) this.history.set(h.conversationId, h);
        }
      }
    } catch (e: any) {
      UltraDevLog.push('ERROR', { event: 'route_history_load_failed', error: e?.message });
    }
  }

  private async save(): Promise<void> {
    const list = Array.from(this.history.values());
    await AppStorage.set(SK.routeHistory, JSON.stringify(list)).catch(() => {});
  }

  get(conversationId: string): RouteHistory | null {
    return this.history.get(conversationId) ?? null;
  }

  async update(conversationId: string, route: ResolvedRoute): Promise<void> {
    const entry: RouteHistory = {
      conversationId,
      lastGroupId: route.groupId ?? '',
      lastProviderId: route.providerId,
      lastModelId: route.modelId,
      lastAdapterId: route.adapterId,
      lastOperation: route.operation,
      updatedAt: Date.now(),
    };
    this.history.set(conversationId, entry);
    await this.save();
  }

  async invalidateProvider(providerId: string): Promise<void> {
    let changed = false;
    for (const [cid, h] of this.history) {
      if (h.lastProviderId === providerId) {
        this.history.delete(cid);
        changed = true;
      }
    }
    if (changed) await this.save();
    UltraDevLog.push('SYSTEM', { event: 'route_history_provider_cleared', providerId });
  }

  async invalidateGroup(groupId: string): Promise<void> {
    let changed = false;
    for (const [cid, h] of this.history) {
      if (h.lastGroupId === groupId) {
        this.history.delete(cid);
        changed = true;
      }
    }
    if (changed) await this.save();
  }

  async invalidateModel(providerId: string, modelId: string): Promise<void> {
    let changed = false;
    for (const [cid, h] of this.history) {
      if (h.lastProviderId === providerId && h.lastModelId === modelId) {
        this.history.delete(cid);
        changed = true;
      }
    }
    if (changed) await this.save();
  }

  async clear(): Promise<void> {
    this.history.clear();
    await this.save();
  }
}
