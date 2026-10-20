import type { AppSpec, BuildProgress, BuildPhase, FileSpec } from '../types/appspec';
import { AppArchitect } from './AppArchitect';
import { ProjectGenerator } from './ProjectGenerator';
import { MavenResolver } from './MavenResolver';
import AgentNative from '../native/AgentNative';
import { Logger } from '../utils/Logger';
import { UltraDevLog } from '../utils/UltraDevLog';
import { Platform } from 'react-native';
import * as ExpoFileSystem from 'expo-file-system/legacy';

const FileSystem: any = Platform.OS !== 'web' ? ExpoFileSystem : null;
const isNative = Platform.OS !== 'web';

type ProgressCallback = (progress: BuildProgress) => void;

interface BuildConfig {
  maxDebugIterations: number;
  maxGlobalRetries: number;
  projectBaseDir: string;
}

const DEFAULT_CONFIG: BuildConfig = {
  maxDebugIterations: 4,
  maxGlobalRetries: 3,
  projectBaseDir: '',
};

export class BuildOrchestrator {
  private architect: AppArchitect;
  private generator: ProjectGenerator;
  private maven: MavenResolver;
  private config: BuildConfig;
  private logger: Logger;

  constructor(
    architect: AppArchitect,
    generator: ProjectGenerator,
    maven: MavenResolver,
    config?: Partial<BuildConfig>
  ) {
    this.architect = architect;
    this.generator = generator;
    this.maven = maven;
    const docDir = (isNative && FileSystem?.documentDirectory) || '';
    this.config = {
      ...DEFAULT_CONFIG,
      projectBaseDir: docDir + 'ultra_projects',
      ...config,
    };
    this.logger = new Logger('BuildOrchestrator');
  }

  async buildFromDescription(
    description: string,
    conversationContext?: string,
    onProgress?: ProgressCallback
  ): Promise<{ apkPath: string; spec: AppSpec }> {
    this.emit(onProgress, 'specifying', 'Designing app architecture...');
    const spec = await this.architect.designApp(description, conversationContext);
    this.logger.info(`Spec created: ${spec.appName} (${spec.files.length} files)`);
    UltraDevLog.push('SYSTEM', { event: 'build_orchestrator_spec_created', appName: spec.appName, packageName: spec.packageName, fileCount: spec.files.length, depCount: spec.dependencies?.length ?? 0 });
    return this.buildFromSpec(spec, onProgress);
  }

  async buildFromSpec(
    spec: AppSpec,
    onProgress?: ProgressCallback
  ): Promise<{ apkPath: string; spec: AppSpec }> {
    if (!isNative) throw new Error('Build requires Android device');

    const projectDir = `${this.config.projectBaseDir}/${spec.packageName.replace(/\./g, '_')}_${Date.now()}`;
    await FileSystem.makeDirectoryAsync(projectDir, { intermediates: true });

    let depPaths: string[] = [];
    if (spec.dependencies.length > 0) {
      this.emit(onProgress, 'resolving_deps', `Resolving ${spec.dependencies.length} dependencies...`);
      depPaths = await this.maven.resolveAll(spec.dependencies);
      this.logger.info(`Resolved ${depPaths.length} dependencies`);
    }

    this.emit(onProgress, 'generating', 'Generating source code...');
    const generatedSpec = await this.generator.generateAll(spec, onProgress);

    this.emit(onProgress, 'scaffolding', 'Writing project files...');
    await this.scaffold(projectDir, generatedSpec);

    this.emit(onProgress, 'compiling', 'Compiling...');
    const androidJar = (isNative && FileSystem?.documentDirectory || '') + 'build-tools/android.jar';
    const fullClasspath = [androidJar, ...depPaths].join(':');

    const compileSuccess = await this.compileWithDebugLoop(
      projectDir, generatedSpec, fullClasspath, onProgress
    );

    if (!compileSuccess) {
      this.emit(onProgress, 'failed', 'Compilation failed after all retry attempts.');
      throw new Error('Build failed: could not produce clean compilation.');
    }

    this.emit(onProgress, 'dexing', 'Converting to DEX...');
    const dexResult = await AgentNative.convertToDex(
      `${projectDir}/build/classes`,
      `${projectDir}/build/dex`,
      fullClasspath
    );
    if (!dexResult.success) throw new Error(`DEX failed: ${dexResult.error}`);

    this.emit(onProgress, 'packaging', 'Packaging APK...');
    const unsignedApk = `${projectDir}/build/${spec.appName.replace(/\s/g, '')}.unsigned.apk`;
    await AgentNative.packageApk(
      projectDir,
      spec.packageName,
      spec.appName,
      spec.versionCode,
      spec.versionName,
      spec.minSdk,
      spec.targetSdk,
      spec.permissions,
      spec.activities.map(a => ({
        name: a.className,
        launcher: a.isLauncher,
        exported: a.exported,
      })),
      unsignedApk
    );

    this.emit(onProgress, 'signing', 'Signing APK...');
    const signedApk = await AgentNative.signApk(unsignedApk);
    if (!signedApk) throw new Error('Signing failed');

    this.emit(onProgress, 'installing', 'Installing...');
    await AgentNative.installApk(signedApk);

    this.emit(onProgress, 'complete', `${spec.appName} built and installed successfully.`);
    return { apkPath: signedApk, spec: generatedSpec };
  }

  private async scaffold(projectDir: string, spec: AppSpec): Promise<void> {
    for (const file of spec.files) {
      if (!file.content) continue;
      const fullPath = `${projectDir}/${file.path}`;
      await AgentNative.writeFile(fullPath, file.content);
    }

    if (Object.keys(spec.strings).length > 0) {
      const stringsXml = this.buildStringsXml(spec.strings);
      await AgentNative.writeFile(`${projectDir}/res/values/strings.xml`, stringsXml);
    }
  }

  private buildStringsXml(strings: Record<string, string>): string {
    const entries = Object.entries(strings)
      .map(([k, v]) => `    <string name="${k}">${this.escapeXml(v)}</string>`)
      .join('\n');
    return `<?xml version="1.0" encoding="utf-8"?>\n<resources>\n${entries}\n</resources>`;
  }

  private escapeXml(s: string): string {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&apos;');
  }

  private async compileWithDebugLoop(
    projectDir: string,
    spec: AppSpec,
    classpath: string,
    onProgress?: ProgressCallback
  ): Promise<boolean> {
    for (let attempt = 1; attempt <= this.config.maxGlobalRetries; attempt++) {
      this.emit(onProgress, 'compiling',
        `Compilation attempt ${attempt}/${this.config.maxGlobalRetries}...`,
        { compileAttempt: attempt, maxCompileAttempts: this.config.maxGlobalRetries }
      );

      const sourcePaths = spec.files
        .filter(f => f.path.endsWith('.java') && f.content)
        .map(f => `${projectDir}/${f.path}`);

      if (sourcePaths.length === 0) {
        this.logger.error('No source files to compile');
        return false;
      }

      const outputDir = `${projectDir}/build/classes`;
      await FileSystem.makeDirectoryAsync(outputDir, { intermediates: true });

      const result = await AgentNative.compileJava(sourcePaths, outputDir, classpath);

      if (result.success) {
        this.logger.info('Compilation successful');
        return true;
      }

      const errorsByFile = this.parseCompilationErrors(result.errors, projectDir);

      if (Object.keys(errorsByFile).length === 0) {
        this.emit(onProgress, 'debugging', 'Unknown errors, regenerating all files...');
        for (const file of spec.files.filter(f => f.path.endsWith('.java'))) {
          file.content = await this.generator.fixFile(spec, file, result.errors);
          await AgentNative.writeFile(`${projectDir}/${file.path}`, file.content);
        }
        continue;
      }

      let fixedAny = false;
      for (const [filePath, errors] of Object.entries(errorsByFile)) {
        const file = spec.files.find(f => filePath.endsWith(f.path));
        if (!file) continue;

        for (let fixAttempt = 0; fixAttempt < this.config.maxDebugIterations; fixAttempt++) {
          this.emit(onProgress, 'debugging',
            `Fixing ${file.path.split('/').pop()} (attempt ${fixAttempt + 1}/${this.config.maxDebugIterations})...`
          );

          file.content = await this.generator.fixFile(spec, file, errors);
          await AgentNative.writeFile(`${projectDir}/${file.path}`, file.content);

          const recheck = await AgentNative.compileJava(
            [`${projectDir}/${file.path}`], outputDir, classpath
          );

          if (recheck.success) {
            file.status = 'compiled';
            fixedAny = true;
            break;
          }
          file.lastError = recheck.errors;
        }
      }

      if (fixedAny && attempt === this.config.maxGlobalRetries) {
        this.emit(onProgress, 'compiling', 'Final verification compile...');
        const finalResult = await AgentNative.compileJava(sourcePaths, outputDir, classpath);
        if (finalResult.success) {
          this.logger.info('Final recompile successful after fixes');
          return true;
        }
      }

      if (!fixedAny && attempt >= this.config.maxGlobalRetries) return false;
    }

    return false;
  }

  private parseCompilationErrors(errorText: string, projectDir: string): Record<string, string> {
    const result: Record<string, string> = {};
    const lines = errorText.split('\n');
    let currentFile = '';
    let currentErrors = '';

    for (const line of lines) {
      const match = line.match(/^(.+\.java):(\d+):/);
      if (match) {
        if (currentFile && currentErrors) {
          result[currentFile] = (result[currentFile] || '') + currentErrors;
        }
        currentFile = match[1];
        currentErrors = line + '\n';
      } else if (currentFile) {
        currentErrors += line + '\n';
      }
    }
    if (currentFile && currentErrors) {
      result[currentFile] = (result[currentFile] || '') + currentErrors;
    }

    return result;
  }

  private emit(
    cb: ProgressCallback | undefined,
    phase: BuildPhase,
    message: string,
    extra?: Partial<BuildProgress>
  ) {
    this.logger.info(`[${phase}] ${message}`);
    cb?.({
      phase,
      message,
      filesGenerated: 0,
      filesTotal: 0,
      compileAttempt: 0,
      maxCompileAttempts: this.config.maxGlobalRetries,
      errors: [],
      ...extra,
    });
  }
}
