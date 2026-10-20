import { Platform } from 'react-native';
import * as ExpoFileSystem from 'expo-file-system/legacy';
import { ModelRouter } from './ModelRouter';
import { DebugEngine } from './DebugEngine';
import { StorageManager } from '../services/StorageManager';
import { Logger } from '../utils/Logger';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import { AppArchitect } from './AppArchitect';
import { ProjectGenerator } from './ProjectGenerator';
import { MavenResolver } from './MavenResolver';
import { BuildOrchestrator } from './BuildOrchestrator';
import type { AppSpec, BuildProgress } from '../types/appspec';

const FileSystem: any = Platform.OS !== 'web' ? ExpoFileSystem : null;
const isNative = Platform.OS !== 'web';

interface BuildResult {
  success: boolean;
  apkPath?: string;
  spec?: AppSpec;
  error?: string;
  debugAttempts?: number;
  totalCost?: number;
}

const TOOL_URLS = {
  ecj: 'https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar',
  d8: 'https://repo1.maven.org/maven2/com/android/tools/r8/8.2.47/r8-8.2.47.jar',
  androidJar: 'https://raw.githubusercontent.com/nicologies/AnyAndroidSdkStub/main/android-33/android.jar',
};

export class BuildSystem {
  private modelRouter: ModelRouter;
  private debugEngine: DebugEngine;
  private storage: StorageManager;
  private logger: Logger;
  private toolsReady: boolean;
  private toolsDir: string;
  private projectsDir: string;
  private orchestrator: BuildOrchestrator | null;
  private lastBuiltSpec: AppSpec | null;

  constructor(modelRouter: ModelRouter, debugEngine: DebugEngine, storage: StorageManager) {
    this.modelRouter = modelRouter;
    this.debugEngine = debugEngine;
    this.storage = storage;
    this.logger = new Logger('BuildSystem');
    this.toolsReady = false;
    const docDir = (isNative && FileSystem?.documentDirectory) || '';
    this.toolsDir = docDir + 'build-tools/';
    this.projectsDir = docDir + 'projects/';
    this.orchestrator = null;
    this.lastBuiltSpec = null;
  }

  async initialize(): Promise<void> {
    if (!isNative) {
      this.logger.info('BuildSystem initialized (web mode - build unavailable)');
      return;
    }
    const ecjExists = await this.storage.fileExists(this.toolsDir + 'ecj.jar');
    const d8Exists = await this.storage.fileExists(this.toolsDir + 'd8.jar');
    const androidJarExists = await this.storage.fileExists(this.toolsDir + 'android.jar');
    this.toolsReady = ecjExists && d8Exists && androidJarExists;
    if (this.toolsReady) this.logger.info('Build tools found (ecj + d8 + android.jar)');
    else this.logger.info('Build tools not fully installed. Will download on first build.');
    const pi = await FileSystem.getInfoAsync(this.projectsDir);
    if (!pi.exists) await FileSystem.makeDirectoryAsync(this.projectsDir, { intermediates: true });
  }

  async ensureTools(): Promise<void> {
    if (!isNative) throw new Error('Build system requires Android device');
    if (this.toolsReady) return;
    this.logger.info('Downloading build tools...');
    const di = await FileSystem.getInfoAsync(this.toolsDir);
    if (!di.exists) await FileSystem.makeDirectoryAsync(this.toolsDir, { intermediates: true });
    const downloads: Array<{ name: string; url: string; dest: string }> = [
      { name: 'ECJ', url: TOOL_URLS.ecj, dest: this.toolsDir + 'ecj.jar' },
      { name: 'D8/R8', url: TOOL_URLS.d8, dest: this.toolsDir + 'd8.jar' },
      { name: 'Android SDK stubs', url: TOOL_URLS.androidJar, dest: this.toolsDir + 'android.jar' },
    ];
    for (const dl of downloads) {
      const exists = await this.storage.fileExists(dl.dest);
      if (exists) {
        this.logger.info(`${dl.name} already downloaded`);
        continue;
      }
      try {
        await FileSystem.downloadAsync(dl.url, dl.dest);
        this.logger.info(`${dl.name} downloaded`);
      } catch (e: any) {
        throw new Error(`Failed to download ${dl.name}: ${e.message}`);
      }
    }
    this.toolsReady = true;
    this.logger.info('Build tools ready');
  }

  getOrchestrator(): BuildOrchestrator {
    if (!this.orchestrator) {
      const architect = new AppArchitect(this.modelRouter);
      const generator = new ProjectGenerator(this.modelRouter);
      const maven = new MavenResolver();
      this.orchestrator = new BuildOrchestrator(architect, generator, maven);
    }
    return this.orchestrator;
  }

  async buildApp(description: string, _taskId: string, onProgress?: (p: BuildProgress) => void): Promise<BuildResult> {
    if (!isNative) return { success: false, error: 'Build system requires Android device' };
    DebugLog.buildStart(_taskId, description);
    const startTime = Date.now();
    try {
      await this.ensureTools();
      const orchestrator = this.getOrchestrator();
      const wrappedProgress = onProgress ? (p: BuildProgress) => {
        DebugLog.buildPhase(_taskId, p.phase, p.message);
        onProgress(p);
      } : undefined;
      const { apkPath, spec } = await orchestrator.buildFromDescription(description, undefined, wrappedProgress);
      this.lastBuiltSpec = spec;
      DebugLog.buildComplete(_taskId, true, `${Date.now() - startTime}ms`);
      return { success: true, apkPath, spec };
    } catch (e: any) {
      DebugLog.buildComplete(_taskId, false, `${Date.now() - startTime}ms: ${e.message}`);
      this.logger.error('Build failed: ' + e.message);
      return { success: false, error: e.message };
    }
  }

  async buildFromSpec(spec: AppSpec, onProgress?: (p: BuildProgress) => void): Promise<BuildResult> {
    if (!isNative) return { success: false, error: 'Build system requires Android device' };
    try {
      await this.ensureTools();
      const orchestrator = this.getOrchestrator();
      const { apkPath, spec: builtSpec } = await orchestrator.buildFromSpec(spec, onProgress);
      this.lastBuiltSpec = builtSpec;
      return { success: true, apkPath, spec: builtSpec };
    } catch (e: any) {
      this.logger.error('Build from spec failed: ' + e.message);
      return { success: false, error: e.message };
    }
  }

  async installApk(apkPath: string): Promise<void> {
    if (!isNative) throw new Error('APK install requires Android device with native module');
    const AgentNative = (await import('../native/AgentNative')).default;
    await AgentNative.installApk(apkPath);
  }

  isReady(): boolean { return this.toolsReady; }

  getLastBuiltSpec(): AppSpec | null { return this.lastBuiltSpec; }
}
