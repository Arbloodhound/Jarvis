import { Agent } from "./agent/loop.js";
import { type Config, type Paths, saveConfig } from "./config.js";
import { AuditLog } from "./gate/audit.js";
import { Gate } from "./gate/gate.js";
import { Embedder } from "./embeddings.js";
import { Memory } from "./memory.js";
import { ClaudeProvider } from "./providers/claude.js";
import { LocalProvider } from "./providers/local.js";
import type { Provider } from "./providers/types.js";
import { toolsFor } from "./tools/index.js";

/**
 * Wires config, memory, gate, brains and tools into one Agent. Shared by the
 * gateway server and the terminal CLI. Brain and offline-only are read on
 * every run, so a settings change applies to the next request.
 */
export class Runtime {
  readonly agent: Agent;
  readonly memory: Memory;

  constructor(readonly cfg: Config, readonly p: Paths, cwd = process.cwd()) {
    this.memory = new Memory(p.memory);
    this.agent = new Agent({
      provider: () => this.provider(),
      embedder: () => this.embedder(),
      tools: () => toolsFor(this.cfg.offlineOnly),
      gate: new Gate({ writableRoots: cfg.writableRoots, extraAllowedCommands: cfg.extraAllowedCommands, protectedPaths: [p.token, p.config] }, cwd),
      memory: this.memory,
      audit: new AuditLog(p.audit),
      home: p.home,
      workspace: p.workspace,
      cwd,
      maxTurns: cfg.maxTurns,
    });
  }

  /** Cloud answers only when chosen AND offline-only is off (same rule as the phone's NetPolicy). */
  get cloudOn(): boolean {
    return this.cfg.brain === "cloud" && !this.cfg.offlineOnly;
  }

  provider(): Provider {
    if (this.cloudOn) {
      return new ClaudeProvider(this.cfg.cloud.model, this.cfg.cloud.effort, this.cfg.cloud.apiKey || undefined);
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

  update(change: { brain?: Config["brain"]; offlineOnly?: boolean; embeddings?: boolean }): void {
    if (change.brain === "cloud" || change.brain === "local") this.cfg.brain = change.brain;
    if (typeof change.offlineOnly === "boolean") this.cfg.offlineOnly = change.offlineOnly;
    if (typeof change.embeddings === "boolean") this.cfg.embeddings.enabled = change.embeddings;
    saveConfig(this.cfg, this.p);
  }

  state() {
    return {
      brain: this.cfg.brain,
      offlineOnly: this.cfg.offlineOnly,
      answering: this.provider().label,
      busy: this.agent.busy,
      cloudModel: this.cfg.cloud.model,
      localModel: this.cfg.local.model,
      embeddings: this.cfg.embeddings.enabled,
      embedderId: this.embedder()?.id ?? null,
    };
  }
}
