package com.agent.jarvis.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelDownloadTest {
    @Test fun freshPlanSplitsEvenlyAndCoversTheFile() {
        val p = ParallelDownload.plan(1000, 0, 4)
        assertEquals(listOf(0L to 250L, 250L to 500L, 500L to 750L, 750L to 1000L), p.segs.map { it.start to it.end })
        assertEquals(0L, p.written)
        assertFalse(p.finished)
    }

    @Test fun anOldPartBecomesTheFirstFinishedRange() {
        val p = ParallelDownload.plan(1000, 400, 3)
        assertEquals(ParallelDownload.Seg(0, 400, 400), p.segs[0])
        assertEquals(1000L, p.segs.last().end)
        assertEquals(400L, p.written)
        assertEquals(4, p.segs.size)
    }

    @Test fun completePartNeedsNothing() = assertTrue(ParallelDownload.plan(1000, 1000).finished)

    @Test fun planRoundTrips() {
        val p = ParallelDownload.plan(10_000, 1234).let { it.copy(segs = it.segs.map { s -> s.copy(done = minOf(s.length, 77)) }) }
        assertEquals(p, ParallelDownload.decode(ParallelDownload.encode(p), 10_000))
    }

    @Test fun badPlansAreRefused() {
        val good = ParallelDownload.encode(ParallelDownload.plan(1000, 0, 2))
        assertNull(ParallelDownload.decode(good, 999))                              // another file
        assertNull(ParallelDownload.decode("total 1000\n0 500 0\n600 1000 0\n", 1000))  // gap
        assertNull(ParallelDownload.decode("total 1000\n0 500 501\n500 1000 0\n", 1000)) // claims too much
        assertNull(ParallelDownload.decode("total 1000\n0 500 0\n", 1000))            // short
        assertNull(ParallelDownload.decode("garbage", 1000))
    }

    @Test fun onlyExactRangesAreTrusted() {
        assertTrue(ParallelDownload.rangeMatches("bytes 250-499/1000", 250, 499, 1000))
        assertFalse(ParallelDownload.rangeMatches("bytes 0-999/1000", 250, 499, 1000))
        assertFalse(ParallelDownload.rangeMatches(null, 250, 499, 1000))
    }

    @Test fun probeGivesTheSizeOnlyForARealRange() {
        assertEquals(2_497_000_000L, ParallelDownload.totalFromProbe(206, "bytes 0-0/2497000000"))
        assertNull(ParallelDownload.totalFromProbe(200, null))
        assertNull(ParallelDownload.totalFromProbe(206, "bytes 0-0/*"))
    }
}
