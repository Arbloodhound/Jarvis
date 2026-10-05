import { Platform } from 'react-native';
import * as FileSystemModule from 'expo-file-system/legacy';
import { UltraDevLog } from '../utils/UltraDevLog';

export interface LogFile {
  name: string;
  path: string;
  size: number;
  createdAt: number;
}

export class LogFolder {
  private static FS: any = null;
  private static LOGS_DIR_CACHE: string | null = null;
  private static initialized = false;
  private static initFailed = false;

  private static getFS() {
    if (this.FS) return this.FS;
    if (Platform.OS === 'web') return null;
    this.FS = FileSystemModule;
    return this.FS;
  }

  private static getLogsDir(): string {
    if (this.LOGS_DIR_CACHE) return this.LOGS_DIR_CACHE;
    const fs = this.getFS();
    if (!fs) return '';
    const docDir = fs?.documentDirectory;
    if (!docDir) return '';
    this.LOGS_DIR_CACHE = `${docDir}agent-ultra-logs`;
    return this.LOGS_DIR_CACHE;
  }

  static async initialize() {
    const fs = this.getFS();
    const dir = this.getLogsDir();
    if (!fs || !dir || this.initialized || this.initFailed) {
      UltraDevLog.push('SYSTEM', { event: 'log_folder_init_skip', hasFs: !!fs, hasDir: !!dir, initialized: this.initialized, initFailed: this.initFailed, platform: Platform.OS });
      return;
    }
    UltraDevLog.push('SYSTEM', { event: 'log_folder_init_start', dir });
    try {
      const info = await fs.getInfoAsync(dir);
      if (!info.exists) {
        await fs.makeDirectoryAsync(dir, { intermediates: true });
      }
      try {
        const existingFiles = await fs.readDirectoryAsync(dir);
        let cleaned = 0;
        for (const f of existingFiles) {
          if (/^agent-ultra-(debug|debuglog|raw)-\d{4}-\d{2}-\d{2}T/.test(f)) {
            try { await fs.deleteAsync(`${dir}/${f}`); cleaned++; } catch {}
          }
        }
        if (cleaned > 0) {
          UltraDevLog.push('SYSTEM', { event: 'log_folder_cleanup', dir, cleanedCount: cleaned });
        }
      } catch {}
      this.initialized = true;
      UltraDevLog.push('SYSTEM', { event: 'log_folder_init_ok', dir });
    } catch (err: any) {
      this.initFailed = true;
      UltraDevLog.push('SYSTEM', { event: 'log_folder_init_fail', dir, error: err?.message });
    }
  }

  static async writeLog(filename: string, content: string): Promise<boolean> {
    const fs = this.getFS();
    const dir = this.getLogsDir();
    if (!fs || !dir) {
      UltraDevLog.push('SYSTEM', { event: 'log_folder_write_skip', filename, reason: 'no_fs_or_dir' });
      return false;
    }
    if (this.initFailed) {
      UltraDevLog.push('SYSTEM', { event: 'log_folder_write_skip', filename, reason: 'init_failed' });
      return false;
    }
    await this.initialize();
    try {
      const filePath = `${dir}/${filename}`;
      await fs.writeAsStringAsync(filePath, content);
      UltraDevLog.push('SYSTEM', { event: 'log_folder_write_ok', filename, filePath, sizeBytes: content.length });
      return true;
    } catch (err: any) {
      UltraDevLog.push('SYSTEM', { event: 'log_folder_write_fail', filename, error: err?.message });
      return false;
    }
  }

  static async appendLog(filename: string, content: string): Promise<boolean> {
    const fs = this.getFS();
    const dir = this.getLogsDir();
    if (!fs || !dir) return false;
    if (this.initFailed) return false;
    await this.initialize();
    try {
      const filePath = `${dir}/${filename}`;
      const info = await fs.getInfoAsync(filePath);
      if (info.exists) {
        const existing = await fs.readAsStringAsync(filePath);
        await fs.writeAsStringAsync(filePath, existing + content);
      } else {
        await fs.writeAsStringAsync(filePath, content);
      }
      UltraDevLog.push('SYSTEM', { event: 'log_folder_append_ok', filename, appendBytes: content.length });
      return true;
    } catch (err: any) {
      UltraDevLog.push('SYSTEM', { event: 'log_folder_append_fail', filename, error: err?.message });
      return false;
    }
  }

  static async listLogs(): Promise<LogFile[]> {
    try {
      const fs = this.getFS();
      const dir = this.getLogsDir();
      if (!fs || !dir) {
        UltraDevLog.push('SYSTEM', { event: 'log_folder_list_skip', reason: 'no_fs_or_dir' });
        return [];
      }
      try { await this.initialize(); } catch (initErr: any) {
        UltraDevLog.push('SYSTEM', { event: 'log_folder_list_init_fail', error: initErr?.message });
        return [];
      }
      try {
        const info = await fs.getInfoAsync(dir);
        if (!info || !info.exists) return [];
      } catch (infoErr: any) {
        UltraDevLog.push('SYSTEM', { event: 'log_folder_list_info_fail', error: infoErr?.message });
        return [];
      }
      try {
        const files = await fs.readDirectoryAsync(dir);
        if (!Array.isArray(files)) return [];
        const logFiles: LogFile[] = [];
        for (const name of files) {
          try {
            const filePath = `${dir}/${name}`;
            const fileInfo = await fs.getInfoAsync(filePath, { size: true });
            if (fileInfo && fileInfo.exists && !fileInfo.isDirectory) {
              logFiles.push({
                name,
                path: filePath,
                size: (fileInfo.size as number) || 0,
                createdAt: fileInfo.modificationTime
                  ? (fileInfo.modificationTime as number) * 1000
                  : 0,
              });
            }
          } catch {}
        }
        const sorted = logFiles.sort((a, b) => b.createdAt - a.createdAt);
        UltraDevLog.push('SYSTEM', { event: 'log_folder_list_ok', dir, count: sorted.length });
        return sorted;
      } catch (readErr: any) {
        UltraDevLog.push('SYSTEM', { event: 'log_folder_list_read_fail', error: readErr?.message });
        return [];
      }
    } catch (err: any) {
      UltraDevLog.push('SYSTEM', { event: 'log_folder_list_unexpected_fail', error: err?.message });
      return [];
    }
  }

  static getFullPath(filePath: string): string {
    return filePath;
  }
}
