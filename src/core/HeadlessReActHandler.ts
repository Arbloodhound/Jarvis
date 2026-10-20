import { AppRegistry, Platform, DeviceEventEmitter } from 'react-native';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

export interface HeadlessReActResult {
  taskId: string;
  success: boolean;
  steps: number;
  goal: string;
  finalObservation?: string;
  screenContent?: string;
  error?: string;
}

const headlessReActHandler = async (taskData: any) => {
  const { taskType, goal, appHint, taskId } = taskData || {};
  console.warn('[HEADLESS] received:', JSON.stringify({ taskType, goal, appHint, taskId }));
  DebugLog.push('HEADLESS_TASK' as any, { event: 'received', taskType, goal, appHint, taskId });

  if (taskType !== 'react_navigate' || !goal) {
    console.warn('[HEADLESS] skip: not react_navigate or no goal');
    DebugLog.push('HEADLESS_TASK' as any, { event: 'skip', reason: 'not_react_navigate_or_no_goal' });
    DeviceEventEmitter.emit('headlessReActComplete', {
      taskId: taskId || '', success: false, steps: 0, goal: goal || '',
      error: 'not_react_navigate_or_no_goal',
    });
    return;
  }

  try {
    const { getAgentCoreInstance } = await import('./AgentCore');
    const core = getAgentCoreInstance();
    if (!core) {
      console.warn('[HEADLESS] ERROR: no AgentCore instance');
      DebugLog.error('HeadlessReAct', 'No AgentCore instance — app may not be initialized');
      DeviceEventEmitter.emit('headlessReActComplete', {
        taskId, success: false, steps: 0, goal, error: 'no_agent_core',
      });
      return;
    }
    const ai = core.getModelRouter();

    const { default: AppController } = await import('../native/AppController');
    try {
      const currentFg = await AppController.getActivePackage();
      if (currentFg) await AppController.allowPackage(currentFg);
    } catch {}
    await AppController.allowPackage('com.android.systemui');

    console.warn('[HEADLESS] starting ReActLoop for goal:', goal);
    DebugLog.push('HEADLESS_TASK' as any, { event: 'react_loop_start', goal, appHint });

    const { ReActLoop } = await import('./ReActLoop');
    const reactLoop = new ReActLoop(
      async (prompt: string) => {
        const aiResult = await ai.complete(prompt, {
          taskId: taskId || 'headless',
          agentId: 'react',
          systemPrompt: 'You control an Android phone. Respond with ONLY the requested action or format. No explanation.',
          maxTokens: 150,
          temperature: 0.1,
        });
        return aiResult.content;
      },
      { maxIterations: 15, iterationDelayMs: 800, allowLLMFallback: true, skipPlanning: true }
    );

    // Listen for cancellation from the UI stop button
    const cancelSub = DeviceEventEmitter.addListener('cancelReActLoop', () => {
      console.warn('[HEADLESS] cancel event received — stopping ReActLoop');
      reactLoop.cancel();
    });

    const _execStart = Date.now();
    let result;
    try {
      result = await reactLoop.execute(goal, appHint);
    } finally {
      cancelSub.remove();
    }
    const totalMs = Date.now() - _execStart;
    console.warn(`[HEADLESS] complete: goalAchieved=${result.goalAchieved} steps=${result.steps.length} totalMs=${totalMs}`);

    // Cache successful navigation patterns for future use
    if (result.goalAchieved && result.steps.length > 0 && appHint) {
      try {
        const AsyncStorage = require('@react-native-async-storage/async-storage').default;
        const cacheKey = `nav_pattern:${appHint.toLowerCase()}:${goal.slice(0, 50).toLowerCase().replace(/[^a-z0-9]+/g, '_')}`;
        const pattern = {
          app: appHint,
          goal,
          steps: result.steps.map((s: any) => s.action).filter((a: string) => a && a !== 'dismiss_overlay' && a !== 'auto_scroll'),
          totalMs,
          timestamp: Date.now(),
        };
        await AsyncStorage.setItem(cacheKey, JSON.stringify(pattern));
        console.warn(`[HEADLESS] cached navigation pattern: ${cacheKey} (${pattern.steps.length} steps)`);
      } catch {}
    }

    // Read the screen AFTER task completes — the brain needs to see what happened
    // But ONLY if we're still on the target app, not Agent Ultra
    let screenAfter = '';
    try {
      const currentPkg = await AppController.getActivePackage();
      if (currentPkg && currentPkg !== 'com.agent.ultra') {
        const screenFlat = await AppController.getScreenContentFlat();
        const nodes = JSON.parse(screenFlat);
        if (Array.isArray(nodes) && nodes.length > 0) {
          screenAfter = nodes
            .filter((n: any) => (n.t || n.d || '').trim())
            .slice(0, 20)
            .map((n: any) => (n.t || n.d || '').trim())
            .join(' | ');
        }
      } else {
        console.warn('[HEADLESS] skipping screen read — Agent Ultra is foreground');
      }
    } catch {}

    DebugLog.push('HEADLESS_TASK' as any, {
      event: 'complete',
      goalAchieved: result.goalAchieved,
      steps: result.steps.length,
      finalObservation: result.finalObservation?.slice(0, 200),
    });
    DeviceEventEmitter.emit('headlessReActComplete', {
      taskId,
      success: result.goalAchieved,
      steps: result.steps.length,
      goal,
      finalObservation: result.finalObservation?.slice(0, 300),
      screenContent: screenAfter.slice(0, 500),
    } as HeadlessReActResult);
  } catch (err: any) {
    console.warn('[HEADLESS] ERROR:', err.message);
    DebugLog.error('HeadlessReAct', err.message, err.stack);
    DeviceEventEmitter.emit('headlessReActComplete', {
      taskId,
      success: false,
      steps: 0,
      goal,
      error: err.message,
    } as HeadlessReActResult);
  }
};

if (Platform.OS === 'android') {
  AppRegistry.registerHeadlessTask('AgentBackgroundTask', () => headlessReActHandler);
}
