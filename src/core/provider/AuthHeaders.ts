// Auth header builder — supports bearer, api_key_header, basic, custom_header, none

import { UltraDevLog } from '../../utils/UltraDevLog';
import type { AuthMode } from '../../types/provider';

export interface AuthHeaderInput {
  authMode: AuthMode;
  apiKey?: string | null;
  password?: string | null;
  customAuthHeaderName?: string;
  customAuthHeaderPrefix?: string;
}

export function buildAuthHeaders(input: AuthHeaderInput): Record<string, string> {
  const { authMode, apiKey, password, customAuthHeaderName, customAuthHeaderPrefix } = input;

  switch (authMode) {
    case 'bearer': {
      if (!apiKey) {
        UltraDevLog.push('SYSTEM', { event: 'auth_header_missing_material', authMode, reason: 'no_api_key' });
        return {};
      }
      UltraDevLog.push('SYSTEM', { event: 'auth_header_built', authMode, headerName: 'Authorization', prefix: 'Bearer' });
      return { Authorization: `Bearer ${apiKey}` };
    }
    case 'api_key_header': {
      const headerName = customAuthHeaderName || 'X-API-Key';
      if (!apiKey) {
        UltraDevLog.push('SYSTEM', { event: 'auth_header_missing_material', authMode, reason: 'no_api_key', headerName });
        return {};
      }
      UltraDevLog.push('SYSTEM', { event: 'auth_header_built', authMode, headerName, prefix: 'none' });
      return { [headerName]: apiKey };
    }
    case 'basic': {
      if (!apiKey) {
        UltraDevLog.push('SYSTEM', { event: 'auth_header_missing_material', authMode, reason: 'no_api_key' });
        return {};
      }
      const hasPassword = !!password;
      UltraDevLog.push('SYSTEM', { event: 'auth_header_built', authMode, headerName: 'Authorization', prefix: 'Basic', hasPassword });
      const credentials = password ? `${apiKey}:${password}` : apiKey;
      const encoded = btoa(credentials);
      return { Authorization: `Basic ${encoded}` };
    }
    case 'custom_header': {
      if (!customAuthHeaderName || !apiKey) {
        UltraDevLog.push('SYSTEM', { event: 'auth_header_missing_material', authMode, reason: !customAuthHeaderName ? 'no_header_name' : 'no_api_key' });
        return {};
      }
      const prefix = customAuthHeaderPrefix ? customAuthHeaderPrefix + ' ' : '';
      UltraDevLog.push('SYSTEM', { event: 'auth_header_built', authMode, headerName: customAuthHeaderName, prefix: customAuthHeaderPrefix || 'none' });
      return { [customAuthHeaderName]: `${prefix}${apiKey}` };
    }
    case 'none':
    default:
      UltraDevLog.push('SYSTEM', { event: 'auth_header_built', authMode: authMode || 'none', headerName: 'none', prefix: 'none' });
      return {};
  }
}

export function redactAuthHeaders(headers: Record<string, string>): Record<string, string> {
  const redacted: Record<string, string> = {};
  const sensitiveNames = new Set(['authorization', 'x-api-key', 'api-key', 'x-auth-token']);
  for (const [k, v] of Object.entries(headers)) {
    if (sensitiveNames.has(k.toLowerCase())) {
      redacted[k] = v.length > 8 ? `${v.slice(0, 4)}...${v.slice(-4)}` : '[REDACTED]';
    } else {
      redacted[k] = v;
    }
  }
  return redacted;
}
