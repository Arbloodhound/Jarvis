import { randomBytes } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import path from "node:path";

/**
 * Everything Jarvis keeps lives in one folder (default ~/.jarvis, or
 * JARVIS_HOME): config.json, the access token, memory, the audit log, the
 * transcript, and the workspace the agent may write to without asking.
 */

export type Brain = "local" | "cloud";

export interface Config {
  /** Which brain answers: "local" (a model server on this computer) or "cloud" (Claude). */
  brain: Brain;
  /** When true, nothing leaves the computer: no cloud brain, no web tools. */
  offlineOnly: boolean;
  cloud: {
    model: string;
    effort: "low" | "medium" | "high" | "xhigh" | "max";
    /** Read from ANTHROPIC_API_KEY when empty. */
    apiKey: string;
  };
  local: {
    /** Any OpenAI-compatible server: Ollama, llama.cpp server, LM Studio, vLLM. */
    baseUrl: string;
    model: string;
    /**
     * The model's context window per conversation, in tokens (llama.cpp:
     * -c / -np). Absent or 0 = ask the server, else learn it from its first
     * "too long" refusal. Near it, older turns are compressed into a summary.
     */
    contextTokens?: number;
  };
  /**
   * The prediction layer: a Timestral forecasting server (forecast/server.py).
   * Empty = not set up (the forecast tool says so); series are tracked anyway.
   */
  forecast: { baseUrl: string };
  embeddings: {
    /** On = semantic memory. Off = word-overlap recall (no server needed). */
    enabled: boolean;
    /** Embedding server; empty means "use the local brain's baseUrl". */
    baseUrl: string;
    model: string;
    /** Keep this many dimensions (Matryoshka truncation); 0 = the model's full size. */
    dims: number;
  };
  vision: {
    /**
     * The owner's one switch for every image read. On (the default), vision
     * is part of the model: the look tools exist and shopping checks read
     * listing photos on their own. Off, no code path reaches a vision
     * backend and the look tools are withheld. There is no per-call opt-out.
     */
    enabled: boolean;
    /** Local vision model id (OpenAI-compatible server). The cloud brain reads images on its own. */
    baseUrl: string;
    model: string;
  };
  voice: {
    /**
     * Speech-to-text server (OpenAI-compatible /audio/transcriptions —
     * a whisper.cpp / faster-whisper server the owner runs). Empty = not
     * set up, and transcribe_audio says so. Network-shaped: with
     * offlineOnly on, only a loopback address is used.
     */
    sttBaseUrl: string;
    /** The transcription model name the server expects. */
    sttModel: string;
    /** The speak tool (the OS's own text-to-speech voice). On by default. */
    ttsEnabled: boolean;
  };
  /** Synthetic keyboard/mouse. Off by default; experimental; every action still needs a card. */
  screenControl: { enabled: boolean };
  tor: { enabled: boolean; socksPort: number };
  nli: {
    /** The NLI gate on final answers (deterministic contradiction check against this run's tool results). */
    enabled: boolean;
    /**
     * Also escalate deterministic passes to the configured local model for a
     * strict entailment check. One-directional: the model can only turn a
     * pass into a fail, never rescue a deterministic fail.
     */
    modelCheck: boolean;
  };
  memory: {
    /**
     * Echolalia verify-before-store: when true, the remember tool holds new
     * facts in the pending store until they are verified (source check +
     * echo, or owner confirmation) instead of writing them straight to
     * durable memory. Off by default: remember writes directly, as before.
     */
    verifyBeforeStore: boolean;
  };
  /**
   * Inbound notifications (the read_notifications tool). A notification
   * reaches the agent only from an allowed app, outside quiet hours, and
   * under the per-app burst limit (the three-gate filter in
   * notifications/filter.ts); Jarvis's own notifications are always
   * excluded. Dropped notifications are recorded in the audit log.
   * Defaults are the phone filter's: quiet 22:00–07:00, 5 per minute per app.
   */
  notifications: {
    allowedApps: string[];
    quietStart: number;
    quietEnd: number;
    maxPerMinutePerApp: number;
  };
  skills: {
    /**
     * Allowlist of skill names a session may see. Empty (the default) means
     * every discovered skill is eligible — the Python SkillStore semantics
     * (`set(allowlist) if allowlist else None`).
     */
    allow: string[];
    /** Extra skill roots, scanned after the app-home and workspace skills dirs, in listed order. */
    roots: string[];
  };
  /**
   * Subagents: how many delegated agents (spawn_agent) may run at once.
   * They share this app's one model endpoint — each is a conversation, not
   * another model load — so the cap is about attention, not memory.
   */
  subagents: { maxConcurrent: number };
  port: number;
  writableRoots: string[];
  /**
   * Extra commands that run without a confirm card, matched as exact text.
   * The built-in read-only list (ls, pwd, df, du…) is in gate.ts and can't be widened here.
   */
  extraAllowedCommands: string[];
  maxTurns: number;
}

export interface Paths {
  home: string;
  skills: string;
  config: string;
  token: string;
  memory: string;
  /** Echolalia pending store (verify-before-store candidates). */
  pending: string;
  audit: string;
  transcript: string;
  conversations: string;
  workspace: string;
  /** Inbox file drop for inbound notifications: one JSON notification per line, drained by the runtime. */
  notificationsInbox: string;
  jobs: string;
  /** Number series tracked for forecasts. */
  series: string;
}

/** Problems found while loading files, shown to the owner once the app is up. */
export const startupWarnings: string[] = [];

export function paths(home = process.env.JARVIS_HOME || path.join(homedir(), ".jarvis")): Paths {
  return {
    home,
    skills: path.join(home, "skills"),
    config: path.join(home, "config.json"),
    token: path.join(home, "token"),
    memory: path.join(home, "memory.json"),
    pending: path.join(home, "memory-pending.json"),
    audit: path.join(home, "audit.jsonl"),
    transcript: path.join(home, "transcript.jsonl"),
    conversations: path.join(home, "conversations.json"),
    workspace: path.join(home, "workspace"),
    notificationsInbox: path.join(home, "notifications-inbox.jsonl"),
    jobs: path.join(home, "jobs.json"),
    series: path.join(home, "series.json"),
  };
}

export function defaults(p: Paths): Config {
  return {
    brain: "local",
    offlineOnly: false,
    cloud: { model: "claude-opus-5-5", effort: "medium", apiKey: "" },
    local: { baseUrl: "http://127.0.0.1:11434/v1", model: "qwen3:8b" },
    forecast: { baseUrl: "" },
    embeddings: { enabled: false, baseUrl: "", model: "embeddinggemma-2", dims: 256 },
    vision: { enabled: true, baseUrl: "", model: "qwen3-vl" },
    voice: { sttBaseUrl: "", sttModel: "whisper-1", ttsEnabled: true },
    screenControl: { enabled: false },
    tor: { enabled: false, socksPort: 9050 },
    nli: { enabled: true, modelCheck: false },
    memory: { verifyBeforeStore: false },
    notifications: { allowedApps: [], quietStart: 22, quietEnd: 7, maxPerMinutePerApp: 5 },
    skills: { allow: [], roots: [] },
    subagents: { maxConcurrent: 3 },
    port: 7337,
    writableRoots: [p.workspace],
    extraAllowedCommands: [],
    maxTurns: 16,
  };
}

/** Load config.json over the defaults. Unknown keys are kept so a newer file still loads. */
export function loadConfig(p: Paths = paths()): Config {
  mkdirSync(p.workspace, { recursive: true });
  const base = defaults(p);
  if (!existsSync(p.config)) {
    writeFileSync(p.config, JSON.stringify(base, null, 2) + "\n", { mode: 0o600 });
    return base;
  }
  let raw: Partial<Config> & { allowCommands?: unknown };
  try {
    raw = JSON.parse(readFileSync(p.config, "utf8")) as Partial<Config>;
  } catch (err) {
    // A broken file must not stop Jarvis from starting: keep it for the owner, start from defaults.
    const aside = `${p.config}.broken-${Date.now()}`;
    renameSync(p.config, aside);
    startupWarnings.push(`config.json could not be read (${err instanceof Error ? err.message : String(err)}). It was moved to ${aside} and the defaults are in use.`);
    writeFileSync(p.config, JSON.stringify(base, null, 2) + "\n", { mode: 0o600 });
    return base;
  }
  // Older configs carried a prefix allowlist ("git status" …) that let flags through; it is ignored now.
  delete raw.allowCommands;
  return {
    ...base,
    ...raw,
    cloud: { ...base.cloud, ...(raw.cloud ?? {}) },
    local: { ...base.local, ...(raw.local ?? {}) },
    forecast: { ...base.forecast, ...(raw.forecast ?? {}) },
    embeddings: { ...base.embeddings, ...(raw.embeddings ?? {}) },
    vision: { ...base.vision, ...(raw.vision ?? {}) },
    voice: { ...base.voice, ...(raw.voice ?? {}) },
    screenControl: { ...base.screenControl, ...(raw.screenControl ?? {}) },
    tor: { ...base.tor, ...(raw.tor ?? {}) },
    nli: { ...base.nli, ...(raw.nli ?? {}) },
    memory: { ...base.memory, ...(raw.memory ?? {}) },
    // Validated field by field: a null or string allowedApps would break state() or become a Set of characters.
    notifications: {
      allowedApps: Array.isArray(raw.notifications?.allowedApps)
        ? raw.notifications.allowedApps.filter((s): s is string => typeof s === "string")
        : base.notifications.allowedApps,
      quietStart: isHour(raw.notifications?.quietStart) ? raw.notifications.quietStart : base.notifications.quietStart,
      quietEnd: isHour(raw.notifications?.quietEnd) ? raw.notifications.quietEnd : base.notifications.quietEnd,
      maxPerMinutePerApp: isPositiveInt(raw.notifications?.maxPerMinutePerApp)
        ? raw.notifications.maxPerMinutePerApp
        : base.notifications.maxPerMinutePerApp,
    },
    skills: {
      allow: Array.isArray(raw.skills?.allow) ? raw.skills.allow.filter((s): s is string => typeof s === "string") : base.skills.allow,
      roots: Array.isArray(raw.skills?.roots) ? raw.skills.roots.filter((s): s is string => typeof s === "string") : base.skills.roots,
    },
    subagents: { ...base.subagents, ...(raw.subagents ?? {}) },
  };
}

const isHour = (v: unknown): v is number => Number.isInteger(v) && (v as number) >= 0 && (v as number) <= 23;
const isPositiveInt = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) > 0;

export function saveConfig(cfg: Config, p: Paths = paths()): void {
  writeFileSync(p.config, JSON.stringify(cfg, null, 2) + "\n", { mode: 0o600 });
}

/** The token every gateway client must present. Created once, readable only by the owner. */
export function accessToken(p: Paths = paths()): string {
  if (existsSync(p.token)) return readFileSync(p.token, "utf8").trim();
  const t = randomBytes(24).toString("base64url");
  writeFileSync(p.token, t + "\n", { mode: 0o600 });
  try { chmodSync(p.token, 0o600); } catch { /* Windows: ACLs, not modes */ }
  return t;
}
