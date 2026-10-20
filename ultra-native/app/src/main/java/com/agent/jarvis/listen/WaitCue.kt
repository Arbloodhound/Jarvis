package com.agent.jarvis.listen

/**
 * Pure, JVM-tested. What the ambient service says while a spoken command still has no answer, so
 * Jarvis never sounds frozen ("the thinking being fast is okay, the freeze is not"). A cue only
 * speaks: nothing is cancelled, cut short or dropped, however long the thinking takes.
 *
 * "On it." at [ON_IT_AFTER_MS]; then, once, at [LATE_AFTER_MS] after the command was heard:
 * [STILL_WORKING] when it is running and has said nothing yet, or [STILL_BUSY] when it is still
 * queued behind a run that has not finished (a slow or hung load/generation holds the queue).
 */
object WaitCue {
    /** "On it." this long after the command was heard, if nothing has been said for it. */
    const val ON_IT_AFTER_MS = 2_500L
    /** The one later cue, this long after the command was heard. */
    const val LATE_AFTER_MS = 20_000L

    /** Something said this recently counts as Jarvis talking (see [talking]). */
    const val TALKING_MS = 5_000L

    const val STILL_WORKING = "Still working on it."
    const val STILL_BUSY = "I'm still busy with the last thing — I'll get to it, or say stop."

    enum class Cue { NONE, STILL_WORKING, STILL_BUSY }

    /**
     * The late cue for command [seq] (commands are numbered in the order heard).
     * [clearedUpTo]: commands numbered up to it were stopped or replaced. [startedSeq] /
     * [startedAt]: the command the runner last started, and when. [answeredAt]: when Jarvis last
     * said something for a command (an answer, a refusal, a failure). [doneAt]: when the last
     * started command finished. [hushed]: a run in progress was talked over, so nothing may talk
     * over the person. [talking]: Jarvis is speaking (or just spoke) right now: busy out loud, not
     * frozen. [onItEnabled]: the "On it." setting, which "Still working on it." follows; the busy
     * line is said regardless, as it tells how to get out of the wait ("say stop").
     */
    fun late(
        seq: Long, clearedUpTo: Long, startedSeq: Long, startedAt: Long,
        answeredAt: Long, doneAt: Long, hushed: Boolean, talking: Boolean, onItEnabled: Boolean,
    ): Cue = when {
        seq <= clearedUpTo || hushed || talking -> Cue.NONE
        startedSeq < seq -> Cue.STILL_BUSY                 // still waiting its turn
        startedSeq > seq -> Cue.NONE                       // done: a later command has started
        answeredAt >= startedAt || doneAt >= startedAt -> Cue.NONE
        onItEnabled -> Cue.STILL_WORKING
        else -> Cue.NONE
    }

    /** Pure, JVM-tested. Something said within [TALKING_MS] of [nowMs] (a streamed answer between
     *  two sentences) counts as talking, as does speech playing ([speaking]). */
    fun talking(speaking: Boolean, answeredAt: Long, nowMs: Long): Boolean =
        speaking || nowMs - answeredAt in 0 until TALKING_MS

    fun text(cue: Cue): String? = when (cue) {
        Cue.NONE -> null
        Cue.STILL_WORKING -> STILL_WORKING
        Cue.STILL_BUSY -> STILL_BUSY
    }
}
