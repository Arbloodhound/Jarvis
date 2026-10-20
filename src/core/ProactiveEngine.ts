import { DeviceSignals, SignalSnapshot, BehaviorPattern } from './DeviceSignals';
import { KnowledgeGraph } from './KnowledgeGraph';
import { DeviceContext, CalendarEvent } from './DeviceContext';
import type { ModelRouter } from './ModelRouter';
import { SecureVault } from '../security/SecureVault';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import { logAICall } from '../utils/AICallLogger';

export interface ProactiveSuggestion {
  id: string; type: 'reminder' | 'optimization' | 'insight' | 'action' | 'alert';
  title: string; body: string; confidence: number; urgency: 'low' | 'medium' | 'high';
  actionable: boolean; suggestedCommand?: string; context: Record<string, any>;
  createdAt: number; expiresAt: number; dismissed: boolean; acted: boolean;
}

type RuleFunction = (snap: SignalSnapshot, patterns: BehaviorPattern[], calendar: CalendarEvent[], graph: KnowledgeGraph) => ProactiveSuggestion | null;

const SUGGESTIONS_KEY = 'proactive_suggestions';
const DISMISSED_KEY = 'proactive_dismissed';

export class ProactiveEngine {
  private suggestions: ProactiveSuggestion[] = [];
  private dismissedIds: Set<string> = new Set();
  private lastEvaluation = 0;
  private rules: RuleFunction[] = [];

  constructor(private signals: DeviceSignals, private graph: KnowledgeGraph, private ai: ModelRouter, private vault: SecureVault) {
    this.registerBuiltInRules();
  }

  async initialize(): Promise<void> {
    try {
      const raw = await this.vault.get(DISMISSED_KEY).catch(() => null);
      if (raw) this.dismissedIds = new Set(JSON.parse(raw));
      const sugRaw = await this.vault.get(SUGGESTIONS_KEY).catch(() => null);
      if (sugRaw) this.suggestions = JSON.parse(sugRaw);
      const now = Date.now();
      this.suggestions = this.suggestions.filter(s => s.expiresAt > now && !s.dismissed);
      DebugLog.push('PROACTIVE_EVAL' as any, { event: 'initialized', active: this.suggestions.length, dismissed: this.dismissedIds.size });
    } catch (err: any) { DebugLog.error('ProactiveEngine', `Init failed: ${err.message}`); }
  }

  async evaluate(): Promise<ProactiveSuggestion[]> {
    const now = Date.now();
    DebugLog.push('PROACTIVE_EVAL' as any, { event: 'start' });
    try {
      const snap = await this.signals.read();
      const patterns = this.signals.detectPatterns();
      let calendar: CalendarEvent[] = [];
      try { const ds = await DeviceContext.gather({ includeCalendar: true, includeCallLog: false, includeSms: false, includeContacts: false, includeLocation: false, includeScreen: false, calendarDaysAhead: 30 }); calendar = ds.calendar; } catch {}

      const newSuggestions: ProactiveSuggestion[] = [];
      for (let ruleIdx = 0; ruleIdx < this.rules.length; ruleIdx++) {
        try {
          const suggestion = this.rules[ruleIdx](snap, patterns, calendar, this.graph);
          if (suggestion && !this.dismissedIds.has(suggestion.id) && suggestion.confidence >= 0.5) {
            // Check concept-level dismissal
            const conceptKey = `concept:${suggestion.type}:${Object.values(suggestion.context).join(':')}`.toLowerCase();
            if (this.dismissedIds.has(conceptKey)) {
              DebugLog.push('PROACTIVE_EVAL' as any, { event: 'rule_miss', ruleIndex: ruleIdx, reason: 'concept_dismissed' });
              continue;
            }
            if (!this.suggestions.some(s => s.id === suggestion.id)) {
              newSuggestions.push(suggestion);
              DebugLog.push('PROACTIVE_SUGGEST' as any, { event: 'rule_match', ruleIndex: ruleIdx, title: suggestion.title, confidence: suggestion.confidence, urgency: suggestion.urgency });
            }
          } else {
            DebugLog.push('PROACTIVE_EVAL' as any, { event: 'rule_miss', ruleIndex: ruleIdx, reason: !suggestion ? 'returned_null' : this.dismissedIds.has(suggestion!.id) ? 'dismissed' : suggestion!.confidence < 0.5 ? 'low_confidence' : 'duplicate' });
          }
        } catch (e: any) { DebugLog.push('PROACTIVE_EVAL' as any, { event: 'rule_error', ruleIndex: ruleIdx, error: e.message }); }
      }

      if (this.ai.hasApiKey() && now - this.lastEvaluation > 1_800_000) {
        try { const aiSugs = await this.generateAISuggestions(snap, patterns, calendar); newSuggestions.push(...aiSugs); } catch {}
      }

      this.suggestions.push(...newSuggestions);
      if (this.suggestions.length > 50) { this.suggestions.sort((a, b) => b.confidence - a.confidence); this.suggestions = this.suggestions.slice(0, 50); }
      this.lastEvaluation = now;
      await this.persistSuggestions();
      DebugLog.push('PROACTIVE_EVAL' as any, { event: 'done', newCount: newSuggestions.length, totalActive: this.suggestions.length });
      return newSuggestions;
    } catch (err: any) { DebugLog.error('ProactiveEngine', `EVAL FAILED: ${err.message}`); return []; }
  }

  private registerBuiltInRules(): void {
    this.rules.push((snap, _, calendar) => {
      if (snap.batteryLevel > 20 || snap.batteryCharging) return null;
      const upcoming = calendar.find(e => e.startDate > Date.now() && e.startDate < Date.now() + 3_600_000 && !e.allDay);
      if (!upcoming) return null;
      return { id: `low_bat_${new Date().toDateString()}`, type: 'alert', title: 'Low battery before event', body: `Battery at ${snap.batteryLevel}% with "${upcoming.title}" in less than an hour.`, confidence: 0.9, urgency: 'high', actionable: false, context: {}, createdAt: Date.now(), expiresAt: upcoming.startDate, dismissed: false, acted: false };
    });
    this.rules.push((_, __, calendar, graph) => {
      const people = graph.getByType('person');
      const pp = people.map(p => { const rels = graph.getRelations(p.id); const place = rels.find(r => r.relation.type === 'lives_in'); return { person: p, place: place?.targetEntity }; }).filter(x => x.place);
      if (pp.length === 0) return null;
      const freeWindows = DeviceContext.findFreeWindows(calendar, 60, 1440);
      if (freeWindows.length === 0) return null;
      const top = pp.sort((a, b) => (b.person.accessCount || 0) - (a.person.accessCount || 0))[0];
      const w = freeWindows[0]; const start = new Date(w.start);
      return { id: `visit_${top.person.name}_${start.toISOString().slice(0, 10)}`, type: 'insight', title: `Visit ${top.person.name}?`, body: `You have ${Math.round(w.durationMinutes / 60 / 24)} free days starting ${start.toLocaleDateString([], { month: 'long', day: 'numeric' })}. ${top.person.name} is in ${top.place!.name}.`, confidence: 0.6, urgency: 'low', actionable: true, suggestedCommand: `find cheap flights to ${top.place!.name} around ${start.toLocaleDateString()}`, context: { person: top.person.name, place: top.place!.name }, createdAt: Date.now(), expiresAt: w.start, dismissed: false, acted: false };
    });
    this.rules.push((snap, _, calendar) => {
      if (snap.inferredActivity !== 'at_home' && snap.inferredActivity !== 'at_work') return null;
      const upcoming = calendar.find(e => { const t = e.startDate - Date.now(); return t > 0 && t < 5_400_000 && !e.allDay && e.location; });
      if (!upcoming) return null;
      return { id: `travel_${upcoming.id}`, type: 'reminder', title: `Heads up: ${upcoming.title}`, body: `"${upcoming.title}" at ${upcoming.location} starts at ${new Date(upcoming.startDate).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}. You might need to leave soon.`, confidence: 0.8, urgency: 'medium', actionable: true, suggestedCommand: `navigate to ${upcoming.location}`, context: { event: upcoming.title }, createdAt: Date.now(), expiresAt: upcoming.startDate, dismissed: false, acted: false };
    });
  }

  private async generateAISuggestions(snap: SignalSnapshot, patterns: BehaviorPattern[], calendar: CalendarEvent[]): Promise<ProactiveSuggestion[]> {
    const graphSummary = this.graph.getSummary();
    const patsSummary = patterns.slice(0, 10).map(p => p.description).join('\n');
    const calSummary = calendar.slice(0, 10).map(e => { const d = new Date(e.startDate); return `${d.toLocaleDateString([], { month: 'short', day: 'numeric' })} ${e.allDay ? 'all day' : d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}: ${e.title}`; }).join('\n');
    const prompt = `You are a proactive assistant. Generate 0-2 USEFUL suggestions.\n\nSTATE: ${snap.inferredActivity}, battery ${snap.batteryLevel}%\nPEOPLE/PLACES: ${graphSummary || 'None'}\nPATTERNS: ${patsSummary || 'None'}\nCALENDAR: ${calSummary || 'None'}\n\nJSON array (empty if nothing useful):\n[{"title":"...","body":"...","type":"insight|reminder|action|alert","urgency":"low|medium|high","confidence":0.0-1.0,"suggestedCommand":"optional command"}]`;
    try {
      const aiLog = logAICall({ agentId: 'proactive', prompt, maxTokens: 600, temperature: 0.4 });
      const result = await this.ai.complete(prompt, { taskId: `pro_${Date.now().toString(36)}`, agentId: 'proactive', maxTokens: 600, temperature: 0.4 });
      aiLog.logResponse(result.content, result.cost);
      const parsed = JSON.parse(result.content.replace(/```json|```/g, '').trim());
      if (!Array.isArray(parsed)) return [];
      return parsed.map((s: any) => ({ id: `ai_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 5)}`, type: s.type || 'insight', title: s.title || '', body: s.body || '', confidence: Math.min(1, Math.max(0, s.confidence || 0.5)), urgency: s.urgency || 'low', actionable: !!s.suggestedCommand, suggestedCommand: s.suggestedCommand, context: {}, createdAt: Date.now(), expiresAt: Date.now() + 86_400_000, dismissed: false, acted: false })).filter((s: ProactiveSuggestion) => s.confidence >= 0.5);
    } catch { return []; }
  }

  getActive(): ProactiveSuggestion[] {
    const now = Date.now();
    return this.suggestions.filter(s => !s.dismissed && !s.acted && s.expiresAt > now).sort((a, b) => { const o = { high: 3, medium: 2, low: 1 }; return (o[b.urgency] || 0) - (o[a.urgency] || 0) || b.confidence - a.confidence; });
  }

  async dismiss(id: string, dismissConcept: boolean = true): Promise<void> {
    const suggestion = this.suggestions.find(x => x.id === id);
    if (suggestion) {
      suggestion.dismissed = true;
      this.dismissedIds.add(id);

      if (dismissConcept) {
        const conceptKey = `${suggestion.type}:${Object.values(suggestion.context).join(':')}`.toLowerCase();
        this.dismissedIds.add(`concept:${conceptKey}`);

        for (const s of this.suggestions) {
          if (s.dismissed || s.id === id) continue;
          const otherKey = `${s.type}:${Object.values(s.context).join(':')}`.toLowerCase();
          if (otherKey === conceptKey) {
            s.dismissed = true;
            this.dismissedIds.add(s.id);
          }
        }
      }
    }

    await this.persistSuggestions();
    DebugLog.push('PROACTIVE_DISMISS' as any, { event: 'dismissed', id, conceptDismissal: dismissConcept });
  }
  async markActed(id: string): Promise<void> { const s = this.suggestions.find(x => x.id === id); if (s) s.acted = true; await this.persistSuggestions(); DebugLog.push('PROACTIVE_ACT' as any, { event: 'acted', id }); }
  addRule(rule: RuleFunction): void { this.rules.push(rule); }

  formatForChat(max = 2): string {
    const active = this.getActive().slice(0, max);
    if (active.length === 0) return '';
    return active.map(s => `\ud83d\udca1 ${s.title}: ${s.body}${s.suggestedCommand ? ` (say: "${s.suggestedCommand}")` : ''}`).join('\n');
  }

  private async persistSuggestions(): Promise<void> {
    try { await this.vault.set(SUGGESTIONS_KEY, JSON.stringify(this.suggestions)); await this.vault.set(DISMISSED_KEY, JSON.stringify(Array.from(this.dismissedIds))); } catch {}
  }
}
