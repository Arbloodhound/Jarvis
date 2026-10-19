import { Agent } from "./agent/loop.js";
import { type Config, type Paths, saveConfig } from "./config.js";
import { AuditLog } from "./gate/audit.js";
import { Gate } from "./gate/gate.js";
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

  update(change: { brain?: Config["brain"]; offlineOnly?: boolean }): void {
    if (change.brain === "cloud" || change.brain === "local") this.cfg.brain = change.brain;
    if (typeof change.offlineOnly === "boolean") this.cfg.offlineOnly = change.offlineOnly;
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
    };
  }
}
