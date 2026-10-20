package com.agent.jarvis.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorConnectTest {
    private class Clock(var t: Long = 0) { fun now() = t.also { t += 1_000 } }

    @Test fun connectedOnceTorIsReadyWithTheTime() = runBlocking {
        var polls = 0
        val o = TorConnect.await({ ++polls >= 4 }, { 50 }, waitMs = 60_000, pollMs = 1, now = Clock()::now)
        assertEquals(TorConnect.State.ON, o.state)
        assertEquals(4_000, o.ms)
        assertEquals("Connected to Tor in 4 s (in-app Tor).", TorConnect.describe(o))
    }

    @Test fun timeoutSaysStillConnectingWithProgress() = runBlocking {
        val o = TorConnect.await({ false }, { 85 }, waitMs = 5_000, pollMs = 1, now = Clock()::now)
        assertEquals(TorConnect.State.STARTING, o.state)
        assertTrue(TorConnect.describe(o), TorConnect.describe(o).startsWith("Still connecting to Tor after 5 s (85% bootstrapped)"))
    }

    @Test fun alreadyConnectedNamesWhichTor() {
        assertEquals("Tor is already connected (Orbot).",
            TorConnect.describe(TorConnect.Outcome(TorConnect.State.ON, 0, TorConnect.VIA_ORBOT)))
    }

    @Test fun bootstrapProgressFromTorsStatusLine() {
        assertEquals(100, EmbeddedTor.progressOf("NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\""))
        assertEquals(14, EmbeddedTor.progressOf("NOTICE BOOTSTRAP PROGRESS=14 TAG=handshake"))
        assertNull(EmbeddedTor.progressOf("garbage"))
    }

    @Test fun currentProxyFollowsTheInAppPort() {
        TorProxy.embeddedPort = -1
        assertEquals(TorProxy.current(), TorProxy.current())
        TorProxy.embeddedPort = 9_123
        val inApp = TorProxy.current()
        TorProxy.embeddedPort = -1
        assertTrue(inApp !== TorProxy.current())
    }
}
