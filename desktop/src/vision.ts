import Anthropic from "@anthropic-ai/sdk";

/**
 * Looks at images (a screenshot, or an image file the owner points at) and
 * answers in words. Two backends, picked to match the brain:
 *
 * - **Local**: any OpenAI-compatible server hosting a vision model
 *   (Qwen3-VL, llava, moondream, a custom VLM). The image goes as a base64
 *   data URL in the chat request. Nothing leaves the computer.
 * - **Cloud**: Claude, which reads images natively.
 *
 * The choice follows the current brain, so "offline only" keeps vision local
 * too. Sending a screenshot to the cloud brain means the screen's contents
 * leave the computer — the same trade as any cloud use, and the gate still
 * blocks a screenshot that a scam or private-data rule catches.
 */

export interface VisionConfig {
  kind: "local" | "cloud";
  /** Local server base URL (OpenAI-compatible). */
  baseUrl: string;
  /** Local vision model id, or the cloud model id. */
  model: string;
  /** Cloud API key, when kind === "cloud". */
  apiKey?: string;
}

export interface Image {
  /** Base64 PNG/JPEG bytes (no data: prefix). */
  data: string;
  mediaType: "image/png" | "image/jpeg";
}

export class Vision {
  constructor(private readonly cfg: VisionConfig) {}

  get label(): string {
    return this.cfg.kind === "cloud" ? `Claude (${this.cfg.model})` : `Local VLM (${this.cfg.model})`;
  }

  async describe(images: Image[], question: string, signal?: AbortSignal): Promise<string> {
    if (images.length === 0) throw new Error("No image to look at.");
    return this.cfg.kind === "cloud" ? this.cloud(images, question, signal) : this.local(images, question, signal);
  }

  private async cloud(images: Image[], question: string, signal?: AbortSignal): Promise<string> {
    const client = new Anthropic(this.cfg.apiKey ? { apiKey: this.cfg.apiKey } : {});
    const msg = await client.messages.create(
      {
        model: this.cfg.model,
        max_tokens: 2048,
        messages: [{
          role: "user",
          content: [
            ...images.map((im) => ({ type: "image" as const, source: { type: "base64" as const, media_type: im.mediaType, data: im.data } })),
            { type: "text" as const, text: question },
          ],
        }],
      },
      { signal },
    );
    return msg.content.filter((b): b is Anthropic.TextBlock => b.type === "text").map((b) => b.text).join("").trim();
  }

  private async local(images: Image[], question: string, signal?: AbortSignal): Promise<string> {
    let res: Response;
    try {
      res = await fetch(`${this.cfg.baseUrl.replace(/\/$/, "")}/chat/completions`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        signal,
        body: JSON.stringify({
          model: this.cfg.model,
          max_tokens: 2048,
          messages: [{
            role: "user",
            content: [
              { type: "text", text: question },
              ...images.map((im) => ({ type: "image_url", image_url: { url: `data:${im.mediaType};base64,${im.data}` } })),
            ],
          }],
        }),
      });
    } catch (err) {
      throw new Error(`Can't reach the vision server at ${this.cfg.baseUrl} (${err instanceof Error ? err.message : String(err)}).`);
    }
    if (!res.ok) throw new Error(`Vision server answered ${res.status}: ${(await res.text()).slice(0, 200)}`);
    const body = (await res.json()) as { choices?: Array<{ message?: { content?: string } }> };
    const text = body.choices?.[0]?.message?.content;
    if (!text) throw new Error("The vision model returned no description (is it a vision model?).");
    return text.trim();
  }
}
