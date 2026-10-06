package com.agent.ultra.local

/**
 * Running estimate of how fast the loaded model decodes (tokens per second), so the
 * think budget can be bounded by how long the person waits in silence, not only by a
 * token count. Pure, JVM-tested.
 *
 * - Exponential moving average of recent generations ([ALPHA] weight on the newest), so
 *   one odd run (a thermal dip, a page-fault burst) moves it but does not own it.
 * - Runs under [MIN_TOKENS] decoded tokens, or under [MIN_MS], are ignored: their timing
 *   is mostly noise.
 * - Keyed by model file: a sample for a different model starts over, and [reset] (called
 *   on unload) forgets everything, because another model or another load mode decodes at
 *   a different speed.
 */
class DecodeSpeed {
    private var model: String? = null
    private var ema: Double? = null

    /** Feed one generation: [tokens] decoded in [decodeMs] (prompt prefill excluded). */
    @Synchronized
    fun observe(modelName: String, tokens: Int, decodeMs: Long) {
        if (modelName != model) { model = modelName; ema = null }
        if (tokens < MIN_TOKENS || decodeMs < MIN_MS) return
        val tps = tokens * 1000.0 / decodeMs
        if (!tps.isFinite() || tps <= 0.0) return
        ema = ema?.let { ALPHA * tps + (1 - ALPHA) * it } ?: tps
    }

    /** Estimate for [modelName], or null when there is none yet (or it was for another model). */
    @Synchronized
    fun tokPerSec(modelName: String): Double? = if (modelName == model) ema else null

    @Synchronized
    fun reset() { model = null; ema = null }

    companion object {
        const val ALPHA = 0.5
        const val MIN_TOKENS = 16
        const val MIN_MS = 500L
    }
}
