import { PermissionsAndroid, Platform } from 'react-native';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

/**
 * Standard dangerous permissions that can be safely batched in requestMultiple().
 * EXCLUDED intentionally:
 *   ACCESS_BACKGROUND_LOCATION — Android 11+ throws SecurityException if batched with others; requested separately below.
 *   SCHEDULE_EXACT_ALARM       — Android 12+ special app access, not a dangerous permission; not grantable via requestMultiple().
 */
const RUNTIME_PERMISSIONS: Array<{ key: string; perm: string; minApi?: number; maxApi?: number }> = [
  { key: 'READ_CONTACTS',          perm: PermissionsAndroid.PERMISSIONS.READ_CONTACTS },
  { key: 'WRITE_CONTACTS',         perm: PermissionsAndroid.PERMISSIONS.WRITE_CONTACTS },
  { key: 'CALL_PHONE',             perm: PermissionsAndroid.PERMISSIONS.CALL_PHONE },
  { key: 'READ_CALL_LOG',          perm: PermissionsAndroid.PERMISSIONS.READ_CALL_LOG },
  { key: 'SEND_SMS',               perm: PermissionsAndroid.PERMISSIONS.SEND_SMS },
  { key: 'READ_SMS',               perm: PermissionsAndroid.PERMISSIONS.READ_SMS },
  { key: 'RECEIVE_SMS',            perm: PermissionsAndroid.PERMISSIONS.RECEIVE_SMS },
  { key: 'CAMERA',                 perm: PermissionsAndroid.PERMISSIONS.CAMERA },
  { key: 'RECORD_AUDIO',           perm: PermissionsAndroid.PERMISSIONS.RECORD_AUDIO },
  { key: 'ACCESS_FINE_LOCATION',   perm: PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION },
  { key: 'ACCESS_COARSE_LOCATION', perm: PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION },
  { key: 'READ_EXTERNAL_STORAGE',  perm: PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE, maxApi: 32 },
  { key: 'WRITE_EXTERNAL_STORAGE', perm: PermissionsAndroid.PERMISSIONS.WRITE_EXTERNAL_STORAGE, maxApi: 32 },
  { key: 'READ_CALENDAR',          perm: 'android.permission.READ_CALENDAR' },
  { key: 'WRITE_CALENDAR',         perm: 'android.permission.WRITE_CALENDAR' },
  { key: 'READ_PHONE_STATE',       perm: 'android.permission.READ_PHONE_STATE' },
  { key: 'BLUETOOTH_CONNECT',      perm: 'android.permission.BLUETOOTH_CONNECT', minApi: 31 },
  { key: 'BLUETOOTH_SCAN',         perm: 'android.permission.BLUETOOTH_SCAN', minApi: 31 },
  { key: 'POST_NOTIFICATIONS',     perm: 'android.permission.POST_NOTIFICATIONS', minApi: 33 },
  { key: 'READ_MEDIA_IMAGES',      perm: 'android.permission.READ_MEDIA_IMAGES', minApi: 33 },
  { key: 'READ_MEDIA_VIDEO',       perm: 'android.permission.READ_MEDIA_VIDEO', minApi: 33 },
  { key: 'READ_MEDIA_AUDIO',       perm: 'android.permission.READ_MEDIA_AUDIO', minApi: 33 },
];

export class PermissionBroker {
  private granted = new Set<string>();
  private denied = new Set<string>();
  private initialized = false;

  /**
   * Request ALL runtime permissions upfront at first launch.
   * This runs during AgentCore.initialize() — before any capability executes.
   * Permissions that are already granted are skipped silently.
   * Denied permissions are tracked so capabilities can check and degrade gracefully.
   */
  async initialize(): Promise<void> {
    if (Platform.OS !== 'android') return;
    if (this.initialized) return;

    const apiLevel = typeof Platform.Version === 'number'
      ? Platform.Version
      : parseInt(String(Platform.Version), 10) || 26;

    // Filter permissions applicable to this API level
    const applicable = RUNTIME_PERMISSIONS.filter(p => (!p.minApi || apiLevel >= p.minApi) && (!p.maxApi || apiLevel <= p.maxApi));

    // First pass: check what's already granted (instant, no UI)
    const needsRequest: string[] = [];
    for (const { key, perm } of applicable) {
      try {
        const already = await PermissionsAndroid.check(perm as any);
        if (already) {
          this.granted.add(key);
        } else {
          needsRequest.push(perm);
        }
      } catch {
        needsRequest.push(perm);
      }
    }

    DebugLog.systemEvent('PermissionBroker', `Pre-check: ${this.granted.size} already granted, ${needsRequest.length} to request`);

    // Second pass: batch-request everything that's not yet granted
    if (needsRequest.length > 0) {
      try {
        const results = await PermissionsAndroid.requestMultiple(needsRequest as any[]);
        for (const [perm, result] of Object.entries(results)) {
          const entry = applicable.find(p => p.perm === perm);
          const key = entry?.key || perm;
          if (result === PermissionsAndroid.RESULTS.GRANTED) {
            this.granted.add(key);
            DebugLog.permissionStatus(key, 'granted');
          } else {
            this.denied.add(key);
            DebugLog.permissionStatus(key, result);
          }
        }
      } catch (batchErr: any) {
        DebugLog.error('PermissionBroker', `Batch request failed: ${batchErr.message}`);
        // Mark all unrequested as denied
        for (const perm of needsRequest) {
          const entry = applicable.find(p => p.perm === perm);
          if (entry && !this.granted.has(entry.key)) {
            this.denied.add(entry.key);
          }
        }
      }
    }

    // Third pass: ACCESS_BACKGROUND_LOCATION — MUST be a solo requestMultiple() call.
    // Android 11+ throws SecurityException if it shares a batch with any other permission.
    // Only request it if foreground location was just granted.
    const hasForeground = this.granted.has('ACCESS_FINE_LOCATION') || this.granted.has('ACCESS_COARSE_LOCATION');
    if (hasForeground) {
      const bgPerm = PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION;
      const alreadyBg = await PermissionsAndroid.check(bgPerm as any).catch(() => false);
      if (alreadyBg) {
        this.granted.add('ACCESS_BACKGROUND_LOCATION');
        DebugLog.permissionStatus('ACCESS_BACKGROUND_LOCATION', 'already granted');
      } else {
        try {
          const bgResults = await PermissionsAndroid.requestMultiple([bgPerm] as any[]);
          const bgResult = bgResults[bgPerm];
          if (bgResult === PermissionsAndroid.RESULTS.GRANTED) {
            this.granted.add('ACCESS_BACKGROUND_LOCATION');
            DebugLog.permissionStatus('ACCESS_BACKGROUND_LOCATION', 'granted');
          } else {
            this.denied.add('ACCESS_BACKGROUND_LOCATION');
            DebugLog.permissionStatus('ACCESS_BACKGROUND_LOCATION', bgResult || 'denied');
          }
        } catch (bgErr: any) {
          this.denied.add('ACCESS_BACKGROUND_LOCATION');
          DebugLog.error('PermissionBroker', `Background location solo request failed: ${bgErr.message}`);
        }
      }
    } else {
      this.denied.add('ACCESS_BACKGROUND_LOCATION');
      DebugLog.permissionStatus('ACCESS_BACKGROUND_LOCATION', 'skipped — no foreground location');
    }

    this.initialized = true;
    DebugLog.systemEvent('PermissionBroker', `Complete: ${this.granted.size} granted, ${this.denied.size} denied`);
  }

  getMissing(permissions: string[]): string[] {
    if (Platform.OS !== 'android') return [];
    return permissions.filter(p => !this.granted.has(p));
  }

  /**
   * Re-request specific permissions if needed (e.g., user re-enabled in settings).
   * This is a fallback — primary path is upfront init.
   */
  async requestAll(permissions: string[]): Promise<string[]> {
    if (Platform.OS !== 'android') return [];
    const failed: string[] = [];
    const toRequest: string[] = [];
    for (const key of permissions) {
      if (this.granted.has(key)) continue;
      const entry = RUNTIME_PERMISSIONS.find(p => p.key === key);
      if (entry) toRequest.push(entry.perm);
    }
    if (toRequest.length === 0) return [];
    try {
      const results = await PermissionsAndroid.requestMultiple(toRequest as any[]);
      for (const [perm, result] of Object.entries(results)) {
        const entry = RUNTIME_PERMISSIONS.find(p => p.perm === perm);
        const key = entry?.key || perm;
        if (result === PermissionsAndroid.RESULTS.GRANTED) {
          this.granted.add(key);
          this.denied.delete(key);
        } else {
          this.denied.add(key);
          this.granted.delete(key);
          failed.push(key);
        }
      }
    } catch (e: any) {
      DebugLog.error('PermissionBroker', `requestAll failed: ${e.message}`);
      failed.push(...permissions);
    }
    return failed;
  }

  isGranted(permission: string): boolean {
    return this.granted.has(permission);
  }

  allGranted(permissions: string[]): boolean {
    return permissions.every(p => this.isGranted(p));
  }

  getStatusReport(): string {
    const g = [...this.granted].slice(0, 15).join(', ') || 'none';
    const d = [...this.denied].slice(0, 10).join(', ') || 'none';
    return `Granted: ${g}. Denied: ${d}`;
  }
}
