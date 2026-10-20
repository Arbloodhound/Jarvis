package com.agent.jarvis.local

import android.content.Context
import java.io.File

/**
 * The "Speed boost": speculative decoding's draft model, Qwen3-0.6B Q4_0. It shares the Qwen3
 * tokenizer with the thinker presets (the native side checks the vocabularies the way
 * llama.cpp does and refuses a mismatch), guesses a few tokens ahead, and the thinker checks
 * them in one batched decode. The thinker's own sampler still picks every token, so answers
 * are the same; only the wait changes. Lives beside the THINKER only (never the librarian).
 *
 * Memory: weights + q8_0 KV for its context + a small compute buffer, all in the app's own
 * memory. It is attached only when that fits with [FREE_AFTER_BYTES] still free ([contextFor]),
 * so it never takes the room the thinker's load policy keeps free; otherwise generation runs
 * without it, as before. Pure parts JVM-tested (DraftModelTest).
 */
object DraftModel {
    /** File name in filesDir/models (distinct from every preset). */
    const val FILE = "qwen3-0.6b-q4_0.gguf"

    /** Checked on the Hugging Face listing (unsloth/Qwen3-0.6B-GGUF), 2026-10-07. */
    const val URL = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_0.gguf"

    /** Its exact size on that listing: 382,156,480 B. A file of any other size is not used. */
    const val BYTES = 382_156_480L

    /**
     * Longest draft per step. Each step costs the draft's decodes plus one thinker decode of
     * draft+1 tokens; past a few tokens, acceptance of the later ones falls off while the
     * batched check still costs more. 6 sits in the middle of the 4–8 range llama.cpp's
     * examples use for a small same-family draft; the debug speed test measures 4 and 8 too.
     */
    const val DRAFT_MAX = 6

    private const val MIB = 1_048_576L

    /** Free memory that must remain after the draft is attached. It was the thinker's 3 GB
     *  planning figure ([LocalModelEngine.THINKER_FREE_AFTER_LOAD_BYTES]); on the Pixel that left the
     *  draft skipped on almost every answer ("roomMB=3659 need 675 + 3072", 2026-10-18) while runs
     *  with it reached 5-7 tok/s against 1-3 without. 2 GB still leaves room for the memory/action
     *  model (~1.7 GB at 4096). Runtime-unproven. */
    const val FREE_AFTER_BYTES = 2_048L * MIB

    /** Qwen3-0.6B: 28 layers x 8 KV heads x 128, vocab 151,936 (its config.json). */
    private const val LAYERS = 28
    private const val KV_HEADS = 8
    private const val HEAD_DIM = 128
    private const val VOCAB = 151_936L
    /** Activations at n_ubatch 512 for a 1024-wide model: an estimate, rounded up. */
    private const val ACTIVATIONS_BYTES = 64L * MIB

    /** q8_0 KV bytes per token (34 bytes per 32 elements, K and V). */
    const val KV_BYTES_PER_TOKEN = LAYERS.toLong() * KV_HEADS * HEAD_DIM * 2 * 34 / 32

    /**
     * Bytes the draft takes at [ctx]: weights + q8_0 KV + compute buffer (ultra_llm.cpp
     * nativeAttachDraft reserves ONE output row of logits, n_outputs_max = 1, plus the
     * flash-attention mask at n_ubatch 512, counted twice as in
     * [LocalModelEngine.memoryNeedBytes], plus activations). 16384: 1,481,671,872 B; 8192:
     * 965,772,480; 4096: 707,822,784.
     */
    fun needBytes(ctx: Int): Long =
        BYTES + KV_BYTES_PER_TOKEN * ctx + VOCAB * 4 + 2L * ctx * LocalModelEngine.N_UBATCH * 2 + ACTIVATIONS_BYTES

    /**
     * The draft's context: the largest of [LocalModelEngine.CTX_STEPS] not above the thinker's
     * [thinkerCtx] whose [needBytes] leaves [FREE_AFTER_BYTES] of [roomBytes] (memory free now,
     * with the thinker loaded, less mapped weights that must stay: [roomAfterThinker]). 0 = no
     * draft: room short, or unknown (<= 0). A draft context below the thinker's is fine: past
     * it, steps simply run without drafting. Pure, JVM-tested.
     */
    fun contextFor(roomBytes: Long, thinkerCtx: Int): Int {
        if (roomBytes <= 0 || thinkerCtx <= 0) return 0
        return LocalModelEngine.CTX_STEPS.filter {
            it <= thinkerCtx && roomBytes - needBytes(it) >= FREE_AFTER_BYTES
        }.maxOrNull() ?: 0
    }

    /**
     * Room for the draft once the thinker is loaded: availMem less what it counts as free but
     * must stay in memory, i.e. the librarian's mapped weights and, when the thinker is mapped
     * rather than resident, the thinker's own file. Unknown (<= 0) stays unknown. Pure.
     */
    fun roomAfterThinker(availBytes: Long, otherMappedBytes: Long, thinkerMappedBytes: Long): Long =
        LocalModelEngine.roomBesideOther(availBytes, otherMappedBytes.coerceAtLeast(0) + thinkerMappedBytes.coerceAtLeast(0))

    /** Thinker files the draft can serve: the Qwen3 family (same tokenizer). Anything else would
     *  only be refused by the vocabulary check after reading 382 MB. Not Qwen3.5 ("qwen35-"): a
     *  different vocabulary (248,320 tokens), and a hybrid model the native side won't draft for
     *  (nativeAttachDraft -3). Pure. */
    fun eligible(thinkerFileName: String): Boolean =
        thinkerFileName.startsWith("qwen3") && !thinkerFileName.startsWith("qwen35") && thinkerFileName != FILE

    fun file(context: Context): File = File(context.filesDir, "models/$FILE")

    /** Downloaded, complete (exact size). */
    fun present(context: Context): Boolean = file(context).let { it.isFile && it.length() == BYTES }
}
