import { Agent, type AgentDeps, type RunHooks, type RunResult } from "./agent/loop.js";
import { Orchestrator, formatHandoff, subagentTools, type ChildHandle, type ChildRun, type Handoff } from "./agent/orchestrator.js";
import { type Config, type Paths, saveConfig } from "./config.js";
import { Conversations } from "./conversations.js";
import { ownerContext, type Attachment } from "./context.js";
import { AuditLog } from "./gate/audit.js";
import { Gate } from "./gate/gate.js";
import { createLocalModelChecker } from "./gate/nli.js";
import { Embedder } from "./embeddings.js";
import { EcholaliaStore } from "./echolalia.js";
import { Vision } from "./vision.js";
import { isLoopbackUrl, Transcriber } from "./voice.js";
import { Memory } from "./memory.js";
import { Forecaster, SeriesStore } from "./forecast.js";
import { SkillStore, skillRoots } from "./skills.js";
import { ClaudeProvider } from "./providers/claude.js";
import { LocalProvider } from "./providers/local.js";
import type { Provider } from "./providers/types.js";
import { JobStore, Scheduler, seedDefaultHarnessJobs, type Job, type RunnerResult } from "./scheduler.js";
import { makeWebFetch, probeTorProxy, toolsFor, type Tool } from "./tools/index.js";
import { NotificationFilter, type NotificationInput } from "./notifications/filter.js";
import { NotificationInbox, type IngestResult } from "./notifications/inbox.js";

/**
 * The owner's live channel: the run hooks every subagent reports and asks
 * through, plus two notices a front-end may take — a hand-off was queued
 * (it reaches the main conversation on the main agent's next step), and
 * the busy state changed outside the front-end's own run (a scheduled run
 * started or ended), so a Stop control can follow it.
 */
export interface OwnerChannel extends RunHooks {
  handoff?: (h: Handoff) => void;
  stateChanged?: () => void;
}

/**
 * Wires config, memory, gate, brains and tools into one Agent. Shared by the
 * gateway server and the terminal CLI. Brain and offline-only are read on
 * every run, so a settings change applies to the next request.
 */
export class Runtime {
  readonly agent: Agent;
  /** The subagent coordinator. Children are separate Agent instances built
   * from the same deps — same brain endpoint, gate, memory and vision. */
  readonly orchestrator: Orchestrator;
  readonly memory: Memory;
  /** Number series tracked for forecasts (~/.jarvis/series.json). */
  readonly series: SeriesStore;
  /** Conversation branches; the agent's live turns are the active branch's working copy. */
  readonly conversations: Conversations;
  readonly jobs: JobStore;
  readonly scheduler: Scheduler;
  private readonly gate: Gate;
  private readonly cwd: string;
  readonly audit: AuditLog;
  /** Echolalia pending store; always exists for inspection, routes remember only when verifyBeforeStore is on. */
  readonly pending: EcholaliaStore;
  /**
   * Inbound notification intelligence: the three-gate filter + queue every
   * intake feeds (gateway endpoint, inbox file drop) and the
   * read_notifications tool drains. Built from cfg.notifications at startup;
   * a config change to the allowlist or quiet hours applies on restart,
   * like the gate's writable roots.
   */
  readonly notifications: NotificationInbox;
  /**
   * The owner's live channel — the gateway's broadcast + card machinery, or
   * the terminal's — registered by whichever front-end is running. Subagents
   * report and ask through it; with none registered, a subagent's card is
   * declined, the same answer an unanswered card gets.
   */
  private channel: OwnerChannel | null = null;
  /** Scheduled runs in flight: Stop and quit reach them, and they count as busy. */
  private readonly scheduledRuns = new Set<Agent>();

  constructor(readonly cfg: Config, readonly p: Paths, cwd = process.cwd()) {
    this.cwd = cwd;
    this.memory = new Memory(p.memory);
    this.series = new SeriesStore(p.series);
    this.audit = new AuditLog(p.audit);
    this.pending = new EcholaliaStore(p.pending, this.memory, (entry, decision) => {
      this.audit.write({ tool: "echolalia", decision, id: entry.id, claim: entry.claim, source: entry.source, echoes: entry.echoes, reasons: entry.reasons });
    });
    this.notifications = new NotificationInbox({
      filter: new NotificationFilter({
        allowedApps: cfg.notifications.allowedApps,
        quietStart: cfg.notifications.quietStart,
        quietEnd: cfg.notifications.quietEnd,
        maxPerMinutePerApp: cfg.notifications.maxPerMinutePerApp,
      }),
      audit: new AuditLog(p.audit),
      inboxFile: p.notificationsInbox,
    });
    this.gate = new Gate({ writableRoots: cfg.writableRoots, extraAllowedCommands: cfg.extraAllowedCommands, protectedPaths: [p.token, p.config] }, cwd);
    this.orchestrator = new Orchestrator({
      maxConcurrent: () => this.cfg.subagents.maxConcurrent,
      start: (h) => this.startChild(h),
      // The owner hears of a hand-off when it lands, not only when the
      // main agent next reads its inbox.
      onHandoff: (h) => this.channel?.handoff?.(h),
    });
    this.agent = this.newAgent(
      () => [...this.baseTools(), ...subagentTools(this.orchestrator)],
      // Finished subagents hand their results into this conversation.
      { inbox: () => this.orchestrator.drainHandoffs().map(formatHandoff) },
    );
    // Resume the active conversation branch, so a restart (or the CLI after
    // the gateway) continues the same conversation the owner left.
    this.conversations = new Conversations(p.conversations);
    const saved = this.conversations.activeTurns();
    if (saved.length) this.agent.loadTurns(saved);
    this.jobs = new JobStore(p.jobs);
    this.scheduler = new Scheduler({
      store: this.jobs,
      audit: this.audit,
      cwd,
      runJob: (job) => this.runScheduledJob(job),
    });
    // First init only (seedDefaultHarnessJobs no-ops when jobs.json
    // already exists): the phone bug was default jobs never being seeded.
    seedDefaultHarnessJobs(this.jobs, Date.now());
  }

  /**
   * The tools a delegated run gets (subagents and scheduled jobs): the shared
   * set minus read_notifications, which drains the owner's inbox — only the
   * main agent, talking to the owner, reads notifications.
   */
  private delegateTools(): Tool[] {
    return this.baseTools().filter((t) => t.name !== "read_notifications");
  }

  /** The tools every agent here shares (the coordinator's four are added only at the top level). */
  private baseTools(): Tool[] {
    return toolsFor({ offlineOnly: this.cfg.offlineOnly, screenControl: this.cfg.screenControl.enabled, vision: this.cfg.vision.enabled, cloudVision: this.vision()?.kind === "cloud", tor: { enabled: this.cfg.tor.enabled, socksPort: this.cfg.tor.socksPort }, ttsEnabled: this.cfg.voice.ttsEnabled, notifications: this.notifications });
  }

  /**
   * One agent wired to this runtime's gate, memory, audit and brains. The
   * top agent, every subagent and every scheduled run differ only in tools,
   * inbox and standing orders; the defaults (shared tools, no orchestration)
   * are what a scheduled run gets.
   */
  private newAgent(tools: () => Tool[] = () => this.delegateTools(), extra: Partial<AgentDeps> = {}): Agent {
    return new Agent({
      provider: () => this.provider(),
      embedder: () => this.embedder(),
      vision: () => this.vision(),
      visionEnabled: () => this.cfg.vision.enabled,
      transcriber: () => this.transcriber(),
      skills: () => this.skillStore(),
      tools,
      gate: this.gate,
      memory: this.memory,
      echolalia: () => (this.cfg.memory.verifyBeforeStore ? this.pending : null),
      audit: this.audit,
      home: this.p.home,
      workspace: this.p.workspace,
      cwd: this.cwd,
      maxTurns: this.cfg.maxTurns,
      series: () => this.series,
      forecaster: () => (this.cfg.forecast.baseUrl ? new Forecaster(this.cfg.forecast.baseUrl) : null),
      contextWindow: () => (this.cfg.brain === "local" ? this.cfg.local.contextTokens ?? 0 : 0),
      nli: {
        enabled: this.cfg.nli.enabled,
        // The model-backed layer runs on the configured LOCAL provider only,
        // and only when explicitly turned on; deterministic-only otherwise.
        modelCheck: this.cfg.nli.modelCheck ? createLocalModelChecker({ baseUrl: this.cfg.local.baseUrl, model: this.cfg.local.model }) : null,
      },
      ...extra,
    });
  }

  /**
   * Run one subagent: its own Agent (no orchestration tools — depth stops
   * at 1), the orchestrator's inbox as its steering channel, and the
   * owner's live channel for its events and cards. A card a subagent raises
   * keeps its own text and gains "(via subagent …)", so the owner always
   * knows whose action they are approving. Every event and card it sends
   * is tagged with the child's id and name, so a front-end can keep it
   * apart from the main agent's reply. A failed run rejects `done`, so the
   * child hands off FAILED rather than DONE with the error as its answer.
   */
  private startChild(h: ChildHandle): ChildRun {
    const tag = { id: h.id, name: h.name };
    const agent = this.newAgent(() => this.delegateTools(), {
      inbox: () => h.drainInbox().map((m) => `[Message from your coordinator]\n${m}`),
      systemSuffix: "\n\nYou are a subagent working for the main Jarvis agent, which delegated this task to you. Do only this task. " +
        "You share the owner's gate, tools and memory, but you cannot spawn subagents. " +
        "Your final answer is your hand-off: make it complete, concrete and self-contained, because the main agent gets only that.",
    });
    const hooks: RunHooks = {
      emit: (e) => {
        if (e.type === "tool_start") h.report(`running ${e.name}`);
        else if (e.type === "done") h.report("finished");
        this.channel?.emit({ ...e, agent: tag });
      },
      confirm: async (card) => {
        if (!this.channel) return false;
        return this.channel.confirm({ ...card, reason: `${card.reason} (via subagent "${h.name}")`, agent: tag });
      },
    };
    const done = agent.runResult(h.task, hooks).then((r) => {
      if (r.status === "failed") throw new Error(r.error ?? r.text);
      return r.text;
    });
    return { done, stop: () => agent.stop() };
  }

  /** Register (or clear) the owner's live channel — see the field note. */
  setChannel(channel: OwnerChannel | null): void {
    this.channel = channel;
  }

  /** The owner's Stop: the current run, every running subagent and every running scheduled job. */
  stopAll(): void {
    this.agent.stop();
    this.orchestrator.stopAll();
    this.stopScheduledRuns();
  }

  /** Stop every scheduled run in flight (part of Stop; the app also calls it on quit). */
  stopScheduledRuns(): void {
    for (const a of this.scheduledRuns) a.stop();
  }

  /**
   * For a front-end about to exit (`jarvis ask`): wait up to `timeoutMs`
   * for the running subagents to settle, then stop whichever are still
   * running and wait briefly for their stopped hand-offs — so no child keeps
   * working, unobserved, after the owner's terminal is gone. Hand-offs go
   * out through the channel as usual. Returns how many were running and
   * the names of those it had to stop.
   */
  async settleChildren(timeoutMs: number): Promise<{ waited: number; stopped: string[] }> {
    const running = () => this.orchestrator.list().filter((c) => c.state === "running");
    const waited = running().length;
    if (!waited) return { waited, stopped: [] };
    const within = (ms: number) => {
      let timer: NodeJS.Timeout | undefined;
      return Promise.race([
        this.orchestrator.idle().then(() => true),
        new Promise<boolean>((r) => { timer = setTimeout(() => r(false), ms); }),
      ]).finally(() => clearTimeout(timer));
    };
    if (await within(timeoutMs)) return { waited, stopped: [] };
    const stopped = running().map((c) => c.name);
    this.orchestrator.stopAll();
    await within(5_000);
    return { waited, stopped };
  }

  /** Save the agent's live turns back into the active branch. Call after every run and edit. */
  syncConversation(): void {
    this.conversations.setActiveTurns(this.agent.turns);
  }

  /**
   * The owner-provided context for one message — @-references resolved and
   * attachments read — as a block that rides the turn ("" when the message
   * carries neither). Fetches go through the web tools' own transport, so
   * Tor routing applies and offline-only refuses.
   */
  async ownerContext(text: string, attachments: Attachment[] = []): Promise<string> {
    return ownerContext(text, attachments, {
      cwd: this.cwd,
      vision: this.vision(),
      webFetch: this.cfg.offlineOnly ? null : makeWebFetch({ enabled: this.cfg.tor.enabled, socksPort: this.cfg.tor.socksPort }),
      signal: AbortSignal.timeout(60_000),
    });
  }

  /**
   * The session's skills, discovered fresh: roots in precedence order —
   * the app-home skills dir, the workspace skills dir, then config-listed
   * extra roots. The config allowlist governs eligibility; empty means all
   * (Python SkillStore semantics). Discovery is metadata-only; bodies load
   * on demand through the skill tools.
   */
  skillStore(): SkillStore {
    const store = new SkillStore(this.cfg.skills.allow);
    store.discover(skillRoots(this.p.home, this.p.workspace, this.cfg.skills.roots));
    return store;
  }

  /**
   * How a scheduled job runs: through a fresh agent run, headlessly, with
   * the gate fully active. No user interface is attached, so a confirm
   * card is declined (the piped-stdin rule) and that decline is written
   * to the audit log with the job's id and owner — a scheduled job never
   * gains silent privileges. The harness's own jobs are safe internal
   * checks in this build (heartbeat / memory status), not model runs.
   *
   * A run that failed or was stopped throws, so the scheduler records it
   * failed (with the reason) instead of "ran" with the error as its detail.
   * The run is tracked while it works: the owner's Stop and the app's quit
   * reach it, and state().busy counts it.
   */
  async runScheduledJob(job: Job): Promise<RunnerResult> {
    if (job.owner === "harness") {
      if (job.id === "harness:heartbeat") return { detail: `heartbeat ok at ${new Date().toISOString()}` };
      if (job.id === "harness:memory-upkeep") return { detail: `memory upkeep check: ${this.memory.all().length} fact(s) stored; nothing to consolidate in this build` };
      return { detail: `harness status check ok: ${job.id}` };
    }
    const agent = this.newAgent();
    let confirmDeclined = false;
    let runId = "";
    const hooks: RunHooks = {
      emit: (e) => { if (e.type === "run_start") runId = e.runId; },
      confirm: async (card) => {
        confirmDeclined = true;
        this.audit.write({ event: "scheduler_confirm_declined", jobId: job.id, owner: job.owner, runId, tool: card.tool, rule: card.rule, detail: "no user interface attached to a scheduled run" });
        return false;
      },
    };
    this.scheduledRuns.add(agent);
    this.channel?.stateChanged?.();
    let r: RunResult;
    try {
      r = await agent.runResult(job.task, hooks);
    } finally {
      this.scheduledRuns.delete(agent);
      this.channel?.stateChanged?.();
    }
    if (r.status === "failed") throw new Error(r.error ?? r.text);
    if (r.status === "stopped") throw new Error("Stopped by the owner before it finished.");
    return { detail: r.text, confirmDeclined };
  }

  /** Cloud answers only when chosen AND offline-only is off (same rule as the phone's NetPolicy). */
  get cloudOn(): boolean {
    return this.cfg.brain === "cloud" && !this.cfg.offlineOnly;
  }

  /**
   * The cloud key: config first, then the ANTHROPIC_API_KEY the Anthropic
   * SDK itself falls back to (config documents apiKey as "read from
   * ANTHROPIC_API_KEY when empty"). Every cloud-availability decision must
   * use this rather than cfg.cloud.apiKey alone — an env-only key powers
   * the cloud brain, so a decision made on the config field alone (vision
   * did) silently misroutes to a local server and 404s.
   */
  private cloudKey(): string | undefined {
    return this.cfg.cloud.apiKey || process.env.ANTHROPIC_API_KEY || undefined;
  }

  provider(): Provider {
    if (this.cloudOn) {
      return new ClaudeProvider(this.cfg.cloud.model, this.cfg.cloud.effort, this.cloudKey());
    }
    return new LocalProvider(this.cfg.local.baseUrl, this.cfg.local.model);
  }

  /** The embedder for semantic memory, or null when it's off or blocked offline. */
  embedder(): Embedder | null {
    const e = this.cfg.embeddings;
    if (!e.enabled) return null;
    const baseUrl = e.baseUrl || this.cfg.local.baseUrl;
    // An embedder that isn't on this computer is egress; offline-only forbids it.
    if (this.cfg.offlineOnly && !/^https?:\/\/(127\.0\.0\.1|localhost|\[::1\])/i.test(baseUrl)) return null;
    return new Embedder({ enabled: true, baseUrl, model: e.model, dims: e.dims });
  }

  /**
   * The vision backend: a configured local VLM if one is set, otherwise the
   * cloud brain when it's on. null when offline-only with no local VLM.
   */
  vision(): Vision | null {
    // The owner's switch: off means no backend exists, for any caller —
    // the look tools, @image context and shopping checks all see null.
    if (!this.cfg.vision.enabled) return null;
    const v = this.cfg.vision;
    const localUrl = v.baseUrl || this.cfg.local.baseUrl;
    // Prefer a dedicated local VLM so it works offline and on the local brain.
    if (v.model && (this.cfg.offlineOnly || this.cfg.brain === "local" || !this.cloudKey())) {
      return new Vision({ kind: "local", baseUrl: localUrl, model: v.model });
    }
    if (this.cloudOn) {
      return new Vision({ kind: "cloud", baseUrl: "", model: this.cfg.cloud.model, apiKey: this.cloudKey() });
    }
    if (v.model) return new Vision({ kind: "local", baseUrl: localUrl, model: v.model });
    return null;
  }

  /**
   * The speech-to-text backend, or null when none is configured. The STT
   * endpoint is network-shaped, so — same loopback rule as the embedder —
   * offline-only withholds a non-loopback server; a whisper server on this
   * computer keeps working offline.
   */
  transcriber(): Transcriber | null {
    const v = this.cfg.voice;
    if (!v.sttBaseUrl) return null;
    if (this.cfg.offlineOnly && !isLoopbackUrl(v.sttBaseUrl)) return null;
    return new Transcriber({ baseUrl: v.sttBaseUrl, model: v.sttModel });
  }

  /** One inbound notification through the filter choke point (the gateway endpoint calls this). */
  ingestNotification(input: NotificationInput, source: "gateway" | "inbox_file" | "direct" = "gateway"): IngestResult {
    return this.notifications.ingest(input, source);
  }

  /** Drain the inbox file drop (notifications-inbox.jsonl) through the same choke point. */
  drainNotificationInbox(): IngestResult[] {
    return this.notifications.drainInboxFile();
  }

  /** The model ids a server has, for the picker. Defaults to the local brain's server. */
  async listModels(baseUrl?: string): Promise<string[]> {
    return LocalProvider.listModels(baseUrl || this.cfg.local.baseUrl);
  }

  /** Set the local or cloud model from the picker. */
  setModel(which: "local" | "cloud", model: string): void {
    if (!model) return;
    if (which === "local") this.cfg.local.model = model;
    else this.cfg.cloud.model = model;
    saveConfig(this.cfg, this.p);
  }

  /** Last Tor proxy probe: true = a proxy answered, false = none did, null = not probed (or Tor off). */
  private torReachable: boolean | null = null;

  /** Probe the configured Tor proxy once and remember the answer for state(). */
  async refreshTorStatus(): Promise<boolean> {
    if (!this.cfg.tor.enabled) { this.torReachable = null; return false; }
    this.torReachable = await probeTorProxy(this.cfg.tor.socksPort);
    return this.torReachable;
  }

  update(change: { brain?: Config["brain"]; offlineOnly?: boolean; embeddings?: boolean; screenControl?: boolean; tor?: boolean; verifyBeforeStore?: boolean; vision?: boolean }): void {
    if (change.brain === "cloud" || change.brain === "local") this.cfg.brain = change.brain;
    if (typeof change.offlineOnly === "boolean") this.cfg.offlineOnly = change.offlineOnly;
    if (typeof change.embeddings === "boolean") this.cfg.embeddings.enabled = change.embeddings;
    if (typeof change.vision === "boolean") this.cfg.vision.enabled = change.vision;
    if (typeof change.screenControl === "boolean") this.cfg.screenControl.enabled = change.screenControl;
    if (typeof change.tor === "boolean") {
      this.cfg.tor.enabled = change.tor;
      this.torReachable = null;
      if (change.tor) void this.refreshTorStatus();
    }
    if (typeof change.verifyBeforeStore === "boolean") this.cfg.memory.verifyBeforeStore = change.verifyBeforeStore;
    saveConfig(this.cfg, this.p);
  }

  state() {
    return {
      brain: this.cfg.brain,
      offlineOnly: this.cfg.offlineOnly,
      answering: this.provider().label,
      /** The main agent or a scheduled run is working (what Stop would stop). */
      busy: this.agent.busy || this.scheduledRuns.size > 0,
      scheduledRunning: this.scheduledRuns.size,
      agents: this.orchestrator.list(),
      cloudModel: this.cfg.cloud.model,
      /** Where the cloud key comes from — never the key itself. */
      cloudKeySource: this.cfg.cloud.apiKey ? "config" : process.env.ANTHROPIC_API_KEY ? "env" : "none",
      localModel: this.cfg.local.model,
      embeddings: this.cfg.embeddings.enabled,
      /** The last run's router decision (tier + mode + reason), or null before the first run. */
      lastRoute: this.agent.lastRoute,
      verifyBeforeStore: this.cfg.memory.verifyBeforeStore,
      pendingMemory: this.pending.list("pending").length,
      quarantinedMemory: this.pending.list("quarantined").length,
      embedderId: this.embedder()?.id ?? null,
      vision: this.vision()?.label ?? null,
      voiceStt: this.transcriber()?.label ?? null,
      voiceTts: this.cfg.voice.ttsEnabled,
      visionEnabled: this.cfg.vision.enabled,
      screenControl: this.cfg.screenControl.enabled,
      tor: this.cfg.tor.enabled,
      torPort: this.cfg.tor.socksPort,
      torReachable: this.torReachable,
      nli: this.cfg.nli.enabled,
      nliModelCheck: this.cfg.nli.enabled && this.cfg.nli.modelCheck,
      notificationsAllowedApps: this.cfg.notifications.allowedApps.length,
      notificationsPending: this.notifications.pendingCount,
      skills: this.skillStore().list().length,
      scheduler: this.scheduler.status(),
      jobs: this.jobs.load().map((j) => ({ id: j.id, owner: j.owner, kind: j.kind, schedule: j.schedule, task: j.task, enabled: j.enabled, lastRun: j.lastRun, nextRun: j.nextRun })),
    };
  }
}
