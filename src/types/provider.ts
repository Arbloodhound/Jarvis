// Provider system types — provider-agnostic multi-provider architecture

export type AllowedOperation =
  | 'chat'
  | 'reason'
  | 'vision'
  | 'image_generate'
  | 'audio_generate'
  | 'audio_transcribe'
  | 'video_generate'
  | 'embeddings'
  | 'tool_use'
  | 'generic_text';

export type SelectionStrategy =
  | 'priority'
  | 'round_robin'
  | 'cheapest_first'
  | 'fastest_first'
  | 'highest_context'
  | 'last_known_good'
  | 'fallback_chain';

export type AuthMode =
  | 'bearer'
  | 'api_key_header'
  | 'basic'
  | 'custom_header'
  | 'none';

export type ProviderStatus =
  | 'unknown'
  | 'ok'
  | 'unauthorized'
  | 'forbidden_scope'
  | 'not_found'
  | 'network_error'
  | 'parse_error'
  | 'error';

export interface ProviderCapabilities {
  adapterIds: string[];
  supportsModelListing: boolean;
  supportsAccountDiscovery: boolean;
  supportsBillingDiscovery: boolean;
  supportsUsageDiscovery: boolean;
  supportsStreaming: boolean;
  supportsToolCalls: boolean;
  supportsVision: boolean;
  supportsImageGeneration: boolean;
  supportsAudioGeneration: boolean;
  supportsVideoGeneration: boolean;
  supportsEmbeddings: boolean;
  supportsReasoningHints: boolean;
  supportsJsonMode: boolean;
  supportsResponsesApi: boolean;
  supportsChatCompletionsApi: boolean;
}

export interface PricingInfo {
  inputPer1kTokens?: number;
  outputPer1kTokens?: number;
  /** @deprecated Use inputPer1kTokens instead */
  inputPer1k?: number;
  /** @deprecated Use outputPer1kTokens instead */
  outputPer1k?: number;
  imagePerRequest?: number;
  audioPerSecond?: number;
  videoPerSecond?: number;
  currency?: string;
}

export interface DiscoveredModel {
  id: string;
  name: string;
  providerId: string;
  contextWindow: number;
  maxTokens: number;
  pricing?: PricingInfo;
  latencyHintMs?: number;
  capabilityHints?: Partial<ProviderCapabilities>;
  raw: Record<string, unknown>;
}

export interface ProbeEndpointResult {
  url: string;
  reachable: boolean;
  status?: number;
  errorCode?: 'unauthorized' | 'forbidden_scope' | 'not_found' | 'unsupported' | 'network_error' | 'parse_error' | 'ok';
  requiresStrongerScope?: boolean;
  rawSnapshotCapped?: string;
}

export interface ProbeSummary {
  probedAt: number;
  status: ProviderStatus;
  modelsFound: number;
  hasAccountData: boolean;
  hasBillingData: boolean;
  hasUsageData: boolean;
  requiresStrongerScope: boolean;
  endpointResults: ProbeEndpointResult[];
  rawAccountSnapshotCapped?: string;
  rawBillingSnapshotCapped?: string;
  rawUsageSnapshotCapped?: string;
  error?: string;
}

export interface ApiProvider {
  id: string;
  name: string;
  baseUrl: string;
  apiKeyRef: string;
  authMode: AuthMode;
  customAuthHeaderName?: string;
  customAuthHeaderPrefix?: string;
  passwordRef?: string;
  isActive: boolean;
  createdAt: number;
  updatedAt: number;
  lastVerifiedAt?: number;
  status: ProviderStatus;
  probeError?: string;
  lastProbeSummary?: ProbeSummary;
  endpointOverrides?: Record<string, string>;
  manualModelIds?: string[];
  capabilities: ProviderCapabilities;
  metadata: Record<string, unknown>;
}

export interface GroupMember {
  id: string;
  providerId: string;
  modelId: string;
  enabled: boolean;
  priority: number;
  weight: number;
  costRank?: number;
  speedRank?: number;
  contextRank?: number;
  allowedOperations: AllowedOperation[];
  adapterOverrideId?: string;
  metadata: Record<string, unknown>;
}

export interface ModelGroup {
  id: string;
  name: string;
  description: string;
  notes: string;
  tags: string[];
  aliases: string[];
  isActive: boolean;
  selectionStrategy: SelectionStrategy;
  fallbackGroupIds: string[];
  members: GroupMember[];
  createdAt: number;
  updatedAt: number;
}

export interface UserDefaults {
  groupAssignments: Record<string, string>;
  fallbackGroupId: string | null;
  globalDefaultOperationMap: Record<string, string | null>;
  conversationStickyRouting: boolean;
  notifyOnRouteSwitch: boolean;
}

export interface RouteHistory {
  conversationId: string;
  lastGroupId: string;
  lastProviderId: string;
  lastModelId: string;
  lastAdapterId: string;
  lastOperation: AllowedOperation;
  updatedAt: number;
}

export interface ResolvedRoute {
  providerId: string;
  providerName: string;
  modelId: string;
  groupId?: string;
  groupName?: string;
  adapterId: string;
  operation: AllowedOperation;
  selectionStrategy: SelectionStrategy;
  apiKey: string;
  baseUrl: string;
  authMode: AuthMode;
  customAuthHeaderName?: string;
  customAuthHeaderPrefix?: string;
  /** Resolved password for basic auth. Never log this value. */
  password?: string | null;
}

export interface RouteError {
  code: 'no_providers' | 'no_valid_route' | 'no_groups' | 'adapter_missing' | 'provider_inactive' | 'key_missing' | 'no_task_default';
  message: string;
  userMessage: string;
}

export type RouteResult = { ok: true; route: ResolvedRoute } | { ok: false; error: RouteError };

export interface NormalizedAiResponse {
  content: string;
  model: string;
  providerId: string;
  adapterId: string;
  inputTokens: number;
  outputTokens: number;
  latencyMs: number;
  finishReason?: string;
}

export interface NormalizedImageResponse {
  images: string[];
  model: string;
  providerId: string;
  adapterId: string;
  latencyMs: number;
}

export interface NormalizedAudioResponse {
  audioBase64: string;
  model: string;
  providerId: string;
  adapterId: string;
  latencyMs: number;
}

export interface NormalizedVideoJob {
  jobId: string;
  model: string;
  providerId: string;
  adapterId: string;
}

export interface NormalizedVideoResult {
  url?: string;
  base64?: string;
  model: string;
  providerId: string;
  adapterId: string;
  latencyMs: number;
}

export interface NormalizedEmbeddingsResponse {
  embeddings: number[][];
  model: string;
  providerId: string;
  adapterId: string;
  inputTokens: number;
  latencyMs: number;
}

export interface AdapterError {
  code: 'unsupported' | 'unauthorized' | 'rate_limited' | 'server_error' | 'network' | 'parse' | 'timeout';
  message: string;
  httpStatus?: number;
  retryable: boolean;
}

export type AdapterResult<T> = { ok: true; value: T } | { ok: false; error: AdapterError };

export const DEFAULT_PROVIDER_CAPABILITIES: ProviderCapabilities = {
  adapterIds: ['openai_compatible_chat'],
  supportsModelListing: true,
  supportsAccountDiscovery: false,
  supportsBillingDiscovery: false,
  supportsUsageDiscovery: false,
  supportsStreaming: true,
  supportsToolCalls: false,
  supportsVision: false,
  supportsImageGeneration: false,
  supportsAudioGeneration: false,
  supportsVideoGeneration: false,
  supportsEmbeddings: false,
  supportsReasoningHints: false,
  supportsJsonMode: false,
  supportsResponsesApi: false,
  supportsChatCompletionsApi: true,
};

export const DEFAULT_USER_DEFAULTS: UserDefaults = {
  groupAssignments: {},
  fallbackGroupId: null,
  globalDefaultOperationMap: {},
  conversationStickyRouting: true,
  notifyOnRouteSwitch: true,
};

export const STORAGE_KEYS = {
  providers: 'api_providers',
  modelGroups: 'model_groups',
  userDefaults: 'user_defaults',
  groupRoundRobinState: 'group_round_robin_state',
  routeHistory: 'route_history',
  migrationComplete: 'api_migration_complete',
  providerModels: (id: string) => `provider_${id}_models`,
  providerProbe: (id: string) => `provider_${id}_probe`,
  providerAccount: (id: string) => `provider_${id}_account`,
};

export const VAULT_KEYS = {
  providerApiKey: (id: string) => `provider_${id}_apiKey`,
  providerPassword: (id: string) => `provider_${id}_password`,
};
