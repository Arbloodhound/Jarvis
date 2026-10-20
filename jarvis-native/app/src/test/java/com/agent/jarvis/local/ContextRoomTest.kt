package com.agent.jarvis.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure, JVM-tested: [LocalModelEngine.contextForRoom] sizes the thinker's ctx from free memory. */
class ContextRoomTest {
    private val mib = 1_048_576L
    /** The owner's Pixel 10 Pro XL. */
    private val pixelTotal = 15_081L * mib
    private val pixelAvail = 8_600_000_000L

    private val q8b = "qwen3-8b-q4ks.gguf"
    private val q8bBytes = 4_802_012_992L            // Hub size, unsloth Qwen3-8B-Q4_K_S
    private val q4b = "qwen3-4b-thinking-2507-q4_0.gguf"
    private val q4bBytes = 2_375_773_312L            // Hub size, unsloth Qwen3-4B-Thinking-2507-Q4_0
    private val libBytes = 1_056_782_912L            // Hub size, unsloth Qwen3-1.7B-Q4_0

    private fun ctx(room: Long, file: String, bytes: Long, total: Long = pixelTotal) =
        LocalModelEngine.contextForRoom(room, total, file, bytes)

    @Test fun totalRamAloneGave8b16384() =
        // The old sizing: what made the 8B load resident at 16384 and page on the Pixel.
        assertEquals(16384, LocalModelEngine.contextFor(pixelTotal, q8b, q8bBytes))

    @Test fun pixel8bGetsTheSmallestCtx() = assertEquals(4096, ctx(pixelAvail, q8b, q8bBytes))

    @Test fun pixel8bAt16384WouldLeaveLessThanThePagingCase() {
        val left = pixelAvail - LocalModelEngine.residentNeedBytes(q8b, q8bBytes, 16384)
        assertTrue(left < 2_400_000_000L)
        assertTrue(left < LocalModelEngine.THINKER_FREE_AFTER_LOAD_BYTES)
    }

    @Test fun pixel4bKeeps16384() = assertEquals(16384, ctx(pixelAvail, q4b, q4bBytes))

    @Test fun pixel4bBesideALoadedLibrarianKeeps16384() =
        assertEquals(16384, ctx(LocalModelEngine.roomBesideOther(pixelAvail, libBytes), q4b, q4bBytes))

    @Test fun lessRoomSteps4bDown() {
        assertEquals(8192, ctx(7_000_000_000L, q4b, q4bBytes))
        assertEquals(4096, ctx(5_000_000_000L, q4b, q4bBytes))
    }

    @Test fun neverBelowCtxSize() = assertEquals(LocalModelEngine.CTX_SIZE, ctx(1_000_000_000L, q8b, q8bBytes))

    @Test fun neverAboveTheTotalRamBound() {
        // A huge reading can't lift the ctx past what total RAM (and the training ctx) allows.
        val gemma2 = "gemma2-9b-q4km.gguf"
        assertEquals(8192, ctx(64_000_000_000L, gemma2, 5_494 * mib, total = 64_000_000_000L))
        assertEquals(
            LocalModelEngine.contextFor(6_000_000_000L, q4b, q4bBytes),
            ctx(64_000_000_000L, q4b, q4bBytes, total = 6_000_000_000L),
        )
    }

    @Test fun unknownRoomFallsBackToTotalRam() {
        assertEquals(LocalModelEngine.contextFor(pixelTotal, q8b, q8bBytes), ctx(0L, q8b, q8bBytes))
        assertEquals(LocalModelEngine.contextFor(pixelTotal, q8b, q8bBytes), ctx(-1L, q8b, q8bBytes))
    }

    @Test fun unknownSizeIsCtxSize() = assertEquals(LocalModelEngine.CTX_SIZE, ctx(pixelAvail, q8b, 0L))

    @Test fun monotonicInRoom() {
        var prev = 0
        for (gb in 1..40) {
            val c = ctx(gb * 500_000_000L, q4b, q4bBytes)
            assertTrue("$gb", c >= prev); prev = c
        }
    }
}
