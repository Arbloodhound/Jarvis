import { NativeModules, Platform } from 'react-native';
import { UltraDevLog } from '../utils/UltraDevLog';

export interface UINode {
  className: string;
  text: string | null;
  contentDescription: string | null;
  bounds: { left: number; top: number; right: number; bottom: number };
  clickable: boolean;
  scrollable: boolean;
  children: UINode[];
}

export interface AppControllerInterface {
  getScreenContent(): Promise<UINode>;
  getScreenContentFlat(): Promise<string>;
  performClick(nodeSelector: string): Promise<boolean>;
  performTap(x: number, y: number): Promise<boolean>;
  performSwipe(x1: number, y1: number, x2: number, y2: number, durationMs: number): Promise<boolean>;
  performScroll(direction: 'up' | 'down' | 'left' | 'right' | string): Promise<boolean>;
  performText(nodeSelector: string, text: string): Promise<boolean>;
  performBack(): Promise<boolean>;
  performHome(): Promise<boolean>;
  getActivePackage(): Promise<string>;
  isServiceEnabled(): Promise<boolean>;
  openAccessibilitySettings(): Promise<void>;
  allowPackage(pkg: string): Promise<boolean>;
  revokePackage(pkg: string): Promise<boolean>;
  waitForUiChange(timeoutMs: number): Promise<boolean>;
  performQuickSettings(): Promise<boolean>;
  takeScreenshot(): Promise<boolean>;
  toggleQuickSetting(tileLabel: string): Promise<boolean>;
  setSecureSetting(namespace: string, key: string, value: number): Promise<boolean>;
  getSecureSetting(namespace: string, key: string): Promise<number>;
  setVolume(streamType: string, level: number): Promise<number>;
  getVolume(streamType: string): Promise<number>;
  adjustVolume(direction: 'up' | 'down'): Promise<number>;
  blockPackage(pkg: string): Promise<boolean>;
  unblockPackage(pkg: string): Promise<boolean>;
  getBlockedPackages(): Promise<string[]>;
  getSystemStateSnapshot(): Promise<string>;
  drainAccessibilityLogs(): Promise<string[]>;
  readCrashLog(): Promise<string>;
  clearCrashLog(): Promise<boolean>;
  heartbeatPing(): Promise<{ alive: boolean; foregroundPackage: string; timestamp: number }>;
  startBackgroundService(): Promise<boolean>;
  moveTaskToBack(): Promise<boolean>;
  performImeAction(): Promise<boolean>;
  exec?(cmd: string, args?: string): Promise<string>;
  isAvailable(): boolean;
}

const emptyNode: UINode = {
  className: '',
  text: null,
  contentDescription: null,
  bounds: { left: 0, top: 0, right: 0, bottom: 0 },
  clickable: false,
  scrollable: false,
  children: [],
};

const noopController: AppControllerInterface = {
  getScreenContent: async () => emptyNode,
  getScreenContentFlat: async () => '[]',
  performClick: async () => false,
  performTap: async () => false,
  performSwipe: async () => false,
  performScroll: async () => false,
  performText: async () => false,
  performBack: async () => false,
  performHome: async () => false,
  getActivePackage: async () => '',
  isServiceEnabled: async () => false,
  openAccessibilitySettings: async () => {},
  allowPackage: async () => true,
  revokePackage: async () => true,
  waitForUiChange: async () => false,
  performQuickSettings: async () => false,
  takeScreenshot: async () => false,
  toggleQuickSetting: async () => false,
  setSecureSetting: async () => false,
  getSecureSetting: async () => -1,
  setVolume: async () => 0,
  getVolume: async () => 0,
  adjustVolume: async () => 0,
  blockPackage: async () => false,
  unblockPackage: async () => false,
  getBlockedPackages: async () => [],
  getSystemStateSnapshot: async () => '{"error":"mock"}',
  drainAccessibilityLogs: async () => [],
  readCrashLog: async () => '',
  clearCrashLog: async () => true,
  heartbeatPing: async () => ({ alive: false, foregroundPackage: 'mock', timestamp: 0 }),
  startBackgroundService: async () => false,
  moveTaskToBack: async () => false,
  performImeAction: async () => false,
  isAvailable: () => false,
};

function createNativeController(): AppControllerInterface {
  const native = NativeModules.AppController;
  if (!native) {
    UltraDevLog.push('SYSTEM', { event: 'app_controller_init', available: false, reason: 'no_native_module', platform: Platform.OS });
    return noopController;
  }
  UltraDevLog.push('SYSTEM', { event: 'app_controller_init', available: true, platform: Platform.OS });

  return {
    getScreenContent: async () => {
      try {
        const json = await native.getScreenContent();
        const parsed = JSON.parse(json) as UINode;
        UltraDevLog.push('SYSTEM', { event: 'native_call_ok', method: 'getScreenContent' });
        return parsed;
      } catch (error: any) {
        UltraDevLog.push('SYSTEM', { event: 'native_call_fail', method: 'getScreenContent', error: error?.message });
        return emptyNode;
      }
    },
    getScreenContentFlat: async () => {
      if (!native.getScreenContentFlat) {
        UltraDevLog.push('SYSTEM', { event: 'native_call_noop', method: 'getScreenContentFlat', reason: 'method_missing' });
        return '[]';
      }
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'getScreenContentFlat' });
      return native.getScreenContentFlat();
    },
    performClick: (nodeSelector: string) => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'performClick', selectorLen: nodeSelector.length });
      return native.performClick(nodeSelector);
    },
    performTap: (x: number, y: number) => {
      if (!native.performTap) {
        UltraDevLog.push('SYSTEM', { event: 'native_call_noop', method: 'performTap', reason: 'method_missing' });
        return Promise.resolve(false);
      }
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'performTap', x, y });
      return native.performTap(x, y);
    },
    performSwipe: (x1: number, y1: number, x2: number, y2: number, durationMs: number) => {
      if (!native.performSwipe) {
        UltraDevLog.push('SYSTEM', { event: 'native_call_noop', method: 'performSwipe', reason: 'method_missing' });
        return Promise.resolve(false);
      }
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'performSwipe', x1, y1, x2, y2, durationMs });
      return native.performSwipe(x1, y1, x2, y2, durationMs);
    },
    performScroll: (direction: string) => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'performScroll', direction });
      return native.performScroll(direction);
    },
    performText: (nodeSelector: string, text: string) => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'performText', selectorLen: nodeSelector.length, textLen: text.length });
      return native.performText(nodeSelector, text);
    },
    performBack: () => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'performBack' });
      return native.performBack();
    },
    performHome: () => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'performHome' });
      return native.performHome();
    },
    getActivePackage: () => native.getActivePackage(),
    isServiceEnabled: () => native.isServiceEnabled(),
    openAccessibilitySettings: () => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'openAccessibilitySettings' });
      return native.openAccessibilitySettings();
    },
    allowPackage: (pkg: string) => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'allowPackage', pkg });
      return native.allowPackage(pkg);
    },
    revokePackage: (pkg: string) => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'revokePackage', pkg });
      return native.revokePackage(pkg);
    },
    waitForUiChange: (timeoutMs: number) => {
      if (!native.waitForUiChange) {
        UltraDevLog.push('SYSTEM', { event: 'native_call_noop', method: 'waitForUiChange', reason: 'method_missing' });
        return Promise.resolve(false);
      }
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'waitForUiChange', timeoutMs });
      return native.waitForUiChange(timeoutMs);
    },
    performQuickSettings: () => native.performQuickSettings ? native.performQuickSettings() : Promise.resolve(false),
    takeScreenshot: () => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'takeScreenshot', available: !!native.takeScreenshot });
      return native.takeScreenshot ? native.takeScreenshot() : Promise.resolve(false);
    },
    toggleQuickSetting: (tileLabel: string) => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'toggleQuickSetting', tileLabel });
      return native.toggleQuickSetting ? native.toggleQuickSetting(tileLabel) : Promise.resolve(false);
    },
    setSecureSetting: (namespace: string, key: string, value: number) => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'setSecureSetting', namespace, key, value });
      return native.setSecureSetting ? native.setSecureSetting(namespace, key, value) : Promise.resolve(false);
    },
    getSecureSetting: (namespace: string, key: string) => {
      return native.getSecureSetting ? native.getSecureSetting(namespace, key) : Promise.resolve(-1);
    },
    setVolume: (streamType: string, level: number) => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'setVolume', streamType, level });
      return native.setVolume ? native.setVolume(streamType, level) : Promise.resolve(0);
    },
    getVolume: (streamType: string) => native.getVolume ? native.getVolume(streamType) : Promise.resolve(0),
    adjustVolume: (direction: 'up' | 'down') => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'adjustVolume', direction });
      return native.adjustVolume ? native.adjustVolume(direction) : Promise.resolve(0);
    },
    blockPackage: (pkg: string) => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'blockPackage', pkg });
      return native.blockPackage ? native.blockPackage(pkg) : Promise.resolve(false);
    },
    unblockPackage: (pkg: string) => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'unblockPackage', pkg });
      return native.unblockPackage ? native.unblockPackage(pkg) : Promise.resolve(false);
    },
    getBlockedPackages: () => native.getBlockedPackages ? native.getBlockedPackages() : Promise.resolve([]),
    getSystemStateSnapshot: () => {
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'getSystemStateSnapshot' });
      return native.getSystemStateSnapshot ? native.getSystemStateSnapshot() : Promise.resolve('{"error":"not_available"}');
    },
    drainAccessibilityLogs: () => native.drainAccessibilityLogs ? native.drainAccessibilityLogs() : Promise.resolve([]),
    readCrashLog: () => native.readCrashLog ? native.readCrashLog() : Promise.resolve(''),
    clearCrashLog: () => native.clearCrashLog ? native.clearCrashLog() : Promise.resolve(true),
    heartbeatPing: () => native.heartbeatPing
      ? native.heartbeatPing()
      : Promise.resolve({ alive: false, foregroundPackage: 'unknown', timestamp: 0 }),
    startBackgroundService: () => {
      const bridge = NativeModules.AppController;
      if (!bridge?.startBackgroundService) {
        UltraDevLog.push('SYSTEM', { event: 'native_call_noop', method: 'startBackgroundService', reason: 'method_missing' });
        return Promise.resolve(false);
      }
      UltraDevLog.push('SYSTEM', { event: 'native_call_start', method: 'startBackgroundService' });
      return bridge.startBackgroundService();
    },
    performImeAction: async () => {
      if (!native.performImeAction) return false;
      try {
        return await native.performImeAction();
      } catch {
        return false;
      }
    },
    moveTaskToBack: async () => {
      console.warn('[NATIVE] moveTaskToBack: typeof =', typeof native.moveTaskToBack);
      if (!native.moveTaskToBack) {
        console.warn('[NATIVE] moveTaskToBack: NOT FOUND');
        return false;
      }
      try {
        const result = await native.moveTaskToBack();
        console.warn('[NATIVE] moveTaskToBack: resolved =', result);
        return result;
      } catch (e: any) {
        console.warn('[NATIVE] moveTaskToBack: rejected =', e.message);
        return false;
      }
    },
    isAvailable: () => true,
  };
}

const AppController: AppControllerInterface =
  Platform.OS !== 'web' ? createNativeController() : (() => {
    UltraDevLog.push('SYSTEM', { event: 'app_controller_init', available: false, reason: 'web_platform', platform: Platform.OS });
    return noopController;
  })();

export default AppController;

export async function performTap(x: number, y: number): Promise<boolean> {
  if (Platform.OS !== 'android') return false;
  const mod = NativeModules.AppController;
  if (!mod?.performTap) return false;
  return mod.performTap(x, y);
}

export async function performSwipe(
  x1: number, y1: number,
  x2: number, y2: number,
  durationMs: number = 350
): Promise<boolean> {
  if (Platform.OS !== 'android') return false;
  const mod = NativeModules.AppController;
  if (!mod?.performSwipe) return false;
  return mod.performSwipe(x1, y1, x2, y2, durationMs);
}

export async function getScreenContentFlat(): Promise<string> {
  if (Platform.OS !== 'android') return '[]';
  const mod = NativeModules.AppController;
  if (!mod?.getScreenContentFlat) return '[]';
  return mod.getScreenContentFlat();
}

export async function waitForUiChange(timeoutMs: number = 3000): Promise<boolean> {
  if (Platform.OS !== 'android') return false;
  const mod = NativeModules.AppController;
  if (!mod?.waitForUiChange) return false;
  return mod.waitForUiChange(timeoutMs);
}
