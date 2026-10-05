import { Platform, AppState } from 'react-native';
import * as Battery from 'expo-battery';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

export type UserActivity = 'sleeping' | 'commuting' | 'at_work' | 'at_home' | 'exercising' | 'socializing' | 'browsing' | 'unknown';

export interface SignalSnapshot {
  timestamp: number; batteryLevel: number; batteryCharging: boolean; isPluggedIn: boolean;
  screenOn: boolean; hourOfDay: number; dayOfWeek: number;
  wifiSSID: string | null; bluetoothDevices: string[]; foregroundApp: string; inferredActivity: UserActivity;
}

export interface BehaviorPattern {
  id: string; type: 'time_pattern' | 'location_pattern' | 'app_pattern' | 'communication_pattern';
  description: string; confidence: number; occurrences: number; lastSeen: number; data: Record<string, any>;
}

const isNative = Platform.OS !== 'web';
const SIG_HIST_KEY = 'device_signal_history';
const SIG_PAT_KEY = 'device_signal_patterns';
const SIG_WIFI_KEY = 'device_wifi_map';
const SIG_BT_KEY = 'device_bt_map';

export class DeviceSignals {
  private signalHistory: SignalSnapshot[] = [];
  private patterns: BehaviorPattern[] = [];
  private static readonly MAX_HISTORY = 1000;
  private static readonly PATTERN_MIN_OCC = 3;
  private knownWifi: Map<string, string> = new Map();
  private knownBluetooth: Map<string, string> = new Map();

  constructor() {}

  async initialize(): Promise<void> {
    try {
      const [histRaw, patRaw, wifiRaw, btRaw] = await Promise.all([
        AsyncStorage.getItem(SIG_HIST_KEY), AsyncStorage.getItem(SIG_PAT_KEY),
        AsyncStorage.getItem(SIG_WIFI_KEY), AsyncStorage.getItem(SIG_BT_KEY),
      ]);
      if (histRaw) { const loaded = JSON.parse(histRaw) as SignalSnapshot[]; const cutoff = Date.now() - 86_400_000; this.signalHistory = loaded.filter(s => s.timestamp > cutoff); }
      if (patRaw) this.patterns = JSON.parse(patRaw);
      if (wifiRaw) this.knownWifi = new Map(JSON.parse(wifiRaw));
      if (btRaw) this.knownBluetooth = new Map(JSON.parse(btRaw));
      DebugLog.push('SIGNAL_READ' as any, { event: 'initialized', historySize: this.signalHistory.length, patternCount: this.patterns.length });
    } catch (e: any) { DebugLog.error('DeviceSignals', `Init failed: ${e.message}`); }
  }

  async read(): Promise<SignalSnapshot> {
    const now = new Date();
    const snap: SignalSnapshot = { timestamp: Date.now(), batteryLevel: -1, batteryCharging: false, isPluggedIn: false, screenOn: AppState.currentState === 'active', hourOfDay: now.getHours(), dayOfWeek: now.getDay(), wifiSSID: null, bluetoothDevices: [], foregroundApp: '', inferredActivity: 'unknown' };
    try { const [level, state] = await Promise.all([Battery.getBatteryLevelAsync(), Battery.getBatteryStateAsync()]); snap.batteryLevel = Math.round(level * 100); snap.batteryCharging = state === Battery.BatteryState.CHARGING || state === Battery.BatteryState.FULL; snap.isPluggedIn = snap.batteryCharging; } catch {}
    if (isNative) {
      try { const AN = (await import('../native/AgentNative')).default; if ((AN as any).getWifiSSID) snap.wifiSSID = await (AN as any).getWifiSSID(); if ((AN as any).getConnectedBluetoothDevices) snap.bluetoothDevices = await (AN as any).getConnectedBluetoothDevices(); } catch {}
      try { snap.foregroundApp = await (await import('../native/AppController')).default.getActivePackage(); } catch {}
    }
    snap.inferredActivity = this.inferActivity(snap);
    this.signalHistory.push(snap);
    if (this.signalHistory.length > DeviceSignals.MAX_HISTORY) this.signalHistory = this.signalHistory.slice(-DeviceSignals.MAX_HISTORY);
    // Persist on first snapshot (warm-restart survivability), then every 5th to keep cadence
    // tight enough for short sessions while avoiding excessive I/O on long sessions.
    const len = this.signalHistory.length;
    if (len === 1 || len % 5 === 0) this.persist().catch(() => {});
    DebugLog.push('SIGNAL_READ' as any, { event: 'read', activity: snap.inferredActivity, battery: snap.batteryLevel, hasWifi: !!snap.wifiSSID, btDevices: snap.bluetoothDevices.length });
    return snap;
  }

  private inferActivity(snap: SignalSnapshot): UserActivity {
    const hour = snap.hourOfDay;
    if ((hour >= 23 || hour < 6) && snap.isPluggedIn && !snap.screenOn) return 'sleeping';
    if (snap.bluetoothDevices.some(d => { const l = this.knownBluetooth.get(d.toLowerCase()); return l === 'car' || /car|auto|vehicle/i.test(d); })) return 'commuting';
    if (snap.wifiSSID) { const l = this.knownWifi.get(snap.wifiSSID.toLowerCase()); if (l === 'work') return 'at_work'; if (l === 'home') return 'at_home'; }
    const fitness = ['com.strava', 'com.nike.plusgps', 'com.google.android.apps.fitness', 'com.samsung.android.samsunghealth'];
    if (fitness.includes(snap.foregroundApp)) return 'exercising';
    if (snap.isPluggedIn && (hour >= 18 || hour < 8)) return 'at_home';
    if (hour >= 9 && hour <= 17 && snap.dayOfWeek >= 1 && snap.dayOfWeek <= 5) return 'at_work';
    return 'unknown';
  }

  learnWifi(ssid: string, label: string): void { this.knownWifi.set(ssid.toLowerCase(), label.toLowerCase()); this.persist().catch(() => {}); }
  learnBluetooth(deviceName: string, label: string): void { this.knownBluetooth.set(deviceName.toLowerCase(), label.toLowerCase()); this.persist().catch(() => {}); }

  detectPatterns(): BehaviorPattern[] {
    if (this.signalHistory.length < 20) return this.patterns;
    const newPatterns: BehaviorPattern[] = [];
    const hourBuckets: Map<number, Map<UserActivity, number>> = new Map();
    for (const snap of this.signalHistory) {
      if (!hourBuckets.has(snap.hourOfDay)) hourBuckets.set(snap.hourOfDay, new Map());
      const b = hourBuckets.get(snap.hourOfDay)!;
      b.set(snap.inferredActivity, (b.get(snap.inferredActivity) || 0) + 1);
    }
    for (const [hour, activities] of hourBuckets) {
      const total = Array.from(activities.values()).reduce((a, b) => a + b, 0);
      for (const [activity, count] of activities) {
        if (count >= DeviceSignals.PATTERN_MIN_OCC && count / total > 0.5)
          newPatterns.push({ id: `time_${hour}_${activity}`, type: 'time_pattern', description: `Usually ${activity} at ${hour}:00`, confidence: count / total, occurrences: count, lastSeen: Date.now(), data: { hour, activity } });
      }
    }
    this.patterns = newPatterns;
    DebugLog.push('SIGNAL_PATTERN' as any, { event: 'detected', count: newPatterns.length, top: newPatterns[0]?.description });
    this.persist().catch(() => {});
    return newPatterns;
  }

  getLatest(): SignalSnapshot | null { return this.signalHistory.length > 0 ? this.signalHistory[this.signalHistory.length - 1] : null; }
  getPatterns(): BehaviorPattern[] { return this.patterns; }
  getHistory(sinceMs?: number): SignalSnapshot[] { if (!sinceMs) return this.signalHistory; return this.signalHistory.filter(s => s.timestamp >= sinceMs); }

  async getContextString(): Promise<string> {
    const snap = await this.read();
    const lines = [`[SIGNALS] ${new Date(snap.timestamp).toLocaleTimeString()}`, `  Activity: ${snap.inferredActivity}`, `  Battery: ${snap.batteryLevel}%${snap.batteryCharging ? ' charging' : ''}`];
    if (snap.wifiSSID) { const l = this.knownWifi.get(snap.wifiSSID.toLowerCase()); lines.push(`  WiFi: ${snap.wifiSSID}${l ? ` (${l})` : ''}`); }
    if (snap.bluetoothDevices.length > 0) lines.push(`  BT: ${snap.bluetoothDevices.join(', ')}`);
    if (snap.foregroundApp) lines.push(`  FG: ${snap.foregroundApp}`);
    return lines.join('\n');
  }

  async persist(): Promise<void> {
    const t0 = Date.now();
    DebugLog.push('SIGNAL_READ' as any, { event: 'persist_attempt', historySize: this.signalHistory.length, patternCount: this.patterns.length });
    try {
      const trimmed = this.signalHistory.slice(-500);
      await Promise.all([
        AsyncStorage.setItem(SIG_HIST_KEY, JSON.stringify(trimmed)),
        AsyncStorage.setItem(SIG_PAT_KEY, JSON.stringify(this.patterns)),
        AsyncStorage.setItem(SIG_WIFI_KEY, JSON.stringify(Array.from(this.knownWifi.entries()))),
        AsyncStorage.setItem(SIG_BT_KEY, JSON.stringify(Array.from(this.knownBluetooth.entries()))),
      ]);
      DebugLog.push('SIGNAL_READ' as any, { event: 'persist_ok', writtenSnapshots: trimmed.length, durationMs: Date.now() - t0 });
    } catch (e: any) {
      DebugLog.error('DeviceSignals', `Persist failed: ${e.message}`);
      DebugLog.push('SIGNAL_READ' as any, { event: 'persist_fail', error: e.message, durationMs: Date.now() - t0 });
    }
  }
}
