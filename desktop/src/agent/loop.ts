import { randomUUID } from "node:crypto";
import { platform } from "node:os";
import type { AuditLog } from "../gate/audit.js";
import { Episode, Gate, type Rule } from "../gate/gate.js";
import { checkClaimWithModel, extractCheckableClaims, type ModelChecker, type NliVerdict } from "../gate/nli.js";
import { redactSecrets } from "../gate/secrets.js";
import type { Memory } from "../memory.js";
import type { Provider, ToolCall, ToolResult, Turn } from "../providers/types.js";
import type { Embedder } from "../embeddings.js";
import type { SkillStore } from "../skills.js";
import type { Vision } from "../vision.js";
import type { Transcriber } from "../voice.js";
import { jsonSchema, type Tool, type ToolContext } from "../tools/index.js";
import { fastCommand } from "./fast.js";
import { requestContext, systemPrompt } from "./prompt.js";
import { routeRequest, type Route, type RouteChannel } from "./router.js";

/**
 * One agent: a conversation, a brain, tools, and the gate between them.
 *
 * A run: fast command if the request is an obvious one-shot; otherwise
 * stream a model turn, run its tool calls (each through the gate, with a
 * confirm card when the gate asks), feed all results back in one message,
 * repeat until the model answers or the turn cap is hit. Read-only calls run
 * in parallel; anything with a side effect runs one at a time, in order, so
 * cards appear one by one. Stop aborts the model stream and every running tool.
 */

export type AgentEvent =
  | { type: "run_start"; runId: string; brain: string; route: Route }
  | { type: "text"; runId: string; delta: string }
  | { type: "thinking"; runId: string; delta: string }
  | { type: "tool_start"; runId: string; id: string; name: string; args: Record<string, unknown> }
  | { type: "tool_end"; runId: string; id: string; name: string; ok: boolean; summary: string }
  | { type: "confirm"; runId: string; cardId: string; tool: string; args: Record<string, unknown>; rule: Rule; reason: string }
  | { type: "confirm_resolved"; runId: string; cardId: string; allowed: boolean }
  | { type: "warning"; runId: string; text: string }
  | { type: "done"; runId: string; text: string; stopped?: boolean }
  | { type: "error"; runId: string; message: string };

export interface AgentDeps {
  provider: () => Provider;
  embedder: () => Embedder | null;
  vision: () => Vision | null;
  /** Speech-to-text backend for transcribe_audio; absent = not set up. */
  transcriber?: () => Transcriber | null;
  /**
   * The session's skill store, discovered fresh per model run: its bounded
   * directory goes into that run's system prompt and the same store backs
   * the skill tools for the whole run, so prompt and tools never disagree.
   * Optional — hosts that wire none get no directory and skill tools that
   * say so.
   */
  skills?: () => SkillStore | null;
  tools: () => Tool[];
  gate: Gate;
  memory: Memory;
  audit: AuditLog;
  home: string;
  workspace: string;
  cwd: string;
  maxTurns: number;
  /**
   * The NLI gate on final answers. Absent means the default: enabled, with
   * the deterministic checker only. `enabled: false` turns the loop wiring
   * off entirely. `modelCheck` is the optional model-backed escalator.
   */
  nli?: { enabled: boolean; modelCheck?: ModelChecker | null };
}

export interface RunHooks {
  emit: (e: AgentEvent) => void;
  /** Ask the owner. Resolves true when they allow. */
  confirm: (card: Extract<AgentEvent, { type: "confirm" }>) => Promise<boolean>;
}

const SUMMARY = 400;
const DECLINED = "The owner declined this.";
/** About 150k tokens of conversation, well inside every current model's window. */
const HISTORY_BUDGET = 600_000;

export class Agent {
  readonly turns: Turn[] = [];
  /** The most recent run's route (tier + mode + reason), for state displays. */
  lastRoute: Route | null = null;
  private running: AbortController | null = null;

  constructor(private readonly deps: AgentDeps) {}

  get busy(): boolean {
    return this.running !== null;
  }

  stop(): void {
    this.running?.abort();
  }

  async run(request: string, hooks: RunHooks, extraContext: string | { channel?: RouteChannel } = "", opts: { channel?: RouteChannel } = {}): Promise<string> {
    if (this.running) throw new Error("Already working on something; stop it first.");
    const runId = randomUUID().slice(0, 8);
    const ac = new AbortController();
    this.running = ac;
    try {
      const provider = this.deps.provider();
      const ep = new Episode(request);
      this.trim();
      const fast = fastCommand(request);
      // The router labels every run — fast commands included (tier=fast,
      // and they still short-circuit below). No model call: router.ts is
      // deterministic rules, and its reason travels with the run. The
      // channel arrives either as run options or in the options-shaped
      // third argument (both call conventions exist: context features pass
      // an extraContext string there, voice passes { channel }).
      const channel = (typeof extraContext === "object" ? extraContext.channel : undefined) ?? opts.channel ?? "chat";
      const extra = typeof extraContext === "string" ? extraContext : "";
      const route = routeRequest(request, { channel, fastCommand: fast !== null });
      this.lastRoute = route;
      hooks.emit({ type: "run_start", runId, brain: provider.label, route });
      if (fast) return await this.runFast(runId, fast, ep, hooks, ac.signal, request, extra, route);
      return await this.runModel(runId, request, provider, ep, hooks, ac.signal, extra, route);
    } catch (err) {
      if (ac.signal.aborted) {
        hooks.emit({ type: "done", runId, text: "Stopped.", stopped: true });
        return "Stopped.";
      }
      const message = err instanceof Error ? err.message : String(err);
      hooks.emit({ type: "error", runId, message });
      return message;
    } finally {
      this.settle();
      this.running = null;
    }
  }

  /** Forget the conversation (memory facts stay). */
  reset(): void {
    if (this.running) throw new Error("Stop the current request first.");
    this.turns.length = 0;
  }

  /**
   * Replace the conversation with a stored one (a branch switch, a restart
   * reload). The stored turns are closed the same way a stopped run's are,
   * so a list saved mid-step can never malformed the next request.
   */
  loadTurns(turns: Turn[]): void {
    if (this.running) throw new Error("Stop the current request first.");
    this.turns.length = 0;
    this.turns.push(...turns);
    this.settle();
  }

  /**
   * Keep the conversation under a size budget by dropping the oldest whole
   * exchanges (a request and everything that answered it), so a long session
   * never overflows the model's context and every tool call keeps its result.
   */
  private trim(budgetChars = HISTORY_BUDGET): void {
    const size = () => this.turns.reduce((n, t) => n + JSON.stringify(t).length, 0);
    while (size() > budgetChars) {
      const next = this.turns.findIndex((t, i) => i > 0 && t.role === "user");
      if (next <= 0) { this.turns.length = 0; return; }
      this.turns.splice(0, next);
    }
  }

  /**
   * After a stop or an error the transcript may end mid-step. Every tool call
   * must have a result and every request an answer, or the next request is
   * malformed, so close what is open.
   */
  private settle(): void {
    const last = this.turns[this.turns.length - 1];
    if (last?.role === "assistant" && last.toolCalls.length) {
      this.turns.push({ role: "tool", results: last.toolCalls.map((c) => ({ id: c.id, name: c.name, content: "Stopped before this ran.", isError: true })) });
    }
    const end = this.turns[this.turns.length - 1];
    if (end && end.role !== "assistant") {
      this.turns.push({ role: "assistant", text: "(stopped)", toolCalls: [], brain: "fast" });
    }
  }

  private async runFast(runId: string, fast: NonNullable<ReturnType<typeof fastCommand>>, ep: Episode, hooks: RunHooks, signal: AbortSignal, request: string, extraContext: string, route: Route): Promise<string> {
    let text: string;
    if (fast.kind === "answer") {
      text = fast.text;
    } else {
      const call: ToolCall = { id: `fast_${runId}`, name: fast.tool, input: fast.args };
      const [res] = await this.execute(runId, [call], ep, hooks, signal, false);
      if (signal.aborted) throw new Error("stopped");
      text = !res!.isError ? (fast.say ? fast.say(res!.content) : res!.content)
        : res!.content.startsWith(DECLINED) ? "Okay, I didn't do it."
        : res!.content;
    }
    this.turns.push({ role: "user", text: extraContext ? `${request}\n\n${extraContext}` : request }, { role: "assistant", text, toolCalls: [], brain: "fast", route });
    hooks.emit({ type: "text", runId, delta: text });
    hooks.emit({ type: "done", runId, text });
    return text;
  }

  private async runModel(runId: string, request: string, provider: Provider, ep: Episode, hooks: RunHooks, signal: AbortSignal, extraContext: string, route: Route): Promise<string> {
    const tools = this.deps.tools();
    const specs = tools.map((t) => ({ name: t.name, description: t.description, inputSchema: jsonSchema(t) }));
    const skills = this.deps.skills?.() ?? null;
    const system = systemPrompt(this.deps.home, platform(), skills?.directory() ?? "");
    const embedder = this.deps.embedder();
    const withContext = await requestContext(request, this.deps.memory, embedder);
    this.turns.push({ role: "user", text: extraContext ? `${withContext}\n\n${extraContext}` : withContext });
    const cloud = provider.kind === "cloud";
    /** Tool-result texts from THIS run: the evidence the NLI gate checks the final answer against. */
    const evidence: string[] = [];

    let answer = "";
    for (let turn = 0; turn < this.deps.maxTurns; turn++) {
      const res = await provider.turn({
        system,
        turns: this.turns,
        tools: specs,
        signal,
        effort: route.effort,
        onText: (delta) => hooks.emit({ type: "text", runId, delta }),
        onThinking: (delta) => hooks.emit({ type: "thinking", runId, delta }),
      });
      this.turns.push({ role: "assistant", text: res.text, toolCalls: res.toolCalls, raw: res.raw, brain: provider.kind, route });
      if (res.text) answer += (answer ? "\n\n" : "") + res.text;

      if (res.stop === "refusal") {
        const t = `The model declined this request${res.refusal ? ` (${res.refusal})` : ""}.`;
        hooks.emit({ type: "warning", runId, text: t });
        answer ||= t;
        break;
      }
      if (res.stop === "pause") continue;
      if (res.stop === "max_tokens" && !res.toolCalls.length) {
        hooks.emit({ type: "warning", runId, text: "The answer hit the length limit and was cut off." });
        break;
      }
      if (!res.toolCalls.length) break;
      if (res.stop === "max_tokens") {
        // A tool call cut off mid-way may still parse (a half-written file); never run it.
        this.turns.push({ role: "tool", results: res.toolCalls.map((c) => ({ id: c.id, name: c.name, content: "Not run: the request was cut off by the length limit. Do it in smaller steps.", isError: true })) });
        continue;
      }

      const results = await this.execute(runId, res.toolCalls, ep, hooks, signal, cloud, skills);
      for (const r of results) if (!r.isError) evidence.push(r.content);
      this.turns.push({ role: "tool", results });
      if (signal.aborted) throw new Error("stopped");
      if (turn === this.deps.maxTurns - 1) {
        hooks.emit({ type: "warning", runId, text: `Stopped after ${this.deps.maxTurns} steps without a final answer.` });
      }
    }
    answer = await this.gateAnswer(runId, answer, evidence, provider, system, hooks, signal);
    hooks.emit({ type: "done", runId, text: answer });
    // Embed any new facts in the background so the next recall is semantic; failures are ignored.
    if (embedder) void this.deps.memory.ensureEmbedded(embedder).catch(() => {});
    return answer;
  }

  /**
   * The NLI gate on the final answer. Only answers from a run that produced
   * tool results are gated, and only their checkable claims (sentences
   * containing numbers — see extractCheckableClaims) are checked, against
   * this run's tool-result texts. Each checked claim is audit-logged.
   *
   * On FAIL the claim is not deleted and the answer is not silently passed:
   * the model gets exactly one re-check turn, told the contradiction and
   * shown the evidence, and asked to correct. If the corrected answer still
   * fails, it is delivered with the disagreement surfaced to the owner.
   */
  private async gateAnswer(
    runId: string,
    answer: string,
    evidence: string[],
    provider: Provider,
    system: string,
    hooks: RunHooks,
    signal: AbortSignal,
  ): Promise<string> {
    if (this.deps.nli && !this.deps.nli.enabled) return answer;
    if (!answer || !evidence.length) return answer;
    const modelCheck = this.deps.nli?.modelCheck ?? null;

    const checkAll = async (text: string, round: "initial" | "recheck"): Promise<Array<{ claim: string; verdict: NliVerdict }>> => {
      const failures: Array<{ claim: string; verdict: NliVerdict }> = [];
      for (const claim of extractCheckableClaims(text)) {
        const verdict = await checkClaimWithModel(claim, evidence, modelCheck);
        this.deps.audit.write({ runId, tool: "nli_gate", gate: verdict.passed ? "nli_pass" : "nli_fail", round, claim: claim.slice(0, 500), reason: verdict.reason, source: verdict.source });
        if (!verdict.passed) failures.push({ claim, verdict });
      }
      return failures;
    };

    let failures = await checkAll(answer, "initial");
    if (!failures.length) return answer;
    hooks.emit({ type: "warning", runId, text: `NLI gate: ${failures.length} claim(s) in the answer contradict this run's tool results; asking the model to re-check.` });

    // One re-check loop: the model is told the contradiction and asked to correct.
    const correction = [
      "[NLI gate re-check] Your previous answer contains claim(s) that contradict the tool results from this run:",
      ...failures.map((f) => `- "${f.claim}" — ${f.verdict.reason}`),
      "",
      "Tool results:",
      ...evidence.map((ev, i) => `${i + 1}. ${ev.slice(0, 2000)}`),
      "",
      "Write a corrected final answer that matches the tool results. Do not repeat the contradicted claim(s).",
    ].join("\n");
    this.turns.push({ role: "user", text: correction });
    let candidate = answer;
    try {
      const res = await provider.turn({
        system,
        turns: this.turns,
        tools: [],
        signal,
        onText: (delta) => hooks.emit({ type: "text", runId, delta }),
        onThinking: (delta) => hooks.emit({ type: "thinking", runId, delta }),
      });
      this.turns.push({ role: "assistant", text: res.text, toolCalls: [], raw: res.raw, brain: provider.kind });
      if (res.text) candidate = res.text;
    } catch {
      // A failed re-check is not a pass: fall through and surface the original disagreement.
      this.turns.push({ role: "assistant", text: answer, toolCalls: [], brain: provider.kind });
    }
    if (candidate !== answer) {
      failures = await checkAll(candidate, "recheck");
      if (!failures.length) return candidate;
    }

    // Still failing: deliver the answer WITH the disagreement surfaced — never silently passed, never silently dropped.
    const note = failures.map((f) => `- "${f.claim}" — ${f.verdict.reason}`).join("\n");
    const surfaced = `${candidate}\n\n⚠ NLI gate disagreement — the following claim(s) could not be verified against this run's tool results and may be wrong:\n${note}`;
    hooks.emit({ type: "warning", runId, text: `NLI gate: disagreement remains after re-check; delivering the answer with it surfaced.` });
    this.deps.audit.write({ runId, tool: "nli_gate", gate: "nli_disagreement_surfaced", failures: failures.map((f) => ({ claim: f.claim.slice(0, 500), reason: f.verdict.reason })) });
    return surfaced;
  }

  /** Run a model turn's tool calls through the gate. Results come back in call order. */
  private async execute(runId: string, calls: ToolCall[], ep: Episode, hooks: RunHooks, signal: AbortSignal, cloud: boolean, skills: SkillStore | null = null): Promise<ToolResult[]> {
    const byName = new Map(this.deps.tools().map((t) => [t.name, t]));
    const results = new Array<ToolResult>(calls.length);
    const parallel: Array<Promise<void>> = [];
    for (let i = 0; i < calls.length; i++) {
      const call = calls[i]!;
      const t = byName.get(call.name);
      const job = async () => { results[i] = await this.one(runId, call, t, ep, hooks, signal, cloud, skills); };
      if (t?.parallelSafe) parallel.push(job());
      else { await Promise.all(parallel.splice(0)); await job(); }
    }
    await Promise.all(parallel);
    return results;
  }

  private async one(runId: string, call: ToolCall, t: Tool | undefined, ep: Episode, hooks: RunHooks, signal: AbortSignal, cloud: boolean, skills: SkillStore | null = null): Promise<ToolResult> {
    const fail = (content: string): ToolResult => {
      hooks.emit({ type: "tool_end", runId, id: call.id, name: call.name, ok: false, summary: content.slice(0, SUMMARY) });
      return { id: call.id, name: call.name, content, isError: true };
    };
    hooks.emit({ type: "tool_start", runId, id: call.id, name: call.name, args: call.input });
    if (signal.aborted) return fail("Stopped by the owner.");
    if (!t) return fail(`No tool named ${call.name}.`);

    // Inputs stream eagerly from the cloud brain, so they are validated here before anything runs.
    const parsed = t.schema.safeParse(call.input);
    if (!parsed.success) {
      const why = parsed.error.issues.map((i) => `${i.path.join(".") || "input"}: ${i.message}`).join("; ");
      return fail(`Invalid input for ${t.name} (${why}).`);
    }
    const args = parsed.data as Record<string, unknown>;

    const verdict = this.deps.gate.check(ep, t.name, t.policy, args);
    if (verdict.blocked) {
      this.deps.audit.write({ runId, tool: t.name, args: redactArgs(args), gate: "blocked", rule: verdict.blocked.rule });
      return fail(`Blocked: ${verdict.blocked.reason}`);
    }
    if (verdict.confirm) {
      const cardId = randomUUID().slice(0, 8);
      const card = { type: "confirm" as const, runId, cardId, tool: t.name, args: redactArgs(args), rule: verdict.confirm.rule, reason: verdict.confirm.reason };
      const allowed = await Promise.race([
        hooks.confirm(card),
        new Promise<boolean>((resolve) => signal.addEventListener("abort", () => resolve(false), { once: true })),
      ]);
      hooks.emit({ type: "confirm_resolved", runId, cardId, allowed });
      if (signal.aborted) return fail("Stopped by the owner.");
      this.deps.audit.write({ runId, tool: t.name, args: redactArgs(args), gate: "confirm", rule: verdict.confirm.rule, allowed });
      if (!allowed) return fail(`${DECLINED} Do not retry it another way.`);
      ep.confirm(t.name, args);
    }

    const ctx: ToolContext = { cwd: this.deps.cwd, home: this.deps.home, workspace: this.deps.workspace, memory: this.deps.memory, embedder: this.deps.embedder(), vision: this.deps.vision(), transcriber: this.deps.transcriber?.() ?? null, skills, signal };
    try {
      const out = await t.run(args, ctx);
      const scam = ep.observe(t.name, t.policy.effects, out, !!t.outside);
      if (scam) hooks.emit({ type: "warning", runId, text: `This looks like a scam: ${scam.reasons}. I won't send anything to it without you.` });
      // One-time codes and keys are hidden from the cloud brain; the local brain never leaves the computer.
      const content = cloud ? redactSecrets(out).text : out;
      hooks.emit({ type: "tool_end", runId, id: call.id, name: call.name, ok: true, summary: redactSecrets(out).text.slice(0, SUMMARY) });
      this.deps.audit.write({ runId, tool: t.name, args: redactArgs(args), gate: verdict.confirm ? "confirmed" : "allowed", ok: true });
      return { id: call.id, name: call.name, content, isError: false };
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.deps.audit.write({ runId, tool: t.name, args: redactArgs(args), ok: false, error: msg });
      return fail(signal.aborted ? "Stopped by the owner." : `Failed: ${msg}`);
    }
  }
}

/**
 * The args a tool was called with, as cards and the audit log may show them:
 * secret-shaped string values hidden, long values trimmed. The args the tool
 * itself runs with are never changed — this only shapes display copies.
 */
export function redactArgs(args: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(args)) {
    if (typeof v !== "string") { out[k] = v; continue; }
    const r = redactSecrets(v).text;
    out[k] = r.length > 2000 ? `${r.slice(0, 2000)}… (${r.length} chars)` : r;
  }
  return out;
}
