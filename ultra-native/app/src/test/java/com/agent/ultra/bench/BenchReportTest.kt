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
        "threads=5 promptMs=812 genTokens=64 tokPerSec=21.37 majfaults=0 thermal=1",
        BenchReport.resultLine(5, 812, 64, 21.3712, 0, 1),
    )
}
