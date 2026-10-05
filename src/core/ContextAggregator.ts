import { DeviceContext, DeviceSnapshot } from './DeviceContext';
import { MemoryManager } from './MemoryManager';
import { DevLogAnalyzer } from './DevLogAnalyzer';
import type { ModelRouter } from './ModelRouter';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import { logAICall, logContextBudget } from '../utils/AICallLogger';

function estimateTokens(text: string): number { return Math.ceil((text || '').length / 4); }

export interface DataNeed {
  calendar: boolean; callLog: boolean; sms: boolean; contacts: boolean;
  location: boolean; screen: boolean; specificApps: string[];
}

export interface AggregatedContext {
  goal: string; dataNeed: DataNeed; deviceData: string; memoryData: string;
  diagnosticHint: string; aiAnalysis: string; missingData: string[];
  snapshot: DeviceSnapshot;
}

export class ContextAggregator {
  constructor(private ai: ModelRouter, private memory: MemoryManager, private devLogAnalyzer: DevLogAnalyzer) {}

  async aggregate(goal: string): Promise<AggregatedContext> {
    DebugLog.push('CTX_AGGREGATE' as any, { event: 'start', goal: goal.slice(0, 80) });
    const dataNeed = await this.determineDataNeeds(goal);
    const snapshot = await DeviceContext.gather({
      includeCalendar: dataNeed.calendar, includeCallLog: dataNeed.callLog,
      includeSms: dataNeed.sms, includeContacts: dataNeed.contacts,
      includeLocation: dataNeed.location, includeScreen: dataNeed.screen,
      calendarDaysAhead: 90, calendarDaysBehind: 14, smsLimit: 50, callLogLimit: 30,
    });
    const deviceData = DeviceContext.toContextString(snapshot, 5000);
    const memories = await this.memory.retrieveRelevant(goal, 8);
    const memoryData = this.memory.formatForPrompt(memories);
    const diagnosticHint = await this.devLogAnalyzer.getContextHint();
    const { aiAnalysis, missingData } = await this.analyzeContext(goal, deviceData, memoryData, dataNeed);
    DebugLog.push('CTX_AGGREGATE' as any, { event: 'done', deviceLen: deviceData.length, memoryCount: memories.length, missingData });
    return { goal, dataNeed, deviceData, memoryData, diagnosticHint, aiAnalysis, missingData, snapshot };
  }

  private async determineDataNeeds(goal: string): Promise<DataNeed> {
    if (!this.ai.hasApiKey()) return this.heuristicDataNeeds(goal);
    try {
      const prompt = `Given this goal, determine which phone data sources are needed.\n\nGOAL: "${goal}"\n\nRespond ONLY with valid JSON:\n{"calendar":true/false,"callLog":true/false,"sms":true/false,"contacts":true/false,"location":true/false,"screen":false,"specificApps":["app name if needed"]}`;
      const aiLog = logAICall({ agentId: 'needs_analyzer', prompt, maxTokens: 300, temperature: 0.1 });
      const result = await this.ai.complete(prompt, { taskId: `needs_${Date.now().toString(36)}`, agentId: 'needs_analyzer', maxTokens: 300, temperature: 0.1 });
      aiLog.logResponse(result.content, result.cost);
      const parsed = JSON.parse(result.content.replace(/```json|```/g, '').trim());
      return { calendar: !!parsed.calendar, callLog: !!parsed.callLog, sms: !!parsed.sms, contacts: !!parsed.contacts, location: !!parsed.location, screen: !!parsed.screen, specificApps: Array.isArray(parsed.specificApps) ? parsed.specificApps : [] };
    } catch (e: any) {
      DebugLog.error('ContextAggregator', `AI needs failed: ${e.message}`);
      return this.heuristicDataNeeds(goal);
    }
  }

  private heuristicDataNeeds(goal: string): DataNeed {
    const g = goal.toLowerCase();
    return {
      calendar: /\b(calendar|schedul|free|busy|event|meeting|when|travel|visit|trip)\b/i.test(g),
      callLog: /\b(call|called|rang|phone|dial)\b/i.test(g),
      sms: /\b(text|sms|message|bank|notif|delivery|payment)\b/i.test(g),
      contacts: /\b(mom|dad|wife|husband|friend|contact|person|who)\b/i.test(g),
      location: /\b(where|near|nearby|locat|navig|map|travel)\b/i.test(g),
      screen: false, specificApps: [],
    };
  }

  private async analyzeContext(goal: string, deviceData: string, memoryData: string, dataNeed: DataNeed): Promise<{ aiAnalysis: string; missingData: string[] }> {
    if (!this.ai.hasApiKey()) {
      return { aiAnalysis: `Device data gathered. Cannot analyze without API key.`, missingData: [] };
    }
    try {
      let maxInputTokens = 6000;
      try { const cw = (this.ai as any).getContextWindow?.(this.ai.getDefaultModel()); if (cw > 0) maxInputTokens = Math.floor(cw * 0.6); } catch {}
      const promptOverhead = 500; const responseReserve = 600;
      const available = maxInputTokens - promptOverhead - responseReserve;
      const memBudget = Math.min(estimateTokens(memoryData), Math.floor(available * 0.2));
      const devBudget = available - memBudget;
      const trimmedDevice = deviceData.slice(0, devBudget * 4);
      const trimmedMemory = memoryData.slice(0, memBudget * 4);

      logContextBudget({
        agentId: 'context_analyst', model: this.ai.getDefaultModel(), contextWindow: maxInputTokens * 2,
        components: [
          { name: 'device_data', chars: trimmedDevice.length, tokens: estimateTokens(trimmedDevice) },
          { name: 'memory_data', chars: trimmedMemory.length, tokens: estimateTokens(trimmedMemory) },
        ],
        totalTokens: estimateTokens(trimmedDevice) + estimateTokens(trimmedMemory) + promptOverhead,
        reservedForResponse: responseReserve,
      });

      const prompt = `Analyze device data to help plan a complex task.\n\nGOAL: "${goal}"\n\nDEVICE DATA:\n${trimmedDevice}\n\n${trimmedMemory ? `PAST EXPERIENCE:\n${trimmedMemory}` : ''}\n\nProvide:\n1. ANALYSIS: What you can determine (2-4 sentences)\n2. MISSING: JSON array of data still needed\n\nFormat:\nANALYSIS: ...\nMISSING: [...]`;
      const aiLog = logAICall({ agentId: 'context_analyst', prompt, maxTokens: 600, temperature: 0.2 });
      const result = await this.ai.complete(prompt, { taskId: `ctx_${Date.now().toString(36)}`, agentId: 'context_analyst', maxTokens: 600, temperature: 0.2 });
      aiLog.logResponse(result.content, result.cost);
      const content = result.content;
      const analysisMatch = content.match(/ANALYSIS:\s*(.+?)(?=MISSING:|$)/is);
      const missingMatch = content.match(/MISSING:\s*(\[[\s\S]*?\])/i);
      const aiAnalysis = analysisMatch ? analysisMatch[1].trim() : content.slice(0, 500);
      let missingData: string[] = [];
      if (missingMatch) { try { missingData = JSON.parse(missingMatch[1]); } catch {} }
      return { aiAnalysis, missingData };
    } catch (e: any) {
      return { aiAnalysis: `Data gathered but analysis failed: ${e.message}`, missingData: [] };
    }
  }

  async quickContext(goal: string): Promise<string> {
    const snapshot = await DeviceContext.gather({ includeCalendar: false, includeCallLog: false, includeSms: false, includeContacts: false, includeLocation: false, includeScreen: true });
    const deviceStr = DeviceContext.toContextString(snapshot, 2000);
    const memories = await this.memory.retrieveRelevant(goal, 3);
    return [deviceStr, this.memory.formatForPrompt(memories)].filter(Boolean).join('\n');
  }
}
