import type { Genome, MutationResult } from './types';
import { UltraDevLog } from '../utils/UltraDevLog';

export class GenomeValidator {
  validate(genome: Genome): { violations: string[]; warnings: string[] } {
    const violations: string[] = [];
    const warnings: string[] = [];

    for (const inv of genome.safety.invariants) {
      const holds = this.checkPredicate(inv.predicate, genome);
      if (!holds) {
        const msg = `Invariant "${inv.id}" violated: ${inv.description}`;
        if (inv.enforcement === 'reject') violations.push(msg);
        else warnings.push(msg);
      }
    }

    const capIds = new Set(genome.capabilities.map(c => c.id));
    for (const cap of genome.capabilities) {
      for (const req of cap.requires) {
        if (!capIds.has(req)) {
          violations.push(`Capability "${cap.id}" requires "${req}" which is missing`);
        }
      }
      for (const conflict of cap.conflicts) {
        if (capIds.has(conflict)) {
          violations.push(`Capability "${cap.id}" conflicts with "${conflict}" which is present`);
        }
      }
    }

    const provided = new Map<string, string>();
    const required: Array<{ capId: string; iface: string }> = [];
    for (const cap of genome.capabilities) {
      for (const contract of cap.interfaces) {
        if (contract.direction === 'provides') {
          provided.set(contract.name, cap.id);
        } else {
          required.push({ capId: cap.id, iface: contract.name });
        }
      }
    }
    for (const req of required) {
      if (!provided.has(req.iface)) {
        violations.push(`Capability "${req.capId}" requires interface "${req.iface}" but no capability provides it`);
      }
    }

    for (const cap of genome.capabilities) {
      if (cap.essential && !capIds.has(cap.id)) {
        violations.push(`Essential capability "${cap.id}" is missing`);
      }
    }

    if (!genome.capabilities.some(c => c.category === 'ui')) {
      violations.push('Genome must have at least one UI capability');
    }

    if (!genome.capabilities.some(c => c.id === 'cap.network.ai')) {
      violations.push('Genome must have AI client capability');
    }

    UltraDevLog.push('SYSTEM', { event: 'genome_validate', genomeId: genome.id, generation: genome.generation, capabilityCount: genome.capabilities.length, violations: violations.length, warnings: warnings.length, valid: violations.length === 0 });

    return { violations, warnings };
  }

  validateMutation(mutation: MutationResult, genome: Genome): { valid: boolean; reason?: string } {
    if (!mutation.success) return { valid: false, reason: 'Mutation was not successful' };
    if (!mutation.mutatedGenome) return { valid: false, reason: 'No mutated genome produced' };
    const { violations } = this.validate(mutation.mutatedGenome);
    if (violations.length > 0) {
      UltraDevLog.push('SYSTEM', { event: 'genome_mutation_validate_fail', genomeId: genome.id, violationCount: violations.length, firstViolation: violations[0] });
      return { valid: false, reason: violations[0] };
    }
    UltraDevLog.push('SYSTEM', { event: 'genome_mutation_validate_ok', genomeId: genome.id });
    return { valid: true };
  }

  private checkPredicate(predicate: string, genome: Genome): boolean {
    try {
      if (predicate.startsWith('has_capability:')) {
        const capId = predicate.replace('has_capability:', '');
        return genome.capabilities.some(c => c.id === capId);
      }
      if (predicate.startsWith('no_capability:')) {
        const capId = predicate.replace('no_capability:', '');
        return !genome.capabilities.some(c => c.id === capId);
      }
      if (predicate.startsWith('max_dangerous:')) {
        const max = parseInt(predicate.replace('max_dangerous:', ''), 10);
        const dangerous = genome.capabilities.filter(c => c.riskLevel === 'dangerous').length;
        return dangerous <= max;
      }
      if (predicate === 'has_ui_capability') {
        return genome.capabilities.some(c => c.category === 'ui');
      }
      if (predicate === 'safety_enabled') {
        return genome.safety.enabled ?? true;
      }
      if (predicate.startsWith('max_capabilities:')) {
        const max = parseInt(predicate.replace('max_capabilities:', ''), 10);
        return genome.capabilities.length <= max;
      }
      return true;
    } catch {
      return false;
    }
  }
}
