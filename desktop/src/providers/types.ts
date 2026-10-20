/**
 * The brain-neutral conversation the agent loop keeps. Each provider converts
 * it to its own wire format. An assistant turn also keeps the provider's raw
 * content (`raw`) so it can be replayed unchanged on the next request; Claude
 * needs its thinking blocks back exactly as it sent them.
 */

export interface ToolCall {
  id: string;
  name: string;
  input: Record<string, unknown>;
}

export interface ToolResult {
  id: string;
  name: string;
  content: string;
  isError: boolean;
}

export type Turn =
  | { role: "user"; text: string }
  | { role: "assistant"; text: string; toolCalls: ToolCall[]; raw?: unknown; brain?: string }
  | { role: "tool"; results: ToolResult[] };

export interface ToolSpec {
  name: string;
  description: string;
  /** JSON Schema for the input object. */
  inputSchema: Record<string, unknown>;
}

export type StopReason = "end" | "tool_use" | "max_tokens" | "refusal" | "pause";

export interface TurnResult {
  text: string;
  toolCalls: ToolCall[];
  stop: StopReason;
  raw?: unknown;
  /** Set on a refusal: the category and explanation the model gave, when it gave one. */
  refusal?: string;
}

export interface TurnRequest {
  system: string;
  turns: Turn[];
  tools: ToolSpec[];
  signal: AbortSignal;
  onText: (delta: string) => void;
  onThinking: (delta: string) => void;
}

export interface Provider {
  /** "cloud" or "local", for the UI and the transcript. */
  readonly kind: "cloud" | "local";
  readonly label: string;
  turn(req: TurnRequest): Promise<TurnResult>;
}
