import { z } from "zod";
import { AgentStore } from "../agents.js";
import { AdminError } from "../skills-admin.js";
import type { Tool } from "./index.js";

/**
 * The agent-definition tools: Jarvis can save, list and delete named
 * subagent definitions (src/agents.ts). Saving and deleting change what
 * future delegations do, so both always show the owner a card; saving also
 * lasts beyond the request. Starting a saved agent is spawn_agent's
 * optional `agent` field (agent/orchestrator.ts), not a tool here.
 *
 * index.ts imports this file (to list these tools in ALL_TOOLS), so the
 * catalogue is fetched with a dynamic import at run time; a static import
 * back would make the load order matter.
 */

function tool<S extends z.ZodType>(t: Tool<S>): Tool {
  return t as unknown as Tool;
}

/**
 * Tools a subagent never gets (mirrors Runtime.delegateTools: only the main
 * agent drains the owner's notifications). The orchestration four are not
 * in ALL_TOOLS at all.
 */
export const NOT_FOR_SUBAGENTS: ReadonlySet<string> = new Set(["read_notifications"]);

/** The catalogue a definition may pick from, out of a full tool list. */
export function subagentCatalog<T extends { name: string }>(all: readonly T[]): T[] {
  return all.filter((t) => !NOT_FOR_SUBAGENTS.has(t.name));
}

/** The tool names a definition may list. */
export async function subagentToolNames(): Promise<string[]> {
  const { ALL_TOOLS } = await import("./index.js");
  return subagentCatalog(ALL_TOOLS).map((t) => t.name);
}

async function store(home: string): Promise<AgentStore> {
  const known = await subagentToolNames();
  return new AgentStore(home, () => known);
}

function plain(err: unknown): never {
  if (err instanceof AdminError) throw new Error(err.message);
  throw err;
}

export const createAgentTool = tool({
  name: "create_agent",
  description:
    "Save a reusable subagent definition the owner can run later (spawn_agent's `agent` field starts it). " +
    "Stored as ~/.jarvis/agents/<name>/AGENT.md. `tools` limits what it may use (empty = the normal subagent tools; " +
    "it can never get more than a normal subagent). Use replace=true to overwrite an existing one.",
  schema: z.object({
    name: z.string().min(1).max(64).describe("Lowercase letters, digits and hyphens, e.g. \"price-checker\""),
    description: z.string().min(1).max(1024).describe("One line: what this agent is for"),
    tools: z.array(z.string()).optional().describe("Tool names it may use; omit for the default subagent tools"),
    instructions: z.string().min(1).describe("Its standing instructions (the body of AGENT.md)"),
    replace: z.boolean().optional().describe("Overwrite an existing definition of the same name"),
  }),
  policy: { effects: ["mutate"], lastsBeyondRequest: true, alwaysConfirm: true },
  async run({ name, description, tools, instructions, replace }, ctx) {
    try {
      const saved = (await store(ctx.home)).save(name, { description, tools: tools ?? [], instructions }, replace ? "upsert" : "create");
      const toolLine = saved.tools.length ? saved.tools.filter((t) => !saved.unknownTools.includes(t)).join(", ") || "(none usable)" : "the default subagent tools";
      const unknown = saved.unknownTools.length
        ? `\nNot subagent tools, kept in the file but never granted: ${saved.unknownTools.join(", ")}.`
        : "";
      return `Saved agent "${saved.name}" at ${saved.location}.\nTools: ${toolLine}.${unknown}`;
    } catch (err) {
      plain(err);
    }
  },
});

export const listAgentsTool = tool({
  name: "list_agents",
  description: "List the saved subagent definitions (name, description, tools). Start one with spawn_agent's `agent` field.",
  schema: z.object({}),
  policy: { effects: ["read"] },
  parallelSafe: true,
  async run(_a, ctx) {
    const all = (await store(ctx.home)).list();
    if (!all.length) return "No saved agents yet — create_agent saves one.";
    return all.map((a) => {
      const tools = a.tools.length ? a.tools.join(", ") : "default subagent tools";
      const unknown = a.unknownTools.length ? ` (unknown, not granted: ${a.unknownTools.join(", ")})` : "";
      return `- ${a.name}: ${a.description} [tools: ${tools}${unknown}]`;
    }).join("\n");
  },
});

export const deleteAgentTool = tool({
  name: "delete_agent",
  description: "Delete a saved subagent definition. Its folder is moved aside under ~/.jarvis/deleted/agents, not erased.",
  schema: z.object({ name: z.string().min(1).max(64) }),
  policy: { effects: ["mutate"], alwaysConfirm: true },
  async run({ name }, ctx) {
    try {
      const { movedTo } = (await store(ctx.home)).delete(name);
      return `Deleted agent "${name}" (moved to ${movedTo}).`;
    } catch (err) {
      plain(err);
    }
  },
});

export const AGENT_DEF_TOOLS: Tool[] = [createAgentTool, listAgentsTool, deleteAgentTool];
