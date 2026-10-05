import { Platform } from 'react-native';
import { UltraDevLog } from '../utils/UltraDevLog';

const MAX_SCREENSHOTS = 20;
const DIR_NAME = 'debug_screenshots';

let FileSystem: any = null;
if (Platform.OS !== 'web') {
  try { FileSystem = require('expo-file-system/legacy'); } catch {}
}

export class DebugScreenshots {
  private static dir = FileSystem?.documentDirectory ? `${FileSystem.documentDirectory}${DIR_NAME}/` : '';

  static async capture(reason: string): Promise<string | null> {
    if (!FileSystem || Platform.OS === 'web') return null;
    try {
      const AsyncStorage = require('@react-native-async-storage/async-storage').default;
      const devMode = await AsyncStorage.getItem('dev_mode_enabled');
      if (devMode !== '1') return null;

      const dirInfo = await FileSystem.getInfoAsync(DebugScreenshots.dir);
      if (!dirInfo.exists) await FileSystem.makeDirectoryAsync(DebugScreenshots.dir, { intermediates: true });

      const AppController = require('../native/AppController').default;
      if (!AppController?.takeScreenshot) return null;
      const taken = await AppController.takeScreenshot();
      if (!taken) return null;

      const filename = `${Date.now()}_${reason.replace(/[^a-zA-Z0-9]/g, '_').slice(0, 30)}.png`;
      const filepath = `${DebugScreenshots.dir}${filename}`;

      await DebugScreenshots.enforceCap();

      UltraDevLog.push('DEBUG_SCREENSHOT', { reason, filepath, timestamp: Date.now() });
      return filepath;
    } catch (e: any) {
      UltraDevLog.push('DEBUG_SCREENSHOT_FAIL', { reason, error: e?.message });
      return null;
    }
  }

  static async enforceCap(): Promise<void> {
    if (!FileSystem) return;
    try {
      const dirInfo = await FileSystem.getInfoAsync(DebugScreenshots.dir);
      if (!dirInfo.exists) return;
      const files = await FileSystem.readDirectoryAsync(DebugScreenshots.dir);
      const pngs = files.filter((f: string) => f.endsWith('.png')).sort();
      while (pngs.length >= MAX_SCREENSHOTS) {
        const oldest = pngs.shift()!;
        await FileSystem.deleteAsync(`${DebugScreenshots.dir}${oldest}`, { idempotent: true });
      }
    } catch {}
  }

  static async getAll(): Promise<string[]> {
    if (!FileSystem) return [];
    try {
      const dirInfo = await FileSystem.getInfoAsync(DebugScreenshots.dir);
      if (!dirInfo.exists) return [];
      const files = await FileSystem.readDirectoryAsync(DebugScreenshots.dir);
      return files.filter((f: string) => f.endsWith('.png')).sort().map((f: string) => `${DebugScreenshots.dir}${f}`);
    } catch { return []; }
  }

  static async clear(): Promise<void> {
    if (!FileSystem) return;
    try {
      await FileSystem.deleteAsync(DebugScreenshots.dir, { idempotent: true });
    } catch {}
  }

  static getDir(): string { return DebugScreenshots.dir; }
}
