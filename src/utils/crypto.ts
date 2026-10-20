import { Platform } from 'react-native';
import { UltraDevLog } from './UltraDevLog';

function simpleHash(input: string): string {
  let hash = 0;
  for (let i = 0; i < input.length; i++) {
    const char = input.charCodeAt(i);
    hash = ((hash << 5) - hash + char) | 0;
  }
  const h1 = (hash >>> 0).toString(16).padStart(8, '0');

  let hash2 = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    hash2 ^= input.charCodeAt(i);
    hash2 = (hash2 * 0x01000193) | 0;
  }
  const h2 = (hash2 >>> 0).toString(16).padStart(8, '0');

  let hash3 = 0;
  for (let i = 0; i < input.length; i++) {
    hash3 = input.charCodeAt(i) + ((hash3 << 6) + (hash3 << 16) - hash3);
  }
  const h3 = (hash3 >>> 0).toString(16).padStart(8, '0');

  let hash4 = 0x5bd1e995;
  for (let i = 0; i < input.length; i++) {
    hash4 = ((hash4 << 5) + hash4) + input.charCodeAt(i);
  }
  const h4 = (hash4 >>> 0).toString(16).padStart(8, '0');

  return h1 + h2 + h3 + h4 + h1 + h2 + h3 + h4;
}

let _cryptoModule: any = null;
let _cryptoLoaded = false;

async function loadCryptoModule(): Promise<any> {
  if (_cryptoLoaded) return _cryptoModule;
  _cryptoLoaded = true;
  if (Platform.OS !== 'web') {
    try {
      _cryptoModule = require('expo-crypto');
      UltraDevLog.push('SYSTEM', { event: 'crypto_module_loaded', source: 'expo-crypto', platform: Platform.OS });
    } catch {
      _cryptoModule = null;
      UltraDevLog.push('SYSTEM', { event: 'crypto_module_load_fail', source: 'expo-crypto', fallback: 'simpleHash', platform: Platform.OS });
    }
  } else {
    UltraDevLog.push('SYSTEM', { event: 'crypto_module_skip', reason: 'web_platform', fallback: 'simpleHash' });
  }
  return _cryptoModule;
}

export function createHash(input: string): string {
  return simpleHash(input);
}

export async function createHashAsync(input: string): Promise<string> {
  const crypto = await loadCryptoModule();
  if (crypto && crypto.digestStringAsync) {
    try {
      const digest = await crypto.digestStringAsync(
        crypto.CryptoDigestAlgorithm.SHA256,
        input
      );
      UltraDevLog.push('SYSTEM', { event: 'crypto_hash_ok', algorithm: 'SHA256', source: 'expo-crypto', inputLen: input.length });
      return digest;
    } catch (err: any) {
      UltraDevLog.push('SYSTEM', { event: 'crypto_hash_fail', algorithm: 'SHA256', error: err?.message, fallback: 'simpleHash' });
      return simpleHash(input);
    }
  }
  UltraDevLog.push('SYSTEM', { event: 'crypto_hash_ok', algorithm: 'simpleHash', source: 'fallback', inputLen: input.length });
  return simpleHash(input);
}
