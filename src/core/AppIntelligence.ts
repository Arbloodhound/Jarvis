import { Platform } from 'react-native';
import AppController from '../native/AppController';
import { EnhancedReActLoop } from './EnhancedReActLoop';
import { ReActLoop } from './ReActLoop';
import { lookupPackage, findBestMatch } from './AppDirectory';
import type { ModelRouter } from './ModelRouter';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import { CorrIdScope } from '../utils/CorrIdScope';
import { logAICall } from '../utils/AICallLogger';

const isNative = Platform.OS !== 'web';

export interface AppDataResult {
  success: boolean;
  app: string;
  packageName: string;
  screenText: string[];
  structuredData: any;
  aiSummary: string;
  confidence: number;
  steps: number;
  timestamp: number;
}

interface ScreenReading {
  nodes: Array<{ i: number; t: string; d: string; c: boolean; e: boolean; s: boolean; x: number; y: number }>;
  activePackage: string;
  allText: string[];
  clickableLabels: string[];
  editableCount: number;
}

interface GoalExecutionResult {
  goalAchieved: boolean;
  steps: number;
  mode: 'deterministic' | 'hybrid';
}

export class AppIntelligence {
  constructor(private ai: ModelRouter) {}

  async search(query: string, options?: { app?: string; extractPrompt?: string; maxSteps?: number }): Promise<AppDataResult> {
    const appName = options?.app || 'google';
    const disposeCorrId = CorrIdScope.enter(`search_${Date.now().toString(36)}`);
    try {
      DebugLog.push('APP_INTEL_SEARCH' as any, { event: 'start', query: query.slice(0, 60), app: appName });
      if (!isNative || !AppController.isAvailable()) return this.failResult(appName, 'Requires Android with accessibility');
      const serviceOn = await AppController.isServiceEnabled().catch(() => false);
      if (!serviceOn) return this.failResult(appName, 'Accessibility not enabled');

      let launched = await this.launchApp(appName);
      if (!launched.success) {
        launched = await this.launchApp('chrome');
        if (!launched.success) return this.failResult(appName, `Could not launch ${appName} or Chrome`);
      }

      const goal = `Search for "${query}" and wait for results to load`;
      const loopResult = await this.runGoal(goal, launched.packageName, options?.maxSteps ?? 12);
      const reading = await this.readScreen();
      DebugLog.push('APP_INTEL_SEARCH' as any, {
        event: 'screen_read',
        app: reading.activePackage,
        textItems: reading.allText.length,
        steps: loopResult.steps,
        goalAchieved: loopResult.goalAchieved,
        mode: loopResult.mode,
      });
      const extraction = await this.extractFromScreen(query, reading, options?.extractPrompt);
      DebugLog.push('APP_INTEL_SEARCH' as any, {
        event: 'done',
        query: query.slice(0, 40),
        confidence: extraction.confidence,
        steps: loopResult.steps,
        mode: loopResult.mode,
      });

      const confidence = loopResult.goalAchieved ? Math.max(extraction.confidence, 0.45) : extraction.confidence;
      return {
        success: loopResult.goalAchieved || confidence > 0.2,
        app: appName,
        packageName: reading.activePackage,
        screenText: reading.allText,
        structuredData: extraction.structuredData,
        aiSummary: extraction.summary,
        confidence,
        steps: loopResult.steps,
        timestamp: Date.now(),
      };
    } finally {
      disposeCorrId();
    }
  }

  async readApp(appName: string, goal: string, options?: { extractPrompt?: string; maxSteps?: number }): Promise<AppDataResult> {
    const disposeCorrId = CorrIdScope.enter(`readapp_${Date.now().toString(36)}`);
    try {
      DebugLog.push('APP_INTEL_READAPP' as any, { event: 'start', app: appName, goal: goal.slice(0, 60) });
      if (!isNative || !AppController.isAvailable()) return this.failResult(appName, 'Requires Android with accessibility');
      const serviceOn = await AppController.isServiceEnabled().catch(() => false);
      if (!serviceOn) return this.failResult(appName, 'Accessibility not enabled');

      const launched = await this.launchApp(appName);
      if (!launched.success) return this.failResult(appName, `Could not launch ${appName}`);

      const loopResult = await this.runGoal(goal, launched.packageName, options?.maxSteps ?? 15);
      const reading = await this.readScreen();
      const extraction = await this.extractFromScreen(goal, reading, options?.extractPrompt);
      DebugLog.push('APP_INTEL_READAPP' as any, {
        event: 'done',
        app: appName,
        confidence: extraction.confidence,
        steps: loopResult.steps,
        mode: loopResult.mode,
      });

      const confidence = loopResult.goalAchieved ? Math.max(extraction.confidence, 0.45) : extraction.confidence;
      return {
        success: loopResult.goalAchieved || confidence > 0.2,
        app: appName,
        packageName: reading.activePackage,
        screenText: reading.allText,
        structuredData: extraction.structuredData,
        aiSummary: extraction.summary,
        confidence,
        steps: loopResult.steps,
        timestamp: Date.now(),
      };
    } finally {
      disposeCorrId();
    }
  }

  async readCurrentScreen(context?: string): Promise<AppDataResult> {
    const reading = await this.readScreen();
    const extraction = await this.extractFromScreen(context || 'Describe what is on screen', reading);
    return {
      success: true,
      app: reading.activePackage,
      packageName: reading.activePackage,
      screenText: reading.allText,
      structuredData: extraction.structuredData,
      aiSummary: extraction.summary,
      confidence: extraction.confidence,
      steps: 0,
      timestamp: Date.now(),
    };
  }

  private async runGoal(goal: string, appPackage: string, maxSteps: number): Promise<GoalExecutionResult> {
    const deterministicLoop = new ReActLoop(
      async () => 'ACTION: done',
      { maxIterations: Math.max(4, Math.min(8, maxSteps)), iterationDelayMs: 1100, allowLLMFallback: false },
    );
    const deterministic = await deterministicLoop.execute(goal, appPackage);
    if (deterministic.goalAchieved || !this.ai.hasApiKey()) {
      return { goalAchieved: deterministic.goalAchieved, steps: deterministic.steps.length, mode: 'deterministic' };
    }

    const aiCall = this.createAiCall();
    const hybridLoop = new EnhancedReActLoop(aiCall, aiCall, { maxIterations: maxSteps, iterationDelayMs: 1200 });
    const hybrid = await hybridLoop.execute(goal, appPackage);
    return { goalAchieved: hybrid.goalAchieved, steps: hybrid.steps.length, mode: 'hybrid' };
  }

  private createAiCall(): (prompt: string) => Promise<string> {
    return async (prompt: string) => {
      const response = await this.ai.complete(prompt, {
        taskId: `appintel_${Date.now().toString(36)}`,
        agentId: 'app_intel',
        maxTokens: 600,
        temperature: 0.2,
      });
      return response.content;
    };
  }

  private async launchApp(appName: string): Promise<{ success: boolean; packageName: string }> {
    try {
      const AgentNative = (await import('../native/AgentNative')).default;
      let pkg = lookupPackage(appName);
      if (!pkg) {
        const installed = await AgentNative.getInstalledApps();
        const match = findBestMatch(appName.toLowerCase(), installed);
        if (match) pkg = match.packageName;
      }
      if (!pkg) return { success: false, packageName: '' };

      const result = await AgentNative.launchApp(pkg);
      if (!result.success) return { success: false, packageName: pkg };
      await new Promise((resolve) => setTimeout(resolve, 2500));
      await AppController.allowPackage(pkg);
      await AppController.allowPackage('com.android.systemui');
      try {
        const fg = await AppController.getActivePackage();
        if (fg && fg !== pkg) await AppController.allowPackage(fg);
      } catch {
        // ignore foreground allow failures
      }

      try {
        const { AuthGate } = await import('./AuthGate');
        const authDetection = await AuthGate.detect();
        if (authDetection.detected) {
          DebugLog.push('APP_INTEL_LAUNCH' as any, { event: 'auth_wall', app: appName, authType: authDetection.authType });
          const userPresent = true;
          let credentialVault: any = undefined;
          try {
            const core = (await import('./AgentCore')).getAgentCoreInstance();
            const cv = core?.getCredentialVault?.();
            if (cv && cv.isEnabled()) {
              credentialVault = {
                hasCredentials: (app: string) => cv.hasCredentials(app),
                autoFill: (app: string, authType: any) => cv.autoFill(app, authType),
              };
            }
          } catch {
            // ignore vault lookup failures
          }
          const authResult = await AuthGate.handle(authDetection, { userPresent, credentialVault });
          if (!authResult.success) return { success: false, packageName: pkg };
        }
      } catch {
        // ignore auth gate failures
      }

      DebugLog.push('APP_INTEL_LAUNCH' as any, { event: 'launched', app: appName, packageName: pkg });
      return { success: true, packageName: pkg };
    } catch (e: any) {
      DebugLog.error('AppIntelligence', `Launch ${appName} failed: ${e.message}`);
      return { success: false, packageName: '' };
    }
  }

  private async readScreen(): Promise<ScreenReading> {
    const result: ScreenReading = { nodes: [], activePackage: '', allText: [], clickableLabels: [], editableCount: 0 };
    try {
      result.activePackage = await AppController.getActivePackage();
      const flat = await AppController.getScreenContentFlat();
      const allNodes: typeof result.nodes = JSON.parse(flat);
      const SKIP_PATTERNS = /^(com\.|android\.|·|•|\.{3,}|\s*)$/;
      result.nodes = allNodes.filter((node) => {
        const text = (node.t || node.d || '').trim();
        if (!text) return false;
        if (text.length < 2 && !node.c) return false;
        if (SKIP_PATTERNS.test(text)) return false;
        return true;
      });
      for (const node of result.nodes) {
        const text = (node.t || node.d || '').trim();
        if (text) {
          result.allText.push(text);
          if (node.c) result.clickableLabels.push(text);
        }
        if (node.e) result.editableCount++;
      }
    } catch (e: any) {
      DebugLog.error('AppIntelligence', `Screen read failed: ${e.message}`);
    }
    return result;
  }

  private async extractFromScreen(query: string, reading: ScreenReading, extractPrompt?: string): Promise<{ summary: string; structuredData: any; confidence: number }> {
    if (!this.ai.hasApiKey()) {
      const text = reading.allText.slice(0, 40);
      const summary = text.length > 0
        ? text.slice(0, 10).join('\n')
        : `Screen captured from ${reading.activePackage}`;
      return { summary, structuredData: { lines: text }, confidence: text.length > 0 ? 0.35 : 0.15 };
    }

    const screenContext = reading.allText.slice(0, 80).join('\n');
    const prompt = `You are reading an Android app screen.\n\nAPP: ${reading.activePackage}\nQUERY: "${query}"\n\nSCREEN CONTENT:\n${screenContext.slice(0, 6000)}\n\n${extractPrompt || 'Extract the most relevant information. Be specific with numbers, dates, prices.'}\n\nRespond:\nSUMMARY: Clear answer (2-5 sentences with real data)\nDATA: JSON of key extracted data\nCONFIDENCE: 0-1`;

    try {
      const aiLog = logAICall({ agentId: 'app_extractor', prompt, maxTokens: 800, temperature: 0.2 });
      const { withRetry } = await import('../utils/AICallLogger');
      const aiResult = await withRetry(
        () => this.ai.complete(prompt, { taskId: `extract_${Date.now().toString(36)}`, agentId: 'app_extractor', maxTokens: 800, temperature: 0.2 }),
        { maxRetries: 2, agentId: 'app_extractor' },
      );
      aiLog.logResponse(aiResult.content, aiResult.cost);
      const content = aiResult.content;
      const sm = content.match(/SUMMARY:\s*(.+?)(?=DATA:|CONFIDENCE:|$)/is);
      const dm = content.match(/DATA:\s*(\{[\s\S]*?\})(?=\s*CONFIDENCE:|$)/i);
      const cm = content.match(/CONFIDENCE:\s*([\d.]+)/i);
      let sd: any = null;
      if (dm) {
        try {
          sd = JSON.parse(dm[1]);
        } catch {
          sd = null;
        }
      }
      const confidence = cm ? Math.min(1, parseFloat(cm[1])) : 0.5;
      DebugLog.push('APP_INTEL_EXTRACT' as any, { event: 'extracted', app: reading.activePackage, confidence, hasData: !!sd });
      return { summary: sm ? sm[1].trim() : content.slice(0, 500), structuredData: sd, confidence };
    } catch {
      return {
        summary: `Screen: ${reading.allText.length} elements from ${reading.activePackage}`,
        structuredData: { lines: reading.allText.slice(0, 30) },
        confidence: 0.2,
      };
    }
  }

  private failResult(app: string, reason: string): AppDataResult {
    return {
      success: false,
      app,
      packageName: '',
      screenText: [],
      structuredData: null,
      aiSummary: reason,
      confidence: 0,
      steps: 0,
      timestamp: Date.now(),
    };
  }
}
