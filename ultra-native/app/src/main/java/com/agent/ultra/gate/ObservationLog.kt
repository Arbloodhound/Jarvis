package com.agent.ultra.gate

/**
 * All observations made during one episode (one user request).
 *
 * Lives inside Gate.Episode and is cleared with it. The log is append-only
 * within the episode — facts are never removed, only decayed by age.
 *
 * The point is not to enforce anything yet. The point is to carry the data
 * so future rules (risk scoring, composition resistance, confidence-gated
 * escalation) have something to read. Logging is the first consumer: every
 * fact reaches logcat tagged HIGH or LOW, making the agent's information
 * quality auditable from outside.
 */
class ObservationLog {

    private val facts = mutableListOf<Fact>()

    fun record(tool: String, source: Fact.Source, summary: String = "") {
        val f = Fact(
            tool = tool,
            source = source,
            confidence = Fact.confidenceOf(source),
            summary = summary.take(120),
        )
        facts += f
        android.util.Log.i(TAG, "OBSERVE ${f.confidence} ${f.source} via ${f.tool}" +
            if (f.summary.isNotBlank()) ": ${f.summary}" else "")
    }

    fun record(fact: Fact) {
        facts += fact
        android.util.Log.i(TAG, "OBSERVE ${fact.confidence} ${fact.source} via ${fact.tool}" +
            if (fact.summary.isNotBlank()) ": ${fact.summary}" else "")
    }

    fun all(): List<Fact> = facts.toList()

    val size: Int get() = facts.size

    fun hasHighConfidenceRecent(maxAgeMs: Long = Fact.MAX_AGE_MS): Boolean =
        facts.any { it.effectiveConfidence(maxAgeMs) == Fact.Confidence.HIGH }

    fun hasLowConfidenceOnly(maxAgeMs: Long = Fact.MAX_AGE_MS): Boolean =
        facts.isNotEmpty() && facts.all {
            it.effectiveConfidence(maxAgeMs) == Fact.Confidence.LOW
        }

    fun highCount(maxAgeMs: Long = Fact.MAX_AGE_MS): Int =
        facts.count { it.effectiveConfidence(maxAgeMs) == Fact.Confidence.HIGH }

    fun lowCount(maxAgeMs: Long = Fact.MAX_AGE_MS): Int =
        facts.count { it.effectiveConfidence(maxAgeMs) == Fact.Confidence.LOW }

    /** Tool observations only — excludes the initial USER_REQUEST. Gate rules
     *  that care about information quality check these, not the raw counts,
     *  because the user request is always HIGH and would mask a tree-only run. */
    fun toolObservations(): List<Fact> =
        facts.filter { it.source != Fact.Source.USER_REQUEST }

    fun hasHighToolObservation(maxAgeMs: Long = Fact.MAX_AGE_MS): Boolean =
        toolObservations().any { it.effectiveConfidence(maxAgeMs) == Fact.Confidence.HIGH }

    fun hasLowToolOnly(maxAgeMs: Long = Fact.MAX_AGE_MS): Boolean {
        val tools = toolObservations()
        return tools.isNotEmpty() && tools.all {
            it.effectiveConfidence(maxAgeMs) == Fact.Confidence.LOW
        }
    }

    /** Summary line for the end-of-request log. */
    fun summary(): String {
        if (facts.isEmpty()) return "no observations"
        val h = highCount()
        val l = lowCount()
        return "${facts.size} observations ($h HIGH, $l LOW)"
    }

    companion object {
        private const val TAG = "UltraObserve"
    }
}
