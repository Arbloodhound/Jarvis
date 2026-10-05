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
}
