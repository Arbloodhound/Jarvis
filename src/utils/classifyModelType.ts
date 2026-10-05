import { UltraDevLog } from './UltraDevLog';

export type PickerCategory = "text" | "image" | "code" | "video" | "audio" | "reasoning";

export interface ModelCapabilities {
  supportsVision?: boolean;
  supportsReasoning?: boolean;
  optimizedForCode?: boolean;
  supportsFunctionCalling?: boolean;
  supportsWebSearch?: boolean;
  supportsMultipleImages?: boolean;
  isUncensored?: boolean;
  // Provider-backed capability fields
  supportsImageGeneration?: boolean;
  supportsAudioGeneration?: boolean;
  supportsVideoGeneration?: boolean;
  supportsEmbeddings?: boolean;
  supportsReasoningHints?: boolean;
  supportsToolCalls?: boolean;
}

const IMAGE_PATTERNS = ["flux", "stable-diffusion", "sdxl", "dall-e", "imagen", "pony-realism", "z-image", "qwen-image", "qwen-edit", "image-turbo", "nano-banana"];
const VIDEO_PATTERNS = ["wan-", "luma", "runway", "minimax-video", "kling", "genmo", "preview-image-to-video", "preview-t2v"];
const AUDIO_PATTERNS = ["tts-", "kokoro", "parakeet", "whisper", "speech"];
const CODE_PATTERNS = ["coder", "codestral", "deepseek-coder"];
const REASONING_PATTERNS = ["reason", "qwq", "deepseek-r1", "o1-", "o3-", "o4-mini"];

function matchesAny(value: string, patterns: string[]): boolean {
  return patterns.some((p) => value.includes(p));
}

// ── Summary accumulator (replaces per-call durable log) ─────────────────────
let _summaryCount = 0;
const _categoryCounts: Record<string, number> = {};
const _ruleCounts: Record<string, number> = {};
let _unknownFallbackCount = 0;

/**
 * Emit a SYSTEM summary of all classify_model_type calls since last reset.
 * Call this at meaningful boundaries (provider bridge sync, picker open, picker filter change).
 * Has no effect if zero classifications have occurred since last reset.
 */
export function classifyBatchSummary(trigger: string): void {
  if (_summaryCount === 0) return;
  UltraDevLog.push('SYSTEM', {
    event: 'classify_model_type_summary',
    trigger,
    totalClassified: _summaryCount,
    byCategoryCount: { ..._categoryCounts },
    byRuleCount: { ..._ruleCounts },
    unknownFallbackCount: _unknownFallbackCount,
    note: `ok — ${_summaryCount} models classified, fallback=${_unknownFallbackCount}`,
  });
  // Reset
  _summaryCount = 0;
  Object.keys(_categoryCounts).forEach(k => delete _categoryCounts[k]);
  Object.keys(_ruleCounts).forEach(k => delete _ruleCounts[k]);
  _unknownFallbackCount = 0;
}

export function classifyModelType(
  id: string,
  name: string,
  rawType?: string,
  capabilities?: ModelCapabilities | null,
): PickerCategory {
  let result: PickerCategory;
  let matchedRule: string;

  if (rawType === "image") { result = "image"; matchedRule = "rawType:image"; }
  else if (rawType === "video") { result = "video"; matchedRule = "rawType:video"; }
  else if (rawType === "audio") { result = "audio"; matchedRule = "rawType:audio"; }
  else if (rawType === "embedding") { result = "text"; matchedRule = "rawType:embedding→text"; }
  else if (capabilities?.supportsImageGeneration) { result = "image"; matchedRule = "capability:supportsImageGeneration"; }
  else if (capabilities?.supportsVideoGeneration) { result = "video"; matchedRule = "capability:supportsVideoGeneration"; }
  else if (capabilities?.supportsAudioGeneration) { result = "audio"; matchedRule = "capability:supportsAudioGeneration"; }
  else if (capabilities?.supportsEmbeddings) { result = "text"; matchedRule = "capability:supportsEmbeddings→text"; }
  else if (capabilities?.supportsReasoning || capabilities?.supportsReasoningHints) { result = "reasoning"; matchedRule = "capability:supportsReasoning"; }
  else if (capabilities?.optimizedForCode) { result = "code"; matchedRule = "capability:optimizedForCode"; }
  else {
    const idLower = (id || "").toLowerCase();
    const nameLower = (name || "").toLowerCase();

    if (matchesAny(idLower, IMAGE_PATTERNS) || matchesAny(nameLower, IMAGE_PATTERNS)) { result = "image"; matchedRule = "pattern:image"; }
    else if (matchesAny(idLower, VIDEO_PATTERNS) || nameLower.includes("video gen")) { result = "video"; matchedRule = "pattern:video"; }
    else if (matchesAny(idLower, AUDIO_PATTERNS) || matchesAny(nameLower, AUDIO_PATTERNS)) { result = "audio"; matchedRule = "pattern:audio"; }
    else if (matchesAny(idLower, CODE_PATTERNS) || matchesAny(nameLower, CODE_PATTERNS)) { result = "code"; matchedRule = "pattern:code"; }
    else if (matchesAny(idLower, REASONING_PATTERNS) || matchesAny(nameLower, REASONING_PATTERNS)) { result = "reasoning"; matchedRule = "pattern:reasoning"; }
    else { result = "text"; matchedRule = "default_text_fallback"; }
  }

  // Accumulate into summary — no per-call durable log to avoid noise
  _summaryCount++;
  _categoryCounts[result] = (_categoryCounts[result] ?? 0) + 1;
  _ruleCounts[matchedRule] = (_ruleCounts[matchedRule] ?? 0) + 1;
  if (matchedRule === 'default_text_fallback') _unknownFallbackCount++;

  return result;
}
