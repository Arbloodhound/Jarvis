import * as os from "./tools/platform.js";

/**
 * Voice: speech in (transcription) and speech out (the OS's own voice).
 *
 * Two rules carry over from the phone and are not negotiable here:
 *
 * - **Finished utterances only.** Voice never speaks text that ends
 *   mid-thought (features/voice.py's contract): the loop must finish an
 *   answer before speaking it, so `speak` refuses anything that does not
 *   end in terminal punctuation, and refuses empty text outright.
 * - **No fake backends.** Transcription goes to a real speech-to-text
 *   server the owner runs (below); speech goes to the operating system's
 *   own TTS command. When neither exists, the tools say so plainly —
 *   nothing is silently dropped, and success is never claimed for an
 *   action that did not happen.
 *
 * The phone's always-on ambient listening is a phone hardware feature
 * (microphone service, whisper.cpp on device) and is deliberately NOT
 * reproduced here: the desktop transcribes audio files it is given.
 */

/** Sentence-final punctuation, exactly the phone/Python contract's set. */
const TERMINAL = [".", "!", "?", "…"];

export class UnfinishedUtterance extends Error {
  constructor(message: string) {
    super(message);
    this.name = "UnfinishedUtterance";
  }
}

/**
 * The finished-utterance rule (features/voice.py `assert_finished`):
 * text may be spoken only when its trimmed form is non-empty and ends
 * in terminal punctuation. Throws UnfinishedUtterance otherwise.
 */
export function assertFinished(text: string): true {
  const stripped = text.trim();
  if (!stripped) throw new UnfinishedUtterance("empty utterance");
  if (!TERMINAL.some((t) => stripped.endsWith(t))) {
    throw new UnfinishedUtterance("utterance ends mid-thought; finish it before speaking");
  }
  return true;
}

/** True when a URL stays on this computer (127.0.0.1, localhost, ::1). */
export function isLoopbackUrl(raw: string): boolean {
  return /^https?:\/\/(127\.0\.0\.1|localhost|\[::1\])(?::|\/|$)/i.test(raw);
}

export interface SttConfig {
  /**
   * OpenAI-compatible transcription server base URL, e.g.
   * "http://127.0.0.1:8000/v1" for a local whisper.cpp / faster-whisper
   * server (whisper.cpp's server front-ends, speaches, LocalAI…). Empty
   * means speech-to-text is not set up.
   */
  baseUrl: string;
  /** The model name the server expects (e.g. "whisper-1", "base.en"). */
  model: string;
}

/**
 * Turns audio into text through any OpenAI-compatible
 * `/audio/transcriptions` endpoint, the same shape the Embedder uses for
 * `/embeddings`: the owner points it at a whisper-class server they run,
 * and nothing is bundled or faked here. Multipart form upload (file +
 * model), `{ text }` back.
 */
export class Transcriber {
  constructor(private readonly cfg: SttConfig) {}

  get label(): string {
    return `Whisper-class STT (${this.cfg.model} @ ${this.cfg.baseUrl})`;
  }

  /** Transcribe one audio file's bytes. Throws with a plain reason on any failure. */
  async transcribe(audio: Buffer, filename: string, opts: { language?: string; signal?: AbortSignal } = {}): Promise<string> {
    if (audio.length === 0) throw new Error("That audio file is empty; nothing to transcribe.");
    const form = new FormData();
    form.append("model", this.cfg.model);
    if (opts.language) form.append("language", opts.language);
    form.append("file", new Blob([new Uint8Array(audio)]), filename);
    let res: Response;
    try {
      res = await fetch(`${this.cfg.baseUrl.replace(/\/$/, "")}/audio/transcriptions`, {
        method: "POST",
        signal: opts.signal,
        body: form,
      });
    } catch (err) {
      throw new Error(`Can't reach the speech-to-text server at ${this.cfg.baseUrl} (${err instanceof Error ? err.message : String(err)}). Is the whisper server running?`);
    }
    if (!res.ok) throw new Error(`Speech-to-text server answered ${res.status}: ${(await res.text()).slice(0, 200)}`);
    const body = (await res.json()) as { text?: unknown };
    if (typeof body.text !== "string" || !body.text.trim()) {
      throw new Error("The speech-to-text server returned no transcript (is the model a whisper model, and does the audio contain speech?).");
    }
    return body.text.trim();
  }
}

/**
 * Speak a finished utterance with the OS's own voice, or write the
 * synthesized audio to a file when `outFile` is given and the backend
 * supports it. The finished-utterance rule is enforced BEFORE any
 * backend is touched. Throws when no TTS backend exists on this
 * computer — a failure, never a claimed success.
 */
export async function speak(text: string, opts: { outFile?: string } = {}): Promise<{ chars: number; backend: string; outFile?: string }> {
  assertFinished(text);
  if (opts.outFile) {
    const backend = await os.speakToFile(text, opts.outFile);
    return { chars: text.trim().length, backend, outFile: opts.outFile };
  }
  const backend = await os.speakText(text);
  return { chars: text.trim().length, backend };
}
