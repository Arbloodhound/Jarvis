import type { Genome, MutationRecord, FitnessMetrics, TaskPerformance } from './types';
import { createHash } from '../utils/crypto';
import { UltraDevLog } from '../utils/UltraDevLog';

export interface LineageNode {
  genomeId: string;
  generation: number;
  parentId: string | null;
  lineageHash: string;
  createdAt: number;
  mutationCount: number;
  fitness: FitnessMetrics | null;
  mutations: MutationRecord[];
  taskPerformance: TaskPerformance | null;
}

export class GenomeLineage {
  private nodes: Map<string, LineageNode> = new Map();

  record(genome: Genome): void {
    this.nodes.set(genome.id, {
      genomeId: genome.id,
      generation: genome.generation,
      parentId: genome.parentId,
      lineageHash: genome.lineageHash,
      createdAt: genome.createdAt,
      mutationCount: genome.mutations.length,
      fitness: genome.fitness,
      mutations: genome.mutations,
      taskPerformance: genome.fitness?.taskPerformance ?? null,
    });
    UltraDevLog.push('SYSTEM', { event: 'genome_lineage_record', genomeId: genome.id, generation: genome.generation, parentId: genome.parentId, mutationCount: genome.mutations.length, hasfitness: !!genome.fitness, totalNodes: this.nodes.size });
  }

  getAncestry(genomeId: string): LineageNode[] {
    const chain: LineageNode[] = [];
    let current = this.nodes.get(genomeId);
    while (current) {
      chain.unshift(current);
      current = current.parentId ? this.nodes.get(current.parentId) : undefined;
    }
    UltraDevLog.push('SYSTEM', { event: 'genome_lineage_ancestry', genomeId, chainLength: chain.length, generations: chain.map(n => n.generation) });
    return chain;
  }

  verifyChain(genomeId: string): { valid: boolean; brokenAt?: string } {
    const chain = this.getAncestry(genomeId);
    for (let i = 1; i < chain.length; i++) {
      const parent = chain[i - 1];
      const child = chain[i];
      if (child.parentId !== parent.genomeId) {
        UltraDevLog.push('SYSTEM', { event: 'genome_lineage_chain_broken', genomeId, brokenAt: child.genomeId, parentExpected: child.parentId, parentActual: parent.genomeId });
        return { valid: false, brokenAt: child.genomeId };
      }
    }
    UltraDevLog.push('SYSTEM', { event: 'genome_lineage_chain_verified', genomeId, chainLength: chain.length, valid: true });
    return { valid: true };
  }

  getFitnessTrajectory(genomeId: string): Array<{ generation: number; score: number }> {
    return this.getAncestry(genomeId)
      .filter(n => n.fitness !== null)
      .map(n => ({
        generation: n.generation,
        score: n.fitness!.overallScore,
      }));
  }

  getImprovementRate(genomeId: string): number {
    const trajectory = this.getFitnessTrajectory(genomeId);
    if (trajectory.length < 2) return 0;
    const first = trajectory[0].score;
    const last = trajectory[trajectory.length - 1].score;
    const rate = (last - first) / trajectory.length;
    UltraDevLog.push('SYSTEM', { event: 'genome_lineage_improvement_rate', genomeId, rate, firstScore: first, lastScore: last, generations: trajectory.length });
    return rate;
  }

  buildLineageHash(genome: Genome): string {
    const parentHash = genome.parentId
      ? (this.nodes.get(genome.parentId)?.lineageHash ?? 'root')
      : 'root';
    const mutationIds = genome.mutations.map(m => m.id).join(',');
    return createHash(`${parentHash}:${genome.id}:${mutationIds}`);
  }

  getAllNodes(): LineageNode[] {
    return Array.from(this.nodes.values()).sort((a, b) => a.generation - b.generation);
  }

  getBestGenome(): LineageNode | null {
    let best: LineageNode | null = null;
    let bestScore = -1;
    for (const node of this.nodes.values()) {
      const score = node.fitness?.overallScore ?? -1;
      if (score > bestScore) {
        bestScore = score;
        best = node;
      }
    }
    return best;
  }
}
