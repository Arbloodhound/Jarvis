import { Platform, Dimensions } from 'react-native';
import * as Battery from 'expo-battery';
import * as Device from 'expo-device';
import DeviceInfo from 'react-native-device-info';
import NetInfo from '@react-native-community/netinfo';
import AgentNative from '../native/AgentNative';
import AppController from '../native/AppController';
import { UltraDevLog } from '../utils/UltraDevLog';

export interface SystemInfoData {
  battery: {
    level: number;
    state: 'Unknown' | 'Unplugged' | 'Charging' | 'Full';
    lowPowerMode: boolean;
  };
  memory: {
    usedMB: number;
    totalMB: number;
    usedPercent: number;
  };
  storage: {
    freeMB: number;
    totalMB: number;
    usedPercent: number;
  };
  device: {
    model: string;
    os: string;
    osVersion: string;
  };
  network: {
    connected: boolean;
    type: string;
  };
  screen: {
    width: number;
    height: number;
  };
  cpuTemp: number | null;
  processCount: number | null;
  timestamp: number;
}

const isNative = Platform.OS !== 'web';

export class SystemInfoService {
  private static batterySubscription: any = null;
  private static lastBatteryLevel: number = -1;
  private static lastBatteryState: number = -1;

  static startBatteryMonitoring(onChange?: (level: number, state: string) => void): void {
    if (!isNative || SystemInfoService.batterySubscription) return;
    try {
      SystemInfoService.batterySubscription = Battery.addBatteryLevelListener(({ batteryLevel }) => {
        const pct = Math.round(batteryLevel * 100);
        if (pct !== SystemInfoService.lastBatteryLevel) {
          SystemInfoService.lastBatteryLevel = pct;
          onChange?.(pct, '');
        }
      });
    } catch {}
  }

  static stopBatteryMonitoring(): void {
    if (SystemInfoService.batterySubscription) {
      SystemInfoService.batterySubscription.remove();
      SystemInfoService.batterySubscription = null;
    }
  }

  static async gather(): Promise<SystemInfoData> {
    const result: SystemInfoData = {
      battery: { level: -1, state: 'Unknown', lowPowerMode: false },
      memory: { usedMB: 0, totalMB: 0, usedPercent: 0 },
      storage: { freeMB: 0, totalMB: 0, usedPercent: 0 },
      device: { model: 'Unknown', os: Platform.OS, osVersion: String(Platform.Version) },
      network: { connected: false, type: 'unknown' },
      screen: {
        width: Math.round(Dimensions.get('window').width),
        height: Math.round(Dimensions.get('window').height),
      },
      cpuTemp: null,
      processCount: null,
      timestamp: Date.now(),
    };

    const batteryPromise = (async () => {
      try {
        const [level, state, lowPower] = await Promise.all([
          Battery.getBatteryLevelAsync(),
          Battery.getBatteryStateAsync(),
          Battery.isLowPowerModeEnabledAsync(),
        ]);
        const stateStr = (['Unknown', 'Unplugged', 'Charging', 'Full'] as const)[state] ?? 'Unknown';
        result.battery = {
          level: Math.round(level * 100),
          state: stateStr,
          lowPowerMode: lowPower,
        };
        SystemInfoService.lastBatteryLevel = result.battery.level;
      } catch {}
    })();

    const memoryPromise = (async () => {
      try {
        const [used, total] = await Promise.all([
          DeviceInfo.getUsedMemory(),
          DeviceInfo.getTotalMemory(),
        ]);
        const usedMB = Math.round(used / 1024 / 1024);
        const totalMB = Math.round(total / 1024 / 1024);
        result.memory = {
          usedMB,
          totalMB,
          usedPercent: totalMB > 0 ? Math.round((usedMB / totalMB) * 100) : 0,
        };
      } catch {
        try {
          const totalMem = Device.totalMemory;
          if (totalMem) {
            result.memory.totalMB = Math.round(totalMem / 1024 / 1024);
          }
        } catch {}
      }
    })();

    const storagePromise = (async () => {
      try {
        if (isNative) {
          try {
            const nativeStorage = await AgentNative.getStorageInfo();
            if (nativeStorage && nativeStorage.total > 0) {
              result.storage = {
                freeMB: Math.round(nativeStorage.free / 1024 / 1024),
                totalMB: Math.round(nativeStorage.total / 1024 / 1024),
                usedPercent: Math.round((nativeStorage.used / nativeStorage.total) * 100),
              };
              return;
            }
          } catch {}
        }
        const [free, total] = await Promise.all([
          DeviceInfo.getFreeDiskStorage(),
          DeviceInfo.getTotalDiskCapacity(),
        ]);
        const freeMB = Math.round(free / 1024 / 1024);
        const totalMB = Math.round(total / 1024 / 1024);
        result.storage = {
          freeMB,
          totalMB,
          usedPercent: totalMB > 0 ? Math.round(((totalMB - freeMB) / totalMB) * 100) : 0,
        };
      } catch {}
    })();

    const networkPromise = (async () => {
      try {
        const netState = await NetInfo.fetch();
        result.network = {
          connected: !!netState.isConnected,
          type: netState.type || 'unknown',
        };
      } catch {}
    })();

    const devicePromise = (async () => {
      try {
        result.device = {
          model: DeviceInfo.getModel(),
          os: Platform.OS === 'android' ? 'Android' : Platform.OS === 'ios' ? 'iOS' : Platform.OS,
          osVersion: DeviceInfo.getSystemVersion(),
        };
      } catch {
        result.device.model = Device.modelName ?? 'Unknown';
      }
    })();

    const cpuTempPromise = (async () => {
      if (!isNative) return;
      try {
        const exec = AppController.exec ?? AgentNative.exec;
        if (exec) {
          const temp = await exec('cat /sys/class/thermal/thermal_zone0/temp', '/');
          if (temp && !isNaN(parseInt(temp))) {
            result.cpuTemp = parseFloat((parseInt(temp) / 1000).toFixed(1));
          }
        }
      } catch {}
    })();

    const processCountPromise = (async () => {
      if (!isNative) return;
      try {
        const exec = AppController.exec ?? AgentNative.exec;
        if (exec) {
          const psOutput = await exec('ls /proc', '/');
          if (psOutput) {
            const pidCount = psOutput.split('\n').filter((l: string) => /^\d+$/.test(l.trim())).length;
            if (pidCount > 0) result.processCount = pidCount;
          }
        }
      } catch {}
    })();

    await Promise.all([
      batteryPromise,
      memoryPromise,
      storagePromise,
      networkPromise,
      devicePromise,
      cpuTempPromise,
      processCountPromise,
    ]);

    UltraDevLog.systemInfo({
      batteryPct: result.battery.level >= 0 ? result.battery.level : undefined,
      batteryState: result.battery.state,
      lowPower: result.battery.lowPowerMode,
      ramUsedMB: result.memory.usedMB || undefined,
      ramTotalMB: result.memory.totalMB || undefined,
      storageFreeGB: result.storage.freeMB > 0 ? parseFloat((result.storage.freeMB / 1024).toFixed(1)) : undefined,
      storageTotalGB: result.storage.totalMB > 0 ? parseFloat((result.storage.totalMB / 1024).toFixed(1)) : undefined,
      cpuTempC: result.cpuTemp ?? undefined,
      failedReads: [],
    });

    return result;
  }

  static toSummaryString(data: SystemInfoData): string {
    const lines: string[] = [];
    if (data.battery.level >= 0) {
      lines.push(`Battery: ${data.battery.level}% (${data.battery.state})${data.battery.lowPowerMode ? ' — Low Power Mode' : ''}`);
    }
    if (data.memory.totalMB > 0) {
      lines.push(`RAM: ${data.memory.usedMB} MB / ${data.memory.totalMB} MB (${data.memory.usedPercent}%)`);
    }
    if (data.storage.totalMB > 0) {
      const freeGB = (data.storage.freeMB / 1024).toFixed(1);
      const totalGB = (data.storage.totalMB / 1024).toFixed(1);
      lines.push(`Storage: ${freeGB} GB free / ${totalGB} GB total (${data.storage.usedPercent}% used)`);
    }
    lines.push(`Device: ${data.device.model} (${data.device.os} ${data.device.osVersion})`);
    lines.push(`Network: ${data.network.connected ? data.network.type : 'Disconnected'}`);
    lines.push(`Screen: ${data.screen.width} × ${data.screen.height}`);
    if (data.cpuTemp !== null) lines.push(`CPU Temp: ${data.cpuTemp}°C`);
    if (data.processCount !== null) lines.push(`Processes: ${data.processCount}`);
    return lines.join('\n');
  }

  static toContextString(data: SystemInfoData): string {
    const parts: string[] = [];
    if (data.battery.level >= 0) {
      parts.push(`battery=${data.battery.level}%(${data.battery.state})`);
      if (data.battery.level <= 15) parts.push('BATTERY_LOW');
    }
    if (data.memory.totalMB > 0) {
      parts.push(`ram=${data.memory.usedPercent}%`);
      if (data.memory.usedPercent > 85) parts.push('RAM_HIGH');
    }
    if (data.storage.totalMB > 0) {
      parts.push(`storage=${data.storage.usedPercent}%used`);
      if (data.storage.freeMB < 1024) parts.push('STORAGE_LOW');
    }
    parts.push(`net=${data.network.connected ? data.network.type : 'offline'}`);
    if (data.cpuTemp !== null) {
      parts.push(`cpuTemp=${data.cpuTemp}C`);
      if (data.cpuTemp > 45) parts.push('CPU_HOT');
    }
    return `[DeviceState: ${parts.join(', ')}]`;
  }
}
