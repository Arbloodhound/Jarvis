package com.agent.jarvis.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSettleTest {
    @Test fun settledNeedsAnEventAfterTheActionThenQuiet() {
        assertFalse("no event since the action", ScreenSettle.settled(1_000, 500, 400, 300, 150))
        assertFalse("still changing", ScreenSettle.settled(1_000, 500, 900, 300, 150))
        assertTrue(ScreenSettle.settled(1_300, 500, 900, 300, 150))
        assertFalse("not before the minimum", ScreenSettle.settled(600, 500, 520, 50, 150))
    }

    @Test fun returnsSoonAfterTheScreenGoesQuiet() = runBlocking {
        var t = 0L
        val waited = ScreenSettle.await(sinceMs = 0, maxMs = 900, quietMs = 300, pollMs = 1,
            lastEvent = { 200 }, now = { t.also { t += 50 } })
        assertEquals(500, waited)
    }

    @Test fun neverLongerThanTheOldFixedWait() = runBlocking {
        var t = 0L
        assertEquals(900, ScreenSettle.await(sinceMs = 0, maxMs = 900, pollMs = 1, lastEvent = { 0 }, now = { t.also { t += 100 } }))
    }
}
