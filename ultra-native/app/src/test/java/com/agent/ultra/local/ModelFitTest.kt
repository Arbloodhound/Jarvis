package com.agent.ultra.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelFitTest {
    private fun preset(file: String) = LocalModelEngine.PRESETS.first { it.fileName == file }

    // Range of totalMem a "16 GB" phone actually reports.
    private val sixteenGbPhones = listOf(15_000_000_000L, 16_000_000_000L, 17_179_869_184L)

    @Test fun desktopOnlyIq3IsTooBigOnSixteenGb() = sixteenGbPhones.forEach {
        assertEquals(Fit.TOO_BIG, LocalModelEngine.fitFor(preset("qwen3moe-thinking-iq3xxs.gguf"), it))
    }

    @Test fun phoneMoeStillTightOnSixteenGb() = sixteenGbPhones.forEach {
        assertEquals(Fit.TIGHT, LocalModelEngine.fitFor(preset("qwen3moe-abliterated-iq2m.gguf"), it))
    }

    @Test fun defaultComfortable() =
        assertEquals(Fit.COMFORTABLE, LocalModelEngine.fitFor(LocalModelEngine.DEFAULT, 8_000_000_000L))

    // KV cache + compute buffer at CTX_SIZE is counted on top of the weights.

    @Test fun overheadIsLargeForMoeAndBigModels() {
        assertEquals(LocalModelEngine.LARGE_MODEL_OVERHEAD_MB,
            LocalModelEngine.runtimeOverheadMb(preset("qwen3moe-abliterated-iq2m.gguf")))
        assertEquals(LocalModelEngine.LARGE_MODEL_OVERHEAD_MB,
            LocalModelEngine.runtimeOverheadMb(preset("qwen25-7b-q4km.gguf")))
    }

    @Test fun overheadIsSmallForSmallModels() =
        assertEquals(LocalModelEngine.SMALL_MODEL_OVERHEAD_MB,
            LocalModelEngine.runtimeOverheadMb(LocalModelEngine.DEFAULT))

    @Test fun moeFlagWinsOverSize() =
        assertEquals(LocalModelEngine.LARGE_MODEL_OVERHEAD_MB,
            LocalModelEngine.runtimeOverheadMb(ModelChoice("t", "u", "tiny-moe.gguf", 500, "")))

    @Test fun overheadCanTipComfortableToTight() {
        // 1800 MiB of weights is 0.236 of 8 GB on its own (COMFORTABLE); with the
        // 256 MiB overhead it is 0.27 (TIGHT).
        val m = ModelChoice("t", "u", "small.gguf", 1800, "")
        assertEquals(Fit.TIGHT, LocalModelEngine.fitFor(m, 8_000_000_000L))
    }

    @Test fun overheadCanTipMoeTightToTooBig() {
        // IQ2_S MoE on a 12 GB-class phone: 0.848 of RAM by weights alone (TIGHT, knife-edge),
        // 0.894 once its KV and compute buffer are counted.
        assertEquals(Fit.TOO_BIG, LocalModelEngine.fitFor(preset("qwen3moe-abliterated-iq2s.gguf"), 11_500_000_000L))
    }
}
