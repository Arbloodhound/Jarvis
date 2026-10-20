package com.agent.jarvis.harness

/**
 * §5 Proactivity — background suggestions reach the user only when genuinely useful and new.
 * Background work proposes [Candidate]s; [decide] returns send or skip for each, with the reason,
 * using the same reason vocabulary Muse's proactivity worker records.
 *
 * Rules, in order:
 *  - something the user explicitly asked to hear about ([Reason.REQUESTED]) is sent unless already sent;
 *  - the same item ([Candidate.fingerprint]) is never sent twice — no nagging;
 *  - an unconfirmed item (its facts not checked yet) is skipped;
 *  - a topic the user muted is skipped; ONE dismissal never mutes a topic (only an explicit mute does);
 *  - quiet hours hold everything except time-sensitive and requested items;
 *  - at most [Prefs.maxPerDay] sends a day, and one per topic per [Prefs.topicGapHours];
 *  - within the budget, time-sensitive first, then changed, ready-for-decision, new-opportunity.
 * Pure; JVM-tested.
 */
object Proactivity {

    enum class Reason { TIME_SENSITIVE, CHANGED, REQUESTED, READY_FOR_DECISION, NEW_OPPORTUNITY }
    enum class Skip { MOOT, ALREADY_HANDLED, UNCONFIRMED, PREFERENCES, NOT_USEFUL_NOW }

    data class Candidate(val id: String, val topic: String, val title: String, val reason: Reason,
                         val fingerprint: String, val confirmed: Boolean = true, val moot: Boolean = false)

    data class Prefs(val mutedTopics: Set<String> = emptySet(), val quietStartHour: Int = 22, val quietEndHour: Int = 7,
                     val maxPerDay: Int = 3, val topicGapHours: Int = 6)

    /** What was sent before: fingerprint → time, and topic → last time. */
    data class History(val sentFingerprints: Map<String, Long> = emptyMap(), val lastByTopic: Map<String, Long> = emptyMap(),
                       val sentToday: Int = 0)

    data class Decision(val candidate: Candidate, val send: Boolean, val skip: Skip?)

    private val PRIORITY = listOf(Reason.REQUESTED, Reason.TIME_SENSITIVE, Reason.CHANGED, Reason.READY_FOR_DECISION, Reason.NEW_OPPORTUNITY)

    fun decide(candidates: List<Candidate>, prefs: Prefs, history: History, nowMs: Long, hourOfDay: Int): List<Decision> {
        val quiet = if (prefs.quietStartHour <= prefs.quietEndHour) hourOfDay in prefs.quietStartHour until prefs.quietEndHour
            else hourOfDay >= prefs.quietStartHour || hourOfDay < prefs.quietEndHour
        var budget = (prefs.maxPerDay - history.sentToday).coerceAtLeast(0)
        val topicsThisRun = HashSet<String>()
        val seen = HashSet<String>()
        val out = ArrayList<Decision>()
        for (c in candidates.sortedBy { PRIORITY.indexOf(it.reason) }) {
            val skip: Skip? = when {
                c.moot -> Skip.MOOT
                c.fingerprint in history.sentFingerprints || !seen.add(c.fingerprint) -> Skip.ALREADY_HANDLED
                !c.confirmed -> Skip.UNCONFIRMED
                c.reason != Reason.REQUESTED && c.topic.lowercase() in prefs.mutedTopics.map { it.lowercase() } -> Skip.PREFERENCES
                quiet && c.reason != Reason.TIME_SENSITIVE && c.reason != Reason.REQUESTED -> Skip.NOT_USEFUL_NOW
                c.reason != Reason.REQUESTED && (c.topic in topicsThisRun ||
                    (history.lastByTopic[c.topic]?.let { nowMs - it < prefs.topicGapHours * 3_600_000L } ?: false)) -> Skip.NOT_USEFUL_NOW
                c.reason != Reason.REQUESTED && budget <= 0 -> Skip.NOT_USEFUL_NOW
                else -> null
            }
            if (skip == null) {
                if (c.reason != Reason.REQUESTED) budget--
                topicsThisRun += c.topic
            }
            out += Decision(c, skip == null, skip)
        }
        return out
    }

    /** The history after [sent] went out. */
    fun record(history: History, sent: List<Candidate>, nowMs: Long): History = History(
        history.sentFingerprints + sent.associate { it.fingerprint to nowMs },
        history.lastByTopic + sent.associate { it.topic to nowMs },
        history.sentToday + sent.count { it.reason != Reason.REQUESTED })
}
