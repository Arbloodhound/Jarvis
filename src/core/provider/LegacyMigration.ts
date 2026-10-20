// One-time migration from legacy single-provider settings to the new multi-provider system

import type { SecureVault } from '../../security/SecureVault';
import type { ProviderManager } from './ProviderManager';
import type { GroupManager } from './GroupManager';
import { AppStorage } from '../../utils/AppStorage';
import { UltraDevLog } from '../../utils/UltraDevLog';
import { STORAGE_KEYS as SK } from '../../types/provider';

const MIGRATION_FLAG = SK.migrationComplete;
const LEGACY_KEY_NAMES = ['venice_api_key'];
const LEGACY_BASE_URL_KEYS = ['api_base_url'];
const LEGACY_MODEL_KEYS = ['preferred_model', 'default_model'];

export async function runLegacyMigration(
  vault: SecureVault,
  providerManager: ProviderManager,
  groupManager: GroupManager
): Promise<void> {
  try {
    const done = await AppStorage.get(MIGRATION_FLAG);
    if (done === 'true') return;

    UltraDevLog.push('SYSTEM', { event: 'legacy_migration_start' });

    // Detect legacy API key
    let legacyApiKey: string | null = null;
    let legacyKeySource: string | null = null;
    for (const keyName of LEGACY_KEY_NAMES) {
      const k = await vault.get(keyName).catch(() => null);
      if (k && k.trim()) {
        legacyApiKey = k.trim();
        legacyKeySource = keyName;
        break;
      }
    }

    // If no legacy API key, nothing to migrate — just mark done
    if (!legacyApiKey) {
      await AppStorage.set(MIGRATION_FLAG, 'true');
      UltraDevLog.push('SYSTEM', { event: 'legacy_migration_skip', reason: 'no_legacy_key' });
      return;
    }

    // Detect legacy base URL
    let legacyBaseUrl = 'https://api.venice.ai/api/v1';
    for (const urlKey of LEGACY_BASE_URL_KEYS) {
      const u = await vault.get(urlKey).catch(() => null) ?? await AppStorage.get(urlKey).catch(() => null);
      if (u && u.trim()) {
        legacyBaseUrl = u.trim();
        break;
      }
    }

    // Detect legacy preferred model
    let legacyModel: string | null = null;
    for (const mKey of LEGACY_MODEL_KEYS) {
      const m = await vault.get(mKey).catch(() => null) ?? await AppStorage.get(mKey).catch(() => null);
      if (m && m.trim()) {
        legacyModel = m.trim();
        break;
      }
    }

    UltraDevLog.push('SYSTEM', { event: 'legacy_migration_creating_provider', baseUrl: legacyBaseUrl, hasModel: !!legacyModel });

    // Create migrated provider
    const provider = await providerManager.addProvider({
      name: 'Migrated Provider',
      baseUrl: legacyBaseUrl,
      apiKey: legacyApiKey,
      authMode: 'bearer',
    });

    // Create migrated default group
    const group = await groupManager.createGroup({
      name: 'Default',
      description: 'Migrated from legacy configuration',
      tags: ['chat', 'default'],
      aliases: ['text', 'conversation'],
      selectionStrategy: 'priority',
    });

    // Add the legacy model as a group member if we have one
    if (legacyModel) {
      await groupManager.addMember(group.id, {
        providerId: provider.id,
        modelId: legacyModel,
        enabled: true,
        priority: 1,
        weight: 1,
        allowedOperations: ['chat', 'reason', 'vision', 'generic_text'],
        metadata: { migratedFrom: 'legacy' },
      });
    }

    // Set default group for all text operations
    await groupManager.saveUserDefaults({
      groupAssignments: {
        chat: group.id,
        reason: group.id,
        vision: group.id,
        generic_text: group.id,
      },
      fallbackGroupId: group.id,
    });

    // Mark migration done
    await AppStorage.set(MIGRATION_FLAG, 'true');

    // Probe to discover models
    try {
      await providerManager.probe(provider.id);
    } catch {
      // Non-fatal — probe runs in background
    }

    // Delete legacy keys
    for (const keyName of LEGACY_KEY_NAMES) {
      await vault.delete(keyName).catch(() => {});
    }
    for (const urlKey of LEGACY_BASE_URL_KEYS) {
      await vault.delete(urlKey).catch(() => {});
      await AppStorage.remove(urlKey).catch(() => {});
    }
    for (const mKey of LEGACY_MODEL_KEYS) {
      await vault.delete(mKey).catch(() => {});
      await AppStorage.remove(mKey).catch(() => {});
    }

    UltraDevLog.push('SYSTEM', {
      event: 'legacy_migration_complete',
      providerId: provider.id,
      groupId: group.id,
      legacyKeySource,
      legacyModel,
    });
  } catch (e: any) {
    UltraDevLog.push('ERROR', { event: 'legacy_migration_failed', error: e?.message });
    // Non-fatal — app continues without migration
  }
}
