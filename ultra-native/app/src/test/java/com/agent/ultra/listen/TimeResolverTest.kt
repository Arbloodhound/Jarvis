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

    @Test fun nightSmallHoursAreAfterMidnight() {
        assertEquals(at(1, day = 7), point("tonight at 1", at(22)))
        assertEquals(at(2, day = 7), point("2am tonight", at(22)))
        assertEquals(at(3), point("last night at 3", at(14)))           // early hours of today
        assertEquals(at(2, day = 10), point("Friday night at 2", at(14)))   // Sat 10th
        assertEquals(at(23), point("tonight at 11", at(14)))
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

    @Test fun phraseInFindsTheTimeWordsInText() {
        assertEquals("tomorrow at 3pm", TimeResolver.phraseIn("Dentist appointment with Dr Lee tomorrow at 3pm"))
        assertEquals("next Tuesday at 9am", TimeResolver.phraseIn("Car service next Tuesday at 9am."))
        assertEquals("in 2 hours", TimeResolver.phraseIn("Call Jo back in 2 hours"))
        assertEquals("tonight at 8", TimeResolver.phraseIn("Pick up Sam tonight at 8"))
        assertEquals("March 5th", TimeResolver.phraseIn("Anna's birthday party on March 5th"))
        assertEquals("this weekend", TimeResolver.phraseIn("Paint the fence this weekend"))
        assertEquals("at noon", TimeResolver.phraseIn("Lunch with the team at noon"))
    }

    @Test fun phraseInIgnoresTextWithoutTimes() {
        assertEquals(null, TimeResolver.phraseIn("Renew the passport before the Lisbon trip"))
        assertEquals(null, TimeResolver.phraseIn("Jo lives at 12 Baker Street"))
        assertEquals(null, TimeResolver.phraseIn("Meet at 3 Station Road"))
        assertEquals(null, TimeResolver.phraseIn("Party at Jo's"))
        assertEquals(null, TimeResolver.phraseIn(""))
        assertEquals(null, TimeResolver.phraseIn(null))
    }

    @Test fun phraseInResolvesLikeTheModelsOwnWhen() {
        val anchor = java.time.ZonedDateTime.of(2026, 10, 6, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val p = TimeResolver.phraseIn("Dentist appointment with Dr Lee tomorrow at 3pm")
        assertEquals(TimeResolver.resolve("tomorrow at 3pm", anchor, zone), TimeResolver.resolve(p, anchor, zone))
    }

    @Test fun phraseInSkipsNonTimes() {
        assertNull(TimeResolver.phraseIn("Need to look at two flats in Leeds"))
        assertNull(TimeResolver.phraseIn("At one point we should repaint the hall"))
        assertNull(TimeResolver.phraseIn("Party at Sam's, maybe 2 may come along"))
        assertEquals("the 5th of May", TimeResolver.phraseIn("Anna's party on the 5th of May"))
        assertEquals("at 7", TimeResolver.phraseIn("Dinner at 7, bring wine"))
        assertEquals("at 3 tomorrow", TimeResolver.phraseIn("Meet Jo at 3 tomorrow"))
    }

    @Test fun twoSeparateTimesAreNotGuessed() {
        assertNull(TimeResolver.phraseIn("Lost the card yesterday, need to call the bank next Friday"))
        assertEquals("on Friday, at 9", TimeResolver.phraseIn("Call the bank on Friday, at 9"))
    }
}
