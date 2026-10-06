package com.agent.ultra.local

import com.agent.ultra.local.LocalModelEngine.LoadDecision
import com.agent.ultra.local.LocalModelEngine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The thinker + librarian pair: presets, memory need, and who gives way. */
class TwoModelsTest {
    private val mib = 1_048_576L
    private val headroom = LocalModelEngine.MIN_AVAIL_BYTES

    // Real file sizes (Hub listing): Qwen3-1.7B-Q4_0 and Qwen3-8B-Q4_K_S.
    private val librarianBytes = 1_056_782_912L
    private val thinkerBytes = 4_802_012_992L
    private val librarianNeed = LocalModelEngine.residentNeedBytes("qwen3-1.7b-q4_0.gguf", librarianBytes, 4096)
    private val thinkerNeed = LocalModelEngine.residentNeedBytes("qwen3-8b-q4ks.gguf", thinkerBytes, 16384)

    private fun decide(role: Role, avail: Long, need: Long, otherLoaded: Boolean, otherMapped: Long) =
        LocalModelEngine.loadDecision(role, avail, need, otherLoaded, otherMapped)

    // ── presets ──

    @Test fun librarianDefaultIsQwen3SmallQ4_0() {
        val m = LocalModelEngine.LIBRARIAN_DEFAULT
        assertTrue(m.url.endsWith("/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_0.gguf"))
        assertEquals(Math.round(librarianBytes.toDouble() / mib).toInt(), m.approxMb)
        assertFalse(m.desktopOnly)
    }

    @Test fun librarianPresetsAreSmallAndIncludeTheDefault() {
        val list = LocalModelEngine.LIBRARIAN_PRESETS
        assertTrue(LocalModelEngine.LIBRARIAN_DEFAULT in list)
        list.forEach {
            assertTrue(it.fileName, it.approxMb in 1..LocalModelEngine.LIBRARIAN_MAX_MB)
            assertFalse(it.fileName, it.desktopOnly)
            assertTrue(it.fileName, it.fileName.startsWith("qwen3-"))   // the no-think prompt is Qwen3's
        }
        assertFalse(list.any { it.fileName == LocalModelEngine.BIG_DEFAULT.fileName })
    }

    @Test fun librarianFileHasQwen3SmallKvProfile() =
        assertEquals(LocalModelEngine.kvProfile("qwen3-1.7b-q4km.gguf"), LocalModelEngine.kvProfile("qwen3-1.7b-q4_0.gguf"))

    /** The thinker keeps the prefs file existing installs already have. */
    @Test fun thinkerKeepsItsPrefsFile() {
        assertEquals("ultra_local_model", Role.THINKER.prefsName)
        assertFalse(Role.THINKER.prefsName == Role.LIBRARIAN.prefsName)
    }

    // ── memory need ──

    /** Weights + q8_0 KV + compute buffer, without the Android/app shares of memoryNeedBytes. */
    @Test fun residentNeedIsWeightsKvAndCompute() {
        // 1.7B at 4096: KV 28 x 8 x 128 x 2 x 34/32 = 60,928 B/token -> 249,561,088;
        // compute 512 x 151,936 x 4 + 2 x 4096 x 512 x 2 + 128 MiB = 453,771,264.
        assertEquals(librarianBytes + 249_561_088L + 453_771_264L, librarianNeed)
        // 8B at 16384: KV 78,336 B/token -> 1,283,457,024; compute 311,164,928 + 33,554,432 + 134,217,728.
        assertEquals(thinkerBytes + 1_283_457_024L + 478_937_088L, thinkerNeed)
    }

    // ── who gives way ──

    @Test fun librarianAloneLoadsWhenItFits() =
        assertEquals(LoadDecision.LOAD, decide(Role.LIBRARIAN, 4_000_000_000L, librarianNeed, false, 0))

    @Test fun librarianIsRefusedWhenTheThinkersMappedWeightsLeaveNoRoom() =
        // 6.0e9 available, but 4.8e9 of it is the thinker's weights in page cache.
        assertEquals(LoadDecision.REFUSE, decide(Role.LIBRARIAN, 6_000_000_000L, librarianNeed, true, thinkerBytes))

    @Test fun librarianLoadsBesideAThinkerWhenBothFit() =
        assertEquals(LoadDecision.LOAD,
            decide(Role.LIBRARIAN, thinkerBytes + librarianNeed + headroom, librarianNeed, true, thinkerBytes))

    @Test fun librarianLineIsExact() {
        val avail = thinkerBytes + librarianNeed + headroom
        assertEquals(LoadDecision.REFUSE, decide(Role.LIBRARIAN, avail - 1, librarianNeed, true, thinkerBytes))
    }

    /** A resident thinker is already outside availMem: nothing is subtracted for it. */
    @Test fun librarianBesideResidentThinkerCountsOnlyAvail() =
        assertEquals(LoadDecision.LOAD, decide(Role.LIBRARIAN, 3_000_000_000L, librarianNeed, true, 0))

    @Test fun librarianIsRefusedAloneWhenMemoryIsShort() =
        assertEquals(LoadDecision.REFUSE, decide(Role.LIBRARIAN, 1_500_000_000L, librarianNeed, false, 0))

    @Test fun thinkerMakesTheLibrarianYieldWhenBothDontFit() =
        assertEquals(LoadDecision.LIBRARIAN_YIELDS,
            decide(Role.THINKER, 7_500_000_000L, thinkerNeed, true, librarianBytes))

    @Test fun thinkerLoadsBesideTheLibrarianWhenBothFit() =
        assertEquals(LoadDecision.LOAD, decide(Role.THINKER, 9_000_000_000L, thinkerNeed, true, librarianBytes))

    /** The thinker is never refused by the pair check; alone it loads as before. */
    @Test fun thinkerAloneIsNeverRefused() {
        assertEquals(LoadDecision.LOAD, decide(Role.THINKER, 2_000_000_000L, thinkerNeed, false, 0))
        assertEquals(LoadDecision.LOAD, decide(Role.THINKER, 2_000_000_000L, thinkerNeed, false, librarianBytes))
    }

    @Test fun neverRefusesTheThinkerWhateverTheNumbers() =
        listOf(1L, 1_000_000_000L, 5_000_000_000L, 20_000_000_000L).forEach { avail ->
            listOf(true, false).forEach { other ->
                assertTrue(decide(Role.THINKER, avail, thinkerNeed, other, librarianBytes) != LoadDecision.REFUSE)
            }
        }

    @Test fun librarianNeverAsksTheThinkerToYield() =
        listOf(1L, 1_000_000_000L, 5_000_000_000L).forEach { avail ->
            assertTrue(decide(Role.LIBRARIAN, avail, librarianNeed, true, thinkerBytes) != LoadDecision.LIBRARIAN_YIELDS)
        }

    @Test fun unknownMemoryLoadsAsBefore() {
        assertEquals(LoadDecision.LOAD, decide(Role.LIBRARIAN, 0, librarianNeed, true, thinkerBytes))
        assertEquals(LoadDecision.LOAD, decide(Role.THINKER, -1, thinkerNeed, true, librarianBytes))
    }

    @Test fun otherMappedBytesIgnoredWhenTheOtherIsNotLoaded() =
        assertEquals(LoadDecision.LOAD, decide(Role.LIBRARIAN, 4_000_000_000L, librarianNeed, false, thinkerBytes))
}
