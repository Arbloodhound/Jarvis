import type { AppSpec, FileSpec, BuildProgress } from '../types/appspec';
import { ModelRouter } from './ModelRouter';
import { Logger } from '../utils/Logger';
import { UltraDevLog } from '../utils/UltraDevLog';

type ProgressCallback = (progress: BuildProgress) => void;

export class ProjectGenerator {
  private modelRouter: ModelRouter;
  private logger: Logger;

  constructor(modelRouter: ModelRouter) {
    this.modelRouter = modelRouter;
    this.logger = new Logger('ProjectGenerator');
  }

  async generateAll(spec: AppSpec, onProgress?: ProgressCallback): Promise<AppSpec> {
    const order = this.topologicalSort(spec.files);
    const generated = new Map<string, string>();
    let count = 0;

    this.logger.info(`Generating ${order.length} files in dependency order`);
    UltraDevLog.push('SYSTEM', { event: 'project_generator_start', appName: spec.appName, fileCount: order.length });

    for (const filePath of order) {
      const file = spec.files.find(f => f.path === filePath);
      if (!file) continue;

      if (file.content && file.content.length > 0 && file.status === 'generated') {
        generated.set(file.path, file.content);
        count++;
        this.logger.info(`Preserved existing ${file.path} (${count}/${order.length})`);
        continue;
      }

      onProgress?.({
        phase: 'generating',
        message: `Generating ${file.path.split('/').pop()}...`,
        filesGenerated: count,
        filesTotal: order.length,
        compileAttempt: 0,
        maxCompileAttempts: 0,
        errors: [],
      });

      try {
        const content = await this.generateFile(spec, file, generated);
        file.content = content;
        file.status = 'generated';
        generated.set(file.path, content);
        count++;
        this.logger.info(`Generated ${file.path} (${count}/${order.length})`);
      } catch (err: any) {
        file.status = 'error';
        file.lastError = err.message;
        this.logger.error(`Failed to generate ${file.path}: ${err.message}`);
      }
    }

    return spec;
  }

  async fixFile(spec: AppSpec, file: FileSpec, errors: string): Promise<string> {
    this.logger.info(`Fixing file ${file.path} for errors: ${errors.substring(0, 100)}...`);

    const systemPrompt = `You are debugging a Java compilation error. Fix the source code.
CRITICAL: ALL UI must be built PROGRAMMATICALLY. Do NOT use R.layout references, XML layouts, or any resource references that require aapt2/R.java.
Output ONLY the complete fixed Java source file. No markdown. No explanation.
Do not remove functionality. Fix only what's broken.`;

    const userPrompt = `File: ${file.path}
Purpose: ${file.purpose}

Current source:
${file.content}

Compilation errors:
${errors}

Fix the code.`;

    const result = await this.modelRouter.complete(userPrompt, {
      systemPrompt,
      model: this.modelRouter.selectModel('debug'),
      temperature: 0.3,
      maxTokens: 3000,
      taskId: 'project-generator-fix',
      agentId: 'project-generator',
    });

    return this.cleanGeneratedCode(result.content);
  }

  private async generateFile(
    spec: AppSpec,
    file: FileSpec,
    alreadyGenerated: Map<string, string>
  ): Promise<string> {
    const depContext = file.dependsOn
      .map(dep => {
        const content = alreadyGenerated.get(dep);
        return content ? `--- ${dep} ---\n${content}` : null;
      })
      .filter(Boolean)
      .join('\n\n');

    const systemPrompt = `You are an expert Android developer. Generate a COMPLETE, COMPILABLE Java source file.

Project: ${spec.appName}
Package: ${spec.packageName}
Min SDK: ${spec.minSdk} / Target SDK: ${spec.targetSdk}
Theme: background=${spec.theme.backgroundColor}, primary=${spec.theme.primaryColor}, accent=${spec.theme.accentColor}, text=${spec.theme.textColor}
Architecture: ${spec.architectureNotes}

Available string resources: ${JSON.stringify(spec.strings)}

Rules:
- Output ONLY the Java source code. No markdown fences. No explanation. Just code.
- Must compile against android.jar API ${spec.targetSdk}
- Include all imports
- Handle null safety
- Include proper error handling (try-catch)
- CRITICAL: ALL UI must be built PROGRAMMATICALLY. Do NOT use setContentView(R.layout.xxx) or any R.* references. There is no aapt2 or R.java generator. Create all views in code: new LinearLayout(this), new TextView(this), etc. Set layout params, colors, and properties programmatically.
- For network operations: use AsyncTask or Thread (no coroutines, this is Java)
- All UI updates on main thread (runOnUiThread)
- Close all streams in finally blocks
- Use the theme colors programmatically via Color.parseColor("#hex")`;

    const userPrompt = `Generate: ${file.path}
Purpose: ${file.purpose}
${depContext ? `\nDependency source code for reference:\n${depContext}` : ''}`;

    const result = await this.modelRouter.complete(userPrompt, {
      systemPrompt,
      model: this.modelRouter.selectModel('code'),
      temperature: 0.3,
      maxTokens: 3000,
      taskId: 'project-generator',
      agentId: 'project-generator',
    });

    return this.cleanGeneratedCode(result.content);
  }

  private topologicalSort(files: FileSpec[]): string[] {
    const visited = new Set<string>();
    const result: string[] = [];
    const visiting = new Set<string>();

    const pathMap = new Map(files.map(f => [f.path, f]));

    const visit = (path: string) => {
      if (visited.has(path)) return;
      if (visiting.has(path)) return;
      visiting.add(path);
      const file = pathMap.get(path);
      if (file) {
        for (const dep of file.dependsOn) {
          visit(dep);
        }
      }
      visiting.delete(path);
      visited.add(path);
      result.push(path);
    };

    for (const file of files) {
      visit(file.path);
    }

    return result;
  }

  private cleanGeneratedCode(raw: string): string {
    let code = raw.trim();
    const fenced = code.match(/```(?:java|xml)?\s*([\s\S]*?)```/);
    if (fenced) code = fenced[1].trim();
    const javaStart = code.search(/^(package |import |public |class |interface |abstract |\/\*)/m);
    const xmlStart = code.search(/^<\?xml|^<[A-Z]/m);
    const start = Math.min(
      javaStart >= 0 ? javaStart : Infinity,
      xmlStart >= 0 ? xmlStart : Infinity
    );
    if (start !== Infinity && start > 0) code = code.slice(start);
    return code;
  }
}
