export interface GridCategory {
  id: string;
  label: string;
  icon: string;
  iconFamily: 'ionicons' | 'material';
  color: string;
  actions: GridAction[];
  modes?: string[];
}

export interface GridAction {
  id: string;
  label: string;
  icon: string;
  iconFamily: 'ionicons' | 'material';
  capability: string;
  params: Record<string, any>;
  requiresInput?: boolean;
  inputPlaceholder?: string;
  inputKey?: string;
  children?: GridAction[];
}

export interface GridConfig {
  categoryOrder: string[];
  hiddenCategories: string[];
  favorites: string[];
  customActions: GridAction[];
}

export interface ContextAction {
  id: string;
  label: string;
  icon: string;
  iconFamily: 'ionicons' | 'material';
  execute: {
    type: 'plan';
    capability: string;
    params: Record<string, any>;
  } | {
    type: 'prompt';
    text: string;
  };
}

export interface ContextRule {
  capability?: string;
  mode?: string;
  contentMatch?: RegExp;
  actions: ContextAction[];
}

export interface TaskTemplate {
  id: string;
  name: string;
  icon: string;
  color: string;
  steps: TaskStep[];
  trigger?: TaskTrigger;
  lastUsed: number;
  useCount: number;
  lastError?: string;
  createdAt: number;
}

export interface TaskStep {
  id: string;
  capability: string;
  label: string;
  params: Record<string, any>;
  inputRequired?: string;
  inputPlaceholder?: string;
  useResultFrom?: number;
  delayMs?: number;
}

export interface TaskTrigger {
  type: 'manual' | 'time' | 'location' | 'event';
  condition: string;
  enabled: boolean;
}

export interface TaskRunResult {
  templateId: string;
  startedAt: number;
  completedAt: number;
  steps: Array<{
    stepId: string;
    success: boolean;
    result: string;
    durationMs: number;
  }>;
  overallSuccess: boolean;
  error?: string;
}
