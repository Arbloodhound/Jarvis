package com.agent.ultra.listen

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Turns spoken time words ("tomorrow at 3", "next Friday", "in 2 hours", "March 5th") into an
 * absolute span, anchored on the moment the words were overheard. Pure, JVM-tested.
 *
 * The on-device model is asked to copy the words, never to compute dates: date arithmetic is
 * exactly what a 4B model gets wrong, and this does it deterministically.
 *
 * Rules (each a guess a person would make; all tested):
 *  - a bare hour without am/pm: 1–7 → afternoon/evening, 8–11 → morning, 12 → noon; a part of
 *    day ("tonight", "in the morning") decides instead when present;
 *  - a time with no day that is more than an hour in the past → the next day ("at 9" said at 22:00);
 *  - a weekday ("Friday", "this Friday", "next Friday", "on Friday") → the next such day after
 *    today (today's own name → a week ahead); "last Friday" → the most recent one before today;
 *  - a month-day without a year more than 60 days in the past → next year;
 *  - a day without a time → the whole day; a part of day without a time → that part's window.
 * Unrecognised words → null (the note is kept, just without a time).
 */
object TimeResolver {

    /** [end] null = a point in time (a clock time was given). */
    data class Span(val start: Long, val end: Long?)

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )
    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december",
    )
    private val MONTH_RE = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|" +
        "sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "a" to 1, "an" to 1, "a couple of" to 2, "a few" to 3,
    )
    private val NUM = "(\\d{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|a couple of|a few|an|a)"

    private enum class Part(val from: LocalTime, val to: LocalTime, val pm: Boolean) {
        MORNING(LocalTime.of(6, 0), LocalTime.of(12, 0), false),
        AFTERNOON(LocalTime.of(12, 0), LocalTime.of(17, 0), true),
        EVENING(LocalTime.of(17, 0), LocalTime.of(21, 0), true),
        NIGHT(LocalTime.of(18, 0), LocalTime.of(23, 59), true),
    }

    fun resolve(phrase: String?, anchorMs: Long, zone: ZoneId): Span? {
        val p = phrase?.lowercase(Locale.ROOT)?.replace(Regex("\\s+"), " ")?.trim()
            ?.replace("a.m.", "am")?.replace("p.m.", "pm")
            ?.takeIf { it.isNotEmpty() } ?: return null
        val anchor = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(anchorMs), zone)
        val today = anchor.toLocalDate()

        // "in 2 hours" / "in half an hour" / "3 days ago": exact offsets
        Regex("\\bin (half an hour|$NUM (minute|min|hour|day|week)s?)\\b").find(p)?.let { m ->
            if (m.groupValues[1] == "half an hour") return point(anchor.plusMinutes(30), zone)
            val n = number(m.groupValues[2]) ?: return@let
            return offset(anchor, n.toLong(), m.groupValues[3], p, zone)
        }
        Regex("\\b$NUM (minute|min|hour|day|week)s? ago\\b").find(p)?.let { m ->
            val n = number(m.groupValues[1]) ?: return@let
            return offset(anchor, -n.toLong(), m.groupValues[2], p, zone)
        }

        val part = partOfDay(p)
        val time = clockTime(p, part)
        var date: LocalDate? = explicitDate(p, today)
        var dayExplicit = date != null

        if (date == null) {
            date = when {
                Regex("\\bday after tomorrow\\b").containsMatchIn(p) -> today.plusDays(2)
                Regex("\\bday before yesterday\\b").containsMatchIn(p) -> today.minusDays(2)
                Regex("\\btomorrow\\b").containsMatchIn(p) -> today.plusDays(1)
                Regex("\\byesterday|last night\\b").containsMatchIn(p) -> today.minusDays(1)
                Regex("\\b(today|tonight|this (morning|afternoon|evening))\\b").containsMatchIn(p) -> today
                else -> weekday(p, today)
            }
            dayExplicit = date != null
        }

        if (!dayExplicit) {
            weekSpan(p, today)?.let { (a, b) ->
                return Span(a.atStartOfDay(zone).toInstant().toEpochMilli(),
                    b.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1)
            }
        }

        if (time != null) {
            var at = (date ?: today).atTime(time)
            if (!dayExplicit && at.isBefore(anchor.minusHours(1))) at = at.plusDays(1)
            return point(at, zone)
        }
        val day = date ?: if (part != null) today else return null
        if (part != null) {
            return Span(day.atTime(part.from).atZone(zone).toInstant().toEpochMilli(),
                day.atTime(part.to).atZone(zone).toInstant().toEpochMilli())
        }
        return Span(day.atStartOfDay(zone).toInstant().toEpochMilli(),
            day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1)
    }

    private fun point(t: LocalDateTime, zone: ZoneId) = Span(t.atZone(zone).toInstant().toEpochMilli(), null)

    private fun number(s: String): Int? = s.toIntOrNull() ?: NUMBER_WORDS[s]

    private fun offset(anchor: LocalDateTime, n: Long, unit: String, p: String, zone: ZoneId): Span =
        when (unit) {
            "minute", "min" -> point(anchor.plusMinutes(n), zone)
            "hour" -> point(anchor.plusHours(n), zone)
            else -> {
                val day = if (unit == "day") anchor.toLocalDate().plusDays(n) else anchor.toLocalDate().plusWeeks(n)
                val t = clockTime(p, partOfDay(p))
                if (t != null) point(day.atTime(t), zone)
                else Span(day.atStartOfDay(zone).toInstant().toEpochMilli(),
                    day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1)
            }
        }

    private fun partOfDay(p: String): Part? = when {
        Regex("\\b(tonight|night)\\b").containsMatchIn(p) -> Part.NIGHT
        Regex("\\bevening\\b").containsMatchIn(p) -> Part.EVENING
        Regex("\\bafternoon\\b").containsMatchIn(p) -> Part.AFTERNOON
        Regex("\\bmorning\\b").containsMatchIn(p) -> Part.MORNING
        else -> null
    }

    /** A clock time in the phrase, or null. */
    private fun clockTime(p: String, part: Part?): LocalTime? {
        if (Regex("\\b(noon|midday)\\b").containsMatchIn(p)) return LocalTime.NOON
        if (Regex("\\bmidnight\\b").containsMatchIn(p)) return LocalTime.of(23, 59)
        val hourWord = "(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
        Regex("\\b(half past|quarter past|quarter to) $hourWord\\b").find(p)?.let { m ->
            val h = number(m.groupValues[2]) ?: return@let
            if (h !in 1..12) return@let
            val base = LocalTime.of(withMeridiem(h, null, part), 0)
            return when (m.groupValues[1]) {
                "half past" -> base.plusMinutes(30)
                "quarter past" -> base.plusMinutes(15)
                else -> base.minusMinutes(15)
            }
        }
        // 3pm, 3 pm, 3:30pm, 10:15 am
        Regex("\\b(\\d{1,2})(?::(\\d{2}))? ?(am|pm)\\b").find(p)?.let { m ->
            val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toIntOrNull() ?: 0
            if (h !in 1..12 || min > 59) return@let
            return LocalTime.of(withMeridiem(h, m.groupValues[3], part), min)
        }
        // 15:00, 9:30 (24 h when > 12, otherwise the bare-hour rule)
        Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(p)?.let { m ->
            val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) return@let
            return LocalTime.of(if (h in 1..12) withMeridiem(h, null, part) else h, min)
        }
        // "at 3", "at three", "three o'clock"
        val bare = Regex("\\bat $hourWord\\b(?! (day|week|month|year|minute|hour)s?\\b)").find(p)?.groupValues?.get(1)
            ?: Regex("\\b$hourWord o'?clock\\b").find(p)?.groupValues?.get(1)
        bare?.let { w ->
            val h = number(w) ?: return null
            if (h in 1..12) return LocalTime.of(withMeridiem(h, null, part), 0)
            if (h in 13..23) return LocalTime.of(h, 0)
        }
        return null
    }

    private fun withMeridiem(h12: Int, meridiem: String?, part: Part?): Int {
        val pm = when (meridiem) {
            "am" -> false
            "pm" -> true
            else -> part?.pm ?: (h12 in 1..7)
        }
        return when {
            h12 == 12 -> if (meridiem == "am") 0 else 12
            pm -> h12 + 12
            else -> h12
        }
    }

    private fun explicitDate(p: String, today: LocalDate): LocalDate? {
        Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b").find(p)?.let { m ->
            return runCatching {
                LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }.getOrNull()
        }
        val ord = "(\\d{1,2})(?:st|nd|rd|th)?"
        val year = "(?:,? (\\d{4}))?"
        val dm = Regex("\\b(?:the )?$ord(?: of)? $MONTH_RE\\b$year").find(p)
        val md = Regex("\\b$MONTH_RE (?:the )?$ord\\b$year").find(p)
        val (day, month, y) = when {
            dm != null -> Triple(dm.groupValues[1], dm.groupValues[2], dm.groupValues[3])
            md != null -> Triple(md.groupValues[2], md.groupValues[1], md.groupValues[3])
            else -> return null
        }
        val mIdx = MONTHS.indexOfFirst { it.startsWith(month.take(3)) }
        if (mIdx < 0) return null
        val d = day.toInt()
        if (y.isNotEmpty()) return runCatching { LocalDate.of(y.toInt(), mIdx + 1, d) }.getOrNull()
        val thisYear = runCatching { LocalDate.of(today.year, mIdx + 1, d) }.getOrNull() ?: return null
        return if (thisYear.isBefore(today.minusDays(60))) thisYear.plusYears(1) else thisYear
    }

    private fun weekday(p: String, today: LocalDate): LocalDate? {
        val m = Regex("\\b(?:(last|next|this|on|coming) )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?\\b")
            .find(p) ?: return null
        val dow = WEEKDAYS.getValue(m.groupValues[2])
        return if (m.groupValues[1] == "last") today.with(TemporalAdjusters.previous(dow))
            else today.with(TemporalAdjusters.next(dow))
    }

    /** "next week" (Mon–Sun after this week), "this weekend"/"the weekend" (coming Sat–Sun). */
    private fun weekSpan(p: String, today: LocalDate): Pair<LocalDate, LocalDate>? = when {
        Regex("\\bnext week\\b").containsMatchIn(p) ->
            today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
        Regex("\\b(this|the|next) weekend\\b|^weekend$").containsMatchIn(p) -> {
            val sat = if (today.dayOfWeek == DayOfWeek.SATURDAY) today
                else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
            val start = if (p.contains("next weekend") && today.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
                today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)) else sat
            start to start.plusDays(1)
        }
        else -> null
    }
}
