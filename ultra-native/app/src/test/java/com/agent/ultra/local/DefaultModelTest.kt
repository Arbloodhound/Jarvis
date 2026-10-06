package com.agent.ultra.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DefaultModelTest {
    private val fast = "qwen3-4b-thinking-2507-q4_0.gguf"
    private val gemma = "gemma3-1b-q4km.gguf"

    // Range of totalMem a "16 GB" phone actually reports (same as ModelFitTest).
    @Test fun sixteenGbGetsFast4b() =
        listOf(15_000_000_000L, 16_000_000_000L, 17_179_869_184L).forEach {
            assertEquals(fast, LocalModelEngine.defaultFor(it).fileName)
        }

    @Test fun twelveGbGetsFast4b() = assertEquals(fast, LocalModelEngine.defaultFor(12_000_000_000L).fileName)
    @Test fun eightGbGetsFast4b() = assertEquals(fast, LocalModelEngine.defaultFor(8_000_000_000L).fileName)
    @Test fun fourGbGetsGemma() = assertEquals(gemma, LocalModelEngine.defaultFor(4_000_000_000L).fileName)

    @Test fun unknownRamGetsGemma() {
        assertEquals(gemma, LocalModelEngine.defaultFor(0L).fileName)
        assertEquals(gemma, LocalModelEngine.defaultFor(-1L).fileName)
    }

    // Upgrade from a build that downloaded Gemma without saving prefs: keep the working model.
    @Test fun sixteenGbWithOnlyGemmaOnDiskStaysOnGemma() =
        assertEquals(gemma, LocalModelEngine.defaultFor(16_000_000_000L, setOf(gemma)).fileName)

    @Test fun sixteenGbWithFastOnDiskGetsFast() =
        assertEquals(fast, LocalModelEngine.defaultFor(16_000_000_000L, setOf(gemma, fast)).fileName)

    @Test fun sixteenGbWithOtherFilesOnDiskGetsFast() =
        assertEquals(fast, LocalModelEngine.defaultFor(16_000_000_000L, setOf("qwen25-7b-q4km.gguf")).fileName)

    @Test fun defaultIsNeverDesktopOnly() =
        listOf(0L, 4_000_000_000L, 16_000_000_000L, 64_000_000_000L).forEach {
            assertFalse(LocalModelEngine.defaultFor(it).desktopOnly)
        }
}
