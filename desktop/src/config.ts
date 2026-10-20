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
  };
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
    /** Local vision model id (OpenAI-compatible server). The cloud brain reads images on its own. */
    baseUrl: string;
    model: string;
  };
  /** Synthetic keyboard/mouse. Off by default; experimental; every action still needs a card. */
  screenControl: { enabled: boolean };
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
  config: string;
  token: string;
  memory: string;
  audit: string;
  transcript: string;
  workspace: string;
}

/** Problems found while loading files, shown to the owner once the app is up. */
export const startupWarnings: string[] = [];

export function paths(home = process.env.JARVIS_HOME || path.join(homedir(), ".jarvis")): Paths {
  return {
    home,
    config: path.join(home, "config.json"),
    token: path.join(home, "token"),
    memory: path.join(home, "memory.json"),
    audit: path.join(home, "audit.jsonl"),
    transcript: path.join(home, "transcript.jsonl"),
    workspace: path.join(home, "workspace"),
  };
}

export function defaults(p: Paths): Config {
  return {
    brain: "local",
    offlineOnly: false,
    cloud: { model: "claude-opus-5-5", effort: "medium", apiKey: "" },
    local: { baseUrl: "http://127.0.0.1:11434/v1", model: "qwen3:8b" },
    embeddings: { enabled: false, baseUrl: "", model: "embeddinggemma-2", dims: 256 },
    vision: { baseUrl: "", model: "qwen3-vl" },
    screenControl: { enabled: false },
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
    embeddings: { ...base.embeddings, ...(raw.embeddings ?? {}) },
    vision: { ...base.vision, ...(raw.vision ?? {}) },
    screenControl: { ...base.screenControl, ...(raw.screenControl ?? {}) },
  };
}

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
