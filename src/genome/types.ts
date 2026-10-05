export type CapabilityCategory =
  | 'core'
  | 'build'
  | 'network'
  | 'storage'
  | 'ui'
  | 'safety'
  | 'genome'
  | 'system'
  | 'sense'
  | 'custom';

export type RiskTier = 'safe' | 'moderate' | 'dangerous' | 'critical' | 'blocked';

export type MutationOp =
  | 'add_capability'
  | 'remove_capability'
  | 'modify_capability'
  | 'update_config'
  | 'add_permission'
  | 'remove_permission'
  | 'add_behavior'
  | 'modify_behavior'
  | 'add_fixed_source'
  | 'modify_fixed_source'
  | 'update_identity'
  | 'update_ai_config'
  | 'update_safety';

export interface SourceEntry {
  path: string;
  content: string;
  isTemplate: boolean;
  generatedBy: 'fixed' | 'template' | 'ai';
}

export interface Capability {
  id: string;
  name: string;
  version: string;
  category: CapabilityCategory;
  requires: string[];
  conflicts: string[];
  permissions: string[];
  services: ServiceDef[];
  sources: SourceEntry[];
  config: Record<string, ConfigValue>;
  behaviorSpec: string;
  mutable: boolean;
  essential: boolean;
  enabled?: boolean;
  riskLevel?: RiskTier;
  interfaces: InterfaceContract[];
}

export interface InterfaceContract {
  direction: 'provides' | 'requires';
  name: string;
  methods: string[];
}

export interface ServiceDef {
  className: string;
  exported: boolean;
  permission?: string;
  intentFilters?: string[];
}

export interface ConfigValue {
  type: 'string' | 'number' | 'boolean' | 'string[]';
  value: string | number | boolean | string[];
  description: string;
  mutable: boolean;
}

export interface SafetyInvariant {
  id: string;
  description: string;
  predicate: string;
  enforcement: 'reject' | 'warn';
}

export interface BehaviorSpec {
  id: string;
  targetFile: string;
  description: string;
  constraints: string[];
  exampleInput?: string;
  exampleOutput?: string;
}

export interface MutationRecord {
  id: string;
  timestamp?: number;
  operation: MutationOp;
  target: string;
  description?: string;
  diff?: string;
  parentGenomeHash?: string;
  resultGenomeHash?: string;
  fitnessImpact: number | null;
  payload?: any;
  reason?: string;
  appliedAt?: number;
}

export interface TaskPerformance {
  challengesPassed: number;
  challengesTotal: number;
  weightedScore: number;
  failedChallenges: string[];
  averageTimeMs?: number;
  avgExecutionTimeMs?: number;
}

export interface FitnessMetrics {
  buildSuccess: boolean;
  compilationTimeMs: number;
  apkSizeBytes: number;
  installSuccess: boolean;
  launchSuccess: boolean;
  testsPassed: number;
  testsTotal: number;
  runtimeCrashes: number;
  capabilityScore: number;
  overallScore: number;
  evaluatedAt: number;
  taskPerformance: TaskPerformance | null;
}

export interface Genome {
  id: string;
  schemaVersion: string;
  version: string;
  generation: number;
  parentId: string | null;
  lineageHash: string;
  createdAt: number;

  identity: {
    name: string;
    packageName: string;
    description: string;
    greeting: string;
  };

  capabilities: Capability[];
  config?: Record<string, any>;
  permissions?: string[];

  ai: {
    apiBaseUrl: string;
    defaultModel: string;
    models: Array<{
      id: string;
      name: string;
      maxTokens: number;
      tier: 'fast' | 'balanced' | 'strong';
      useFor: string[];
    }>;
    systemPrompt: string;
    architectPrompt: string;
    temperature: number;
    maxRetries: number;
  };

  safety: {
    enabled?: boolean;
    invariants: SafetyInvariant[];
    approvalRequired: string[];
    autoApproved: string[];
    blocked: string[];
    maxShellTimeoutMs: number;
    shellAllowlist: string[];
    maxBuildRetries: number;
    maxMutationsPerCycle: number;
  };

  manifest: {
    minSdk: number;
    targetSdk: number;
    permissions: string[];
    versionCode: number;
    versionName: string;
  };

  build: {
    ecjAsset: string;
    d8Asset: string;
    androidJarUrl: string;
    signingAlias: string;
    signingValidYears: number;
  };

  fixedSources: SourceEntry[];
  behaviorSpecs: BehaviorSpec[];
  mutations: MutationRecord[];
  fitness: FitnessMetrics | null;
}

export interface MutationRequest {
  operation: MutationOp;
  target: string;
  payload: any;
  reason: string;
}

export interface MutationResult {
  success: boolean;
  genome?: Genome | null;
  mutatedGenome?: Genome | null;
  violations?: string[];
  warnings?: string[];
  error?: string;
  appliedMutations?: MutationRecord[];
}

export interface GenomeBuildOutput {
  sourceFiles: SourceEntry[];
  manifestConfig: Genome['manifest'];
  assetFiles: Array<{ path: string; content: string }>;
  dependencies: string[];
}
