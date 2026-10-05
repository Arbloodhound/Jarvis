package com.agent.ultra.agent

/**
 * Deciding whether a remembered task is the same task being asked for now.
 *
 * Pulled out of the brain so it can be argued with in a test rather than only
 * on a phone, because getting this wrong is not a wasted turn — it is the agent
 * doing something the user did not ask for.
 */
object TaskMatch {

    /**
     * How much two requests are the same job, from 0 to 1.
     *
     * Containment alone — dividing the overlap by the SMALLER request — meant a
     * short memory was trivially "contained" in any longer request that
     * happened to mention it. "battery level" scored a perfect 1.0 against
     * "check the battery level then open my banking app and pay the bill",
     * because every word of the memory appears in the request. The agent would
     * then be told that this exact job had succeeded before with one tool, and
     * the banking half of the sentence simply evaporates.
     *
     * That is the same failure that replaying a learned routine would have, one
     * size larger: a remembered thing firing on a request that merely *contains*
     * it.
     *
     * So both directions have to hold. Containment keeps the original
     * behaviour — the same job asked at different lengths still matches, which
     * plain Jaccard punishes for no reason. Coverage asks the other question:
     * how much of what the user actually said does this memory account for? A
     * memory that explains two words of a twelve-word request is not that
     * request, however completely those two words are contained.
     */
    fun score(mine: Set<String>, theirs: Set<String>): Double {
        if (mine.isEmpty() || theirs.isEmpty()) return 0.0
        val overlap = mine.intersect(theirs).size.toDouble()
        if (overlap == 0.0) return 0.0
        val containment = overlap / minOf(mine.size, theirs.size)
        val coverage = overlap / mine.size
        return if (coverage < MIN_COVERAGE) 0.0 else containment
    }

    /**
     * How much of the new request a memory must account for before it is
     * allowed to claim it.
     *
     * Deliberately not high. A rephrasing carries extra words — "how is the
     * battery doing" against "battery level" — and should still match. What it
     * must exclude is a memory that explains a fragment of a compound request
     * and stays silent about the rest.
     */
    const val MIN_COVERAGE = 0.4
}
