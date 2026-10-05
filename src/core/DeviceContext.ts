import { Platform } from 'react-native';
import * as Contacts from 'expo-contacts';
import * as Location from 'expo-location';
import * as Battery from 'expo-battery';
import DeviceInfo from 'react-native-device-info';
import AppController from '../native/AppController';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

const isNative = Platform.OS !== 'web';

export interface CalendarEvent {
  id: string;
  title: string;
  startDate: number;
  endDate: number;
  allDay: boolean;
  location: string;
  description: string;
  calendar: string;
}

export interface CallLogEntry {
  number: string;
  name: string;
  type: 'incoming' | 'outgoing' | 'missed';
  date: number;
  duration: number;
}

export interface SmsEntry {
  address: string;
  body: string;
  date: number;
  direction: 'sent' | 'received';
}

export interface ContactEntry {
  name: string;
  phones: Array<{ number: string; label: string }>;
  emails: string[];
}

export interface LocationData {
  latitude: number;
  longitude: number;
  accuracy: number;
  altitude: number | null;
  timestamp: number;
}

export interface BatteryData {
  level: number;
  state: string;
  lowPower: boolean;
}

export interface SystemData {
  model: string;
  osVersion: string;
  ramUsedMB: number;
  ramTotalMB: number;
  storageFreeGB: number;
  storageTotalGB: number;
}

export interface ScreenData {
  activePackage: string;
  nodeCount: number;
  clickableCount: number;
  topLabels: string[];
  rawNodes: Array<{
    i: number; t: string; d: string;
    c: boolean; e: boolean; s: boolean;
    x: number; y: number;
  }>;
}

export interface InstalledApp {
  packageName: string;
  appName: string;
}

export interface DeviceSnapshot {
  timestamp: number;
  calendar: CalendarEvent[];
  callLog: CallLogEntry[];
  sms: SmsEntry[];
  contacts: ContactEntry[];
  location: LocationData | null;
  battery: BatteryData;
  system: SystemData;
  screen: ScreenData | null;
  installedApps: InstalledApp[];
  errors: string[];
}

export class DeviceContext {
  private static lastSnapshot: DeviceSnapshot | null = null;
  private static lastSnapshotTime = 0;
  private static readonly CACHE_TTL_MS = 10_000;

  static async gather(options?: {
    includeCalendar?: boolean;
    includeCallLog?: boolean;
    includeSms?: boolean;
    includeContacts?: boolean;
    includeLocation?: boolean;
    includeScreen?: boolean;
    smsLimit?: number;
    calendarDaysAhead?: number;
    calendarDaysBehind?: number;
    callLogLimit?: number;
  }): Promise<DeviceSnapshot> {
    const now = Date.now();
    if (this.lastSnapshot && now - this.lastSnapshotTime < this.CACHE_TTL_MS) {
      return this.lastSnapshot;
    }

    const opts = {
      includeCalendar: true, includeCallLog: true, includeSms: true,
      includeContacts: true, includeLocation: true, includeScreen: true,
      smsLimit: 30, calendarDaysAhead: 60, calendarDaysBehind: 7, callLogLimit: 30,
      ...options,
    };

    const errors: string[] = [];
    const snapshot: DeviceSnapshot = {
      timestamp: now, calendar: [], callLog: [], sms: [], contacts: [],
      location: null,
      battery: { level: -1, state: 'Unknown', lowPower: false },
      system: { model: '', osVersion: '', ramUsedMB: 0, ramTotalMB: 0, storageFreeGB: 0, storageTotalGB: 0 },
      screen: null, installedApps: [], errors,
    };

    // Battery
    try {
      const [level, state, lowPower] = await Promise.all([
        Battery.getBatteryLevelAsync(), Battery.getBatteryStateAsync(), Battery.isLowPowerModeEnabledAsync(),
      ]);
      snapshot.battery = {
        level: Math.round(level * 100),
        state: ['Unknown', 'Unplugged', 'Charging', 'Full'][state] ?? 'Unknown',
        lowPower,
      };
    } catch (e: any) { errors.push(`battery: ${e.message}`); }

    // System
    try {
      const [usedMem, totalMem, freeDisk, totalDisk] = await Promise.all([
        DeviceInfo.getUsedMemory(), DeviceInfo.getTotalMemory(),
        DeviceInfo.getFreeDiskStorage(), DeviceInfo.getTotalDiskCapacity(),
      ]);
      snapshot.system = {
        model: DeviceInfo.getModel(), osVersion: DeviceInfo.getSystemVersion(),
        ramUsedMB: Math.round(usedMem / 1024 / 1024), ramTotalMB: Math.round(totalMem / 1024 / 1024),
        storageFreeGB: parseFloat((freeDisk / 1024 / 1024 / 1024).toFixed(1)),
        storageTotalGB: parseFloat((totalDisk / 1024 / 1024 / 1024).toFixed(1)),
      };
    } catch (e: any) { errors.push(`system: ${e.message}`); }

    // Calendar (Java bridge)
    if (opts.includeCalendar && isNative) {
      try {
        const AgentNative = (await import('../native/AgentNative')).default;
        if ((AgentNative as any).readCalendarEvents) {
          const startMs = now - opts.calendarDaysBehind * 86_400_000;
          const endMs = now + opts.calendarDaysAhead * 86_400_000;
          const raw: any[] = await (AgentNative as any).readCalendarEvents(startMs, endMs, 100);
          snapshot.calendar = raw.map(e => ({
            id: String(e.id || ''), title: e.title || '(no title)',
            startDate: e.startDate || 0, endDate: e.endDate || 0,
            allDay: !!e.allDay, location: e.location || '',
            description: e.description || '', calendar: e.calendar || '',
          }));
        }
      } catch (e: any) { errors.push(`calendar: ${e.message}`); }
    }

    // Call Log (Java bridge)
    if (opts.includeCallLog && isNative) {
      try {
        const AgentNative = (await import('../native/AgentNative')).default;
        if ((AgentNative as any).readCallLog) {
          const raw: any[] = await (AgentNative as any).readCallLog(opts.callLogLimit);
          snapshot.callLog = raw.map(c => ({
            number: c.number || '', name: c.name || '',
            type: c.type === 1 ? 'incoming' : c.type === 2 ? 'outgoing' : 'missed',
            date: c.date || 0, duration: c.duration || 0,
          }));
        }
      } catch (e: any) { errors.push(`callLog: ${e.message}`); }
    }

    // SMS
    if (opts.includeSms && isNative) {
      try {
        const AgentNative = (await import('../native/AgentNative')).default;
        if (AgentNative.readSms) {
          const raw = await AgentNative.readSms(opts.smsLimit, '');
          snapshot.sms = (raw || []).map(m => ({
            address: m.address, body: m.body, date: m.date,
            direction: (m as any).type === 2 ? 'sent' as const : 'received' as const,
          }));
        }
      } catch (e: any) { errors.push(`sms: ${e.message}`); }
    }

    // Contacts
    if (opts.includeContacts) {
      try {
        const { data } = await Contacts.getContactsAsync({
          fields: [Contacts.Fields.Name, Contacts.Fields.PhoneNumbers, Contacts.Fields.Emails],
          pageSize: 200,
        });
        snapshot.contacts = data.map(c => ({
          name: c.name || '(unknown)',
          phones: (c.phoneNumbers || []).map(p => ({ number: p.number || '', label: p.label || 'other' })),
          emails: (c.emails || []).map(e => e.email || ''),
        }));
      } catch (e: any) { errors.push(`contacts: ${e.message}`); }
    }

    // Location
    if (opts.includeLocation) {
      try {
        if (Platform.OS === 'web') {
          const pos = await new Promise<GeolocationPosition>((res, rej) =>
            navigator.geolocation.getCurrentPosition(res, rej, { timeout: 8000 })
          );
          snapshot.location = { latitude: pos.coords.latitude, longitude: pos.coords.longitude, accuracy: pos.coords.accuracy, altitude: pos.coords.altitude, timestamp: pos.timestamp };
        } else {
          const { coords } = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
          snapshot.location = { latitude: coords.latitude, longitude: coords.longitude, accuracy: coords.accuracy ?? 0, altitude: coords.altitude ?? 0, timestamp: now };
        }
      } catch (e: any) { errors.push(`location: ${e.message}`); }
    }

    // Screen
    if (opts.includeScreen && isNative && AppController.isAvailable()) {
      try {
        const serviceOn = await AppController.isServiceEnabled().catch(() => false);
        if (serviceOn) {
          const pkg = await AppController.getActivePackage();
          const flat = await AppController.getScreenContentFlat();
          const nodes = JSON.parse(flat) as Array<{ i: number; t: string; d: string; c: boolean; e: boolean; s: boolean; x: number; y: number }>;
          snapshot.screen = {
            activePackage: pkg, nodeCount: nodes.length,
            clickableCount: nodes.filter(n => n.c).length,
            topLabels: nodes.filter(n => n.t).slice(0, 12).map(n => (n.t || '').slice(0, 60)),
            rawNodes: nodes,
          };
        }
      } catch (e: any) { errors.push(`screen: ${e.message}`); }
    }

    // Installed Apps
    if (isNative) {
      try {
        const AgentNative = (await import('../native/AgentNative')).default;
        snapshot.installedApps = await AgentNative.getInstalledApps();
      } catch (e: any) { errors.push(`installedApps: ${e.message}`); }
    }

    if (errors.length > 0) DebugLog.push('CTX_AGGREGATE' as any, { event: 'device_context_errors', errors });

    this.lastSnapshot = snapshot;
    this.lastSnapshotTime = now;
    return snapshot;
  }

  static invalidate(): void { this.lastSnapshot = null; this.lastSnapshotTime = 0; }

  static toContextString(snap: DeviceSnapshot, maxLen = 4000): string {
    const lines: string[] = [];
    const now = snap.timestamp;
    lines.push(`[DEVICE] ${snap.system.model} Android ${snap.system.osVersion}`);
    lines.push(`[BATTERY] ${snap.battery.level}% ${snap.battery.state}${snap.battery.lowPower ? ' LOW_POWER' : ''}`);
    lines.push(`[RAM] ${snap.system.ramUsedMB}/${snap.system.ramTotalMB} MB`);
    lines.push(`[STORAGE] ${snap.system.storageFreeGB} GB free / ${snap.system.storageTotalGB} GB`);

    if (snap.calendar.length > 0) {
      lines.push(`\n[CALENDAR] ${snap.calendar.length} events:`);
      for (const evt of snap.calendar.slice(0, 20)) {
        const start = new Date(evt.startDate);
        const dayStr = start.toLocaleDateString([], { month: 'short', day: 'numeric' });
        const timeStr = evt.allDay ? 'all day' : start.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        const relative = evt.startDate > now ? 'upcoming' : 'past';
        lines.push(`  ${dayStr} ${timeStr} — "${evt.title}" ${evt.location ? `@ ${evt.location}` : ''} [${relative}]`);
      }
    }

    if (snap.callLog.length > 0) {
      lines.push(`\n[CALLS] ${snap.callLog.length} recent:`);
      for (const call of snap.callLog.slice(0, 15)) {
        const d = new Date(call.date);
        const dateStr = d.toLocaleDateString([], { month: 'short', day: 'numeric' });
        const durStr = call.duration > 0 ? `${Math.round(call.duration / 60)}min` : '';
        const nameOrNum = call.name || call.number.slice(0, 4) + '****';
        lines.push(`  ${dateStr} ${call.type} ${nameOrNum} ${durStr}`);
      }
    }

    if (snap.sms.length > 0) {
      lines.push(`\n[SMS] ${snap.sms.length} recent:`);
      for (const msg of snap.sms.slice(0, 15)) {
        const d = new Date(msg.date);
        const dateStr = d.toLocaleDateString([], { month: 'short', day: 'numeric' });
        const addr = msg.address.length > 6 ? msg.address.slice(0, 4) + '****' : msg.address;
        lines.push(`  ${dateStr} ${addr}: ${msg.body.slice(0, 80)}`);
      }
    }

    if (snap.contacts.length > 0) lines.push(`\n[CONTACTS] ${snap.contacts.length} total`);
    if (snap.location) lines.push(`\n[LOCATION] available (redacted for privacy)`);
    if (snap.screen) {
      lines.push(`\n[SCREEN] App: ${snap.screen.activePackage} | ${snap.screen.nodeCount} elements (${snap.screen.clickableCount} clickable)`);
      if (snap.screen.topLabels.length > 0) lines.push(`  Labels: ${snap.screen.topLabels.join(', ')}`);
    }
    if (snap.installedApps.length > 0) lines.push(`\n[APPS] ${snap.installedApps.length} installed`);

    let result = lines.join('\n');
    if (result.length > maxLen) result = result.slice(0, maxLen - 20) + '\n...[truncated]';
    return result;
  }

  static async findContact(name: string): Promise<ContactEntry[]> {
    try {
      const { data } = await Contacts.getContactsAsync({
        fields: [Contacts.Fields.Name, Contacts.Fields.PhoneNumbers, Contacts.Fields.Emails], name,
      });
      return data.map(c => ({
        name: c.name || '(unknown)',
        phones: (c.phoneNumbers || []).map(p => ({ number: p.number || '', label: p.label || 'other' })),
        emails: (c.emails || []).map(e => e.email || ''),
      }));
    } catch { return []; }
  }

  static findFreeWindows(events: CalendarEvent[], daysAhead: number, minGapMinutes: number = 60): Array<{ start: number; end: number; durationMinutes: number }> {
    const now = Date.now();
    const horizon = now + daysAhead * 86_400_000;
    const sorted = events.filter(e => e.endDate > now && e.startDate < horizon && !e.allDay).sort((a, b) => a.startDate - b.startDate);
    const windows: Array<{ start: number; end: number; durationMinutes: number }> = [];
    let cursor = now;
    for (const evt of sorted) {
      if (evt.startDate > cursor) {
        const gap = (evt.startDate - cursor) / 60_000;
        if (gap >= minGapMinutes) windows.push({ start: cursor, end: evt.startDate, durationMinutes: Math.round(gap) });
      }
      if (evt.endDate > cursor) cursor = evt.endDate;
    }
    if (horizon > cursor) {
      const gap = (horizon - cursor) / 60_000;
      if (gap >= minGapMinutes) windows.push({ start: cursor, end: horizon, durationMinutes: Math.round(gap) });
    }
    return windows;
  }
}
