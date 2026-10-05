export type ApiCategory = 'text' | 'image' | 'video' | 'audio' | 'code' | 'reasoning';

export interface ApiProvider {
  id: string;
  name: string;
  baseUrl: string;
  apiKey: string;
  password?: string;
  categories: ApiCategory[];
  isBuiltIn?: boolean;
  isActive: boolean;
}

export type MessageRole = 'user' | 'assistant' | 'system' | 'tool';
export type MessageSource = 'ultra' | 'model' | 'system';

export interface ExecutionStep {
  step: string;
  timestamp: number;
  detail: string;
  success: boolean;
}

export interface TraceSafetyCheck {
  risk: string;
  allowed: boolean;
  reasons: string[];
}

export interface TraceVerification {
  verified: boolean;
  issues: string[];
}

export interface TraceLedgerEvent {
  phase: string;
  capability?: string;
  inputSummary: string;
  outputSummary: string;
  success: boolean;
  timestamp: number;
}

export interface PromptTrace {
  model: string;
  systemPrompt: string;
  framedUserMessage: string;
  includedMessages: Array<{ role: MessageRole; content: string }>;
  createdAt: number;
  executionSteps?: ExecutionStep[];
  plan?: { capability: string; params: Record<string, any>; reason?: string } | null;
  rawResult?: string | null;
  safetyCheck?: TraceSafetyCheck | null;
  verification?: TraceVerification | null;
  permissionState?: string;
  error?: string | null;
  durationMs?: number;
  taskId?: string;
  mode?: 'command' | 'conversation' | 'ai_instruction' | 'system' | 'vision';
  deterministic?: boolean;
  ledgerEvents?: TraceLedgerEvent[];
}

export interface ChatMessage {
  id: string;
  role: MessageRole;
  content: string;
  createdAt: number;
  source?: MessageSource;
  meta?: {
    promptTrace?: PromptTrace;
    mode?: 'command' | 'conversation' | 'ai_instruction' | 'system' | 'vision';
    capability?: string;
    risk?: 'safe' | 'moderate' | 'dangerous' | 'blocked';
    requiresApproval?: boolean;
    isBuildLog?: boolean;
    data?: any;
    needsUpgrade?: boolean;
    tierBlocked?: boolean;
    model?: string;
    cost?: number;
    multiStep?: boolean;
    stepCount?: number;
    cortex?: boolean;
    cortexTaskId?: string;
    subSteps?: number;
    originalMessage?: string;
    requiresMessageBody?: boolean;
    pendingNumber?: string;
    pendingName?: string;
    pendingChain?: {
      steps: Array<{ capability: string; params: Record<string, unknown>; reason?: string }>;
      idx: number;
      collectedSummaries: string[];
      disambigCapability: string;
      disambigParams: Record<string, unknown>;
      createdAt: number;
    };
  };
}

export interface Conversation {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  summary?: string;
  summaryUpdatedAt?: number;
  messageCountSinceSummary?: number;
  messages: ChatMessage[];
  meta?: Record<string, unknown>;
}

export interface ConversationMeta {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  preview: string;
  messageCount: number;
  starred?: boolean;
}

export interface UltraModelDef {
  id: string;
  contextWindow: number;
  speedTier: 'fast' | 'balanced' | 'heavy';
  strengths: Array<'code' | 'analysis' | 'chat' | 'long_context' | 'tool_use'>;
}

export type ExecutionResultType =
  | 'text'
  | 'action_result'
  | 'approval_required'
  | 'blocked'
  | 'error'
  | 'clarify';

export interface UltraExecutionResult {
  type: ExecutionResultType;
  message: string;
  success?: boolean;
  taskId?: string;
  data?: any;
}

export interface ActionPlan {
  capability: string;
  params: Record<string, any>;
  reason?: string;
  raw?: string;
}

export interface SafetyCheckResult {
  allowed: boolean;
  risk: 'safe' | 'moderate' | 'dangerous' | 'blocked';
  requiresApproval: boolean;
  reasons: string[];
}

export type EventPhase = 'INTAKE' | 'PLAN' | 'VERIFY' | 'APPROVE' | 'EXECUTE' | 'VERIFY_RESULT' | 'LEARN';

export interface ExecutionEvent {
  id: string;
  timestamp: number;
  phase: EventPhase;
  capability?: string;
  inputSummary: string;
  outputSummary: string;
  model?: string;
  cost?: number;
  idempotencyKey?: string;
  success: boolean;
  conversationId?: string;
}

export interface ParamDef {
  type: 'string' | 'number' | 'boolean' | 'object' | 'array';
  description?: string;
}

export interface CapabilitySchema {
  capabilityId: string;
  version: number;
  requiredParams: Record<string, ParamDef>;
  optionalParams: Record<string, ParamDef>;
}

export interface RecommendInput {
  taskType: 'code' | 'analysis' | 'conversation' | 'simple' | 'agent_action';
  requiredContextTokens: number;
  currentModel: string;
}

export interface ModelRecommendation {
  recommended: string;
  reason: string;
}

export interface BudgetCheck {
  allowed: boolean;
  reason: string;
}

export interface SessionStats {
  actionCount: number;
  highRiskCount: number;
  totalCost: number;
}
