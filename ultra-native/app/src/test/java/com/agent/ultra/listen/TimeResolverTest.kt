package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TimeResolverTest {
    private val zone = ZoneId.of("Europe/London")

    /** Tuesday 6 October 2026. */
    private fun at(h: Int, m: Int = 0, day: Int = 6, month: Int = 10, year: Int = 2026) =
        LocalDateTime.of(year, month, day, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun dayEnd(day: Int, month: Int = 10, year: Int = 2026) = at(0, 0, day, month, year) + 86_400_000L - 1

    private fun point(phrase: String, anchor: Long) = TimeResolver.resolve(phrase, anchor, zone)!!.also {
        assertNull("$phrase should be a point in time", it.end)
    }.start

    @Test fun tomorrowAtClockTime() {
        assertEquals(at(15, day = 7), point("tomorrow at 3pm", at(14)))
        assertEquals(at(15, day = 7), point("tomorrow at 3", at(14)))     // bare 1-7 → afternoon
        assertEquals(at(10, 30, day = 7), point("tomorrow at 10:30am", at(14)))
        assertEquals(at(12, day = 7), point("noon tomorrow", at(14)))
    }

    @Test fun bareTimeInThePastMeansNextDay() {
        assertEquals(at(9, day = 7), point("at 9", at(22)))
        assertEquals(at(16), point("at 4", at(14)))
        assertEquals(at(15, 45), point("quarter to 4", at(14)))
    }

    @Test fun partOfDaySetsMeridiem() {
        assertEquals(at(20), point("tonight at 8", at(14)))
    }

    @Test fun weekdays() {
        val fri = TimeResolver.resolve("next Friday", at(14), zone)!!
        assertEquals(at(0, day = 9), fri.start)
        assertEquals(dayEnd(9), fri.end)
        // the day's own name means a week ahead
        assertEquals(at(0, day = 13), TimeResolver.resolve("on Tuesday", at(14), zone)!!.start)
        assertEquals(at(0, day = 2), TimeResolver.resolve("last Friday", at(14), zone)!!.start)
    }

    @Test fun offsets() {
        assertEquals(at(16), point("in 2 hours", at(14)))
        assertEquals(at(14, 30), point("in half an hour", at(14)))
        assertEquals(at(0, day = 3), TimeResolver.resolve("3 days ago", at(14), zone)!!.start)
        assertEquals(at(0, day = 8), TimeResolver.resolve("in a couple of days", at(14), zone)!!.start)
    }

    @Test fun namedDays() {
        assertEquals(at(0, day = 5), TimeResolver.resolve("yesterday", at(14), zone)!!.start)
        assertEquals(at(0, day = 8), TimeResolver.resolve("the day after tomorrow", at(14), zone)!!.start)
    }

    @Test fun monthDays() {
        assertEquals(at(0, day = 5, month = 11), TimeResolver.resolve("5th of November", at(14), zone)!!.start)
        // more than 60 days ago this year → next year
        assertEquals(at(0, day = 5, month = 3, year = 2027), TimeResolver.resolve("March 5th", at(14), zone)!!.start)
        assertEquals(at(0, day = 1, month = 9), TimeResolver.resolve("September 1st", at(14), zone)!!.start)
        assertEquals(at(0, day = 7, month = 1, year = 2027), TimeResolver.resolve("2027-01-07", at(14), zone)!!.start)
    }

    @Test fun weekSpans() {
        val weekend = TimeResolver.resolve("this weekend", at(14), zone)!!
        assertEquals(at(0, day = 10), weekend.start)
        assertEquals(dayEnd(11), weekend.end)
        val next = TimeResolver.resolve("next week", at(14), zone)!!
        assertEquals(at(0, day = 12), next.start)
        assertEquals(dayEnd(18), next.end)
    }

    @Test fun partOfDayAlreadyOverRollsToTomorrow() {
        val eve = TimeResolver.resolve("in the evening", at(23), zone)!!
        assertEquals(at(17, day = 7), eve.start)
        assertEquals(at(21, day = 7), eve.end)
        val sameDay = TimeResolver.resolve("in the evening", at(14), zone)!!
        assertEquals(at(17), sameDay.start)
    }

    @Test fun unknownWordsGiveNoTime() {
        assertNull(TimeResolver.resolve("soon", at(14), zone))
        assertNull(TimeResolver.resolve("", at(14), zone))
        assertNull(TimeResolver.resolve(null, at(14), zone))
    }
}
