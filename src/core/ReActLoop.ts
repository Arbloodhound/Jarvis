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
  allowLLMFallback?: boolean;
  visionSparseThreshold?: number;
  skipPlanning?: boolean;
}

interface FlatNode {
  i: number;
  t: string;
  d: string;
  c: boolean;
  e: boolean;
  s: boolean;
  x: number;
  y: number;
}

interface ParsedGoal {
  action: 'search' | 'tap' | 'type' | 'scroll';
  target: string;
  value: string;
}

interface TextMatchOptions {
  clickableOnly?: boolean;
  preferNonEditable?: boolean;
}

const SEARCH_FIELD_HINT = /(search|find|query|lookup)/i;
const SEARCH_BUTTON_HINT = /^(search|go|enter|submit|done|ok|apply)$/i;
const RESULT_TEXT_HINT = /(result|results|price|rating|reviews?|buy|shop|watch|play|open|visit)/i;

const VISION_SPARSE_THRESHOLD = 8;

export class ReActLoop {
  private maxIterations: number;
  private iterationDelayMs: number;
  private allowLLMFallback: boolean;
  private visionSparseThreshold: number;
  private skipPlanning: boolean;
  private _cancelled = false;

  constructor(
    private aiCall: (prompt: string) => Promise<string>,
    options: ReActOptions = {}
  ) {
    this.maxIterations = options.maxIterations ?? 8;
    this.iterationDelayMs = options.iterationDelayMs ?? 1200;
    this.allowLLMFallback = options.allowLLMFallback ?? true;
    this.visionSparseThreshold = options.visionSparseThreshold ?? VISION_SPARSE_THRESHOLD;
    this.skipPlanning = options.skipPlanning ?? false;
  }

  /** Signal the loop to stop at the next iteration check. */
  cancel(): void {
    this._cancelled = true;
    console.warn('[REACT] cancel() called — will stop at next iteration');
  }

  private async tryGetVisionContext(): Promise<string | null> {
    try {
      const { getAgentCoreInstance } = await import('./AgentCore');
      const core = getAgentCoreInstance();
      const vision = core?.getCortex()?.getVisionPipeline();
      if (!vision) return null;
      const u = await vision.understand();
      if (!u || !u.description) return null;
      // confidence < 0.2 means even the a11y tree was empty — nothing useful to offer
      if (u.confidence < 0.2) return null;
      // confidence === 0.2 means AI vision failed but we have a tree-based fallback.
      // Always use [VISUAL] as the outer tag for downstream consistency; append
      // [TREE_FALLBACK] to signal that no image-model analysis was performed.
      const tag = u.confidence <= 0.2 ? '[VISUAL][TREE_FALLBACK]' : '[VISUAL]';
      return `${tag} ${u.description}${u.textContent.length > 0 ? '\nText visible: ' + u.textContent.slice(0, 5).join(' | ') : ''}`;
    } catch {
      return null;
    }
  }

  /**
   * One-time planning call: given the goal, current screen, and app context,
   * produce an ordered list of concrete UI steps. Separates planning from
   * execution (AppAgent pattern).
   */
  private async planSteps(goal: string, observation: string, appPackage: string): Promise<string[]> {
    const prompt = `You are planning UI actions on an Android phone.

APP: ${appPackage || 'unknown'}
GOAL: ${goal}

CURRENT SCREEN:
${observation.slice(0, 1200)}

Produce 3-8 concrete UI steps to achieve the goal from this screen.
Each step must be a specific action like "tap the search bar", "type 'query text'", "scroll down to find X", "tap the first result".
Do NOT use vague steps like "find what you need" or "browse around".

Respond with ONLY a JSON array of strings. No explanation. Example:
["tap the search bar", "type 'tokyo flights'", "tap Search button", "scroll down to see results"]`;

    try {
      const response = await this.aiCall(prompt);
      const cleaned = response.replace(/```json|```/g, '').trim();
      const start = cleaned.indexOf('[');
      const end = cleaned.lastIndexOf(']');
      if (start >= 0 && end > start) {
        const parsed = JSON.parse(cleaned.slice(start, end + 1));
        if (Array.isArray(parsed) && parsed.length > 0 && parsed.every((s: any) => typeof s === 'string')) {
          DebugLog.systemEvent('ReActLoop', `PLAN (${parsed.length} steps): ${parsed.join(' → ')}`);
          return parsed;
        }
      }
    } catch (err: any) {
      DebugLog.error('ReActLoop', `Planning failed: ${err.message}`);
    }
    // If planning fails, return empty — loop will run without plan context (current behavior)
    return [];
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

    DebugLog.systemEvent('ReActLoop', `START goal="${goal.slice(0, 80)}" app="${appHint || 'any'}" llmFallback=${this.allowLLMFallback} skipPlanning=${this.skipPlanning}`);

    // Resolve the expected package from appHint early — needed for foreground wait + wrong-app detection
    let expectedPkg = '';
    if (appHint) {
      try {
        const { findBestMatch: findMatch, lookupPackage: lookupPkg } = await import('./AppDirectory');
        const knownPkg = lookupPkg(appHint.toLowerCase().trim());
        if (knownPkg) {
          expectedPkg = knownPkg;
        } else {
          const AgentNativeModule = (await import('../native/AgentNative')).default;
          const installed = await AgentNativeModule.getInstalledApps();
          const m = findMatch(appHint.toLowerCase().trim(), installed, 55);
          if (m) expectedPkg = m.packageName;
        }
      } catch {}
    }
    console.warn(`[REACT] expectedPkg=${expectedPkg} appHint=${appHint || 'none'}`);

    // Wait for the target app to be in foreground before observing
    if (expectedPkg) {
      for (let wait = 0; wait < 10; wait++) {
        const fg = await AppController.getActivePackage().catch(() => '');
        if (fg === expectedPkg) {
          console.warn(`[REACT_TIMING] target_app_ready: ${expectedPkg} after ${wait * 500}ms`);
          break;
        }
        await this.sleep(500);
      }
      // Extra settle time for the app to finish rendering
      await this.sleep(1000);
    }

    const _observeStart = Date.now();
    let observation = await this.observe();
    console.warn(`[REACT_TIMING] initial_observe: ${Date.now() - _observeStart}ms`);
    if (observation === 'Screen: empty or inaccessible' || observation === 'Screen: observation failed') {
      await this.sleep(1500);
      observation = await this.observe();
      if (observation === 'Screen: empty or inaccessible' || observation === 'Screen: observation failed') {
        await this.sleep(1500);
        observation = await this.observe();
      }
    }


    const parsedGoal = this.parseGoal(goal);

    // ── PLANNING STEP: get app context and produce ordered plan ──────
    let currentAppPackage = '';
    try {
      currentAppPackage = await AppController.getActivePackage() || appHint || '';
    } catch {
      currentAppPackage = appHint || '';
    }

    let plan: string[] = [];
    if (this.allowLLMFallback && !this.skipPlanning) {
      plan = await this.planSteps(goal, observation, currentAppPackage);
    }
    let currentPlanStep = 0;
    let stuckOnPlanStep = 0;

    let stuckCount = 0;
    let lastTreePrefix = '';
    let deterministicFailCount = 0;

    let wrongAppBackAttempts = 0;

    for (let iteration = 1; iteration <= this.maxIterations; iteration++) {
      // ── CANCELLATION CHECK ──────────────────────────────────────────
      if (this._cancelled) {
        console.warn(`[REACT] CANCELLED at iteration ${iteration}`);
        DebugLog.systemEvent('ReActLoop', `CANCELLED by user at iteration ${iteration}`);
        return { success: false, steps, finalObservation: observation, goalAchieved: false, error: 'cancelled' };
      }

      try {
        const currentPkg = await AppController.getActivePackage();
        // Skip self-check only for iteration 1 when we have an appHint —
        // moveTaskToBack needs a moment to complete
        const skipSelfCheck = iteration === 1 && !!appHint;
        if (!skipSelfCheck && currentPkg === 'com.agent.ultra') {
          DebugLog.error('ReActLoop', `SAFETY STOP at iter ${iteration}: foreground package is Agent Ultra — aborting to prevent self-interaction`);
          return { success: false, steps, finalObservation: 'ReActLoop detected self-interaction — stopped for safety', goalAchieved: false, error: 'self_interaction' };
        }

        // Wrong-app detection: if we're on a different app than expected, try to recover
        const OVERLAY_PKGS = ['com.android.systemui', 'com.samsung.android.honeyboard',
          'com.samsung.android.smartcapture', 'com.samsung.android.app.smartcapture'];
        // Samsung settings uses a separate package for search — treat as same app
        const isSameApp = currentPkg === expectedPkg
          || (expectedPkg === 'com.android.settings' && currentPkg?.startsWith('com.android.settings'))
          || (expectedPkg?.startsWith('com.android.settings') && currentPkg === 'com.android.settings');
        if (expectedPkg && currentPkg && !isSameApp
            && currentPkg !== 'com.agent.ultra' && !OVERLAY_PKGS.includes(currentPkg)) {
          wrongAppBackAttempts++;
          console.warn(`[REACT] WRONG_APP iter=${iteration}: expected=${expectedPkg} got=${currentPkg} attempt=${wrongAppBackAttempts}`);
          DebugLog.error('ReActLoop', `WRONG APP at iter ${iteration}: expected=${expectedPkg} got=${currentPkg} (attempt ${wrongAppBackAttempts})`);
          if (wrongAppBackAttempts >= 3) {
            return { success: false, steps, finalObservation: `Wrong app: expected ${expectedPkg} but stuck on ${currentPkg}`, goalAchieved: false, error: 'wrong_app' };
          }
          // Re-launch expected app instead of just pressing Back
          try {
            const AgentNativeRelaunch = (await import('../native/AgentNative')).default;
            console.warn(`[REACT] re-launching ${expectedPkg}`);
            await AgentNativeRelaunch.launchApp(expectedPkg);
            await this.sleep(1500);
          } catch {
            await AppController.performBack();
            await this.sleep(800);
          }
          continue;
        } else if (expectedPkg && isSameApp) {
          wrongAppBackAttempts = 0; // reset on correct app
        }

        if (currentPkg) await AppController.allowPackage(currentPkg);
      } catch (e: any) {
        DebugLog.error('ReActLoop', `Safety check failed at iter ${iteration}: ${e?.message}`);
      }

      // Refresh app context each iteration
      const _refreshStart = Date.now();
      try {
        currentAppPackage = await AppController.getActivePackage() || currentAppPackage;
      } catch { /* keep previous */ }

      const _nodesStart = Date.now();
      const nodes = await this.getNodes();
      console.warn(`[REACT_TIMING] iter=${iteration} refresh=${_nodesStart - _refreshStart}ms getNodes=${Date.now() - _nodesStart}ms nodes=${nodes.length}`);

      // Danger zone detection — stop if we're on accessibility settings (could disable our own service)
      if (currentAppPackage === 'com.android.settings') {
        const a11yNode = nodes.find(n => {
          const label = (n.t || n.d || '').trim().toLowerCase();
          return label.includes('accessibility') && (label.includes('agent ultra') || label.includes('installed apps') || label.includes('vision enhancements'));
        });
        if (a11yNode) {
          console.warn(`[REACT] DANGER_ZONE: on accessibility settings — aborting`);
          await AppController.performBack();
          return { success: false, steps, finalObservation: 'Stopped — navigating accessibility settings could disable the agent.', goalAchieved: false, error: 'danger_zone' };
        }
      }

      // Auth/blocker detection — stop if we hit a login wall
      // Exclude dismissable suggestions like "Sign in to save your searches" (Maps, Chrome)
      const AUTH_PATTERNS = /\b(sign.?in|log.?in|password|captcha|verify your|enter.?code|two.?factor|2fa|create.?account|register now)\b/i;
      const AUTH_EXCEPTIONS = /\b(sign in to save|sign in to sync|sign in for a better|sign in to get suggestions)\b/i;
      const authNode = nodes.find(n => {
        const label = (n.t || n.d || '').trim();
        if (label.length <= 2 || label.length >= 60) return false;
        if (!AUTH_PATTERNS.test(label)) return false;
        if (AUTH_EXCEPTIONS.test(label)) return false;
        return true;
      });
      if (authNode && iteration > 1) {
        const authLabel = (authNode.t || authNode.d || '').trim();
        console.warn(`[REACT] AUTH_WALL detected: "${authLabel}" at iter ${iteration}`);
        return { success: false, steps, finalObservation: `Authentication required: screen shows "${authLabel}". User needs to sign in.`, goalAchieved: false, error: 'auth_required' };
      }

      const deterministicAction = nodes.length > 0 ? this.executeDeterministic(parsedGoal, nodes, steps) : null;

      if (deterministicAction) {
        DebugLog.systemEvent('ReActLoop', `STEP ${iteration}: DETERMINISTIC action="${deterministicAction}"`);
        if (/^done$/i.test(deterministicAction.trim())) {
          steps.push({ iteration, observation, reasoning: 'deterministic:done', action: deterministicAction, actionResult: true, uiChanged: false });
          // Verify we're on the correct app before declaring success
          if (expectedPkg) {
            const donePkg = await AppController.getActivePackage().catch(() => '');
            if (donePkg === 'com.agent.ultra' || (donePkg && donePkg !== expectedPkg)) {
              DebugLog.error('ReActLoop', `DONE rejected: on ${donePkg}, expected ${expectedPkg}`);
              return { success: false, steps, finalObservation: `Goal declared done but wrong app active (${donePkg})`, goalAchieved: false, error: 'wrong_app_at_done' };
            }
          }
          return { success: true, steps, finalObservation: observation, goalAchieved: true };
        }

        const beforeObs = observation;
        const actionResult = await this.executeAction(deterministicAction);
        await this.waitAfterAction();
        const newObservation = await this.observe();
        const uiChanged = newObservation !== beforeObs;
        steps.push({ iteration, observation, reasoning: 'deterministic', action: deterministicAction, actionResult, uiChanged });
        DebugLog.systemEvent('ReActLoop', `STEP ${iteration}: det="${deterministicAction}" result=${actionResult} uiChanged=${uiChanged}`);

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

        // Advance plan step on successful UI change
        if (uiChanged && plan.length > 0 && currentPlanStep < plan.length) {
          currentPlanStep++;
          stuckOnPlanStep = 0;
          DebugLog.systemEvent('ReActLoop', `PLAN: advanced to step ${currentPlanStep + 1}/${plan.length}`);
        } else if (plan.length > 0) {
          stuckOnPlanStep++;
          if (stuckOnPlanStep >= 2) {
            currentPlanStep++;
            stuckOnPlanStep = 0;
            DebugLog.systemEvent('ReActLoop', `PLAN: forced advance past stuck step to ${currentPlanStep + 1}/${plan.length}`);
          }
        }

        continue;
      }

      deterministicFailCount++;

      // Auto-scroll after 2 deterministic failures — target element may be below the fold
      if (deterministicFailCount === 2 && nodes.length > 5) {
        console.warn(`[REACT] auto_scroll: deterministic failed ${deterministicFailCount}x, scrolling down to find target`);
        await AppController.performScroll('down');
        await this.sleep(600);
        observation = await this.observe();
        steps.push({ iteration, observation, reasoning: 'auto_scroll', action: 'scroll(down)', actionResult: true, uiChanged: true });
        continue;
      }

      if (!this.allowLLMFallback) {
        DebugLog.systemEvent('ReActLoop', `STEP ${iteration}: deterministic exhausted with no LLM fallback`);
        break;
      }

      const _iterStart = Date.now();
      DebugLog.systemEvent('ReActLoop', `STEP ${iteration}: Falling back to LLM (deterministic failed ${deterministicFailCount}x)`);

      // Sparse screen (1-3 nodes) = likely a dialog/overlay/tooltip blocking the real UI
      // Dismiss it with Back instead of calling the expensive vision API
      if (nodes.length <= 3 && nodes.length > 0) {
        console.warn(`[REACT] sparse_screen nodes=${nodes.length} — dismissing overlay with Back`);
        DebugLog.systemEvent('ReActLoop', `STEP ${iteration}: sparse screen (${nodes.length} nodes), dismissing overlay`);
        await AppController.performBack();
        await this.sleep(800);
        observation = await this.observe();
        const refreshedNodes = await this.getNodes();
        console.warn(`[REACT] after_dismiss nodes=${refreshedNodes.length}`);
        if (refreshedNodes.length > 3) {
          // Overlay dismissed, continue with refreshed observation
          steps.push({ iteration, observation, reasoning: 'dismiss_overlay', action: 'back()', actionResult: true, uiChanged: true });
          continue;
        }
      }

      let enhancedObservation = observation;

      const planContext = plan.length > 0 && currentPlanStep < plan.length
        ? `\nCURRENT STEP (${currentPlanStep + 1}/${plan.length}): ${plan[currentPlanStep]}`
        : '';
      const remainingPlan = plan.length > 0 && currentPlanStep < plan.length
        ? `\nFULL PLAN:\n${plan.map((s, i) => `  ${i < currentPlanStep ? '✓' : i === currentPlanStep ? '→' : ' '} ${i + 1}. ${s}`).join('\n')}`
        : '';

      const systemPrompt = `You control an Android phone. You are inside: ${currentAppPackage || 'unknown'}.
Choose ONE action to make progress toward the goal.

ACTIONS:
- tap(N)         tap element by index number — ALWAYS use this, never guess coordinates
- type("text")   type into focused field (auto-submits)
- submit()       press Enter/Go/Search on keyboard
- scroll(down)   scroll down to reveal more content
- scroll(up)     scroll up
- back()         go back to previous screen
- done           goal complete — ONLY after verifying the screen shows the expected result

CRITICAL:
- Do NOT tap back() unless the current screen is clearly wrong for the goal.
- Do NOT type into a field that already contains the correct text.
- Only tap elements shown in the SCREEN list below. Off-screen elements are not available.

Respond: ACTION: <action> // <one-word reason>`;

      // Build step history for context (last 4 steps)
      const recentSteps = steps.slice(-4).map(s =>
        `  ${s.action} → ${s.uiChanged ? 'screen changed' : s.actionResult ? 'no visual change' : 'FAILED'}`
      ).join('\n');
      const historyLine = recentSteps ? `\nRECENT ACTIONS:\n${recentSteps}\n` : '';

      const userMessage = `GOAL: ${goal}${planContext}${remainingPlan}${historyLine}\n\nSCREEN:\n${enhancedObservation.slice(0, 1500)}\n\nACTION:`;
      const fullPrompt = `${systemPrompt}\n\n${userMessage}`;
      console.warn(`[REACT_TIMING] prompt_chars=${fullPrompt.length} est_tokens=${Math.ceil(fullPrompt.length / 4)} nodes=${nodes.length}`);
      let reasoning: string;
      try {
        const _aiStart = Date.now();
        reasoning = await this.aiCall(fullPrompt);
        console.warn(`[REACT_TIMING] aiCall: ${Date.now() - _aiStart}ms response_len=${reasoning.length}`);
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
        // Verify we're on the correct app before declaring success
        if (expectedPkg) {
          const donePkg = await AppController.getActivePackage().catch(() => '');
          if (donePkg === 'com.agent.ultra' || (donePkg && donePkg !== expectedPkg)) {
            DebugLog.error('ReActLoop', `DONE rejected: on ${donePkg}, expected ${expectedPkg}`);
            return { success: false, steps, finalObservation: `Goal declared done but wrong app active (${donePkg})`, goalAchieved: false, error: 'wrong_app_at_done' };
          }
        }
        return { success: true, steps, finalObservation: observation, goalAchieved: true };
      }

      const beforeObservation = observation;
      const actionResult = await this.executeAction(action);
      await this.waitAfterAction();
      const newObservation = await this.observe();
      const uiChanged = newObservation !== beforeObservation;

      steps.push({ iteration, observation, reasoning, action, actionResult, uiChanged });
      DebugLog.systemEvent('ReActLoop', `STEP ${iteration}: "${action}" result=${actionResult} uiChanged=${uiChanged}`);

      // Advance plan step on successful UI change
      if (uiChanged && plan.length > 0 && currentPlanStep < plan.length) {
        currentPlanStep++;
        stuckOnPlanStep = 0;
        DebugLog.systemEvent('ReActLoop', `PLAN: advanced to step ${currentPlanStep + 1}/${plan.length}`);
      } else if (plan.length > 0) {
        stuckOnPlanStep++;
        if (stuckOnPlanStep >= 2) {
          currentPlanStep++;
          stuckOnPlanStep = 0;
          DebugLog.systemEvent('ReActLoop', `PLAN: forced advance past stuck step to ${currentPlanStep + 1}/${plan.length}`);
        }
      }

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

    let goalAchieved = await this.checkCompletion(parsedGoal, observation);
    // Reject goalAchieved if we're on the wrong app
    if (goalAchieved && expectedPkg) {
      const finalPkg = await AppController.getActivePackage().catch(() => '');
      if (finalPkg === 'com.agent.ultra' || (finalPkg && finalPkg !== expectedPkg)) {
        DebugLog.error('ReActLoop', `goalAchieved overridden: on ${finalPkg}, expected ${expectedPkg}`);
        goalAchieved = false;
      }
    }
    DebugLog.systemEvent('ReActLoop', `COMPLETE goalAchieved=${goalAchieved} steps=${steps.length}`);
    return { success: goalAchieved, steps, finalObservation: observation, goalAchieved };
  }

  private async observe(): Promise<string> {
    try {
      const flat = await getScreenContentFlat();
      const nodes = JSON.parse(flat) as FlatNode[];
      if (!Array.isArray(nodes) || nodes.length === 0) return 'Screen: empty or inaccessible';

      // P7: Get screen dimensions for visible-only filtering
      let screenHeight = 2400; // reasonable default
      try {
        const { Dimensions } = require('react-native');
        const { height } = Dimensions.get('screen');
        if (height > 0) screenHeight = height;
      } catch {}

      // Only show interactive, VISIBLE nodes — filter off-screen elements
      const tappable: string[] = [];
      const typeable: string[] = [];
      const scrollable: string[] = [];

      for (const n of nodes) {
        const label = (n.t || n.d || '').trim().slice(0, 50);
        if (!label) continue; // skip blank nodes
        // P7: Visible-only filter — skip elements above or below the screen
        if (typeof n.y === 'number' && (n.y < 0 || n.y > screenHeight)) continue;

        // P7: Enrich labels with type hints for better LLM decisions
        let typeHint = '';
        const lLower = label.toLowerCase();
        if (n.e) {
          typeHint = ' (input)';
        } else if (n.c) {
          if (/\b(ok|cancel|done|save|submit|send|close|accept|deny|allow|skip|next|back|yes|no)\b/i.test(lLower)) typeHint = ' (button)';
          else if (/\b(settings|account|about|privacy|security|general|display|sound|battery)\b/i.test(lLower)) typeHint = ' (menu-item)';
          else if (n.s) typeHint = ' (tab)';
        }

        if (n.e) typeable.push(`  [${n.i}] ${label}${typeHint}`);
        else if (n.c) tappable.push(`  [${n.i}] ${label}${typeHint}`);
        else if (n.s && !tappable.length) scrollable.push(`  [${n.i}] ${label}`);
      }

      const parts: string[] = [];
      if (tappable.length) parts.push(`TAPPABLE:\n${tappable.slice(0, 20).join('\n')}`);
      if (typeable.length) parts.push(`TYPEABLE:\n${typeable.slice(0, 5).join('\n')}`);
      if (scrollable.length) parts.push(`SCROLLABLE:\n${scrollable.slice(0, 3).join('\n')}`);
      if (!parts.length) parts.push('Screen has no interactive elements — try scroll(down) or back()');

      return parts.join('\n\n');
    } catch {
      try {
        const tree = await AppController.getScreenContent();
        const serialized = typeof (tree as any) === 'string' ? String(tree) : JSON.stringify(tree);
        return serialized.slice(0, 1500);
      } catch {
        return 'Screen: observation failed';
      }
    }
  }

  private extractAction(text: string): string | null {
    const t = text.trim();

    // 1. Strict prefix match: "ACTION: tap(5) // reason" — strip comment
    const strict = t.match(/^ACTION:\s*(.+)$/im);
    if (strict) return strict[1].replace(/\s*\/\/.*$/, '').trim();

    // 2. Bare action on its own line or as the whole response
    const bare = t.match(/^(tap_index\(\s*\d+\s*\)|tap\(\s*\d+(?:\s*,\s*\d+)?\s*\)|type\(["']?[^)]+["']?\)|scroll\((?:up|down|forward|backward)\)|swipe\(\s*\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*\d+\s*\)|back\(\)|home\(\)|done)$/i);
    if (bare) return bare[1].trim();

    // 3. Action embedded anywhere in text — extract first match
    const patterns = [
      /\b(tap_index\(\s*\d+\s*\))/i,
      /\b(tap\(\s*\d+\s*,\s*\d+\s*\))/i,
      /\b(tap\(\s*\d+\s*\))/i,             // single-arg tap = tap by index
      /\b(type\(["']?[^)]{1,100}["']?\))/i,
      /\b(scroll\((?:up|down|forward|backward)\))/i,
      /\b(swipe\(\s*\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*\d+\s*\))/i,
      /\b(back\(\))/i,
      /\b(home\(\))/i,
    ];
    for (const p of patterns) {
      const m = t.match(p);
      if (m) return m[1].trim();
    }

    // 4. Keyword fallback for simple prose responses
    const lower = t.toLowerCase();
    if (/\bdone\b/.test(lower) && t.length < 60) return 'done';
    if (/\bscroll down\b/.test(lower)) return 'scroll(down)';
    if (/\bscroll up\b/.test(lower)) return 'scroll(up)';
    if (/\bgo back\b|\bpress back\b/.test(lower)) return 'back()';

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
      if (tapMatch) {
        // LLM guessed raw coordinates — snap to nearest node center for accuracy
        const rawX = parseInt(tapMatch[1], 10);
        const rawY = parseInt(tapMatch[2], 10);
        try {
          const flat = await getScreenContentFlat();
          const nodes = JSON.parse(flat) as FlatNode[];
          if (Array.isArray(nodes) && nodes.length > 0) {
            let closest: FlatNode | null = null;
            let closestDist = Infinity;
            for (const n of nodes) {
              if (!n.c && !n.e) continue; // only interactive nodes
              const dist = Math.sqrt((n.x - rawX) ** 2 + (n.y - rawY) ** 2);
              if (dist < closestDist) { closestDist = dist; closest = n; }
            }
            if (closest && closestDist < 150) {
              DebugLog.systemEvent('ReActLoop', `TAP SNAP: (${rawX},${rawY}) → node[${closest.i}] at (${closest.x},${closest.y}) dist=${Math.round(closestDist)}`);
              return await performTap(closest.x, closest.y);
            }
          }
        } catch {}
        // Fallback to raw coordinates if no nearby node found
        return await performTap(rawX, rawY);
      }

      // tap(N) with single arg = tap by node index (alias for tap_index(N))
      const tapSingleMatch = a.match(/^tap\(\s*(\d+)\s*\)$/i);
      if (tapSingleMatch) {
        const idx = parseInt(tapSingleMatch[1], 10);
        const flat = await getScreenContentFlat();
        const nodes = JSON.parse(flat) as FlatNode[];
        const node = Array.isArray(nodes) ? nodes.find((n) => n.i === idx) : null;
        if (node) return await performTap(node.x, node.y);
        return false;
      }

      const tapIdxMatch = a.match(/^tap_index\(\s*(\d+)\s*\)$/i);
      if (tapIdxMatch) {
        const idx = parseInt(tapIdxMatch[1], 10);
        const flat = await getScreenContentFlat();
        const nodes = JSON.parse(flat) as FlatNode[];
        const node = Array.isArray(nodes) ? nodes.find((n) => n.i === idx) : null;
        if (node) return await performTap(node.x, node.y);
        return false;
      }

      if (/^submit\(\)$/i.test(a) || /^enter\(\)$/i.test(a)) {
        return await AppController.performImeAction();
      }

      const typeMatch = a.match(/^type\(\s*["']?(.+?)["']?\s*\)$/i);
      if (typeMatch) {
        const typed = await AppController.performText('', typeMatch[1]);
        console.warn(`[REACT] type result=${typed} text="${typeMatch[1].slice(0, 30)}"`);
        if (typed) {
          // Brief pause to let keyboard process input before pressing Enter
          await new Promise(r => setTimeout(r, 200));
          try {
            const imeResult = await AppController.performImeAction();
            console.warn(`[REACT] auto_ime_enter result=${imeResult}`);
            if (!imeResult) {
              // Retry once after longer pause — some keyboards are slow
              await new Promise(r => setTimeout(r, 500));
              const retryResult = await AppController.performImeAction();
              console.warn(`[REACT] auto_ime_enter retry result=${retryResult}`);
            }
          } catch (imeErr: any) {
            console.warn(`[REACT] auto_ime_enter error: ${imeErr.message}`);
          }
        }
        return typed;
      }

      const swipeMatch = a.match(/^swipe\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)$/i);
      if (swipeMatch) {
        return await performSwipe(
          parseInt(swipeMatch[1], 10),
          parseInt(swipeMatch[2], 10),
          parseInt(swipeMatch[3], 10),
          parseInt(swipeMatch[4], 10),
          350,
        );
      }

      DebugLog.error('ReActLoop', `Unknown action: ${a}`);
      return false;
    } catch (err: any) {
      DebugLog.error('ReActLoop', `Action error: ${err.message}`);
      return false;
    }
  }

  private async checkCompletion(parsedGoal: ParsedGoal, observation: string): Promise<boolean> {
    if (!this.allowLLMFallback) {
      const nodes = await this.getNodes();
      return this.heuristicCompletion(parsedGoal, nodes, observation);
    }

    try {
      const prompt = `Goal: "${parsedGoal.action} ${parsedGoal.target} ${parsedGoal.value}"\nCurrent screen:\n${observation.slice(0, 800)}\n\nIs the goal fully achieved? Reply YES or NO only.`;
      const response = await this.aiCall(prompt);
      return /^yes/i.test(response.trim());
    } catch {
      const nodes = await this.getNodes();
      return this.heuristicCompletion(parsedGoal, nodes, observation);
    }
  }

  private heuristicCompletion(parsedGoal: ParsedGoal, nodes: FlatNode[], observation: string): boolean {
    if (parsedGoal.action === 'search') {
      const queryVisible = this.isQueryVisible(nodes, parsedGoal.value || parsedGoal.target);
      const likelyResultsVisible = this.looksLikeSearchResults(nodes, parsedGoal.value || parsedGoal.target);
      return queryVisible && (likelyResultsVisible || observation.toLowerCase().includes((parsedGoal.value || parsedGoal.target).toLowerCase()));
    }
    if (parsedGoal.action === 'tap') {
      return !!this.findNodeByText(nodes, parsedGoal.target, { clickableOnly: false, preferNonEditable: false });
    }
    return false;
  }

  private async waitAfterAction(): Promise<void> {
    try {
      await waitForUiChange(Math.min(1800, this.iterationDelayMs + 400));
    } catch {
      // ignore and rely on fixed delay
    }
    await this.sleep(Math.max(250, Math.min(900, this.iterationDelayMs)));
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  private async getNodes(): Promise<FlatNode[]> {
    try {
      const flat = await getScreenContentFlat();
      const parsed = JSON.parse(flat) as FlatNode[];
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }

  private parseGoal(goal: string): ParsedGoal {
    const raw = goal.trim();
    const g = raw.toLowerCase();

    // "search for X" / "find X" / "look up X" / "google X"
    const searchMatch = raw.match(/(?:search|find|look\s*up|browse|google)\s+(?:for\s+)?["']?(.+?)["']?$/i);
    if (searchMatch) {
      return { action: 'search', target: 'search_field', value: searchMatch[1].trim() };
    }

    // "tap X" / "click X" / "press X"
    const tapMatch = raw.match(/^(?:tap|click|press|select|choose)\s+(?:the\s+)?["']?(.+?)["']?(?:\s+button)?$/i);
    if (tapMatch) {
      return { action: 'tap', target: tapMatch[1].trim(), value: '' };
    }

    // "type X" / "enter X into Y"
    const typeMatch = raw.match(/^(?:type|enter|input|fill\s+in)\s+["']?(.+?)["']?(?:\s+(?:in|into|to)\s+(.+))?$/i);
    if (typeMatch) {
      return { action: 'type', target: typeMatch[2]?.trim() || 'input_field', value: typeMatch[1].trim() };
    }

    // "scroll up/down"
    const scrollMatch = raw.match(/^scroll\s+(up|down)$/i);
    if (scrollMatch) {
      return { action: 'scroll', target: scrollMatch[1].toLowerCase(), value: '' };
    }

    // If goal contains "search" anywhere, treat as search
    const implicitSearch = raw.match(/^.*?\b(?:search|look up|find)\b.*?["'](.+?)["'].*$/i)
      || raw.match(/^.*?\b(?:search|look up|find)\b\s+(?:for\s+)?(.+)$/i);
    if (implicitSearch) {
      return { action: 'search', target: 'search_field', value: implicitSearch[1].trim() };
    }

    return { action: 'tap', target: g.slice(0, 40), value: '' };
  }

  private normalizeText(value: string): string {
    return value.toLowerCase().replace(/[^a-z0-9]+/g, ' ').replace(/\s+/g, ' ').trim();
  }

  private escapeForAction(value: string): string {
    return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  }

  private scoreTextMatch(label: string, target: string): number {
    const normalizedLabel = this.normalizeText(label);
    const normalizedTarget = this.normalizeText(target);
    if (!normalizedLabel || !normalizedTarget) return 0;
    if (normalizedLabel === normalizedTarget) return 100;
    if (normalizedLabel.startsWith(normalizedTarget)) return 85;
    if (normalizedLabel.includes(normalizedTarget)) return 75;

    let score = 0;
    const targetWords = normalizedTarget.split(' ').filter((word) => word.length > 2);
    for (const word of targetWords) {
      if (normalizedLabel === word) score += 30;
      else if (normalizedLabel.startsWith(word)) score += 22;
      else if (normalizedLabel.includes(word)) score += 16;
    }
    return score;
  }

  private findNodeByText(nodes: FlatNode[], text: string, options: TextMatchOptions = {}): FlatNode | null {
    const matches = nodes
      .filter((node) => !options.clickableOnly || node.c)
      .map((node) => {
        const label = `${node.t || ''} ${node.d || ''}`.trim();
        let score = this.scoreTextMatch(label, text);
        if (options.preferNonEditable && node.e) score -= 15;
        if (!node.c && options.clickableOnly) score = 0;
        return { node, score };
      })
      .filter((entry) => entry.score > 0)
      .sort((a, b) => b.score - a.score || Number(a.node.e) - Number(b.node.e));

    return matches.length > 0 ? matches[0].node : null;
  }

  private findSearchField(nodes: FlatNode[]): FlatNode | null {
    // First try editable fields (focused text inputs)
    const editable = nodes.filter((node) => node.e);
    if (editable.length > 0) {
      const scored = editable
        .map((node) => {
          const label = `${node.t || ''} ${node.d || ''}`.trim();
          let score = SEARCH_FIELD_HINT.test(label) ? 100 : 10;
          if (node.c) score += 5;
          return { node, score };
        })
        .sort((a, b) => b.score - a.score);
      return scored[0]?.node ?? null;
    }

    // Fallback: clickable nodes with search-like labels (e.g. Chrome's "Search Google or type URL")
    const searchClickable = nodes
      .filter((node) => node.c && SEARCH_FIELD_HINT.test(`${node.t || ''} ${node.d || ''}`))
      .map((node) => {
        const label = `${node.t || ''} ${node.d || ''}`.trim();
        const score = /url|address|omnibox/i.test(label) ? 110 : 100;
        return { node, score };
      })
      .sort((a, b) => b.score - a.score);
    return searchClickable.length > 0 ? searchClickable[0].node : null;
  }

  private findSearchSubmit(nodes: FlatNode[]): FlatNode | null {
    const candidates = nodes
      .filter((node) => node.c && !node.e)
      .map((node) => {
        const label = `${node.t || ''} ${node.d || ''}`.trim();
        let score = SEARCH_BUTTON_HINT.test(this.normalizeText(label)) ? 100 : 0;
        if (!score && /search/i.test(label)) score = 85;
        if (!score && /go|submit|done|apply|enter/i.test(label)) score = 75;
        return { node, score };
      })
      .filter((entry) => entry.score > 0)
      .sort((a, b) => b.score - a.score);

    return candidates[0]?.node ?? null;
  }

  private isQueryVisible(nodes: FlatNode[], query: string): boolean {
    const normalizedQuery = this.normalizeText(query);
    if (!normalizedQuery) return false;
    return nodes.some((node) => this.scoreTextMatch(`${node.t || ''} ${node.d || ''}`.trim(), normalizedQuery) >= 60);
  }

  private looksLikeSearchResults(nodes: FlatNode[], query: string): boolean {
    const normalizedQuery = this.normalizeText(query);
    let nonInputMatches = 0;
    for (const node of nodes) {
      if (node.e) continue;
      const label = `${node.t || ''} ${node.d || ''}`.trim();
      const normalizedLabel = this.normalizeText(label);
      if (!normalizedLabel) continue;
      if (normalizedLabel.includes(normalizedQuery) || RESULT_TEXT_HINT.test(label)) {
        nonInputMatches++;
      }
      if (nonInputMatches >= 2) return true;
    }
    return false;
  }

  private executeDeterministic(parsed: ParsedGoal, nodes: FlatNode[], steps: ReActStep[]): string | null {
    const lastAction = steps.length > 0 ? steps[steps.length - 1].action.trim().toLowerCase() : '';
    const lastUiChanged = steps.length > 0 ? steps[steps.length - 1].uiChanged : false;

    // ── PATTERN 1: Dialog/popup dismissal ──────────────────────────────
    // Only on small screens (≤10 nodes) after the first iteration — likely a blocking dialog
    const dismissPatterns = /^(ok|got it|accept|allow|continue|not now|skip|no thanks|dismiss|close|maybe later|i agree)$/i;
    if (steps.length > 0 && nodes.length <= 10 && nodes.length >= 2) {
      const dialogButton = nodes.find(n => n.c && dismissPatterns.test((n.t || n.d || '').trim()));
      if (dialogButton) {
        console.warn(`[REACT_DET] dismiss_dialog: tapping "${(dialogButton.t || dialogButton.d || '').trim()}" node=${dialogButton.i}`);
        return `tap_index(${dialogButton.i})`;
      }
    }

    // ── PATTERN 2: Search task (parsed or inferred) ────────────────────
    if (parsed.action === 'search') {
      const query = parsed.value || parsed.target;
      const queryVisible = this.isQueryVisible(nodes, query);
      const resultsVisible = this.looksLikeSearchResults(nodes, query);
      const searchField = this.findSearchField(nodes);
      const searchSubmit = this.findSearchSubmit(nodes);

      // Goal already achieved — query visible + results showing
      if (queryVisible && resultsVisible) return 'done';

      // Just typed → auto-submit handles Enter, check for results
      if (lastAction.startsWith('type(')) {
        if (queryVisible && resultsVisible) return 'done';
        if (searchSubmit) return `tap_index(${searchSubmit.i})`;
        return null; // let LLM decide or wait for UI to update
      }

      // Just tapped a field → type the query
      if (lastAction.startsWith('tap_index(') || lastAction.startsWith('tap(')) {
        if (!queryVisible) return `type("${this.escapeForAction(query)}")`;
      }

      // Search field visible → tap it
      if (searchField && !queryVisible) {
        return `tap_index(${searchField.i})`;
      }

      if (searchSubmit && queryVisible) return `tap_index(${searchSubmit.i})`;
      if (queryVisible) return 'done';
      return null;
    }

    // ── PATTERN 3: Toggle task (goal mentions on/off/enable/disable/toggle) ──
    const goalLower = `${parsed.target} ${parsed.value}`.toLowerCase();
    const toggleMatch = goalLower.match(/(?:turn|switch|toggle|enable|disable)\s+(?:on|off)?\s*(.+?)(?:\s+(?:on|off))?$/);
    if (toggleMatch) {
      const toggleTarget = toggleMatch[1].trim();
      // Find a switch/toggle node matching the target
      const switchNode = nodes.find(n => {
        const label = `${n.t || ''} ${n.d || ''}`.toLowerCase();
        return n.c && label.includes(toggleTarget);
      });
      if (switchNode) {
        console.warn(`[REACT_DET] toggle: tapping "${(switchNode.t || switchNode.d || '').trim()}" node=${switchNode.i}`);
        return `tap_index(${switchNode.i})`;
      }
    }

    // ── PATTERN 4: Direct tap target ───────────────────────────────────
    if (parsed.action === 'tap') {
      const node = this.findNodeByText(nodes, parsed.target, { clickableOnly: true, preferNonEditable: true });
      if (node) return `tap_index(${node.i})`;

      // Partial match: try individual words from the target
      const words = parsed.target.split(/\s+/).filter(w => w.length > 3);
      for (const word of words) {
        const partial = this.findNodeByText(nodes, word, { clickableOnly: true, preferNonEditable: true });
        if (partial) {
          console.warn(`[REACT_DET] partial_tap: "${word}" → node=${partial.i} "${(partial.t || partial.d || '').trim()}"`);
          return `tap_index(${partial.i})`;
        }
      }
      return null;
    }

    // ── PATTERN 5: Type task ───────────────────────────────────────────
    if (parsed.action === 'type') {
      if (lastAction.startsWith('tap_index(') || lastAction.startsWith('tap(')) {
        return `type("${this.escapeForAction(parsed.value)}")`;
      }
      const field = parsed.target !== 'input_field'
        ? this.findNodeByText(nodes, parsed.target, { clickableOnly: true, preferNonEditable: false }) || this.findSearchField(nodes)
        : this.findSearchField(nodes);
      if (field) return `tap_index(${field.i})`;
      return null;
    }

    // ── PATTERN 6: Scroll ──────────────────────────────────────────────
    if (parsed.action === 'scroll') {
      return `scroll(${parsed.target})`;
    }

    // ── PATTERN 7: Goal text matches a visible clickable element ───────
    // Even if parseGoal fell through to 'tap', try matching goal words against nodes
    const goalWords = goalLower.split(/\s+/).filter(w => w.length > 3);
    for (const word of goalWords) {
      const match = nodes.find(n => {
        if (!n.c) return false;
        const label = `${n.t || ''} ${n.d || ''}`.toLowerCase();
        return label.includes(word) && !/(systemui|status|battery|clock|wifi|signal)/i.test(label);
      });
      if (match) {
        console.warn(`[REACT_DET] goal_word_match: "${word}" → node=${match.i} "${(match.t || match.d || '').trim()}"`);
        return `tap_index(${match.i})`;
      }
    }

    return null;
  }
}
