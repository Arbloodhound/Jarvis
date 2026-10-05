import { UltraDevLog } from '../utils/UltraDevLog';

export interface TaskChallenge {
  id: string;
  name: string;
  description: string;
  category: 'ui_navigation' | 'text_generation' | 'file_operation' | 'app_interaction' | 'self_awareness' | 'resilience' | 'compound';
  difficulty: number;
  steps: ChallengeStep[];
  successCriteria: SuccessCriterion[];
  timeoutMs: number;
  weight: number;
}

export interface ChallengeStep {
  action: 'launch' | 'click' | 'type' | 'scroll' | 'back' | 'home' | 'wait' | 'verify';
  selector?: string;
  text?: string;
  direction?: 'up' | 'down' | 'left' | 'right';
  waitMs?: number;
  description: string;
}

export interface SuccessCriterion {
  type: 'screen_contains' | 'screen_not_contains' | 'app_foreground' | 'no_crash';
  value?: string;
  description: string;
}

export interface ChallengeResult {
  challengeId: string;
  passed: boolean;
  partialScore: number;
  executionTimeMs: number;
  crashCount: number;
  screenCaptures: string[];
  error?: string;
  criteriaResults: Array<{ criterion: string; passed: boolean }>;
}

export interface OverallEvaluation {
  results: ChallengeResult[];
  overallPassRate: number;
  weightedScore: number;
  totalCrashes: number;
  totalTimeMs: number;
}

interface AiClient {
  chat: (args: { model: string; messages: Array<{ role: string; content: string }>; max_tokens: number }) => Promise<string>;
}

export function getDefaultChallenges(packageName: string): TaskChallenge[] {
  UltraDevLog.push('SYSTEM', { event: 'task_challenges_get_defaults', packageName });
  return [
    {
      id: 'ch_launch_verify',
      name: 'Launch & Verify',
      description: 'Launch the app and verify the main screen loads',
      category: 'ui_navigation',
      difficulty: 1,
      steps: [
        { action: 'launch', description: 'Launch the offspring app' },
        { action: 'wait', waitMs: 3000, description: 'Wait for app to initialize' },
        { action: 'verify', description: 'Verify main screen is visible' },
      ],
      successCriteria: [
        { type: 'app_foreground', value: packageName, description: 'App is in foreground' },
        { type: 'no_crash', description: 'App did not crash on launch' },
      ],
      timeoutMs: 15000,
      weight: 1.5,
    },
    {
      id: 'ch_ui_navigation',
      name: 'UI Navigation',
      description: 'Navigate to settings or about screen and verify it loads',
      category: 'ui_navigation',
      difficulty: 2,
      steps: [
        { action: 'launch', description: 'Launch the app' },
        { action: 'wait', waitMs: 2000, description: 'Wait for load' },
        { action: 'click', selector: 'Settings', description: 'Tap settings or menu' },
        { action: 'wait', waitMs: 1000, description: 'Wait for navigation' },
        { action: 'verify', description: 'Verify settings screen loaded' },
      ],
      successCriteria: [
        { type: 'app_foreground', value: packageName, description: 'App still in foreground' },
        { type: 'no_crash', description: 'No crash during navigation' },
      ],
      timeoutMs: 20000,
      weight: 1.0,
    },
    {
      id: 'ch_genome_info',
      name: 'Genome Self-Awareness',
      description: 'Verify the app knows its own generation and genome ID',
      category: 'self_awareness',
      difficulty: 2,
      steps: [
        { action: 'launch', description: 'Launch the app' },
        { action: 'wait', waitMs: 2000, description: 'Wait for load' },
        { action: 'click', selector: 'About', description: 'Navigate to about/genome info' },
        { action: 'wait', waitMs: 1000, description: 'Wait for screen' },
        { action: 'verify', description: 'Look for generation info' },
      ],
      successCriteria: [
        { type: 'screen_contains', value: 'Generation', description: 'Shows generation number' },
        { type: 'no_crash', description: 'No crash while checking genome info' },
      ],
      timeoutMs: 20000,
      weight: 1.5,
    },
    {
      id: 'ch_text_input',
      name: 'Text Input & Response',
      description: 'Type a message and verify the app responds',
      category: 'text_generation',
      difficulty: 3,
      steps: [
        { action: 'launch', description: 'Launch the app' },
        { action: 'wait', waitMs: 3000, description: 'Wait for full initialization' },
        { action: 'type', selector: 'input', text: 'Hello, what can you do?', description: 'Type a query' },
        { action: 'click', selector: 'Send', description: 'Send the message' },
        { action: 'wait', waitMs: 10000, description: 'Wait for AI response' },
        { action: 'verify', description: 'Check for a response' },
      ],
      successCriteria: [
        { type: 'app_foreground', value: packageName, description: 'App still running' },
        { type: 'no_crash', description: 'No crash during interaction' },
      ],
      timeoutMs: 30000,
      weight: 2.0,
    },
    {
      id: 'ch_file_create',
      name: 'File Creation',
      description: 'Ask the app to create a file and verify it acknowledges',
      category: 'file_operation',
      difficulty: 3,
      steps: [
        { action: 'launch', description: 'Launch the app' },
        { action: 'wait', waitMs: 3000, description: 'Wait for init' },
        { action: 'type', selector: 'input', text: 'Create a file called test.txt with hello world', description: 'Request file creation' },
        { action: 'click', selector: 'Send', description: 'Send the command' },
        { action: 'wait', waitMs: 8000, description: 'Wait for processing' },
        { action: 'verify', description: 'Check for success acknowledgment' },
      ],
      successCriteria: [
        { type: 'app_foreground', value: packageName, description: 'App still running' },
        { type: 'no_crash', description: 'No crash during file op' },
      ],
      timeoutMs: 25000,
      weight: 1.5,
    },
    {
      id: 'ch_error_resilience',
      name: 'Error Resilience',
      description: 'Send malformed input and verify the app handles it gracefully',
      category: 'resilience',
      difficulty: 2,
      steps: [
        { action: 'launch', description: 'Launch the app' },
        { action: 'wait', waitMs: 2000, description: 'Wait for load' },
        { action: 'type', selector: 'input', text: '{}[]<>!!!@@@###$$$', description: 'Send garbage input' },
        { action: 'click', selector: 'Send', description: 'Send the message' },
        { action: 'wait', waitMs: 5000, description: 'Wait for handling' },
        { action: 'verify', description: 'App should still be running' },
      ],
      successCriteria: [
        { type: 'app_foreground', value: packageName, description: 'App survived bad input' },
        { type: 'no_crash', description: 'No crash on malformed input' },
      ],
      timeoutMs: 15000,
      weight: 1.5,
    },
    {
      id: 'ch_back_navigation',
      name: 'Back Navigation',
      description: 'Navigate forward then back and verify app state is consistent',
      category: 'ui_navigation',
      difficulty: 2,
      steps: [
        { action: 'launch', description: 'Launch the app' },
        { action: 'wait', waitMs: 2000, description: 'Wait for load' },
        { action: 'click', selector: 'Settings', description: 'Navigate to secondary screen' },
        { action: 'wait', waitMs: 1000, description: 'Wait for transition' },
        { action: 'back', description: 'Press back' },
        { action: 'wait', waitMs: 1000, description: 'Wait for return' },
        { action: 'verify', description: 'Verify main screen is back' },
      ],
      successCriteria: [
        { type: 'app_foreground', value: packageName, description: 'App still in foreground after back' },
        { type: 'no_crash', description: 'No crash during navigation cycle' },
      ],
      timeoutMs: 15000,
      weight: 1.0,
    },
    {
      id: 'ch_compound_flow',
      name: 'Compound Task',
      description: 'Multi-step interaction: type, send, scroll, verify — tests end-to-end flow',
      category: 'compound',
      difficulty: 4,
      steps: [
        { action: 'launch', description: 'Launch the app' },
        { action: 'wait', waitMs: 3000, description: 'Wait for full init' },
        { action: 'type', selector: 'input', text: 'What is 2 + 2?', description: 'Ask a simple question' },
        { action: 'click', selector: 'Send', description: 'Send message' },
        { action: 'wait', waitMs: 8000, description: 'Wait for response' },
        { action: 'scroll', direction: 'down', description: 'Scroll to see full response' },
        { action: 'type', selector: 'input', text: 'Thank you', description: 'Send follow-up' },
        { action: 'click', selector: 'Send', description: 'Send second message' },
        { action: 'wait', waitMs: 5000, description: 'Wait for second response' },
        { action: 'verify', description: 'Verify conversation flow worked' },
      ],
      successCriteria: [
        { type: 'app_foreground', value: packageName, description: 'App survived multi-step flow' },
        { type: 'no_crash', description: 'No crash during compound task' },
      ],
      timeoutMs: 45000,
      weight: 2.5,
    },
  ];
}

export async function generateChallengesForGoal(
  ai: AiClient,
  model: string,
  packageName: string,
  goal: string
): Promise<TaskChallenge[]> {
  const prompt = `You are designing test challenges for an Android AI agent app.
The app's package name is: ${packageName}
The user's improvement goal is: "${goal}"

Generate 3-5 task challenges that test whether the app meets this goal.
Each challenge should have:
- id (string, unique like "ch_custom_1")
- name (short title)
- description (what the test does)
- category (one of: ui_navigation, text_generation, file_operation, app_interaction, self_awareness, resilience, compound)
- difficulty (1-5)
- steps (array of {action, selector?, text?, direction?, waitMs?, description})
  - actions: launch, click, type, scroll, back, home, wait, verify
- successCriteria (array of {type, value?, description})
  - types: screen_contains, screen_not_contains, app_foreground, no_crash
- timeoutMs (10000-60000)
- weight (0.5-3.0, higher = more important)

Respond with ONLY a JSON array of challenges. No markdown fencing.`;

  const response = await ai.chat({
    model,
    messages: [{ role: 'user', content: prompt }],
    max_tokens: 4000,
  });

  try {
    let cleaned = response.trim();
    if (cleaned.startsWith('```')) {
      cleaned = cleaned.replace(/^```(?:json)?\n?/, '').replace(/\n?```$/, '');
    }
    const challenges = JSON.parse(cleaned) as TaskChallenge[];
    return challenges.filter(c => c.id && c.steps && c.successCriteria);
  } catch {
    return [];
  }
}
