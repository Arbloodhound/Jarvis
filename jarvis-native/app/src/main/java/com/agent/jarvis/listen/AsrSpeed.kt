package com.agent.jarvis.listen

/**
 * Which speech model to run, and whether the larger one keeps up on this phone.
 *
 * small.en hears quiet and distant speech better than base.en but costs about three times the
 * compute. It is timed while it has the fast cores (the brain idle): when most of the
 * last [WINDOW] utterances took longer to transcribe than they lasted, base takes over.
 */
class AsrSpeed(private val window: Int = WINDOW) {
    private val ratios = ArrayDeque<Double>()

    /** One transcription of [audioMs] of speech that took [ms], on the fast cores. */
    fun record(audioMs: Long, ms: Long) {
        if (audioMs <= 0) return
        ratios.addLast(ms.toDouble() / audioMs)
        while (ratios.size > window) ratios.removeFirst()
    }

    /** Slower than real time on most recent utterances. */
    fun tooSlow(): Boolean {
        if (ratios.size < window) return false
        val sorted = ratios.sorted()
        return sorted[sorted.size / 2] > 1.0
    }

    companion object {
        const val WINDOW = 8

        /** small when wanted, downloaded and not found too slow; else base. */
        fun pick(accurate: Boolean, smallReady: Boolean, smallTooSlow: Boolean): String =
            if (accurate && smallReady && !smallTooSlow) AmbientConfig.ASR_SMALL_MODEL else AmbientConfig.ASR_MODEL
    }
}
