package com.agent.ultra.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DefaultModelTest {
    private val big = "qwen3-8b-q4ks.gguf"
    private val fast = "qwen3-4b-thinking-2507-q4_0.gguf"
    private val gemma = "gemma3-1b-q4km.gguf"

    // Range of totalMem a "16 GB" phone actually reports (same as ModelFitTest).
    @Test fun sixteenGbGetsBig8b() =
        listOf(15_000_000_000L, 16_000_000_000L, 17_179_869_184L).forEach {
            assertEquals(big, LocalModelEngine.defaultFor(it).fileName)
        }

    /** The owner's Pixel 10 Pro XL: 15,081 MiB total. */
    @Test fun pixel10ProXlGetsBig8b() =
        assertEquals(big, LocalModelEngine.defaultFor(15_081L * 1_048_576).fileName)

    @Test fun fourteenGbIsTheLine() {
        assertEquals(big, LocalModelEngine.defaultFor(14_000_000_000L).fileName)
        assertEquals(fast, LocalModelEngine.defaultFor(13_999_999_999L).fileName)
    }

    @Test fun twelveGbGetsFast4b() = assertEquals(fast, LocalModelEngine.defaultFor(12_000_000_000L).fileName)
    @Test fun eightGbGetsFast4b() = assertEquals(fast, LocalModelEngine.defaultFor(8_000_000_000L).fileName)
    @Test fun fourGbGetsGemma() = assertEquals(gemma, LocalModelEngine.defaultFor(4_000_000_000L).fileName)

    @Test fun unknownRamGetsGemma() {
        assertEquals(gemma, LocalModelEngine.defaultFor(0L).fileName)
        assertEquals(gemma, LocalModelEngine.defaultFor(-1L).fileName)
    }

    // Upgrade from a build that downloaded an earlier default without saving prefs: keep the working model.
    @Test fun sixteenGbWithOnlyGemmaOnDiskStaysOnGemma() =
        assertEquals(gemma, LocalModelEngine.defaultFor(16_000_000_000L, setOf(gemma)).fileName)

    @Test fun sixteenGbWithFastOnDiskStaysOnFast() =
        assertEquals(fast, LocalModelEngine.defaultFor(16_000_000_000L, setOf(gemma, fast)).fileName)

    @Test fun sixteenGbWithBigOnDiskGetsBig() =
        assertEquals(big, LocalModelEngine.defaultFor(16_000_000_000L, setOf(gemma, fast, big)).fileName)

    @Test fun sixteenGbWithOtherFilesOnDiskGetsBig() =
        assertEquals(big, LocalModelEngine.defaultFor(16_000_000_000L, setOf("qwen25-7b-q4km.gguf")).fileName)

    @Test fun twelveGbWithOnlyGemmaOnDiskStaysOnGemma() =
        assertEquals(gemma, LocalModelEngine.defaultFor(12_000_000_000L, setOf(gemma)).fileName)

    @Test fun twelveGbWithFastOnDiskGetsFast() =
        assertEquals(fast, LocalModelEngine.defaultFor(12_000_000_000L, setOf(gemma, fast)).fileName)

    @Test fun defaultIsNeverDesktopOnly() =
        listOf(0L, 4_000_000_000L, 16_000_000_000L, 64_000_000_000L).forEach {
            assertFalse(LocalModelEngine.defaultFor(it).desktopOnly)
        }
}
