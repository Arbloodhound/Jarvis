import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import { MemoryManager } from './MemoryManager';
import type { TaskExecutor } from './TaskExecutor';
import type { ModelRouter } from './ModelRouter';
import { CommandParser } from './CommandParser';

export interface PlanStep {
  id: string;
  description: string;
  capability: string;
  params: Record<string, any>;
  status: 'pending' | 'running' | 'done' | 'failed';
  result?: any;
  retries: number;
}

export class TaskPlanner {
  private parser = new CommandParser();

  constructor(
    private executor: TaskExecutor,
    private memory: MemoryManager,
    private ai: ModelRouter
  ) {}

  async executeComplexTask(goal: string, taskId: string): Promise<{ success: boolean; summary: string }> {
    DebugLog.systemEvent('TaskPlanner', `START goal="${goal.slice(0, 100)}"`);

    const deterministicSteps = this.deterministicDecompose(goal);
    if (deterministicSteps.length > 0) {
      return this.executeSteps(deterministicSteps, goal, taskId);
    }

    const steps = await this.aiDecompose(goal, taskId);
    return this.executeSteps(steps, goal, taskId);
  }

  private deterministicDecompose(goal: string): PlanStep[] {
    const connectors = /\b(?:then|and then|after that|followed by|next|afterwards|subsequently|once done|when done|after which|and also)\b/i;
    const rawParts = goal.split(connectors);
    const parts = rawParts.map(s => s.trim()).filter(s => s.length > 3);
    if (parts.length < 2) return [];
    const steps: PlanStep[] = [];
    for (let i = 0; i < parts.length; i++) {
      const plan = this.parser.parse(parts[i]);
      if (!plan) return [];
      steps.push({
        id: `step_${i}`,
        description: parts[i],
        capability: plan.capability,
        params: plan.params,
        status: 'pending',
        retries: 0,
      });
    }
    return steps;
  }

  private async aiDecompose(goal: string, taskId: string): Promise<PlanStep[]> {
    const relevantMemory = await this.memory.retrieveRelevant(goal, 3);
    const memoryContext = this.memory.formatForPrompt(relevantMemory);

    const prompt = `Decompose this goal into sequential atomic steps. Each step maps to one capability.

GOAL: "${goal}"

${memoryContext}

AVAILABLE CAPABILITIES:
app_launch, sms_send, camera_capture, device_location, device_info, web_search, open_url,
calendar_create, reminder_create, note_create, alarm_set, timer_set, flashlight_toggle,
clipboard_write, clipboard_read, screenshot, notification_read, share_content, media_play,
media_next, app_info, react_navigate, file_read, file_write, contacts_read

Respond ONLY with valid JSON array, no other text:
[{"description":"step","capability":"exact_name","params":{"key":"value"}}]`;

    try {
      const result = await this.ai.complete(prompt, {
        taskId,
        agentId: 'planner',
        maxTokens: 1000,
        temperature: 0.2,
      });
      const clean = result.content.replace(/```json|```/g, '').trim();
      const parsed = JSON.parse(clean);
      if (!Array.isArray(parsed)) throw new Error('Not an array');
      return parsed.map((s: any, i: number) => ({
        id: `step_${i}`,
        description: s.description || `Step ${i + 1}`,
        capability: s.capability || 'ai_query',
        params: s.params || {},
        status: 'pending' as const,
        retries: 0,
      }));
    } catch (err: any) {
      DebugLog.error('TaskPlanner', `AI decompose failed: ${err.message}`);
      return [{
        id: 'step_0',
        description: goal,
        capability: 'ai_query',
        params: { query: goal },
        status: 'pending',
        retries: 0,
      }];
    }
  }

  private async executeSteps(steps: PlanStep[], goal: string, taskId: string): Promise<{ success: boolean; summary: string }> {
    const results: string[] = [];
    let allSucceeded = true;

    for (const step of steps) {
      step.status = 'running';
      DebugLog.systemEvent('TaskPlanner', `STEP ${step.id}: ${step.capability}`);

      let succeeded = false;
      for (let attempt = 0; attempt <= 1; attempt++) {
        try {
          const result = await this.executor.runWithPlan(
            { capability: step.capability, params: step.params, reason: step.description },
            taskId
          );
          step.result = result;
          succeeded = result.success;
          if (succeeded) break;
          step.retries++;
        } catch (err: any) {
          step.result = { success: false, error: err.message };
          step.retries++;
        }
      }

      step.status = succeeded ? 'done' : 'failed';
      results.push(succeeded
        ? `Done: ${step.description}`
        : `Failed: ${step.description}`
      );
      if (!succeeded) allSucceeded = false;
      const STEP_DELAY_MS = 500;
      await new Promise(r => setTimeout(r, STEP_DELAY_MS));
    }

    const summary = results.join(' -> ');
    if (allSucceeded) await this.memory.promoteLongterm(goal, 'multi_step', summary);
    DebugLog.systemEvent('TaskPlanner', `COMPLETE success=${allSucceeded}`);
    return { success: allSucceeded, summary };
  }
}