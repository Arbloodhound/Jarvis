package com.agent.jarvis.local

import com.agent.jarvis.local.DownloadPlan.Asset
import com.agent.jarvis.local.DownloadPlan.ItemState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DownloadPlanTest {
    private val brain = Asset("thinker", "Brain", 4_802_478_080L, present = false)
    private val voice = Asset("voice", "Voice", 21_000_000L, present = false)
    private val vad = Asset("vad", "VAD", 900_000L, present = true)
    private val speaker = Asset("speaker", "Speaker", 40_257_283L, present = false, essential = false)

    @Test fun missingAndBytes() {
        val all = listOf(brain, voice, vad, speaker)
        assertEquals(listOf("thinker", "voice", "speaker"), DownloadPlan.missing(all).map { it.id })
        assertEquals(4_802_478_080L + 21_000_000L + 40_257_283L, DownloadPlan.missingBytes(all))
    }

    @Test fun unfinishedDownloadCountsAsDone() {
        val half = brain.copy(partBytes = 2_000_000_000L)
        assertEquals(2_802_478_080L, DownloadPlan.missingBytes(listOf(half)))
        // A .part bigger than the stated size never makes the total negative.
        assertEquals(0L, DownloadPlan.missingBytes(listOf(voice.copy(partBytes = 30_000_000L))))
    }

    @Test fun smallestFirstUnknownLast() {
        val unknown = Asset("x", "X", 0L, present = false)
        val order = DownloadPlan.order(listOf(brain, unknown, speaker, voice, vad)).map { it.id }
        assertEquals(listOf("voice", "speaker", "thinker", "x"), order)
    }

    @Test fun cardOnlyForEssentialMissing() {
        assertTrue(DownloadPlan.showCard(listOf(voice, vad), dismissed = false))
        assertFalse(DownloadPlan.showCard(listOf(voice, vad), dismissed = true))
        // Only the optional speaker model missing: no card.
        assertFalse(DownloadPlan.showCard(listOf(vad, speaker), dismissed = false))
        assertFalse(DownloadPlan.showCard(listOf(vad), dismissed = false))
    }

    @Test fun cardTextAndSizes() {
        assertEquals("Jarvis needs 4.8 GB of downloads", DownloadPlan.cardText(listOf(brain, vad)))
        assertEquals("0.9 MB", DownloadPlan.size(900_000L))
        assertEquals("59.7 MB", DownloadPlan.size(59_700_000L))
        assertEquals("1.1 GB", DownloadPlan.size(1_056_964_608L))
        assertEquals("size unknown", DownloadPlan.size(0L))
    }

    @Test fun fraction() {
        assertEquals(0.5f, DownloadPlan.fraction(50, 100)!!, 1e-6f)
        assertEquals(1f, DownloadPlan.fraction(150, 100)!!, 1e-6f)
        assertNull(DownloadPlan.fraction(10, 0))
    }

    @Test fun statusText() {
        assertEquals("✓", DownloadPlan.status(vad, null))
        assertEquals("✓", DownloadPlan.status(vad, ItemState.Done))
        assertEquals("missing", DownloadPlan.status(voice, null))
        assertEquals("waiting", DownloadPlan.status(voice, ItemState.Queued))
        assertEquals("downloading 42%", DownloadPlan.status(voice, ItemState.Running(0.42f)))
        assertEquals("downloading…", DownloadPlan.status(voice, ItemState.Running(null)))
        assertEquals("failed: HTTP 404", DownloadPlan.status(voice, ItemState.Failed("HTTP 404")))
        assertEquals("missing (2.0 GB done)", DownloadPlan.status(brain.copy(partBytes = 2_000_000_000L), null))
        // Present wins over an old failure (e.g. it arrived by another path).
        assertEquals("✓", DownloadPlan.status(vad, ItemState.Failed("x")))
    }

    @Test fun missingHintNamesTheButton() {
        assertTrue("Settings → DOWNLOADS → Download all missing" in DownloadPlan.MISSING_HINT)
    }

    @Test fun runContinuesAfterFailuresAndThrows() = runBlocking {
        val started = mutableListOf<String>()
        val done = mutableListOf<DownloadPlan.Outcome>()
        val out = DownloadPlan.runSequential(
            listOf("a", "b", "c", "d"),
            fetch = { id ->
                when (id) {
                    "b" -> "no network"
                    "c" -> throw java.io.IOException("reset")
                    else -> null
                }
            },
            onStart = { started += it },
            onDone = { done += it },
        )
        assertEquals(listOf("a", "b", "c", "d"), started)
        assertEquals(out, done)
        assertEquals(listOf(true, false, false, true), out.map { it.ok })
        assertEquals("no network", out[1].error)
        assertEquals("reset", out[2].error)
    }

    @Test fun runSequentialIsOneAtATime() = runBlocking {
        var inFlight = 0
        var maxInFlight = 0
        DownloadPlan.runSequential(listOf("a", "b", "c"), fetch = {
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
            kotlinx.coroutines.yield()
            inFlight--; null
        })
        assertEquals(1, maxInFlight)
    }

    @Test fun cancellationStopsTheRun() = runBlocking {
        val started = mutableListOf<String>()
        try {
            DownloadPlan.runSequential(listOf("a", "b"), fetch = { throw CancellationException("stop") },
                onStart = { started += it })
            fail("expected cancellation")
        } catch (_: CancellationException) {}
        assertEquals(listOf("a"), started)
    }
}
