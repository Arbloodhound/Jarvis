package com.agent.jarvis.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KitReportTest {
    private val line = "I/JarvisLlm( 123): gen tokens=180 ms=9000 tokPerSec=20.0 majfaults=3 availMB=900 model=x.gguf cpu=i8mm threads=6"

    @Test fun parsesGenLine() = assertEquals(KitReport.Gen(180, 9000, 20.0, 3, 900), KitReport.parseGen(line))
    @Test fun parsesCommaDecimal() = assertEquals(5.3, KitReport.parseGen("gen tokens=10 ms=1886 tokPerSec=5,3 majfaults=0 availMB=1")!!.tokPerSec, 1e-9)
    @Test fun missingFieldsAreMinusOne() = assertEquals(-1L, KitReport.parseGen("gen tokens=1 ms=2 tokPerSec=0.5")!!.availMb)
    @Test fun otherLinesIgnored() = assertNull(KitReport.parseGen("I/JarvisLlm: cpu variant ultra_llm_i8mm"))
    @Test fun mainGenIsLongest() = assertEquals(180, KitReport.mainGen(listOf("gen tokens=12 ms=1 tokPerSec=1.0", line))!!.tokens)
    @Test fun mainGenNoneIsNull() = assertNull(KitReport.mainGen(listOf("nothing")))

    @Test fun keepWhenFastAndSteady() = assertEquals(KitReport.Model.KEEP, KitReport.modelVerdict(listOf(4.0, 7.0, 7.0, 6.5, 6.8), false).first)
    @Test fun smallerWhenSlow() = assertEquals(KitReport.Model.TRY_SMALLER, KitReport.modelVerdict(listOf(3.0, 3.5, 3.2, 3.1), false).first)
    @Test fun smallerWhenSlowingDown() = assertEquals(KitReport.Model.TRY_SMALLER, KitReport.modelVerdict(listOf(8.0, 9.0, 8.0, 7.0, 5.5), false).first)
    @Test fun smallerWhenCrashed() = assertEquals(KitReport.Model.TRY_SMALLER, KitReport.modelVerdict(listOf(9.0, 9.0, 9.0), true).first)
    @Test fun tooFewAnswers() = assertEquals(KitReport.Model.NOT_ENOUGH_DATA, KitReport.modelVerdict(listOf(9.0, null, null), false).first)
    @Test fun unmeasuredQuestionsSkipped() = assertEquals(KitReport.Model.KEEP, KitReport.modelVerdict(listOf(null, 6.0, 6.0, 6.0), false).first)

    @Test fun logcatSinceFormat() = assertEquals("1700000000.005", KitReport.logcatSince(1_700_000_000_005))
    @Test fun crashDetected() = assertTrue(KitReport.hasCrash(listOf("E/AndroidRuntime: FATAL EXCEPTION: main")))
    @Test fun noCrash() = assertFalse(KitReport.hasCrash(listOf("I/JarvisLlm: ok")))

    // The real list's shape (LocalModelEngine.PRESETS, in order): file names and listed sizes.
    private val presets = listOf(
        KitReport.Preset("Qwen3-4B Thinking 2507 (Q4_0)", "qwen3-4b-thinking-2507-q4_0.gguf", 2266),
        KitReport.Preset("Qwen3-4B Thinking 2507 ABLITERATED (Q4_K_M)", "qwen3-4b-thinking-2507-abliterated-q4km.gguf", 2382),
        KitReport.Preset("Qwen3-8B (Q4_K_S)", "qwen3-8b-q4ks.gguf", 4580),
        KitReport.Preset("Jarvis fine-tune (IQ2_S)", "qwen3moe-jarvis-iq2s.gguf", 9300),
        KitReport.Preset("Qwen3-30B-A3B Thinking ABLITERATED (IQ2_M)", "qwen3moe-abliterated-iq2m.gguf", 10240),
        KitReport.Preset("Qwen3-30B-A3B Thinking ABLITERATED (IQ2_S) — roomier fallback", "qwen3moe-abliterated-iq2s.gguf", 9300),
        KitReport.Preset("Qwen3-30B-A3B Thinking (IQ3) — DESKTOP ONLY", "qwen3moe-thinking-iq3xxs.gguf", 12900, desktopOnly = true),
        KitReport.Preset("Qwen3 1.7B (Q4_K_M)", "qwen3-1.7b-q4km.gguf", 1110),
        KitReport.Preset("Qwen3 1.7B (Q4_0) — memory model", "qwen3-1.7b-q4_0.gguf", 1008),
        KitReport.Preset("Gemma 3 1B (Q4_K_M)", "gemma3-1b-q4km.gguf", 768),
    )
    private val lib = setOf("qwen3-1.7b-q4_0.gguf")

    /** Pixel 10 Pro XL: the 8B at 2–4 tok/s. The advice is the 4B, never the MoE's IQ2_S. */
    @Test fun slow8bSuggestsThe4b() {
        val s = KitReport.smallerModel("qwen3-8b-q4ks.gguf", 4580, 3.0, presets, lib)!!
        assertEquals("qwen3-4b-thinking-2507-q4_0.gguf", s.preset.fileName)
        assertEquals(3.0 * 4580 / 2266, s.expectedTokPerSec!!, 1e-9)
        val text = KitReport.advice(KitReport.Model.TRY_SMALLER, "Qwen3-8B (Q4_K_S)", "qwen3-8b-q4ks.gguf", 4580, 3.0, presets, lib)
        assertTrue(text, "Qwen3-4B Thinking 2507 (Q4_0)" in text)
        assertFalse(text, "IQ2" in text)
    }

    @Test fun neverTheMoeOrDesktopOnly() {
        // Even from the MoE itself (10 GB), the suggestion is a fast-path model, not another MoE.
        val s = KitReport.smallerModel("qwen3moe-abliterated-iq2m.gguf", 10240, 1.0, presets, lib)!!
        assertFalse(s.preset.fileName.startsWith("qwen3moe"))
        for (mb in listOf(20_000, 4580, 2266)) for (tps in listOf(null, 0.5, 4.0)) {
            val p = KitReport.smallerModel("x.gguf", mb, tps, presets, lib)?.preset ?: continue
            assertFalse(p.fileName.startsWith("qwen3moe")); assertFalse(p.desktopOnly)
        }
    }

    @Test fun slow4bSuggestsTheNextSmallerNotTheLibrarian() {
        val s = KitReport.smallerModel("qwen3-4b-thinking-2507-q4_0.gguf", 2266, 3.0, presets, lib)!!
        assertEquals("qwen3-1.7b-q4km.gguf", s.preset.fileName)   // the abliterated 4B is not smaller
    }

    @Test fun veryslowTakesTheSmallest() =
        assertEquals("gemma3-1b-q4km.gguf", KitReport.smallerModel("qwen3-8b-q4ks.gguf", 4580, 0.5, presets, lib)!!.preset.fileName)

    @Test fun unmeasuredTakesTheFirstSmallerInListOrder() {
        val s = KitReport.smallerModel("qwen3-8b-q4ks.gguf", 4580, null, presets, lib)!!
        assertEquals("qwen3-4b-thinking-2507-q4_0.gguf", s.preset.fileName)
        assertNull(s.expectedTokPerSec)
    }

    @Test fun smallestModelHasNothingSmaller() {
        assertNull(KitReport.smallerModel("gemma3-1b-q4km.gguf", 768, 2.0, presets, lib))
        val text = KitReport.advice(KitReport.Model.TRY_SMALLER, "Gemma 3 1B", "gemma3-1b-q4km.gguf", 768, 2.0, presets, lib)
        assertTrue(text, "already the smallest" in text)
    }

    @Test fun keepAndNotEnoughDataNameNoModel() {
        assertTrue(KitReport.advice(KitReport.Model.KEEP, "Qwen3-8B", "qwen3-8b-q4ks.gguf", 4580, 9.0, presets).startsWith("KEEP"))
        assertTrue(KitReport.advice(KitReport.Model.NOT_ENOUGH_DATA, "Qwen3-8B", "qwen3-8b-q4ks.gguf", 4580, null, presets)
            .startsWith("Not enough"))
    }

    @Test fun medianSkipsUnmeasured() {
        assertEquals(6.0, KitReport.median(listOf(null, 5.0, 6.0, 7.0))!!, 1e-9)
        assertEquals(5.5, KitReport.median(listOf(5.0, 6.0))!!, 1e-9)
        assertNull(KitReport.median(listOf(null, null)))
    }

    private val reasoning = setOf("qwen3-4b-thinking-2507-q4_0.gguf", "qwen3-4b-thinking-2507-abliterated-q4km.gguf")

    /** Pixel 10 Pro XL, 4B Thinking at 3.4 tok/s: keep it and speed it up, never the weaker 1.7B. */
    @Test fun slowReasoningDefaultIsKeptNotSwapped() {
        val text = KitReport.advice(KitReport.Model.TRY_SMALLER, "Qwen3-4B Thinking 2507 (Q4_0)",
            "qwen3-4b-thinking-2507-q4_0.gguf", 2266, 3.4, presets, lib, reasoning)
        assertTrue(text, text.startsWith("KEEP this model"))
        assertFalse(text, "1.7B" in text)
        assertFalse(text, "TRY A SMALLER" in text)
        for (hint in listOf("close other heavy apps", "cool down", "Quick", "Speed boost")) assertTrue(text, hint in text)
    }

    @Test fun slowAbliteratedTwinIsKeptToo() = assertTrue(KitReport.advice(KitReport.Model.TRY_SMALLER, "4B abliterated",
        "qwen3-4b-thinking-2507-abliterated-q4km.gguf", 2382, 2.0, presets, lib, reasoning).startsWith("KEEP"))

    /** The rule is only about the reasoning default: a slow 8B is still pointed at the (stronger) 4B. */
    @Test fun slow8bStillSuggestsThe4b() = assertTrue(KitReport.advice(KitReport.Model.TRY_SMALLER, "Qwen3-8B (Q4_K_S)",
        "qwen3-8b-q4ks.gguf", 4580, 3.0, presets, lib, reasoning).contains("Qwen3-4B Thinking 2507 (Q4_0)"))

    /** A 4B a few percent smaller than another 4B is no cure: the next real step down is suggested. */
    @Test fun aBarelySmallerModelIsNotSuggested() {
        val s = KitReport.smallerModel("qwen3-4b-thinking-2507-abliterated-q4km.gguf", 2382, 4.9, presets, lib)!!
        assertEquals("qwen3-1.7b-q4km.gguf", s.preset.fileName)
    }
}
