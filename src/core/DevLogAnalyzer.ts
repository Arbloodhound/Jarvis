import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import type { ModelRouter } from './ModelRouter';
import { logAICall } from '../utils/AICallLogger';

export interface ErrorPattern {
  category: string;
  count: number;
  recentErrors: string[];
  firstSeen: number;
  lastSeen: number;
}

export interface DiagnosisResult {
  patterns: ErrorPattern[];
  diagnosis: string;
  suggestions: string[];
  timestamp: number;
}

export class DevLogAnalyzer {
  private lastDiagnosis: DiagnosisResult | null = null;
  private lastDiagnosisTime = 0;
  private static readonly DIAGNOSIS_TTL_MS = 300_000;

  constructor(private ai: ModelRouter) {}

  async analyzePatterns(): Promise<ErrorPattern[]> {
    const patterns = new Map<string, ErrorPattern>();
    try {
      const entries = DebugLog.getEntries(500);
      for (const entry of entries) {
        const raw = typeof entry === 'string' ? entry : JSON.stringify(entry);
        if (!raw.toLowerCase().includes('error') && !raw.toLowerCase().includes('fail')) continue;
        let category = 'unknown';
        const catMatch = raw.match(/\[([A-Z_]+)\]/) || raw.match(/"cat":"([A-Z_]+)"/);
        if (catMatch) category = catMatch[1].toLowerCase();
        const now = Date.now();
        const existing = patterns.get(category);
        if (existing) {
          existing.count++;
          existing.lastSeen = now;
          if (existing.recentErrors.length < 3) existing.recentErrors.push(raw.slice(0, 200));
        } else {
          patterns.set(category, { category, count: 1, recentErrors: [raw.slice(0, 200)], firstSeen: now, lastSeen: now });
        }
      }
    } catch (e: any) {
      DebugLog.error('DevLogAnalyzer', `Pattern analysis failed: ${e.message}`);
    }
    return Array.from(patterns.values()).sort((a, b) => b.count - a.count);
  }

  async diagnose(): Promise<DiagnosisResult> {
    const now = Date.now();
    if (this.lastDiagnosis && now - this.lastDiagnosisTime < DevLogAnalyzer.DIAGNOSIS_TTL_MS) return this.lastDiagnosis;

    const patterns = await this.analyzePatterns();
    if (patterns.length === 0) {
      const result: DiagnosisResult = { patterns: [], diagnosis: 'No error patterns detected.', suggestions: [], timestamp: now };
      this.lastDiagnosis = result;
      this.lastDiagnosisTime = now;
      return result;
    }

    let diagnosis = '';
    let suggestions: string[] = [];

    if (this.ai.hasApiKey()) {
      try {
        const patternText = patterns.slice(0, 10).map(p => `[${p.category}] ${p.count}x. Recent: ${p.recentErrors[0]?.slice(0, 150)}`).join('\n');
        const prompt = `You are a mobile app debugger. Error patterns:\n\n${patternText}\n\nProvide:\n1. DIAGNOSIS: 2-3 sentences\n2. SUGGESTIONS: JSON array\n\nFormat:\nDIAGNOSIS: ...\nSUGGESTIONS: [...]`;
        const aiLog = logAICall({ agentId: 'diagnostician', prompt, maxTokens: 500, temperature: 0.2 });
        const aiResult = await this.ai.complete(prompt, { taskId: `diag_${Date.now().toString(36)}`, agentId: 'diagnostician', maxTokens: 500, temperature: 0.2 });
        aiLog.logResponse(aiResult.content, aiResult.cost);
        const content = aiResult.content;
        const diagMatch = content.match(/DIAGNOSIS:\s*(.+?)(?=SUGGESTIONS:|$)/is);
        const sugMatch = content.match(/SUGGESTIONS:\s*(\[[\s\S]*?\])/i);
        diagnosis = diagMatch ? diagMatch[1].trim() : content.slice(0, 300);
        if (sugMatch) { try { suggestions = JSON.parse(sugMatch[1]); } catch {} }
      } catch (e: any) {
        diagnosis = `AI diagnosis unavailable: ${e.message}. ${patterns.length} error patterns found.`;
      }
    } else {
      diagnosis = `${patterns.length} error patterns found. Top: ${patterns[0].category} (${patterns[0].count}x).`;
    }

    const result: DiagnosisResult = { patterns, diagnosis, suggestions, timestamp: now };
    this.lastDiagnosis = result;
    this.lastDiagnosisTime = now;
    return result;
  }

  async getContextHint(): Promise<string> {
    const diag = await this.diagnose();
    if (diag.patterns.length === 0) return '';
    const top = diag.patterns.slice(0, 3);
    const lines = ['[SYSTEM HEALTH]'];
    for (const p of top) lines.push(`\u26a0 ${p.category}: ${p.count} recent errors`);
    if (diag.suggestions.length > 0) lines.push(`Suggestion: ${diag.suggestions[0]}`);
    return lines.join('\n');
  }

  invalidate(): void { this.lastDiagnosis = null; this.lastDiagnosisTime = 0; }
}
