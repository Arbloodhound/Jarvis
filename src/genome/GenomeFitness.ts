import type { Genome, FitnessMetrics, TaskPerformance } from './types';
import type { OverallEvaluation } from './TaskChallenges';
import { UltraDevLog } from '../utils/UltraDevLog';

export class GenomeFitness {
  async evaluate(
    genome: Genome,
    buildResult: {
      success: boolean;
      compilationTimeMs: number;
      apkPath?: string;
      apkSizeBytes?: number;
      errors: string[];
    },
    testResults?: {
      installSuccess: boolean;
      launchSuccess: boolean;
      testsPassed: number;
      testsTotal: number;
      runtimeCrashes: number;
    }
  ): Promise<FitnessMetrics> {
    UltraDevLog.push('SYSTEM', { event: 'genome_fitness_evaluate_start', genomeId: genome.id, generation: genome.generation, buildSuccess: buildResult.success, hasTestResults: !!testResults });

    const metrics: FitnessMetrics = {
      buildSuccess: buildResult.success,
      compilationTimeMs: buildResult.compilationTimeMs,
      apkSizeBytes: buildResult.apkSizeBytes || 0,
      installSuccess: testResults?.installSuccess ?? false,
      launchSuccess: testResults?.launchSuccess ?? false,
      testsPassed: testResults?.testsPassed ?? 0,
      testsTotal: testResults?.testsTotal ?? 0,
      runtimeCrashes: testResults?.runtimeCrashes ?? 0,
      capabilityScore: this.computeCapabilityScore(genome, testResults),
      overallScore: 0,
      evaluatedAt: Date.now(),
      taskPerformance: null,
    };

    metrics.overallScore = this.computeOverallScore(metrics);
    UltraDevLog.push('SYSTEM', { event: 'genome_fitness_evaluate_done', genomeId: genome.id, overallScore: metrics.overallScore, capabilityScore: metrics.capabilityScore, buildSuccess: metrics.buildSuccess });
    return metrics;
  }

  async evaluateWithTasks(
    genome: Genome,
    buildResult: {
      success: boolean;
      compilationTimeMs: number;
      apkPath?: string;
      apkSizeBytes?: number;
      errors: string[];
    },
    taskEvaluation: OverallEvaluation
  ): Promise<FitnessMetrics> {
    UltraDevLog.push('SYSTEM', { event: 'genome_fitness_evaluate_with_tasks_start', genomeId: genome.id, generation: genome.generation, challengeCount: taskEvaluation.results.length, buildSuccess: buildResult.success });

    const failedChallenges = taskEvaluation.results
      .filter(r => !r.passed)
      .map(r => r.challengeId);

    const totalTime = taskEvaluation.results.reduce((s, r) => s + r.executionTimeMs, 0);
    const avgTime = taskEvaluation.results.length > 0 ? totalTime / taskEvaluation.results.length : 0;

    const taskPerf: TaskPerformance = {
      challengesPassed: taskEvaluation.results.filter(r => r.passed).length,
      challengesTotal: taskEvaluation.results.length,
      weightedScore: taskEvaluation.weightedScore,
      avgExecutionTimeMs: avgTime,
      failedChallenges,
    };

    const metrics: FitnessMetrics = {
      buildSuccess: buildResult.success,
      compilationTimeMs: buildResult.compilationTimeMs,
      apkSizeBytes: buildResult.apkSizeBytes || 0,
      installSuccess: taskEvaluation.results.length > 0,
      launchSuccess: taskEvaluation.results.some(r => r.passed),
      testsPassed: taskPerf.challengesPassed,
      testsTotal: taskPerf.challengesTotal,
      runtimeCrashes: taskEvaluation.totalCrashes,
      capabilityScore: this.computeCapabilityScore(genome),
      overallScore: 0,
      evaluatedAt: Date.now(),
      taskPerformance: taskPerf,
    };

    metrics.overallScore = this.computeOverallScore(metrics);
    UltraDevLog.push('SYSTEM', { event: 'genome_fitness_evaluate_with_tasks_done', genomeId: genome.id, overallScore: metrics.overallScore, challengesPassed: taskPerf.challengesPassed, challengesTotal: taskPerf.challengesTotal, totalCrashes: taskEvaluation.totalCrashes });
    return metrics;
  }

  private computeCapabilityScore(genome: Genome, testResults?: any): number {
    const total = genome.capabilities.length;
    if (total === 0) return 0;
    const mutable = genome.capabilities.filter(c => c.mutable).length;
    const enabled = genome.capabilities.filter(c => c.enabled).length;
    let score = (enabled / total) * 50;
    score += (mutable / total) * 30;
    if (testResults?.launchSuccess) score += 20;
    return Math.min(100, Math.round(score));
  }

  compareGenerations(parent: FitnessMetrics, offspring: FitnessMetrics): { breakdown: Array<{ metric: string; parent: number; offspring: number; change: number }> } {
    const metrics: Array<{ metric: string; parent: number; offspring: number }> = [
      { metric: 'overallScore', parent: parent.overallScore, offspring: offspring.overallScore },
      { metric: 'capabilityScore', parent: parent.capabilityScore, offspring: offspring.capabilityScore },
      { metric: 'testsPassed', parent: parent.testsPassed, offspring: offspring.testsPassed },
      { metric: 'runtimeCrashes', parent: parent.runtimeCrashes, offspring: offspring.runtimeCrashes },
    ];
    return {
      breakdown: metrics.map(m => ({ ...m, change: m.offspring - m.parent })),
    };
  }

  private computeOverallScore(metrics: FitnessMetrics): number {
    let score = 0;
    if (metrics.buildSuccess) score += 30;
    if (metrics.installSuccess) score += 15;
    if (metrics.launchSuccess) score += 20;
    const testRate = metrics.testsTotal > 0 ? metrics.testsPassed / metrics.testsTotal : 0;
    score += testRate * 20;
    const crashPenalty = Math.min(15, metrics.runtimeCrashes * 5);
    score -= crashPenalty;
    score += metrics.capabilityScore * 0.15;
    if (metrics.taskPerformance) {
      score += metrics.taskPerformance.weightedScore * 0.2;
    }
    return Math.max(0, Math.min(100, Math.round(score)));
  }
}
