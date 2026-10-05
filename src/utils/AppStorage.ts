import AsyncStorage from '@react-native-async-storage/async-storage';
import { SecureVault } from '../security/SecureVault';
import { UltraDevLog } from './UltraDevLog';

// Keys that MUST survive process kills → vault (SecureStore)
// AsyncStorage doesn't persist across process kills on Samsung.
function normalizeValue(value: string | null): string | null {
  return value === '' ? null : value;
}

const VAULT_KEYS = new Set([
  'action_grid_config',
  'preferred_model', 'dev_mode_enabled', 'onboarding_done',
  'battery_optim_prompted', 'user_folders', 'task_templates',
  'grid_config_version', 'user_tier', 'tier_usage_today',
]);

let vaultInstance: SecureVault | null = null;

async function getVault(): Promise<SecureVault> {
  if (!vaultInstance) vaultInstance = await SecureVault.initialize();
  return vaultInstance;
}

export const AppStorage = {
  async get(key: string): Promise<string | null> {
    try {
      if (VAULT_KEYS.has(key)) {
        const vault = await getVault();
        return normalizeValue(await vault.get(key));
      }
      return await AsyncStorage.getItem(key);
    } catch (e: any) {
      UltraDevLog.push('STORAGE_READ_ERROR', { key, error: e?.message || 'unknown error' });
      return null;
    }
  },

  async set(key: string, value: string): Promise<void> {
    try {
      if (value === '') {
        await this.remove(key);
        return;
      }
      if (VAULT_KEYS.has(key)) {
        const vault = await getVault();
        await vault.set(key, value);
        return;
      }
      await AsyncStorage.setItem(key, value);
    } catch (e: any) {
      UltraDevLog.push('STORAGE_WRITE_ERROR', { key, error: e?.message || 'unknown error' });
      throw e;
    }
  },

  async remove(key: string): Promise<void> {
    try {
      if (VAULT_KEYS.has(key)) {
        const vault = await getVault();
        await vault.delete(key);
        return;
      }
      await AsyncStorage.removeItem(key);
    } catch (e: any) {
      UltraDevLog.push('STORAGE_DELETE_ERROR', { key, error: e?.message || 'unknown error' });
      throw e;
    }
  },
};
