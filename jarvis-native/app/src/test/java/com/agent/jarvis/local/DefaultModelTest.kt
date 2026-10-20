package com.agent.jarvis.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultModelTest {
    private val big = "qwen3-8b-q4ks.gguf"
    private val fast = "qwen3-4b-thinking-2507-abliterated-q4km.gguf"
    private val stock = "qwen3-4b-thinking-2507-q4_0.gguf"
    private val gemma = "gemma3-1b-q4km.gguf"

    // Range of totalMem a "16 GB" phone actually reports (same as ModelFitTest).
    @Test fun sixteenGbGetsFast4bThinking() =
        listOf(15_000_000_000L, 16_000_000_000L, 17_179_869_184L).forEach {
            assertEquals(fast, LocalModelEngine.defaultFor(it).fileName)
        }

    /** The owner's Pixel 10 Pro XL: 15,081 MiB total. */
    @Test fun pixel10ProXlGetsFast4bThinking() =
        assertEquals(fast, LocalModelEngine.defaultFor(15_081L * 1_048_576).fileName)

    @Test fun twelveGbGetsFast4b() = assertEquals(fast, LocalModelEngine.defaultFor(12_000_000_000L).fileName)
    @Test fun eightGbGetsFast4b() = assertEquals(fast, LocalModelEngine.defaultFor(8_000_000_000L).fileName)
    @Test fun fourGbGetsGemma() = assertEquals(gemma, LocalModelEngine.defaultFor(4_000_000_000L).fileName)

    @Test fun unknownRamGetsGemma() {
        assertEquals(gemma, LocalModelEngine.defaultFor(0L).fileName)
        assertEquals(gemma, LocalModelEngine.defaultFor(-1L).fileName)
    }

    /** The 4B preset is the file checked on the Hub (unsloth, 2,375,773,312 B = 2266 MiB). */
    @Test fun fastPresetIsTheCheckedFile() {
        val m = LocalModelEngine.STOCK_4B
        assertEquals(
            "https://huggingface.co/unsloth/Qwen3-4B-Thinking-2507-GGUF/resolve/main/Qwen3-4B-Thinking-2507-Q4_0.gguf",
            m.url,
        )
        assertEquals(Math.round(2_375_773_312.0 / 1_048_576).toInt(), m.approxMb)
    }

    // Upgrade from a build that downloaded an earlier default without saving prefs: keep the working model.
    @Test fun sixteenGbWithOnlyGemmaOnDiskStaysOnGemma() =
        assertEquals(gemma, LocalModelEngine.defaultFor(16_000_000_000L, setOf(gemma)).fileName)

    @Test fun sixteenGbWithFastOnDiskGetsFast() =
        assertEquals(fast, LocalModelEngine.defaultFor(16_000_000_000L, setOf(gemma, fast)).fileName)

    @Test fun sixteenGbWithFastAndBigOnDiskGetsFast() =
        assertEquals(fast, LocalModelEngine.defaultFor(16_000_000_000L, setOf(gemma, fast, big)).fileName)

    /** The 8B was the 14 GB+ default: an install that downloaded it without prefs keeps it. */
    @Test fun sixteenGbWithOnlyBigOnDiskKeepsBig() {
        assertEquals(big, LocalModelEngine.defaultFor(15_081L * 1_048_576, setOf(big)).fileName)
        assertEquals(big, LocalModelEngine.defaultFor(16_000_000_000L, setOf(gemma, big)).fileName)
    }

    /** The 8B is TOO_BIG on 8 GB: a stray file there doesn't become the default. */
    @Test fun eightGbIgnoresBigOnDisk() =
        assertEquals(fast, LocalModelEngine.defaultFor(8_000_000_000L, setOf(big)).fileName)

    @Test fun sixteenGbWithOtherFilesOnDiskGetsFast() =
        assertEquals(fast, LocalModelEngine.defaultFor(16_000_000_000L, setOf("qwen25-7b-q4km.gguf")).fileName)

    @Test fun twelveGbWithOnlyGemmaOnDiskStaysOnGemma() =
        assertEquals(gemma, LocalModelEngine.defaultFor(12_000_000_000L, setOf(gemma)).fileName)

    @Test fun twelveGbWithFastOnDiskGetsFast() =
        assertEquals(fast, LocalModelEngine.defaultFor(12_000_000_000L, setOf(gemma, fast)).fileName)

    @Test fun defaultIsNeverDesktopOnly() =
        listOf(0L, 4_000_000_000L, 16_000_000_000L, 64_000_000_000L).forEach {
            assertFalse(LocalModelEngine.defaultFor(it).desktopOnly)
        }

    @Test fun hintOnlyForThe8b() {
        assertNotNull(LocalModelEngine.fasterThinkerHint(big))
        assertNull(LocalModelEngine.fasterThinkerHint(fast))
        assertNull(LocalModelEngine.fasterThinkerHint(gemma))
        assertNull(LocalModelEngine.fasterThinkerHint("custom.gguf"))
    }

    @Test fun eightBStaysSelectable() =
        assertFalse(LocalModelEngine.PRESETS.first { it.fileName == big }.desktopOnly)

    @Test fun aDownloadedStock4bIsKeptUntilTheAbliteratedOneIsThere() {
        assertEquals(stock, LocalModelEngine.defaultFor(16_000_000_000L, setOf(stock)).fileName)
        assertEquals(fast, LocalModelEngine.defaultFor(16_000_000_000L, setOf(stock, fast)).fileName)
        assertEquals("The default is now the abliterated 4B (same model, refusals removed): pick it below to switch.",
            LocalModelEngine.fasterThinkerHint(stock))
    }
}
