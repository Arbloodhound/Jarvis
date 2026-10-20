import type { Genome, MutationRequest, FitnessMetrics, GenomeBuildOutput } from './types';
import type { AppSpec, BuildProgress } from '../types/appspec';
import type { TaskChallenge } from './TaskChallenges';
import { GenomeCompiler } from './GenomeCompiler';
import { GenomeMutator } from './GenomeMutator';
import { GenomeValidator } from './GenomeValidator';
import { GenomeFitness } from './GenomeFitness';
import { GenomeLineage } from './GenomeLineage';
import { TaskEvaluator } from './TaskEvaluator';
import { getDefaultChallenges, generateChallengesForGoal } from './TaskChallenges';
import { BuildOrchestrator } from '../core/BuildOrchestrator';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

interface SafetyGate {
  requestApproval: (action: string, details: string) => Promise<boolean>;
}

interface AiClient {
  chat: (args: { model: string; messages: Array<{ role: string; content: string }>; max_tokens: number }) => Promise<string>;
}

type ProgressCallback = (phase: string, message: string) => void;

export class SelfImprover {
  private compiler: GenomeCompiler;
  private mutator: GenomeMutator;
  private validator: GenomeValidator;
  private fitness: GenomeFitness;
  private lineage: GenomeLineage;
  private buildOrchestrator: BuildOrchestrator;
  private safetyGate: SafetyGate;
  private taskEvaluator: TaskEvaluator | null;
  private aiClient: AiClient | null;
  private getModel: (() => Promise<string>) | null;

  constructor(
    compiler: GenomeCompiler,
    mutator: GenomeMutator,
    buildOrchestrator: BuildOrchestrator,
    safetyGate: SafetyGate,
    taskEvaluator?: TaskEvaluator,
    aiClient?: AiClient,
    getModel?: () => Promise<string>
  ) {
    this.compiler = compiler;
    this.mutator = mutator;
    this.validator = new GenomeValidator();
    this.fitness = new GenomeFitness();
    this.lineage = new GenomeLineage();
    this.buildOrchestrator = buildOrchestrator;
    this.safetyGate = safetyGate;
    this.taskEvaluator = taskEvaluator || null;
    this.aiClient = aiClient || null;
    this.getModel = getModel || null;
  }

  async improveCycle(
    currentGenome: Genome,
    userGoal?: string,
    onProgress?: ProgressCallback,
    customChallenges?: TaskChallenge[]
  ): Promise<{ genome: Genome; improved: boolean; report: string }> {
    const cycleId = `cycle_${Date.now().toString(36)}`;
    DebugLog.executorBranch(cycleId, 'improve_cycle', 'ENTER', { generation: currentGenome.generation, userGoal: userGoal?.slice(0, 100) });
    this.lineage.record(currentGenome);
    const report: string[] = [];

    onProgress?.('analyzing', 'Analyzing genome for improvements...');
    const proposals = await this.mutator.proposeMutations(currentGenome, userGoal);
    DebugLog.executorBranch(cycleId, 'improve_cycle', 'proposals_generated', { count: proposals.length });

    if (proposals.length === 0) {
      return {
        genome: currentGenome,
        improved: false,
        report: 'No improvements identified.',
      };
    }

    const limited = proposals.slice(0, currentGenome.safety.maxMutationsPerCycle);
    report.push(`Proposed ${proposals.length} mutations, applying ${limited.length}:`);
    for (const p of limited) {
      report.push(`  - ${p.operation} on ${p.target}: ${p.reason}`);
    }

    onProgress?.('approval', `Requesting approval for ${limited.length} mutations...`);
    const mutationSummary = limited
      .map(m => `${m.operation}: ${m.reason}`)
      .join('\n');

    const approved = await this.safetyGate.requestApproval(
      'self_modify',
      `Ultra wants to modify its own genome with ${limited.length} mutations:\n\n${mutationSummary}`
    );

    if (!approved) {
      report.push('User denied mutations.');
      return { genome: currentGenome, improved: false, report: report.join('\n') };
    }

    onProgress?.('mutating', 'Applying mutations...');
    const mutationResult = await this.mutator.applyMutations(currentGenome, limited);

    if (!mutationResult.success) {
      report.push('Mutation failed:');
      if (mutationResult.error) report.push(`  x ${mutationResult.error}`);
      return { genome: currentGenome, improved: false, report: report.join('\n') };
    }

    const mutatedGenome = mutationResult.mutatedGenome!;
    report.push('Mutations applied successfully.');

    onProgress?.('compiling', 'Compiling mutated genome...');
    const buildStartTime = Date.now();
    let buildOutput: GenomeBuildOutput;
    try {
      buildOutput = await this.compiler.compile(mutatedGenome);
    } catch (e) {
      report.push(`Genome compilation failed: ${(e as Error).message}`);
      report.push('Rolling back to previous genome.');
      return { genome: currentGenome, improved: false, report: report.join('\n') };
    }

    onProgress?.('building', 'Building offspring APK...');
    let buildResult: { apkPath: string; spec: AppSpec };
    try {
      const spec = this.genomeBuildToAppSpec(mutatedGenome, buildOutput);
      buildResult = await this.buildOrchestrator.buildFromSpec(spec, (progress: BuildProgress) => {
        onProgress?.(progress.phase, progress.message);
      });
    } catch (e) {
      const compilationTime = Date.now() - buildStartTime;
      report.push(`Build failed: ${(e as Error).message}`);

      const failedFitness = await this.fitness.evaluate(mutatedGenome, {
        success: false,
        compilationTimeMs: compilationTime,
        errors: [(e as Error).message],
      });
      mutatedGenome.fitness = failedFitness;
      report.push(`Failed build fitness: ${failedFitness.overallScore}/100`);
      report.push('Rolling back to previous genome.');
      return { genome: currentGenome, improved: false, report: report.join('\n') };
    }

    const compilationTime = Date.now() - buildStartTime;
    const buildResultData = {
      success: true,
      compilationTimeMs: compilationTime,
      apkPath: buildResult.apkPath,
      apkSizeBytes: 0,
      errors: [] as string[],
    };

    let newFitness: FitnessMetrics;

    if (this.taskEvaluator) {
      onProgress?.('installing', 'Installing offspring for testing...');
      const challenges = await this.resolveChallenges(
        mutatedGenome.identity.packageName,
        userGoal,
        customChallenges
      );

      report.push(`Running ${challenges.length} task challenges...`);

      onProgress?.('testing', `Running ${challenges.length} real-world task challenges...`);
      const taskEvaluation = await this.taskEvaluator.evaluateOffspring(
        mutatedGenome.identity.packageName,
        buildResult.apkPath,
        challenges,
        onProgress
      );

      report.push(`Task results: ${taskEvaluation.results.filter(r => r.passed).length}/${taskEvaluation.results.length} passed`);
      report.push(`Weighted task score: ${(taskEvaluation.weightedScore * 100).toFixed(1)}%`);
      report.push(`Total crashes: ${taskEvaluation.totalCrashes}`);

      for (const r of taskEvaluation.results) {
        const status = r.passed ? 'PASS' : 'FAIL';
        report.push(`  [${status}] ${r.challengeId} (${(r.partialScore * 100).toFixed(0)}%) ${r.error || ''}`);
      }

      onProgress?.('evaluating', 'Computing task-aware fitness score...');
      newFitness = await this.fitness.evaluateWithTasks(mutatedGenome, buildResultData, taskEvaluation);
    } else {
      onProgress?.('evaluating', 'Evaluating offspring fitness (build-only)...');
      newFitness = await this.fitness.evaluate(mutatedGenome, buildResultData);
    }

    mutatedGenome.fitness = newFitness;
    report.push(`Fitness score: ${newFitness.overallScore}/100`);

    if (newFitness.taskPerformance) {
      report.push(`Task performance: ${newFitness.taskPerformance.challengesPassed}/${newFitness.taskPerformance.challengesTotal} challenges`);
    }

    const previousScore = currentGenome.fitness?.overallScore ?? 0;
    const improvement = newFitness.overallScore - previousScore;

    if (currentGenome.fitness) {
      const comparison = this.fitness.compareGenerations(currentGenome.fitness, newFitness);
      report.push('Generation comparison:');
      for (const b of comparison.breakdown) {
        const sign = b.change >= 0 ? '+' : '';
        report.push(`  ${b.metric}: ${b.parent.toFixed(1)} → ${b.offspring.toFixed(1)} (${sign}${b.change.toFixed(1)})`);
      }
    }

    for (const mut of mutatedGenome.mutations) {
      if (mut.fitnessImpact === null) {
        mut.fitnessImpact = improvement / mutatedGenome.mutations.filter(m => m.fitnessImpact === null).length;
      }
    }

    if (improvement >= 0) {
      this.lineage.record(mutatedGenome);
      report.push(`Improvement: +${improvement} points. Keeping mutated genome.`);
      DebugLog.executorBranch(cycleId, 'improve_cycle', 'EXIT', { improved: improvement > 0, fitnessScore: newFitness.overallScore, improvement });
      return { genome: mutatedGenome, improved: improvement > 0, report: report.join('\n') };
    } else {
      report.push(`Degradation: ${improvement} points. Rolling back.`);
      DebugLog.executorBranch(cycleId, 'improve_cycle', 'EXIT', { improved: false, fitnessScore: newFitness.overallScore, improvement, rolledBack: true });
      return { genome: currentGenome, improved: false, report: report.join('\n') };
    }
  }

  async evolve(
    genome: Genome,
    maxCycles: number = 5,
    userGoal?: string,
    onProgress?: ProgressCallback,
    customChallenges?: TaskChallenge[]
  ): Promise<{ genome: Genome; totalCycles: number; totalImprovements: number; report: string }> {
    let current = genome;
    let totalImprovements = 0;
    const fullReport: string[] = [];

    for (let cycle = 1; cycle <= maxCycles; cycle++) {
      onProgress?.('cycle', `Evolution cycle ${cycle}/${maxCycles}`);
      fullReport.push(`\n=== Cycle ${cycle} ===`);

      const result = await this.improveCycle(current, userGoal, onProgress, customChallenges);
      fullReport.push(result.report);

      if (result.improved) {
        current = result.genome;
        totalImprovements++;
      } else {
        fullReport.push('No improvement. Stopping evolution.');
        break;
      }
    }

    return {
      genome: current,
      totalCycles: fullReport.filter(l => l.startsWith('\n=== Cycle')).length,
      totalImprovements,
      report: fullReport.join('\n'),
    };
  }

  private isValidChallenge(c: any): c is TaskChallenge {
    return (
      c &&
      typeof c === 'object' &&
      typeof c.id === 'string' &&
      typeof c.name === 'string' &&
      Array.isArray(c.steps) &&
      Array.isArray(c.successCriteria) &&
      typeof c.timeoutMs === 'number' &&
      typeof c.weight === 'number'
    );
  }

  private async resolveChallenges(
    packageName: string,
    userGoal?: string,
    customChallenges?: TaskChallenge[]
  ): Promise<TaskChallenge[]> {
    if (customChallenges && Array.isArray(customChallenges) && customChallenges.length > 0) {
      const valid = customChallenges.filter(c => this.isValidChallenge(c));
      if (valid.length > 0) return valid;
    }

    const defaults = getDefaultChallenges(packageName);

    if (userGoal && this.aiClient && this.getModel) {
      try {
        const model = await this.getModel();
        const goalChallenges = await generateChallengesForGoal(
          this.aiClient,
          model,
          packageName,
          userGoal
        );
        if (goalChallenges.length > 0) {
          return [...defaults, ...goalChallenges];
        }
      } catch {}
    }

    return defaults;
  }

  getLineage(): GenomeLineage {
    return this.lineage;
  }

  genomeBuildToAppSpec(genome: Genome, output: GenomeBuildOutput): AppSpec {
    const activities = genome.capabilities
      .filter(c => c.category === 'ui')
      .flatMap(c => c.sources)
      .filter(s => s.path.includes('Activity'))
      .map((s, i) => ({
        className: genome.identity.packageName + '.' + s.path.split('/').pop()!.replace('.java', ''),
        isLauncher: i === 0,
        exported: i === 0,
      }));

    return {
      appName: genome.identity.name,
      packageName: genome.identity.packageName,
      versionCode: genome.manifest.versionCode,
      versionName: genome.manifest.versionName,
      minSdk: genome.manifest.minSdk,
      targetSdk: genome.manifest.targetSdk,
      permissions: genome.manifest.permissions,
      dependencies: output.dependencies || [],
      theme: {
        primaryColor: '#6C63FF',
        backgroundColor: '#0F0F1A',
        accentColor: '#6C63FF',
        textColor: '#E0E0E0',
        isDark: true,
      },
      files: output.sourceFiles.map(s => ({
        path: s.path,
        purpose: 'Genome-compiled source',
        dependsOn: [] as string[],
        content: s.content,
        status: 'generated' as const,
      })),
      activities,
      strings: { app_name: genome.identity.name },
      architectureNotes: genome.identity.description,
    };
  }
}
