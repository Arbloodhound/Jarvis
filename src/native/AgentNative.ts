import { NativeModules, Platform } from 'react-native';
import { UltraDevLog } from '../utils/UltraDevLog';

interface CompileResult {
  success: boolean;
  output: string;
  errors: string;
}

interface DexResult {
  success: boolean;
  dexPath?: string;
  error?: string;
}

export interface AgentNativeInterface {
  writeFile(filePath: string, content: string): Promise<boolean>;
  compileJava(sourcePaths: string[], outputDir: string, classpath: string): Promise<CompileResult>;
  convertToDex(classDir: string, outputDir: string, extraClasspath: string): Promise<DexResult>;
  packageApk(
    projectDir: string,
    packageName: string,
    appName: string,
    versionCode: number,
    versionName: string,
    minSdk: number,
    targetSdk: number,
    permissions: string[],
    activities: Array<{ name: string; exported: boolean; launcher: boolean }>,
    outputPath: string
  ): Promise<string>;
  signApk(unsignedPath: string): Promise<string>;
  installApk(apkPath: string): Promise<string>;
  exec(command: string, workDir: string): Promise<string>;
  getStorageInfo(): Promise<{ total: number; free: number; used: number }>;
  isAllFilesAccessGranted(): Promise<boolean>;
  getInstalledApps(): Promise<Array<{ packageName: string; appName: string }>>;
  launchApp(packageName: string): Promise<{ success: boolean; packageName?: string; error?: string }>;
  setFlashlight(on: boolean): Promise<boolean>;
  sendMediaKey(keyCode: number): Promise<boolean>;
  sendSms(phoneNumber: string, message: string): Promise<boolean>;
  readSms(limit: number, filter: string): Promise<Array<{ id: string; address: string; body: string; date: number; read: boolean }>>;
  readSmsConversation(address: string, limit: number): Promise<Array<{ id: string; address: string; body: string; date: number; direction: string }>>;
  readCalendarEvents(startMs: number, endMs: number, limit: number): Promise<Array<{ id: string; title: string; startDate: number; endDate: number; allDay: boolean; location: string; description: string; calendar: string }>>;
  readCallLog(limit: number): Promise<Array<{ number: string; name: string; type: number; date: number; duration: number }>>;
  getWifiSSID(): Promise<string | null>;
  getConnectedBluetoothDevices(): Promise<string[]>;
  startBackgroundAgent(): Promise<boolean>;
  stopBackgroundAgent(): Promise<boolean>;
  startReActTask(goal: string, appHint: string, taskId: string): Promise<boolean>;
  getContentUriForFile(filePath: string): Promise<string>;
}

const ALLOWED_COMMANDS = ['dalvikvm', 'keytool', 'ls', 'mkdir', 'cp', 'cat', 'chmod', 'find'];
const BLOCKED_METACHAR = [';', '|', '&&', '||', '$(', '`'];

function validateCommand(command: string): boolean {
  const trimmed = command.trim();
  const prefixOk = ALLOWED_COMMANDS.some(
    (c) => trimmed.startsWith(c + ' ') || trimmed === c
  );
  if (!prefixOk) return false;
  return !BLOCKED_METACHAR.some((m) => trimmed.includes(m));
}

const noopModule: AgentNativeInterface = {
  writeFile: async () => true,
  compileJava: async () => ({ success: false, output: '', errors: 'AgentNative not available' }),
  convertToDex: async () => ({ success: false, error: 'AgentNative not available' }),
  packageApk: async () => 'AgentNative not available',
  signApk: async () => 'AgentNative not available',
  installApk: async () => 'AgentNative not available',
  exec: async () => 'AgentNative not available',
  getStorageInfo: async () => ({ total: 0, free: 0, used: 0 }),
  isAllFilesAccessGranted: async () => false,
  getInstalledApps: async () => [],
  launchApp: async () => ({ success: false, error: 'AgentNative not available' }),
  setFlashlight: async () => false,
  sendMediaKey: async () => false,
  sendSms: async () => false,
  readSms: async () => [],
  readSmsConversation: async () => [],
  readCalendarEvents: async () => [],
  readCallLog: async () => [],
  getWifiSSID: async () => null,
  getConnectedBluetoothDevices: async () => [],
  startBackgroundAgent: async () => false,
  stopBackgroundAgent: async () => false,
  startReActTask: async () => false,
  getContentUriForFile: async (filePath: string) => filePath,
};

function createNativeWrapper(): AgentNativeInterface {
  const native = NativeModules.AgentNative;
  if (!native) {
    UltraDevLog.error('AgentNative', 'NativeModules.AgentNative is null — native module not loaded. getInstalledApps will return empty array.');
    return noopModule;
  }
  UltraDevLog.systemEvent('AgentNative', 'NativeModules.AgentNative loaded successfully');

  return {
    writeFile: (filePath: string, content: string) => native.writeFile(filePath, content),
    compileJava: (sourcePaths: string[], outputDir: string, classpath: string) =>
      native.compileJava(sourcePaths, outputDir, classpath),
    convertToDex: (classDir: string, outputDir: string, extraClasspath: string) =>
      native.convertToDex(classDir, outputDir, extraClasspath),
    packageApk: (
      projectDir: string, packageName: string, appName: string,
      versionCode: number, versionName: string,
      minSdk: number, targetSdk: number,
      permissions: string[],
      activities: Array<{ name: string; exported: boolean; launcher: boolean }>,
      outputPath: string
    ) => native.packageApk(
      projectDir, packageName, appName, versionCode, versionName,
      minSdk, targetSdk, permissions, activities, outputPath
    ),
    signApk: (unsignedPath: string) => native.signApk(unsignedPath),
    installApk: (apkPath: string) => native.installApk(apkPath),
    exec: (command: string, workDir: string) => {
      if (!validateCommand(command)) {
        return Promise.reject(new Error(`Command blocked by safety filter: ${command.split(' ')[0]}`));
      }
      return native.exec(command, workDir);
    },
    getStorageInfo: () => native.getStorageInfo(),
    isAllFilesAccessGranted: () => native.isAllFilesAccessGranted ? native.isAllFilesAccessGranted() : Promise.resolve(false),
    getInstalledApps: () => native.getInstalledApps ? native.getInstalledApps() : Promise.resolve([]),
    launchApp: (packageName: string) => native.launchApp ? native.launchApp(packageName) : Promise.resolve({ success: false, error: 'launchApp not available' }),
    setFlashlight: (on: boolean) => native.setFlashlight ? native.setFlashlight(on) : Promise.resolve(false),
    sendMediaKey: (keyCode: number) => native.sendMediaKey ? native.sendMediaKey(keyCode) : Promise.resolve(false),
    sendSms: (phoneNumber: string, message: string) =>
      native.sendSms ? native.sendSms(phoneNumber, message) : Promise.resolve(false),
    readSms: (limit: number, filter: string) =>
      native.readSms ? native.readSms(limit, filter) : Promise.resolve([]),
    readSmsConversation: (address: string, limit: number) =>
      native.readSmsConversation ? native.readSmsConversation(address, limit) : Promise.resolve([]),
    readCalendarEvents: (startMs: number, endMs: number, limit: number) =>
      native.readCalendarEvents ? native.readCalendarEvents(startMs, endMs, limit) : Promise.resolve([]),
    readCallLog: (limit: number) =>
      native.readCallLog ? native.readCallLog(limit) : Promise.resolve([]),
    getWifiSSID: () =>
      native.getWifiSSID ? native.getWifiSSID() : Promise.resolve(null),
    getConnectedBluetoothDevices: () =>
      native.getConnectedBluetoothDevices ? native.getConnectedBluetoothDevices() : Promise.resolve([]),
    startBackgroundAgent: () =>
      native.startBackgroundAgent ? native.startBackgroundAgent() : Promise.resolve(false),
    stopBackgroundAgent: () =>
      native.stopBackgroundAgent ? native.stopBackgroundAgent() : Promise.resolve(false),
    getContentUriForFile: (filePath: string) =>
      native.getContentUriForFile ? native.getContentUriForFile(filePath) : Promise.resolve(filePath),
    startReActTask: (goal: string, appHint: string, taskId: string) =>
      native.startReActTask ? native.startReActTask(goal, appHint, taskId) : Promise.resolve(false),
  };
}

const AgentNative: AgentNativeInterface =
  Platform.OS !== 'web' && NativeModules.AgentNative
    ? createNativeWrapper()
    : noopModule;

export default AgentNative;
