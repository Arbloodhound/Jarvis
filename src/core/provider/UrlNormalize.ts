// URL normalization — always store base URL, never operation endpoints

import { UltraDevLog } from '../../utils/UltraDevLog';

const OPERATION_SUFFIXES = [
  '/chat/completions',
  '/responses',
  '/completions',
  '/models',
  '/images/generations',
  '/image/generate',
  '/audio/speech',
  '/audio/transcriptions',
  '/video/generations',
  '/video/queue',
  '/video/retrieve',
  '/embeddings',
];

export interface NormalizeResult {
  ok: boolean;
  url: string;
  error?: string;
  strippedSuffix?: string;
}

export function normalizeProviderUrl(raw: string): NormalizeResult {
  const trimmed = (raw || '').trim();
  if (!trimmed) {
    UltraDevLog.push('SYSTEM', { event: 'url_normalize_fail', raw: '[empty]', reason: 'empty' });
    return { ok: false, url: '', error: 'URL is empty.' };
  }

  let url = trimmed;

  if (!/^https?:\/\//i.test(url)) {
    url = 'https://' + url;
  }

  let strippedSuffix: string | undefined;
  for (const suffix of OPERATION_SUFFIXES) {
    if (url.toLowerCase().endsWith(suffix.toLowerCase())) {
      url = url.slice(0, url.length - suffix.length);
      strippedSuffix = suffix;
      break;
    }
  }

  url = url.replace(/\/+$/, '');

  try {
    new URL(url);
  } catch {
    UltraDevLog.push('SYSTEM', { event: 'url_normalize_fail', raw: trimmed, normalized: url, reason: 'invalid_url' });
    return { ok: false, url: '', error: `Invalid URL: ${trimmed}` };
  }

  UltraDevLog.push('SYSTEM', { event: 'url_normalize_ok', normalized: url, strippedSuffix: strippedSuffix || null });
  return { ok: true, url, strippedSuffix };
}

export function isValidProviderUrl(url: string): boolean {
  return normalizeProviderUrl(url).ok;
}

export function buildEndpointUrl(baseUrl: string, path: string): string {
  const base = baseUrl.replace(/\/+$/, '');
  const p = path.startsWith('/') ? path : '/' + path;
  return base + p;
}
