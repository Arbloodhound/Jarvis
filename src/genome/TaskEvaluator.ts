import { Platform } from 'react-native';
import type { AppControllerInterface, UINode } from '../native/AppController';
import type { TaskChallenge, ChallengeResult, ChallengeStep, SuccessCriterion, OverallEvaluation } from './TaskChallenges';
import { UltraDevLog } from '../utils/UltraDevLog';

const isNative = Platform.OS !== 'web';

interface InstallerInterface {
  installApk: (path: string) => Promise<void>;
}

type ProgressCallback = (phase: string, message: string) => void;

function uiNodeToText(node: UINode): string {
  const parts: string[] = [];
  if (node.text) parts.push(node.text);
  if (node.contentDescription) parts.push(node.contentDescription);
  for (const child of node.children) {
    parts.push(uiNodeToText(child));
  }
  return parts.join(' ');
}

export class TaskEvaluator {
  private appController: AppControllerInterface | null = null;
  private installer: InstallerInterface | null = null;

  constructor(
    appController?: AppControllerInterface,
    installer?: InstallerInterface
  ) {
    this.appController = appController || null;
    this.installer = installer || null;
    UltraDevLog.push('SYSTEM', { event: 'task_evaluator_init', hasController: !!appController, hasInstaller: !!installer, platform: Platform.OS });
  }

  async evaluateOffspring(
    packageName: string,
    apkPath: string,
    challenges: TaskChallenge[],
    onProgress?: ProgressCallback
  ): Promise<OverallEvaluation> {
    UltraDevLog.push('SYSTEM', { event: 'task_evaluator_evaluate_start', packageName, apkPath, challengeCount: challenges.length, isNative, hasController: !!this.appController });

    if (!isNative || !this.appController) {
      UltraDevLog.push('SYSTEM', { event: 'task_evaluator_web_fallback', packageName, reason: !isNative ? 'web_platform' : 'no_controller' });
      return this.createWebFallbackResult(challenges);
    }

    onProgress?.('installing', 'Installing offspring APK...');
    try {
      if (this.installer) {
        await this.installer.installApk(apkPath);
        await this.delay(3000);
        UltraDevLog.push('SYSTEM', { event: 'task_evaluator_install_ok', packageName });
      }
    } catch (e) {
      UltraDevLog.push('SYSTEM', { event: 'task_evaluator_install_fail', packageName, error: (e as Error).message });
      return this.createFailedResult(challenges, `Install failed: ${(e as Error).message}`);
    }

    await this.appController.allowPackage(packageName);

    const results: ChallengeResult[] = [];
    let totalCrashes = 0;
    const overallStart = Date.now();

    for (const challenge of challenges) {
      onProgress?.('testing', `Running: ${challenge.name}`);
      UltraDevLog.push('SYSTEM', { event: 'task_evaluator_challenge_start', challengeId: challenge.id, challengeName: challenge.name, difficulty: challenge.difficulty });
      const result = await this.runChallenge(packageName, challenge);
      results.push(result);
      totalCrashes += result.crashCount;
      UltraDevLog.push('SYSTEM', { event: 'task_evaluator_challenge_done', challengeId: challenge.id, passed: result.passed, partialScore: result.partialScore, crashCount: result.crashCount, executionTimeMs: result.executionTimeMs });
    }

    const totalTime = Date.now() - overallStart;
    const passedResults = results.filter(r => r.passed);
    const passRate = results.length > 0 ? passedResults.length / results.length : 0;
    const weightedScore = results.reduce((sum, r) => {
      const challenge = challenges.find(c => c.id === r.challengeId);
      return sum + (r.partialScore * (challenge?.weight ?? 1));
    }, 0) / Math.max(1, challenges.reduce((sum, c) => sum + (c.weight ?? 1), 0));

    const evaluation: OverallEvaluation = {
      results,
      overallPassRate: passRate,
      weightedScore: Math.min(1, weightedScore),
      totalCrashes,
      totalTimeMs: totalTime,
    };

    UltraDevLog.push('SYSTEM', { event: 'task_evaluator_evaluate_done', packageName, challengeCount: challenges.length, passed: passedResults.length, passRate, weightedScore: evaluation.weightedScore, totalCrashes, totalTimeMs: totalTime });
    return evaluation;
  }

  private async runChallenge(packageName: string, challenge: TaskChallenge): Promise<ChallengeResult> {
    if (!this.appController) {
      return this.createSingleFailed(challenge, 'No app controller');
    }
    const start = Date.now();
    let crashCount = 0;
    const screenCaptures: string[] = [];
    const criteriaResults: Array<{ criterion: string; passed: boolean }> = [];

    try {
      for (const step of challenge.steps) {
        await this.executeStep(packageName, step);
        await this.delay(500);
      }

      const screen = await this.appController.getScreenContentFlat();
      for (const criterion of challenge.successCriteria) {
        const passed = await this.checkCriterion(packageName, criterion, screen);
        criteriaResults.push({ criterion: criterion.description, passed });
      }

      const allPassed = criteriaResults.every(r => r.passed);
      const partialScore = criteriaResults.length > 0
        ? criteriaResults.filter(r => r.passed).length / criteriaResults.length
        : 0;

      return {
        challengeId: challenge.id,
        passed: allPassed,
        partialScore,
        executionTimeMs: Date.now() - start,
        crashCount,
        screenCaptures,
        criteriaResults,
      };
    } catch (e: any) {
      const msg = e?.message || 'Unknown error';
      if (msg.toLowerCase().includes('crash') || msg.toLowerCase().includes('exception')) {
        crashCount++;
      }
      return {
        challengeId: challenge.id,
        passed: false,
        partialScore: 0,
        executionTimeMs: Date.now() - start,
        crashCount,
        screenCaptures,
        error: msg,
        criteriaResults,
      };
    }
  }

  private async executeStep(packageName: string, step: ChallengeStep): Promise<void> {
    if (!this.appController) return;
    switch (step.action) {
      case 'launch':
        await this.appController.allowPackage(packageName);
        break;
      case 'click':
        if (step.selector) await this.appController.performClick(step.selector);
        break;
      case 'type':
        if (step.selector && step.text) await this.appController.performText(step.selector, step.text);
        break;
      case 'scroll':
        await this.appController.performScroll(step.direction || 'down');
        break;
      case 'back':
        await this.appController.performBack();
        break;
      case 'home':
        await this.appController.performHome();
        break;
      case 'wait':
        await this.delay(step.waitMs || 1000);
        break;
    }
  }

  private async checkCriterion(
    packageName: string,
    criterion: SuccessCriterion,
    screenText: string
  ): Promise<boolean> {
    switch (criterion.type) {
      case 'screen_contains':
        return criterion.value ? screenText.toLowerCase().includes(criterion.value.toLowerCase()) : false;
      case 'screen_not_contains':
        return criterion.value ? !screenText.toLowerCase().includes(criterion.value.toLowerCase()) : true;
      case 'app_foreground':
        if (!this.appController) return false;
        const active = await this.appController.getActivePackage();
        return active === packageName;
      case 'no_crash':
        return !screenText.toLowerCase().includes('unfortunately') && !screenText.toLowerCase().includes('has stopped');
      default:
        return false;
    }
  }

  private createWebFallbackResult(challenges: TaskChallenge[]): OverallEvaluation {
    const results: ChallengeResult[] = challenges.map(c => ({
      challengeId: c.id,
      passed: false,
      partialScore: 0,
      executionTimeMs: 0,
      crashCount: 0,
      screenCaptures: [],
      error: 'Not available on web',
      criteriaResults: c.successCriteria.map(sc => ({ criterion: sc.description, passed: false })),
    }));
    return { results, overallPassRate: 0, weightedScore: 0, totalCrashes: 0, totalTimeMs: 0 };
  }

  private createFailedResult(challenges: TaskChallenge[], error: string): OverallEvaluation {
    const results: ChallengeResult[] = challenges.map(c => ({
      challengeId: c.id,
      passed: false,
      partialScore: 0,
      executionTimeMs: 0,
      crashCount: 0,
      screenCaptures: [],
      error,
      criteriaResults: c.successCriteria.map(sc => ({ criterion: sc.description, passed: false })),
    }));
    return { results, overallPassRate: 0, weightedScore: 0, totalCrashes: 0, totalTimeMs: 0 };
  }

  private createSingleFailed(challenge: TaskChallenge, error: string): ChallengeResult {
    return {
      challengeId: challenge.id,
      passed: false,
      partialScore: 0,
      executionTimeMs: 0,
      crashCount: 0,
      screenCaptures: [],
      error,
      criteriaResults: challenge.successCriteria.map(sc => ({ criterion: sc.description, passed: false })),
    };
  }

  private delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}
