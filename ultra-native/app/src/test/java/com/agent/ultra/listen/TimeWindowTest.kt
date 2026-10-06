package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TimeWindowTest {
    private val zone = ZoneId.of("UTC")
    private val now = ms(2026, 10, 7, 15, 30)                     // Wednesday 15:30
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun p(q: String) = TimeWindow.parse(q, now, zone)

    @Test fun yesterdayIsTheWholeDay() {
        val w = p("What did Sam say yesterday?")!!
        assertEquals(ms(2026, 10, 6, 0), w.fromMs); assertEquals(ms(2026, 10, 7, 0), w.toMs)
    }

    @Test fun thisMorningIsFiveToNoonToday() {
        val w = p("anything about the car this morning")!!
        assertEquals(ms(2026, 10, 7, 5), w.fromMs); assertEquals(ms(2026, 10, 7, 12), w.toMs)
    }

    @Test fun yesterdayEveningBeatsYesterday() {
        val w = p("yesterday evening at dinner")!!
        assertEquals(ms(2026, 10, 6, 17), w.fromMs); assertEquals(ms(2026, 10, 6, 22), w.toMs)
    }

    @Test fun lastNightSpansMidnight() {
        val w = p("what was said last night")!!
        assertEquals(ms(2026, 10, 6, 18), w.fromMs); assertEquals(ms(2026, 10, 7, 5), w.toMs)
    }

    @Test fun lastTuesdayIsTheMostRecentTuesday() {
        val w = p("last Tuesday")!!
        assertEquals(ms(2026, 10, 6, 0), w.fromMs); assertEquals(ms(2026, 10, 7, 0), w.toMs)
        val mon = p("on monday")!!
        assertEquals(ms(2026, 10, 5, 0), mon.fromMs)
        // Said on a Wednesday, "Wednesday" means a week ago, not today.
        assertEquals(ms(2026, 9, 30, 0), p("wednesday")!!.fromMs)
    }

    @Test fun nextTuesdayIsAPlanNotAWindow() {
        assertNull(p("what's happening next Tuesday"))
        assertNull(p("this coming friday"))
    }

    @Test fun hoursAgoAndTodayNeverRunPastNow() {
        val w = p("about two hours ago")!!
        assertEquals(now - 3 * 3_600_000L, w.fromMs); assertEquals(now - 3_600_000L, w.toMs)
        val t = p("earlier today")!!
        assertEquals(ms(2026, 10, 7, 0), t.fromMs); assertTrue(t.toMs <= now + 1)
        assertTrue(now in p("this afternoon")!!)
    }

    @Test fun lastWeekIsTheCalendarWeekBefore() {
        val w = p("last week")!!
        assertEquals(ms(2026, 9, 28, 0), w.fromMs); assertEquals(ms(2026, 10, 5, 0), w.toMs)
    }

    @Test fun noTimeNamed() {
        assertNull(p("what did the dentist say about my teeth"))
    }

    @Test fun stripRemovesOnlyTheTimePhrase() {
        val q = "What did Sam say about the dentist yesterday?"
        assertEquals("What did Sam say about the dentist", TimeWindow.strip(q, p(q)))
        assertEquals(q, TimeWindow.strip(q, null))
    }
}
