package com.agent.ultra.gate

import com.agent.ultra.agent.ScamSignals

/**
 * What recently arrived that looked like a scam, and what it pointed at.
 *
 * The gate trusts the user: a number the user names traces, and the send goes
 * through. That is exactly the hole a scam text uses — it gets the *person* to
 * name the scammer's number, link or cashtag. So when a flagged message arrives,
 * its targets are held here for [TTL_MS], and the gate treats any of them in an
 * outbound action as needing the user's explicit, informed confirmation
 * (`scam_followup`), never an auto-approve.
 *
 * Process memory only. Nothing about the message is written to disk: the
 * targets live as long as the app process, which is the right lifetime for "a
 * text that arrived in the last few days" and leaves no record of anyone's inbox.
 */
object ScamWatch {

    const val TTL_MS = 72L * 60 * 60 * 1000
    private const val MAX = 50

    data class Flag(val atMs: Long, val reasons: String, val targets: Set<String>, val from: String)

    private val flags = ArrayDeque<Flag>()

    /** Record a message the detector flagged. No-op when it pointed at nothing. */
    @Synchronized
    fun remember(assessment: ScamSignals.Assessment, from: String, nowMs: Long = System.currentTimeMillis()) {
        if (!assessment.scam || assessment.targets.isEmpty()) return
        flags.addLast(Flag(nowMs, assessment.reasons, assessment.targets, from.take(40)))
        while (flags.size > MAX) flags.removeFirst()
    }

    /** The flag whose targets appear in [value], if any recent one does. */
    @Synchronized
    fun match(value: String, nowMs: Long = System.currentTimeMillis()): Pair<Flag, String>? {
        flags.removeAll { nowMs - it.atMs > TTL_MS }
        if (flags.isEmpty() || value.isBlank()) return null
        val found = ScamSignals.targetsIn(value)
        if (found.isEmpty()) return null
        for (f in flags.asReversed()) {
            val hit = found.firstOrNull { it in f.targets } ?: continue
            return f to hit.substringAfter(':')
        }
        return null
    }

    @Synchronized
    fun recent(nowMs: Long = System.currentTimeMillis()): List<Flag> {
        flags.removeAll { nowMs - it.atMs > TTL_MS }
        return flags.toList()
    }

    @Synchronized
    fun clear() = flags.clear()
}
