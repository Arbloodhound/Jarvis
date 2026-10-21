import Anthropic from "@anthropic-ai/sdk";
import { redactSecrets } from "../gate/secrets.js";
import type { Provider, ToolCall, Turn, TurnRequest, TurnResult } from "./types.js";

/**
 * The cloud brain: Claude through the official SDK, streamed.
 *
 * - Adaptive thinking with summarized display, so the UI can show what it is
 *   thinking instead of a long silent pause.
 * - The system prompt and the tool list are stable across requests and marked
 *   for prompt caching; per-request context travels in the user turn.
 * - Server-side refusal fallback ("default") is on; a refusal that still comes
 *   back is reported, not hidden.
 * - Tool inputs stream eagerly; the agent loop validates every input against
 *   its schema before anything runs.
 */
export class ClaudeProvider implements Provider {
  readonly kind = "cloud" as const;
  private readonly client: Anthropic;

  constructor(
    private readonly model: string,
    private readonly effort: "low" | "medium" | "high" | "xhigh" | "max",
    apiKey?: string,
  ) {
    this.client = new Anthropic(apiKey ? { apiKey } : {});
  }

  get label(): string {
    return `Claude (${this.model})`;
  }

  async turn(req: TurnRequest): Promise<TurnResult> {
    const stream = this.client.beta.messages.stream(
      {
        model: this.model,
        max_tokens: 64000,
        system: [{ type: "text", text: req.system, cache_control: { type: "ephemeral" } }],
        messages: toMessages(req.turns),
        tools: req.tools.map((t) => ({
          name: t.name,
          description: t.description,
          input_schema: t.inputSchema as Anthropic.Beta.BetaTool.InputSchema,
          eager_input_streaming: true,
        })),
        thinking: { type: "adaptive", display: "summarized" },
        // The router's per-run effort wins over the configured default.
        output_config: { effort: req.effort ?? this.effort },
        betas: ["server-side-fallback-2026-07-01"],
        fallbacks: "default",
      },
      { signal: req.signal },
    );

    for await (const event of stream) {
      if (event.type !== "content_block_delta") continue;
      if (event.delta.type === "text_delta") req.onText(event.delta.text);
      else if (event.delta.type === "thinking_delta") req.onThinking(event.delta.thinking);
    }
    const msg = await stream.finalMessage();

    const text = msg.content
      .filter((b): b is Anthropic.Beta.BetaTextBlock => b.type === "text")
      .map((b) => b.text)
      .join("");
    const toolCalls: ToolCall[] = msg.content
      .filter((b): b is Anthropic.Beta.BetaToolUseBlock => b.type === "tool_use")
      .map((b) => ({ id: b.id, name: b.name, input: (b.input ?? {}) as Record<string, unknown> }));

    const stop =
      msg.stop_reason === "tool_use" ? "tool_use"
      : msg.stop_reason === "max_tokens" ? "max_tokens"
      : msg.stop_reason === "refusal" ? "refusal"
      : msg.stop_reason === "pause_turn" ? "pause"
      : "end";
    const refusal = stop === "refusal" && msg.stop_details
      ? [msg.stop_details.category, msg.stop_details.explanation].filter(Boolean).join(": ")
      : undefined;
    return { text, toolCalls, stop, raw: msg.content, refusal };
  }
}

/** Neutral turns to Messages API params. Claude's own turns replay their raw content unchanged. */
export function toMessages(turns: Turn[]): Anthropic.Beta.BetaMessageParam[] {
  const out: Anthropic.Beta.BetaMessageParam[] = [];
  for (const t of turns) {
    if (t.role === "user") {
      out.push({ role: "user", content: t.text });
    } else if (t.role === "assistant") {
      if (t.brain === "cloud" && Array.isArray(t.raw)) {
        out.push({ role: "assistant", content: t.raw as Anthropic.Beta.BetaContentBlockParam[] });
      } else {
        // A turn the local brain wrote: send it as plain text and tool_use blocks.
        const content: Anthropic.Beta.BetaContentBlockParam[] = [];
        if (t.text) content.push({ type: "text", text: t.text });
        for (const c of t.toolCalls) content.push({ type: "tool_use", id: c.id, name: c.name, input: c.input });
        if (content.length) out.push({ role: "assistant", content });
      }
    } else {
      // All results of one assistant turn go back in a single user message.
      out.push({
        role: "user",
        content: t.results.map((r) => ({
          type: "tool_result" as const,
          tool_use_id: r.id,
          // Results the local brain saw unredacted are masked here, whichever brain produced them.
          content: redactSecrets(r.content).text,
          is_error: r.isError,
        })),
      });
    }
  }
  return out;
}
