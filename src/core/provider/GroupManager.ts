// Group Manager — authoritative store for model groups and user defaults

import { AppStorage } from '../../utils/AppStorage';
import { UltraDevLog } from '../../utils/UltraDevLog';
import type { ModelGroup, GroupMember, UserDefaults, AllowedOperation, SelectionStrategy, ApiProvider } from '../../types/provider';
import { DEFAULT_USER_DEFAULTS, STORAGE_KEYS as SK } from '../../types/provider';

function generateId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 9);
}

export class GroupManager {
  private groups: Map<string, ModelGroup> = new Map();
  private userDefaults: UserDefaults = { ...DEFAULT_USER_DEFAULTS };
  private roundRobinState: Map<string, number> = new Map();
  private initialized = false;

  async initialize(): Promise<void> {
    if (this.initialized) return;
    await this.load();
    this.initialized = true;
    UltraDevLog.push('SYSTEM', { event: 'group_manager_init', groupCount: this.groups.size });
  }

  private async load(): Promise<void> {
    try {
      const raw = await AppStorage.get(SK.modelGroups);
      if (raw) {
        const list: ModelGroup[] = JSON.parse(raw);
        this.groups.clear();
        for (const g of list) {
          if (g && g.id) this.groups.set(g.id, g);
        }
      }
      const defaultsRaw = await AppStorage.get(SK.userDefaults);
      if (defaultsRaw) {
        const parsed = JSON.parse(defaultsRaw);
        this.userDefaults = { ...DEFAULT_USER_DEFAULTS, ...parsed };
      }
      const rrRaw = await AppStorage.get(SK.groupRoundRobinState);
      if (rrRaw) {
        const parsed = JSON.parse(rrRaw);
        this.roundRobinState = new Map(Object.entries(parsed));
      }
    } catch (e: any) {
      UltraDevLog.push('ERROR', { event: 'group_manager_load_failed', error: e?.message });
    }
  }

  private async saveGroups(): Promise<void> {
    await AppStorage.set(SK.modelGroups, JSON.stringify(Array.from(this.groups.values())));
  }

  private async saveDefaults(): Promise<void> {
    await AppStorage.set(SK.userDefaults, JSON.stringify(this.userDefaults));
  }

  private async saveRoundRobinState(): Promise<void> {
    const obj = Object.fromEntries(this.roundRobinState.entries());
    await AppStorage.set(SK.groupRoundRobinState, JSON.stringify(obj));
  }

  getAll(): ModelGroup[] {
    return Array.from(this.groups.values());
  }

  getActive(): ModelGroup[] {
    return this.getAll().filter(g => g.isActive);
  }

  getById(id: string): ModelGroup | null {
    return this.groups.get(id) ?? null;
  }

  getUserDefaults(): UserDefaults {
    return { ...this.userDefaults };
  }

  getOperationMapping(): Record<string, string> {
    return { ...this.userDefaults.groupAssignments };
  }

  async createGroup(input: {
    name: string;
    description?: string;
    notes?: string;
    tags?: string[];
    aliases?: string[];
    selectionStrategy?: SelectionStrategy;
  }): Promise<ModelGroup> {
    const id = generateId();
    const now = Date.now();
    const group: ModelGroup = {
      id,
      name: input.name,
      description: input.description ?? '',
      notes: input.notes ?? '',
      tags: input.tags ?? [],
      aliases: input.aliases ?? [],
      isActive: true,
      selectionStrategy: input.selectionStrategy ?? 'priority',
      fallbackGroupIds: [],
      members: [],
      createdAt: now,
      updatedAt: now,
    };
    this.groups.set(id, group);
    await this.saveGroups();
    UltraDevLog.push('SYSTEM', { event: 'group_created', id, name: group.name });
    return group;
  }

  async updateGroup(id: string, updates: Partial<Omit<ModelGroup, 'id' | 'createdAt' | 'members'>>): Promise<void> {
    const existing = this.groups.get(id);
    if (!existing) throw new Error(`Group ${id} not found`);
    const updated: ModelGroup = { ...existing, ...updates, updatedAt: Date.now() };
    this.groups.set(id, updated);
    await this.saveGroups();
    UltraDevLog.push('SYSTEM', { event: 'group_updated', id });
  }

  async deleteGroup(id: string): Promise<void> {
    this.groups.delete(id);
    this.roundRobinState.delete(id);
    // Remove references from other groups' fallback chains
    for (const [gid, g] of this.groups) {
      if (g.fallbackGroupIds.includes(id)) {
        this.groups.set(gid, {
          ...g,
          fallbackGroupIds: g.fallbackGroupIds.filter(fid => fid !== id),
          updatedAt: Date.now(),
        });
      }
    }
    // Clean up user defaults references
    const ga = { ...this.userDefaults.groupAssignments };
    let changed = false;
    for (const [op, gid] of Object.entries(ga)) {
      if (gid === id) { delete ga[op]; changed = true; }
    }
    if (this.userDefaults.fallbackGroupId === id) {
      this.userDefaults = { ...this.userDefaults, fallbackGroupId: null, groupAssignments: ga };
      changed = true;
    } else if (changed) {
      this.userDefaults = { ...this.userDefaults, groupAssignments: ga };
    }
    await this.saveGroups();
    if (changed) await this.saveDefaults();
    UltraDevLog.push('SYSTEM', { event: 'group_deleted', id });
  }

  async addMember(groupId: string, member: Omit<GroupMember, 'id'>): Promise<GroupMember> {
    const group = this.groups.get(groupId);
    if (!group) throw new Error(`Group ${groupId} not found`);
    const id = generateId();
    const newMember: GroupMember = { ...member, id };
    const updated: ModelGroup = {
      ...group,
      members: [...group.members, newMember],
      updatedAt: Date.now(),
    };
    this.groups.set(groupId, updated);
    await this.saveGroups();
    return newMember;
  }

  async updateMember(groupId: string, memberId: string, updates: Partial<Omit<GroupMember, 'id'>>): Promise<void> {
    const group = this.groups.get(groupId);
    if (!group) throw new Error(`Group ${groupId} not found`);
    const updated: ModelGroup = {
      ...group,
      members: group.members.map(m => m.id === memberId ? { ...m, ...updates } : m),
      updatedAt: Date.now(),
    };
    this.groups.set(groupId, updated);
    await this.saveGroups();
  }

  async removeMember(groupId: string, memberId: string): Promise<void> {
    const group = this.groups.get(groupId);
    if (!group) throw new Error(`Group ${groupId} not found`);
    const updated: ModelGroup = {
      ...group,
      members: group.members.filter(m => m.id !== memberId),
      updatedAt: Date.now(),
    };
    this.groups.set(groupId, updated);
    await this.saveGroups();
  }

  async saveUserDefaults(defaults: Partial<UserDefaults>): Promise<void> {
    this.userDefaults = { ...this.userDefaults, ...defaults };
    await this.saveDefaults();
    UltraDevLog.push('SYSTEM', { event: 'user_defaults_saved' });
  }

  advanceRoundRobin(groupId: string, memberCount: number): number {
    const current = this.roundRobinState.get(groupId) ?? 0;
    const next = memberCount > 0 ? (current + 1) % memberCount : 0;
    this.roundRobinState.set(groupId, next);
    this.saveRoundRobinState().catch(() => {});
    return current;
  }

  getRoundRobinCursor(groupId: string): number {
    return this.roundRobinState.get(groupId) ?? 0;
  }

  async setOperationGroup(operation: AllowedOperation, groupId: string | null): Promise<void> {
    const ga: Record<string, string> = { ...this.userDefaults.groupAssignments };
    if (groupId === null) {
      delete ga[operation];
    } else {
      ga[operation] = groupId;
    }
    this.userDefaults = { ...this.userDefaults, groupAssignments: ga };
    await this.saveDefaults();
    UltraDevLog.push('SYSTEM', { event: 'group_manager_operation_group_set', operation, groupId });
  }

  /**
   * Returns groups that are genuinely routable for the given operation.
   * A group is eligible if it is active and has at least one enabled member whose
   * allowedOperations includes the operation. Provider-level checks (isActive,
   * key present) require passing activeProviders; if omitted the check is
   * operation-only (used for UI display).
   */
  getEligibleGroupsForOperation(operation: AllowedOperation, activeProviders?: ApiProvider[]): ModelGroup[] {
    const providerMap = activeProviders ? new Map(activeProviders.map(p => [p.id, p])) : null;
    return this.getActive().filter(g => {
      return g.members.some(m => {
        if (!m.enabled) return false;
        if (!m.allowedOperations.includes(operation)) return false;
        if (providerMap) {
          const p = providerMap.get(m.providerId);
          if (!p || !p.isActive) return false;
        }
        return true;
      });
    });
  }

  async removeProviderReferences(providerId: string): Promise<void> {
    let changed = false;
    for (const [id, group] of this.groups) {
      const filtered = group.members.filter(m => m.providerId !== providerId);
      if (filtered.length !== group.members.length) {
        this.groups.set(id, { ...group, members: filtered, updatedAt: Date.now() });
        changed = true;
      }
    }
    if (changed) {
      await this.saveGroups();
      UltraDevLog.push('SYSTEM', { event: 'group_provider_references_removed', providerId });
    }
  }
}
