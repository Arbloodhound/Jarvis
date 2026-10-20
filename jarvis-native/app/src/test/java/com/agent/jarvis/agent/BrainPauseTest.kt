package com.agent.jarvis.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Policy-gate pauses: a card confirmed after 2 minutes is not acted on, and a confirm with the
 * cloud brain gone says so. Pure helpers plus the real [Brain] against [FakeChatServer], with
 * Robolectric's SystemClock moved forward.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrainPauseTest {
    private lateinit var h: BrainHarness

    @Before fun setUp() { h = BrainHarness() }
    @After fun tearDown() { h.close() }

    @Test fun pauseExpiresAfterTwoMinutes() {
        assertFalse(Brain.pauseExpired(1_000L, 1_000L))
        assertFalse(Brain.pauseExpired(0L, 119_000L))
        assertFalse(Brain.pauseExpired(0L, 120_000L))
        assertTrue(Brain.pauseExpired(0L, 121_000L))
        assertTrue("a clock that went backwards is expired", Brain.pauseExpired(5_000L, 4_000L))
        assertTrue(Brain.pauseExpired(0L, 11_000L, timeoutMs = 10_000L))
    }

    @Test fun blankOnDeviceAnswerGetsALine() {
        assertEquals(Brain.EMPTY_LOCAL_ANSWER, Brain.blankAnswerFallback(""))
        assertEquals(Brain.EMPTY_LOCAL_ANSWER, Brain.blankAnswerFallback(" \n\n "))
        assertNull(Brain.blankAnswerFallback("Paris."))
        assertEquals("Sorry, I didn't get an answer out — try asking again.", Brain.EMPTY_LOCAL_ANSWER)
    }

    private fun pauseAnSms() {
        h.server.reply("""{"tool":"sms_send","params":{"to":"5551234567","message":"running late"}}""")
        h.drive { h.brain.run("text my wife that I'm running late") }
        assertNotNull(h.brain.pendingConfirm)
    }

    @Test fun confirmAfter121SecondsIsCancelledAndAudited() {
        pauseAnSms()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(121))
        h.drive { h.brain.resolvePending(true) }
        assertNull(h.brain.pendingConfirm)
        assertEquals("Cancelled: Send a text to 5551234567: \"running late\" (it waited over 2 minutes — ask again)",
            h.answers.last())
        assertEquals("nothing resumed", 1, h.server.requests.size)
        assertTrue(h.awaitAudit { it.optString("tool") == "sms_send" && it.optString("outcome") == "BLOCKED" &&
            it.optString("rule") == "confirm_expired" })
        assertTrue("never run", h.auditLines().none { it.optString("outcome") == "OVERRIDDEN" })
    }

    @Test fun confirmAfter119SecondsStillResumes() {
        pauseAnSms()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(119))
        h.server.reply("I couldn't send it.")
        h.server.reply("I couldn't send it.")
        h.drive { h.brain.resolvePending(true) }
        assertEquals(3, h.server.requests.size)
        assertTrue(h.server.lastMessageOf(1).getString("content").startsWith("[RESULT: sms_send] STATUS: failed"))
        assertEquals("I couldn't send it.", h.answers.last())
    }

    @Test fun confirmWithTheCloudBrainGoneSaysSo() {
        pauseAnSms()
        // The key is cleared in Settings while the card waits (the same Brain is kept while a pause is open).
        com.agent.jarvis.provider.ProviderConfig.save(h.ctx,
            com.agent.jarvis.provider.ProviderConfig(baseUrl = h.server.baseUrl, apiKey = "", model = "fake-model"))
        h.drive { h.brain.resolvePending(true) }
        assertNull(h.brain.pendingConfirm)
        assertEquals("Cancelled: Send a text to 5551234567: \"running late\" — the cloud brain isn't set up any more.",
            h.answers.last())
        assertEquals(1, h.server.requests.size)
    }
}
