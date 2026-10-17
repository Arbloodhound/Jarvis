package com.agent.ultra.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorConnectTest {
    private class Clock(var t: Long = 0) { fun now() = t.also { t += 1_000 } }

    @Test fun orbotStatusStrings() {
        assertEquals(TorConnect.State.ON, TorConnect.stateOf("ON"))
        assertEquals(TorConnect.State.OFF, TorConnect.stateOf("STOPPING"))
        assertEquals(TorConnect.State.STARTS_DISABLED, TorConnect.stateOf("STARTS_DISABLED"))
        assertEquals(null, TorConnect.stateOf("weird"))
    }

    @Test fun connectedOnceTheProxyAnswersWithTheTime() = runBlocking {
        val c = Clock(); var polls = 0
        val o = TorConnect.await({ ++polls >= 4 }, { TorConnect.State.STARTING }, waitMs = 60_000, pollMs = 1, now = c::now)
        assertEquals(TorConnect.State.ON, o.state)
        assertEquals(4_000, o.ms)
        assertEquals("Connected to Tor in 4 s (Orbot, 127.0.0.1:9050).", TorConnect.describe(o))
    }

    @Test fun startsDisabledStopsWaitingAtOnce() = runBlocking {
        val o = TorConnect.await({ false }, { TorConnect.State.STARTS_DISABLED }, pollMs = 1, now = Clock()::now)
        assertEquals(TorConnect.State.STARTS_DISABLED, o.state)
        assertTrue(TorConnect.describe(o.copy(openedOrbot = true)).contains("tap Start"))
    }

    @Test fun timeoutSaysStillConnectingOrOff() = runBlocking {
        val starting = TorConnect.await({ false }, { TorConnect.State.STARTING }, waitMs = 5_000, pollMs = 1, now = Clock()::now)
        assertEquals(TorConnect.State.STARTING, starting.state)
        assertTrue(TorConnect.describe(starting).startsWith("Still connecting to Tor after 5 s"))
        val off = TorConnect.await({ false }, { null }, waitMs = 5_000, pollMs = 1, now = Clock()::now)
        assertEquals(TorConnect.State.OFF, off.state)
    }

    @Test fun alreadyConnectedIsSaidPlainly() {
        assertEquals("Tor is already connected (Orbot, 127.0.0.1:9050).",
            TorConnect.describe(TorConnect.Outcome(TorConnect.State.ON, 0)))
    }
}
