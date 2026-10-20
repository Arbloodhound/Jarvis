package com.agent.jarvis.listen

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure vector maths for semantic recall (JVM-tested): sentence-transformers pooling,
 * the compact on-disk form, and similarity against it.
 *
 * On disk a vector is 4 bytes of little-endian float scale + one signed byte per
 * dimension (value ≈ byte × scale): 388 bytes for MiniLM's 384 dims instead of 1,536.
 * Vectors are L2-normalised before quantising, so the dot product is the cosine.
 * An empty blob marks a row that failed to embed (never retried, never scored).
 */
object MemoryVectors {

    /** Mean of the token vectors where mask = 1, then L2-normalised (MiniLM's 1_Pooling +
     * Normalize modules). [hidden] is the flattened [batch, len, dim] last_hidden_state. */
    fun meanPoolNormalize(hidden: FloatArray, mask: Array<IntArray>, len: Int, dim: Int): List<FloatArray> =
        mask.mapIndexed { b, m ->
            val v = FloatArray(dim)
            var n = 0
            for (t in 0 until len) {
                if (t >= m.size || m[t] == 0) continue
                n++
                val base = (b * len + t) * dim
                for (d in 0 until dim) v[d] += hidden[base + d]
            }
            if (n > 0) for (d in 0 until dim) v[d] /= n
            normalize(v)
        }

    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val norm = kotlin.math.sqrt(s).toFloat()
        if (norm > 1e-12f) for (i in v.indices) v[i] /= norm
        return v
    }

    fun quantize(v: FloatArray): ByteArray {
        var max = 0f
        for (x in v) max = maxOf(max, kotlin.math.abs(x))
        val scale = if (max > 0f) max / 127f else 1f
        val bb = ByteBuffer.allocate(4 + v.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putFloat(scale)
        for (x in v) bb.put(kotlin.math.round(x / scale).toInt().coerceIn(-127, 127).toByte())
        return bb.array()
    }

    fun dequantize(blob: ByteArray): FloatArray? {
        if (blob.size <= 4) return null
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val scale = bb.getFloat()
        return FloatArray(blob.size - 4) { bb.get(4 + it) * scale }
    }

    /** Cosine of a normalised float query against a stored blob; null if the blob is
     * empty or a different width. */
    fun cosine(query: FloatArray, blob: ByteArray): Float? {
        if (blob.size != query.size + 4) return null
        val scale = ByteBuffer.wrap(blob, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat()
        var dot = 0f
        for (i in query.indices) dot += query[i] * blob[4 + i]
        return dot * scale
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val d = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (d > 0f) dot / d else 0f
    }
}
