import type { AppSpec } from '../types/appspec';
import AppController from '../native/AppController';
import AgentNative from '../native/AgentNative';
import { ModelRouter } from './ModelRouter';
import { Logger } from '../utils/Logger';
import { UltraDevLog } from '../utils/UltraDevLog';

export interface TestStep {
  description: string;
  action: 'launch' | 'click' | 'type' | 'scroll' | 'verify' | 'wait' | 'back';
  selector?: string;
  text?: string;
  expectedResult: string;
}

export interface TestStepResult {
  description: string;
  action: string;
  expected: string;
  actual: string;
  passed: boolean;
}

export interface TestResult {
  passed: boolean;
  steps: TestStepResult[];
  summary: string;
}

export interface TestPlan {
  appName: string;
  packageName: string;
  steps: TestStep[];
}

export class TestRunner {
  private modelRouter: ModelRouter;
  private logger: Logger;

  constructor(modelRouter: ModelRouter) {
    this.modelRouter = modelRouter;
    this.logger = new Logger('TestRunner');
  }

  async generateTestPlan(appDescription: string, spec: AppSpec): Promise<TestPlan> {
    this.logger.info(`Generating test plan for ${spec.appName}`);
    UltraDevLog.push('SYSTEM', { event: 'test_runner_generate_plan_start', appName: spec.appName, packageName: spec.packageName });

    const filesContext = spec.files
      .filter(f => f.purpose)
      .map(f => `- ${f.path}: ${f.purpose}`)
      .join('\n');

    const prompt = `Generate a test plan for this Android app:
App: ${spec.appName}
Package: ${spec.packageName}
Description: ${appDescription}
Files:
${filesContext}
Activities: ${spec.activities.map(a => a.className).join(', ')}

Create 3-6 test steps. Each step has:
- description: what we're testing
- action: one of "launch", "click", "type", "scroll", "verify", "wait", "back"
- selector: text/label to find the UI element (for click, type actions)
- text: text to input (for type action)
- expectedResult: what should be visible/true after this step

Respond with ONLY valid JSON:
{
  "steps": [
    {"description":"Launch app","action":"launch","expectedResult":"App opens and main screen is visible"},
    {"description":"Click button","action":"click","selector":"Calculate","expectedResult":"Result appears"}
  ]
}`;

    const result = await this.modelRouter.complete(prompt, {
      taskId: 'test-planner',
      agentId: 'test-runner',
      maxTokens: 2000,
      temperature: 0.3,
    });

    const parsed = this.parsePlan(result.content);
    return {
      appName: spec.appName,
      packageName: spec.packageName,
      steps: parsed,
    };
  }

  async executeTestPlan(plan: TestPlan): Promise<TestResult> {
    this.logger.info(`Executing test plan for ${plan.appName} (${plan.steps.length} steps)`);
    const results: TestStepResult[] = [];

    if (!AppController.isAvailable()) {
      return {
        passed: false,
        steps: [],
        summary: 'Accessibility service not available. Enable it in device settings to run tests.',
      };
    }

    for (const step of plan.steps) {
      this.logger.info(`Step: ${step.description} (${step.action})`);
      const stepResult = await this.executeStep(step, plan.packageName);
      results.push(stepResult);

      if (!stepResult.passed && step.action !== 'verify') {
        this.logger.warn(`Step failed: ${step.description}`);
      }
    }

    const passedCount = results.filter(r => r.passed).length;
    const passed = passedCount === results.length;

    return {
      passed,
      steps: results,
      summary: `${passedCount}/${results.length} steps passed. ${passed ? 'All tests passed!' : 'Some tests failed.'}`,
    };
  }

  private async executeStep(step: TestStep, packageName: string): Promise<TestStepResult> {
    const base: TestStepResult = {
      description: step.description,
      action: step.action,
      expected: step.expectedResult,
      actual: '',
      passed: false,
    };

    try {
      switch (step.action) {
        case 'launch': {
          const activePackage = await AppController.getActivePackage();
          if (activePackage === packageName) {
            base.actual = 'App already in foreground';
            base.passed = true;
          } else {
            base.actual = 'Waiting for app to launch...';
            const started = await this.waitForPackage(packageName, 5000);
            base.actual = started ? 'App launched successfully' : 'App did not launch in time';
            base.passed = started;
          }
          break;
        }

        case 'click': {
          if (!step.selector) {
            base.actual = 'No selector provided';
            break;
          }
          await this.sleep(300);
          const clicked = await AppController.performClick(step.selector);
          base.actual = clicked ? `Clicked "${step.selector}"` : `Could not find "${step.selector}" to click`;
          base.passed = clicked;
          await this.sleep(500);
          break;
        }

        case 'type': {
          if (!step.selector || !step.text) {
            base.actual = 'Missing selector or text';
            break;
          }
          await this.sleep(300);
          const typed = await AppController.performText(step.selector, step.text);
          base.actual = typed ? `Typed "${step.text}" into "${step.selector}"` : `Could not find "${step.selector}" to type into`;
          base.passed = typed;
          await this.sleep(300);
          break;
        }

        case 'scroll': {
          const direction = (step.selector as any) || 'down';
          const scrolled = await AppController.performScroll(direction);
          base.actual = scrolled ? `Scrolled ${direction}` : 'Nothing to scroll';
          base.passed = scrolled;
          await this.sleep(300);
          break;
        }

        case 'verify': {
          await this.sleep(500);
          const screen = await AppController.getScreenContent();
          const screenText = JSON.stringify(screen);
          const hasExpected = step.selector
            ? screenText.toLowerCase().includes(step.selector.toLowerCase())
            : screenText.length > 50;
          base.actual = hasExpected
            ? `Found expected content: "${step.selector || 'screen content present'}"`
            : `Expected content not found: "${step.selector || step.expectedResult}"`;
          base.passed = hasExpected;
          break;
        }

        case 'wait': {
          const ms = parseInt(step.selector || '1000', 10);
          await this.sleep(ms);
          base.actual = `Waited ${ms}ms`;
          base.passed = true;
          break;
        }

        case 'back': {
          const backed = await AppController.performBack();
          base.actual = backed ? 'Pressed back' : 'Back action failed';
          base.passed = backed;
          await this.sleep(300);
          break;
        }

        default:
          base.actual = `Unknown action: ${step.action}`;
      }
    } catch (err: any) {
      base.actual = `Error: ${err.message}`;
    }

    return base;
  }

  private async waitForPackage(packageName: string, timeoutMs: number): Promise<boolean> {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      const active = await AppController.getActivePackage();
      if (active === packageName) return true;
      await this.sleep(500);
    }
    return false;
  }

  private sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  private parsePlan(raw: string): TestStep[] {
    let cleaned = raw.trim();
    const fenced = cleaned.match(/```(?:json)?\s*([\s\S]*?)```/);
    if (fenced) cleaned = fenced[1].trim();

    try {
      const parsed = JSON.parse(cleaned);
      const steps = parsed.steps || parsed;
      if (!Array.isArray(steps)) throw new Error('Expected array of steps');

      return steps.map((s: any) => ({
        description: s.description || '',
        action: s.action || 'verify',
        selector: s.selector,
        text: s.text,
        expectedResult: s.expectedResult || s.expected || '',
      }));
    } catch (e) {
      this.logger.error(`Failed to parse test plan: ${(e as Error).message}`);
      return [
        {
          description: 'Launch and verify app opens',
          action: 'launch',
          expectedResult: 'App opens successfully',
        },
        {
          description: 'Verify main screen content',
          action: 'verify',
          selector: '',
          expectedResult: 'Main screen shows content',
        },
      ];
    }
  }
}
