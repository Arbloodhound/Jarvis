import { Platform } from 'react-native';
import AppController from '../native/AppController';
import { performTap, performSwipe, getScreenContentFlat, waitForUiChange } from '../native/AppController';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

export interface ReActStep {
  iteration: number;
  observation: string;
  reasoning: string;
  action: string;
  actionResult: boolean;
  uiChanged: boolean;
}

export interface ReActResult {
  success: boolean;
  steps: ReActStep[];
  finalObservation: string;
  goalAchieved: boolean;
  error?: string;
}

export interface ReActOptions {
  maxIterations?: number;
  iterationDelayMs?: number;
}

export class ReActLoop {
  private maxIterations: number;
  private iterationDelayMs: number;

  constructor(
    private aiCall: (prompt: string) => Promise<string>,
    options: ReActOptions = {}
  ) {
    this.maxIterations = options.maxIterations ?? 8;
    this.iterationDelayMs = options.iterationDelayMs ?? 1200;
  }

  async execute(goal: string, appHint?: string): Promise<ReActResult> {
    const steps: ReActStep[] = [];

    if (Platform.OS !== 'android') {
      return { success: false, steps, finalObservation: 'ReAct requires Android', goalAchieved: false, error: 'platform' };
    }

    const serviceEnabled = await AppController.isServiceEnabled().catch(() => false);
    if (!serviceEnabled) {
      const msg = 'Accessibility service not enabled. Go to Settings > Accessibility > Agent Ultra and enable it.';
      return { success: false, steps, finalObservation: msg, goalAchieved: false, error: 'no_service' };
    }

    DebugLog.systemEvent('ReActLoop', `START goal="${goal.slice(0, 80)}" app="${appHint || 'any'}"`);

    // Wait for accessibility tree to populate — first read may be empty
    let observation = await this.observe();
    if (observation === 'Screen: empty or inaccessible' || observation === 'Screen: observation failed') {
      await this.sleep(1500);
      observation = await this.observe();
      if (observation === 'Screen: empty or inaccessible' || observation === 'Screen: observation failed') {
        await this.sleep(1500);
        observation = await this.observe();
      }
    }
    let stuckCount = 0;
    let lastTreePrefix = '';
    let deterministicFailCount = 0;

    for (let iteration = 1; iteration <= this.maxIterations; iteration++) {
      // TASK 2 SAFETY: verify we're NOT looking at our own UI each iteration
      try {
        const currentPkg = await AppController.getActivePackage();
        if (currentPkg === 'com.agent.ultra') {
          DebugLog.error('ReActLoop', `SAFETY STOP at iter ${iteration}: foreground package is Agent Ultra — aborting to prevent self-interaction`);
          return { success: false, steps, finalObservation: 'ReActLoop detected self-interaction — stopped for safety', goalAchieved: false, error: 'self_interaction' };
        }
        // Re-allow current foreground package — handles app redirects, permission dialogs, mid-loop package changes
        if (currentPkg) await AppController.allowPackage(currentPkg);
      } catch (e: any) { DebugLog.error('ReActLoop', `Safety check failed at iter ${iteration}: ${e?.message}`); }

      // DETERMINISTIC: try to match goal to UI elements without LLM first
      const nodes = await this.getNodes();
      const deterministicAction = nodes.length > 0 ? this.executeDeterministic(this.parseGoal(goal), nodes) : null;

      if (deterministicAction) {
        DebugLog.systemEvent('ReActLoop', `STEP ${iteration}: DETERMINISTIC action="${deterministicAction}"`);
        if (/^done$/i.test(deterministicAction.trim())) {
          steps.push({ iteration, observation, reasoning: 'deterministic:done', action: deterministicAction, actionResult: true, uiChanged: false });
          return { success: true, steps, finalObservation: observation, goalAchieved: true };
        }
        const beforeObs = observation;
        const actionResult = await this.executeAction(deterministicAction);
        await this.sleep(this.iterationDelayMs);
        const newObservation = await this.observe();
        const uiChanged = newObservation !== beforeObs;
        steps.push({ iteration, observation, reasoning: 'deterministic', action: deterministicAction, actionResult, uiChanged });
        DebugLog.systemEvent('ReActLoop', `STEP ${iteration}: det="${deterministicAction}" result=${actionResult} uiChanged=${uiChanged}`);
        const treePrefix2 = newObservation.slice(0, 80);
        if (treePrefix2 === lastTreePrefix) {
          stuckCount++;
          if (stuckCount >= 2) { await AppController.performScroll('down'); await this.sleep(600); stuckCount = 0; }
        } else { stuckCount = 0; }
        lastTreePrefix = treePrefix2;
        observation = newObservation;
        continue;
      } else {
        deterministicFailCount++;
      }

      // LLM FALLBACK: only when deterministic matching failed
      DebugLog.systemEvent('ReActLoop', `STEP ${iteration}: Falling back to LLM (deterministic failed ${deterministicFailCount}x)`);

      const systemPrompt = `You control an Android screen. You see UI elements and choose one action. Respond ONLY: ACTION: tap_index(N), tap(x,y), type("text"), scroll(up|down), back(), done. No explanation.`;
      const userMessage = `GOAL: ${goal}\n\nSCREEN:\n${observation.slice(0, 2000)}\n\nACTION:`;
      let reasoning: string;
      try {
        reasoning = await this.aiCall(`${systemPrompt}\n\n${userMessage}`);
      } catch (err: any) {
        DebugLog.error('ReActLoop', `AI failed at step ${iteration}: ${err.message}`);
        break;
      }

      const action = this.extractAction(reasoning);
      if (!action) {
        DebugLog.error('ReActLoop', `No action extracted at step ${iteration}`);
        break;
      }

      if (/^done$/i.test(action.trim())) {
        steps.push({ iteration, observation, reasoning, action, actionResult: true, uiChanged: false });
        DebugLog.systemEvent('ReActLoop', `DONE at step ${iteration}`);
        return { success: true, steps, finalObservation: observation, goalAchieved: true };
      }

      const beforeObservation = observation;
      const actionResult = await this.executeAction(action);
      await this.sleep(this.iterationDelayMs);
      const newObservation = await this.observe();
      const uiChanged = newObservation !== beforeObservation;

      steps.push({ iteration, observation, reasoning, action, actionResult, uiChanged });
      DebugLog.systemEvent('ReActLoop', `STEP ${iteration}: "${action}" result=${actionResult} uiChanged=${uiChanged}`);

      const treePrefix = newObservation.slice(0, 80);
      if (treePrefix === lastTreePrefix) {
        stuckCount++;
        if (stuckCount >= 2) {
          await AppController.performScroll('down');
          await this.sleep(600);
          stuckCount = 0;
        }
      } else {
        stuckCount = 0;
      }
      lastTreePrefix = treePrefix;
      observation = newObservation;
    }

    const goalAchieved = await this.checkCompletion(goal, observation);
    DebugLog.systemEvent('ReActLoop', `COMPLETE goalAchieved=${goalAchieved} steps=${steps.length}`);
    return { success: goalAchieved, steps, finalObservation: observation, goalAchieved };
  }

  private async observe(): Promise<string> {
    try {
      const flat = await getScreenContentFlat();
      const nodes = JSON.parse(flat) as Array<{
        i: number; t: string; d: string;
        c: boolean; e: boolean; s: boolean;
        x: number; y: number;
      }>;
      if (nodes.length === 0) return 'Screen: empty or inaccessible';
      return nodes.map(n => {
        const label = (n.t || n.d || '').slice(0, 60);
        const flags: string[] = [];
        if (n.c) flags.push('tap');
        if (n.e) flags.push('type');
        if (n.s) flags.push('scroll');
        return `[${n.i}] "${label}" [${flags.join(',') || 'view'}] @(${n.x},${n.y})`;
      }).join('\n');
    } catch {
      try {
        const tree = await AppController.getScreenContent();
        return typeof tree === 'string' ? (tree as string).slice(0, 1500) : JSON.stringify(tree).slice(0, 1500);
      } catch {
        return 'Screen: observation failed';
      }
    }
  }

  private buildPrompt(goal: string, observation: string, history: ReActStep[]): string {
    const recentHistory = history.slice(-3).map(s =>
      `Step ${s.iteration}: ${s.action} -> ${s.actionResult ? 'ok' : 'fail'}${s.uiChanged ? ' (screen changed)' : ' (no change)'}`
    ).join('\n') || 'None';

    return `You are controlling an Android phone. Analyze the screen and choose ONE action.

GOAL: ${goal}

CURRENT SCREEN (index, label, capabilities, coordinates):
${observation.slice(0, 2500)}

RECENT ACTIONS:
${recentHistory}

AVAILABLE ACTIONS:
- tap(x,y) - tap screen coordinates
- tap_index(N) - tap element by index [N]
- type("text") - type into focused field
- scroll(down) or scroll(up)
- swipe(x1,y1,x2,y2)
- back()
- home()
- done - goal is achieved

RULES:
1. Identify elements by label and index number.
2. Choose the single most direct action toward the goal.
3. If goal achieved: ACTION: done
4. Never repeat an action that had no effect.
5. If element not visible, scroll first.
6. Prefer tap_index(N) over coordinates when possible.

RESPOND WITH:
REASONING: <brief analysis>
ACTION: <single action command>`;
  }

  private extractAction(text: string): string | null {
    const strict = text.match(/^ACTION:\s*(.+)$/im);
    if (strict) return strict[1].trim();

    const actionPatterns = [
      /\b(tap_index\(\s*\d+\s*\))/i,
      /\b(tap\(\s*\d+\s*,\s*\d+\s*\))/i,
      /\b(type\(\s*["']?.+?["']?\s*\))/i,
      /\b(scroll\(\s*(?:up|down|forward|backward)\s*\))/i,
      /\b(swipe\(\s*\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*\d+\s*\))/i,
      /\b(back\(\))/i,
      /\b(home\(\))/i,
      /\b(done)\b/i,
    ];
    for (const pattern of actionPatterns) {
      const found = text.match(pattern);
      if (found) return found[1].trim();
    }

    const trimmed = text.trim().toLowerCase();
    if (trimmed === 'done' || trimmed === 'back' || trimmed === 'back()') return trimmed;
    return null;
  }

  private async executeAction(action: string): Promise<boolean> {
    const a = action.trim();
    try {
      if (/^back\(\)$/i.test(a) || /^back$/i.test(a)) return await AppController.performBack();
      if (/^home\(\)$/i.test(a) || /^home$/i.test(a)) return await AppController.performHome();

      const scrollMatch = a.match(/^scroll\((up|down|forward|backward)\)$/i);
      if (scrollMatch) {
        const dir = scrollMatch[1].toLowerCase();
        return await AppController.performScroll(dir === 'up' || dir === 'backward' ? 'up' : 'down');
      }

      const tapMatch = a.match(/^tap\(\s*(\d+)\s*,\s*(\d+)\s*\)$/i);
      if (tapMatch) return await performTap(parseInt(tapMatch[1]), parseInt(tapMatch[2]));

      const tapIdxMatch = a.match(/^tap_index\(\s*(\d+)\s*\)$/i);
      if (tapIdxMatch) {
        const idx = parseInt(tapIdxMatch[1]);
        const flat = await getScreenContentFlat();
        const nodes = JSON.parse(flat) as Array<{ i: number; x: number; y: number }>;
        const node = nodes.find(n => n.i === idx);
        if (node) return await performTap(node.x, node.y);
        return false;
      }

      const typeMatch = a.match(/^type\(\s*["']?(.+?)["']?\s*\)$/i);
      if (typeMatch) return await AppController.performText('', typeMatch[1]);

      const swipeMatch = a.match(/^swipe\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)$/i);
      if (swipeMatch) {
        return await performSwipe(
          parseInt(swipeMatch[1]), parseInt(swipeMatch[2]),
          parseInt(swipeMatch[3]), parseInt(swipeMatch[4]),
          350
        );
      }

      DebugLog.error('ReActLoop', `Unknown action: ${a}`);
      return false;
    } catch (err: any) {
      DebugLog.error('ReActLoop', `Action error: ${err.message}`);
      return false;
    }
  }

  private async checkCompletion(goal: string, observation: string): Promise<boolean> {
    try {
      const prompt = `Goal: "${goal}"\nCurrent screen:\n${observation.slice(0, 800)}\n\nIs the goal fully achieved? Reply YES or NO only.`;
      const response = await this.aiCall(prompt);
      return /^yes/i.test(response.trim());
    } catch {
      return false;
    }
  }

  private sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  private async getNodes(): Promise<Array<{ i: number; t: string; d: string; c: boolean; e: boolean; s: boolean; x: number; y: number }>> {
    try {
      const flat = await getScreenContentFlat();
      const parsed = JSON.parse(flat) as Array<{ i: number; t: string; d: string; c: boolean; e: boolean; s: boolean; x: number; y: number }>;
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }

  private parseGoal(goal: string): { action: string; target: string; value: string } {
    const g = goal.toLowerCase().trim();
    const searchMatch = g.match(/^(?:search|find|look\s*up)\s+(?:for\s+)?["']?(.+?)["']?(?:\s+(?:in|on|using|within).*)?$/i);
    if (searchMatch) return { action: 'search', target: 'search_field', value: searchMatch[1].trim() };
    const tapMatch = g.match(/^(?:tap|click|press|select|choose)\s+(?:the\s+)?["']?(.+?)["']?(?:\s+button)?$/i);
    if (tapMatch) return { action: 'tap', target: tapMatch[1].trim(), value: '' };
    const typeMatch = g.match(/^(?:type|enter|input|fill\s+in)\s+["']?(.+?)["']?(?:\s+(?:in|into|to)\s+(.+))?$/i);
    if (typeMatch) return { action: 'type', target: typeMatch[2]?.trim() || 'input_field', value: typeMatch[1].trim() };
    const scrollMatch = g.match(/^scroll\s+(up|down)$/i);
    if (scrollMatch) return { action: 'scroll', target: scrollMatch[1].toLowerCase(), value: '' };
    return { action: 'tap', target: g.slice(0, 40), value: '' };
  }

  private findNodeByText(
    nodes: Array<{ i: number; t: string; d: string; c: boolean; e: boolean; s: boolean; x: number; y: number }>,
    text: string
  ): { i: number; x: number; y: number } | null {
    const needle = text.toLowerCase().trim();
    const exact = nodes.find(n => (n.t || n.d || '').toLowerCase().trim() === needle && n.c);
    if (exact) return exact;
    const partial = nodes.find(n => (n.t || n.d || '').toLowerCase().includes(needle) && n.c);
    if (partial) return partial;
    const loose = nodes.find(n => needle.split(' ').some(word => word.length > 3 && (n.t || n.d || '').toLowerCase().includes(word)) && n.c);
    return loose || null;
  }

  private findEditableField(
    nodes: Array<{ i: number; t: string; d: string; c: boolean; e: boolean; s: boolean; x: number; y: number }>
  ): { i: number; x: number; y: number } | null {
    const searchField = nodes.find(n => n.e && /search|query|find|q=/i.test(n.t + n.d));
    if (searchField) return searchField;
    const editableField = nodes.find(n => n.e);
    return editableField || null;
  }

  private findScrollable(
    nodes: Array<{ i: number; t: string; d: string; c: boolean; e: boolean; s: boolean; x: number; y: number }>
  ): { i: number; x: number; y: number } | null {
    return nodes.find(n => n.s) || null;
  }

  private executeDeterministic(
    parsed: { action: string; target: string; value: string },
    nodes: Array<{ i: number; t: string; d: string; c: boolean; e: boolean; s: boolean; x: number; y: number }>
  ): string | null {
    if (parsed.action === 'search') {
      const field = this.findEditableField(nodes);
      if (field) return `tap_index(${field.i})`;
      const searchBtn = this.findNodeByText(nodes, 'search');
      if (searchBtn) return `tap_index(${searchBtn.i})`;
      return null;
    }
    if (parsed.action === 'tap') {
      const node = this.findNodeByText(nodes, parsed.target);
      if (node) return `tap_index(${node.i})`;
      return null;
    }
    if (parsed.action === 'type') {
      const field = parsed.target !== 'input_field'
        ? this.findNodeByText(nodes, parsed.target) || this.findEditableField(nodes)
        : this.findEditableField(nodes);
      if (field) return `type("${parsed.value}")`;
      return null;
    }
    if (parsed.action === 'scroll') {
      return `scroll(${parsed.target})`;
    }
    return null;
  }
}