package com.agent.ultra.listen

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * "When" in a recall question → a [from, to) window of epoch millis. Pure, JVM-tested.
 *
 * Understands: today, yesterday, this morning/afternoon/evening, tonight, last night,
 * yesterday morning/afternoon/evening, earlier / just now / a minute ago, the last /
 * past hour, N hours ago, N days ago, the day before yesterday, on <weekday> /
 * last <weekday>, this week, last week, past few days, this month, last month.
 * Day parts: morning 05–12, afternoon 12–17, evening 17–22, night 18–05 next day.
 * A window never extends past [now]; one that would start after [now] ("this evening" asked
 * at 10 am) is a plan, not when something was heard, so it is null like no time at all.
 * Returns null when the question names no time.
 */
data class TimeWindow(val fromMs: Long, val toMs: Long, val phrase: String) {
    operator fun contains(ts: Long) = ts in fromMs until toMs

    companion object {
        private val WORD_NUM = mapOf(
            "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "couple of" to 2, "few" to 3,
        )
        private const val NUM = "(\\d{1,2}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|couple of|few)"
        private val HOURS_AGO = Regex("\\b$NUM hours? ago\\b")
        private val DAYS_AGO = Regex("\\b$NUM days? ago\\b")
        private val WEEKDAY = Regex("\\b(?:on |last )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")

        fun parse(query: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): TimeWindow? {
            val q = " " + query.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ") + " "
            val nowZ = Instant.ofEpochMilli(now).atZone(zone)
            val today = nowZ.toLocalDate()
            fun at(d: LocalDate, h: Int): Long = d.atTime(LocalTime.of(h, 0)).atZone(zone).toInstant().toEpochMilli()
            fun day(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
            fun w(from: Long, to: Long, phrase: String): TimeWindow? =
                if (from > now) null else TimeWindow(from, minOf(to, now + 1), phrase)
            fun has(p: String) = q.contains(" $p ")
            val yday = today.minusDays(1)

            // Most specific first.
            if (has("day before yesterday")) return w(day(today.minusDays(2)), day(yday), "day before yesterday")
            for ((part, from, to) in listOf(Triple("morning", 5, 12), Triple("afternoon", 12, 17), Triple("evening", 17, 22))) {
                if (has("yesterday $part")) return w(at(yday, from), at(yday, to), "yesterday $part")
                if (has("this $part") || (has(part) && has("today"))) return w(at(today, from), at(today, to), "this $part")
            }
            if (has("last night")) return w(at(yday, 18), at(today, 5), "last night")
            if (has("tonight")) return w(at(today, 17), day(today.plusDays(1)), "tonight")
            if (has("yesterday")) return w(day(yday), day(today), "yesterday")
            // The phrase is the words actually used, so strip() removes exactly them.
            listOf("just now", "a minute ago", "a moment ago", "a few minutes ago").firstOrNull { has(it) }
                ?.let { return w(now - 15 * 60_000L, now, it) }
            listOf("last hour", "past hour").firstOrNull { has(it) }?.let { return w(now - 3_600_000L, now, it) }
            HOURS_AGO.find(q)?.let { m ->
                val n = num(m.groupValues[1])
                return w(now - (n + 1) * 3_600_000L, now - maxOf(0, n - 1) * 3_600_000L, m.value.trim())
            }
            if (has("today") || has("earlier today")) return w(day(today), now, "today")
            DAYS_AGO.find(q)?.let { m ->
                val n = num(m.groupValues[1])
                if (m.groupValues[1] == "few" || m.groupValues[1] == "couple of")
                    return w(day(today.minusDays(n.toLong())), now, m.value.trim())
                val d = today.minusDays(n.toLong())
                return w(day(d), day(d.plusDays(1)), m.value.trim())
            }
            listOf("past few days", "last few days", "past couple of days", "last couple of days")
                .firstOrNull { has(it) }?.let { return w(day(today.minusDays(3)), now, it) }
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            if (has("this week")) return w(day(monday), now, "this week")
            if (has("last week") || has("past week")) {
                return if (has("past week")) w(day(today.minusDays(7)), now, "past week")
                else w(day(monday.minusDays(7)), day(monday), "last week")
            }
            if (has("this month")) return w(day(today.withDayOfMonth(1)), now, "this month")
            if (has("last month")) {
                val first = today.withDayOfMonth(1)
                return w(day(first.minusMonths(1)), day(first), "last month")
            }
            if (has("earlier")) return w(now - 3 * 3_600_000L, now, "earlier")
            // "next Tuesday" / "this coming Tuesday" is a plan, not when something was heard.
            WEEKDAY.findAll(q).firstOrNull { m ->
                !q.substring(0, m.range.first).trimEnd().let { it.endsWith(" next") || it.endsWith(" coming") }
            }?.let { m ->
                val dow = DayOfWeek.valueOf(m.groupValues[1].uppercase())
                // The most recent such day before today ("Monday" said on a Monday = a week ago).
                val d = today.with(TemporalAdjusters.previous(dow))
                return w(day(d), day(d.plusDays(1)), m.value.trim())
            }
            return null
        }

        private fun num(s: String): Int = s.toIntOrNull() ?: WORD_NUM[s] ?: 1

        /** The question without its time phrase, so "yesterday" isn't searched for as a word.
         * Only the phrase itself goes: "the last thing Sam said last week" keeps the first
         * "last". If the phrase isn't there word for word ("morning … today" → "this morning"),
         * the first occurrence of each of its words goes instead. */
        fun strip(query: String, window: TimeWindow?): String {
            if (window == null) return query
            val phrase = window.phrase.split(' ').filter { it.isNotEmpty() }
            val toks = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
            fun norm(t: String) = t.lowercase().trim { !it.isLetterOrDigit() }
            val n = toks.map(::norm)
            val drop = HashSet<Int>()
            val at = (0..n.size - phrase.size).firstOrNull { i -> phrase.indices.all { n[i + it] == phrase[it] } }
            if (at != null) drop += at until at + phrase.size
            else for (p in phrase) n.indices.firstOrNull { it !in drop && n[it] == p }?.let { drop += it }
            return toks.filterIndexed { i, _ -> i !in drop }.joinToString(" ")
        }
    }
}
