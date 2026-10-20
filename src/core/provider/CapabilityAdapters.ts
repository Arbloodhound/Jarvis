// Capability adapters — each adapter translates a route into the correct invocation

import { buildAuthHeaders } from './AuthHeaders';
import { buildEndpointUrl } from './UrlNormalize';
import { UltraDevLog } from '../../utils/UltraDevLog';
import type {
  AllowedOperation,
  AdapterResult,
  AdapterError,
  NormalizedAiResponse,
  NormalizedImageResponse,
  NormalizedAudioResponse,
  NormalizedVideoJob,
  NormalizedVideoResult,
  NormalizedEmbeddingsResponse,
  ResolvedRoute,
  ProbeEndpointResult,
} from '../../types/provider';

const PROBE_TIMEOUT_MS = 12000;
const REQUEST_TIMEOUT_MS = 90000;
const IMAGE_TIMEOUT_MS = 120000;
const VIDEO_POLL_INTERVAL_MS = 4000;
const VIDEO_MAX_WAIT_MS = 300000;

function makeError(
  code: AdapterError['code'],
  message: string,
  httpStatus?: number,
  retryable = false
): AdapterError {
  return { code, message, httpStatus, retryable };
}

function isRetryableStatus(status: number): boolean {
  return status === 429 || status === 503 || status === 502 || status === 504;
}

async function safeFetch(url: string, init: RequestInit, timeoutMs: number): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

function capSnap(obj: unknown, maxLen = 4000): string {
  try {
    const s = JSON.stringify(obj);
    return s.length > maxLen ? s.slice(0, maxLen) + '…[capped]' : s;
  } catch {
    return String(obj).slice(0, maxLen);
  }
}

async function probeEndpoint(
  url: string,
  headers: Record<string, string>
): Promise<ProbeEndpointResult> {
  try {
    const resp = await safeFetch(url, { method: 'GET', headers }, PROBE_TIMEOUT_MS);
    const status = resp.status;
    if (status === 401) return { url, reachable: true, status, errorCode: 'unauthorized' };
    if (status === 403) return { url, reachable: true, status, errorCode: 'forbidden_scope', requiresStrongerScope: true };
    if (status === 404) return { url, reachable: true, status, errorCode: 'not_found' };
    if (!resp.ok) return { url, reachable: true, status, errorCode: 'unsupported' };
    let raw: unknown;
    try { raw = await resp.json(); } catch { return { url, reachable: true, status, errorCode: 'parse_error' }; }
    return { url, reachable: true, status, errorCode: 'ok', rawSnapshotCapped: capSnap(raw) };
  } catch (e: any) {
    if (e?.name === 'AbortError') {
      return { url, reachable: false, errorCode: 'network_error' };
    }
    return { url, reachable: false, errorCode: 'network_error' };
  }
}

// ─── openai_compatible_chat ───────────────────────────────────────────────────

export interface ChatMessage {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string | null | Array<Record<string, unknown>>;
  name?: string;
}

export interface ChatOptions {
  model: string;
  messages: ChatMessage[];
  maxTokens?: number;
  temperature?: number;
  stream?: boolean;
  systemPrompt?: string;
  taskId?: string;
  agentId?: string;
}

async function openaiChatInvoke(
  route: ResolvedRoute,
  opts: ChatOptions
): Promise<AdapterResult<NormalizedAiResponse>> {
  const url = buildEndpointUrl(route.baseUrl, '/chat/completions');
  const headers = {
    ...buildAuthHeaders({ authMode: route.authMode, apiKey: route.apiKey, password: route.password, customAuthHeaderName: route.customAuthHeaderName, customAuthHeaderPrefix: route.customAuthHeaderPrefix }),
    'Content-Type': 'application/json',
  };
  const body: Record<string, unknown> = {
    model: opts.model,
    messages: opts.messages,
    max_tokens: opts.maxTokens ?? 4096,
    temperature: opts.temperature ?? 0.7,
    stream: false,
  };
  const t0 = Date.now();
  const bodyStr = JSON.stringify(body);
  console.warn(`[ADAPTER_TIMING] start: model=${opts.model} provider=${route.providerId} url=${url} body_bytes=${bodyStr.length} msg_count=${opts.messages.length} max_tokens=${body.max_tokens} agent=${opts.agentId || 'unknown'}`);
  UltraDevLog.push('SYSTEM', { event: 'adapter_invoke_start', adapterId: 'openai_compatible_chat', providerId: route.providerId, modelId: opts.model, msgCount: opts.messages.length, url });
  try {
    const resp = await safeFetch(url, { method: 'POST', headers, body: bodyStr }, REQUEST_TIMEOUT_MS);
    console.warn(`[ADAPTER_TIMING] fetch_done: ${Date.now() - t0}ms status=${resp.status} agent=${opts.agentId || 'unknown'}`);
    if (!resp.ok) {
      const text = await resp.text().catch(() => '');
      const code: AdapterError['code'] =
        resp.status === 401 ? 'unauthorized' :
        resp.status === 429 ? 'rate_limited' :
        isRetryableStatus(resp.status) ? 'server_error' : 'server_error';
      UltraDevLog.push('SYSTEM', { event: 'adapter_invoke_fail', adapterId: 'openai_compatible_chat', providerId: route.providerId, modelId: opts.model, httpStatus: resp.status, errorCode: code, latencyMs: Date.now() - t0 });
      return { ok: false, error: makeError(code, `HTTP ${resp.status}: ${text.slice(0, 200)}`, resp.status, isRetryableStatus(resp.status)) };
    }
    const _jsonStart = Date.now();
    const data = await resp.json();
    console.warn(`[ADAPTER_TIMING] json_parse: ${Date.now() - _jsonStart}ms total_so_far: ${Date.now() - t0}ms agent=${opts.agentId || 'unknown'}`);
    const choice = data?.choices?.[0];
    if (!choice) {
      UltraDevLog.push('SYSTEM', { event: 'adapter_invoke_fail', adapterId: 'openai_compatible_chat', providerId: route.providerId, modelId: opts.model, errorCode: 'parse', reason: 'no_choices', latencyMs: Date.now() - t0 });
      return { ok: false, error: makeError('parse', 'No choices in response') };
    }
    const content = choice.message?.content ?? '';
    const usage = data.usage || {};
    const latencyMs = Date.now() - t0;
    UltraDevLog.push('SYSTEM', { event: 'adapter_invoke_ok', adapterId: 'openai_compatible_chat', providerId: route.providerId, modelId: opts.model, inputTokens: usage.prompt_tokens ?? 0, outputTokens: usage.completion_tokens ?? 0, latencyMs, finishReason: choice.finish_reason });
    return {
      ok: true,
      value: {
        content,
        model: data.model ?? opts.model,
        providerId: route.providerId,
        adapterId: 'openai_compatible_chat',
        inputTokens: usage.prompt_tokens ?? 0,
        outputTokens: usage.completion_tokens ?? 0,
        latencyMs,
        finishReason: choice.finish_reason,
      },
    };
  } catch (e: any) {
    const latencyMs = Date.now() - t0;
    if (e?.name === 'AbortError') {
      UltraDevLog.push('SYSTEM', { event: 'adapter_invoke_fail', adapterId: 'openai_compatible_chat', providerId: route.providerId, modelId: opts.model, errorCode: 'timeout', latencyMs });
      return { ok: false, error: makeError('timeout', 'Request timed out', undefined, true) };
    }
    UltraDevLog.push('SYSTEM', { event: 'adapter_invoke_fail', adapterId: 'openai_compatible_chat', providerId: route.providerId, modelId: opts.model, errorCode: 'network', error: e?.message, latencyMs });
    return { ok: false, error: makeError('network', e?.message || 'Network error', undefined, true) };
  }
}

async function openaiChatProbePlan(
  route: ResolvedRoute
): Promise<ProbeEndpointResult[]> {
  const headers = buildAuthHeaders({ authMode: route.authMode, apiKey: route.apiKey, password: route.password, customAuthHeaderName: route.customAuthHeaderName, customAuthHeaderPrefix: route.customAuthHeaderPrefix });
  const results: ProbeEndpointResult[] = [];
  results.push(await probeEndpoint(buildEndpointUrl(route.baseUrl, '/models'), headers));
  return results;
}

// ─── image_generation_basic ──────────────────────────────────────────────────

export interface ImageOptions {
  model: string;
  prompt: string;
  width?: number;
  height?: number;
  steps?: number;
  negativePrompt?: string;
  stylePreset?: string;
  taskId?: string;
}

async function imageGenerationInvoke(
  route: ResolvedRoute,
  opts: ImageOptions
): Promise<AdapterResult<NormalizedImageResponse>> {
  const url = buildEndpointUrl(route.baseUrl, '/image/generate');
  const headers = {
    ...buildAuthHeaders({ authMode: route.authMode, apiKey: route.apiKey, password: route.password, customAuthHeaderName: route.customAuthHeaderName, customAuthHeaderPrefix: route.customAuthHeaderPrefix }),
    'Content-Type': 'application/json',
  };
  const body: Record<string, unknown> = {
    model: opts.model,
    prompt: opts.prompt,
    width: opts.width ?? 1024,
    height: opts.height ?? 1024,
    steps: opts.steps,
    negative_prompt: opts.negativePrompt,
    style_preset: opts.stylePreset,
    return_binary: false,
    safe_mode: false,
  };
  const t0 = Date.now();
  try {
    const resp = await safeFetch(url, { method: 'POST', headers, body: JSON.stringify(body) }, IMAGE_TIMEOUT_MS);
    if (!resp.ok) {
      const text = await resp.text().catch(() => '');
      const code: AdapterError['code'] = resp.status === 401 ? 'unauthorized' : resp.status === 429 ? 'rate_limited' : 'server_error';
      return { ok: false, error: makeError(code, `HTTP ${resp.status}: ${text.slice(0, 200)}`, resp.status, isRetryableStatus(resp.status)) };
    }
    const data = await resp.json();
    const images: string[] = (data.images ?? []).map((img: any) => {
      if (typeof img === 'string') return img;
      if (img?.url) return img.url;
      if (img?.b64_json) return img.b64_json;
      return '';
    }).filter(Boolean);
    return {
      ok: true,
      value: {
        images,
        model: opts.model,
        providerId: route.providerId,
        adapterId: 'image_generation_basic',
        latencyMs: Date.now() - t0,
      },
    };
  } catch (e: any) {
    if (e?.name === 'AbortError') return { ok: false, error: makeError('timeout', 'Image request timed out', undefined, false) };
    return { ok: false, error: makeError('network', e?.message || 'Network error', undefined, true) };
  }
}

// ─── speech_basic ─────────────────────────────────────────────────────────────

export interface SpeechOptions {
  model: string;
  text: string;
  voice?: string;
  taskId?: string;
}

async function speechInvoke(
  route: ResolvedRoute,
  opts: SpeechOptions
): Promise<AdapterResult<NormalizedAudioResponse>> {
  const url = buildEndpointUrl(route.baseUrl, '/audio/speech');
  const headers = {
    ...buildAuthHeaders({ authMode: route.authMode, apiKey: route.apiKey, password: route.password, customAuthHeaderName: route.customAuthHeaderName, customAuthHeaderPrefix: route.customAuthHeaderPrefix }),
    'Content-Type': 'application/json',
  };
  const body: Record<string, unknown> = {
    model: opts.model,
    input: opts.text,
    voice: opts.voice ?? 'alloy',
  };
  const t0 = Date.now();
  try {
    const resp = await safeFetch(url, { method: 'POST', headers, body: JSON.stringify(body) }, IMAGE_TIMEOUT_MS);
    if (!resp.ok) {
      const text = await resp.text().catch(() => '');
      const code: AdapterError['code'] = resp.status === 401 ? 'unauthorized' : 'server_error';
      return { ok: false, error: makeError(code, `HTTP ${resp.status}: ${text.slice(0, 200)}`, resp.status, isRetryableStatus(resp.status)) };
    }
    const arrayBuffer = await resp.arrayBuffer();
    const bytes = new Uint8Array(arrayBuffer);
    let binary = '';
    for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]);
    const audioBase64 = btoa(binary);
    return {
      ok: true,
      value: {
        audioBase64,
        model: opts.model,
        providerId: route.providerId,
        adapterId: 'speech_basic',
        latencyMs: Date.now() - t0,
      },
    };
  } catch (e: any) {
    if (e?.name === 'AbortError') return { ok: false, error: makeError('timeout', 'Speech request timed out') };
    return { ok: false, error: makeError('network', e?.message || 'Network error', undefined, true) };
  }
}

// ─── video_job_queue ──────────────────────────────────────────────────────────

export interface VideoOptions {
  model: string;
  prompt: string;
  imageBase64?: string;
  duration?: number;
  taskId?: string;
}

async function videoQueueInvoke(
  route: ResolvedRoute,
  opts: VideoOptions
): Promise<AdapterResult<NormalizedVideoJob>> {
  const url = buildEndpointUrl(route.baseUrl, '/video/queue');
  const headers = {
    ...buildAuthHeaders({ authMode: route.authMode, apiKey: route.apiKey, password: route.password, customAuthHeaderName: route.customAuthHeaderName, customAuthHeaderPrefix: route.customAuthHeaderPrefix }),
    'Content-Type': 'application/json',
  };
  const body: Record<string, unknown> = {
    model: opts.model,
    prompt: opts.prompt,
    duration: opts.duration ?? 5,
  };
  if (opts.imageBase64) body.image = opts.imageBase64;
  try {
    const resp = await safeFetch(url, { method: 'POST', headers, body: JSON.stringify(body) }, REQUEST_TIMEOUT_MS);
    if (!resp.ok) {
      const text = await resp.text().catch(() => '');
      const code: AdapterError['code'] = resp.status === 401 ? 'unauthorized' : 'server_error';
      return { ok: false, error: makeError(code, `HTTP ${resp.status}: ${text.slice(0, 200)}`, resp.status) };
    }
    const data = await resp.json();
    const jobId = data?.queue_id ?? data?.id ?? data?.job_id ?? '';
    if (!jobId) return { ok: false, error: makeError('parse', 'No job ID in video queue response') };
    return {
      ok: true,
      value: {
        jobId,
        model: opts.model,
        providerId: route.providerId,
        adapterId: 'video_job_queue',
      },
    };
  } catch (e: any) {
    if (e?.name === 'AbortError') return { ok: false, error: makeError('timeout', 'Video queue request timed out') };
    return { ok: false, error: makeError('network', e?.message || 'Network error', undefined, true) };
  }
}

async function videoPollResult(
  route: ResolvedRoute,
  jobId: string,
  maxWaitMs = VIDEO_MAX_WAIT_MS
): Promise<AdapterResult<NormalizedVideoResult>> {
  const url = buildEndpointUrl(route.baseUrl, '/video/retrieve');
  const headers = buildAuthHeaders({ authMode: route.authMode, apiKey: route.apiKey, password: route.password, customAuthHeaderName: route.customAuthHeaderName, customAuthHeaderPrefix: route.customAuthHeaderPrefix });
  const deadline = Date.now() + maxWaitMs;
  const t0 = Date.now();
  while (Date.now() < deadline) {
    try {
      const resp = await safeFetch(
        url,
        { method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify({ id: jobId }) },
        PROBE_TIMEOUT_MS
      );
      if (resp.ok) {
        const data = await resp.json();
        const status = data?.status ?? '';
        if (status === 'completed' || data?.url || data?.video_url) {
          return {
            ok: true,
            value: {
              url: data.url ?? data.video_url,
              base64: data.base64,
              model: data.model ?? '',
              providerId: route.providerId,
              adapterId: 'video_job_queue',
              latencyMs: Date.now() - t0,
            },
          };
        }
        if (status === 'failed' || status === 'error') {
          return { ok: false, error: makeError('server_error', data?.error ?? data?.message ?? 'Video generation failed') };
        }
      }
    } catch {
      // network blip during poll — continue
    }
    await new Promise(r => setTimeout(r, VIDEO_POLL_INTERVAL_MS));
  }
  return { ok: false, error: makeError('timeout', 'Video polling timed out') };
}

// ─── embeddings_basic ─────────────────────────────────────────────────────────

export interface EmbeddingsOptions {
  model: string;
  input: string | string[];
  taskId?: string;
}

async function embeddingsInvoke(
  route: ResolvedRoute,
  opts: EmbeddingsOptions
): Promise<AdapterResult<NormalizedEmbeddingsResponse>> {
  const url = buildEndpointUrl(route.baseUrl, '/embeddings');
  const headers = {
    ...buildAuthHeaders({ authMode: route.authMode, apiKey: route.apiKey, password: route.password, customAuthHeaderName: route.customAuthHeaderName, customAuthHeaderPrefix: route.customAuthHeaderPrefix }),
    'Content-Type': 'application/json',
  };
  const t0 = Date.now();
  try {
    const resp = await safeFetch(url, { method: 'POST', headers, body: JSON.stringify({ model: opts.model, input: opts.input }) }, REQUEST_TIMEOUT_MS);
    if (!resp.ok) {
      const text = await resp.text().catch(() => '');
      return { ok: false, error: makeError('server_error', `HTTP ${resp.status}: ${text.slice(0, 200)}`, resp.status) };
    }
    const data = await resp.json();
    const embeddings: number[][] = (data.data ?? []).map((d: any) => d.embedding ?? []);
    return {
      ok: true,
      value: {
        embeddings,
        model: opts.model,
        providerId: route.providerId,
        adapterId: 'embeddings_basic',
        inputTokens: data.usage?.prompt_tokens ?? 0,
        latencyMs: Date.now() - t0,
      },
    };
  } catch (e: any) {
    if (e?.name === 'AbortError') return { ok: false, error: makeError('timeout', 'Embeddings request timed out') };
    return { ok: false, error: makeError('network', e?.message || 'Network error', undefined, true) };
  }
}

// ─── Unsupported stub ─────────────────────────────────────────────────────────

function unsupportedResult(adapterId: string, operation: string): AdapterResult<never> {
  return {
    ok: false,
    error: makeError('unsupported', `Adapter '${adapterId}' does not support operation '${operation}'`),
  };
}

// ─── Adapter interface ────────────────────────────────────────────────────────

export interface CapabilityAdapter {
  id: string;
  supportedOperations: AllowedOperation[];
  supportsOperation(op: AllowedOperation): boolean;
  probePlan(route: ResolvedRoute): Promise<ProbeEndpointResult[]>;
  invokeChat(route: ResolvedRoute, opts: ChatOptions): Promise<AdapterResult<NormalizedAiResponse>>;
  invokeImage(route: ResolvedRoute, opts: ImageOptions): Promise<AdapterResult<NormalizedImageResponse>>;
  invokeAudio(route: ResolvedRoute, opts: SpeechOptions): Promise<AdapterResult<NormalizedAudioResponse>>;
  invokeVideoQueue(route: ResolvedRoute, opts: VideoOptions): Promise<AdapterResult<NormalizedVideoJob>>;
  invokeVideoPoll(route: ResolvedRoute, jobId: string, maxWaitMs?: number): Promise<AdapterResult<NormalizedVideoResult>>;
  invokeEmbeddings(route: ResolvedRoute, opts: EmbeddingsOptions): Promise<AdapterResult<NormalizedEmbeddingsResponse>>;
}

// ─── openai_compatible_chat adapter ──────────────────────────────────────────

export const OpenAICompatibleChatAdapter: CapabilityAdapter = {
  id: 'openai_compatible_chat',
  supportedOperations: ['chat', 'reason', 'vision', 'tool_use', 'generic_text'],
  supportsOperation(op) { return this.supportedOperations.includes(op); },
  async probePlan(route) { return openaiChatProbePlan(route); },
  async invokeChat(route, opts) { return openaiChatInvoke(route, opts); },
  async invokeImage(_r, _o) { return unsupportedResult(this.id, 'image_generate') as any; },
  async invokeAudio(_r, _o) { return unsupportedResult(this.id, 'audio_generate') as any; },
  async invokeVideoQueue(_r, _o) { return unsupportedResult(this.id, 'video_generate') as any; },
  async invokeVideoPoll(_r, _j) { return unsupportedResult(this.id, 'video_generate') as any; },
  async invokeEmbeddings(_r, _o) { return unsupportedResult(this.id, 'embeddings') as any; },
};

// ─── image_generation_basic adapter ──────────────────────────────────────────

export const ImageGenerationAdapter: CapabilityAdapter = {
  id: 'image_generation_basic',
  supportedOperations: ['image_generate'],
  supportsOperation(op) { return this.supportedOperations.includes(op); },
  async probePlan(_r) { return []; },
  async invokeChat(_r, _o) { return unsupportedResult(this.id, 'chat') as any; },
  async invokeImage(route, opts) { return imageGenerationInvoke(route, opts); },
  async invokeAudio(_r, _o) { return unsupportedResult(this.id, 'audio_generate') as any; },
  async invokeVideoQueue(_r, _o) { return unsupportedResult(this.id, 'video_generate') as any; },
  async invokeVideoPoll(_r, _j) { return unsupportedResult(this.id, 'video_generate') as any; },
  async invokeEmbeddings(_r, _o) { return unsupportedResult(this.id, 'embeddings') as any; },
};

// ─── speech_basic adapter ─────────────────────────────────────────────────────

export const SpeechAdapter: CapabilityAdapter = {
  id: 'speech_basic',
  supportedOperations: ['audio_generate'],
  supportsOperation(op) { return this.supportedOperations.includes(op); },
  async probePlan(_r) { return []; },
  async invokeChat(_r, _o) { return unsupportedResult(this.id, 'chat') as any; },
  async invokeImage(_r, _o) { return unsupportedResult(this.id, 'image_generate') as any; },
  async invokeAudio(route, opts) { return speechInvoke(route, opts); },
  async invokeVideoQueue(_r, _o) { return unsupportedResult(this.id, 'video_generate') as any; },
  async invokeVideoPoll(_r, _j) { return unsupportedResult(this.id, 'video_generate') as any; },
  async invokeEmbeddings(_r, _o) { return unsupportedResult(this.id, 'embeddings') as any; },
};

// ─── video_job_queue adapter ──────────────────────────────────────────────────

export const VideoJobQueueAdapter: CapabilityAdapter = {
  id: 'video_job_queue',
  supportedOperations: ['video_generate'],
  supportsOperation(op) { return this.supportedOperations.includes(op); },
  async probePlan(_r) { return []; },
  async invokeChat(_r, _o) { return unsupportedResult(this.id, 'chat') as any; },
  async invokeImage(_r, _o) { return unsupportedResult(this.id, 'image_generate') as any; },
  async invokeAudio(_r, _o) { return unsupportedResult(this.id, 'audio_generate') as any; },
  async invokeVideoQueue(route, opts) { return videoQueueInvoke(route, opts); },
  async invokeVideoPoll(route, jobId, maxWaitMs) { return videoPollResult(route, jobId, maxWaitMs); },
  async invokeEmbeddings(_r, _o) { return unsupportedResult(this.id, 'embeddings') as any; },
};

// ─── embeddings_basic adapter ─────────────────────────────────────────────────

export const EmbeddingsAdapter: CapabilityAdapter = {
  id: 'embeddings_basic',
  supportedOperations: ['embeddings'],
  supportsOperation(op) { return this.supportedOperations.includes(op); },
  async probePlan(_r) { return []; },
  async invokeChat(_r, _o) { return unsupportedResult(this.id, 'chat') as any; },
  async invokeImage(_r, _o) { return unsupportedResult(this.id, 'image_generate') as any; },
  async invokeAudio(_r, _o) { return unsupportedResult(this.id, 'audio_generate') as any; },
  async invokeVideoQueue(_r, _o) { return unsupportedResult(this.id, 'video_generate') as any; },
  async invokeVideoPoll(_r, _j) { return unsupportedResult(this.id, 'video_generate') as any; },
  async invokeEmbeddings(route, opts) { return embeddingsInvoke(route, opts); },
};

// ─── All built-in adapters ────────────────────────────────────────────────────

export const ALL_ADAPTERS: CapabilityAdapter[] = [
  OpenAICompatibleChatAdapter,
  ImageGenerationAdapter,
  SpeechAdapter,
  VideoJobQueueAdapter,
  EmbeddingsAdapter,
];
