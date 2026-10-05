package com.agent.ultra.listen

import com.agent.ultra.agent.ThermalPolicy.AmbientMode

/**
 * Per-utterance whisper decision under thermal pressure. Pure, JVM-tested.
 *
 * Never skips an utterance that could carry a command:
 *  - an open command window (acoustic wake already fired), or
 *  - no acoustic gate loaded: the name is then only detectable in the transcript,
 *    so skipping would make Ultra deaf to its own name exactly when it's hot.
 * Only memory-only capture is throttled (SLOWED) or dropped (WAKE_ONLY).
 */
object AmbientThrottle {
    data class Decision(val transcribe: Boolean, val threads: Int)

    const val SLOW_INTERVAL_MS = 10_000L
    private const val HOT_THREADS = 1

    fun decide(
        mode: AmbientMode,
        commandWindowOpen: Boolean,
        gateReady: Boolean,
        nowMs: Long,
        lastMemoryMs: Long,
        baseThreads: Int = AmbientConfig.WHISPER_THREADS,
    ): Decision {
        if (mode == AmbientMode.NORMAL) return Decision(true, baseThreads)
        val mustHear = commandWindowOpen || !gateReady
        return when (mode) {
            AmbientMode.SLOWED ->
                Decision(mustHear || nowMs - lastMemoryMs >= SLOW_INTERVAL_MS, HOT_THREADS)
            else -> Decision(mustHear, HOT_THREADS)   // WAKE_ONLY
        }
    }
}
