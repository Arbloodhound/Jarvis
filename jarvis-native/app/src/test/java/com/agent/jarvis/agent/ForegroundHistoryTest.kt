package com.agent.jarvis.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundHistoryTest {
    private val me = "com.agent.jarvis"

    // The phone DIAG of 2026-10-20: Claude, then Jarvis, with Wispr Flow's dictation overlay
    // flashing in front for 30–400 ms while typing into Jarvis.
    @Test fun theAppBeforeJarvisSurvivesADictationFlicker() {
        val h = ForegroundHistory(me)
        h.onForeground("com.anthropic.claude", 0)
        h.onForeground(me, 20_000)
        h.onForeground("com.wispr.flowapp", 21_000)
        h.onForeground(me, 21_400)
        assertEquals("com.anthropic.claude", h.lastOther)
    }

    @Test fun jarvisItselfNeverCounts() {
        val h = ForegroundHistory(me)
        h.onForeground(me, 0)
        h.onForeground("com.android.settings", 10_000)
        assertNull(h.lastOther)
    }

    @Test fun aLaterRealAppReplacesAnEarlierOne() {
        val h = ForegroundHistory(me)
        h.onForeground("com.android.settings", 0)
        h.onForeground("app.vanadium.browser", 5_000)
        h.onForeground(me, 9_000)
        assertEquals("app.vanadium.browser", h.lastOther)
    }

    @Test fun aRepeatOfTheSamePackageIsNotAChange() {
        val h = ForegroundHistory(me)
        h.onForeground("com.android.settings", 0)
        h.onForeground("com.android.settings", 100)
        h.onForeground(me, 2_000)
        assertEquals("com.android.settings", h.lastOther)
    }
}
