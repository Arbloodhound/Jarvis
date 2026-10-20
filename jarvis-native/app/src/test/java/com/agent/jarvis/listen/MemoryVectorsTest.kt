package com.agent.jarvis.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class MemoryVectorsTest {
    @Test fun meanPoolSkipsPaddingThenNormalises() {
        // batch 2, len 3, dim 2. Row 0 uses tokens 0,1; row 1 uses all three.
        val hidden = floatArrayOf(
            1f, 0f, 3f, 0f, 100f, 100f,     // row 0: padding token is huge and must be ignored
            0f, 3f, 0f, 3f, 0f, 3f,
        )
        val mask = arrayOf(intArrayOf(1, 1, 0), intArrayOf(1, 1, 1))
        val out = MemoryVectors.meanPoolNormalize(hidden, mask, 3, 2)
        assertEquals(1f, out[0][0], 1e-6f); assertEquals(0f, out[0][1], 1e-6f)
        assertEquals(0f, out[1][0], 1e-6f); assertEquals(1f, out[1][1], 1e-6f)
    }

    @Test fun normaliseGivesUnitLengthAndLeavesZeroAlone() {
        val v = MemoryVectors.normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, v[0], 1e-6f); assertEquals(0.8f, v[1], 1e-6f)
        val z = MemoryVectors.normalize(floatArrayOf(0f, 0f))
        assertEquals(0f, z[0], 0f)
    }

    @Test fun quantisedCosineIsCloseToTheFloatOne() {
        val rnd = java.util.Random(7)
        repeat(20) {
            val a = MemoryVectors.normalize(FloatArray(384) { rnd.nextGaussian().toFloat() })
            val b = MemoryVectors.normalize(FloatArray(384) { i -> a[i] + 0.7f * rnd.nextGaussian().toFloat() / sqrt(384f) * 10 })
            val blob = MemoryVectors.quantize(b)
            assertEquals(388, blob.size)
            val exact = MemoryVectors.cosine(a, b)
            assertEquals(exact, MemoryVectors.cosine(a, blob)!!, 0.01f)
            val back = MemoryVectors.dequantize(blob)!!
            for (i in b.indices) assertEquals(b[i], back[i], 0.005f)
        }
    }

    @Test fun emptyOrWrongWidthBlobIsNotScored() {
        val q = MemoryVectors.normalize(FloatArray(384) { 1f })
        assertNull(MemoryVectors.cosine(q, ByteArray(0)))
        assertNull(MemoryVectors.cosine(q, ByteArray(4 + 100)))
        assertNull(MemoryVectors.dequantize(ByteArray(0)))
    }
}
