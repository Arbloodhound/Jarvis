package com.agent.ultra.local

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuTopologyTest {
    // Tensor G5 as the kernel reports it, numbered little-first like most SoCs.
    private val g5 = mapOf(
        0 to 2_253_000L, 1 to 2_253_000L,
        2 to 3_052_000L, 3 to 3_052_000L, 4 to 3_052_000L, 5 to 3_052_000L, 6 to 3_052_000L,
        7 to 3_782_000L,
    )

    @Test fun tensorG5PutsTheLlmOnX4AndA725() {
        val t = CpuTopology.fromMaxFreqs(g5)
        assertEquals(listOf(7, 2, 3, 4, 5, 6, 0, 1), t.fastestFirst)
        assertEquals(listOf(7, 2, 3, 4, 5, 6), t.big)
        assertEquals(4, t.llmThreads)   // fastest in the Pixel speed test; they float over all 6 big cores
        assertEquals(listOf(2, 3, 4, 5, 6, 7), t.llmCpus)
        assertEquals(listOf(0, 1), t.whisperCpus)
    }

    @Test fun numberingIsReadNotAssumed() {
        // same cores, prime core numbered first and the little ones last
        val f = g5.mapKeys { 7 - it.key }
        val t = CpuTopology.fromMaxFreqs(f)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), t.llmCpus)
        assertEquals(listOf(6, 7), t.whisperCpus)
        assertEquals(4, t.llmThreads)
    }

    @Test fun twoBigCoresStillGetFourThreads() {
        // Galaxy A15 5G (Dimensity 6100+): 6x A55 @2.0 + 2x A76 @2.2, where 4 threads was measured.
        val f = (0..5).associateWith { 2_000_000L } + mapOf(6 to 2_200_000L, 7 to 2_200_000L)
        val t = CpuTopology.fromMaxFreqs(f)
        assertEquals(listOf(6, 7), t.big)
        assertEquals(4, t.llmThreads)
        assertEquals(listOf(0, 1, 6, 7), t.llmCpus)   // both A76 + the next two (ties: lowest id)
        assertEquals(listOf(2, 3, 4, 5), t.whisperCpus)
    }

    @Test fun moreThanSixBigCoresCapsAtSixFastest() {
        // 1 prime + 6 mid + 1 little: the slowest mid core goes to whisper with the little one
        val f = mapOf(0 to 1_800_000L, 1 to 2_800_000L, 2 to 2_800_000L, 3 to 2_800_000L,
            4 to 2_800_000L, 5 to 2_800_000L, 6 to 2_700_000L, 7 to 3_300_000L)
        val t = CpuTopology.fromMaxFreqs(f)
        assertEquals(7, t.big.size)
        assertEquals(4, t.llmThreads)
        assertEquals(listOf(1, 2, 3, 4, 5, 7), t.llmCpus)
        assertEquals(listOf(0, 6), t.whisperCpus)
    }

    @Test fun oneClusterUsesAllCoresAndWhisperSharesThem() {
        val t = CpuTopology.fromMaxFreqs(mapOf(0 to 2_000_000L, 1 to 2_000_000L, 2 to 2_000_000L, 3 to 2_000_000L))
        assertEquals(4, t.llmThreads)
        assertEquals(listOf(0, 1, 2, 3), t.llmCpus)
        assertEquals(listOf(0, 1, 2, 3), t.whisperCpus)   // nothing else left: the slowest cores
    }

    @Test fun oneClusterOfEightLeavesTwoForWhisper() {
        val t = CpuTopology.fromMaxFreqs((0..7).associateWith { 2_000_000L })
        assertEquals(4, t.llmThreads)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), t.llmCpus)
        assertEquals(listOf(6, 7), t.whisperCpus)
    }

    @Test fun fewerThanFourCpusUsesThemAll() {
        val t = CpuTopology.fromMaxFreqs(mapOf(0 to 1_000_000L, 1 to 1_000_000L, 2 to 2_000_000L))
        assertEquals(3, t.llmThreads)
        assertEquals(listOf(0, 1, 2), t.llmCpus)
        assertEquals(listOf(0, 1), t.whisperCpus)     // nothing else left: the slowest cores
    }

    @Test fun nothingReadableFallsBackToFourThreadsUnpinned() {
        val t = CpuTopology.fromMaxFreqs(emptyMap())
        assertEquals(CpuTopology.FALLBACK, t)
        assertEquals(4, t.llmThreads)
        assertEquals(emptyList<Int>(), t.llmCpus)
        assertEquals(emptyList<Int>(), t.whisperCpus)
    }

    @Test fun badFrequencyFallsBack() =
        assertEquals(CpuTopology.FALLBACK, CpuTopology.fromMaxFreqs(mapOf(0 to 2_000_000L, 1 to 0L)))

    @Test fun cpuIdsSkipsNonCpuEntries() = assertEquals(
        listOf(0, 1, 7, 10),
        CpuTopology.cpuIds(listOf("cpu10", "cpufreq", "cpuidle", "cpu0", "online", "cpu7", "possible", "cpu1", "cpu")),
    )
}
