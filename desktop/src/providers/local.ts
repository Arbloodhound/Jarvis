import type { Provider, ToolCall, Turn, TurnRequest, TurnResult } from "./types.js";

/**
 * The local brain: any OpenAI-compatible model server on this computer
 * (Ollama, llama.cpp `llama-server`, LM Studio, vLLM). Nothing leaves the
 * machine. Tools go in the request's native `tools` field, never parsed out of
 * free text.
 *
 * Thinking models send their reasoning either as `reasoning_content` deltas or
 * inline between <think> tags; both are routed to onThinking.
 */
export class LocalProvider implements Provider {
  readonly kind = "local" as const;

  constructor(private readonly baseUrl: string, private readonly model: string) {}

  get label(): string {
    return `Local (${this.model})`;
  }

  /** The model ids this server has, from the OpenAI-compatible /models endpoint. */
  static async listModels(baseUrl: string, signal?: AbortSignal): Promise<string[]> {
    const res = await fetch(`${baseUrl.replace(/\/$/, "")}/models`, { signal });
    if (!res.ok) throw new Error(`Model server answered ${res.status}`);
    const body = (await res.json()) as { data?: Array<{ id?: string }> };
    return (body.data ?? []).map((m) => m.id).filter((id): id is string => typeof id === "string").sort();
  }

  async turn(req: TurnRequest): Promise<TurnResult> {
    let res: Response;
    try {
      res = await fetch(`${this.baseUrl.replace(/\/$/, "")}/chat/completions`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        signal: req.signal,
        body: JSON.stringify({
          model: this.model,
          stream: true,
          temperature: 0.2,
          messages: toChat(req.system, req.turns),
          tools: req.tools.length
            ? req.tools.map((t) => ({ type: "function", function: { name: t.name, description: t.description, parameters: t.inputSchema } }))
            : undefined,
        }),
      });
    } catch (err) {
      if (req.signal.aborted) throw err;
      throw new Error(
        `Can't reach the local model at ${this.baseUrl}. Start it (for Ollama: \`ollama serve\` and \`ollama pull ${this.model}\`), or switch the brain to Cloud.`,
      );
    }
    if (!res.ok || !res.body) {
      throw new Error(`Local model server at ${this.baseUrl} answered ${res.status}: ${(await res.text()).slice(0, 300)}`);
    }

    let text = "";
    let finish = "";
    const calls = new Map<number, { id: string; name: string; args: string }>();
    const idSlot = new Map<string, number>();
    let lastSlot = 0;
    const think = new ThinkSplitter(req.onText, req.onThinking);
    for await (const data of sseData(res.body)) {
      if (data === "[DONE]") break;
      let chunk: ChatChunk;
      try { chunk = JSON.parse(data) as ChatChunk; } catch { continue; }
      const choice = chunk.choices?.[0];
      if (!choice) continue;
      const d = choice.delta ?? {};
      if (d.reasoning_content) req.onThinking(d.reasoning_content);
      if (d.reasoning) req.onThinking(d.reasoning);
      if (d.content) text += think.push(d.content);
      for (const tc of d.tool_calls ?? []) {
        // Servers differ: most send an index, some only an id, some neither (one whole call per entry).
        let slot: number;
        if (typeof tc.index === "number") slot = tc.index;
        else if (tc.id && idSlot.has(tc.id)) slot = idSlot.get(tc.id)!;
        else if (!tc.id && !tc.function?.name && calls.size) slot = lastSlot;
        else slot = 1000 + calls.size;
        if (tc.id) idSlot.set(tc.id, slot);
        lastSlot = slot;
        const cur = calls.get(slot) ?? { id: tc.id ?? `call_${calls.size}`, name: "", args: "" };
        if (tc.id) cur.id = tc.id;
        // The name arrives once; a server that repeats it in every fragment must not double it.
        if (tc.function?.name && !cur.name.endsWith(tc.function.name)) cur.name += tc.function.name;
        if (tc.function?.arguments) cur.args += tc.function.arguments;
        calls.set(slot, cur);
      }
      if (choice.finish_reason) finish = choice.finish_reason;
    }
    text += think.flush();

    const toolCalls: ToolCall[] = [...calls.entries()].sort((a, b) => a[0] - b[0]).map(([, c], i) => {
      let input: Record<string, unknown> = {};
      try { input = c.args ? (JSON.parse(c.args) as Record<string, unknown>) : {}; } catch { input = { __invalid_json: c.args }; }
      return { id: c.id || `call_${i}`, name: c.name, input };
    });
    const stop = toolCalls.length ? "tool_use" : finish === "length" ? "max_tokens" : "end";
    return { text: text.trim(), toolCalls, stop };
  }
}

interface ChatChunk {
  choices?: Array<{
    delta?: {
      content?: string;
      reasoning_content?: string;
      reasoning?: string;
      tool_calls?: Array<{ index?: number; id?: string; function?: { name?: string; arguments?: string } }>;
    };
    finish_reason?: string | null;
  }>;
}

type ChatMsg =
  | { role: "system" | "user"; content: string }
  | { role: "assistant"; content: string; tool_calls?: Array<{ id: string; type: "function"; function: { name: string; arguments: string } }> }
  | { role: "tool"; tool_call_id: string; content: string };

export function toChat(system: string, turns: Turn[]): ChatMsg[] {
  const out: ChatMsg[] = [{ role: "system", content: system }];
  for (const t of turns) {
    if (t.role === "user") out.push({ role: "user", content: t.text });
    else if (t.role === "assistant") {
      out.push({
        role: "assistant",
        content: t.text,
        tool_calls: t.toolCalls.length
          ? t.toolCalls.map((c) => ({ id: c.id, type: "function" as const, function: { name: c.name, arguments: JSON.stringify(c.input) } }))
          : undefined,
      });
    } else {
      for (const r of t.results) out.push({ role: "tool", tool_call_id: r.id, content: r.isError ? `ERROR: ${r.content}` : r.content });
    }
  }
  return out;
}

async function* sseData(body: ReadableStream<Uint8Array>): AsyncGenerator<string> {
  const dec = new TextDecoder();
  let buf = "";
  for await (const part of body as unknown as AsyncIterable<Uint8Array>) {
    buf += dec.decode(part, { stream: true });
    let nl: number;
    while ((nl = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (line.startsWith("data:")) yield line.slice(5).trim();
    }
  }
  if (buf.trim().startsWith("data:")) yield buf.trim().slice(5).trim();
}

/** Routes text inside <think>…</think> to thinking, the rest to the answer, across chunk borders. */
export class ThinkSplitter {
  private inThink = false;
  private pending = "";

  constructor(private readonly onText: (s: string) => void, private readonly onThinking: (s: string) => void) {}

  /** Returns the answer text released by this chunk. */
  push(chunk: string): string {
    this.pending += chunk;
    let answer = "";
    for (;;) {
      const tag = this.inThink ? "</think>" : "<think>";
      const i = this.pending.indexOf(tag);
      if (i >= 0) {
        answer += this.emit(this.pending.slice(0, i));
        this.pending = this.pending.slice(i + tag.length);
        this.inThink = !this.inThink;
        continue;
      }
      // Hold back a tail that could be the start of a tag.
      let keep = 0;
      for (let k = Math.min(tag.length - 1, this.pending.length); k > 0; k--) {
        if (tag.startsWith(this.pending.slice(-k))) { keep = k; break; }
      }
      answer += this.emit(this.pending.slice(0, this.pending.length - keep));
      this.pending = this.pending.slice(this.pending.length - keep);
      return answer;
    }
  }

  flush(): string {
    const rest = this.emit(this.pending);
    this.pending = "";
    return rest;
  }

  private emit(s: string): string {
    if (!s) return "";
    if (this.inThink) { this.onThinking(s); return ""; }
    this.onText(s);
    return s;
  }
}
