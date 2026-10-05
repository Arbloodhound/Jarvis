import { UltraDevLog as DebugLog } from './UltraDevLog';
import { CorrIdScope } from './CorrIdScope';

function truncateMiddle(text: string, maxLen: number): string {
  if (text.length <= maxLen) return text;
  const half = Math.floor(maxLen / 2) - 10;
  return `${text.slice(0, half)}\n...[${text.length - maxLen} chars truncated]...\n${text.slice(-half)}`;
}

function categorizeAIError(error: string): string {
  const e = error.toLowerCase();
  if (e.includes('network') || e.includes('fetch') || e.includes('econnrefused') || e.includes('timeout')) return 'network';
  if (e.includes('429') || e.includes('rate limit')) return 'rate_limit';
  if (e.includes('401') || e.includes('unauthorized') || e.includes('invalid key')) return 'auth';
  if (e.includes('400') || e.includes('invalid') || e.includes('malformed')) return 'bad_request';
  if (e.includes('500') || e.includes('502') || e.includes('503')) return 'server_error';
  if (e.includes('context') || e.includes('token') || e.includes('too long')) return 'context_overflow';
  if (e.includes('model') || e.includes('not found')) return 'model_not_found';
  return 'unknown';
}

export function logAICall(params: {
  agentId: string;
  prompt: string;
  systemPrompt?: string;
  messageCount?: number;
  model?: string;
  maxTokens?: number;
  temperature?: number;
}): {
  logResponse: (response: string, cost?: number, durationMs?: number) => void;
  logError: (error: string) => void;
} {
  const callId = `ai_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 5)}`;
  const startMs = Date.now();

  DebugLog.push('AI_CALL' as any, {
    event: 'request',
    callId,
    corrId: CorrIdScope.current(),
    agentId: params.agentId,
    model: params.model || 'default',
    maxTokens: params.maxTokens,
    temperature: params.temperature,
    promptLength: params.prompt.length,
    systemPromptLength: params.systemPrompt?.length || 0,
    messageCount: params.messageCount || 1,
    estimatedInputTokens: Math.ceil(params.prompt.length / 4) + Math.ceil((params.systemPrompt?.length || 0) / 4),
    promptPreview: truncateMiddle(params.prompt, 800),
    systemPromptPreview: params.systemPrompt ? truncateMiddle(params.systemPrompt, 400) : undefined,
  });

  return {
    logResponse: (response: string, cost?: number, durationMs?: number) => {
      DebugLog.push('AI_CALL' as any, {
        event: 'response',
        callId,
        corrId: CorrIdScope.current(),
        agentId: params.agentId,
        model: params.model || 'default',
        responseLength: response.length,
        responsePreview: truncateMiddle(response, 600),
        cost,
        durationMs: durationMs || (Date.now() - startMs),
      });
    },
    logError: async (error: string) => {
      let networkState: Record<string, any> = {};
      try {
        const NetInfo = (await import('@react-native-community/netinfo')).default;
        const state = await NetInfo.fetch();
        networkState = {
          connected: state.isConnected,
          type: state.type,
          isInternetReachable: state.isInternetReachable,
          details: state.details ? {
            isConnectionExpensive: (state.details as any).isConnectionExpensive,
            cellularGeneration: (state.details as any).cellularGeneration,
            ssid: (state.details as any).ssid,
          } : null,
        };
      } catch {}

      DebugLog.push('AI_CALL' as any, {
        event: 'error',
        callId,
        corrId: CorrIdScope.current(),
        agentId: params.agentId,
        model: params.model || 'default',
        error,
        durationMs: Date.now() - startMs,
        networkState,
        errorCategory: categorizeAIError(error),
      });
    },
  };
}

/**
 * Log a detailed token budget breakdown.
 * Call this before sending context to AI to see exactly where tokens go.
 */
export function logContextBudget(params: {
  agentId: string;
  model: string;
  contextWindow: number;
  components: Array<{ name: string; chars: number; tokens: number }>;
  totalTokens: number;
  reservedForResponse: number;
}): void {
  const utilization = params.contextWindow > 0
    ? Math.round((params.totalTokens / params.contextWindow) * 100)
    : 0;

  DebugLog.push('BUDGET_CHECK' as any, {
    event: 'context_budget',
    corrId: CorrIdScope.current(),
    agentId: params.agentId,
    model: params.model,
    contextWindow: params.contextWindow,
    totalInputTokens: params.totalTokens,
    reservedForResponse: params.reservedForResponse,
    utilization: `${utilization}%`,
    components: params.components,
    headroom: params.contextWindow - params.totalTokens - params.reservedForResponse,
    overBudget: (params.totalTokens + params.reservedForResponse) > params.contextWindow,
  });
}

/**
 * Retry an AI call with exponential backoff on transient errors.
 * Only retries on network/timeout/server errors — NOT on auth, rate limit, or bad request.
 */
export async function withRetry<T>(
  fn: () => Promise<T>,
  options?: { maxRetries?: number; baseDelayMs?: number; agentId?: string },
): Promise<T> {
  const maxRetries = options?.maxRetries ?? 3;
  const baseDelay = options?.baseDelayMs ?? 2000;
  let lastError: Error | null = null;

  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      return await fn();
    } catch (e: any) {
      lastError = e;
      const category = categorizeAIError(e.message || '');

      if (!['network', 'server_error'].includes(category)) throw e;
      if (attempt >= maxRetries) throw e;

      const delay = baseDelay * Math.pow(2, attempt) + Math.random() * 1000;
      DebugLog.push('AI_CALL' as any, {
        event: 'retry',
        attempt: attempt + 1,
        maxRetries,
        delayMs: Math.round(delay),
        error: e.message?.slice(0, 100),
        agentId: options?.agentId,
      });
      await new Promise(r => setTimeout(r, delay));
    }
  }
  throw lastError;
}
