/**
 * Turns text into vectors through any OpenAI-compatible `/embeddings` endpoint,
 * so any embedding model the owner serves works: EmbeddingGemma-2, nomic-embed,
 * bge, a custom fine-tune, whatever Ollama / llama.cpp / LM Studio / vLLM hosts.
 *
 * Matryoshka (MRL) truncation: many modern models (EmbeddingGemma-2 among them)
 * are trained so the first N dimensions are a usable smaller vector. Keeping 256
 * instead of 768 cuts storage ~3x for a negligible recall loss, so `dims` just
 * slices and re-normalizes.
 */

export interface EmbedConfig {
  /** Whether semantic memory is on. Off = word-overlap recall, no server needed. */
  enabled: boolean;
  /** OpenAI-compatible server. Defaults to the local brain's server. */
  baseUrl: string;
  model: string;
  /** Keep this many dimensions (MRL truncation). 0 = whatever the model returns. */
  dims: number;
}

export class Embedder {
  constructor(private readonly cfg: EmbedConfig) {}

  get id(): string {
    return `${this.cfg.model}@${this.cfg.dims || "full"}`;
  }

  /** Embed one or more strings. Throws if the server can't be reached or answers oddly. */
  async embed(inputs: string[], signal?: AbortSignal): Promise<number[][]> {
    if (inputs.length === 0) return [];
    let res: Response;
    try {
      res = await fetch(`${this.cfg.baseUrl.replace(/\/$/, "")}/embeddings`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        signal,
        body: JSON.stringify({ model: this.cfg.model, input: inputs }),
      });
    } catch (err) {
      throw new Error(`Can't reach the embedding server at ${this.cfg.baseUrl} (${err instanceof Error ? err.message : String(err)}).`);
    }
    if (!res.ok) throw new Error(`Embedding server answered ${res.status}: ${(await res.text()).slice(0, 200)}`);
    const body = (await res.json()) as { data?: Array<{ embedding: number[] }> };
    if (!body.data || body.data.length !== inputs.length) throw new Error("Embedding server returned the wrong number of vectors.");
    return body.data.map((d) => normalize(this.cfg.dims > 0 ? d.embedding.slice(0, this.cfg.dims) : d.embedding));
  }

  async embedOne(input: string, signal?: AbortSignal): Promise<number[]> {
    return (await this.embed([input], signal))[0]!;
  }
}

/** Unit-length copy, so cosine similarity is just a dot product. */
export function normalize(v: number[]): number[] {
  let sum = 0;
  for (const x of v) sum += x * x;
  const n = Math.sqrt(sum) || 1;
  return v.map((x) => x / n);
}

/** Cosine similarity of two already-normalized vectors (their dot product). */
export function dot(a: number[], b: number[]): number {
  const len = Math.min(a.length, b.length);
  let s = 0;
  for (let i = 0; i < len; i++) s += a[i]! * b[i]!;
  return s;
}
