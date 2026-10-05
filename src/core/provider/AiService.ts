// AiService — all AI/media operations route through AdapterRegistry with first-available provider fallback.

import type {
  AllowedOperation,
  NormalizedAiResponse,
  NormalizedImageResponse,
  NormalizedAudioResponse,
  NormalizedVideoJob,
  NormalizedVideoResult,
  NormalizedEmbeddingsResponse,
  AdapterResult,
  ResolvedRoute,
  ApiProvider,
} from '../../types/provider';
import type { ProviderManager } from './ProviderManager';
import type { GroupRouter } from './GroupRouter';
import { getAdapterRegistry } from './AdapterRegistry';
import type {
  ChatMessage,
  ChatOptions,
  ImageOptions,
  SpeechOptions,
  VideoOptions,
  EmbeddingsOptions,
} from './CapabilityAdapters';
import { UltraDevLog } from '../../utils/UltraDevLog';
import { CorrIdScope } from '../../utils/CorrIdScope';

const FAIL_CLOSED_MSG =
  'No model is configured for this task. Open Settings → AI Providers → Task Defaults and assign a provider + model.';

function routeLogFields(route: ResolvedRoute): Record<string, unknown> {
  return {
    corrId: CorrIdScope.current(),
    providerId: route.providerId,
    providerName: route.providerName,
    modelId: route.modelId,
    adapterId: route.adapterId,
    operation: route.operation,
    groupId: route.groupId,
    groupName: route.groupName,
  };
}

export interface TextCompletionInput {
  messages: ChatMessage[];
  model?: string;
  maxTokens?: number;
  temperature?: number;
  conversationId?: string;
  groupId?: string; // legacy field — ignored, kept for API compat
  tags?: string[];
  taskId?: string;
  agentId?: string;
  /** Provider-qualified routing hint: try this provider first when resolving the model. */
  preferredProviderId?: string;
}

export interface ImageGenerationInput {
  prompt: string;
  model?: string;
  width?: number;
  height?: number;
  steps?: number;
  negativePrompt?: string;
  stylePreset?: string;
  conversationId?: string;
  groupId?: string;
  tags?: string[];
  taskId?: string;
}

export interface SpeechInput {
  text: string;
  model?: string;
  voice?: string;
  conversationId?: string;
  groupId?: string;
  tags?: string[];
  taskId?: string;
}

export interface VideoInput {
  prompt: string;
  model?: string;
  imageBase64?: string;
  duration?: number;
  conversationId?: string;
  groupId?: string;
  tags?: string[];
  taskId?: string;
}

export interface EmbeddingsInput {
  input: string | string[];
  model?: string;
  conversationId?: string;
  groupId?: string;
  tags?: string[];
  taskId?: string;
}

export interface VisionCompletionInput {
  textPrompt: string;
  imageBase64: string;
  mimeType?: string;
  model?: string;
  maxTokens?: number;
  conversationId?: string;
  taskId?: string;
  agentId?: string;
  /** Provider-qualified routing hint: try this provider first when resolving the model. */
  preferredProviderId?: string;
}

export class AiService {
  private providerManager: ProviderManager;
  private groupRouter?: GroupRouter;

  constructor(providerManager: ProviderManager) {
    this.providerManager = providerManager;
  }

  /** Wire in the GroupRouter after construction (avoids circular dependency). */
  setGroupRouter(gr: GroupRouter): void {
    this.groupRouter = gr;
  }

  private providerHasModel(provider: ApiProvider, modelId: string): boolean {
    const discoveredModels = this.providerManager.getModelsForProvider(provider.id);
    return (
      discoveredModels.some(m => m.id === modelId) ||
      (provider.manualModelIds ?? []).includes(modelId)
    );
  }

  // Build a ResolvedRoute for the given provider + model + operation.
  // Returns null if the provider lacks a valid adapter or API key.
  private async buildRoute(
    provider: ApiProvider,
    modelId: string,
    operation: AllowedOperation
  ): Promise<ResolvedRoute | null> {
    const apiKey = await this.providerManager.getApiKey(provider);
    if (!apiKey && provider.authMode !== 'none') return null;
    const password = provider.authMode === 'basic'
      ? await this.providerManager.getPassword(provider)
      : null;
    const registry = getAdapterRegistry();
    const adapter = registry.getForOperation(provider.capabilities.adapterIds, operation);
    if (!adapter) return null;
    return {
      providerId: provider.id,
      providerName: provider.name,
      modelId,
      adapterId: adapter.id,
      operation,
      selectionStrategy: 'priority',
      apiKey: apiKey ?? '',
      baseUrl: provider.baseUrl,
      authMode: provider.authMode,
      customAuthHeaderName: provider.customAuthHeaderName,
      customAuthHeaderPrefix: provider.customAuthHeaderPrefix,
      password,
    };
  }

  // Resolve a route for the given operation. Resolution order:
  //   0. GroupRouter — 8-step group/operation-based resolution (only when groups are configured and no manual override)
  //   1. Preferred provider + manual model (provider-qualified selection) — exact hit
  //   2. Manual model override — find any provider that has this model
  //   3. First available: scan active providers, use first working route
  //   4. Fail closed — throw visible error
  private async resolveRoute(
    operation: AllowedOperation,
    opts: { manualModelId?: string; conversationId?: string; preferredProviderId?: string }
  ): Promise<ResolvedRoute> {
    const corrId = CorrIdScope.current();
    const activeProviders = this.providerManager.getActive();
    if (activeProviders.length === 0) {
      throw new Error(
        'No active providers configured. Open Settings → AI Providers and add a provider with an API key.'
      );
    }

    // Path 0 — GroupRouter: 8-step group/operation-based routing.
    // Only tried when:
    //   a) groupRouter is wired (AgentCore did the injection), and
    //   b) the caller has NOT supplied an explicit manual model override.
    // If no groups are configured (code=no_groups) or no providers (code=no_providers),
    // we fall through to the legacy provider scan. Any other failure fails closed.
    if (this.groupRouter && !opts.manualModelId) {
      const grResult = await this.groupRouter.resolve({
        operation,
        conversationId: opts.conversationId,
      });
      if (grResult.ok) {
        UltraDevLog.push('ROUTE' as any, {
          event: 'route_resolved',
          step: 'group_router',
          corrId,
          operation,
          ...routeLogFields(grResult.route),
        });
        return grResult.route;
      }
      // no_groups or no_providers → GroupRouter not applicable; fall through to legacy scan.
      if (grResult.error.code === 'no_groups' || grResult.error.code === 'no_providers') {
        UltraDevLog.push('ROUTE' as any, {
          event: 'group_router_skip',
          corrId,
          operation,
          reason: grResult.error.code,
          note: 'falling through to provider scan',
        });
      } else {
        // no_valid_route — all groups checked and nothing works; fail closed.
        UltraDevLog.push('ROUTE' as any, {
          event: 'route_fail_closed',
          corrId,
          operation,
          via: 'group_router',
          code: grResult.error.code,
        });
        throw new Error(grResult.error.userMessage);
      }
    }

    // Path A — provider-qualified selection (preferred provider + manual model)
    // When the user picks "ProviderA::model-x", route directly to ProviderA — even if
    // ProviderB also exposes model-x. This eliminates provider identity collision.
    if (opts.manualModelId && opts.preferredProviderId) {
      const preferred = activeProviders.find(p => p.id === opts.preferredProviderId);
      if (preferred && this.providerHasModel(preferred, opts.manualModelId)) {
        const route = await this.buildRoute(preferred, opts.manualModelId, operation);
        if (route) {
          UltraDevLog.push('ROUTE' as any, {
            event: 'route_resolved',
            step: 'preferred_provider_qualified',
            preferredProviderId: opts.preferredProviderId,
            ...routeLogFields(route),
          });
          return route;
        }
      }
      // Preferred provider unavailable/missing — log and fall through.
      UltraDevLog.push('ROUTE' as any, {
        event: 'preferred_provider_not_matched',
        preferredProviderId: opts.preferredProviderId,
        manualModelId: opts.manualModelId,
        note: 'falling through to any-provider scan',
      });
    }

    // Path B — manual model override (unqualified): find any active provider that has this model
    if (opts.manualModelId) {
      for (const provider of activeProviders) {
        if (!this.providerHasModel(provider, opts.manualModelId)) continue;
        const route = await this.buildRoute(provider, opts.manualModelId, operation);
        if (route) {
          UltraDevLog.push('ROUTE' as any, {
            event: 'route_resolved',
            step: 'manual_override',
            ...routeLogFields(route),
          });
          return route;
        }
      }
      // Manual model not found in any provider — fall through to first-available
      UltraDevLog.push('ROUTE' as any, {
        event: 'manual_model_not_matched',
        manualModelId: opts.manualModelId,
        operation,
        note: 'falling through to first-available provider',
      });
    }

    // Path C — first available: scan active providers, use first working route
    for (const provider of activeProviders) {
      const models = this.providerManager.getModelsForProvider(provider.id);
      const modelIds = models.length > 0
        ? models.map(m => m.id)
        : (provider.manualModelIds ?? []);
      for (const modelId of modelIds) {
        const route = await this.buildRoute(provider, modelId, operation);
        if (route) {
          UltraDevLog.push('ROUTE' as any, {
            event: 'route_resolved',
            step: 'first_available',
            ...routeLogFields(route),
          });
          return route;
        }
      }
    }

    // Fail closed
    UltraDevLog.push('ROUTE' as any, {
      event: 'route_fail_closed',
      operation,
      activeProviderCount: activeProviders.length,
    });
    throw new Error(FAIL_CLOSED_MSG);
  }

  async completeVision(input: VisionCompletionInput): Promise<NormalizedAiResponse> {
    const route = await this.resolveRoute('vision', {
      manualModelId: input.model,
      conversationId: input.conversationId,
      preferredProviderId: input.preferredProviderId,
    });
    const registry = getAdapterRegistry();
    const adapter = registry.get(route.adapterId);
    if (!adapter) throw new Error(`Adapter '${route.adapterId}' not found`);
    const model = input.model ?? route.modelId;
    const messages: ChatMessage[] = [
      {
        role: 'user',
        content: [
          { type: 'image_url', image_url: { url: `data:${input.mimeType || 'image/jpeg'};base64,${input.imageBase64}` } },
          { type: 'text', text: input.textPrompt || 'What do you see in this image?' },
        ] as any,
      },
    ];
    const opts: ChatOptions = {
      model,
      messages,
      maxTokens: input.maxTokens,
      temperature: 0.4,
      taskId: input.taskId,
      agentId: input.agentId,
    };
    UltraDevLog.push('AI_REQUEST' as any, {
      event: 'ai_vision_start',
      ...routeLogFields(route),
      model,
      promptLen: input.textPrompt.length,
      imageBytes: input.imageBase64.length,
    });
    const t0 = Date.now();
    const result: AdapterResult<NormalizedAiResponse> = await adapter.invokeChat(route, opts);
    if (!result.ok) {
      UltraDevLog.push('ERROR', {
        event: 'ai_vision_failed',
        ...routeLogFields(route),
        error: result.error.message,
        code: result.error.code,
      });
      throw new Error(`Vision request failed [${result.error.code}]: ${result.error.message}`);
    }
    UltraDevLog.push('AI_RESPONSE', {
      event: 'ai_vision_done',
      ...routeLogFields(route),
      model,
      inputTokens: result.value.inputTokens,
      outputTokens: result.value.outputTokens,
      latencyMs: Date.now() - t0,
    });
    return result.value;
  }

  async completeText(input: TextCompletionInput): Promise<NormalizedAiResponse> {
    const _routeStart = Date.now();
    const route = await this.resolveRoute('chat', {
      manualModelId: input.model,
      conversationId: input.conversationId,
      preferredProviderId: input.preferredProviderId,
    });
    console.warn(`[AISVC_TIMING] resolveRoute: ${Date.now() - _routeStart}ms agent=${input.agentId || 'unknown'} provider=${route.providerId} adapter=${route.adapterId}`);
    const registry = getAdapterRegistry();
    const adapter = registry.get(route.adapterId);
    if (!adapter) throw new Error(`Adapter '${route.adapterId}' not found`);
    const model = input.model ?? route.modelId;
    const opts: ChatOptions = {
      model,
      messages: input.messages,
      maxTokens: input.maxTokens,
      temperature: input.temperature,
      taskId: input.taskId,
      agentId: input.agentId,
    };
    UltraDevLog.push('AI_REQUEST', { event: 'ai_text_start', ...routeLogFields(route), model });
    const t0 = Date.now();
    const result: AdapterResult<NormalizedAiResponse> = await adapter.invokeChat(route, opts);
    console.warn(`[AISVC_TIMING] invokeChat: ${Date.now() - t0}ms agent=${input.agentId || 'unknown'}`);
    if (!result.ok) {
      UltraDevLog.push('ERROR', {
        event: 'ai_text_failed',
        ...routeLogFields(route),
        error: result.error.message,
        code: result.error.code,
      });
      throw new Error(`AI request failed [${result.error.code}]: ${result.error.message}`);
    }
    UltraDevLog.push('AI_RESPONSE', {
      event: 'ai_text_done',
      ...routeLogFields(route),
      model,
      inputTokens: result.value.inputTokens,
      outputTokens: result.value.outputTokens,
      latencyMs: Date.now() - t0,
    });
    return result.value;
  }

  async completeConversation(input: TextCompletionInput): Promise<NormalizedAiResponse> {
    return this.completeText(input);
  }

  async generateImage(input: ImageGenerationInput): Promise<NormalizedImageResponse> {
    const route = await this.resolveRoute('image_generate', {
      conversationId: input.conversationId,
    });
    const registry = getAdapterRegistry();
    const adapter = registry.get(route.adapterId);
    if (!adapter) throw new Error(`Adapter '${route.adapterId}' not found`);
    const model = input.model ?? route.modelId;
    const opts: ImageOptions = {
      model,
      prompt: input.prompt,
      width: input.width,
      height: input.height,
      steps: input.steps,
      negativePrompt: input.negativePrompt,
      stylePreset: input.stylePreset,
      taskId: input.taskId,
    };
    UltraDevLog.push('AI_REQUEST', {
      event: 'ai_image_start',
      ...routeLogFields(route),
      model,
      promptLen: input.prompt.length,
    });
    const result: AdapterResult<NormalizedImageResponse> = await adapter.invokeImage(route, opts);
    if (!result.ok) {
      UltraDevLog.push('ERROR', {
        event: 'ai_image_failed',
        ...routeLogFields(route),
        error: result.error.message,
      });
      throw new Error(`Image generation failed [${result.error.code}]: ${result.error.message}`);
    }
    UltraDevLog.push('AI_RESPONSE', {
      event: 'ai_image_done',
      ...routeLogFields(route),
      model,
      count: result.value.images.length,
    });
    return result.value;
  }

  async generateSpeech(input: SpeechInput): Promise<NormalizedAudioResponse> {
    const route = await this.resolveRoute('audio_generate', {
      conversationId: input.conversationId,
    });
    const registry = getAdapterRegistry();
    const adapter = registry.get(route.adapterId);
    if (!adapter) throw new Error(`Adapter '${route.adapterId}' not found`);
    const model = input.model ?? route.modelId;
    const opts: SpeechOptions = { model, text: input.text, voice: input.voice, taskId: input.taskId };
    UltraDevLog.push('AI_REQUEST', {
      event: 'ai_speech_start',
      ...routeLogFields(route),
      model,
      textLen: input.text.length,
    });
    const result: AdapterResult<NormalizedAudioResponse> = await adapter.invokeAudio(route, opts);
    if (!result.ok) {
      UltraDevLog.push('ERROR', {
        event: 'ai_speech_failed',
        ...routeLogFields(route),
        error: result.error.message,
      });
      throw new Error(`Speech generation failed [${result.error.code}]: ${result.error.message}`);
    }
    UltraDevLog.push('AI_RESPONSE', { event: 'ai_speech_done', ...routeLogFields(route), model });
    return result.value;
  }

  async generateVideo(input: VideoInput): Promise<NormalizedVideoResult> {
    const route = await this.resolveRoute('video_generate', {
      conversationId: input.conversationId,
    });
    const registry = getAdapterRegistry();
    const adapter = registry.get(route.adapterId);
    if (!adapter) throw new Error(`Adapter '${route.adapterId}' not found`);
    const model = input.model ?? route.modelId;
    const opts: VideoOptions = {
      model,
      prompt: input.prompt,
      imageBase64: input.imageBase64,
      duration: input.duration,
      taskId: input.taskId,
    };
    UltraDevLog.push('AI_REQUEST', { event: 'ai_video_start', ...routeLogFields(route), model });
    const jobResult: AdapterResult<NormalizedVideoJob> = await adapter.invokeVideoQueue(route, opts);
    if (!jobResult.ok) {
      UltraDevLog.push('ERROR', {
        event: 'ai_video_queue_failed',
        ...routeLogFields(route),
        error: jobResult.error.message,
      });
      throw new Error(`Video queue failed [${jobResult.error.code}]: ${jobResult.error.message}`);
    }
    const { jobId } = jobResult.value;
    UltraDevLog.push('SYSTEM', { event: 'ai_video_queued', ...routeLogFields(route), jobId });
    const pollResult: AdapterResult<NormalizedVideoResult> = await adapter.invokeVideoPoll(route, jobId);
    if (!pollResult.ok) {
      UltraDevLog.push('ERROR', {
        event: 'ai_video_poll_failed',
        ...routeLogFields(route),
        jobId,
        error: pollResult.error.message,
      });
      throw new Error(
        `Video polling failed [${pollResult.error.code}]: ${pollResult.error.message}`
      );
    }
    UltraDevLog.push('AI_RESPONSE', {
      event: 'ai_video_done',
      ...routeLogFields(route),
      model,
      jobId,
    });
    return pollResult.value;
  }

  async embed(input: EmbeddingsInput): Promise<NormalizedEmbeddingsResponse> {
    const route = await this.resolveRoute('embeddings', {
      conversationId: input.conversationId,
    });
    const registry = getAdapterRegistry();
    const adapter = registry.get(route.adapterId);
    if (!adapter) throw new Error(`Adapter '${route.adapterId}' not found`);
    const model = input.model ?? route.modelId;
    const opts: EmbeddingsOptions = { model, input: input.input, taskId: input.taskId };
    const result: AdapterResult<NormalizedEmbeddingsResponse> = await adapter.invokeEmbeddings(
      route,
      opts
    );
    if (!result.ok)
      throw new Error(`Embeddings failed [${result.error.code}]: ${result.error.message}`);
    return result.value;
  }

  async transcribeAudio(_input: {
    audioUri: string;
    model?: string;
    taskId?: string;
  }): Promise<string> {
    throw new Error(
      'Audio transcription: configure a task default for audio_transcribe and ensure the provider has a transcription adapter.'
    );
  }
}
