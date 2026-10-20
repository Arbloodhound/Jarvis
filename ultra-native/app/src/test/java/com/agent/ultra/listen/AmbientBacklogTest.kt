package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientBacklogTest {
    private data class U(val name: String, val samples: Int = 100, val window: Boolean = false)

    private fun admit(q: ArrayDeque<U>, u: U, maxItems: Int = 3, maxSamples: Int = 1_000) =
        AmbientBacklog.admit(q, u, { it.samples }, { it.window }, maxItems, maxSamples)

    private fun names(q: ArrayDeque<U>) = q.map { it.name }

    @Test fun keepsOrderWithinBounds() {
        val q = ArrayDeque<U>()
        assertTrue(admit(q, U("a")).isEmpty())
        assertTrue(admit(q, U("b")).isEmpty())
        assertTrue(admit(q, U("c")).isEmpty())
        assertEquals(listOf("a", "b", "c"), names(q))
    }

    @Test fun overCountDropsOldestMemoryOnly() {
        val q = ArrayDeque(listOf(U("a"), U("b"), U("c")))
        assertEquals(listOf("a"), admit(q, U("d")).map { it.name })
        assertEquals(listOf("b", "c", "d"), names(q))
    }

    @Test fun wakeWindowSpeechOutlivesMemoryOnly() {
        val q = ArrayDeque(listOf(U("cmd", window = true), U("b"), U("c")))
        assertEquals(listOf("b"), admit(q, U("d")).map { it.name })
        assertEquals(listOf("cmd", "c", "d"), names(q))
    }

    @Test fun newMemoryOnlyDroppedWhenAllOthersAreCommands() {
        val q = ArrayDeque(listOf(U("w1", window = true), U("w2", window = true), U("w3", window = true)))
        assertEquals(listOf("m"), admit(q, U("m")).map { it.name })
        assertEquals(listOf("w1", "w2", "w3"), names(q))
    }

    @Test fun allCommandsDropsOldestCommand() {
        val q = ArrayDeque(listOf(U("w1", window = true), U("w2", window = true), U("w3", window = true)))
        assertEquals(listOf("w1"), admit(q, U("w4", window = true)).map { it.name })
        assertEquals(listOf("w2", "w3", "w4"), names(q))
    }

    @Test fun audioLengthBoundDropsUntilItFits() {
        val q = ArrayDeque(listOf(U("a", 400), U("b", 400)))
        assertEquals(listOf("a"), admit(q, U("c", 400), maxItems = 10).map { it.name })
        assertEquals(listOf("b", "c"), names(q))
    }

    @Test fun loneLongUtteranceKept() {
        val q = ArrayDeque<U>()
        assertTrue(admit(q, U("long", 5_000)).isEmpty())
        assertEquals(listOf("long"), names(q))
    }

    @Test fun defaultsAreSane() {
        assertEquals(32, AmbientBacklog.MAX_ITEMS)
        // Holds a few max-length (28 s) utterances.
        assertTrue(AmbientBacklog.MAX_SAMPLES >= 4 * AmbientConfig.MAX_UTTER_MS / 1000 * AmbientConfig.SAMPLE_RATE)
    }

    @Test fun holdWindowExtendsCurrentWindowFromWhenSpeechEnded() {
        assertEquals(18_000L, AmbientBacklog.holdWindowUntil(12_000L, 5L, 5L, 10_000L, 8_000L))
        assertEquals(30_000L, AmbientBacklog.holdWindowUntil(30_000L, 5L, 5L, 10_000L, 8_000L))  // never shortens
        // A closed window (0) is reopened only for its own still-current id.
        assertEquals(18_000L, AmbientBacklog.holdWindowUntil(0L, 5L, 5L, 10_000L, 8_000L))
    }

    @Test fun holdWindowLeavesOtherWindowsAlone() {
        assertEquals(12_000L, AmbientBacklog.holdWindowUntil(12_000L, 6L, 5L, 10_000L, 8_000L))  // newer window open
        assertEquals(0L, AmbientBacklog.holdWindowUntil(0L, 5L, 0L, 10_000L, 8_000L))            // memory-only speech
    }

    @Test fun transcribesOnlyAfterTheBrainHasSettled() {
        assertFalse(AmbientBacklog.mayTranscribe(true, 100_000L, 0L, 2_000L))        // generating
        assertFalse(AmbientBacklog.mayTranscribe(false, 100_000L, 99_500L, 2_000L))  // gap between steps
        assertTrue(AmbientBacklog.mayTranscribe(false, 100_000L, 98_000L, 2_000L))   // idle 2 s
        assertTrue(AmbientBacklog.mayTranscribe(false, 100_000L, 0L, 2_000L))        // long idle
    }

    /** Pixel Feature check: the brain stayed busy for minutes, nothing was transcribed and the
     *  backlog kept dropping. Past the cap the backlog drains while the brain is still busy. */
    @Test fun holdIsCapped() {
        val cap = AmbientBacklog.MAX_HOLD_MS
        assertFalse(AmbientBacklog.shouldDrain(true, 100_000L, 0L, 100_000L - cap + 1))   // not yet
        assertTrue(AmbientBacklog.shouldDrain(true, 100_000L, 0L, 100_000L - cap))        // waited the cap
        assertTrue(AmbientBacklog.shouldDrain(false, 100_000L, 0L, 99_000L))              // idle: at once
        assertFalse(AmbientBacklog.shouldDrain(true, 100_000L, 0L, null))                  // nothing queued
        assertFalse(AmbientBacklog.shouldDrain(false, 100_000L, 99_500L, 99_000L))        // settling, under cap
    }

    @Test fun unpinnedWhisperWaitsLongerButStillBounded() {
        assertEquals(AmbientBacklog.MAX_HOLD_MS, AmbientBacklog.maxHoldMs(true))
        assertTrue(AmbientBacklog.maxHoldMs(false) > AmbientBacklog.MAX_HOLD_MS)
        val cap = AmbientBacklog.maxHoldMs(false)
        assertTrue(AmbientBacklog.shouldDrain(true, 200_000L, 0L, 200_000L - cap, cap))
        assertFalse(AmbientBacklog.shouldDrain(true, 200_000L, 0L, 200_000L - AmbientBacklog.MAX_HOLD_MS, cap))
    }

    @Test fun waitsUntilTheCapOrTheSettleGap() {
        // busy: until the oldest utterance reaches the cap
        assertEquals(5_000L, AmbientBacklog.waitMs(true, 100_000L, 0L, 90_000L, 15_000L))
        // idle but settling: the settle gap is sooner
        assertEquals(1_500L, AmbientBacklog.waitMs(false, 100_000L, 99_500L, 90_000L, 15_000L))
        // cap already passed: never 0 or negative
        assertEquals(1L, AmbientBacklog.waitMs(true, 100_000L, 0L, 0L, 15_000L))
        // nothing queued and busy: bounded, not forever
        assertEquals(15_000L, AmbientBacklog.waitMs(true, 100_000L, 0L, null, 15_000L))
    }

    /** (window, short) pairs: what goes now while an answer runs. */
    @Test fun duringAnAnswerOnlyJarvisSpeechAndAShortStopGoAtOnce() {
        val q = listOf(false to false, false to true, true to false)
        val win = { u: Pair<Boolean, Boolean> -> u.first }
        val short = { u: Pair<Boolean, Boolean> -> u.second }
        assertEquals(0, AmbientBacklog.next(q, win, short, drain = true, running = true))
        assertEquals(2, AmbientBacklog.next(q, win, short, drain = false, running = true))
        val noWindow = q.take(2)
        assertEquals(1, AmbientBacklog.next(noWindow, win, short, drain = false, running = true))
        // Brain busy without a run (e.g. loading): a short clip waits too.
        assertEquals(null, AmbientBacklog.next(noWindow, win, short, drain = false, running = false))
        assertEquals(null, AmbientBacklog.next(listOf(false to false), win, short, drain = false, running = true))
        assertEquals(null, AmbientBacklog.next(emptyList(), win, short, drain = true, running = true))
    }
}
