import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';
import { Logger } from '../utils/Logger';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

const webStorage: Record<string, string> = {};

const SENSITIVE_KEYS = ['api_key', 'apikey', 'biometric', 'password', 'secret', 'token', 'provider_'];

async function storeSet(key: string, value: string): Promise<void> {
  if (Platform.OS === 'web') {
    try { localStorage.setItem(key, value); } catch { webStorage[key] = value; }
  } else {
    await SecureStore.setItemAsync(key, value);
  }
}

async function storeGet(key: string): Promise<string | null> {
  if (Platform.OS === 'web') {
    try { return localStorage.getItem(key) ?? webStorage[key] ?? null; } catch { return webStorage[key] ?? null; }
  }
  return await SecureStore.getItemAsync(key);
}

async function storeDel(key: string): Promise<void> {
  if (Platform.OS === 'web') {
    try { localStorage.removeItem(key); } catch {}
    delete webStorage[key];
    return;
  }
  await SecureStore.deleteItemAsync(key);
}

function isSensitiveKey(key: string): boolean {
  const normalized = key.toLowerCase();
  return SENSITIVE_KEYS.some(s => normalized.includes(s));
}

function normalizeStoredValue(value: string | null | undefined): string | null {
  if (value === undefined || value === null) return null;
  if (value === '') return null;
  return value;
}

export class SecureVault {
  private static instance: SecureVault | null = null;
  private logger: Logger;
  private cache: Map<string, string>;
  private initialized: boolean;

  private constructor() {
    this.logger = new Logger('SecureVault');
    this.cache = new Map();
    this.initialized = false;
  }

  static async initialize(): Promise<SecureVault> {
    if (SecureVault.instance) return SecureVault.instance;
    const vault = new SecureVault();
    try {
      await storeSet('__vault_test', 'ok');
      await storeDel('__vault_test');
      vault.initialized = true;
      vault.logger.info('SecureVault initialized');
    } catch (error: any) {
      vault.initialized = true;
      vault.logger.warn('SecureVault initialization degraded: ' + (error?.message || 'unknown error'));
      DebugLog.vaultError('INIT', '__vault_test', error?.message || 'unknown error');
    }
    SecureVault.instance = vault;
    return vault;
  }

  static getInstance(): SecureVault {
    if (!SecureVault.instance) throw new Error('SecureVault not initialized');
    return SecureVault.instance;
  }

  async set(key: string, value: string): Promise<void> {
    if (!this.initialized) throw new Error('Vault not initialized');
    const isSensitive = isSensitiveKey(key);
    try {
      await storeSet(`vu_${key}`, value);
      this.cache.set(key, value);
      DebugLog.vaultSet(key, true, isSensitive ? `[REDACTED ${value.length}ch]` : value.slice(0, 200));
    } catch (error: any) {
      DebugLog.vaultError('SET', key, error?.message || 'unknown error');
      this.logger.error(`Store failed for ${key}: ${error?.message || 'unknown error'}`);
      throw error;
    }
  }

  async get(key: string): Promise<string | null> {
    if (!this.initialized) throw new Error('Vault not initialized');
    if (this.cache.has(key)) {
      const cached = normalizeStoredValue(this.cache.get(key) ?? null);
      if (cached === null) {
        this.cache.delete(key);
      } else {
        DebugLog.vaultGet(key, true, isSensitiveKey(key) ? `[REDACTED ${cached.length}ch]` : cached.slice(0, 80));
        return cached;
      }
    }
    try {
      const value = normalizeStoredValue(await storeGet(`vu_${key}`));
      if (value !== null) this.cache.set(key, value);
      else this.cache.delete(key);
      DebugLog.vaultGet(key, value !== null, value ? (isSensitiveKey(key) ? `[REDACTED ${value.length}ch]` : value.slice(0, 80)) : undefined);
      return value;
    } catch (error: any) {
      DebugLog.vaultError('GET', key, error?.message || 'unknown error');
      this.logger.error(`Retrieve failed for ${key}: ${error?.message || 'unknown error'}`);
      return null;
    }
  }

  async delete(key: string): Promise<void> {
    if (!this.initialized) throw new Error('Vault not initialized');
    try {
      await storeDel(`vu_${key}`);
      this.cache.delete(key);
      DebugLog.vaultDelete(key);
    } catch (error: any) {
      DebugLog.vaultError('DEL', key, error?.message || 'unknown error');
      this.logger.error(`Delete failed for ${key}: ${error?.message || 'unknown error'}`);
      throw error;
    }
  }

  async has(key: string): Promise<boolean> {
    const value = await this.get(key);
    return value !== null;
  }

  clearCache(): void {
    this.cache.clear();
  }
}
