import { KnowledgeGraph, EntityType, RelationType } from './KnowledgeGraph';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import type { ModelRouter } from './ModelRouter';
import { logAICall } from '../utils/AICallLogger';

export interface CorrectionResult {
  applied: boolean;
  description: string;
  entitiesModified: number;
  relationsModified: number;
}

export class UserCorrection {
  constructor(private graph: KnowledgeGraph, private ai: ModelRouter) {}

  async process(correction: string): Promise<CorrectionResult> {
    DebugLog.push('KG_ENTITY' as any, { event: 'correction_start', input: correction.slice(0, 80) });

    if (this.ai.hasApiKey()) {
      try {
        return await this.aiCorrection(correction);
      } catch (e: any) {
        DebugLog.error('UserCorrection', `AI correction failed: ${e.message}`);
      }
    }

    return this.regexCorrection(correction);
  }

  private async aiCorrection(correction: string): Promise<CorrectionResult> {
    const graphSummary = this.graph.getSummary();
    const prompt = `The user is correcting something the system knows. Parse the correction.

CORRECTION: "${correction}"

CURRENT KNOWLEDGE:
${graphSummary}

Respond ONLY with JSON:
{
  "actions": [
    {"type": "update_entity", "name": "entity name", "field": "property name", "oldValue": "...", "newValue": "..."},
    {"type": "remove_relation", "from": "entity1", "to": "entity2", "relationType": "..."},
    {"type": "add_relation", "from": "entity1", "to": "entity2", "relationType": "lives_in|works_at|has_number|..."},
    {"type": "remove_entity", "name": "entity to forget"},
    {"type": "add_alias", "entity": "existing entity", "alias": "new alias"}
  ]
}

If nothing to correct: {"actions": []}`;

    const aiLog = logAICall({ agentId: 'correction', prompt, maxTokens: 400, temperature: 0.1 });
    const result = await this.ai.complete(prompt, { taskId: `corr_${Date.now().toString(36)}`, agentId: 'correction', maxTokens: 400, temperature: 0.1 });
    aiLog.logResponse(result.content, result.cost);

    const parsed = JSON.parse(result.content.replace(/```json|```/g, '').trim());
    if (!parsed.actions || !Array.isArray(parsed.actions) || parsed.actions.length === 0) {
      return { applied: false, description: 'Could not parse correction.', entitiesModified: 0, relationsModified: 0 };
    }

    let entitiesModified = 0;
    let relationsModified = 0;
    const descriptions: string[] = [];

    for (const action of parsed.actions) {
      switch (action.type) {
        case 'update_entity': {
          const entity = this.graph.findEntityByName(action.name);
          if (entity) {
            entity.properties[action.field] = action.newValue;
            entity.updatedAt = Date.now();
            entitiesModified++;
            descriptions.push(`Updated ${action.name}: ${action.field} → ${action.newValue}`);
          }
          break;
        }
        case 'remove_relation': {
          const from = this.graph.findEntityByName(action.from);
          const to = this.graph.findEntityByName(action.to);
          if (from && to) {
            const deleted = this.graph.softDeleteRelation(from.id, to.id, action.relationType);
            if (deleted > 0) {
              relationsModified += deleted;
              descriptions.push(`Removed: ${action.from} ${action.relationType} ${action.to}`);
            }
          }
          break;
        }
        case 'add_relation': {
          const fromEntity = this.graph.addEntity({ type: 'person' as EntityType, name: action.from, confidence: 0.9, source: 'user_correction' });
          const toType: EntityType = action.relationType === 'lives_in' || action.relationType === 'works_at' ? 'place' : 'person';
          const toEntity = this.graph.addEntity({ type: toType, name: action.to, confidence: 0.9, source: 'user_correction' });
          this.graph.addRelation({ fromEntity: fromEntity.id, toEntity: toEntity.id, type: action.relationType as RelationType, confidence: 0.95, source: 'user_correction' });
          relationsModified++;
          descriptions.push(`Added: ${action.from} ${action.relationType} ${action.to}`);
          break;
        }
        case 'remove_entity': {
          const entity = this.graph.findEntityByName(action.name);
          if (entity) {
            entity.confidence = 0;
            entitiesModified++;
            descriptions.push(`Removed: ${action.name}`);
          }
          break;
        }
        case 'add_alias': {
          const entity = this.graph.findEntityByName(action.entity);
          if (entity && action.alias) {
            entity.aliases.push(action.alias.toLowerCase());
            entitiesModified++;
            descriptions.push(`Added alias "${action.alias}" to ${action.entity}`);
          }
          break;
        }
      }
    }

    await this.graph.persist();

    const desc = descriptions.join('; ') || 'No changes made';
    DebugLog.push('KG_ENTITY' as any, { event: 'correction_applied', entitiesModified, relationsModified, actions: parsed.actions.length, description: desc });

    return { applied: entitiesModified > 0 || relationsModified > 0, description: `Got it. ${desc}.`, entitiesModified, relationsModified };
  }

  private regexCorrection(correction: string): CorrectionResult {
    const c = correction.toLowerCase();
    let applied = false;
    let desc = '';

    const movedMatch = c.match(/(\w+(?:\s+\w+)?)\s+moved\s+to\s+(\w+(?:\s+\w+)?)/i);
    if (movedMatch) {
      const person = this.graph.findEntityByName(movedMatch[1]);
      if (person) {
        const place = this.graph.addEntity({ type: 'place', name: movedMatch[2], confidence: 0.9, source: 'user_correction' });
        this.graph.softDeleteRelation(person.id, '', 'lives_in');
        this.graph.addRelation({ fromEntity: person.id, toEntity: place.id, type: 'lives_in', confidence: 0.95, source: 'user_correction' });
        desc = `Updated: ${person.name} now lives in ${movedMatch[2]}`;
        applied = true;
      }
    }

    const forgetMatch = c.match(/(?:forget|remove|delete)\s+(?:about\s+)?(\w+(?:\s+\w+)?)/i);
    if (!applied && forgetMatch) {
      const entity = this.graph.findEntityByName(forgetMatch[1]);
      if (entity) { entity.confidence = 0; desc = `Removed ${entity.name} from memory`; applied = true; }
    }

    if (applied) this.graph.persist().catch(() => {});

    DebugLog.push('KG_ENTITY' as any, { event: 'correction_regex', applied, description: desc });
    return { applied, description: applied ? `Got it. ${desc}.` : 'I didn\'t understand the correction. Try "X moved to Y" or "forget about X".', entitiesModified: applied ? 1 : 0, relationsModified: 0 };
  }
}
