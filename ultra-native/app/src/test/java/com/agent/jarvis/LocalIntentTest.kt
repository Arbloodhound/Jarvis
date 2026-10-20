package com.agent.jarvis

import com.agent.jarvis.agent.Brain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the on-device fast path accepts, and how it reads an alarm time. */
class LocalIntentTest {

    @Test
    fun `timers and start requests go to the cloud`() {
        // The local catalog has no timer tool: these were mapped onto the wrong tool.
        assertFalse(Brain.isSimpleLocalIntent("set a timer for 5 minutes"))
        assertFalse(Brain.isSimpleLocalIntent("start the stopwatch"))
    }

    @Test
    fun `plain device commands still run on-device`() {
        assertTrue(Brain.isSimpleLocalIntent("turn on the flashlight"))
        assertTrue(Brain.isSimpleLocalIntent("open chrome"))
        assertTrue(Brain.isSimpleLocalIntent("set an alarm for 7 30 am"))
    }

    @Test
    fun `alarm times are read with their minutes`() {
        assertEquals(7 to 30, Brain.alarmTime("set an alarm for 7 30 am"))
        assertEquals(19 to 30, Brain.alarmTime("set an alarm for 7:30 pm"))
        assertEquals(7 to 0, Brain.alarmTime("wake me at 7am"))
        assertEquals(0 to 15, Brain.alarmTime("alarm at 12:15 am"))
        assertEquals(null, Brain.alarmTime("set an alarm"))
    }
}
