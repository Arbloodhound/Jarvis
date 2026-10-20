import type { Genome, SourceEntry, GenomeBuildOutput, BehaviorSpec } from './types';
import { AGENT_TEMPLATES } from './templates/AgentTemplates';
import { GENOME_TEMPLATES } from './templates/GenomeTemplates';
import { createHash } from '../utils/crypto';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

interface AiClient {
  chat: (args: { model: string; messages: Array<{ role: string; content: string }>; max_tokens: number }) => Promise<string>;
}

export class GenomeCompiler {
  constructor(
    private ai: AiClient,
    private getModel: () => Promise<string>
  ) {}

  async compile(genome: Genome): Promise<GenomeBuildOutput> {
    const compileId = Date.now().toString(36);
    DebugLog.executorBranch(compileId, 'genome_compile', 'ENTER', { genomeId: genome.id, generation: genome.generation });
    const sources: SourceEntry[] = [];
    const assets: Array<{ path: string; content: string }> = [];
    const pkg = genome.identity.packageName;
    const pkgPath = pkg.replace(/\./g, '/');

    const fixedSources = await this.resolveFixedSources(genome);
    for (const fs of fixedSources) {
      sources.push({
        ...fs,
        content: this.rewritePackage(fs.content, fs.path, pkg),
      });
    }

    const templateConfig = this.buildTemplateConfig(genome);

    const allTemplates = { ...AGENT_TEMPLATES, ...GENOME_TEMPLATES };
    for (const cap of genome.capabilities) {
      for (const src of cap.sources) {
        if (src.generatedBy === 'template') {
          const templateName = src.path.split('/').pop()!;
          const template = allTemplates[templateName];
          if (template) {
            sources.push({
              path: `src/${pkgPath}/${templateName}`,
              content: this.resolveTemplate(template, templateConfig),
              isTemplate: false,
              generatedBy: 'template',
            });
          }
        }
      }
    }

    for (const spec of genome.behaviorSpecs) {
      const content = await this.generateBehavioralSource(genome, spec, sources);
      sources.push({
        path: spec.targetFile.replace('com/ultra/agent', pkgPath),
        content,
        isTemplate: false,
        generatedBy: 'ai',
      });
    }

    const manifestConfig = genome.manifest;

    const offspringGenome = this.prepareOffspringGenome(genome);
    offspringGenome.lineageHash = createHash(genome.lineageHash + JSON.stringify(offspringGenome));
    assets.push({
      path: 'assets/genome.json',
      content: JSON.stringify(offspringGenome, null, 2),
    });

    const dependencies: string[] = [];
    for (const cap of genome.capabilities) {
      const deps = cap.config['mavenDependencies'];
      if (deps && deps.type === 'string[]') {
        (deps.value as string[]).forEach(d => {
          if (!dependencies.includes(d)) dependencies.push(d);
        });
      }
    }

    DebugLog.executorBranch(compileId, 'genome_compile', 'EXIT', { sourceCount: sources.length, assetCount: assets.length, depCount: dependencies.length });
    return {
      sourceFiles: sources,
      manifestConfig,
      assetFiles: assets,
      dependencies,
    };
  }

  private async resolveFixedSources(genome: Genome): Promise<SourceEntry[]> {
    const resolved: SourceEntry[] = [];
    for (const entry of genome.fixedSources) {
      if (entry.content.startsWith('LOAD_FROM:') || entry.content.startsWith('FIXED:')) {
        const fileName = entry.path.split('/').pop()!;

        try {
          const FileSystem = require('expo-file-system/legacy');
          const docPath = `${FileSystem.documentDirectory}genome_sources/${fileName}`;
          const info = await FileSystem.getInfoAsync(docPath);
          if (info.exists) {
            const content = await FileSystem.readAsStringAsync(docPath);
            resolved.push({ ...entry, content });
            continue;
          }
        } catch {}

        if (entry.content.startsWith('LOAD_FROM:')) {
          try {
            const FileSystem = require('expo-file-system/legacy');
            const filePath = entry.content.replace('LOAD_FROM:', '');
            const content = await FileSystem.readAsStringAsync(
              `${FileSystem.documentDirectory}${filePath}`
            );
            resolved.push({ ...entry, content });
            continue;
          } catch {}
        }

        throw new Error(`GenomeCompiler: source "${entry.path}" not found on device. Run self_replicate first to stage genome sources.`);
      } else {
        resolved.push(entry);
      }
    }
    return resolved;
  }

  private buildTemplateConfig(genome: Genome): Record<string, any> {
    const config: Record<string, any> = {
      packageName: genome.identity.packageName,
      agentName: genome.identity.name,
      greeting: genome.identity.greeting,
      apiBaseUrl: genome.ai.apiBaseUrl,
      defaultModel: genome.ai.defaultModel,
      defaultMaxTokens: genome.ai.models[0]?.maxTokens || 2000,
      maxRetries: genome.ai.maxRetries,
      temperature: genome.ai.temperature,
      dbName: 'ultra_agent.db',
      dbVersion: 1,
      backgroundColor: '#0F0F1A',
      userBubbleColor: '#2A2A3E',
      agentBubbleColor: '#1A1A2E',
      textColor: '#E0E0E0',
      accentColor: '#6C63FF',
      approvalRequired: genome.safety.approvalRequired,
      autoApproved: genome.safety.autoApproved,
      blocked: genome.safety.blocked,
    };

    const uiCap = genome.capabilities.find(c => c.id === 'cap.ui.chat');
    if (uiCap) {
      for (const [key, cv] of Object.entries(uiCap.config)) {
        if (cv.type === 'string') config[key] = cv.value;
      }
    }

    const dbCap = genome.capabilities.find(c => c.id === 'cap.storage.database');
    if (dbCap) {
      if (dbCap.config['dbName']) config['dbName'] = dbCap.config['dbName'].value;
      if (dbCap.config['dbVersion']) config['dbVersion'] = dbCap.config['dbVersion'].value;
    }

    return config;
  }

  resolveTemplate(template: string, config: Record<string, any>): string {
    let result = template;

    result = result.replace(/\{\{#each (\w+)\}\}([\s\S]*?)\{\{\/each\}\}/g, (_, key, body) => {
      const arr = config[key];
      if (!Array.isArray(arr)) return '';
      return arr.map((item: any, index: number) => {
        let line = body.replace(/\{\{this\}\}/g, typeof item === 'string' ? item : JSON.stringify(item));
        line = line.replace(/\{\{@last\}\}/g, String(index === arr.length - 1));
        line = line.replace(/\{\{#unless @last\}\}([\s\S]*?)\{\{\/unless\}\}/g,
          index === arr.length - 1 ? '' : '$1');
        return line;
      }).join('');
    });

    result = result.replace(/\{\{#if (\w+)\}\}([\s\S]*?)\{\{\/if\}\}/g, (_, key, body) => {
      return config[key] ? body : '';
    });

    result = result.replace(/\{\{(\w+)\}\}/g, (_, key) => {
      const val = config[key];
      if (val === undefined) return `{{${key}}}`;
      return String(val);
    });

    return result;
  }

  private async generateBehavioralSource(
    genome: Genome,
    spec: BehaviorSpec,
    existingSources: SourceEntry[]
  ): Promise<string> {
    const model = await this.getModel();
    const pkg = genome.identity.packageName;

    const context = existingSources
      .filter(s => s.content && s.content.length < 3000)
      .slice(0, 5)
      .map(s => `--- ${s.path} ---\n${s.content.slice(0, 1500)}`)
      .join('\n\n');

    const constraints = spec.constraints.map(c => `- ${c}`).join('\n');

    const response = await this.ai.chat({
      model,
      messages: [
        {
          role: 'system',
          content: `You are generating Java source code for a native Android agent called "${genome.identity.name}".
Package: ${pkg}
This agent can: build Android apps from natural language, modify itself through genome mutations, interact with other apps.

Output ONLY compilable Java source. No markdown. No explanation. Include all imports. Handle errors.
The code must compile against android.jar API ${genome.manifest.targetSdk}.`,
        },
        {
          role: 'user',
          content: `Generate: ${spec.targetFile}

PURPOSE:
${spec.description}

CONSTRAINTS:
${constraints}

AVAILABLE INTERFACES (from other components already in the project):
${context}

Generate the complete Java source file.`,
        },
      ],
      max_tokens: 4000,
    });

    return this.cleanCode(response);
  }

  private rewritePackage(content: string, path: string, targetPackage: string): string {
    const pathParts = path.replace('src/', '').split('/');
    pathParts.pop();
    const fullPackage = pathParts.join('.');

    return content.replace(
      /^package\s+[\w.]+;/m,
      `package ${fullPackage};`
    );
  }

  private prepareOffspringGenome(genome: Genome): Genome {
    return {
      ...genome,
      id: `genome_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`,
      generation: genome.generation + 1,
      parentId: genome.id,
      createdAt: Date.now(),
      lineageHash: '',
      mutations: [],
      fitness: null,
    };
  }

  private cleanCode(raw: string): string {
    let code = raw.trim();
    const fenced = code.match(/```(?:java)?\s*([\s\S]*?)```/);
    if (fenced) code = fenced[1].trim();
    const javaStart = code.search(/^(package |import |public |class |interface |abstract |\/\*)/m);
    if (javaStart > 0) code = code.slice(javaStart);
    return code;
  }
}
