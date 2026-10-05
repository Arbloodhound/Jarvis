import AsyncStorage from '@react-native-async-storage/async-storage';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import { logAICall } from '../utils/AICallLogger';

export type EntityType = 'person' | 'place' | 'app' | 'preference' | 'routine' | 'account' | 'device' | 'topic';
export type RelationType = 'is_contact_of' | 'lives_in' | 'works_at' | 'located_in' | 'prefers' | 'uses_app' | 'communicates_via' | 'related_to' | 'alias_of' | 'has_number' | 'has_email' | 'happens_at' | 'involves' | 'costs' | 'scheduled_for';

export interface Entity {
  id: string; type: EntityType; name: string; aliases: string[];
  properties: Record<string, any>; confidence: number;
  createdAt: number; updatedAt: number; accessCount: number; lastAccessed: number;
}

export interface Relation {
  id: string; fromEntity: string; toEntity: string; type: RelationType;
  properties: Record<string, any>; confidence: number; createdAt: number; source: string;
}

export interface GraphResult {
  entity: Entity | null;
  relations: Array<{ relation: Relation; targetEntity: Entity }>;
  paths: string[];
}

const GRAPH_PREFIX = 'kg_chunk_';
const GRAPH_META_KEY = 'kg_meta';
const CHUNK_SIZE = 80_000;
const MAX_ENTITIES = 500;
const MAX_RELATIONS = 2000;

export class KnowledgeGraph {
  private entities: Map<string, Entity> = new Map();
  private relations: Relation[] = [];
  private aliasIndex: Map<string, string> = new Map();
  private dirty = false;

  constructor() {}

  async initialize(): Promise<void> {
    try {
      const metaRaw = await AsyncStorage.getItem(GRAPH_META_KEY);
      if (!metaRaw) { DebugLog.systemEvent('KnowledgeGraph', 'No stored graph — starting fresh'); return; }
      const meta = JSON.parse(metaRaw);
      const keys = Array.from({ length: meta.chunks || 0 }, (_, i) => `${GRAPH_PREFIX}${i}`);
      const pairs = await AsyncStorage.multiGet(keys);
      let fullJson = '';
      for (const [, value] of pairs) { if (value) fullJson += value; }
      if (fullJson) {
        const data = JSON.parse(fullJson);
        if (data.entities) for (const e of data.entities) this.entities.set(e.id, e);
        if (data.relations) this.relations = data.relations;
        this.rebuildAliasIndex();
      }
      DebugLog.push('KG_ENTITY' as any, { event: 'loaded', entities: this.entities.size, relations: this.relations.length, chunks: meta.chunks });
    } catch (err: any) { DebugLog.error('KnowledgeGraph', `Init failed: ${err.message}`); }
  }

  addEntity(params: { type: EntityType; name: string; aliases?: string[]; properties?: Record<string, any>; confidence?: number; source?: string }): Entity {
    const nameLower = params.name.toLowerCase().trim();
    let existing = this.findEntityByName(nameLower);
    if (existing) {
      if (params.aliases) for (const alias of params.aliases) { const a = alias.toLowerCase().trim(); if (!existing.aliases.includes(a)) { existing.aliases.push(a); this.aliasIndex.set(a, existing.id); } }
      if (params.properties) existing.properties = { ...existing.properties, ...params.properties };
      if (params.confidence && params.confidence > existing.confidence) existing.confidence = params.confidence;
      existing.updatedAt = Date.now();
      this.dirty = true;
      return existing;
    }
    const id = `e_${params.type.slice(0, 3)}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 5)}`;
    const entity: Entity = {
      id, type: params.type, name: params.name,
      aliases: [nameLower, ...(params.aliases || []).map(a => a.toLowerCase().trim())],
      properties: params.properties || {}, confidence: params.confidence ?? 0.5,
      createdAt: Date.now(), updatedAt: Date.now(), accessCount: 0, lastAccessed: Date.now(),
    };
    this.entities.set(id, entity);
    for (const alias of entity.aliases) this.aliasIndex.set(alias, id);
    if (this.entities.size > MAX_ENTITIES) this.evictEntities();
    this.dirty = true;
    DebugLog.push('KG_ENTITY' as any, { event: 'added', name: entity.name, type: entity.type, id });
    return entity;
  }

  addRelation(params: { fromEntity: string; toEntity: string; type: RelationType; properties?: Record<string, any>; confidence?: number; source?: string }): Relation {
    const existing = this.relations.find(r => r.fromEntity === params.fromEntity && r.toEntity === params.toEntity && r.type === params.type);
    if (existing) { if (params.properties) existing.properties = { ...existing.properties, ...params.properties }; this.dirty = true; return existing; }
    const relation: Relation = {
      id: `r_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 5)}`,
      fromEntity: params.fromEntity, toEntity: params.toEntity, type: params.type,
      properties: params.properties || {}, confidence: params.confidence ?? 0.5,
      createdAt: Date.now(), source: params.source || 'system',
    };
    this.relations.push(relation);
    if (this.relations.length > MAX_RELATIONS) { this.relations.sort((a, b) => b.confidence - a.confidence); this.relations = this.relations.slice(0, MAX_RELATIONS); }
    this.dirty = true;
    DebugLog.push('KG_RELATION' as any, { event: 'added', type: params.type, from: params.fromEntity, to: params.toEntity });
    return relation;
  }

  findEntityByName(name: string): Entity | null {
    const lower = name.toLowerCase().trim().replace(/^(my|the|a|an)\s+/i, '');
    const id = this.aliasIndex.get(lower);
    if (id) { const entity = this.entities.get(id); if (entity) { entity.accessCount++; entity.lastAccessed = Date.now(); return entity; } }
    for (const [alias, entityId] of this.aliasIndex) {
      if (alias.includes(lower) || lower.includes(alias)) { const entity = this.entities.get(entityId); if (entity) { entity.accessCount++; entity.lastAccessed = Date.now(); return entity; } }
    }
    return null;
  }

  resolve(naturalText: string): GraphResult {
    const lower = naturalText.toLowerCase().trim();
    const result: GraphResult = { entity: null, relations: [], paths: [] };
    const words = lower.split(/\s+/);
    for (let len = words.length; len > 0; len--) {
      for (let start = 0; start <= words.length - len; start++) {
        const phrase = words.slice(start, start + len).join(' ').replace(/^(my|the|a|an)\s+/i, '');
        const entity = this.findEntityByName(phrase);
        if (entity) {
          result.entity = entity;
          result.relations = this.getRelations(entity.id);
          result.paths = result.relations.map(r => `${entity.name} \u2014[${r.relation.type}]\u2192 ${r.targetEntity.name}`);
          DebugLog.push('KG_RESOLVE' as any, { event: 'resolved', query: naturalText.slice(0, 40), entity: entity.name, relations: result.relations.length });
          return result;
        }
      }
    }
    DebugLog.push('KG_RESOLVE' as any, { event: 'not_found', query: naturalText.slice(0, 40) });
    return result;
  }

  getRelations(entityId: string): Array<{ relation: Relation; targetEntity: Entity }> {
    const results: Array<{ relation: Relation; targetEntity: Entity }> = [];
    for (const rel of this.relations) {
      if (rel.fromEntity === entityId) { const t = this.entities.get(rel.toEntity); if (t) results.push({ relation: rel, targetEntity: t }); }
      else if (rel.toEntity === entityId) { const t = this.entities.get(rel.fromEntity); if (t) results.push({ relation: rel, targetEntity: t }); }
    }
    return results;
  }

  getByType(type: EntityType): Entity[] { return Array.from(this.entities.values()).filter(e => e.type === type); }

  async learnFromInteraction(userInput: string, capability: string, result: string, ai?: { complete: (prompt: string, opts: any) => Promise<{ content: string }> }): Promise<void> {
    DebugLog.push('KG_ENTITY' as any, { event: 'learn_start', inputLength: userInput.length, capability });
    if (ai) {
      try {
        const prompt = `Extract entities and relationships.\n\nUSER: "${userInput.slice(0, 200)}"\nACTION: ${capability}\nRESULT: "${result.slice(0, 200)}"\n\nJSON only:\n{"entities":[{"name":"...","type":"person|place|app|preference","aliases":["..."]}],"relations":[{"from":"name1","to":"name2","type":"is_contact_of|lives_in|works_at|prefers|alias_of|has_number"}]}\n\nIf nothing extractable: {"entities":[],"relations":[]}`;
        const aiLog = logAICall({ agentId: 'kg_extractor', prompt, maxTokens: 300, temperature: 0.1 });
        const aiResult = await ai.complete(prompt, { taskId: `kg_${Date.now().toString(36)}`, agentId: 'kg_extractor', maxTokens: 300, temperature: 0.1 });
        aiLog.logResponse(aiResult.content);
        const parsed = JSON.parse(aiResult.content.replace(/```json|```/g, '').trim());
        if (parsed.entities) for (const e of parsed.entities) { if (e.name && e.type) this.addEntity({ type: e.type, name: e.name, aliases: e.aliases || [], confidence: 0.7, source: `ai:${capability}` }); }
        if (parsed.relations) for (const r of parsed.relations) { if (r.from && r.to && r.type) { const f = this.findEntityByName(r.from); const t = this.findEntityByName(r.to); if (f && t) this.addRelation({ fromEntity: f.id, toEntity: t.id, type: r.type as any, source: `ai:${capability}`, confidence: 0.6 }); } }
        if (this.dirty) this.scheduleSave();
        return;
      } catch { /* fall through to regex */ }
    }
    const input = userInput.toLowerCase();
    const personPatterns = [/(?:call|text|message|email|contact)\s+(?:my\s+)?(\w+(?:\s+\w+)?)/i, /(?:my|the)\s+(mom|mother|dad|father|wife|husband|brother|sister|boss|friend|girlfriend|boyfriend|partner|son|daughter)\b/i];
    for (const pattern of personPatterns) { const match = input.match(pattern); if (match && match[1].length > 1 && match[1].length < 30) this.addEntity({ type: 'person', name: match[1].trim(), confidence: 0.6, source: `regex:${capability}` }); }
    const placePatterns = [/(?:in|to|from|at|near)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)*)/];
    for (const pattern of placePatterns) { const match = userInput.match(pattern); if (match && match[1].length > 1) this.addEntity({ type: 'place', name: match[1].trim(), confidence: 0.5, source: `regex:${capability}` }); }
    if (capability === 'app_launch') {
      const appMatch = result.match(/launched?\s+"?([^"]+)"?/i) || result.match(/opened?\s+"?([^"]+)"?/i);
      if (appMatch) { const e = this.addEntity({ type: 'app', name: appMatch[1].trim(), confidence: 0.9, source: 'app_launch' }); e.properties.launchCount = (e.properties.launchCount || 0) + 1; e.properties.lastLaunched = Date.now(); this.dirty = true; }
    }
    if (this.dirty) this.scheduleSave();
  }

  learnRelation(fromName: string, toName: string, relType: RelationType, fromType: EntityType = 'person', toType: EntityType = 'place', confidence = 0.8, source = 'explicit'): void {
    const f = this.addEntity({ type: fromType, name: fromName, confidence }); const t = this.addEntity({ type: toType, name: toName, confidence });
    this.addRelation({ fromEntity: f.id, toEntity: t.id, type: relType, confidence, source }); this.scheduleSave();
  }

  getContextFor(text: string): string {
    const result = this.resolve(text);
    if (!result.entity) return '';
    const lines = [`[KNOWN] ${result.entity.name} (${result.entity.type})`];
    const props = Object.entries(result.entity.properties).filter(([k]) => !k.startsWith('_')).map(([k, v]) => `${k}: ${v}`).join(', ');
    if (props) lines.push(`  Properties: ${props}`);
    for (const { relation, targetEntity } of result.relations) lines.push(`  ${relation.type} \u2192 ${targetEntity.name} (${targetEntity.type})`);
    return lines.join('\n');
  }

  resolveAll(naturalText: string): GraphResult[] {
    const lower = naturalText.toLowerCase().trim().replace(/^(my|the|a|an)\s+/i, '');
    const results: GraphResult[] = [];
    const seen = new Set<string>();

    for (const [alias, entityId] of this.aliasIndex) {
      if (alias.includes(lower) || lower.includes(alias)) {
        if (seen.has(entityId)) continue;
        seen.add(entityId);
        const entity = this.entities.get(entityId);
        if (entity && entity.confidence > 0.1) {
          const relations = this.getRelations(entity.id);
          results.push({
            entity,
            relations,
            paths: relations.map(r => `${entity.name} —[${r.relation.type}]→ ${r.targetEntity.name}`),
          });
        }
      }
    }

    results.sort((a, b) => (b.entity?.accessCount || 0) - (a.entity?.accessCount || 0));

    if (results.length > 1) {
      DebugLog.push('KG_RESOLVE' as any, {
        event: 'ambiguous',
        query: naturalText.slice(0, 40),
        matchCount: results.length,
        matches: results.slice(0, 5).map(r => r.entity?.name),
      });
    }

    return results;
  }

  softDeleteRelation(fromEntityId: string, toEntityId: string, relationType?: string): number {
    let deleted = 0;
    for (const rel of this.relations) {
      const fromMatch = rel.fromEntity === fromEntityId || rel.toEntity === fromEntityId;
      const toMatch = !toEntityId || rel.toEntity === toEntityId || rel.fromEntity === toEntityId;
      if (fromMatch && toMatch && (!relationType || rel.type === relationType)) {
        rel.confidence = 0;
        deleted++;
      }
    }
    if (deleted > 0) this.dirty = true;
    return deleted;
  }

  getSummary(): string {
    const people = this.getByType('person'); const places = this.getByType('place'); const apps = this.getByType('app');
    const lines: string[] = [];
    if (people.length > 0) lines.push(`People (${people.length}): ${people.slice(0, 10).map(p => p.name).join(', ')}`);
    if (places.length > 0) lines.push(`Places (${places.length}): ${places.slice(0, 10).map(p => p.name).join(', ')}`);
    if (apps.length > 0) { const topApps = apps.sort((a, b) => (b.properties.launchCount || 0) - (a.properties.launchCount || 0)).slice(0, 10); lines.push(`Top apps: ${topApps.map(a => `${a.name}(${a.properties.launchCount || 0})`).join(', ')}`); }
    return lines.join('\n');
  }

  private saveTimeout: ReturnType<typeof setTimeout> | null = null;
  private scheduleSave(): void { if (this.saveTimeout) return; this.saveTimeout = setTimeout(async () => { this.saveTimeout = null; await this.persist(); }, 5000); }

  async persist(): Promise<void> {
    if (!this.dirty) return;
    const startMs = Date.now();
    try {
      const data = JSON.stringify({ entities: Array.from(this.entities.values()), relations: this.relations });
      const chunks: Array<[string, string]> = [];
      for (let i = 0; i < data.length; i += CHUNK_SIZE) chunks.push([`${GRAPH_PREFIX}${chunks.length}`, data.slice(i, i + CHUNK_SIZE)]);
      await AsyncStorage.setItem(GRAPH_META_KEY, JSON.stringify({ chunks: chunks.length, entities: this.entities.size, relations: this.relations.length, updatedAt: Date.now() }));
      await AsyncStorage.multiSet(chunks);
      try { const allKeys = await AsyncStorage.getAllKeys(); const stale = allKeys.filter(k => k.startsWith(GRAPH_PREFIX) && parseInt(k.replace(GRAPH_PREFIX, ''), 10) >= chunks.length); if (stale.length > 0) await AsyncStorage.multiRemove(stale); } catch {}
      this.dirty = false;
      DebugLog.push('KG_PERSIST' as any, { event: 'saved', entities: this.entities.size, relations: this.relations.length, chunks: chunks.length, bytes: data.length, durationMs: Date.now() - startMs });
    } catch (err: any) { DebugLog.error('KnowledgeGraph', `Persist failed: ${err.message}`); }
  }

  private rebuildAliasIndex(): void { this.aliasIndex.clear(); for (const e of this.entities.values()) for (const a of e.aliases) this.aliasIndex.set(a, e.id); }

  private evictEntities(): void {
    const before = this.entities.size;
    const sorted = Array.from(this.entities.values()).sort((a, b) => (a.accessCount * a.confidence) - (b.accessCount * b.confidence));
    const toRemove = Math.floor(sorted.length * 0.2);
    const evicted: Array<{ name: string; type: string }> = [];
    for (let i = 0; i < toRemove; i++) {
      evicted.push({ name: sorted[i].name, type: sorted[i].type });
      this.entities.delete(sorted[i].id);
      this.relations = this.relations.filter(r => r.fromEntity !== sorted[i].id && r.toEntity !== sorted[i].id);
    }
    this.rebuildAliasIndex();
    DebugLog.push('KG_ENTITY' as any, { event: 'eviction', before, after: this.entities.size, evicted });
  }
}
