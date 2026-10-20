import type { ModelRouter } from './ModelRouter';
import type { TaskExecutor, TaskResult } from './TaskExecutor';
import type { CapabilityRegistry } from './CapabilityRegistry';
import type { MemoryManager } from './MemoryManager';
import type { SecureVault } from '../security/SecureVault';
import { CommandParser } from './CommandParser';
import { ContextAggregator, AggregatedContext } from './ContextAggregator';
import { DeviceContext } from './DeviceContext';
import { DevLogAnalyzer } from './DevLogAnalyzer';
import { TaskStore, StoredTask, SubTask, TaskStatus } from './TaskStore';
import { EnhancedReActLoop } from './EnhancedReActLoop';
import { KnowledgeGraph } from './KnowledgeGraph';
import { VisionPipeline } from './VisionPipeline';
import { AppIntelligence, AppDataResult } from './AppIntelligence';
import AppController from '../native/AppController';
import { Platform } from 'react-native';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import { CorrIdScope } from '../utils/CorrIdScope';
import { logAICall, logContextBudget } from '../utils/AICallLogger';

function estimateTokens(text: string): number { return Math.ceil((text || '').length / 4); }

export interface CortexResult {
  success: boolean; summary: string; taskId: string;
  subResults: Array<{ step: string; success: boolean; result: string }>;
  persistent: boolean; totalCost: number;
}

interface DecomposedStep {
  id: string; description: string;
  type: 'capability' | 'react_ui' | 'gather_data' | 'ai_reason' | 'app_data';
  capability?: string; params?: Record<string, any>; dependsOn: string[];
  appHint?: string; query?: string;
}

export class Cortex {
  private parser: CommandParser;
  private aggregator: ContextAggregator;
  private devLogAnalyzer: DevLogAnalyzer;
  private taskStore: TaskStore;
  private graph: KnowledgeGraph;
  private vision: VisionPipeline;
  private appIntel: AppIntelligence;
  private initialized = false;

  constructor(private ai: ModelRouter, private executor: TaskExecutor, private caps: CapabilityRegistry, private memory: MemoryManager, private vault: SecureVault) {
    this.parser = new CommandParser();
    this.devLogAnalyzer = new DevLogAnalyzer(ai);
    this.aggregator = new ContextAggregator(ai, memory, this.devLogAnalyzer);
    this.taskStore = new TaskStore(vault);
    this.graph = new KnowledgeGraph();
    this.vision = new VisionPipeline(ai);
    this.appIntel = new AppIntelligence(ai);
  }

  async initialize(): Promise<void> {
    if (this.initialized) return;
    await this.taskStore.initialize();
    await this.graph.initialize();
    this.initialized = true;
    DebugLog.systemEvent('Cortex', 'Initialized');
  }

  isComplex(input: string): boolean {
    const i = input.toLowerCase().trim();
    const simple = this.parser.parse(input);
    if (simple) {
      const multiSource = /\b(best|cheap|compar|optim|analyz|figure out|plan|schedul|when should|which|recommend)\b/i.test(i);
      const crossApp = /\b(check.+(?:and|then).+(?:book|order|send|call|text))\b/i.test(i);
      if (!multiSource && !crossApp) return false;
    }
    const indicators = [/\b(best time|figure out|find.+for me|plan|analyz|compare|optimi)\b/i, /\b(check.+(?:calendar|schedule|bank|budget).+(?:and|then))\b/i, /\b(after.+(?:check|find|get).+(?:book|order|send|buy|call))\b/i, /\b(all|everything|summary|overview).+\b(about|from|regarding)\b/i, /\b(monitor|watch|track|alert me|notify me)\b/i, /\b(should i|can i afford|is it worth|when is.+good time)\b/i, /\b(visit|travel|trip|flight|hotel|vacation|itinerary)\b/i, /\b(afford|budget|spend|cost|price|save)\b/i];
    let score = 0;
    for (const p of indicators) if (p.test(i)) score++;
    return score >= 2;
  }

  async execute(goal: string, conversationId: string, onProgress?: (step: string, detail: string) => void): Promise<CortexResult> {
    await this.initialize();
    const taskId = `cortex_${Date.now().toString(36)}`;
    const disposeCorrId = CorrIdScope.enter(taskId);
    let taskTotalCost = 0;
    const costsByStep: Array<{ step: string; cost: number }> = [];

    try {
      DebugLog.push('CORTEX_ROUTE' as any, { event: 'execute_start', taskId, goal: goal.slice(0, 80) });
      const subResults: CortexResult['subResults'] = [];

      onProgress?.('gathering', 'Analyzing request and gathering data...');
      let context: AggregatedContext;
      try { context = await this.aggregator.aggregate(goal); } catch (e: any) { return { success: false, summary: `Context failed: ${e.message}`, taskId, subResults, persistent: false, totalCost: 0 }; }

      onProgress?.('planning', 'Creating execution plan...');
      let decomposed: DecomposedStep[];
      try { decomposed = await this.decompose(goal, context); } catch (e: any) { return { success: false, summary: `Planning failed: ${e.message}`, taskId, subResults, persistent: false, totalCost: 0 }; }
      DebugLog.push('CORTEX_DECOMPOSE' as any, { event: 'done', stepCount: decomposed.length, steps: decomposed.map(s => s.id) });

      const storedTask = await this.taskStore.create({
        goal, conversationId,
        subTasks: decomposed.map(s => ({ id: s.id, description: s.description, capability: s.capability || s.type, params: s.params || {}, status: 'pending' as TaskStatus, dependsOn: s.dependsOn, retries: 0 })),
        contextSnapshot: context.deviceData.slice(0, 5000), resumable: true,
      });
      await this.taskStore.update(storedTask.id, { status: 'active' });

      let allSucceeded = true;
      const completedResults: Map<string, any> = new Map();

      for (let round = 0; round < decomposed.length + 5; round++) {
        const currentTask = await this.taskStore.get(storedTask.id);
        if (!currentTask) break;
        const ready = this.taskStore.getReadySubTasks(currentTask);
        if (ready.length === 0) { if (this.taskStore.isComplete(currentTask)) break; const pending = currentTask.subTasks.filter(s => s.status === 'pending'); if (pending.length > 0) { allSucceeded = false; } break; }

        const step = ready[0];
        onProgress?.('executing', `${step.description.slice(0, 60)}...`);
        await this.taskStore.updateSubTask(storedTask.id, step.id, { status: 'active', startedAt: Date.now() });
        await this.taskStore.addProgress(storedTask.id, step.id, 'started', step.description);
        DebugLog.push('CORTEX_STEP' as any, { event: 'start', stepId: step.id, capability: step.capability });

        let stepResult: { success: boolean; result: any; summary: string };
        const dStep = decomposed.find(d => d.id === step.id);
        try { stepResult = await this.executeStep(dStep!, completedResults, taskId); } catch (e: any) { stepResult = { success: false, result: null, summary: `Error: ${e.message}` }; }

        completedResults.set(step.id, stepResult);
        await this.taskStore.updateSubTask(storedTask.id, step.id, { status: stepResult.success ? 'completed' : 'failed', result: stepResult.result, completedAt: Date.now(), error: stepResult.success ? undefined : stepResult.summary });
        await this.taskStore.addProgress(storedTask.id, step.id, stepResult.success ? 'completed' : 'failed', stepResult.summary.slice(0, 200));
        subResults.push({ step: step.description, success: stepResult.success, result: stepResult.summary.slice(0, 300) });
        if (!stepResult.success) allSucceeded = false;

        DebugLog.push('CORTEX_STEP' as any, { event: 'done', stepId: step.id, success: stepResult.success });

        const remaining = (await this.taskStore.get(storedTask.id))!.subTasks.filter(s => s.status === 'pending');
        if (remaining.length > 0 && this.ai.hasApiKey()) {
          try {
            const replanNeeded = await this.shouldReplan(goal, step.description, stepResult, remaining, context);
            if (replanNeeded) {
              onProgress?.('replanning', 'Adjusting plan...');
              DebugLog.push('CORTEX_REPLAN' as any, { event: 'triggered', lastStep: step.id });
              const newSteps = await this.replan(goal, completedResults, remaining, context);
              if (newSteps.length > 0) {
                const updTask = await this.taskStore.get(storedTask.id);
                if (updTask) {
                  const kept = updTask.subTasks.filter(s => s.status !== 'pending');
                  const newSubs: SubTask[] = newSteps.map(s => ({ id: s.id, description: s.description, capability: s.capability || s.type, params: s.params || {}, status: 'pending' as TaskStatus, dependsOn: s.dependsOn.filter(d => kept.some(k => k.id === d) || newSteps.some(n => n.id === d)), retries: 0 }));
                  await this.taskStore.replaceSubTasks(storedTask.id, [...kept, ...newSubs]);
                  decomposed = [...decomposed.filter(d => kept.some(k => k.id === d.id)), ...newSteps];
                }
              }
            }
          } catch {}
        }
      }

      const summary = await this.summarizeResults(goal, subResults, context);
      await this.taskStore.update(storedTask.id, { status: allSucceeded ? 'completed' : 'failed', completedAt: Date.now(), result: summary });
      if (allSucceeded) { try { await this.memory.promoteLongterm(goal, 'cortex_task', summary); } catch {} }

      try {
        const aiClient = this.ai.hasApiKey() ? { complete: async (p: string, o: any) => this.ai.complete(p, o) } : undefined;
        for (const sr of subResults) await this.graph.learnFromInteraction(goal, sr.step, sr.result, aiClient);
      } catch {}

      DebugLog.push('CORTEX_RESULT' as any, { event: 'done', taskId: storedTask.id, success: allSucceeded, steps: subResults.length, totalCost: taskTotalCost, costsByStep });
      return { success: allSucceeded, summary, taskId: storedTask.id, subResults, persistent: true, totalCost: taskTotalCost };
    } finally { disposeCorrId(); }
  }

  private async decompose(goal: string, context: AggregatedContext): Promise<DecomposedStep[]> {
    if (!this.ai.hasApiKey()) return this.heuristicDecompose(goal, context);
    const capList = this.caps.getAll().slice(0, 30).map(c => `${c.id}: ${c.description}`).join('\n');
    const graphContext = this.graph.getContextFor(goal);
    const prompt = `Decompose this goal into sequential/parallel steps.\n\nGOAL: "${goal}"\n\nCONTEXT:\n${context.aiAnalysis}\n${graphContext ? `\nKNOWN ENTITIES:\n${graphContext}` : ''}\n${context.missingData.length > 0 ? `MISSING: ${context.missingData.join(', ')}` : ''}\n\nACTION TYPES:\n- "capability": device capability. Available:\n${capList.slice(0, 2000)}\n- "react_ui": UI automation within an app\n- "gather_data": Read device data (calendar, SMS, contacts, calls)\n- "ai_reason": AI analysis of gathered data\n- "app_data": Open an installed app and read data from screen. Specify appHint.\n\nJSON array only:\n[{"id":"step_1","description":"...","type":"...","capability":"...","params":{},"dependsOn":[],"appHint":"...","query":"..."}]\n\nKeep to 3-8 steps.`;

    logContextBudget({ agentId: 'cortex_planner', model: this.ai.getDefaultModel(), contextWindow: 8000, components: [{ name: 'prompt', chars: prompt.length, tokens: estimateTokens(prompt) }], totalTokens: estimateTokens(prompt), reservedForResponse: 1500 });

    try {
      const aiLog = logAICall({ agentId: 'cortex_planner', prompt, maxTokens: 1500, temperature: 0.2 });
      const { withRetry } = await import('../utils/AICallLogger');
      const result = await withRetry(() => this.ai.complete(prompt, { taskId: `decompose_${Date.now().toString(36)}`, agentId: 'cortex_planner', maxTokens: 1500, temperature: 0.2 }), { maxRetries: 2, agentId: 'cortex_planner' });
      aiLog.logResponse(result.content, result.cost);
      if (result.cost) this.taskTotalCost += result.cost;
      const parsed = JSON.parse(result.content.replace(/```json|```/g, '').trim());
      if (!Array.isArray(parsed) || parsed.length === 0) throw new Error('Empty decomposition');
      return parsed.map((s: any) => ({ id: s.id || `step_${Math.random().toString(36).slice(2, 6)}`, description: s.description || '', type: s.type || 'capability', capability: s.capability, params: s.params || {}, dependsOn: Array.isArray(s.dependsOn) ? s.dependsOn : [], appHint: s.appHint, query: s.query }));
    } catch (e: any) { DebugLog.error('Cortex', `AI decompose failed: ${e.message}`); return this.heuristicDecompose(goal, context); }
  }

  private taskTotalCost = 0;

  private heuristicDecompose(goal: string, context: AggregatedContext): DecomposedStep[] {
    const steps: DecomposedStep[] = [];
    if (context.dataNeed.calendar || context.dataNeed.callLog || context.dataNeed.sms || context.dataNeed.contacts) steps.push({ id: 'gather', description: 'Gather device data', type: 'gather_data', dependsOn: [] });
    if (context.missingData.length > 0) steps.push({ id: 'app_search', description: `Search for: ${context.missingData.join(', ') || goal}`, type: 'app_data', appHint: 'google', query: goal, dependsOn: [] });
    steps.push({ id: 'reason', description: 'Analyze and decide', type: 'ai_reason', dependsOn: steps.map(s => s.id) });
    return steps;
  }

  private async executeStep(step: DecomposedStep, prevResults: Map<string, any>, taskId: string): Promise<{ success: boolean; result: any; summary: string }> {
    switch (step.type) {
      case 'capability': {
        if (!step.capability) return { success: false, result: null, summary: 'No capability specified' };
        const params = { ...step.params };
        for (const [depId, depResult] of prevResults) { if (step.dependsOn.includes(depId) && depResult?.result) params[`_dep_${depId}`] = typeof depResult.result === 'string' ? depResult.result.slice(0, 500) : JSON.stringify(depResult.result).slice(0, 500); }
        const result = await this.executor.runWithPlan({ capability: step.capability, params, reason: step.description }, taskId);
        return { success: result.success, result: result.data, summary: result.summary };
      }
      case 'react_ui': {
        if (Platform.OS !== 'android' || !AppController.isAvailable()) return { success: false, result: null, summary: 'UI automation requires Android with accessibility' };
        const aiCall = async (p: string) => { const r = await this.ai.complete(p, { taskId, agentId: 'cortex_react', maxTokens: 600, temperature: 0.2 }); return r.content; };
        const loop = new EnhancedReActLoop(aiCall, aiCall, { maxIterations: 20, iterationDelayMs: 1000 });
        if (step.appHint) { try { await this.executor.runWithPlan({ capability: 'app_launch', params: { target: step.appHint }, reason: 'Cortex pre-launch' }, taskId); await new Promise(r => setTimeout(r, 2000)); } catch {} }
        const rr = await loop.execute(step.description, step.appHint);
        return { success: rr.goalAchieved, result: { steps: rr.steps.length, goalAchieved: rr.goalAchieved }, summary: rr.goalAchieved ? `Completed: ${step.description}` : `Could not complete (${rr.steps.length} attempts)` };
      }
      case 'gather_data': {
        try {
          const snapshot = await DeviceContext.gather({ includeCalendar: true, includeCallLog: true, includeSms: true, includeContacts: true, includeLocation: true, includeScreen: false });
          const summary = DeviceContext.toContextString(snapshot, 3000);
          return { success: true, result: summary, summary: `Gathered: ${snapshot.calendar.length} events, ${snapshot.callLog.length} calls, ${snapshot.sms.length} SMS, ${snapshot.contacts.length} contacts` };
        } catch (e: any) { return { success: false, result: null, summary: `Data gathering failed: ${e.message}` }; }
      }
      case 'ai_reason': {
        if (!this.ai.hasApiKey()) return { success: false, result: null, summary: 'AI reasoning requires API key' };
        const prevCtx = Array.from(prevResults.entries()).map(([id, res]) => `[${id}]: ${res.summary?.slice(0, 300) || JSON.stringify(res.result).slice(0, 300)}`).join('\n');
        const prompt = `Analyze data to help the user.\n\nGOAL: "${step.description}"\n\nDATA:\n${prevCtx.slice(0, 4000)}\n\nProvide clear, actionable analysis.`;
        const aiLog = logAICall({ agentId: 'cortex_reasoner', prompt, maxTokens: 1000, temperature: 0.3 });
        const result = await this.ai.complete(prompt, { taskId, agentId: 'cortex_reasoner', maxTokens: 1000, temperature: 0.3 });
        aiLog.logResponse(result.content, result.cost);
        return { success: true, result: result.content, summary: result.content.slice(0, 300) };
      }
      case 'app_data': {
        const query = step.query || step.description;
        const targetApp = step.appHint || 'google';
        DebugLog.push('CORTEX_STEP' as any, { event: 'app_data_start', app: targetApp, query: query.slice(0, 60) });
        try {
          let result: AppDataResult;
          if (targetApp === 'google' || targetApp === 'chrome' || targetApp === 'browser') result = await this.appIntel.search(query, { app: targetApp, extractPrompt: step.params?.extractPrompt, maxSteps: 12 });
          else result = await this.appIntel.readApp(targetApp, query, { extractPrompt: step.params?.extractPrompt, maxSteps: 15 });
          return { success: result.success, result: { aiSummary: result.aiSummary, structuredData: result.structuredData, confidence: result.confidence, app: result.app }, summary: result.aiSummary.slice(0, 500) };
        } catch (e: any) {
          let suggestion = `Failed to get data from ${targetApp}: ${e.message}`;
          try {
            const { AppFallback } = await import('./AppFallback');
            const alts = AppFallback.suggestAlternatives(query, targetApp);
            if (alts.length > 0) suggestion += '\n\nAlternatives:\n' + alts.map((a: string) => `• ${a}`).join('\n');
          } catch {}
          DebugLog.error('Cortex', suggestion);
          return { success: false, result: null, summary: suggestion };
        }
      }
      default: return { success: false, result: null, summary: `Unknown step type: ${step.type}` };
    }
  }

  private async shouldReplan(goal: string, lastStep: string, lastResult: { success: boolean; summary: string }, remaining: SubTask[], _context: AggregatedContext): Promise<boolean> {
    if (!lastResult.success) return true;
    if (lastResult.summary.length > 100) {
      try {
        const result = await this.ai.complete(`Goal: "${goal}"\nLast: "${lastStep}" \u2192 "${lastResult.summary.slice(0, 300)}"\nRemaining: ${remaining.map(s => s.description).join(', ')}\n\nDoes this change what we do next? YES or NO.`, { taskId: `replan_${Date.now().toString(36)}`, agentId: 'cortex_manager', maxTokens: 10, temperature: 0.1 });
        return /^yes/i.test(result.content.trim());
      } catch { return false; }
    }
    return false;
  }

  private async replan(goal: string, completedResults: Map<string, any>, remaining: SubTask[], _context: AggregatedContext): Promise<DecomposedStep[]> {
    const summary = Array.from(completedResults.entries()).map(([id, r]) => `[${id}] ${r.success ? '\u2713' : '\u2717'}: ${r.summary?.slice(0, 150)}`).join('\n');
    const prompt = `Plan needs adjustment.\n\nGOAL: "${goal}"\nCOMPLETED:\n${summary.slice(0, 2000)}\nREMAINING:\n${remaining.map(s => `${s.id}: ${s.description}`).join('\n')}\n\nNew remaining steps (JSON array):\n[{"id":"...","description":"...","type":"capability|react_ui|gather_data|ai_reason|app_data","capability":"...","params":{},"dependsOn":[],"appHint":"..."}]`;
    try {
      const result = await this.ai.complete(prompt, { taskId: `replan_${Date.now().toString(36)}`, agentId: 'cortex_replanner', maxTokens: 800, temperature: 0.2 });
      const parsed = JSON.parse(result.content.replace(/```json|```/g, '').trim());
      if (!Array.isArray(parsed)) return [];
      return parsed.map((s: any) => ({ id: s.id || `rp_${Math.random().toString(36).slice(2, 6)}`, description: s.description || '', type: s.type || 'ai_reason', capability: s.capability, params: s.params || {}, dependsOn: Array.isArray(s.dependsOn) ? s.dependsOn : [], appHint: s.appHint, query: s.query }));
    } catch { return []; }
  }

  private async summarizeResults(goal: string, subResults: CortexResult['subResults'], _context: AggregatedContext): Promise<string> {
    if (!this.ai.hasApiKey()) return subResults.map(r => `${r.success ? '\u2713' : '\u2717'} ${r.step}: ${r.result}`).join('\n');
    try {
      const text = subResults.map(r => `${r.success ? 'Done' : 'Failed'}: ${r.step} \u2014 ${r.result}`).join('\n');
      const prompt = `Summarize for the user naturally.\n\nREQUEST: "${goal}"\nRESULTS:\n${text.slice(0, 3000)}\n\nBe concise (3-5 sentences). Speak directly.`;
      const result = await this.ai.complete(prompt, { taskId: `sum_${Date.now().toString(36)}`, agentId: 'cortex_summarizer', maxTokens: 400, temperature: 0.3 });
      return result.content;
    } catch { return subResults.map(r => `${r.success ? '\u2713' : '\u2717'} ${r.step}: ${r.result}`).join('\n'); }
  }

  async resume(taskId: string, onProgress?: (step: string, detail: string) => void): Promise<CortexResult> {
    const task = await this.taskStore.get(taskId);
    if (!task) return { success: false, summary: `Task ${taskId} not found`, taskId, subResults: [], persistent: false, totalCost: 0 };
    if (task.status === 'completed') return { success: true, summary: task.result || 'Already completed', taskId, subResults: [], persistent: true, totalCost: 0 };

    await this.initialize();
    const disposeCorrId = CorrIdScope.enter(`resume_${taskId}`);
    try {
      DebugLog.push('CORTEX_ROUTE' as any, { event: 'resume', taskId, goal: task.goal.slice(0, 80), completedSteps: task.subTasks.filter((s: any) => s.status === 'completed').length, totalSteps: task.subTasks.length });

      for (const st of task.subTasks) {
        if (st.status === 'active' || st.status === 'failed') {
          st.status = 'pending';
          st.retries = (st.retries || 0) + 1;
        }
      }
      await this.taskStore.update(taskId, { status: 'active', subTasks: task.subTasks });

      const subResults: CortexResult['subResults'] = [];
      const completedResults: Map<string, any> = new Map();

      for (const st of task.subTasks) {
        if (st.status === 'completed' && st.result) {
          completedResults.set(st.id, { success: true, result: st.result, summary: typeof st.result === 'string' ? st.result.slice(0, 300) : JSON.stringify(st.result).slice(0, 300) });
          subResults.push({ step: st.description, success: true, result: typeof st.result === 'string' ? st.result.slice(0, 300) : 'completed' });
          onProgress?.('skipping', `Already done: ${st.description.slice(0, 50)}`);
        }
      }

      let allSucceeded = true;
      const decomposed = task.subTasks.map((st: any) => ({
        id: st.id, description: st.description, type: (st.capability || 'capability') as any,
        capability: st.capability, params: st.params, dependsOn: st.dependsOn,
      }));

      for (let round = 0; round < task.subTasks.length + 5; round++) {
        const currentTask = await this.taskStore.get(taskId);
        if (!currentTask) break;
        const ready = this.taskStore.getReadySubTasks(currentTask);
        if (ready.length === 0) { if (this.taskStore.isComplete(currentTask)) break; break; }

        const step = ready[0];
        if (step.retries > 3) {
          await this.taskStore.updateSubTask(taskId, step.id, { status: 'failed', error: 'Max retries exceeded' });
          allSucceeded = false;
          continue;
        }

        onProgress?.('executing', `${step.description.slice(0, 60)}...`);
        await this.taskStore.updateSubTask(taskId, step.id, { status: 'active', startedAt: Date.now() });

        const dStep = decomposed.find((d: any) => d.id === step.id);
        let stepResult: { success: boolean; result: any; summary: string };
        try { stepResult = await this.executeStep(dStep!, completedResults, taskId); } catch (e: any) { stepResult = { success: false, result: null, summary: `Error: ${e.message}` }; }

        completedResults.set(step.id, stepResult);
        await this.taskStore.updateSubTask(taskId, step.id, { status: stepResult.success ? 'completed' : 'failed', result: stepResult.result, completedAt: Date.now() });
        subResults.push({ step: step.description, success: stepResult.success, result: stepResult.summary.slice(0, 300) });
        if (!stepResult.success) allSucceeded = false;
      }

      const summary = await this.summarizeResults(task.goal, subResults, {} as any);
      await this.taskStore.update(taskId, { status: allSucceeded ? 'completed' : 'failed', completedAt: Date.now(), result: summary });

      return { success: allSucceeded, summary, taskId, subResults, persistent: true, totalCost: 0 };
    } finally { disposeCorrId(); }
  }

  async getResumableTasks(): Promise<StoredTask[]> { await this.initialize(); return this.taskStore.getResumableTasks(); }
  getTaskStore(): TaskStore { return this.taskStore; }
  getDevLogAnalyzer(): DevLogAnalyzer { return this.devLogAnalyzer; }
  getKnowledgeGraph(): KnowledgeGraph { return this.graph; }
  getVisionPipeline(): VisionPipeline { return this.vision; }
  getAppIntelligence(): AppIntelligence { return this.appIntel; }
}
