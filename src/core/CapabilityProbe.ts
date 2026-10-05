import { Platform } from 'react-native';
import * as Contacts from 'expo-contacts';
import * as MediaLibrary from 'expo-media-library';
import * as Camera from 'expo-camera';
import * as Location from 'expo-location';
import { Logger } from '../utils/Logger';
import { UltraDevLog } from '../utils/UltraDevLog';

export interface ProbeResult {
  capability: string;
  label: string;
  granted: boolean;
  canRequest: boolean;
  unavailable: boolean;
  note?: string;
}

export type CapabilityProbeResults = Record<string, ProbeResult>;

export class CapabilityProbe {
  private static instance: CapabilityProbe | null = null;
  private results: CapabilityProbeResults = {};
  private probed = false;
  private logger = new Logger('CapabilityProbe');

  static getInstance(): CapabilityProbe {
    if (!CapabilityProbe.instance) {
      CapabilityProbe.instance = new CapabilityProbe();
    }
    return CapabilityProbe.instance;
  }

  async probe(): Promise<CapabilityProbeResults> {
    if (this.probed) return this.results;
    const isNative = Platform.OS !== 'web';
    UltraDevLog.push('SYSTEM', { event: 'capability_probe_start', platform: Platform.OS, isNative });

    const checks: Array<() => Promise<void>> = [
      async () => {
        if (!isNative) {
          this.results.contacts = { capability: 'contacts', label: 'Contacts', granted: false, canRequest: false, unavailable: true, note: 'Not available on web' };
          return;
        }
        try {
          const { status } = await Contacts.getPermissionsAsync();
          this.results.contacts = {
            capability: 'contacts', label: 'Contacts',
            granted: status === 'granted',
            canRequest: status !== 'denied',
            unavailable: false,
          };
        } catch {
          this.results.contacts = { capability: 'contacts', label: 'Contacts', granted: false, canRequest: false, unavailable: true };
        }
      },
      async () => {
        if (!isNative) {
          this.results.sms = { capability: 'sms', label: 'SMS', granted: false, canRequest: false, unavailable: true, note: 'Not available on web' };
          return;
        }
        try {
          const { default: SMS } = await import('expo-sms');
          const avail = await SMS.isAvailableAsync();
          this.results.sms = {
            capability: 'sms', label: 'SMS',
            granted: avail,
            canRequest: false,
            unavailable: !avail,
            note: avail ? undefined : 'SMS not available on this device',
          };
        } catch {
          this.results.sms = { capability: 'sms', label: 'SMS', granted: false, canRequest: false, unavailable: true };
        }
      },
      async () => {
        if (!isNative) {
          this.results.camera = { capability: 'camera', label: 'Camera', granted: false, canRequest: false, unavailable: true, note: 'Not available on web' };
          return;
        }
        try {
          const fn = (Camera as any).getCameraPermissionsAsync ?? (Camera as any).Camera?.getCameraPermissionsAsync;
          if (fn) {
            const { status } = await fn();
            this.results.camera = {
              capability: 'camera', label: 'Camera',
              granted: status === 'granted',
              canRequest: status !== 'denied',
              unavailable: false,
            };
          } else {
            this.results.camera = { capability: 'camera', label: 'Camera', granted: false, canRequest: true, unavailable: false };
          }
        } catch {
          this.results.camera = { capability: 'camera', label: 'Camera', granted: false, canRequest: true, unavailable: false };
        }
      },
      async () => {
        if (!isNative) {
          this.results.location = { capability: 'location', label: 'Location', granted: true, canRequest: true, unavailable: false, note: 'Uses web geolocation' };
          return;
        }
        try {
          const { status } = await Location.getForegroundPermissionsAsync();
          this.results.location = {
            capability: 'location', label: 'Location',
            granted: status === 'granted',
            canRequest: status !== 'denied',
            unavailable: false,
          };
        } catch {
          this.results.location = { capability: 'location', label: 'Location', granted: false, canRequest: true, unavailable: false };
        }
      },
      async () => {
        if (!isNative) {
          this.results.storage = { capability: 'storage', label: 'Storage', granted: false, canRequest: false, unavailable: true, note: 'Not available on web' };
          return;
        }
        try {
          const { status } = await MediaLibrary.getPermissionsAsync();
          this.results.storage = {
            capability: 'storage', label: 'Storage/Media',
            granted: status === 'granted',
            canRequest: status !== 'denied',
            unavailable: false,
          };
        } catch {
          this.results.storage = { capability: 'storage', label: 'Storage/Media', granted: false, canRequest: true, unavailable: false };
        }
      },
      async () => {
        if (!isNative) {
          this.results.accessibility = { capability: 'accessibility', label: 'Accessibility Service', granted: false, canRequest: false, unavailable: true, note: 'Not available on web' };
          return;
        }
        try {
          const AppController = (await import('../native/AppController')).default;
          const enabled = await AppController.isServiceEnabled().catch(() => false);
          this.results.accessibility = {
            capability: 'accessibility', label: 'Accessibility Service',
            granted: enabled,
            canRequest: !enabled,
            unavailable: false,
            note: enabled ? undefined : 'Enable in device Accessibility Settings',
          };
        } catch {
          this.results.accessibility = { capability: 'accessibility', label: 'Accessibility Service', granted: false, canRequest: false, unavailable: true };
        }
      },
    ];

    await Promise.allSettled(checks.map(fn => fn()));
    this.probed = true;
    this.logger.info(`Capability probe complete: ${Object.keys(this.results).length} capabilities checked`);
    const granted = Object.values(this.results).filter(r => r.granted).length;
    const denied = Object.values(this.results).filter(r => !r.granted && !r.unavailable).length;
    const unavailable = Object.values(this.results).filter(r => r.unavailable).length;
    UltraDevLog.push('SYSTEM', { event: 'capability_probe_done', platform: Platform.OS, checked: Object.keys(this.results).length, granted, denied, unavailable });
    return this.results;
  }

  get(capability: string): ProbeResult | null {
    return this.results[capability] ?? null;
  }

  isGranted(capability: string): boolean {
    const r = this.results[capability];
    if (!r) return true;
    return r.granted && !r.unavailable;
  }

  isUnavailable(capability: string): boolean {
    const r = this.results[capability];
    if (!r) return false;
    return r.unavailable;
  }

  canRequest(capability: string): boolean {
    const r = this.results[capability];
    if (!r) return false;
    return r.canRequest && !r.unavailable;
  }

  getAll(): CapabilityProbeResults {
    return { ...this.results };
  }

  getDeniedCapabilities(): ProbeResult[] {
    return Object.values(this.results).filter(r => !r.granted && !r.unavailable);
  }

  getUnavailableCapabilities(): ProbeResult[] {
    return Object.values(this.results).filter(r => r.unavailable);
  }

  reset(): void {
    this.probed = false;
    this.results = {};
  }

  static capabilityToProbeKey(capId: string): string | null {
    const mapping: Record<string, string> = {
      contacts_read: 'contacts',
      sms_send: 'sms',
      camera_capture: 'camera',
      flashlight_toggle: 'camera',
      device_location: 'location',
      media_access: 'storage',
      app_control: 'accessibility',
      app_test: 'accessibility',
      screenshot: 'accessibility',
      notification_read: 'accessibility',
      react_navigate: 'accessibility',
    };
    return mapping[capId] ?? null;
  }
}
