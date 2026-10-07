package com.agent.ultra.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchReportTest {
    @Test fun defaultSweepWhenNoneRequested() {
        assertEquals(listOf(4, 5, 6), BenchReport.threadCounts(0))
        assertEquals(listOf(4, 5, 6), BenchReport.threadCounts(-3))
        assertEquals(listOf(7), BenchReport.threadCounts(7))
    }

    @Test fun tokensDefault() {
        assertEquals(64, BenchReport.tokens(0))
        assertEquals(128, BenchReport.tokens(128))
    }

    @Test fun cpuDirNames() {
        assertEquals(0, BenchReport.cpuIdOf("cpu0"))
        assertEquals(7, BenchReport.cpuIdOf("cpu7"))
        assertNull(BenchReport.cpuIdOf("cpufreq"))
        assertNull(BenchReport.cpuIdOf("cpuidle"))
        assertNull(BenchReport.cpuIdOf("cpu"))
        assertNull(BenchReport.cpuIdOf("possible"))
    }

    private val status = "Name:\tcom.agent.ultra\nState:\tS (sleeping)\n" +
        "Cpus_allowed:\tff\nCpus_allowed_list:\t0-7\nMems_allowed_list:\t0\n"

    @Test fun cpusAllowedList() {
        assertEquals("0-7", BenchReport.statusField(status, "Cpus_allowed_list"))
        assertEquals("ff", BenchReport.statusField(status, "Cpus_allowed"))
        assertNull(BenchReport.statusField(status, "Missing"))
    }

    @Test fun tensorG5Clusters() {
        // 2x A520, 5x A725, 1x X4: the layout the bench is meant to confirm on the Pixel 10 Pro.
        val freqs = mapOf(
            0 to 2_000_000L, 1 to 2_000_000L,
            2 to 3_000_000L, 3 to 3_000_000L, 4 to 3_000_000L, 5 to 3_000_000L, 6 to 3_000_000L,
            7 to 4_000_000L,
        )
        assertEquals(
            listOf(4_000_000L to listOf(7), 3_000_000L to listOf(2, 3, 4, 5, 6), 2_000_000L to listOf(0, 1)),
            BenchReport.clusters(freqs),
        )
    }

    @Test fun decodeRateCountsGapsNotTokens() {
        // 11 callbacks over 1000 ms = 10 decodes in 1 s.
        assertEquals(10.0, BenchReport.decodeTokPerSec(11, 500, 1500), 1e-9)
        assertEquals(0.0, BenchReport.decodeTokPerSec(1, 500, 500), 1e-9)
        assertEquals(0.0, BenchReport.decodeTokPerSec(5, 500, 500), 1e-9)
    }

    @Test fun resultLineIsWhatBenchShParses() = assertEquals(
        "threads=5 promptMs=812 genTokens=64 tokPerSec=21.37 majfaults=0 thermal=1 draft=off accept=-",
        BenchReport.resultLine(5, 812, 64, 21.3712, 0, 1),
    )

    @Test fun resultLineWithDraft() = assertEquals(
        "threads=6 promptMs=900 genTokens=64 tokPerSec=9.50 majfaults=0 thermal=0 draft=6 accept=62%",
        BenchReport.resultLine(6, 900, 64, 9.5, 0, 0, draftLen = 6, drafted = 80, accepted = 50),
    )

    @Test fun acceptLabels() {
        assertEquals("-", BenchReport.acceptLabel(-1, 0))   // ran without a draft
        assertEquals("-", BenchReport.acceptLabel(0, 0))    // nothing confident enough to draft
        assertEquals("100%", BenchReport.acceptLabel(12, 12))
    }

    /** Each thread count without and with the draft, then lengths 4 and 8 at the top count. */
    @Test fun sweepWithDraft() {
        val c = BenchReport.configs(listOf(4, 5, 6), draftAvailable = true, defaultDraftLen = 6)
        assertEquals(listOf(4 to 0, 4 to 6, 5 to 0, 5 to 6, 6 to 0, 6 to 6, 6 to 4, 6 to 8), c.map { it.threads to it.draftLen })
    }

    @Test fun sweepWithoutDraftIsTheOldSweep() =
        assertEquals(listOf(4 to 0, 5 to 0, 6 to 0),
            BenchReport.configs(listOf(4, 5, 6), draftAvailable = false, defaultDraftLen = 6).map { it.threads to it.draftLen })

    @Test fun defaultLengthNotRepeated() =
        assertEquals(listOf(5 to 0, 5 to 4, 5 to 8),
            BenchReport.configs(listOf(5), draftAvailable = true, defaultDraftLen = 4).map { it.threads to it.draftLen })

    @Test fun summaryNamesBestAndSpeedUp() {
        val r = listOf(
            BenchReport.Config(4, 0) to 5.0, BenchReport.Config(5, 0) to 6.0, BenchReport.Config(6, 0) to 0.0,
            BenchReport.Config(5, 6) to 9.0, BenchReport.Config(6, 8) to 8.0,
        )
        assertEquals("summary best without draft: threads=5 6.00 tok/s; best with draft: threads=5 draft=6 9.00 tok/s (x1.50)",
            BenchReport.summary(r))
        assertEquals("summary best without draft: threads=4 5.00 tok/s; no run with the draft",
            BenchReport.summary(r.take(1)))
    }
}
