package com.agent.jarvis.local

import com.agent.jarvis.agent.LocalPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure, JVM-tested: the Qwen3.5-4B abliterated preset (hybrid model) and what follows from it. */
class Qwen35PresetTest {
    private val file = "qwen35-4b-abliterated-q4km.gguf"
    private val preset = LocalModelEngine.PRESETS.first { it.fileName == file }
    private val bytes = 2_707_514_688L   // Hub listing, 2026-10-07

    @Test fun listedWithTheHubFileAndSize() {
        assertTrue(preset.url.endsWith("/mradermacher/Huihui-Qwen3.5-4B-abliterated-GGUF/resolve/main/Huihui-Qwen3.5-4B-abliterated.Q4_K_M.gguf"))
        assertEquals(bytes / 1_048_576, preset.approxMb.toLong())
        assertFalse(preset.desktopOnly)
        assertTrue(preset.label.startsWith("Qwen3.5-4B abliterated (Q4_K_M)"))
    }

    @Test fun notTheDefault() {
        assertNotEquals(file, LocalModelEngine.FAST_DEFAULT.fileName)
        assertNotEquals(file, LocalModelEngine.defaultFor(16_000_000_000L).fileName)
        assertNotEquals(file, LocalModelEngine.defaultFor(16_000_000_000L, setOf(file)).fileName)
        // Listed after both Qwen3-4B Thinking presets, so a slow-model suggestion still names the 4B first.
        val i = LocalModelEngine.PRESETS.indexOf(preset)
        assertTrue(i > LocalModelEngine.PRESETS.indexOf(LocalModelEngine.FAST_DEFAULT))
        assertTrue(i > LocalModelEngine.PRESETS.indexOfFirst { it.fileName == "qwen3-4b-thinking-2507-abliterated-q4km.gguf" })
    }

    @Test fun aReasoningDefault() = assertTrue(file in LocalModelEngine.REASONING_DEFAULTS)

    /** 8 full-attention layers x 4 KV heads x 256 at q8_0; the 24 DeltaNet layers' f32 state on top. */
    @Test fun kvProfileIsTheHybridShape() {
        val p = LocalModelEngine.kvProfile(file)
        assertEquals(8L * 4 * 256 * 2 * 34 / 32, p.kvBytesPerToken)
        assertEquals(17_408L, p.kvBytesPerToken)
        assertEquals(52_690_944L, p.stateBytes)
        assertEquals(248_320, p.vocab)
        // The Qwen3 4B profile is untouched (no prefix collision).
        assertEquals(0L, LocalModelEngine.kvProfile("qwen3-4b-thinking-2507-q4_0.gguf").stateBytes)
    }

    /** weights + KV(16384) + state + logits (512 x 248,320 x 4) + mask + activations. */
    @Test fun residentNeedAt16384() =
        assertEquals(3_721_749_824L, LocalModelEngine.residentNeedBytes(file, bytes, 16384))

    /** Pixel 10 Pro XL (15,081 MiB total, ~8.6e9 free): the full 16384 context, as for the 4B. */
    @Test fun pixelGetsTheFullContext() =
        assertEquals(16384, LocalModelEngine.contextForRoom(8_600_000_000L, 15_081L * 1_048_576, file, bytes))

    @Test fun noSpeedBoostDraft() {
        assertFalse(DraftModel.eligible(file))
        assertTrue(DraftModel.eligible("qwen3-4b-thinking-2507-q4_0.gguf"))
    }

    @Test fun neverTheLibrarian() = assertTrue(LocalModelEngine.LIBRARIAN_PRESETS.none { it.fileName == file })

    /** Qwen/Qwen3.5-4B chat_template.jinja, add_generation_prompt: "<|im_start|>assistant\n<think>\n"
     *  (thinking on) and "<think>\n\n</think>\n\n" (enable_thinking false) — what LocalPrompt emits. */
    @Test fun promptsMatchQwen35Template() {
        assertTrue(LocalPrompt.build("s", emptyList(), "u").endsWith("<|im_start|>user\nu<|im_end|>\n<|im_start|>assistant\n<think>\n"))
        assertTrue(LocalPrompt.buildNoThink("s", "u").endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }
}
