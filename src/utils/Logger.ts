import { Platform } from 'react-native';
import * as ExpoFileSystem from 'expo-file-system/legacy';

const FileSystem: any = Platform.OS !== 'web' ? ExpoFileSystem : null;

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

interface LogEntry {
  timestamp: number;
  level: LogLevel;
  context: string;
  message: string;
  metadata?: Record<string, unknown>;
}

// Bridge: set by UltraDevLog init to avoid circular imports.
// Only warn/error levels are mirrored to durable truth.
type UltraBridgeFn = (level: LogLevel, context: string, message: string, metadata?: Record<string, unknown>) => void;
let _ultraBridge: UltraBridgeFn | null = null;
export function setLoggerUltraBridge(fn: UltraBridgeFn): void {
  _ultraBridge = fn;
}

export class Logger {
  private static entries: LogEntry[] = [];
  private static readonly MAX_ENTRIES = 500;
  private context: string;

  constructor(context: string) {
    this.context = context;
  }

  debug(message: string, metadata?: Record<string, unknown>): void {
    this.log('debug', message, metadata);
  }

  info(message: string, metadata?: Record<string, unknown>): void {
    this.log('info', message, metadata);
  }

  warn(message: string, metadata?: Record<string, unknown>): void {
    this.log('warn', message, metadata);
  }

  error(message: string, metadata?: Record<string, unknown>): void {
    this.log('error', message, metadata);
  }

  private log(level: LogLevel, message: string, metadata?: Record<string, unknown>): void {
    const entry: LogEntry = {
      timestamp: Date.now(),
      level,
      context: this.context,
      message,
      metadata,
    };

    Logger.entries.unshift(entry);
    if (Logger.entries.length > Logger.MAX_ENTRIES) {
      Logger.entries = Logger.entries.slice(0, Logger.MAX_ENTRIES);
    }

    if (__DEV__) {
      const prefix = `[${new Date(entry.timestamp).toISOString()}][${this.context}]`;
      const methods: Record<LogLevel, (...args: any[]) => void> = {
        debug: console.debug,
        info: console.info,
        warn: console.warn,
        error: console.error,
      };
      methods[level](`${prefix} ${message}`, metadata || '');
    }

    // Mirror warn/error to durable UltraDevLog truth via bridge (avoids circular import)
    if ((level === 'warn' || level === 'error') && _ultraBridge) {
      try {
        _ultraBridge(level, this.context, message, metadata);
      } catch {
        // never let bridge failure break logging
      }
    }
  }

  static getEntries(level?: LogLevel, limit?: number): LogEntry[] {
    let filtered = level ? Logger.entries.filter(e => e.level === level) : Logger.entries;
    return limit ? filtered.slice(0, limit) : filtered;
  }

  private static getLogDir(): string {
    if (!FileSystem || !FileSystem.documentDirectory) return '';
    return `${FileSystem.documentDirectory}logs/`;
  }

  static async flush(): Promise<void> {
    if (Platform.OS === 'web') return;
    try {
      const logDir = Logger.getLogDir();
      if (!logDir) return;
      const dirInfo = await FileSystem.getInfoAsync(logDir);
      if (!dirInfo.exists) {
        await FileSystem.makeDirectoryAsync(logDir, { intermediates: true });
      }
      const filename = `log_${Date.now()}.json`;
      await FileSystem.writeAsStringAsync(
        `${logDir}${filename}`,
        JSON.stringify(Logger.entries)
      );
      Logger.entries = [];
    } catch (err) {
      console.error('Failed to flush logs:', err);
    }
  }

  static clear(): void {
    Logger.entries = [];
  }

  static export(): string {
    return JSON.stringify(Logger.entries, null, 2);
  }

  static async cleanOldLogs(maxAgeDays: number = 7): Promise<number> {
    if (Platform.OS === 'web') return 0;
    try {
      const logDir = Logger.getLogDir();
      if (!logDir) return 0;
      const dirInfo = await FileSystem.getInfoAsync(logDir);
      if (!dirInfo.exists) return 0;
      const files = await FileSystem.readDirectoryAsync(logDir);
      const cutoff = Date.now() - maxAgeDays * 86400000;
      let cleaned = 0;
      for (const file of files) {
        const match = file.match(/log_(\d+)\.json/);
        if (match && parseInt(match[1], 10) < cutoff) {
          await FileSystem.deleteAsync(`${logDir}${file}`);
          cleaned++;
        }
      }
      return cleaned;
    } catch {
      return 0;
    }
  }
}
