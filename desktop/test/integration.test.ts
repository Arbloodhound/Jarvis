import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { Agent, type AgentEvent, type RunHooks } from "../src/agent/loop.ts";
import { AuditLog } from "../src/gate/audit.ts";
import { Gate } from "../src/gate/gate.ts";
import { Memory } from "../src/memory.ts";
import type { Provider, ToolCall, TurnResult } from "../src/providers/types.ts";
import { ALL_TOOLS } from "../src/tools/index.ts";
import { useNative } from "../src/tools/platform.ts";
import type { Embedder } from "../src/embeddings.ts";
import type { Vision } from "../src/vision.ts";

// Fake OS so clipboard/screenshot tools work headless in the test.
let clip = "";
useNative({
  async openUrl() {},
  async clipboardRead() { return clip; },
  async clipboardWrite(t) { clip = t; },
  async notify() {},
  async screenshot() { return Buffer.from("PNGDATA"); },
});

const stubVision = { label: "stub", async describe(_i: unknown, q: string) { return `vision saw: ${q}`; } } as unknown as Vision;
const stubEmbedder = { id: "stub@4", async embed(xs: string[]) { return xs.map(() => [1, 0, 0, 0]); }, async embedOne() { return [1, 0, 0, 0]; } } as unknown as Embedder;

function makeAgent(home: string, kind: "cloud" | "local", calls: ToolCall[]): { agent: Agent; events: AgentEvent[] } {
  const results: TurnResult[] = [
    { text: "", stop: "tool_use", toolCalls: calls },
    { text: "All done.", stop: "end", toolCalls: [] },
  ];
  let i = 0;
  const provider: Provider = { kind, label: kind, async turn() { return results[Math.min(i++, results.length - 1)]!; } };
  const events: AgentEvent[] = [];
  const agent = new Agent({
    provider: () => provider,
    embedder: () => stubEmbedder,
    vision: () => stubVision,
    tools: () => ALL_TOOLS,
    gate: new Gate({ writableRoots: [home], extraAllowedCommands: [] }, home),
    memory: new Memory(path.join(home, "mem.json")),
    audit: new AuditLog(null),
    home, workspace: home, cwd: home, maxTurns: 4,
  });
  return { agent, events };
}

const hooks = (events: AgentEvent[], allow = true): RunHooks => ({ emit: (e) => events.push(e), confirm: async () => allow });

const toolSuite: ToolCall[] = [
  { id: "1", name: "system_info", input: {} },
  { id: "2", name: "write_file", input: { path: "note.md", content: "hello" } },
  { id: "3", name: "remember", input: { fact: "the owner likes tea" } },
  { id: "4", name: "recall", input: { query: "tea" } },
  { id: "5", name: "look_at_screen", input: { question: "what's up" } },
  { id: "6", name: "clipboard_write", input: { text: "copied text" } },
];

for (const kind of ["local", "cloud"] as const) {
  test(`every core tool runs on the ${kind} brain, through the gate`, async () => {
    const home = mkdtempSync(path.join(tmpdir(), `jarvis-int-${kind}-`));
    const { agent, events } = makeAgent(home, kind, toolSuite);
    const answer = await agent.run("do my things", hooks(events));
    assert.equal(answer, "All done.");
    const ends = events.filter((e) => e.type === "tool_end") as Array<Extract<AgentEvent, { type: "tool_end" }>>;
    for (const name of ["system_info", "write_file", "remember", "recall", "look_at_screen", "clipboard_write"]) {
      const e = ends.find((x) => x.name === name);
      assert.ok(e?.ok, `${name} should succeed on ${kind} (${e?.summary})`);
    }
    assert.equal(readFileSync(path.join(home, "note.md"), "utf8"), "hello");
    assert.equal(clip, "copied text");
    assert.ok(ends.find((x) => x.name === "look_at_screen")!.summary.includes("vision saw"));
    assert.ok(ends.find((x) => x.name === "recall")!.summary.includes("tea"));
  });
}

test("the gate still protects on the cloud brain: sensitive read is carded, decline = tool error", async () => {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-int-gate-"));
  const { agent, events } = makeAgent(home, "cloud", [{ id: "1", name: "read_file", input: { path: "~/.ssh/id_rsa" } }]);
  let carded = false;
  await agent.run("read my ssh key", { emit: (e) => events.push(e), confirm: async () => { carded = true; return false; } });
  assert.ok(carded, "a sensitive read should raise a confirm card");
  const end = events.find((e) => e.type === "tool_end") as Extract<AgentEvent, { type: "tool_end" }>;
  assert.equal(end.ok, false);
});
