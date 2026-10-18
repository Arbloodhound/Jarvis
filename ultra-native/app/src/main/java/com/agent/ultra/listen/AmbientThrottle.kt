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

    /** Threads whisper gets on the LLM's cores while the brain is idle. */
    const val IDLE_FAST_THREADS = 4

    /**
     * Where whisper runs for one utterance: the threads and cores to pin it to. While the brain
     * is idle (neither model generating, loading or warming) and the phone is [cool] (thermal NORMAL), the LLM's big
     * cores are free, so whisper takes up to [IDLE_FAST_THREADS] of them: the Feature check
     * transcribed 6 s of speech in ~3 s there. Otherwise it stays on its own cores
     * ([whisperCpus], the two A520s on the Tensor G5) with the [plan]'s threads, so it never
     * slows an answer. On the old build the Pixel logged "backlog full: dropped" while listening
     * only ran on the little cores (2026-10-06 19:38). No LLM cores known = unchanged.
     *
     * [command]: speech inside a wake window ("Hey Jarvis, …") always takes the fast cores. On the
     * little ones a 1-3 s command took 28-103 s to transcribe while an answer was running
     * (Pixel 2026-10-17 22:42-22:47); the running answer loses a few seconds instead.
     */
    fun placement(plan: Decision, brainBusy: Boolean, cool: Boolean, llmCpus: IntArray, whisperCpus: IntArray,
                  command: Boolean = false): Pair<Int, IntArray> =
        if (llmCpus.isNotEmpty() && (command || (!brainBusy && cool)))
            minOf(IDLE_FAST_THREADS, llmCpus.size) to llmCpus
        else plan.threads to whisperCpus
}
