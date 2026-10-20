package com.agent.ultra.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure, JVM-tested: the speed boost's draft model sizing, room rule and labels. */
class DraftModelTest {
    private val mib = 1_048_576L

    @Test fun kvPerTokenIsQwen3_06bAtQ8() = assertEquals(28L * 8 * 128 * 2 * 34 / 32, DraftModel.KV_BYTES_PER_TOKEN)

    @Test fun needBytesAsDocumented() {
        assertEquals(1_481_671_872L, DraftModel.needBytes(16384))
        assertEquals(965_772_480L, DraftModel.needBytes(8192))
        assertEquals(707_822_784L, DraftModel.needBytes(4096))
    }

    /** Pixel 10 Pro XL brain test: ~5.2 GB free with the 4B loaded at 16384. */
    @Test fun pixelRoomTakesTheFullContext() = assertEquals(16384, DraftModel.contextFor(5_200_000_000L, 16384))

    @Test fun shorterRoomShrinksTheDraftContext() = assertEquals(8192, DraftModel.contextFor(3_200_000_000L, 16384))

    @Test fun neverAboveTheThinkersContext() = assertEquals(4096, DraftModel.contextFor(9_000_000_000L, 4096))

    @Test fun skippedWhenRoomIsShortOrUnknown() {
        assertEquals(0, DraftModel.contextFor(2_500_000_000L, 16384))
        // The Pixel 2026-10-18: 3659 MB free with the 4B loaded now fits a draft.
        assertTrue(DraftModel.contextFor(3659L * 1_048_576, 16384) > 0)
        assertEquals(0, DraftModel.contextFor(0L, 16384))
        assertEquals(0, DraftModel.contextFor(-1L, 16384))
        assertEquals(0, DraftModel.contextFor(9_000_000_000L, 0))
    }

    /** Whatever context it picks, the free memory the thinker's policy keeps is still there. */
    @Test fun alwaysLeavesTheThinkersFreeMemory() {
        for (room in 0L..12_000_000_000L step 250_000_000L) for (t in LocalModelEngine.CTX_STEPS) {
            val c = DraftModel.contextFor(room, t)
            if (c > 0) assertTrue("room=$room ctx=$c", room - DraftModel.needBytes(c) >= DraftModel.FREE_AFTER_BYTES)
        }
    }

    @Test fun roomExcludesMappedWeights() {
        assertEquals(5_000_000_000L - 1_000L * mib - 2_266L * mib,
            DraftModel.roomAfterThinker(5_000_000_000L, 1_000L * mib, 2_266L * mib))
        assertEquals(5_000_000_000L, DraftModel.roomAfterThinker(5_000_000_000L, 0, 0))   // resident thinker, no librarian
        assertEquals(-1L, DraftModel.roomAfterThinker(-1L, 0, 0))                         // unknown stays unknown
    }

    @Test fun onlyBesideQwen3Thinkers() {
        assertTrue(DraftModel.eligible("qwen3-4b-thinking-2507-q4_0.gguf"))
        assertTrue(DraftModel.eligible("qwen3-8b-q4ks.gguf"))
        assertFalse(DraftModel.eligible("gemma3-1b-q4km.gguf"))
        assertFalse(DraftModel.eligible("qwen25-7b-q4km.gguf"))
        assertFalse(DraftModel.eligible(DraftModel.FILE))
    }

    @Test fun draftFileIsNoPreset() = assertTrue(LocalModelEngine.PRESETS.none { it.fileName == DraftModel.FILE })

    @Test fun draftLengthInTheTunedRange() = assertTrue(DraftModel.DRAFT_MAX in 4..8)

    @Test fun specLabels() {
        assertEquals("off", LocalModelEngine.specLabel(intArrayOf(-1, 0)))
        assertEquals("0/0(0%)", LocalModelEngine.specLabel(intArrayOf(0, 0)))
        assertEquals("30/40(75%)", LocalModelEngine.specLabel(intArrayOf(40, 30)))
    }

    @Test fun reasoningDefaultsArePresets() {
        assertTrue(LocalModelEngine.FAST_DEFAULT.fileName in LocalModelEngine.REASONING_DEFAULTS)
        for (f in LocalModelEngine.REASONING_DEFAULTS) assertTrue(f, LocalModelEngine.PRESETS.any { it.fileName == f })
    }

    @Test fun noPresetLabelCallsItselfAnMoeStandIn() =
        assertTrue(LocalModelEngine.PRESETS.none { "stand-in" in it.label })
}
