import type {
  Genome, Capability, SafetyInvariant, SourceEntry, BehaviorSpec, ConfigValue,
} from './types';
import { FIXED_BUILD_SOURCES } from './templates/BuildTemplates';
import { AGENT_TEMPLATES } from './templates/AgentTemplates';
import { GENOME_TEMPLATES } from './templates/GenomeTemplates';
import { createHash } from '../utils/crypto';
import { UltraDevLog } from '../utils/UltraDevLog';

const VERSION = '1.0.0';
const SCHEMA_VERSION = '1';

export function createDefaultGenome(): Genome {
  UltraDevLog.push('SYSTEM', { event: 'genome_factory_create_default_start' });
  const genome: Genome = {
    id: generateId(),
    schemaVersion: SCHEMA_VERSION,
    version: VERSION,
    generation: 0,
    parentId: null,
    lineageHash: '',
    createdAt: Date.now(),

    identity: {
      name: 'Ultra',
      packageName: 'com.ultra.agent',
      description: 'Self-improving Android agent capable of building applications and replicating.',
      greeting: 'I am Ultra. Describe any app and I will build it. I can also improve myself.',
    },

    capabilities: [
      capCoreReasoning(),
      capCorePlanning(),
      capCoreSafety(),
      capNetworkAi(),
      capStorageDb(),
      capStorageFiles(),
      capBuildCompiler(),
      capBuildPackager(),
      capBuildSigner(),
      capBuildPipeline(),
      capUiChat(),
      capSystemShell(),
      capSystemInstaller(),
      capGenomeReader(),
      capGenomeCompiler(),
      capGenomeMutator(),
      capSenseAccessibility(),
    ],

    ai: {
      apiBaseUrl: '',
      defaultModel: 'llama-3.3-70b',
      models: [
        { id: 'llama-3.3-70b', name: 'Llama 70B', maxTokens: 4000, tier: 'strong', useFor: ['code', 'planning', 'architecture'] },
        { id: 'llama-3.1-8b', name: 'Llama 8B', maxTokens: 2000, tier: 'fast', useFor: ['classification', 'chat', 'simple_code'] },
      ],
      systemPrompt: `You are Ultra, a self-improving Android agent. You build applications from natural language descriptions. You can modify and improve yourself. You prioritize correctness, safety, and user intent.`,
      architectPrompt: `You are an expert Android architect. Decompose the user's app description into a complete, buildable project specification. Think about file dependencies, error handling, edge cases. Every file must be independently compilable with its dependencies.`,
      temperature: 0.3,
      maxRetries: 3,
    },

    safety: {
      invariants: defaultInvariants(),
      approvalRequired: ['install_apk', 'shell_exec', 'self_modify', 'network_request', 'accessibility_action'],
      autoApproved: ['file_read', 'file_write_project', 'compile', 'ai_chat'],
      blocked: ['delete_system', 'root_access', 'disable_safety', 'remove_invariants'],
      maxShellTimeoutMs: 30000,
      shellAllowlist: ['ls', 'cat', 'cp', 'mv', 'mkdir', 'rm', 'chmod', 'pm', 'am', 'ps', 'grep', 'find', 'echo', 'date'],
      maxBuildRetries: 4,
      maxMutationsPerCycle: 5,
    },

    manifest: {
      minSdk: 26,
      targetSdk: 33,
      permissions: [
        'android.permission.INTERNET',
        'android.permission.WRITE_EXTERNAL_STORAGE',
        'android.permission.READ_EXTERNAL_STORAGE',
        'android.permission.REQUEST_INSTALL_PACKAGES',
      ],
      versionCode: 1,
      versionName: VERSION,
    },

    build: {
      ecjAsset: 'tools/ecj.dex',
      d8Asset: 'tools/d8.dex',
      androidJarUrl: 'https://raw.githubusercontent.com/nickreserved/AospMirror/main/platforms/android-33/android.jar',
      signingAlias: 'ultra_sign',
      signingValidYears: 25,
    },

    fixedSources: FIXED_BUILD_SOURCES,
    behaviorSpecs: defaultBehaviorSpecs(),
    mutations: [],
    fitness: null,
  };

  genome.lineageHash = createHash(JSON.stringify(genome));
  return genome;
}

function capCoreReasoning(): Capability {
  return {
    id: 'cap.core.reasoning',
    name: 'Core Reasoning',
    version: '1.0.0',
    category: 'core',
    requires: ['cap.network.ai'],
    conflicts: [],
    permissions: [],
    services: [],
    sources: [{ path: 'src/com/ultra/agent/core/IntentClassifier.java', content: '', isTemplate: false, generatedBy: 'ai' }],
    config: {
      intentCategories: cv('string[]', ['build_app', 'modify_app', 'chat', 'self_improve', 'system_command', 'question'], 'Recognized intent types', true),
      confidenceThreshold: cv('number', 0.7, 'Minimum confidence to act without clarification', true),
    },
    behaviorSpec: `Classify user messages into intent categories. Use the AI to determine intent when ambiguous. Return structured classification with confidence score. Support multi-intent messages (e.g., "build me a calculator and make it dark themed" = build_app + modify_app).`,
    mutable: true,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'IntentClassifiable', methods: ['IntentResult classify(String input, String context)'] },
      { direction: 'requires', name: 'AiCapable', methods: ['String chat(String system, String user)'] },
    ],
  };
}

function capCorePlanning(): Capability {
  return {
    id: 'cap.core.planning',
    name: 'Action Planning',
    version: '1.0.0',
    category: 'core',
    requires: ['cap.core.reasoning', 'cap.network.ai'],
    conflicts: [],
    permissions: [],
    services: [],
    sources: [{ path: 'src/com/ultra/agent/core/ActionPlanner.java', content: '', isTemplate: false, generatedBy: 'ai' }],
    config: {
      maxPlanSteps: cv('number', 20, 'Maximum steps in an action plan', true),
      planReviewEnabled: cv('boolean', true, 'AI reviews its own plan before execution', true),
    },
    behaviorSpec: `Given a classified intent and user input, produce a structured action plan. Each step has: action type, parameters, expected outcome, fallback on failure. The planner should decompose complex requests into atomic steps. For build_app intents, produce an AppSpec. For self_improve intents, produce a MutationRequest.`,
    mutable: true,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'Plannable', methods: ['ActionPlan plan(IntentResult intent, String input)'] },
    ],
  };
}

function capCoreSafety(): Capability {
  return {
    id: 'cap.safety.gate',
    name: 'Safety Gate',
    version: '1.0.0',
    category: 'safety',
    requires: ['cap.ui.chat'],
    conflicts: [],
    permissions: [],
    services: [],
    sources: [{ path: 'src/com/ultra/agent/safety/SafetyGate.java', content: '', isTemplate: false, generatedBy: 'template' }],
    config: {},
    behaviorSpec: `Intercept all actions before execution. Classify risk tier. For actions requiring approval, display a dialog to the user showing what will happen and why. Cache user approvals for repeated identical actions. Log all decisions. NEVER allow bypassing for blocked actions.`,
    mutable: false,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'SafetyCheckable', methods: ['ApprovalResult check(Action action)', 'void logDecision(Action action, boolean approved)'] },
    ],
  };
}

function capNetworkAi(): Capability {
  return {
    id: 'cap.network.ai',
    name: 'AI Client',
    version: '1.0.0',
    category: 'network',
    requires: [],
    conflicts: [],
    permissions: ['android.permission.INTERNET'],
    services: [],
    sources: [{ path: 'src/com/ultra/agent/network/AiClient.java', content: '', isTemplate: false, generatedBy: 'template' }],
    config: {},
    behaviorSpec: `HTTP client for AI chat completions API. Support multiple models. Retry with exponential backoff. Stream-capable. Multi-turn conversation context.`,
    mutable: true,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'AiCapable', methods: ['String chat(String system, String user)', 'String chat(String model, String system, String user, int maxTokens)'] },
    ],
  };
}

function capStorageDb(): Capability {
  return {
    id: 'cap.storage.database',
    name: 'Database',
    version: '1.0.0',
    category: 'storage',
    requires: [],
    conflicts: [],
    permissions: [],
    services: [],
    sources: [{ path: 'src/com/ultra/agent/storage/AgentDatabase.java', content: '', isTemplate: false, generatedBy: 'template' }],
    config: {
      dbName: cv('string', 'ultra_agent.db', 'SQLite database name', false),
      dbVersion: cv('number', 1, 'Database schema version', true),
    },
    behaviorSpec: `SQLite database with tables: conversations (id, title, created_at, summary), messages (id, conversation_id, role, content, created_at, meta), genome (id, json, hash, created_at), build_history (id, spec_json, apk_path, success, created_at), mutations (id, genome_id, operation, description, created_at). Support schema migration.`,
    mutable: true,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'Storable', methods: ['void saveMessage(Message m)', 'List<Message> getMessages(String conversationId)', 'void saveGenome(String json, String hash)', 'String loadGenome()'] },
    ],
  };
}

function capStorageFiles(): Capability {
  return {
    id: 'cap.storage.files',
    name: 'File Manager',
    version: '1.0.0',
    category: 'storage',
    requires: [],
    conflicts: [],
    permissions: ['android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.READ_EXTERNAL_STORAGE'],
    services: [],
    sources: [{ path: 'src/com/ultra/agent/storage/FileManager.java', content: '', isTemplate: false, generatedBy: 'template' }],
    config: {},
    behaviorSpec: `File system operations scoped to app directory. Read, write, delete, list, copy. Create project directory structures. Write source files from strings. Read compilation output.`,
    mutable: true,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'FileCapable', methods: ['void writeFile(String path, String content)', 'String readFile(String path)', 'void mkdirs(String path)', 'String[] listFiles(String dir)'] },
    ],
  };
}

function capBuildCompiler(): Capability {
  return {
    id: 'cap.build.compiler',
    name: 'Java Compiler',
    version: '1.0.0',
    category: 'build',
    requires: ['cap.storage.files'],
    conflicts: [],
    permissions: [],
    services: [],
    sources: [
      { path: 'src/com/ultra/agent/build/BuildToolchain.java', content: 'FIXED:build_toolchain', isTemplate: false, generatedBy: 'fixed' },
      { path: 'src/com/ultra/agent/build/MultiFileCompiler.java', content: 'FIXED:multi_file_compiler', isTemplate: false, generatedBy: 'fixed' },
    ],
    config: {},
    behaviorSpec: `Compile Java source files using ECJ loaded via DexClassLoader. Convert .class files to DEX using D8. Support custom classpath for dependencies.`,
    mutable: false,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'Compilable', methods: ['CompileResult compile(List<String> sources, File outDir, List<String> classpath)', 'File convertToDex(File classDir, File outDir)'] },
    ],
  };
}

function capBuildPackager(): Capability {
  return {
    id: 'cap.build.packager',
    name: 'APK Packager',
    version: '1.0.0',
    category: 'build',
    requires: ['cap.build.compiler'],
    conflicts: [],
    permissions: [],
    services: [],
    sources: [
      { path: 'src/com/ultra/agent/build/BinaryManifestWriter.java', content: 'FIXED:binary_manifest', isTemplate: false, generatedBy: 'fixed' },
      { path: 'src/com/ultra/agent/build/ApkPackager.java', content: 'FIXED:apk_packager', isTemplate: false, generatedBy: 'fixed' },
    ],
    config: {},
    behaviorSpec: `Package compiled DEX + binary manifest into unsigned APK ZIP.`,
    mutable: false,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'Packageable', methods: ['void packageApk(byte[] manifest, File dex, File output, Map<String,File> extras)'] },
    ],
  };
}

function capBuildSigner(): Capability {
  return {
    id: 'cap.build.signer',
    name: 'APK Signer',
    version: '1.0.0',
    category: 'build',
    requires: [],
    conflicts: [],
    permissions: [],
    services: [],
    sources: [
      { path: 'src/com/ultra/agent/build/DerEncoder.java', content: 'FIXED:der_encoder', isTemplate: false, generatedBy: 'fixed' },
      { path: 'src/com/ultra/agent/build/CertificateGenerator.java', content: 'FIXED:cert_generator', isTemplate: false, generatedBy: 'fixed' },
      { path: 'src/com/ultra/agent/build/ApkSignerV1.java', content: 'FIXED:apk_signer', isTemplate: false, generatedBy: 'fixed' },
    ],
    config: {},
    behaviorSpec: `V1 JAR signing with hand-rolled DER/X.509/PKCS7. Generate or load RSA 2048 keypair.`,
    mutable: false,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'Signable', methods: ['String sign(String unsignedApkPath)'] },
    ],
  };
}

function capBuildPipeline(): Capability {
  return {
    id: 'cap.build.pipeline',
    name: 'Build Orchestrator',
    version: '1.0.0',
    category: 'build',
    requires: ['cap.build.compiler', 'cap.build.packager', 'cap.build.signer', 'cap.network.ai', 'cap.storage.files'],
    conflicts: [],
    permissions: [],
    services: [],
    sources: [{ path: 'src/com/ultra/agent/build/BuildPipeline.java', content: '', isTemplate: false, generatedBy: 'template' }],
    config: {
      maxDebugIterations: cv('number', 4, 'Max compile-fix cycles per file', true),
      maxGlobalRetries: cv('number', 3, 'Max full recompilation attempts', true),
    },
    behaviorSpec: `Full build orchestration: AppSpec → scaffold → compile → debug loop → dex → package → sign → install. The debug loop feeds compilation errors back to AI for targeted file fixes. Supports both app building (from user descriptions) and genome compilation (from genome specs).`,
    mutable: true,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'Buildable', methods: ['BuildResult buildFromSpec(AppSpec spec)', 'BuildResult buildFromGenome(Genome genome)'] },
    ],
  };
}

function capUiChat(): Capability {
  return {
    id: 'cap.ui.chat',
    name: 'Chat Interface',
    version: '1.0.0',
    category: 'ui',
    requires: ['cap.storage.database'],
    conflicts: [],
    permissions: [],
    services: [],
    sources: [
      { path: 'src/com/ultra/agent/ui/MainActivity.java', content: '', isTemplate: false, generatedBy: 'template' },
      { path: 'src/com/ultra/agent/ui/ChatAdapter.java', content: '', isTemplate: false, generatedBy: 'template' },
    ],
    config: {
      maxVisibleMessages: cv('number', 100, 'Messages shown before pagination', true),
      userBubbleColor: cv('string', '#2A2A3E', 'User message background', true),
      agentBubbleColor: cv('string', '#1A1A2E', 'Agent message background', true),
      backgroundColor: cv('string', '#0F0F1A', 'Screen background', true),
      textColor: cv('string', '#E0E0E0', 'Primary text color', true),
      accentColor: cv('string', '#6C63FF', 'Accent/button color', true),
    },
    behaviorSpec: `Native chat UI with programmatic layout (no XML). Dark theme. ScrollView with message bubbles. EditText input with send button. Show build progress inline. Long-press message for copy. Settings gear icon in toolbar. API key input in settings.`,
    mutable: true,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'Displayable', methods: ['void showMessage(String role, String content)', 'void showProgress(String phase, String message)', 'String getUserInput()'] },
    ],
  };
}

function capSystemShell(): Capability {
  return {
    id: 'cap.system.shell',
    name: 'Safe Shell',
    version: '1.0.0',
    category: 'system',
    requires: ['cap.safety.gate'],
    conflicts: [],
    permissions: [],
    services: [],
    sources: [
      { path: 'src/com/ultra/agent/system/SafeShellExecutor.java', content: 'FIXED:safe_shell', isTemplate: false, generatedBy: 'fixed' },
    ],
    config: {},
    behaviorSpec: `Hardened shell executor with allowlist, metacharacter rejection, timeout, audit logging.`,
    mutable: false,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'ShellCapable', methods: ['ShellResult execute(String command)'] },
    ],
  };
}

function capSystemInstaller(): Capability {
  return {
    id: 'cap.system.installer',
    name: 'App Installer',
    version: '1.0.0',
    category: 'system',
    requires: ['cap.safety.gate'],
    conflicts: [],
    permissions: ['android.permission.REQUEST_INSTALL_PACKAGES'],
    services: [],
    sources: [{ path: 'src/com/ultra/agent/system/AppInstaller.java', content: '', isTemplate: false, generatedBy: 'template' }],
    config: {},
    behaviorSpec: `Install APK files using Intent with FileProvider for API 24+. Request user confirmation through system installer dialog.`,
    mutable: true,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'Installable', methods: ['void install(String apkPath)'] },
    ],
  };
}

function capGenomeReader(): Capability {
  return {
    id: 'cap.genome.reader',
    name: 'Genome Reader',
    version: '1.0.0',
    category: 'genome',
    requires: ['cap.storage.database', 'cap.storage.files'],
    conflicts: [],
    permissions: [],
    services: [],
    sources: [{ path: 'src/com/ultra/agent/genome/GenomeManager.java', content: '', isTemplate: false, generatedBy: 'template' }],
    config: {},
    behaviorSpec: `Read genome from assets/genome.json on first launch, store in database. Load, parse, serialize genomes. Provide current genome to other capabilities.`,
    mutable: false,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'GenomeReadable', methods: ['Genome loadGenome()', 'void saveGenome(Genome g)', 'String serializeGenome(Genome g)'] },
    ],
  };
}

function capGenomeCompiler(): Capability {
  return {
    id: 'cap.genome.compiler',
    name: 'Genome Compiler',
    version: '1.0.0',
    category: 'genome',
    requires: ['cap.genome.reader', 'cap.build.pipeline', 'cap.network.ai'],
    conflicts: [],
    permissions: [],
    services: [],
    sources: [{ path: 'src/com/ultra/agent/genome/GenomeCompilerNative.java', content: '', isTemplate: false, generatedBy: 'template' }],
    config: {},
    behaviorSpec: `Compile a genome into a complete Android project: resolve fixed sources, apply templates, use AI to generate behavioral code, produce source files for the build pipeline. The output is a set of source files + manifest config + the genome.json as an asset (so offspring carry their genome). This is the Von Neumann constructor.`,
    mutable: false,
    essential: true,
    interfaces: [
      { direction: 'provides', name: 'GenomeCompilable', methods: ['GenomeBuildOutput compileGenome(Genome g)'] },
    ],
  };
}

function capGenomeMutator(): Capability {
  return {
    id: 'cap.genome.mutator',
    name: 'Genome Mutator',
    version: '1.0.0',
    category: 'genome',
    requires: ['cap.genome.reader', 'cap.genome.compiler', 'cap.network.ai', 'cap.safety.gate'],
    conflicts: [],
    permissions: [],
    services: [],
    sources: [{ path: 'src/com/ultra/agent/genome/GenomeMutatorNative.java', content: '', isTemplate: false, generatedBy: 'ai' }],
    config: {},
    behaviorSpec: `AI-driven genome modification. Analyze current genome for improvement opportunities. Propose structured mutations. Validate against invariants. Apply mutations to produce new genome. Trigger rebuild and fitness evaluation. Rollback if fitness degrades. Requires user approval for all mutations.`,
    mutable: true,
    essential: false,
    interfaces: [
      { direction: 'provides', name: 'Mutable', methods: ['MutationResult mutate(Genome g, MutationRequest req)', 'List<MutationRequest> proposeMutations(Genome g)'] },
    ],
  };
}

function capSenseAccessibility(): Capability {
  return {
    id: 'cap.sense.accessibility',
    name: 'App Controller',
    version: '1.0.0',
    category: 'sense',
    requires: ['cap.safety.gate'],
    conflicts: [],
    permissions: ['android.permission.BIND_ACCESSIBILITY_SERVICE'],
    services: [{
      className: 'com.ultra.agent.sense.AppControllerService',
      exported: false,
      permission: 'android.permission.BIND_ACCESSIBILITY_SERVICE',
      intentFilters: ['android.accessibilityservice.AccessibilityService'],
    }],
    sources: [
      { path: 'src/com/ultra/agent/sense/AppControllerService.java', content: 'FIXED:app_controller', isTemplate: false, generatedBy: 'fixed' },
    ],
    config: {},
    behaviorSpec: `Accessibility service for reading screen content and interacting with other apps. Read UI tree as structured data. Click elements by text or ID. Type text. Scroll. Navigate. Used for testing built apps and interacting with installed apps.`,
    mutable: false,
    essential: false,
    interfaces: [
      { direction: 'provides', name: 'Sensible', methods: ['JSONObject readScreen()', 'boolean clickByText(String text)', 'boolean typeText(String text)'] },
    ],
  };
}

function defaultInvariants(): SafetyInvariant[] {
  return [
    {
      id: 'inv.safety.gate.exists',
      description: 'Safety gate capability must always exist',
      predicate: 'exists:capabilities[id=cap.safety.gate]',
      enforcement: 'reject',
    },
    {
      id: 'inv.safety.gate.immutable',
      description: 'Safety gate cannot be marked mutable',
      predicate: 'eq:capabilities[id=cap.safety.gate].mutable:false',
      enforcement: 'reject',
    },
    {
      id: 'inv.safety.invariants.nonempty',
      description: 'Must always have safety invariants',
      predicate: 'gte:safety.invariants.length:3',
      enforcement: 'reject',
    },
    {
      id: 'inv.safety.blocked.nonempty',
      description: 'Must always have blocked actions',
      predicate: 'nonempty:safety.blocked',
      enforcement: 'reject',
    },
    {
      id: 'inv.safety.approval.nonempty',
      description: 'Must always require approval for some actions',
      predicate: 'nonempty:safety.approvalRequired',
      enforcement: 'reject',
    },
    {
      id: 'inv.build.signer.immutable',
      description: 'Cryptographic signing cannot be mutated',
      predicate: 'eq:capabilities[id=cap.build.signer].mutable:false',
      enforcement: 'reject',
    },
    {
      id: 'inv.shell.immutable',
      description: 'Shell executor safety cannot be mutated',
      predicate: 'eq:capabilities[id=cap.system.shell].mutable:false',
      enforcement: 'reject',
    },
    {
      id: 'inv.genome.reader.immutable',
      description: 'Genome reader cannot be mutated (breaks self-knowledge)',
      predicate: 'eq:capabilities[id=cap.genome.reader].mutable:false',
      enforcement: 'reject',
    },
    {
      id: 'inv.genome.compiler.immutable',
      description: 'Genome compiler cannot be mutated (breaks reproduction)',
      predicate: 'eq:capabilities[id=cap.genome.compiler].mutable:false',
      enforcement: 'reject',
    },
    {
      id: 'inv.mutations.limited',
      description: 'Max mutations per cycle must be bounded',
      predicate: 'gte:safety.maxMutationsPerCycle:1',
      enforcement: 'reject',
    },
    {
      id: 'inv.self.approval.required',
      description: 'Self-modification must always require user approval',
      predicate: 'contains:safety.approvalRequired:self_modify',
      enforcement: 'reject',
    },
  ];
}

function defaultBehaviorSpecs(): BehaviorSpec[] {
  return [
    {
      id: 'beh.agent.core',
      targetFile: 'src/com/ultra/agent/core/AgentCore.java',
      description: `Main agent loop. Receives user input from UI. Classifies intent using IntentClassifier. Creates action plan using ActionPlanner. Executes plan steps sequentially, checking SafetyGate before each action. Handles errors by replanning. Reports progress and results back to UI. For build_app intents, delegates to BuildPipeline. For self_improve intents, delegates to GenomeMutator.`,
      constraints: [
        'All actions must go through SafetyGate.check() before execution',
        'Errors must be caught and reported, never crash silently',
        'Long operations must report progress to UI',
        'Must run heavy work on background threads, UI updates on main thread',
      ],
    },
    {
      id: 'beh.intent.classifier',
      targetFile: 'src/com/ultra/agent/core/IntentClassifier.java',
      description: `Use AI to classify user messages into intent categories. Parse AI response into structured IntentResult with category, confidence, and extracted parameters. Handle ambiguous intents by asking for clarification.`,
      constraints: [
        'Must handle AI failures gracefully (default to chat intent)',
        'Confidence threshold from config',
        'Support multi-intent detection',
      ],
    },
    {
      id: 'beh.action.planner',
      targetFile: 'src/com/ultra/agent/core/ActionPlanner.java',
      description: `Given classified intent, produce a sequence of executable steps. Each step is an Action with type, params, expected result, and fallback. For build intents, the plan includes: design spec, generate files, compile, fix errors, package, sign, install. For self-improve, the plan includes: analyze, propose mutations, validate, apply, build, test, evaluate.`,
      constraints: [
        'Plans must be bounded (maxPlanSteps from config)',
        'Each step must have a fallback or error handler',
        'Plan must be reviewable by user before execution',
      ],
    },
  ];
}

function cv(type: ConfigValue['type'], value: ConfigValue['value'], description: string, mutable: boolean): ConfigValue {
  return { type, value, description, mutable };
}

function generateId(): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let id = '';
  for (let i = 0; i < 16; i++) id += chars[Math.floor(Math.random() * chars.length)];
  return `genome_${id}_${Date.now()}`;
}
