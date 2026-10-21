import { Agent } from "./agent/loop.js";
import { type Config, type Paths, saveConfig } from "./config.js";
import { Conversations } from "./conversations.js";
import { ownerContext, type Attachment } from "./context.js";
import { AuditLog } from "./gate/audit.js";
import { Gate } from "./gate/gate.js";
import { Embedder } from "./embeddings.js";
import { Vision } from "./vision.js";
import { Memory } from "./memory.js";
import { ClaudeProvider } from "./providers/claude.js";
import { LocalProvider } from "./providers/local.js";
import type { Provider } from "./providers/types.js";
import { makeWebFetch, probeTorProxy, toolsFor } from "./tools/index.js";

/**
 * Wires config, memory, gate, brains and tools into one Agent. Shared by the
 * gateway server and the terminal CLI. Brain and offline-only are read on
 * every run, so a settings change applies to the next request.
 */
export class Runtime {
  readonly agent: Agent;
  readonly memory: Memory;
  /** Conversation branches; the agent's live turns are the active branch's working copy. */
  readonly conversations: Conversations;
  private readonly cwd: string;

  constructor(readonly cfg: Config, readonly p: Paths, cwd = process.cwd()) {
    this.cwd = cwd;
    this.memory = new Memory(p.memory);
    this.agent = new Agent({
      provider: () => this.provider(),
      embedder: () => this.embedder(),
      vision: () => this.vision(),
      visionEnabled: () => this.cfg.vision.enabled,
      tools: () => toolsFor({ offlineOnly: this.cfg.offlineOnly, screenControl: this.cfg.screenControl.enabled, vision: this.cfg.vision.enabled, cloudVision: this.vision()?.kind === "cloud", tor: { enabled: this.cfg.tor.enabled, socksPort: this.cfg.tor.socksPort } }),
      gate: new Gate({ writableRoots: cfg.writableRoots, extraAllowedCommands: cfg.extraAllowedCommands, protectedPaths: [p.token, p.config] }, cwd),
      memory: this.memory,
      audit: new AuditLog(p.audit),
      home: p.home,
      workspace: p.workspace,
      cwd,
      maxTurns: cfg.maxTurns,
    });
    // Resume the active conversation branch, so a restart (or the CLI after
    // the gateway) continues the same conversation the owner left.
    this.conversations = new Conversations(p.conversations);
    const saved = this.conversations.activeTurns();
    if (saved.length) this.agent.loadTurns(saved);
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

  update(change: { brain?: Config["brain"]; offlineOnly?: boolean; embeddings?: boolean; screenControl?: boolean; tor?: boolean; vision?: boolean }): void {
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
    saveConfig(this.cfg, this.p);
  }

  state() {
    return {
      brain: this.cfg.brain,
      offlineOnly: this.cfg.offlineOnly,
      answering: this.provider().label,
      busy: this.agent.busy,
      cloudModel: this.cfg.cloud.model,
      /** Where the cloud key comes from — never the key itself. */
      cloudKeySource: this.cfg.cloud.apiKey ? "config" : process.env.ANTHROPIC_API_KEY ? "env" : "none",
      localModel: this.cfg.local.model,
      embeddings: this.cfg.embeddings.enabled,
      embedderId: this.embedder()?.id ?? null,
      vision: this.vision()?.label ?? null,
      visionEnabled: this.cfg.vision.enabled,
      screenControl: this.cfg.screenControl.enabled,
      tor: this.cfg.tor.enabled,
      torPort: this.cfg.tor.socksPort,
      torReachable: this.torReachable,
    };
  }
}
