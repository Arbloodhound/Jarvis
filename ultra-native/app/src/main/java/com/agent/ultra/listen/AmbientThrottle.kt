package com.agent.ultra.listen

import com.agent.ultra.agent.ThermalPolicy.AmbientMode

/**
 * Per-utterance whisper decision under thermal pressure. Pure, JVM-tested.
 *
 * Whisper runs on the cores the LLM doesn't use ([com.agent.ultra.local.CpuTopology.whisperCpus],
 * the two little A520s on the Tensor G5), so heat is no reason to stop hearing. SLOWED (HOT)
 * still transcribes everything, on one thread. Only WAKE_ONLY (CRITICAL) pauses memory-only
 * capture, and the caller logs every utterance it skips. Before whisper had its own cores, WARM
 * kept one memory line per 10 s and HOT kept none, silently.
 *
 * Never skips an utterance that could carry a command:
 *  - an open command window (acoustic wake already fired), or
 *  - no acoustic gate loaded: the name is then only detectable in the transcript,
 *    so skipping would make Ultra deaf to its own name exactly when it's hot.
 */
object AmbientThrottle {
    data class Decision(val transcribe: Boolean, val threads: Int)

    private const val HOT_THREADS = 1

    fun decide(
        mode: AmbientMode,
        commandWindowOpen: Boolean,
        gateReady: Boolean,
        baseThreads: Int = AmbientConfig.WHISPER_THREADS,
    ): Decision = when (mode) {
        AmbientMode.NORMAL -> Decision(true, baseThreads)
        AmbientMode.SLOWED -> Decision(true, minOf(HOT_THREADS, baseThreads))
        AmbientMode.WAKE_ONLY -> Decision(commandWindowOpen || !gateReady, minOf(HOT_THREADS, baseThreads))
    }
}
