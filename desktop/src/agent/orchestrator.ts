import { randomUUID } from "node:crypto";
import { z } from "zod";
import type { Tool } from "../tools/index.js";

/**
 * Subagents: delegated work, ported from the phone harness (§4 of its
 * design). The main agent is the coordinator — it spawns children, steers
 * them, and gets each result back as a hand-off. The shape mirrors the
 * phone's SubagentOrchestrator:
 *
 * - A child runs its OWN agent loop (built by the `start` callback the
 *   runtime supplies) against the same providers, gate, tools, memory and
 *   vision as the parent — a conversation on the shared model endpoint,
 *   never a second model load.
 * - Depth stops at 1 by construction: children are built without the
 *   orchestration tools, and spawn() itself refuses a depth ≥ 1 call.
 * - Lifecycle: spawn → running → done | failed | stopped. When a child
 *   settles, its hand-off is queued BEFORE its state flips, so a child
 *   listed as finished has always handed its result over. A stopped child
 *   hands off too, labeled stopped — never silence. (The phone also has an
 *   interrupt/resume pair; the desktop tool surface has only stop, so
 *   stop is terminal here.)
 * - Steering is an inbox the child's loop drains between steps (and once
 *   more before it finishes); steering a child that is not running goes
 *   nowhere and says so, and a message that still arrived too late to be
 *   read is named in the hand-off.
 * - Hand-offs are unverified by default and say so, like the phone's: the
 *   coordinator reads and judges the result itself.
 *
 * Stopping the parent stops every running child (Runtime.stopAll) — a
 * child never outlives the Stop that was meant to halt the work.
 */

export type ChildState = "running" | "done" | "failed" | "stopped";
export type HandoffState = "done" | "failed" | "stopped";

export interface ChildInfo {
  id: string;
  name: string;
  task: string;
  state: ChildState;
  startedAt: number;
  lastActivityAt: number;
  /** What it did most recently, in a few words. */
  note: string;
}

export interface Handoff {
  id: string;
  name: string;
  task: string;
  state: HandoffState;
  result?: string;
  error?: string;
  /** The phone verifies a hand-off before the parent uses it; nothing here
   * does yet, so this is false and the note says so, as the phone's does. */
  verified: boolean;
  verifyNote: string;
  /** Steering messages that arrived too late for the child to read. */
  unread?: string[];
}

/** The child's side of the orchestrator: its steering inbox and a progress tap. */
export interface ChildHandle {
  readonly id: string;
  readonly name: string;
  readonly task: string;
  /** The saved agent definition it was started from (src/agents.ts), if any. */
  readonly definition?: AgentSpec;
  /** Steering messages sent since the child's last step, in order. */
  drainInbox(): string[];
  /** Note what the child is doing (shown by agent_status). */
  report(note: string): void;
}

/** A running child, as the runtime's starter returns it. */
export interface ChildRun {
  /** Resolves with the child's final answer (its hand-off text); rejects when its run failed. */
  done: Promise<string>;
  /** Ask the child's loop to stop (its abort signal). */
  stop(): void;
}

export type ChildStarter = (h: ChildHandle) => ChildRun;

/** A saved agent definition as a child needs it: its instructions and tool allowlist ([] = default). */
export interface AgentSpec { name: string; instructions: string; tools: readonly string[] }

interface ChildRec {
  id: string;
  name: string;
  task: string;
  state: ChildState;
  startedAt: number;
  lastActivityAt: number;
  note: string;
  inbox: string[];
  stopRequested: boolean;
  handedOff: boolean;
  run?: ChildRun;
  /** Resolves once the child has settled and handed off. */
  settled?: Promise<void>;
}

function tool<S extends z.ZodType>(t: Tool<S>): Tool {
  return t as unknown as Tool;
}

export class Orchestrator {
  private readonly children = new Map<string, ChildRec>();
  private handoffs: Handoff[] = [];

  constructor(private readonly opts: {
    start: ChildStarter;
    /** Read live, so a settings change applies to the next spawn. */
    maxConcurrent: () => number;
    /** Fired the moment a hand-off is queued (the runtime tells the owner). */
    onHandoff?: (h: Handoff) => void;
    /** Look up a saved agent definition by name (spawn_agent's `agent` field). */
    resolveAgent?: (name: string) => AgentSpec | null;
  }) {}

  /**
   * Start a child on `task`. Throws — so the calling tool surfaces it — when
   * the task is blank, when the concurrency cap is full (spawn refuses; it
   * never queues), or when a subagent tries to spawn (depth cap 1).
   */
  spawn(task: string, name?: string, depth = 0, agent?: string): string {
    if (depth >= 1) throw new Error("Subagents cannot spawn subagents — delegation stops at one level.");
    const brief = task.trim();
    if (!brief) throw new Error("A subagent needs a task.");
    const definition = agent ? this.opts.resolveAgent?.(agent) ?? undefined : undefined;
    if (agent && !definition) throw new Error(`No saved agent named "${agent}" — list_agents shows them.`);
    const running = [...this.children.values()].filter((c) => c.state === "running").length;
    const max = Math.max(1, Math.floor(this.opts.maxConcurrent()) || 1);
    if (running >= max) {
      throw new Error(`Too many subagents running (${running} of at most ${max}). Wait for one to finish, or stop one with stop_agent.`);
    }
    const id = `agent:${randomUUID().slice(0, 8)}`;
    const rec: ChildRec = {
      id,
      name: this.uniqueName(name?.trim() || definition?.name || id),
      task: brief,
      state: "running",
      startedAt: Date.now(),
      lastActivityAt: Date.now(),
      note: "starting",
      inbox: [],
      stopRequested: false,
      handedOff: false,
    };
    this.children.set(id, rec);
    const handle: ChildHandle = {
      id,
      name: rec.name,
      task: brief,
      definition,
      drainInbox: () => rec.inbox.splice(0),
      report: (note) => { rec.note = note; rec.lastActivityAt = Date.now(); },
    };
    let run: ChildRun;
    try {
      run = this.opts.start(handle);
    } catch (err) {
      this.settle(rec, "failed", undefined, err instanceof Error ? err.message : String(err));
      return id;
    }
    rec.run = run;
    rec.settled = run.done.then(
      (text) => this.settle(rec, rec.stopRequested ? "stopped" : "done", text, undefined),
      (err) => this.settle(rec, rec.stopRequested ? "stopped" : "failed", undefined, err instanceof Error ? err.message : String(err)),
    );
    return id;
  }

  /** Queue a steering message for a running child. False when there is no such running child. */
  steer(idOrName: string, message: string): boolean {
    const rec = this.resolve(idOrName);
    const msg = message.trim();
    if (!rec || rec.state !== "running" || !msg) return false;
    rec.inbox.push(msg);
    rec.lastActivityAt = Date.now();
    return true;
  }

  /**
   * Stop one running child for good. Its loop is aborted; when it settles,
   * a hand-off labeled stopped is delivered. False when it is not running.
   */
  stop(idOrName: string): boolean {
    const rec = this.resolve(idOrName);
    if (!rec || rec.state !== "running") return false;
    rec.stopRequested = true;
    rec.run?.stop();
    return true;
  }

  /** Stop every running child (the owner's Stop stops the whole tree). */
  stopAll(): void {
    for (const rec of this.children.values()) {
      if (rec.state === "running") {
        rec.stopRequested = true;
        rec.run?.stop();
      }
    }
  }

  /** Resolves once every child running now has settled (and so handed off). */
  async idle(): Promise<void> {
    await Promise.all([...this.children.values()].filter((c) => c.state === "running").map((c) => c.settled));
  }

  /** Every child, oldest first — finished ones stay listed with their state. */
  list(): ChildInfo[] {
    return [...this.children.values()]
      .sort((a, b) => a.startedAt - b.startedAt)
      .map((c) => ({
        id: c.id, name: c.name, task: c.task, state: c.state,
        startedAt: c.startedAt, lastActivityAt: c.lastActivityAt, note: c.note,
      }));
  }

  /** Hand-offs not yet delivered into the parent's conversation, in the order the children settled. */
  drainHandoffs(): Handoff[] {
    return this.handoffs.splice(0);
  }

  private settle(rec: ChildRec, state: HandoffState, result: string | undefined, error: string | undefined): void {
    if (rec.handedOff) return;
    rec.handedOff = true;
    const unread = rec.inbox.splice(0);
    const h: Handoff = {
      id: rec.id, name: rec.name, task: rec.task, state,
      result, error,
      verified: false, verifyNote: "not verified",
      ...(unread.length ? { unread } : {}),
    };
    // The hand-off exists before the state flips: a child listed as done,
    // failed or stopped has always handed its result over (phone semantics).
    this.handoffs.push(h);
    rec.state = state;
    rec.lastActivityAt = Date.now();
    rec.note = state === "done" ? "finished" : state;
    this.opts.onHandoff?.(h);
  }

  private resolve(idOrName: string): ChildRec | undefined {
    const byId = this.children.get(idOrName);
    if (byId) return byId;
    const byName = [...this.children.values()].filter((c) => c.name === idOrName);
    return byName.length === 1 ? byName[0] : undefined;
  }

  private uniqueName(base: string): string {
    const taken = new Set([...this.children.values()].map((c) => c.name));
    if (!taken.has(base)) return base;
    for (let n = 2; ; n++) {
      const candidate = `${base}-${n}`;
      if (!taken.has(candidate)) return candidate;
    }
  }
}

/** A hand-off as it enters the parent's conversation: labeled, with its task and verdict. */
export function formatHandoff(h: Handoff): string {
  const head = `[Hand-off from subagent "${h.name}" (${h.id}) — task: "${h.task}" — ${h.state.toUpperCase()} (${h.verifyNote})]`;
  const late = h.unread?.length
    ? `\n(It finished before reading ${h.unread.length} steering message(s): ${h.unread.map((m) => JSON.stringify(m)).join("; ")})`
    : "";
  if (h.state === "done") return `${head}\n${h.result ?? ""}${late}`;
  if (h.state === "failed") return `${head}\nIt failed: ${h.error ?? "unknown error"}${late}`;
  return `${head}\nIt was stopped before finishing.${h.result && h.result !== "Stopped." ? ` Its last answer was: ${h.result}` : ""}${late}`;
}

function ago(ms: number): string {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 5) return "just now";
  if (s < 60) return `${s}s ago`;
  return `${Math.floor(s / 60)}m ago`;
}

/** The four orchestration tools. Only the coordinator (top-level agent) gets them. */
export function subagentTools(orch: Orchestrator): Tool[] {
  return [
    tool({
      name: "spawn_agent",
      description:
        "Delegate a self-contained task to a subagent: your own agent loop with the same tools, gate and memory, " +
        "working in the background while you continue. Brief it completely — it starts fresh and knows only what " +
        "the task says (plus shared memory). Its result comes back into this conversation as a hand-off message. " +
        "Subagents cannot spawn subagents, and only a few may run at once.",
      schema: z.object({
        task: z.string().min(1).describe("The subagent's full brief"),
        name: z.string().min(1).max(40).optional().describe("A short name, e.g. \"prices\" — used in status, cards and hand-offs"),
        agent: z.string().min(1).max(64).optional().describe("A saved agent definition to run (list_agents): it adds that agent's instructions and limits its tools"),
      }),
      // Delegating work is far-reaching: the owner approves each distinct
      // delegation on a card (the phone registry's approval-once for agent.spawn).
      policy: { effects: ["mutate"], alwaysConfirm: true },
      async run({ task, name, agent }) {
        const id = orch.spawn(task, name, 0, agent);
        const info = orch.list().find((c) => c.id === id)!;
        return `Spawned subagent "${info.name}" (${id}) for: ${task}\nIt works in the background; its result returns here as a hand-off message. agent_status shows it meanwhile.`;
      },
    }),
    tool({
      name: "agent_status",
      description: "List your subagents: id, name, task, state (running / done / failed / stopped) and last activity.",
      schema: z.object({}),
      policy: { effects: ["read"] },
      parallelSafe: true,
      async run() {
        const all = orch.list();
        if (!all.length) return "No subagents yet — spawn_agent starts one.";
        return all.map((c) => `${c.id} "${c.name}" [${c.state}] ${c.task} — last activity ${ago(c.lastActivityAt)}${c.note ? ` · ${c.note}` : ""}`).join("\n");
      },
    }),
    tool({
      name: "steer_agent",
      description: "Send a message to a running subagent — a correction, extra context, a change of direction. It reads the message at its next step.",
      schema: z.object({
        agent: z.string().min(1).describe("The subagent's id or name"),
        message: z.string().min(1).describe("What to tell it"),
      }),
      policy: { effects: ["mutate"] },
      async run({ agent, message }) {
        return orch.steer(agent, message)
          ? `Sent to ${agent}; it will read it at its next step.`
          : `No running subagent matches "${agent}" — nothing was sent. agent_status lists them.`;
      },
    }),
    tool({
      name: "stop_agent",
      description: "Stop a running subagent for good. It hands back a note that it was stopped.",
      schema: z.object({
        agent: z.string().min(1).describe("The subagent's id or name"),
      }),
      policy: { effects: ["mutate"] },
      async run({ agent }) {
        return orch.stop(agent)
          ? `Stopping ${agent}.`
          : `No running subagent matches "${agent}" — nothing to stop.`;
      },
    }),
  ];
}
