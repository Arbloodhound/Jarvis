package com.agent.jarvis.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSizeTest {
    private val mib = 1_048_576L
    private val moe = LocalModelEngine.MOE_DEFAULT                 // abliterated IQ2_M, 10240 MiB
    private val moeIq2s = LocalModelEngine.PRESETS.first { it.fileName == "qwen3moe-abliterated-iq2s.gguf" }
    private val gemma = LocalModelEngine.DEFAULT
    private val sixteenGiB = 17_179_869_184L

    private fun ctx(ram: Long, m: ModelChoice) = LocalModelEngine.contextFor(ram, m)

    @Test fun moeKvBytesMatchTheShape() {
        // 48 layers x 4 KV heads x 128 dim x (K,V) at q8_0's 34 bytes per 32 elements.
        assertEquals(52_224L, LocalModelEngine.kvProfile(moe.fileName).kvBytesPerToken)
        assertEquals(408L * mib, 52_224L * 8192)
        assertEquals(816L * mib, 52_224L * 16384)
    }

    @Test fun moeNeedMatchesTheKdocArithmetic() {
        assertEquals(16_996_106_240L, LocalModelEngine.memoryNeedBytes(moe.fileName, 10_240 * mib, 8192))
        assertEquals(17_440_702_464L, LocalModelEngine.memoryNeedBytes(moe.fileName, 10_240 * mib, 16384))
    }

    // Range of totalMem a "16 GB" phone reports (same as ModelFitTest): never 16384 with the IQ2_M.
    @Test fun sixteenGbMoeIq2mIsAtMost8192() {
        assertEquals(8192, ctx(sixteenGiB, moe))
        assertEquals(4096, ctx(16_000_000_000L, moe))
        assertEquals(4096, ctx(15_000_000_000L, moe))
    }

    @Test fun moeIq2mThresholdIsExact() {
        assertEquals(8192, ctx(16_996_106_240L, moe))
        assertEquals(4096, ctx(16_996_106_239L, moe))
    }

    @Test fun lighterMoeGetsMoreRoom() {
        assertEquals(16384, ctx(sixteenGiB, moeIq2s))
        assertEquals(4096, ctx(15_000_000_000L, moeIq2s))
    }

    @Test fun realFileSizeDecides() {
        // A 10.2e9-byte file (537 MB under the preset's 10240 MiB) reaches 8192 on 16.5e9.
        assertEquals(8192, LocalModelEngine.contextFor(16_500_000_000L, moe.fileName, 10_200_000_000L))
        assertEquals(4096, LocalModelEngine.contextFor(16_500_000_000L, moe.fileName, 10_737_418_240L))
    }

    @Test fun smallModelsByRamClass() {
        assertEquals(4096, ctx(4_000_000_000L, gemma))
        assertEquals(4096, ctx(6_000_000_000L, gemma))
        assertEquals(16384, ctx(8_000_000_000L, gemma))
        assertEquals(16384, ctx(12_000_000_000L, gemma))
    }

    @Test fun gemma2StaysWithinItsTrainingContext() {
        val g2 = LocalModelEngine.PRESETS.first { it.fileName == "gemma2-9b-q4km.gguf" }
        assertEquals(8192, ctx(64_000_000_000L, g2))
    }

    @Test fun unknownInputsKeepTheOldContext() {
        assertEquals(4096, ctx(0L, gemma))
        assertEquals(4096, ctx(-1L, moe))
        assertEquals(4096, LocalModelEngine.contextFor(sixteenGiB, moe.fileName, 0L))
    }

    // Resident-load check: unchanged at 4096, plus the KV and mask growth above it.
    @Test fun growthOverTheBaseContext() {
        assertEquals(0L, LocalModelEngine.ctxGrowthBytes(moe.fileName, 4096))
        assertEquals(204L * mib + 8 * mib, LocalModelEngine.ctxGrowthBytes(moe.fileName, 8192))
    }

    @Test fun unknownFileIsCappedAt8192() =
        assertEquals(8192, LocalModelEngine.contextFor(64_000_000_000L, "custom.gguf", 2_000L * mib))

    @Test fun neverBelow4096EvenWhenNothingFits() =
        assertEquals(4096, ctx(2_000_000_000L, moe))

    @Test fun unknownFileIsOverCounted() {
        val p = LocalModelEngine.kvProfile("something-custom.gguf")
        LocalModelEngine.PRESETS.filter { !it.desktopOnly && it.approxMb < 4_000 }.forEach {
            assertTrue(it.fileName, LocalModelEngine.kvProfile(it.fileName).kvBytesPerToken <= p.kvBytesPerToken)
        }
    }

    @Test fun everyStepIsAPresetChoiceAndMonotoneInRam() {
        LocalModelEngine.PRESETS.forEach { m ->
            var last = 0
            for (gb in 2..64) {
                val c = ctx(gb * 1_000_000_000L, m)
                assertTrue(c in LocalModelEngine.CTX_STEPS.toList())
                assertTrue("${m.fileName} at $gb GB", c >= last)
                last = c
            }
        }
    }
}
