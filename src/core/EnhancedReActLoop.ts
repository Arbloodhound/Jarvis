import { Platform } from 'react-native';
import AppController from '../native/AppController';
import { performTap, performSwipe, getScreenContentFlat } from '../native/AppController';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import { CorrIdScope } from '../utils/CorrIdScope';

interface UINode {
  i: number; t: string; d: string;
  c: boolean; e: boolean; s: boolean;
  x: number; y: number;
}

export interface EnhancedStep {
  iteration: number; thought: string; action: string; description: string;
  actionResult: boolean; screenChanged: boolean; stateHash: string; managerDecision: string;
}

export interface EnhancedReActResult {
  success: boolean; goalAchieved: boolean; steps: EnhancedStep[];
  finalObservation: string; totalIterations: number; error?: string;
}

export interface EnhancedReActOptions {
  maxIterations?: number; iterationDelayMs?: number;
  stabilizationMs?: number; stabilizationRetries?: number;
}

export class EnhancedReActLoop {
  private maxIterations: number;
  private iterationDelayMs: number;
  private stabilizationMs: number;
  private stabilizationRetries: number;

  constructor(
    private managerCall: (prompt: string) => Promise<string>,
    private executorCall: (prompt: string) => Promise<string>,
    options: EnhancedReActOptions = {},
  ) {
    this.maxIterations = options.maxIterations ?? 25;
    this.iterationDelayMs = options.iterationDelayMs ?? 1000;
    this.stabilizationMs = options.stabilizationMs ?? 500;
    this.stabilizationRetries = options.stabilizationRetries ?? 3;
  }

  async execute(goal: string, appHint?: string): Promise<EnhancedReActResult> {
    const steps: EnhancedStep[] = [];
    if (Platform.OS !== 'android') return { success: false, goalAchieved: false, steps, finalObservation: 'Requires Android', totalIterations: 0, error: 'platform' };

    const serviceEnabled = await AppController.isServiceEnabled().catch(() => false);
    if (!serviceEnabled) return { success: false, goalAchieved: false, steps, finalObservation: 'Accessibility not enabled', totalIterations: 0, error: 'no_service' };

    DebugLog.systemEvent('EnhancedReActLoop', `START goal="${goal.slice(0, 80)}" app="${appHint || 'any'}"`);

    let prevNodes: UINode[] = [];
    let currentNodes = await this.getStableScreen();
    if (currentNodes.length === 0) { await this.sleep(2000); currentNodes = await this.getStableScreen(); }

    const freshness = await this.verifyTreeFreshness();
    if (!freshness.fresh) {
      DebugLog.push('SYSTEM' as any, { event: 'a11y_tree_stale_warning', nodeCount: freshness.nodeCount });
    }

    let stuckHashes = new Set<string>();
    let consecutiveStuck = 0;

    for (let iteration = 1; iteration <= this.maxIterations; iteration++) {
      const disposeStep = CorrIdScope.enter(`react_${iteration}`);
      try {
        try {
          const currentPkg = await AppController.getActivePackage();
          if (currentPkg === 'com.agent.ultra') {
            DebugLog.error('EnhancedReActLoop', `SAFETY STOP iter ${iteration}: self-interaction`);
            return { success: false, goalAchieved: false, steps, finalObservation: 'Stopped: self-interaction', totalIterations: iteration, error: 'self_interaction' };
          }
          if (currentPkg) await AppController.allowPackage(currentPkg);
        } catch {}

        // Gap 16B: Auth wall detection each iteration
        try {
          const { AuthGate } = await import('./AuthGate');
          const authCheck = await AuthGate.detect();
          if (authCheck.detected) {
            DebugLog.push('REACT_LOOP_STEP' as any, { event: 'auth_wall_mid_loop', iteration, authType: authCheck.authType, app: authCheck.appPackage });
            const authResult = await AuthGate.handle(authCheck, { userPresent: true });
            if (!authResult.success) {
              return { success: false, goalAchieved: false, steps, finalObservation: `Blocked by ${authCheck.authType} on ${authCheck.appPackage}. ${authResult.message}`, totalIterations: iteration, error: 'auth_wall' };
            }
            currentNodes = await this.getStableScreen();
          }
        } catch {}

        const diff = this.computeDiff(prevNodes, currentNodes);
        const currentObs = this.formatNodes(currentNodes);
        const stateHash = this.hashScreen(currentNodes);

        DebugLog.push('REACT_LOOP_STEP' as any, {
          event: 'screen_state', iteration, nodeCount: currentNodes.length, stateHash,
          nodes: currentNodes.slice(0, 40).map(n => ({ i: n.i, t: (n.t || '').slice(0, 60), d: (n.d || '').slice(0, 60), c: n.c, e: n.e, s: n.s, x: n.x, y: n.y })),
          clickableCount: currentNodes.filter(n => n.c).length,
          editableCount: currentNodes.filter(n => n.e).length,
        });

        DebugLog.push('REACT_LOOP_DIFF' as any, { iteration, diff, prevNodeCount: prevNodes.length, currentNodeCount: currentNodes.length, stateHash });

        if (stuckHashes.has(stateHash)) {
          consecutiveStuck++;
          if (consecutiveStuck >= 3) {
            await AppController.performScroll('down');
            await this.sleep(800);
            currentNodes = await this.getStableScreen();
            consecutiveStuck = 0;
            continue;
          }
        } else {
          consecutiveStuck = 0;
          stuckHashes.add(stateHash);
          if (stuckHashes.size > 30) stuckHashes = new Set(Array.from(stuckHashes).slice(-20));
        }

        if (iteration % 5 === 0) {
          const midFresh = await this.verifyTreeFreshness();
          if (!midFresh.fresh) {
            DebugLog.push('SYSTEM' as any, { event: 'a11y_tree_mid_stale', iteration, nodeCount: midFresh.nodeCount });
            await this.sleep(2000);
          }
        }

        const executorPrompt = this.buildExecutorPrompt(goal, currentObs, diff, steps.slice(-3));
        let executorResponse: string;
        try { executorResponse = await this.executorCall(executorPrompt); } catch (err: any) { DebugLog.error('EnhancedReActLoop', `Executor AI failed iter ${iteration}: ${err.message}`); break; }

        const { thought, action, description } = this.parseExecutorResponse(executorResponse);
        if (!action) { DebugLog.error('EnhancedReActLoop', `No action iter ${iteration}`); continue; }
        if (/^done$/i.test(action.trim())) {
          steps.push({ iteration, thought, action, description, actionResult: true, screenChanged: false, stateHash, managerDecision: 'goal_achieved' });
          return { success: true, goalAchieved: true, steps, finalObservation: currentObs, totalIterations: iteration };
        }

        prevNodes = [...currentNodes];
        const actionResult = await this.executeAction(action);
        await this.sleep(this.iterationDelayMs);
        currentNodes = await this.getStableScreen();
        const screenChanged = this.hashScreen(currentNodes) !== stateHash;

        const stepSummary = `Step ${iteration}: ${action} \u2192 ${actionResult ? 'ok' : 'fail'}, screen ${screenChanged ? 'changed' : 'unchanged'}`;
        const managerDecision = await this.askManager(goal, this.formatNodes(currentNodes), steps, stepSummary);

        steps.push({ iteration, thought, action, description, actionResult, screenChanged, stateHash, managerDecision: managerDecision.slice(0, 200) });

        DebugLog.push('REACT_LOOP_STEP' as any, { event: 'step_done', iteration, action, actionResult, screenChanged, managerSnippet: managerDecision.slice(0, 80) });

        if (/\bdone\b/i.test(managerDecision) && /\bgoal\s*(achieved|complete|success)/i.test(managerDecision)) {
          return { success: true, goalAchieved: true, steps, finalObservation: this.formatNodes(currentNodes), totalIterations: iteration };
        }
      } finally {
        disposeStep();
      }
    }

    const finalObs = this.formatNodes(currentNodes);
    const goalAchieved = await this.checkCompletion(goal, finalObs);
    return { success: goalAchieved, goalAchieved, steps, finalObservation: finalObs, totalIterations: steps.length };
  }

  private async verifyTreeFreshness(): Promise<{ fresh: boolean; ageMs: number; nodeCount: number }> {
    const t0 = Date.now();
    const nodes1 = await this.getNodes();
    await this.sleep(500);
    const nodes2 = await this.getNodes();
    const fresh = nodes2.length > 0;
    DebugLog.push('SYSTEM' as any, { event: 'a11y_tree_heartbeat', fresh, nodeCount: nodes2.length, hashChanged: this.hashScreen(nodes1) !== this.hashScreen(nodes2) });
    return { fresh, ageMs: Date.now() - t0, nodeCount: nodes2.length };
  }

  private async getStableScreen(): Promise<UINode[]> {
    let nodes = await this.getNodes();
    for (let i = 0; i < this.stabilizationRetries; i++) {
      await this.sleep(this.stabilizationMs);
      const next = await this.getNodes();
      if (this.hashScreen(nodes) === this.hashScreen(next)) return next;
      nodes = next;
    }
    return nodes;
  }

  private computeDiff(prev: UINode[], current: UINode[]): string {
    if (prev.length === 0) return 'Initial screen.';
    const prevTexts = new Set(prev.map(n => (n.t || n.d || '').toLowerCase().trim()).filter(Boolean));
    const currTexts = new Set(current.map(n => (n.t || n.d || '').toLowerCase().trim()).filter(Boolean));
    const appeared: string[] = []; const disappeared: string[] = [];
    for (const t of currTexts) { if (!prevTexts.has(t)) appeared.push(t); }
    for (const t of prevTexts) { if (!currTexts.has(t)) disappeared.push(t); }
    const lines: string[] = [];
    if (appeared.length > 0) lines.push(`NEW: ${appeared.slice(0, 8).map(t => `"${t.slice(0, 40)}"`).join(', ')}`);
    if (disappeared.length > 0) lines.push(`GONE: ${disappeared.slice(0, 8).map(t => `"${t.slice(0, 40)}"`).join(', ')}`);
    if (lines.length === 0) lines.push('No visible text changes.');
    return lines.join('\n');
  }

  private buildExecutorPrompt(goal: string, observation: string, diff: string, recentSteps: EnhancedStep[]): string {
    const history = recentSteps.map(s => `[${s.iteration}] ${s.action} \u2192 ${s.actionResult ? 'ok' : 'fail'} | ${s.description.slice(0, 60)}`).join('\n') || 'None';
    return `You control an Android screen. Choose ONE action toward the goal.\n\nGOAL: ${goal}\n\nSCREEN CHANGES:\n${diff}\n\nCURRENT SCREEN:\n${observation.slice(0, 2500)}\n\nRECENT:\n${history}\n\nACTIONS: tap_index(N), tap(x,y), type("text"), scroll(up|down), swipe(x1,y1,x2,y2), back(), home(), done\n\nRESPOND:\nTHOUGHT: <why>\nACTION: <single action>\nDESCRIPTION: <what this accomplishes>`;
  }

  private async askManager(goal: string, currentScreen: string, allSteps: EnhancedStep[], latestEvent: string): Promise<string> {
    const stepsSummary = allSteps.slice(-5).map(s => `${s.action} \u2192 ${s.actionResult ? 'ok' : 'fail'}${s.screenChanged ? ' [changed]' : ''}`).join(' | ');
    const prompt = `You oversee a mobile automation task.\n\nGOAL: ${goal}\nPROGRESS: ${stepsSummary || 'none'}\nLATEST: ${latestEvent}\nSCREEN: ${currentScreen.slice(0, 800)}\n\nIs the goal achieved? Continue, try different approach, or give up? Reply briefly. Say "goal achieved" if done.`;
    try { return await this.managerCall(prompt); } catch { return 'continue'; }
  }

  private async checkCompletion(goal: string, observation: string): Promise<boolean> {
    try {
      const response = await this.managerCall(`Goal: "${goal}"\nScreen:\n${observation.slice(0, 800)}\n\nIs the goal fully achieved? YES or NO only.`);
      return /^yes/i.test(response.trim());
    } catch { return false; }
  }

  private parseExecutorResponse(text: string): { thought: string; action: string; description: string } {
    const thoughtMatch = text.match(/THOUGHT:\s*(.+?)(?=ACTION:|$)/is);
    const actionMatch = text.match(/ACTION:\s*(.+?)(?=DESCRIPTION:|$)/im);
    const descMatch = text.match(/DESCRIPTION:\s*(.+)/is);
    let action = actionMatch ? actionMatch[1].trim() : '';
    if (!action) {
      const patterns = [/\b(tap_index\(\s*\d+\s*\))/i, /\b(tap\(\s*\d+\s*,\s*\d+\s*\))/i, /\b(type\(\s*["']?.+?["']?\s*\))/i, /\b(scroll\(\s*(?:up|down)\s*\))/i, /\b(swipe\(\s*\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*\d+\s*\))/i, /\b(back\(\))/i, /\b(home\(\))/i, /\b(done)\b/i];
      for (const p of patterns) { const found = text.match(p); if (found) { action = found[1].trim(); break; } }
    }
    return { thought: thoughtMatch ? thoughtMatch[1].trim() : '', action, description: descMatch ? descMatch[1].trim() : '' };
  }

  private async executeAction(action: string): Promise<boolean> {
    const a = action.trim();
    try {
      if (/^back(\(\))?$/i.test(a)) return await AppController.performBack();
      if (/^home(\(\))?$/i.test(a)) return await AppController.performHome();
      const scrollMatch = a.match(/^scroll\((up|down)\)$/i);
      if (scrollMatch) return await AppController.performScroll(scrollMatch[1].toLowerCase() as any);
      const tapMatch = a.match(/^tap\(\s*(\d+)\s*,\s*(\d+)\s*\)$/i);
      if (tapMatch) return await performTap(parseInt(tapMatch[1]), parseInt(tapMatch[2]));
      const tapIdxMatch = a.match(/^tap_index\(\s*(\d+)\s*\)$/i);
      if (tapIdxMatch) {
        const idx = parseInt(tapIdxMatch[1]);
        const nodes = await this.getNodes();
        const node = nodes.find(n => n.i === idx);
        if (node) return await performTap(node.x, node.y);
        return false;
      }
      const typeMatch = a.match(/^type\(\s*["']?(.+?)["']?\s*\)$/i);
      if (typeMatch) return await AppController.performText('', typeMatch[1]);
      const swipeMatch = a.match(/^swipe\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)$/i);
      if (swipeMatch) return await performSwipe(parseInt(swipeMatch[1]), parseInt(swipeMatch[2]), parseInt(swipeMatch[3]), parseInt(swipeMatch[4]), 350);
      return false;
    } catch (err: any) { DebugLog.error('EnhancedReActLoop', `Action error: ${err.message}`); return false; }
  }

  private async getNodes(): Promise<UINode[]> {
    try { const flat = await getScreenContentFlat(); return JSON.parse(flat) as UINode[]; } catch { return []; }
  }

  private formatNodes(nodes: UINode[]): string {
    if (nodes.length === 0) return 'Screen: empty';
    return nodes.map(n => { const label = (n.t || n.d || '').slice(0, 60); const flags: string[] = []; if (n.c) flags.push('tap'); if (n.e) flags.push('type'); if (n.s) flags.push('scroll'); return `[${n.i}] "${label}" [${flags.join(',') || 'view'}] @(${n.x},${n.y})`; }).join('\n');
  }

  private hashScreen(nodes: UINode[]): string {
    const str = nodes.slice(0, 30).map(n => `${n.i}:${(n.t || n.d || '').slice(0, 10)}`).join('|');
    let hash = 0;
    for (let i = 0; i < str.length; i++) { hash = ((hash << 5) - hash) + str.charCodeAt(i); hash |= 0; }
    return hash.toString(36);
  }

  private sleep(ms: number): Promise<void> { return new Promise(resolve => setTimeout(resolve, ms)); }
}
