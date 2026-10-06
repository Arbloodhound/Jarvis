package com.agent.ultra.listen

/**
 * What ambient listening does with utterances that end while the on-device brain is busy
 * (generating or loading). Pure, JVM-tested.
 *
 * Whisper and the LLM wanted the same big cores; the Pixel 10 Pro ANR dumps showed whisper
 * transcribing while the 10 GB model loaded. So an utterance that ends while the brain is busy is
 * queued here and transcribed, oldest first, once it is idle, or once it has waited
 * [maxHoldMs] (whisper now runs on its own cores, so the brain being busy no longer has to mean
 * silence). Wake-word detection is not queued: it runs on every frame in the capture loop regardless.
 *
 * The queue is bounded by count and by audio length. Over either bound one utterance is dropped:
 * the oldest memory-only one (no wake window) first, because a wake-window utterance may be a
 * command ("Hey Jarvis, …") that must still be answered after the current answer. Only when every
 * queued utterance is wake-window speech does the oldest of those go.
 */
object AmbientBacklog {
    const val MAX_ITEMS = 8
    /** 2 minutes of 16 kHz audio (~3.8 MB of shorts). */
    const val MAX_SAMPLES = 120 * AmbientConfig.SAMPLE_RATE

    /**
     * Appends [item] to [queue] and drops until it fits [maxItems] and [maxSamples]; returns what
     * was dropped (for logging), oldest first. A lone item is always kept, however long.
     */
    fun <T> admit(
        queue: ArrayDeque<T>,
        item: T,
        samples: (T) -> Int,
        inWindow: (T) -> Boolean,
        maxItems: Int = MAX_ITEMS,
        maxSamples: Int = MAX_SAMPLES,
    ): List<T> {
        queue.addLast(item)
        val dropped = ArrayList<T>()
        var total = queue.sumOf { samples(it).toLong() }
        while (queue.size > 1 && (queue.size > maxItems || total > maxSamples)) {
            val i = queue.indexOfFirst { !inWindow(it) }.takeIf { it >= 0 } ?: 0
            val victim = queue.removeAt(i)
            total -= samples(victim)
            dropped += victim
        }
        return dropped
    }

    /** Gap between generations treated as still busy: a multi-step answer (generate, act,
     * generate) drops the engine's busy flag briefly between steps, and whisper shouldn't jump into that gap. */
    const val SETTLE_MS = 2_000L

    /** Transcribe now: the brain is idle and has been for [settleMs] (since [busyChangedAtMs]). */
    fun mayTranscribe(busy: Boolean, nowMs: Long, busyChangedAtMs: Long, settleMs: Long = SETTLE_MS): Boolean =
        !busy && nowMs - busyChangedAtMs >= settleMs

    /**
     * Longest an utterance waits for the brain when whisper is pinned to cores the LLM doesn't use
     * ([com.agent.ultra.local.CpuTopology.whisperCpus]; [0,1] on the Tensor G5). The hold used to
     * have no cap: a Feature check (or any long answer) kept the brain busy for minutes, the
     * backlog filled and dropped speech ("backlog full: dropped" all through the Pixel's log) and
     * nothing was stored ("last heard=never"). Past the cap the backlog drains anyway: whisper's
     * threads are on their own cores, so it competes only for memory bandwidth.
     */
    const val MAX_HOLD_MS = 15_000L

    /** The cap when the cores could not be read and whisper is unpinned (it then shares the
     *  LLM's cores, the case the hold was written for): longer, but still bounded. */
    const val MAX_HOLD_UNPINNED_MS = 60_000L

    fun maxHoldMs(whisperPinned: Boolean): Long = if (whisperPinned) MAX_HOLD_MS else MAX_HOLD_UNPINNED_MS

    /** The oldest queued utterance (ended at [oldestEndedAtMs]; null = nothing queued) has waited
     *  [maxHoldMs] or more: transcribe it even though the brain is busy. */
    fun holdExpired(nowMs: Long, oldestEndedAtMs: Long?, maxHoldMs: Long = MAX_HOLD_MS): Boolean =
        oldestEndedAtMs != null && nowMs - oldestEndedAtMs >= maxHoldMs

    /** Transcribe the head of the backlog now: the brain has settled, or the hold cap is reached. */
    fun shouldDrain(
        busy: Boolean, nowMs: Long, busyChangedAtMs: Long, oldestEndedAtMs: Long?,
        maxHoldMs: Long = MAX_HOLD_MS, settleMs: Long = SETTLE_MS,
    ): Boolean = mayTranscribe(busy, nowMs, busyChangedAtMs, settleMs) || holdExpired(nowMs, oldestEndedAtMs, maxHoldMs)

    /**
     * How long to sleep before [shouldDrain] can next turn true without new input, at least 1 ms:
     * the time left on the hold cap, and (when idle) on the settle gap, whichever is sooner.
     * With [busy] true the caller also wakes as soon as the brain goes idle.
     */
    fun waitMs(
        busy: Boolean, nowMs: Long, busyChangedAtMs: Long, oldestEndedAtMs: Long?,
        maxHoldMs: Long = MAX_HOLD_MS, settleMs: Long = SETTLE_MS,
    ): Long {
        val capLeft = oldestEndedAtMs?.let { it + maxHoldMs - nowMs } ?: Long.MAX_VALUE
        val settleLeft = if (busy) Long.MAX_VALUE else busyChangedAtMs + settleMs - nowMs
        return minOf(capLeft, settleLeft).coerceIn(1L, maxOf(maxHoldMs, settleMs))
    }

    /**
     * The capture loop's command-window deadline after a wake-window utterance (window [itemWindow],
     * ended at [endedAtMs]) has been queued instead of judged. Judged at once, a bare "Hey Jarvis."
     * would have reopened the window for [AmbientConfig.FOLLOWUP_MS] so the next utterance can be
     * the command; queued, nobody knows yet whether it was bare. So keep the window open until the
     * same deadline from when it ended. Harmless if it did carry a command: that command spends
     * the window when judged (in order), so later speech stamped with it stays memory only.
     * Only for the window still current ([openWindow]); a newer window keeps its own deadline.
     * The caller applies it once per window (its first queued utterance), so ongoing speech can't
     * keep a window open for the whole time the brain is busy.
     */
    fun holdWindowUntil(
        currentUntilMs: Long,
        openWindow: Long,
        itemWindow: Long,
        endedAtMs: Long,
        followupMs: Long = AmbientConfig.FOLLOWUP_MS,
    ): Long =
        if (itemWindow == 0L || itemWindow != openWindow) currentUntilMs
        else maxOf(currentUntilMs, endedAtMs + followupMs)
}
