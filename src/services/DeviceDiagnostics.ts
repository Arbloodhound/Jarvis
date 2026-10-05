import { NativeModules, Platform } from 'react-native';
import { UltraDevLog } from '../utils/UltraDevLog';

const exec = async (cmd: string): Promise<string> => {
  try {
    if (Platform.OS !== 'android') return '';
    const result = await NativeModules.AgentNative?.exec?.(cmd, '');
    return typeof result === 'string' ? result : '';
  } catch { return ''; }
};

export class DeviceDiagnostics {

  static async logAudioState(): Promise<void> {
    try {
      const raw = await exec('dumpsys audio');
      if (!raw) return;

      const state: Record<string, any> = {};

      const ringerMatch = raw.match(/ringer mode:\s*(\w+)/i);
      if (ringerMatch) state.ringerMode = ringerMatch[1];

      const musicMatch = raw.match(/- STREAM_MUSIC:[\s\S]*?Max:\s*(\d+)[\s\S]*?Current:\s*(\d+)/i);
      if (musicMatch) { state.musicMax = parseInt(musicMatch[1]); state.musicCurrent = parseInt(musicMatch[2]); }

      const activeMatch = raw.match(/isMusicActive\(\):\s*(true|false)/i);
      if (activeMatch) state.musicActive = activeMatch[1] === 'true';

      const focusMatch = raw.match(/Audio Focus stack entries[\s\S]*?source:\s*(\S+)/i);
      if (focusMatch) state.focusOwner = focusMatch[1].slice(0, 60);

      if (raw.includes('isBluetoothScoOn=true') || raw.includes('A2DP device')) {
        state.bluetoothAudio = true;
      }

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('AUDIO_STATE', state);
      }
    } catch {}
  }

  static async logThermalState(): Promise<void> {
    try {
      const battRaw = await exec('dumpsys battery');
      const state: Record<string, any> = {};

      if (battRaw) {
        const tempMatch = battRaw.match(/temperature:\s*(\d+)/);
        if (tempMatch) state.batteryTempC = parseInt(tempMatch[1]) / 10;

        const levelMatch = battRaw.match(/level:\s*(\d+)/);
        if (levelMatch) state.batteryLevel = parseInt(levelMatch[1]);

        const statusMatch = battRaw.match(/status:\s*(\d+)/);
        if (statusMatch) {
          const codes: Record<string, string> = { '1': 'unknown', '2': 'charging', '3': 'discharging', '4': 'not_charging', '5': 'full' };
          state.batteryStatus = codes[statusMatch[1]] || statusMatch[1];
        }
      }

      const thermalRaw = await exec('dumpsys thermalservice');
      if (thermalRaw) {
        const statusMatch = thermalRaw.match(/mStatus:\s*(\d+)/);
        if (statusMatch) {
          const level = parseInt(statusMatch[1]);
          const labels = ['none', 'light', 'moderate', 'severe', 'critical', 'emergency', 'shutdown'];
          state.thermalStatus = level;
          state.thermalLabel = labels[level] || 'unknown';
        }

        const zones: string[] = [];
        const zoneMatches = thermalRaw.matchAll(/(\w+):\s*([\d.]+)\s*C/gi);
        for (const m of zoneMatches) {
          zones.push(`${m[1]}:${m[2]}C`);
        }
        if (zones.length > 0) state.thermalZones = zones.slice(0, 5).join(', ');
      }

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('THERMAL', state);
      }
    } catch {}
  }

  static async logMemoryState(): Promise<void> {
    try {
      const pid = await exec('cat /proc/self/stat | cut -d" " -f1');
      const raw = await exec(`dumpsys meminfo ${pid.trim()}`);
      const state: Record<string, any> = {};

      if (raw) {
        const totalMatch = raw.match(/TOTAL\s+(\d+)/);
        if (totalMatch) state.totalPssKB = parseInt(totalMatch[1]);

        const javaMatch = raw.match(/Java Heap:\s+(\d+)/);
        if (javaMatch) state.javaHeapKB = parseInt(javaMatch[1]);

        const nativeMatch = raw.match(/Native Heap:\s+(\d+)/);
        if (nativeMatch) state.nativeHeapKB = parseInt(nativeMatch[1]);

        const codeMatch = raw.match(/Code:\s+(\d+)/);
        if (codeMatch) state.codeKB = parseInt(codeMatch[1]);
      }

      const meminfo = await exec('cat /proc/meminfo');
      if (meminfo) {
        const totalMatch = meminfo.match(/MemTotal:\s+(\d+)/);
        const availMatch = meminfo.match(/MemAvailable:\s+(\d+)/);
        if (totalMatch) state.systemTotalMB = Math.round(parseInt(totalMatch[1]) / 1024);
        if (availMatch) state.systemAvailMB = Math.round(parseInt(availMatch[1]) / 1024);
      }

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('MEMORY', state);
      }
    } catch {}
  }

  static async logRenderStats(): Promise<void> {
    try {
      const raw = await exec('dumpsys gfxinfo com.agent.ultra');
      if (!raw) return;

      const state: Record<string, any> = {};

      const totalMatch = raw.match(/Total frames rendered:\s*(\d+)/);
      if (totalMatch) state.totalFrames = parseInt(totalMatch[1]);

      const jankyMatch = raw.match(/Janky frames:\s*(\d+)\s*\(([\d.]+)%\)/);
      if (jankyMatch) {
        state.jankyFrames = parseInt(jankyMatch[1]);
        state.jankyPercent = parseFloat(jankyMatch[2]);
      }

      const pctMatch = raw.match(/50th percentile:\s*(\d+)ms/);
      if (pctMatch) state.p50ms = parseInt(pctMatch[1]);
      const p90Match = raw.match(/90th percentile:\s*(\d+)ms/);
      if (p90Match) state.p90ms = parseInt(p90Match[1]);
      const p99Match = raw.match(/99th percentile:\s*(\d+)ms/);
      if (p99Match) state.p99ms = parseInt(p99Match[1]);

      const vsyncMatch = raw.match(/Number Missed Vsync:\s*(\d+)/);
      if (vsyncMatch) state.missedVsync = parseInt(vsyncMatch[1]);

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('RENDER_STATS', state);
      }
    } catch {}
  }

  static async logA11yStatus(): Promise<void> {
    try {
      const raw = await exec('dumpsys accessibility');
      if (!raw) return;

      const state: Record<string, any> = {};

      state.ultraEnabled = raw.includes('com.agent.ultra') && raw.includes('isEnabled=true');

      const enabledMatches = raw.match(/isEnabled=true/g);
      state.enabledServiceCount = enabledMatches ? enabledMatches.length : 0;

      UltraDevLog.push('A11Y_DUMPSYS', state);
    } catch {}
  }

  // ── Network Connectivity ─────────────────────────
  static async logConnectivity(): Promise<void> {
    try {
      const raw = await exec('dumpsys connectivity');
      if (!raw) return;
      const state: Record<string, any> = {};

      const activeMatch = raw.match(/Active default network:\s*(\S+)/i);
      if (activeMatch) state.activeNetwork = activeMatch[1];

      const typeMatch = raw.match(/type:\s*(WIFI|MOBILE|CELLULAR|ETHERNET|VPN|NONE)/i);
      if (typeMatch) state.networkType = typeMatch[1];

      const validatedMatch = raw.match(/Validated:\s*(true|false)/i);
      if (validatedMatch) state.validated = validatedMatch[1] === 'true';

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('NET_CONNECTIVITY', state);
      }
    } catch {}
  }

  // ── WiFi Details ─────────────────────────────────
  static async logWifiState(): Promise<void> {
    try {
      const raw = await exec('dumpsys wifi');
      if (!raw) return;
      const state: Record<string, any> = {};

      const ssidMatch = raw.match(/mWifiInfo\s+SSID:\s*"?([^",]+)"?/i) || raw.match(/SSID:\s*"?([^",\n]+)"?/i);
      if (ssidMatch) state.ssid = ssidMatch[1].trim().slice(0, 40);

      const rssiMatch = raw.match(/RSSI:\s*(-?\d+)/i);
      if (rssiMatch) state.rssiDbm = parseInt(rssiMatch[1]);

      const linkMatch = raw.match(/Link speed:\s*(\d+)\s*Mbps/i);
      if (linkMatch) state.linkSpeedMbps = parseInt(linkMatch[1]);

      const freqMatch = raw.match(/Frequency:\s*(\d+)\s*MHz/i);
      if (freqMatch) state.freqMHz = parseInt(freqMatch[1]);

      const stateMatch = raw.match(/Wi-Fi is\s+(\w+)/i);
      if (stateMatch) state.wifiEnabled = stateMatch[1].toLowerCase();

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('WIFI_STATE', state);
      }
    } catch {}
  }

  // ── Foreground Activity Stack ────────────────────
  static async logActivityStack(): Promise<void> {
    try {
      const raw = await exec('dumpsys activity activities');
      if (!raw) return;
      const state: Record<string, any> = {};

      const topMatch = raw.match(/mResumedActivity:\s*ActivityRecord\{[^\s]+ [^\s]+ ([^\s}]+)/);
      if (topMatch) state.topActivity = topMatch[1].slice(0, 80);

      const tasks: string[] = [];
      const taskMatches = raw.matchAll(/\* Task\{[^}]*#(\d+)[^}]*A=([^\s}]+)/g);
      for (const m of taskMatches) {
        tasks.push(m[2]);
        if (tasks.length >= 5) break;
      }
      if (tasks.length > 0) state.recentTasks = tasks;

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('ACTIVITY_STACK', state);
      }
    } catch {}
  }

  // ── Window Focus ─────────────────────────────────
  static async logWindowState(): Promise<void> {
    try {
      const raw = await exec('dumpsys window');
      if (!raw) return;
      const state: Record<string, any> = {};

      const focusMatch = raw.match(/mCurrentFocus=Window\{[^\s]+ [^\s]+ ([^\s}]+)/);
      if (focusMatch) state.currentFocus = focusMatch[1].slice(0, 80);

      const focusedMatch = raw.match(/mFocusedApp=ActivityRecord\{[^\s]+ [^\s]+ ([^\s}]+)/);
      if (focusedMatch) state.focusedApp = focusedMatch[1].slice(0, 80);

      const screenMatch = raw.match(/mScreenOnEarly=(true|false)/);
      if (screenMatch) state.screenOn = screenMatch[1] === 'true';

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('WINDOW_STATE', state);
      }
    } catch {}
  }

  // ── Power / Wake Locks / Doze ────────────────────
  static async logPowerState(): Promise<void> {
    try {
      const raw = await exec('dumpsys power');
      if (!raw) return;
      const state: Record<string, any> = {};

      const screenMatch = raw.match(/mWakefulness=(\w+)/);
      if (screenMatch) state.wakefulness = screenMatch[1];

      const dozeMatch = raw.match(/mDeviceIdleMode=(\w+)/i);
      if (dozeMatch) state.dozeMode = dozeMatch[1];

      const wakeLocks = raw.match(/Wake Locks: size=(\d+)/);
      if (wakeLocks) state.wakeLockCount = parseInt(wakeLocks[1]);

      if (raw.includes('com.agent.ultra')) state.ultraHasWakeLock = true;

      const stayOnMatch = raw.match(/mStayOn=(true|false)/);
      if (stayOnMatch) state.stayOn = stayOnMatch[1] === 'true';

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('POWER_STATE', state);
      }
    } catch {}
  }

  // ── Device Idle / Doze Whitelist ─────────────────
  static async logDeviceIdle(): Promise<void> {
    try {
      const raw = await exec('dumpsys deviceidle');
      if (!raw) return;
      const state: Record<string, any> = {};

      const idleMatch = raw.match(/mState=(\w+)/);
      if (idleMatch) state.idleState = idleMatch[1];

      state.ultraWhitelisted = raw.includes('com.agent.ultra');

      if (raw.match(/Whitelist[\s\S]*com\.agent\.ultra/i)) {
        state.whitelistType = 'system_or_user';
      }

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('DEVICE_IDLE', state);
      }
    } catch {}
  }

  // ── Alarm Status ─────────────────────────────────
  static async logAlarmState(): Promise<void> {
    try {
      const raw = await exec('dumpsys alarm');
      if (!raw) return;
      const state: Record<string, any> = {};

      const ultraAlarms = (raw.match(/com\.agent\.ultra/g) || []).length;
      state.ultraAlarmCount = ultraAlarms;

      const pendingMatch = raw.match(/Pending alarm batches:\s*(\d+)/);
      if (pendingMatch) state.totalPendingBatches = parseInt(pendingMatch[1]);

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('ALARM_STATE', state);
      }
    } catch {}
  }

  // ── Notification / Foreground Service ─────────────
  static async logNotificationState(): Promise<void> {
    try {
      const raw = await exec('dumpsys notification');
      if (!raw) return;
      const state: Record<string, any> = {};

      const ultraNotifs = (raw.match(/com\.agent\.ultra/g) || []).length;
      state.ultraNotificationRefs = ultraNotifs;

      state.hasForegroundNotification = raw.includes('com.agent.ultra') && raw.includes('foreground');

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('NOTIFICATION_STATE', state);
      }
    } catch {}
  }

  // ── Package Permissions (our app) ────────────────
  static async logPackagePermissions(): Promise<void> {
    try {
      const raw = await exec('dumpsys package com.agent.ultra');
      if (!raw) return;
      const state: Record<string, any> = {};

      const versionMatch = raw.match(/versionName=([^\s]+)/);
      if (versionMatch) state.versionName = versionMatch[1];

      const vCodeMatch = raw.match(/versionCode=(\d+)/);
      if (vCodeMatch) state.versionCode = parseInt(vCodeMatch[1]);

      const granted: string[] = [];
      const denied: string[] = [];
      const permSection = raw.match(/runtime permissions:[\s\S]*?(?=\n\n|\ninstall permissions:)/i);
      if (permSection) {
        const lines = permSection[0].split('\n');
        for (const line of lines) {
          const permMatch = line.match(/(android\.permission\.\w+):\s*granted=(true|false)/);
          if (permMatch) {
            if (permMatch[2] === 'true') granted.push(permMatch[1].replace('android.permission.', ''));
            else denied.push(permMatch[1].replace('android.permission.', ''));
          }
        }
      }
      state.grantedCount = granted.length;
      state.deniedCount = denied.length;
      if (denied.length > 0) state.denied = denied;

      state.batteryOptimExempt = raw.includes('IGNORE_BATTERY_OPTIMIZATIONS') || false;

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('PACKAGE_STATE', state);
      }
    } catch {}
  }

  // ── CPU Usage ────────────────────────────────────
  static async logCpuState(): Promise<void> {
    try {
      const raw = await exec('dumpsys cpuinfo');
      if (!raw) return;
      const state: Record<string, any> = {};

      const totalMatch = raw.match(/([\d.]+)%\s+TOTAL/);
      if (totalMatch) state.totalCpuPercent = parseFloat(totalMatch[1]);

      const ultraMatch = raw.match(/([\d.]+)%\s+\d+\/com\.agent\.ultra/);
      if (ultraMatch) state.ultraCpuPercent = parseFloat(ultraMatch[1]);

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('CPU_STATE', state);
      }
    } catch {}
  }

  // ── Process Priority / State ─────────────────────
  static async logProcessState(): Promise<void> {
    try {
      const raw = await exec('dumpsys procstats --hours 1');
      if (!raw) return;
      const state: Record<string, any> = {};

      const ultraLine = raw.split('\n').find(l => l.includes('com.agent.ultra'));
      if (ultraLine) {
        state.procLine = ultraLine.trim().slice(0, 120);
        if (ultraLine.includes('Top')) state.wasTop = true;
        if (ultraLine.includes('Fg')) state.wasForeground = true;
        if (ultraLine.includes('Bg')) state.wasBackground = true;
        if (ultraLine.includes('Cached')) state.wasCached = true;
      }

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('PROC_STATE', state);
      }
    } catch {}
  }

  // ── Network Stats (our app data usage) ───────────
  static async logNetStats(): Promise<void> {
    try {
      const raw = await exec('dumpsys netstats detail');
      if (!raw) return;
      const state: Record<string, any> = {};

      const uidRaw = await exec('dumpsys package com.agent.ultra | grep userId=');
      const uidMatch = uidRaw.match(/userId=(\d+)/);
      if (!uidMatch) return;
      const uid = uidMatch[1];

      const uidSection = raw.match(new RegExp(`uid=${uid}[\\s\\S]*?(?=uid=|$)`));
      if (uidSection) {
        const rxMatch = uidSection[0].match(/rb=(\d+)/);
        const txMatch = uidSection[0].match(/tb=(\d+)/);
        if (rxMatch) state.rxBytes = parseInt(rxMatch[1]);
        if (txMatch) state.txBytes = parseInt(txMatch[1]);
        if (state.rxBytes) state.rxMB = Math.round(state.rxBytes / 1048576 * 10) / 10;
        if (state.txBytes) state.txMB = Math.round(state.txBytes / 1048576 * 10) / 10;
      }

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('NET_STATS', state);
      }
    } catch {}
  }

  // ── Input State ──────────────────────────────────
  static async logInputState(): Promise<void> {
    try {
      const raw = await exec('dumpsys input');
      if (!raw) return;
      const state: Record<string, any> = {};

      const touchMatch = raw.match(/(\d+):\s+.*[Tt]ouch/);
      if (touchMatch) state.hasTouchDevice = true;

      const dispatchMatch = raw.match(/DispatchEnabled:\s*(true|false)/i);
      if (dispatchMatch) state.dispatchEnabled = dispatchMatch[1] === 'true';

      const focusMatch = raw.match(/FocusedWindow:\s*name='([^']+)'/);
      if (focusMatch) state.inputFocusedWindow = focusMatch[1].slice(0, 60);

      if (Object.keys(state).length > 0) {
        UltraDevLog.push('INPUT_STATE', state);
      }
    } catch {}
  }

  static async runAll(): Promise<void> {
    // Core diagnostics — every 30s
    await DeviceDiagnostics.logAudioState();
    await DeviceDiagnostics.logThermalState();
    await DeviceDiagnostics.logMemoryState();
    await DeviceDiagnostics.logRenderStats();
    await DeviceDiagnostics.logA11yStatus();
    await DeviceDiagnostics.logConnectivity();
    await DeviceDiagnostics.logPowerState();
    await DeviceDiagnostics.logCpuState();
  }

  // Heavy diagnostics — run less frequently (on init + every 5 min)
  static async runDeep(): Promise<void> {
    await DeviceDiagnostics.logWifiState();
    await DeviceDiagnostics.logActivityStack();
    await DeviceDiagnostics.logWindowState();
    await DeviceDiagnostics.logDeviceIdle();
    await DeviceDiagnostics.logAlarmState();
    await DeviceDiagnostics.logNotificationState();
    await DeviceDiagnostics.logPackagePermissions();
    await DeviceDiagnostics.logProcessState();
    await DeviceDiagnostics.logNetStats();
    await DeviceDiagnostics.logInputState();
  }

  static async logAudioBeforeAfter(when: string): Promise<void> {
    try {
      const raw = await exec('dumpsys audio');
      if (!raw) return;
      const state: Record<string, any> = { when };
      const musicMatch = raw.match(/- STREAM_MUSIC:[\s\S]*?Max:\s*(\d+)[\s\S]*?Current:\s*(\d+)/i);
      if (musicMatch) { state.musicMax = parseInt(musicMatch[1]); state.musicCurrent = parseInt(musicMatch[2]); }
      const ringerMatch = raw.match(/ringer mode:\s*(\w+)/i);
      if (ringerMatch) state.ringerMode = ringerMatch[1];
      const activeMatch = raw.match(/isMusicActive\(\):\s*(true|false)/i);
      if (activeMatch) state.musicActive = activeMatch[1] === 'true';
      UltraDevLog.push('AUDIO_STATE', state);
    } catch {}
  }
}
