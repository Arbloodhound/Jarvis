import { ModelRouter } from './ModelRouter';
import { PreferenceLearner } from '../utils/PreferenceLearner';
import { Logger } from '../utils/Logger';
import { UltraDevLog } from '../utils/UltraDevLog';

interface FixAttempt {
  errorHash: string;
  errorMessage: string;
  fixDescription: string;
  fixCode: string;
  result: 'success' | 'failed';
  timestamp: number;
}

export class DebugEngine {
  private modelRouter: ModelRouter;
  private learner: PreferenceLearner;
  private logger: Logger;
  private attempts: FixAttempt[];
  private maxAttempts: number;
  private static readonly HISTORY_KEY = 'debug_history';

  constructor(modelRouter: ModelRouter, learner: PreferenceLearner) {
    this.modelRouter = modelRouter;
    this.learner = learner;
    this.logger = new Logger('DebugEngine');
    this.attempts = [];
    this.maxAttempts = 10;
  }

  async initialize(): Promise<void> {
    const h = this.learner.getPreference(DebugEngine.HISTORY_KEY);
    if (h) {
      try { this.attempts = JSON.parse(h); } catch { this.attempts = []; }
    }
    this.logger.info(`DebugEngine initialized with ${this.attempts.length} historical fixes`);
  }

  async debugLoop(
    code: string,
    error: string,
    taskId: string,
    compileAndTest: (code: string) => Promise<{ success: boolean; error?: string }>
  ): Promise<{ success: boolean; fixedCode: string; attempts: number; totalCost: number }> {
    let currentCode = code;
    let currentError = error;
    let attemptCount = 0;
    let totalCost = 0;
    const triedFixes: string[] = [];

    this.logger.info(`Starting debug loop for task ${taskId}`);
    UltraDevLog.push('SYSTEM', { event: 'debug_engine_loop_start', taskId, codeLen: code.length, errorLen: error.length, maxAttempts: this.maxAttempts });

    while (attemptCount < this.maxAttempts) {
      attemptCount++;
      this.logger.info(`Debug attempt ${attemptCount}/${this.maxAttempts}`);

      const knownFix = this.findKnownFix(currentError);
      let fixedCode: string;

      if (knownFix && !triedFixes.includes(knownFix.fixDescription)) {
        this.logger.info('Applying known fix: ' + knownFix.fixDescription);
        fixedCode = knownFix.fixCode;
        triedFixes.push(knownFix.fixDescription);
      } else {
        const prompt = this.buildDebugPrompt(currentCode, currentError, triedFixes);
        try {
          const result = await this.modelRouter.complete(prompt, {
            model: this.modelRouter.selectModel('debug'),
            taskId,
            agentId: 'debug-engine',
            temperature: 0.3,
            maxTokens: 6000,
          });
          totalCost += result.cost;
          fixedCode = this.extractCode(result.content);

          if (!fixedCode || fixedCode === currentCode) {
            this.logger.warn('AI returned same code, forcing different approach');
            const forcePrompt = `CRITICAL: All previous approaches failed. Tried:\n${triedFixes.join('\n')}\n\nTake a COMPLETELY DIFFERENT approach.\n\nError:\n${currentError}\n\nCode:\n${currentCode}\n\nProvide COMPLETE fixed code only.`;
            const forceResult = await this.modelRouter.complete(forcePrompt, {
              model: this.modelRouter.selectModel('debug'),
              taskId,
              agentId: 'debug-engine',
              temperature: 0.8,
              maxTokens: 6000,
            });
            totalCost += forceResult.cost;
            fixedCode = this.extractCode(forceResult.content);
          }
          triedFixes.push(`Attempt ${attemptCount}: ${result.content.substring(0, 200)}`);
        } catch (aiErr: any) {
          this.logger.error('AI debug request failed: ' + aiErr.message);
          continue;
        }
      }

      if (!fixedCode) { this.logger.warn('No code extracted'); continue; }

      currentCode = fixedCode;
      const testResult = await compileAndTest(fixedCode);

      if (testResult.success) {
        this.logger.info(`Fix successful on attempt ${attemptCount}`);
        await this.recordFix(error, 'Fixed after ' + attemptCount + ' attempts', fixedCode, 'success');
        return { success: true, fixedCode, attempts: attemptCount, totalCost };
      } else {
        currentError = testResult.error || 'Unknown error';
        this.logger.warn(`Fix failed: ${currentError.substring(0, 100)}`);
        await this.recordFix(error, `Attempt ${attemptCount}`, fixedCode, 'failed');
      }
    }

    this.logger.error(`Debug loop exhausted after ${this.maxAttempts} attempts`);
    return { success: false, fixedCode: currentCode, attempts: attemptCount, totalCost };
  }

  private buildDebugPrompt(code: string, error: string, triedFixes: string[]): string {
    let p = `You are a debugging expert. Fix this error.\n\nERROR:\n${error}\n\nCODE:\n${code}\n\n`;
    if (triedFixes.length > 0) {
      p += `ALREADY TRIED AND FAILED (do NOT repeat these):\n`;
      triedFixes.forEach((f, i) => { p += `${i + 1}. ${f}\n`; });
      p += `\nYou MUST try a DIFFERENT approach.\n\n`;
    }
    p += `Respond with ONLY the complete fixed code. No explanations, no markdown fences.`;
    return p;
  }

  private extractCode(response: string): string {
    const blockMatch = response.match(/```(?:\w+)?\n([\s\S]*?)```/);
    if (blockMatch) return blockMatch[1].trim();
    return response.trim();
  }

  private hashError(error: string): string {
    const norm = error.replace(/line \d+/gi, 'line N').replace(/column \d+/gi, 'col N').replace(/\d+/g, 'N').trim().toLowerCase();
    let hash = 0;
    for (let i = 0; i < norm.length; i++) {
      hash = ((hash << 5) - hash) + norm.charCodeAt(i);
      hash = hash & hash;
    }
    return hash.toString(36);
  }

  private findKnownFix(error: string): FixAttempt | null {
    const h = this.hashError(error);
    return this.attempts.find((a) => a.errorHash === h && a.result === 'success') || null;
  }

  private async recordFix(error: string, description: string, code: string, result: 'success' | 'failed'): Promise<void> {
    this.attempts.unshift({
      errorHash: this.hashError(error),
      errorMessage: error.substring(0, 500),
      fixDescription: description,
      fixCode: result === 'success' ? code : '',
      result,
      timestamp: Date.now(),
    });
    const successful = this.attempts.filter((a) => a.result === 'success').slice(0, 100);
    const recent = this.attempts.filter((a) => a.result === 'failed').slice(0, 50);
    this.attempts = [...successful, ...recent];
    await this.learner.setPreference(DebugEngine.HISTORY_KEY, JSON.stringify(this.attempts), 'debug-engine');
  }

  setMaxAttempts(max: number): void { this.maxAttempts = max; }

  getStats(): { totalFixes: number; successRate: number } {
    const total = this.attempts.length;
    const successes = this.attempts.filter((a) => a.result === 'success').length;
    return { totalFixes: total, successRate: total > 0 ? successes / total : 0 };
  }
}
