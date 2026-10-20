package com.agent.jarvis.listen

/**
 * A copy of up to [total] samples of mic audio read by someone else: while ambient listening
 * holds the microphone, the owner's voice enrolment takes its 10 s from ambient capture through
 * this instead of opening a second recorder (Android may give one of two concurrent recorders
 * silence). [offer] is called from the capture thread, [full] / [take] from the enrolment.
 * Pure, JVM-tested.
 */
class PcmTap(private val total: Int) {
    private val buf = ShortArray(total)
    private var got = 0

    /** Appends the first [n] samples of [frame] (copied: the caller reuses its buffer); extra is ignored. */
    @Synchronized
    fun offer(frame: ShortArray, n: Int = frame.size) {
        val k = minOf(n, frame.size, total - got)
        if (k <= 0) return
        frame.copyInto(buf, got, 0, k)
        got += k
    }

    val full: Boolean @Synchronized get() = got >= total

    /** What was collected; null when less than half of [total] arrived (the mic wasn't really there). */
    @Synchronized
    fun take(): ShortArray? = if (got < total / 2 || got == 0) null else buf.copyOf(got)
}
