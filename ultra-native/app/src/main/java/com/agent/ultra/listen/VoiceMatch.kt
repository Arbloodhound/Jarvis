package com.agent.ultra.listen

import kotlin.math.sqrt

/**
 * A voice heard before: a running centroid of speaker embeddings ("voiceprint") plus that voice's
 * usual speech rate and loudness. Stored only on the phone (AmbientMemory's voices table).
 * [num] numbers unnamed voices ("Speaker 2", "Speaker 3", ...); the owner is "Me".
 */
data class Voice(
    val id: Long,
    val num: Int,
    val name: String?,
    val owner: Boolean,
    val centroid: FloatArray,
    val n: Int,
    val lastSeen: Long,
    val rate: Baseline = Baseline(),
    val loud: Baseline = Baseline(),
) {
    val label: String get() = CueTags.label(owner, name, num)
    override fun equals(other: Any?) = other is Voice && other.id == id
    override fun hashCode() = id.hashCode()
}

/**
 * Online speaker clustering by cosine similarity. Pure, JVM-tested.
 *
 * Each utterance's embedding goes to the most similar known voice when that similarity is at
 * least [MATCH]; otherwise it starts a new voice, but only if the utterance had enough speech
 * ([NEW_MIN_SPEECH_MS]) for its embedding to be trusted. Too little speech, or a short line that
 * matches nobody, is "Unknown" rather than a new speaker every few seconds.
 *
 * [MATCH] was picked on the host with sherpa-onnx 1.13.8 and nemo_en_titanet_small on 13 clips
 * from 4 speakers (2 to 17 s): same-speaker cosines were mostly 0.55 to 0.8, different speakers
 * at most 0.37 (full clips) / 0.49 (1.5 s cuts). A handful of clips is not a benchmark: far-field
 * phone audio, TV voices and similar-sounding people will merge or split voices sometimes.
 */
object VoiceMatch {
    const val MATCH = 0.5f
    /** No embedding at all below this much speech. */
    const val MIN_SPEECH_MS = 1_000L
    /** A voice nobody matches becomes a new speaker only with this much speech. */
    const val NEW_MIN_SPEECH_MS = 2_000L
    /** The centroid's memory: after this many lines a new one weighs 1/CENTROID_CAP. */
    const val CENTROID_CAP = 50
    /** Most voices kept; past it the longest-unheard unnamed one is forgotten. */
    const val MAX_VOICES = 40
    /** Overlap check: a window that is clearly not the line's voice ... */
    const val OVERLAP_MAIN_MAX = 0.3f

    sealed class Decision {
        data class Match(val voice: Voice, val similarity: Float) : Decision()
        object New : Decision()
        object Unknown : Decision()
    }

    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val norm = sqrt(s).toFloat()
        return if (norm <= 0f || norm.isNaN()) v.copyOf() else FloatArray(v.size) { v[it] / norm }
    }

    /** Cosine similarity; 0 for vectors of different sizes (another model's voiceprint). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na <= 0 || nb <= 0) return 0f
        return (dot / sqrt(na * nb)).toFloat()
    }

    /** The most similar voice and its similarity, or null when there are none. */
    fun best(voices: List<Voice>, emb: FloatArray, except: Long? = null): Pair<Voice, Float>? =
        voices.filter { it.id != except }.map { it to cosine(it.centroid, emb) }.maxByOrNull { it.second }

    fun decide(voices: List<Voice>, emb: FloatArray?, speechMs: Long): Decision {
        if (emb == null || speechMs < MIN_SPEECH_MS) return Decision.Unknown
        val b = best(voices, emb)
        if (b != null && b.second >= MATCH) return Decision.Match(b.first, b.second)
        return if (speechMs >= NEW_MIN_SPEECH_MS) Decision.New else Decision.Unknown
    }

    /** The owner's enrolled voice moves only on a confident match, so a similar voice can't
     * slowly drag "Me" toward itself. */
    const val OWNER_UPDATE = 0.6f

    /** Whether a line matched at [similarity] should move [v]'s centroid. */
    fun shouldUpdate(v: Voice, similarity: Float): Boolean =
        similarity >= if (v.owner) OWNER_UPDATE else MATCH

    /** [c] (a unit centroid of [n] lines) moved toward [emb]; returns the new centroid and count. */
    fun update(c: FloatArray, n: Int, emb: FloatArray): Pair<FloatArray, Int> {
        if (c.size != emb.size) return normalize(emb) to 1
        val n2 = minOf(n + 1, CENTROID_CAP)
        val w = 1f / n2
        val e = normalize(emb)
        return normalize(FloatArray(c.size) { c[it] * (1 - w) + e[it] * w }) to n2
    }

    /** Two voices' centroids joined, weighted by their line counts. */
    fun merge(a: FloatArray, na: Int, b: FloatArray, nb: Int): FloatArray {
        if (a.size != b.size) return a.copyOf()
        val wa = maxOf(na, 1).toFloat(); val wb = maxOf(nb, 1).toFloat()
        return normalize(FloatArray(a.size) { (a[it] * wa + b[it] * wb) / (wa + wb) })
    }

    /** The voice to forget to make room for a new one: the longest-unheard voice that the owner
     * hasn't named and isn't the owner. Null when there is room, or nothing may be forgotten. */
    fun evict(voices: List<Voice>, max: Int = MAX_VOICES): Voice? {
        if (voices.size < max) return null
        return voices.filter { !it.owner && it.name.isNullOrBlank() }.minByOrNull { it.lastSeen }
    }

    /**
     * Best-effort overlap / interruption inside one utterance (one mic: two voices at once can't
     * be separated). The line is cut into windows; [windows] holds, per window, its similarity to
     * the line's own voice and the best similarity to any OTHER known voice. The first window
     * after the start that is clearly not the line's voice ([OVERLAP_MAIN_MAX]) but clearly another
     * known one ([MATCH]) is where a second voice came in; -1 when none.
     */
    fun overlapWindow(windows: List<Pair<Float, Float>>): Int {
        for (i in 1 until windows.size) {
            val (main, other) = windows[i]
            if (main <= OVERLAP_MAIN_MAX && other >= MATCH) return i
        }
        return -1
    }
}
