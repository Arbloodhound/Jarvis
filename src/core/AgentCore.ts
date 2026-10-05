import { SecureVault } from '../security/SecureVault';
import { ModelRouter, type ModelRouterBridge } from './ModelRouter';
import { CapabilityRegistry } from './CapabilityRegistry';
import { CapabilityProbe } from './CapabilityProbe';
import { PermissionBroker } from './PermissionBroker';
import { DebugEngine } from './DebugEngine';
import { BuildSystem } from './BuildSystem';
import { TaskExecutor } from './TaskExecutor';
import { ProviderManager } from './provider/ProviderManager';
import { GroupManager } from './provider/GroupManager';
import { RouteHistoryStore } from './provider/RouteHistoryStore';
import { GroupRouter } from './provider/GroupRouter';
import { AiService } from './provider/AiService';
import { PreferenceLearner } from '../utils/PreferenceLearner';
import { CostTracker } from '../services/CostTracker';
import { StorageManager } from '../services/StorageManager';
import { ConversationManager } from '../services/ConversationManager';
import { ExecutionLedger } from '../services/ExecutionLedger';
import { SafetyChecker } from './SafetyChecker';
import { CommandParser } from './CommandParser';
import { validatePlan } from './CapabilitySchemas';
import { Logger } from '../utils/Logger';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import { MemoryManager } from './MemoryManager';
import { EventMonitor } from '../services/EventMonitor';
import { TierService } from '../services/TierService';
import { Cortex, CortexResult } from './Cortex';
import { KnowledgeGraph } from './KnowledgeGraph';
import { DeviceSignals } from './DeviceSignals';
import { ProactiveEngine } from './ProactiveEngine';
import { BackgroundOrchestrator } from './BackgroundOrchestrator';
import { CredentialVault } from './CredentialVault';
import { AIIntentParser } from './AIIntentParser';
import { BrainExecutor } from './BrainExecutor';
import type {
  ChatMessage,
  UltraExecutionResult,
  ActionPlan,
  PromptTrace,
  ExecutionResultType,
  ExecutionStep,
  TraceLedgerEvent,
} from '../types/ultra';

class SimpleEmitter {
  private listeners: Map<string, Array<(...args: any[]) => void>> = new Map();
  on(event: string, fn: (...args: any[]) => void): this {
    if (!this.listeners.has(event)) this.listeners.set(event, []);
    this.listeners.get(event)!.push(fn);
    return this;
  }
  emit(event: string, ...args: any[]): boolean {
    const fns = this.listeners.get(event);
    if (!fns) return false;
    fns.forEach(fn => { try { fn(...args); } catch (e) { console.error(`[SimpleEmitter] listener error on "${event}":`, e); } });
    return true;
  }
  removeAllListeners(event?: string): this {
    if (event) this.listeners.delete(event);
    else this.listeners.clear();
    return this;
  }
}

type Mode = 'command' | 'conversation' | 'ai_instruction';

function estimateTokens(text: string): number {
  return Math.ceil((text || '').length / 4);
}

function uid(prefix = 'msg'): string {
  return `${prefix}_${Math.random().toString(36).slice(2)}_${Date.now()}`;
}

type Complexity = 'simple' | 'moderate' | 'complex';

function classifyComplexity(input: string): Complexity {
  const t = input.toLowerCase();
  const complexSignals = [
    /\b(explain|analyze|compare|contrast|evaluate|critique|assess|reasoning|logic|philosophy|ethical|moral|theorem|proof|research|hypothesis|argue|debate)\b/,
    /\b(why|how does|what causes|what is the relationship|implications?|consequences?|significance)\b/,
    /\b(step by step|in depth|thoroughly|comprehensively|detailed analysis)\b/,
    /\b(difference between .+ and .+|pros and cons|advantages? and disadvantages?)\b/,
  ];
  const moderateSignals = [
    /\b(summarize|describe|list|tell me about|what is|what are|how to|how do)\b/,
    /\b(recommend|suggest|help me|write a|create a|draft|plan|ideas?)\b/,
    /[?].*[?]/,
  ];
  if (complexSignals.some(r => r.test(t))) return 'complex';
  if (moderateSignals.some(r => r.test(t)) || input.length > 200) return 'moderate';
  return 'simple';
}

export interface ExecuteArgs {
  conversationId: string;
  userInput: string;
  replay?: boolean;
  approvedAction?: boolean;
  pendingState?: {
    messages: Array<{ role: string; content: string }>;
    toolCall: { tool: string; params: Record<string, any> };
  };
}

export class AgentCore extends SimpleEmitter {
  private vault: SecureVault;
  private ai: ModelRouter;
  private caps: CapabilityRegistry;
  private probe: CapabilityProbe;
  private perms: PermissionBroker;
  private debugEngine: DebugEngine;
  private buildSystem: BuildSystem;
  private executor: TaskExecutor;
  private learner: PreferenceLearner;
  private costTracker: CostTracker;
  private storage: StorageManager;
  private conversations: ConversationManager;
  private ledger: ExecutionLedger;
  private safety: SafetyChecker;
  private parser: CommandParser;
  private memory: MemoryManager;
  private tierService: TierService;
  private cortex: Cortex;
  private deviceSignals: DeviceSignals;
  private proactive: ProactiveEngine | null = null;
  private background: BackgroundOrchestrator | null = null;
  private eventMonitor: EventMonitor | null = null;
  private providerManager: ProviderManager;
  private groupManager: GroupManager;
  private routeHistoryStore: RouteHistoryStore;
  private aiService: AiService;
  private logger: Logger;
  private ready: boolean;
  private instanceId: string;
  private credentialVault: CredentialVault;
  private intentParser: AIIntentParser;
  private destroyed = false;
  private destroyPromise: Promise<void> | null = null;

  constructor(vault: SecureVault, cb: (msg: string, type: string) => void) {
    super();
    this.instanceId = DebugLog.coreCreated('initial_mount');
    this.vault = vault;
    this.logger = new Logger('AgentCore');
    this.costTracker = new CostTracker(vault);
    this.storage = new StorageManager(500);
    this.ai = new ModelRouter(vault, this.costTracker);
    this.caps = new CapabilityRegistry();
    this.probe = CapabilityProbe.getInstance();
    this.perms = new PermissionBroker();
    this.learner = new PreferenceLearner(vault);
    this.debugEngine = new DebugEngine(this.ai, this.learner);
    this.buildSystem = new BuildSystem(this.ai, this.debugEngine, this.storage);
    this.providerManager = new ProviderManager(vault);
    this.groupManager = new GroupManager();
    this.routeHistoryStore = new RouteHistoryStore();
    this.aiService = new AiService(this.providerManager);
    const groupRouter = new GroupRouter(this.providerManager, this.groupManager, this.routeHistoryStore);
    this.aiService.setGroupRouter(groupRouter);
    this.executor = new TaskExecutor(this.buildSystem, this.debugEngine, this.caps, this.perms, this.ai, this.probe);
    this.executor.setPreferenceLearner(this.learner);
    this.executor.setAiService(this.aiService);
    this.conversations = new ConversationManager();
    this.ledger = new ExecutionLedger();
    this.safety = new SafetyChecker();
    this.parser = new CommandParser();
    this.memory = new MemoryManager(vault);
    this.tierService = new TierService(vault);
    this.cortex = new Cortex(this.ai, this.executor, this.caps, this.memory, this.vault);
    this.deviceSignals = new DeviceSignals();
    this.credentialVault = new CredentialVault(this.vault);
    this.intentParser = new AIIntentParser(this.ai, this.caps);
    this.ready = false;
    this.on('log', cb);
  }

  getInstanceId(): string {
    return this.instanceId;
  }

  getCapabilityProbe(): CapabilityProbe {
    return this.probe;
  }

  destroy(reason: string = 'cleanup'): void {
    DebugLog.coreDestroyed(this.instanceId, reason);
    this.removeAllListeners();
    this.background?.stop();
  }

  async initialize(): Promise<void> {
    if (this.ready) return;
    this.emit('log', 'Initializing systems...', 'system');
    DebugLog.agentInitStart();
    const initStart = Date.now();

    const safeInit = async (name: string, fn: () => Promise<void>): Promise<void> => {
      const t0 = Date.now();
      try {
        await fn();
        DebugLog.agentInitSubsystem(name, true);
      } catch (err: any) {
        DebugLog.agentInitSubsystem(name, false);
        console.warn(`[AgentCore] ${name} init failed: ${err.message}`);
      }
    };

    await Promise.all([
      safeInit('CostTracker', () => this.costTracker.initialize()),
      safeInit('Storage', () => this.storage.initialize()),
      safeInit('Capabilities', () => this.caps.initialize()),
      safeInit('Permissions', () => this.perms.initialize()),
      safeInit('Learner', () => this.learner.initialize()),
      safeInit('Ledger', () => this.ledger.initialize()),
      safeInit('MemoryManager', () => this.memory.initialize()),
      safeInit('TierService', () => this.tierService.initialize()),
      safeInit('CapabilityProbe', () => this.probe.probe().then(() => {})),
      safeInit('ProviderManager', () => this.providerManager.initialize()),
      safeInit('GroupManager', () => this.groupManager.initialize()),
      safeInit('RouteHistory', () => this.routeHistoryStore.initialize()),
      safeInit('CredentialVault', () => this.credentialVault.initialize()),
    ]);

    await safeInit('ModelRouter', () => this.ai.initialize());
    await safeInit('ModelProviders', () => this.ai.loadProviders());
    // Wire the runtime bridge so ModelRouter delegates to the new provider system.
    this.wireModelRouterBridge();
    // Immediately populate the provider-backed model list so the picker shows real data.
    await safeInit('BridgeSync', () => this.ai.refreshBridgeState());
    await safeInit('DebugEngine', () => this.debugEngine.initialize());
    await safeInit('BuildSystem', () => this.buildSystem.initialize());
    await safeInit('TaskExecutor', () => this.executor.initialize());
    await safeInit('StorageBudget', () => this.storage.enforceBudget());
    await safeInit('LogCleanup', async () => { await Logger.cleanOldLogs(7); });
    await safeInit('DebugLogCleanup', async () => { await DebugLog.cleanOldLogs(7); });
    DebugLog.scheduleStartupRawExport();
    try {
      const rawDaily = await this.vault.get('daily_cost_limit');
      const rawTask = await this.vault.get('task_cost_limit');
      const dailyLimit = parseFloat(rawDaily || '0');
      const taskLimit = parseFloat(rawTask || '0');
      if (dailyLimit > 0) {
        this.ledger.budgetLimits.maxCostPerDay = dailyLimit;
        DebugLog.systemEvent('AgentCore', `Daily cost limit loaded: $${dailyLimit}/24h`);
      }
      if (taskLimit > 0) {
        this.ledger.budgetLimits.maxCostPerTask = taskLimit;
        DebugLog.systemEvent('AgentCore', `Per-task cost limit loaded: $${taskLimit}/task`);
      }
      if (dailyLimit === 0 && taskLimit === 0) {
        DebugLog.systemEvent('AgentCore', 'No cost limits configured — using default session limit of $2.00');
      }
    } catch (costErr: any) {
      DebugLog.error('AgentCore', `Failed to load cost limits from vault: ${costErr.message}`, costErr.stack);
    }
    await safeInit('CostCleanup', () => this.costTracker.cleanup(30));
    DebugLog.agentInitComplete(Date.now() - initStart);

    this.ready = true;

    await safeInit('Cortex', () => this.cortex.initialize());
    await safeInit('DeviceSignals', () => this.deviceSignals.initialize());

    try {
      this.eventMonitor = new EventMonitor(this.executor, this.memory, this.ai);
      await this.eventMonitor.start();
      DebugLog.systemEvent('AgentCore', 'EventMonitor started');
    } catch (evErr: any) {
      DebugLog.error('EventMonitor', `Failed to start: ${evErr.message}`, evErr.stack);
    }

    await safeInit('ProactiveEngine', async () => {
      const graph = this.cortex.getKnowledgeGraph();
      this.proactive = new ProactiveEngine(this.deviceSignals, graph, this.ai, this.vault);
      await this.proactive.initialize();
    });
    await safeInit('BackgroundOrchestrator', async () => {
      if (this.proactive) {
        this.background = new BackgroundOrchestrator(this.proactive, this.deviceSignals);
        await this.background.start((suggestions) => {
          for (const s of suggestions) this.emit('log', `\ud83d\udca1 ${s.title}: ${s.body}`, 'proactive');
        });
      }
    });
    await safeInit('KnowledgeGraphSeed', async () => {
      const graph = this.cortex.getKnowledgeGraph();
      if (!graph || graph.getByType('person').length > 0) return;
      DebugLog.push('KG_SEED' as any, { event: 'start' });
      try {
        const Contacts = await import('expo-contacts');
        const { data } = await Contacts.getContactsAsync({ fields: [Contacts.Fields.Name, Contacts.Fields.PhoneNumbers], pageSize: 100 });
        // Create the "user" entity — the person holding the phone
        const userEntity = graph.addEntity({
          type: 'person' as any,
          name: 'me',
          aliases: ['myself', 'i', 'user', 'owner'],
          properties: { isOwner: true },
          confidence: 1.0,
          source: 'system_init',
        });
        let seeded = 0;
        for (const contact of data) {
          if (!contact.name || contact.name.length < 2) continue;
          const entity = graph.addEntity({ type: 'person', name: contact.name, confidence: 0.7, source: 'contact_sync' } as any);
          if (contact.phoneNumbers) for (const phone of contact.phoneNumbers) {
            if (!phone.number) continue;
            const numE = graph.addEntity({ type: 'person', name: phone.number, properties: { isPhoneNumber: true, label: phone.label || 'other' }, confidence: 0.9 } as any);
            graph.addRelation({ fromEntity: entity.id, toEntity: numE.id, type: 'has_number', source: 'contact_sync', confidence: 0.9 });
          }
          graph.addRelation({ fromEntity: userEntity.id, toEntity: entity.id, type: 'is_contact_of', source: 'contact_sync', confidence: 0.7 });
          seeded++;
        }
        await graph.persist();
        DebugLog.push('KG_SEED' as any, { event: 'done', contacts: data.length, seeded });
      } catch (e: any) { DebugLog.error('KGSeed', e.message, e.stack); }
    });

    try { const resumable = await this.cortex.getResumableTasks(); if (resumable.length > 0) DebugLog.systemEvent('AgentCore', `${resumable.length} resumable Cortex tasks`); } catch {}

    this.emit('log', 'All systems online', 'agent');
  }

  private detectMode(input: string): Mode {
    const t = input.toLowerCase().trim();
    if (t.startsWith('ask the ai') || t.startsWith('use ai to') || t.includes('when you ask the ai') || t.includes('when you talk to ai')) {
      return 'ai_instruction';
    }
    const genomePatterns = /^(improve\s+yourself|self[\s-]?improve|evolve|mutate|upgrade\s+yourself|replicate|self[\s-]?replicate|reproduce|clone\s+yourself|spawn\s+offspring)\b/i;
    if (genomePatterns.test(t)) return 'command';
    const imperative = /^(open|send|read|delete|find|show|create|build|run|execute|launch|call|write|list|take|share|pick|choose|select|where|what'?s?|how|get|text|make|start|switch|generate|play|schedule|email|mail|dial|navigate|directions?|timer|map|turn|set|search|google|look|flash|torch|flashlight|mute|unmute|silence|dim|brighten|copy|paste|capture|grab|clip|notify|check|battery|network|storage|ram|memory|disk|space|wifi|bluetooth|system|device|status|info|phone|cpu|temp|weather|remind|wake|alarm|volume|ringer|brightness|screenshot|record|scan|download|upload|install|uninstall|update|sync|pair|connect|disconnect|reset|clear|lock|unlock|enable|disable|activate|deactivate|toggle|browse|visit|go|stop|pause|resume|skip|next|previous|repeat|shuffle|queue|bookmark|save|note|jot|remember|forget|recall|settings|camera|contacts|messages|photos|gallery|apps|calendar|maps|clock|calculator|location|gps|coordinates|where'?s)\b/i.test(t);
    if (imperative) return 'command';
    if (/^(gps|my\s+(?:location|coordinates|gps))\b/i.test(t)) return 'command';
    const ultraCommand = /^ultra[\s,]+(?:open|send|read|delete|find|show|create|build|run|execute|launch|call|write|list|take|share|pick|choose|select|where|what|how|get|text|make|start|switch|generate|play|schedule|email|mail|dial|navigate|directions?|timer|map|turn|set|search|google|look|flash|torch|mute|unmute|silence|dim|brighten|copy|paste|capture|grab|check|battery|network|storage|ram|memory|disk|space|wifi|system|device|status|info|weather|remind|wake|alarm|volume|ringer|brightness|screenshot|record|scan|download|upload|install|uninstall|update|sync|pair|connect|disconnect|reset|clear|lock|unlock|enable|disable|activate|deactivate|toggle|browse|visit|go|stop|pause|resume|skip|next|previous|repeat|shuffle|queue|bookmark|save|note|jot|remember|forget|recall)\b/i;
    if (ultraCommand.test(t)) return 'command';
    return 'conversation';
  }

  private lastSystemContext: string = '';

  private shouldPromoteConversationalPlan(plan: ActionPlan | null): boolean {
    if (!plan?.capability) return false;
    return [
      'app_launch', 'open_url', 'media_access', 'device_location', 'contacts_read',
      'sms_read', 'sms_conversation', 'sms_send', 'camera_capture', 'flashlight_toggle',
      'alarm_set', 'timer_set', 'reminder_create', 'clipboard_read', 'clipboard_write',
      'wifi_toggle', 'bluetooth_toggle', 'do_not_disturb', 'battery_status', 'system_info',
      'device_info', 'app_share', 'web_research', 'vision_read', 'react_navigate',
      'event_trigger_set', 'event_trigger_list', 'event_trigger_remove', 'note_create',
      'calendar_create', 'app_info', 'notification_read', 'volume_set', 'brightness_set',
      'describe_screen', 'read_text_on_screen'
    ].includes(plan.capability);
  }


  async refreshSystemContext(): Promise<void> {
    try {
      const { SystemInfoService } = await import('../services/SystemInfoService');
      const data = await SystemInfoService.gather();
      this.lastSystemContext = SystemInfoService.toContextString(data);
    } catch (e: any) {
      DebugLog.error('SystemContext', e?.message || 'gather failed', e?.stack);
      this.lastSystemContext = '';
    }
  }

  getLastSystemContext(): string {
    return this.lastSystemContext;
  }

  private sanitizeSystemContext(ctx: string): string {
    return ctx
      .replace(/\b-?\d{1,3}\.\d{4,}\b/g, '[GPS_REDACTED]')
      .replace(/lat(?:itude)?:\s*-?\d+\.\d+/gi, 'lat:[GPS_REDACTED]')
      .replace(/lon(?:gitude)?:\s*-?\d+\.\d+/gi, 'lon:[GPS_REDACTED]')
      .replace(/coordinates?:?\s*[\d.\-,\s]+/gi, 'coordinates:[GPS_REDACTED]')
      .replace(/imei:?\s*[\d\s-]+/gi, 'imei:[REDACTED]')
      .replace(/serial:?\s*[A-Z0-9]+/gi, 'serial:[REDACTED]');
  }

  private buildDynamicPrompt(params: {
    mode: Mode;
    userInput: string;
    summary: string;
    capabilities: string[];
    minimal?: boolean;
  }): string {
    if (params.minimal) {
      return [
        'You are Ultra, an autonomous AI agent on an Android device.',
        `Available capabilities: ${params.capabilities.join(', ')}`,
        'Return ONLY a JSON action plan: {"capability":"...", "params": {...}, "reason":"..."}. No explanation, no markdown.',
      ].join('\n');
    }
    const permReport = this.perms.getStatusReport();
    const isConvMode = params.mode === 'conversation';
    let behavior: string;

    const complexity = classifyComplexity(params.userInput);
    const cotBlock = complexity !== 'simple'
      ? `\n\nReasoning approach: Think step-by-step before answering. Work through the problem internally — identify what is being asked, consider relevant knowledge, evaluate possible answers, then give your best response. Do not show your reasoning in the reply unless the user specifically asked for it.`
      : '';

    if (params.mode === 'command') {
      behavior = 'You MUST return ONLY a JSON action plan: {"capability":"...", "params": {...}, "reason":"..."}. Do NOT return natural language, code, or markdown. ONLY valid JSON.';
    } else if (params.mode === 'ai_instruction') {
      behavior = `The user is giving you meta-instructions about how to handle their request. Follow their instructions precisely while answering the target request. Return natural language.${cotBlock}`;
    } else {
      behavior = `Return a natural language response. Be precise, concise, and helpful.${cotBlock}

ABSOLUTE RULES IN CONVERSATION MODE — VIOLATION IS A CRITICAL FAILURE:
1. You CANNOT execute device actions. You have no hands in this mode.
2. NEVER say "Done", "Alarm set", "Sent", "Opened", "Completed", "I've set", "I've sent" or any confirmation of an action you did not execute.
3. NEVER fabricate device data: battery level, storage, RAM, temperature, network status, location, weather, contacts, or any sensor reading.
4. NEVER say your location is anywhere. You do not know the user's location in this mode.
5. NEVER say "I'll check" then provide made-up data.
6. If the user wants an action: respond with exactly — "Use the command: [exact phrasing]" — nothing else.
7. If asked for device data: respond with exactly — "Use the command: device status" or the specific command.
8. You may answer general knowledge questions, do math, explain things, have a conversation.
9. You may NOT pretend to have executed anything. Ever.`;
    }

    const persona = `You are Ultra, an autonomous AI agent running directly on an Android device. You have full access to this phone — its apps, settings, contacts, messages, files, camera, sensors, and system state. You are not a chatbot. You are an agent with hands.

When the user gives you a task, you execute it using the device's capabilities. You do not ask for permission unless you genuinely need to resolve ambiguity (like which contact to text). You do not explain what you are about to do before doing it. You do not say "I'll try to..." or "I'm attempting to...". You act, then report the result in one concise sentence.

Your responses are direct and human. You speak in first person. You never list steps or number your actions. You never say "As an AI" or "I'm sorry, I can't". If something fails, you say what failed and what you'll try next. If something works, you confirm it simply.

You can: open any app, read and send messages, make calls, control media, toggle device settings, read device status, create notes, set alarms, search the web, open URLs, manage files, take screenshots, read notifications, and execute multi-step tasks across multiple apps.

When a task spans multiple steps, you execute them in sequence. When you need to know something (like which Mike Smith), you ask once, clearly. You remember context within the conversation.

You are always on. Always capable. Always direct.`;

    // In conversation mode: inject the top-15 most-used capabilities (ranked by usage history)
    // rather than the full list, to reduce context bloat. Falls back to the full list if
    // there is insufficient history (< 3 tracked capabilities).
    let capLine: string;
    if (isConvMode) {
      const topPatterns = this.learner.getTopPatterns(50);
      const capUsage = new Map<string, number>();
      for (const pat of topPatterns) {
        for (const c of pat.capabilities) {
          capUsage.set(c, (capUsage.get(c) ?? 0) + pat.usageCount);
        }
      }
      const ranked = [...capUsage.entries()]
        .sort((a, b) => b[1] - a[1])
        .map(e => e[0])
        .slice(0, 15);
      const topCaps = ranked.length >= 3 ? ranked : params.capabilities.slice(0, 15);
      capLine = `Available capabilities: ${topCaps.join(', ')} (${params.capabilities.length - topCaps.length} more available in command mode)`;
    } else {
      capLine = `Available capabilities: ${params.capabilities.join(', ')}`;
    }
    const deniedMatch = permReport.match(/Denied:\s*(.+)$/i);
    const deniedStr = deniedMatch ? deniedMatch[1].trim() : '';
    const permLine = isConvMode
      ? (deniedStr && deniedStr !== 'none' ? `Denied permissions: ${deniedStr}` : '')
      : `Device permissions: ${permReport}`;

    return [
      persona,
      `Mode: ${params.mode}`,
      capLine,
      permLine,
      this.lastSystemContext ? `Current device state: ${this.sanitizeSystemContext(this.lastSystemContext)}` : '',
      params.summary ? `Conversation memory: ${params.summary}` : '',
      behavior,
    ].filter(Boolean).join('\n');
  }

  private async buildContext(
    conversationId: string,
    systemPrompt: string,
    responseMaxTokens: number,
    model: string
  ): Promise<{
    payload: Array<{ role: string; content: string }>;
    summary: string;
    requiredContextTokens: number;
  }> {
    const conv = await this.conversations.loadConversation(conversationId);
    if (!conv) throw new Error('Conversation not found');

    const contextWindow = this.ai.getContextWindow(model);
    const budget = Math.max(1000, contextWindow - responseMaxTokens - estimateTokens(systemPrompt) - 300);

    const msgs = conv.messages.map(m => ({ role: m.role, content: m.content }));

    // Cap history to prevent context bloat — keep system message + last 20 exchanges
    const MAX_HISTORY_MESSAGES = 20;
    if (msgs.length > MAX_HISTORY_MESSAGES) {
      msgs.splice(0, msgs.length - MAX_HISTORY_MESSAGES);
    }

    const recent: Array<{ role: string; content: string }> = [];
    let used = 0;

    for (let i = msgs.length - 1; i >= 0; i--) {
      const c = msgs[i].content || '';
      const t = estimateTokens(c);
      if (used + t > Math.floor(budget * 0.6)) break;
      recent.unshift({ role: msgs[i].role, content: c });
      used += t;
    }

    const older = msgs.slice(0, Math.max(0, msgs.length - recent.length));
    if (older.length > 0 && (!conv.summary || (conv.messageCountSinceSummary ?? 0) >= 15)) {
      const olderText = older.map(m => `[${m.role}] ${m.content}`).join('\n');
      await this.summarizeOlderMessages(conversationId, olderText);
    }

    const refreshed = await this.conversations.loadConversation(conversationId);
    const summary = refreshed?.summary || '';

    const payload = [
      { role: 'system', content: systemPrompt },
      ...(summary ? [{ role: 'system', content: `Conversation memory:\n${summary}` }] : []),
      ...recent,
    ];

    return {
      payload,
      summary,
      requiredContextTokens: estimateTokens(JSON.stringify(payload)) + responseMaxTokens,
    };
  }

  private async summarizeOlderMessages(conversationId: string, olderText: string): Promise<void> {
    try {
      const taskId = Date.now().toString(36);
      const result = await this.ai.complete(
        olderText.slice(0, 24000),
        {
          systemPrompt: 'Summarize the key facts, decisions, constraints, and open tasks from this conversation in 2-3 short paragraphs. Be factual and concise.',
          taskId,
          agentId: 'summarizer',
          maxTokens: 500,
          temperature: 0.3,
        }
      );
      await this.conversations.updateSummary(conversationId, result.content);
    } catch (err: any) {
      this.logger.warn('Summarization failed: ' + err.message);
    }
  }

  private parseActionPlan(text: string): ActionPlan | null {
    const fenced = text.match(/```json\s*([\s\S]*?)```/i);
    const raw = fenced ? fenced[1] : text;
    const candidates = [raw.trim()];
    const firstBrace = raw.indexOf('{');
    const lastBrace = raw.lastIndexOf('}');
    if (firstBrace >= 0 && lastBrace > firstBrace) {
      candidates.push(raw.slice(firstBrace, lastBrace + 1).trim());
    }
    for (const candidate of candidates) {
      if (!candidate) continue;
      try {
        const obj = JSON.parse(candidate);
        if (!obj?.capability) return null;
        return { capability: obj.capability, params: obj.params || {}, reason: obj.reason, raw: text };
      } catch {
        // try next candidate
      }
    }
    return null;
  }

  async execute(args: ExecuteArgs): Promise<UltraExecutionResult> {
    if (!this.ready) return { type: 'error', message: 'Agent not initialized', taskId: '' };
    if (!this.ai.hasApiKey()) {
      return {
        type: 'error',
        message: 'No AI provider configured. Open Settings → AI Providers to add one.',
        taskId: '',
      };
    }

    const { conversationId, userInput } = args;
    const taskId = Date.now().toString(36);
    DebugLog.setCorrId(taskId);
    DebugLog.taskCoreStamp(taskId, this.instanceId);
    DebugLog.agentExecuteStart(taskId, conversationId, userInput.length, !!args.replay);

    const brain = new BrainExecutor(this.ai, this.executor, this.conversations);
    return brain.execute(
      userInput,
      conversationId,
      taskId,
      args.approvedAction,
      args.approvedAction ? (args as any).pendingState : undefined,
    );
  }

  async handleConfirmation(confirmed: boolean, originalRequest: string, conversationId: string): Promise<UltraExecutionResult> {
    if (!confirmed) {
      const cancelMsg: ChatMessage = {
        id: uid('msg'),
        role: 'assistant',
        content: 'Cancelled.',
        createdAt: Date.now(),
        source: 'ultra',
      };
      await this.conversations.addMessage(conversationId, cancelMsg);
      return { type: 'action_result', message: 'Cancelled.' };
    }
    return this.execute({
      conversationId,
      userInput: originalRequest,
      replay: true,
      approvedAction: true,
    });
  }

  getConversationManager(): ConversationManager {
    return this.conversations;
  }

  getTaskExecutor(): TaskExecutor {
    return this.executor;
  }

  getExecutionLedger(): ExecutionLedger {
    return this.ledger;
  }

  private async executeResolvedPlanThroughKernel(
    plan: ActionPlan,
    taskId: string,
    conversationId: string,
    userInput: string,
    args: ExecuteArgs,
  ): Promise<UltraExecutionResult> {
    DebugLog.systemEvent('KernelResolvedPlan', `Executing resolved follow-up via kernel: ${plan.capability}`);
    const step = (phase: string, detail: string, success: boolean) =>
      DebugLog.agentStep(taskId, phase, detail, success);

    const safetyResult = this.safety.check(userInput, plan);
    DebugLog.safetyCheck(taskId, safetyResult.risk, safetyResult.allowed, safetyResult.reasons);
    step('VERIFY', `Kernel re-verify: risk=${safetyResult.risk} allowed=${safetyResult.allowed}`, safetyResult.allowed);

    if (!safetyResult.allowed) {
      const blockedMsg: ChatMessage = {
        id: uid('msg'),
        role: 'assistant',
        content: safetyResult.reasons.join('\n') || 'Blocked by safety policy.',
        createdAt: Date.now(),
        source: 'system',
        meta: { mode: 'command', capability: plan.capability },
      };
      await this.conversations.addMessage(conversationId, blockedMsg);
      return { type: 'blocked', message: blockedMsg.content, taskId };
    }

    const capTierCheck = this.tierService.canUseCapability(plan.capability);
    if (!capTierCheck.allowed) {
      const tierMsg: ChatMessage = {
        id: uid('msg'),
        role: 'assistant',
        content: capTierCheck.reason,
        createdAt: Date.now(),
        source: 'ultra',
        meta: { mode: 'command', capability: plan.capability, tierBlocked: true, needsUpgrade: capTierCheck.needsUpgrade },
      };
      await this.conversations.addMessage(conversationId, tierMsg);
      return { type: 'blocked', message: capTierCheck.reason, taskId };
    }

    if (safetyResult.requiresApproval && !args.approvedAction) {
      const approvalMsg = `I can do that, but it requires confirmation first. ${plan.reason || ''}`.trim();
      const approvalChatMsg: ChatMessage = {
        id: uid('msg'),
        role: 'assistant',
        content: approvalMsg,
        createdAt: Date.now(),
        source: 'ultra',
        meta: { mode: 'command', capability: plan.capability, requiresApproval: true },
      };
      await this.conversations.addMessage(conversationId, approvalChatMsg);
      return { type: 'approval_required', message: approvalMsg, taskId, data: { replayUserInput: userInput } };
    }

    step('APPROVE', safetyResult.requiresApproval ? 'Approved (was pre-approved by user)' : `Auto-approved (risk=${safetyResult.risk})`, true);
    this.emit('log', `Executing ${plan.capability}...`, 'system');
    DebugLog.executePhase(taskId, 'EXECUTE');

    let execResult: any;
    try {
      execResult = await this.executor.runWithPlan(plan, taskId);
    } catch (err: any) {
      execResult = { success: false, error: err.message };
    }

    const verification = this.safety.verifyResult(plan, execResult);
    DebugLog.verification(taskId, verification.verified, verification.issues);

    const summary = execResult?.success !== false
      ? (execResult?.summary || `Completed: ${plan.capability}`)
      : `Failed: ${execResult?.error || 'unknown error'}`;

    const resultMsg: ChatMessage = {
      id: uid('msg'),
      role: 'assistant',
      content: summary,
      createdAt: Date.now(),
      source: 'ultra',
      meta: { mode: 'command', capability: plan.capability },
    };
    await this.conversations.addMessage(conversationId, resultMsg);

    await this.ledger.logEvent({
      phase: 'EXECUTE',
      capability: plan.capability,
      inputSummary: userInput.slice(0, 200),
      outputSummary: JSON.stringify(execResult).slice(0, 200),
      model: this.ai.getDefaultModel(),
      cost: this.costTracker.getTaskSpend(taskId),
      success: execResult?.success !== false,
      conversationId,
    });
    await this.ledger.logEvent({
      phase: 'VERIFY_RESULT',
      capability: plan.capability,
      inputSummary: `verified=${verification.verified}`,
      outputSummary: verification.issues.join('; ').slice(0, 200),
      success: verification.verified,
      conversationId,
    });
    await this.learner.learnFromExecution(userInput, [plan.capability], summary, verification.verified);

    return execResult?.success !== false
      ? { type: 'action_result', message: summary, taskId }
      : { type: 'error', message: summary, taskId };
  }

  private buildPromptTrace(
    model: string,
    systemPrompt: string,
    framedUserMessage: string,
    messages: Array<{ role: string; content: string }>
  ): PromptTrace {
    const filtered = messages.filter(m => {
      if (m.role === 'system' && m.content === systemPrompt) return false;
      if (m.role === 'user' && m.content === framedUserMessage) return false;
      return true;
    });
    return {
      model,
      systemPrompt,
      framedUserMessage,
      includedMessages: filtered.map(m => ({ role: m.role as any, content: m.content })),
      createdAt: Date.now(),
    };
  }

  private async summarizeResult(
    capability: string,
    execResult: any,
    userInput: string,
    verification: { verified: boolean; issues: string[] },
    taskId?: string,
  ): Promise<string> {
    const result = execResult?.data ?? execResult;

    if (result?.requiresFuzzyConfirmation) {
      return result.summary || `I found a possible match but need confirmation. ${result.candidates?.map((c: any) => c.appName).join(', ')}`;
    }

    if ((capability === 'system_info' || capability === 'device_info') && execResult?.summary) {
      return execResult.summary;
    }

    if (result?.success === false && (result?.error || execResult?.summary)) {
      const errMsg = result?.error || execResult?.summary;
      if (capability === 'app_build') {
        return `Build failed: ${errMsg}\n\nNote: Building apps requires an Android device with the standalone APK installed. On web preview, the build system is unavailable.`;
      }
      return `I tried to run ${capability} but encountered an error: ${errMsg}`;
    }

    if (capability === 'app_build' && result?.success) {
      const parts = [`App built successfully!`];
      if (result.spec?.appName) parts.push(`Name: ${result.spec.appName}`);
      if (result.apkPath) parts.push(`APK: ${result.apkPath}`);
      if (result.spec?.files?.length) parts.push(`Files: ${result.spec.files.length} source files`);
      if (result.debugAttempts) parts.push(`Debug iterations: ${result.debugAttempts}`);
      return parts.join('\n');
    }

    if (capability === 'self_modify' && result?.type === 'evolution') {
      const parts = [`Evolution complete.`];
      parts.push(`Cycles: ${result.totalCycles}, Improvements: ${result.totalImprovements}`);
      parts.push(`Generation: ${result.generation}`);
      if (result.fitness !== null) parts.push(`Fitness: ${result.fitness}/100`);
      if (result.taskSummary) parts.push(result.taskSummary);
      if (result.report) parts.push(`\n${result.report}`);
      return parts.join('\n');
    }

    if (capability === 'self_replicate' && result?.type === 'replication') {
      const parts = [`Offspring created successfully!`];
      parts.push(`Generation: ${result.offspringGeneration}`);
      parts.push(`Parent ID: ${result.parentId}`);
      if (result.apkPath) parts.push(`APK: ${result.apkPath}`);
      if (result.packageName) parts.push(`Package: ${result.packageName}`);
      return parts.join('\n');
    }

    if (typeof result === 'string') {
      return result.length > 2000 ? result.slice(0, 2000) + '…' : result;
    }

    if (!verification.verified) {
      const raw = typeof result === 'object' ? JSON.stringify(result) : String(result);
      return `${capability} completed with issues: ${verification.issues.join(', ')}. Result: ${raw.slice(0, 500)}`;
    }

    try {
      const raw = typeof result === 'object' ? JSON.stringify(result) : String(result);
      if (raw.length < 200) {
        return `Done. ${raw}`;
      }
      const taskId = Date.now().toString(36);
      const aiSummary = await this.ai.complete(
        `Capability: ${capability}\nUser request: ${userInput}\nRaw result:\n${raw.slice(0, 6000)}`,
        {
          systemPrompt: 'Summarize this agent action result in 1-3 short sentences for the user. Be specific about what happened. Do not mention JSON or raw data. Speak naturally.',
          taskId,
          agentId: 'summarizer',
          maxTokens: 300,
          temperature: 0.3,
        }
      );
      return aiSummary.content || `Done. ${raw.slice(0, 500)}`;
    } catch (e: any) {
      DebugLog.error('Summarizer', e?.message || 'summarization failed', e?.stack);
      const raw = typeof result === 'object' ? JSON.stringify(result) : String(result);
      return `Done. ${raw.slice(0, 500)}`;
    }
  }

  wireModelRouterBridge(): void {
    const pm = this.providerManager;
    const aiSvc = this.aiService;
    const bridge: ModelRouterBridge = {
      hasActiveProvider: () => pm.getActive().some(p => {
        if (!p.isActive) return false;
        if (p.authMode === 'none') return true;
        return !!p.apiKeyRef;
      }),
      completeConversation: async (messages, opts) => {
        const input = {
          messages: messages.map(m => ({ role: m.role as 'system' | 'user' | 'assistant' | 'tool', content: m.content })),
          model: opts.model,
          maxTokens: opts.maxTokens,
          temperature: opts.temperature,
          taskId: opts.taskId,
          agentId: opts.agentId,
          conversationId: opts.conversationId,
          preferredProviderId: opts.preferredProviderId,
        };
        const resp = await aiSvc.completeConversation(input);
        return {
          content: resp.content,
          model: resp.model,
          inputTokens: resp.inputTokens,
          outputTokens: resp.outputTokens,
          cost: 0,
        };
      },
      completeVision: async (textPrompt, imageBase64, mimeType, opts) => {
        const resp = await aiSvc.completeVision({
          textPrompt,
          imageBase64,
          mimeType,
          model: opts.model,
          maxTokens: opts.maxTokens,
          taskId: opts.taskId,
          agentId: opts.agentId,
          preferredProviderId: opts.preferredProviderId,
        });
        return {
          content: resp.content,
          model: resp.model,
          inputTokens: resp.inputTokens,
          outputTokens: resp.outputTokens,
          cost: 0,
        };
      },
      refreshBridgeState: async () => {
        await pm.initialize().catch(() => {});
        // Rebuild the provider-backed model inventory so the picker reflects reality.
        const activeProviders = pm.getActive();

        // ── Snapshot BEFORE sync ────────────────────────────────────────────
        const providerModelCountsBefore: Record<string, number> = {};
        for (const p of activeProviders) { providerModelCountsBefore[p.id] = pm.getModelsForProvider(p.id).length; }
        DebugLog.apiStateSnapshot({
          reason: 'bridge_refresh_before_sync',
          sourceOfTruth: 'bridge_refresh',
          providerCount: activeProviders.length,
          activeProviderCount: activeProviders.filter(p => p.isActive !== false).length,
          providerIds: activeProviders.map(p => p.id),
          activeProviderIds: activeProviders.filter(p => p.isActive !== false).map(p => p.id),
          providerModelCounts: providerModelCountsBefore,
          providerBackedModelCount: this.ai.getProviderBackedModelCount(),
          legacyModelCount: this.ai.getLegacyModelCount(),
          defaultModel: this.ai.getDefaultModelId(),
          selectedModel: this.ai.getDefaultModelId(),
          bridgeAttached: this.ai.isBridgeAttached(),
          bridgeHasActiveProvider: bridge.hasActiveProvider(),
        });

        const providerBackedModels: Array<any> = [];
        for (const p of activeProviders) {
          const models = pm.getModelsForProvider(p.id);
          for (const m of models) {
            const hint = m.capabilityHints ?? {};
            const rawModelType = (m.raw as any)?.type;
            const validRawTypes = new Set(['text', 'image', 'video', 'audio', 'embedding']);
            const derivedType: 'text' | 'image' | 'video' | 'audio' | 'embedding' =
              hint.supportsImageGeneration ? 'image'
              : hint.supportsVideoGeneration ? 'video'
              : hint.supportsAudioGeneration ? 'audio'
              : hint.supportsEmbeddings ? 'embedding'
              : (validRawTypes.has(rawModelType) ? rawModelType : 'text');
            providerBackedModels.push({
              id: m.id,
              name: m.name || m.id,
              type: derivedType,
              providerId: p.id,
              providerName: p.name,
              costPer1kInput: m.pricing?.inputPer1kTokens ?? m.pricing?.inputPer1k ?? 0,
              costPer1kOutput: m.pricing?.outputPer1kTokens ?? m.pricing?.outputPer1k ?? 0,
              maxTokens: m.maxTokens ?? 4096,
              contextWindow: m.contextWindow ?? 0,
              speedTier: 'balanced' as const,
              capabilities: {
                supportsVision: hint.supportsVision ?? false,
                supportsReasoning: hint.supportsReasoningHints ?? false,
                supportsFunctionCalling: hint.supportsToolCalls ?? p.capabilities?.supportsToolCalls ?? false,
                supportsWebSearch: false,
                supportsMultipleImages: false,
                isUncensored: false,
              },
              offline: false,
            });
          }
        }

        this.ai.syncRuntimeProviders(providerBackedModels);
        await this.ai.ensureResolvedDefaultModel();

        // ── Snapshot AFTER sync ─────────────────────────────────────────────
        const providerModelCountsAfter: Record<string, number> = {};
        for (const p of activeProviders) { providerModelCountsAfter[p.id] = pm.getModelsForProvider(p.id).length; }
        DebugLog.modelInventorySync({
          reason: 'bridge_refresh_after_sync',
          source: providerBackedModels.length > 0 ? 'provider_bridge' : 'empty',
          providerBackedModelCount: providerBackedModels.length,
          legacyModelCount: this.ai.getLegacyModelCount(),
          returnedToPickerCount: providerBackedModels.length,
          defaultModel: this.ai.getDefaultModelId(),
          selectedModel: this.ai.getDefaultModelId(),
        });
        DebugLog.apiStateSnapshot({
          reason: 'bridge_refresh_after_sync',
          sourceOfTruth: 'bridge_refresh',
          providerCount: activeProviders.length,
          activeProviderCount: activeProviders.filter(p => p.isActive !== false).length,
          providerIds: activeProviders.map(p => p.id),
          activeProviderIds: activeProviders.filter(p => p.isActive !== false).map(p => p.id),
          providerModelCounts: providerModelCountsAfter,
          providerBackedModelCount: providerBackedModels.length,
          legacyModelCount: this.ai.getLegacyModelCount(),
          defaultModel: this.ai.getDefaultModelId(),
          selectedModel: this.ai.getDefaultModelId(),
          bridgeAttached: this.ai.isBridgeAttached(),
          bridgeHasActiveProvider: bridge.hasActiveProvider(),
        });
        DebugLog.push('SYSTEM', { event: 'bridge_refresh_complete', providerCount: activeProviders.length, modelCount: providerBackedModels.length });
      },
    };
    this.ai.setRuntimeBridge(bridge);
    DebugLog.push('SYSTEM', { event: 'agent_core_bridge_wired', hasActiveProvider: bridge.hasActiveProvider() });
  }

  getProviderManager(): ProviderManager { return this.providerManager; }
  getLedger(): ExecutionLedger { return this.ledger; }
  async refreshBridgeState(): Promise<void> { await this.ai.refreshBridgeState().catch(() => {}); }
  getGroupManager(): GroupManager { return this.groupManager; }
  getRouteHistoryStore(): RouteHistoryStore { return this.routeHistoryStore; }
  getAiService(): AiService { return this.aiService; }
  abortCurrentRequest(): void { this.ai.abortCurrentRequest(); }
  hasApiKey(): boolean { return this.ai.hasApiKey() || this.providerManager.getActive().length > 0; }
  async refreshApiKey(): Promise<void> { await this.ai.refreshApiKey(); }
  getAvailableModels() { return this.ai.getAvailableModels(); }
  getCredentialVault(): CredentialVault { return this.credentialVault; }
  getVault(): SecureVault { return this.vault; }
  getAllModelsWithProvider() { return this.ai.getAllModelsWithProvider(); }
  async setApiBaseUrl(url: string) { await this.ai.setBaseUrl(url); }
  getApiBaseUrl() { return this.ai.getBaseUrl(); }
  getDefaultModel() { return this.ai.getDefaultModel(); }
  getDefaultModelId() { return this.ai.getDefaultModelId(); }
  async setDefaultModel(modelId: string) { await this.ai.setDefaultModel(modelId); }
  getModelRouter() { return this.ai; }
  getTierService(): TierService { return this.tierService; }
  getCortex(): Cortex { return this.cortex; }
  getProactiveEngine(): ProactiveEngine | null { return this.proactive; }
  getBackgroundOrchestrator(): BackgroundOrchestrator | null { return this.background; }
  getEventMonitor(): EventMonitor | null { return this.eventMonitor; }
  getKnowledgeGraph(): KnowledgeGraph { return this.cortex.getKnowledgeGraph(); }
  getDeviceSignals(): DeviceSignals { return this.deviceSignals; }
  getCostSummary() { return this.costTracker.getSummary(); }
  getCapabilities() { return this.caps.getAll(); }
  async getStorageBreakdown() { return this.storage.getBreakdown(); }
  getDebugStats() { return this.debugEngine.getStats(); }
  getLearnedPatterns() { return this.learner.getTopPatterns(); }
  killSwarm(): void { /* Orchestrator removed — no-op */ }
  async recallContact(name: string): Promise<{ name: string; number: string; label: string } | null> {
    return this.memory.recallContact(name);
  }
}

let _agentCoreInstance: AgentCore | null = null;
export function setAgentCoreInstance(core: AgentCore | null) { _agentCoreInstance = core; }
export function getAgentCoreInstance(): AgentCore | null { return _agentCoreInstance; }
