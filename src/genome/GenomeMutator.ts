import type {
  Genome, MutationRequest, MutationResult, MutationRecord, Capability,
} from './types';
import { GenomeValidator } from './GenomeValidator';
import { createHash } from '../utils/crypto';
import { UltraDevLog } from '../utils/UltraDevLog';

interface AiClient {
  chat: (args: { model: string; messages: Array<{ role: string; content: string }>; max_tokens: number }) => Promise<string>;
}

export class GenomeMutator {
  private validator: GenomeValidator;

  constructor(
    private ai: AiClient,
    private getModel: () => Promise<string>
  ) {
    this.validator = new GenomeValidator();
    UltraDevLog.push('SYSTEM', { event: 'genome_mutator_init' });
  }

  async proposeMutations(genome: Genome, userGoal?: string): Promise<MutationRequest[]> {
    const model = await this.getModel();
    const capSummary = genome.capabilities
      .map(c => `- ${c.id} (${c.category}, mutable=${c.mutable}): ${c.name}`)
      .join('\n');

    UltraDevLog.push('SYSTEM', { event: 'genome_mutator_propose_start', genomeId: genome.id, generation: genome.generation, hasUserGoal: !!userGoal, capabilityCount: genome.capabilities.length, model });

    const prompt = userGoal
      ? `The user wants: ${userGoal}\n\nAnalyze the genome and propose mutations to achieve this goal.`
      : `Analyze this genome for improvement opportunities. Consider: missing capabilities, configuration improvements, behavioral enhancements.`;

    const response = await this.ai.chat({
      model,
      messages: [
        {
          role: 'system',
          content: `You are a genome engineer for a self-improving Android agent. You analyze agent genomes and propose structured mutations.

RULES:
- NEVER propose removing or modifying capabilities where mutable=false
- NEVER propose removing safety invariants
- NEVER propose removing capabilities where essential=true
- Mutations must be specific and actionable
- Each mutation must have a clear reason

Valid operations: add_capability, remove_capability, modify_capability, update_config, add_permission, remove_permission, add_behavior, modify_behavior, update_ai_config, update_safety

Respond with a JSON array of mutation requests:
[{"operation": "...", "target": "...", "payload": {...}, "reason": "..."}]
No markdown. No explanation. Just the JSON array.`,
        },
        {
          role: 'user',
          content: `${prompt}

Current genome capabilities:
${capSummary}

Current fitness: ${genome.fitness ? JSON.stringify(genome.fitness) : 'Not evaluated'}
Generation: ${genome.generation}
Mutation history length: ${genome.mutations.length}`,
        },
      ],
      max_tokens: 2000,
    });

    try {
      const parsed = JSON.parse(response);
      const proposals = Array.isArray(parsed) ? parsed as MutationRequest[] : [];
      UltraDevLog.push('SYSTEM', { event: 'genome_mutator_propose_done', genomeId: genome.id, proposalCount: proposals.length, operations: proposals.map(p => p.operation) });
      return proposals;
    } catch (err: any) {
      UltraDevLog.push('SYSTEM', { event: 'genome_mutator_propose_parse_fail', genomeId: genome.id, error: err?.message });
      return [];
    }
  }

  async applyMutations(genome: Genome, mutations: MutationRequest[]): Promise<MutationResult> {
    UltraDevLog.push('SYSTEM', { event: 'genome_mutator_apply_start', genomeId: genome.id, generation: genome.generation, mutationCount: mutations.length });
    const mutated = this.deepClone(genome);
    const records: MutationRecord[] = [];
    const errors: string[] = [];

    for (const mutation of mutations) {
      try {
        const record = this.applyMutation(mutated, mutation);
        records.push(record);
        UltraDevLog.push('SYSTEM', { event: 'genome_mutator_apply_mutation', genomeId: genome.id, operation: mutation.operation, target: mutation.target });
      } catch (error: any) {
        errors.push(`${mutation.operation}/${mutation.target}: ${error.message}`);
        UltraDevLog.push('SYSTEM', { event: 'genome_mutator_apply_mutation_fail', genomeId: genome.id, operation: mutation.operation, target: mutation.target, error: error?.message });
      }
    }

    if (errors.length > 0 && records.length === 0) {
      UltraDevLog.push('SYSTEM', { event: 'genome_mutator_apply_all_failed', genomeId: genome.id, errors });
      return { success: false, error: errors.join('; '), appliedMutations: [] };
    }

    mutated.generation = genome.generation + 1;
    mutated.parentId = genome.id;
    mutated.mutations = [...genome.mutations, ...records];
    mutated.lineageHash = createHash(`${genome.lineageHash}:${records.map(r => r.id).join(',')}`);
    mutated.createdAt = Date.now();
    mutated.fitness = null;

    const validation = this.validator.validate(mutated);
    if (validation.violations.length > 0) {
      UltraDevLog.push('SYSTEM', { event: 'genome_mutator_apply_invalid', genomeId: genome.id, violations: validation.violations.length, firstViolation: validation.violations[0] });
      return { success: false, error: `Validation failed: ${validation.violations[0]}`, appliedMutations: records };
    }

    UltraDevLog.push('SYSTEM', { event: 'genome_mutator_apply_done', fromGenomeId: genome.id, newGenomeId: mutated.id, newGeneration: mutated.generation, appliedCount: records.length, skippedCount: errors.length, warnings: validation.warnings.length });
    return { success: true, mutatedGenome: mutated, appliedMutations: records };
  }

  private applyMutation(genome: Genome, mutation: MutationRequest): MutationRecord {
    const id = createHash(`${genome.id}:${mutation.operation}:${mutation.target}:${Date.now()}`);
    const record: MutationRecord = {
      id,
      operation: mutation.operation,
      target: mutation.target,
      payload: mutation.payload,
      reason: mutation.reason,
      appliedAt: Date.now(),
      fitnessImpact: null,
    };

    switch (mutation.operation) {
      case 'add_capability': {
        const existing = genome.capabilities.find(c => c.id === mutation.target);
        if (existing) throw new Error(`Capability ${mutation.target} already exists`);
        genome.capabilities.push(mutation.payload as Capability);
        break;
      }
      case 'remove_capability': {
        const idx = genome.capabilities.findIndex(c => c.id === mutation.target);
        if (idx === -1) throw new Error(`Capability ${mutation.target} not found`);
        const cap = genome.capabilities[idx];
        if (!cap.mutable) throw new Error(`Capability ${mutation.target} is not mutable`);
        if (cap.essential) throw new Error(`Capability ${mutation.target} is essential`);
        genome.capabilities.splice(idx, 1);
        break;
      }
      case 'modify_capability': {
        const cap = genome.capabilities.find(c => c.id === mutation.target);
        if (!cap) throw new Error(`Capability ${mutation.target} not found`);
        if (!cap.mutable) throw new Error(`Capability ${mutation.target} is not mutable`);
        Object.assign(cap, mutation.payload);
        break;
      }
      case 'update_config': {
        if (!genome.config) genome.config = {};
        Object.assign(genome.config, mutation.payload);
        break;
      }
      case 'update_ai_config': {
        Object.assign(genome.ai, mutation.payload);
        break;
      }
      case 'update_safety': {
        Object.assign(genome.safety, mutation.payload);
        break;
      }
      case 'add_permission': {
        if (!genome.permissions) genome.permissions = [];
        if (!genome.permissions.includes(mutation.target)) {
          genome.permissions.push(mutation.target);
        }
        break;
      }
      case 'remove_permission': {
        if (genome.permissions) {
          genome.permissions = genome.permissions.filter(p => p !== mutation.target);
        }
        break;
      }
      default:
        throw new Error(`Unknown mutation operation: ${mutation.operation}`);
    }

    return record;
  }

  private deepClone<T>(obj: T): T {
    return JSON.parse(JSON.stringify(obj));
  }
}
