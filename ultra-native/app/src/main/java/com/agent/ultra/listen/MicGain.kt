package com.agent.ultra.listen

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Quiet speech made louder for whisper and the "Hey Jarvis" detector (owner, 2026-10-07: from
 * across the room it missed words "despite me being pretty loud"). Speech from a distance reaches
 * the phone 10–20 dB down; whisper's confidence filter and the wake model's training levels both
 * want it near normal. Pure, JVM-tested.
 */
object MicGain {
    /** Where speech is brought to (dBFS RMS of its loud frames): ordinary close-up speech. */
    const val TARGET_DB = -22f
    /** At most this much boost: past it, the room's noise comes up with the speech. */
    const val MAX_GAIN_DB = 18f

    fun rmsDb(pcm: ShortArray, from: Int = 0, to: Int = pcm.size): Float {
        if (to <= from) return -120f
        var sum = 0.0
        for (i in from until to) { val v = pcm[i] / 32768.0; sum += v * v }
        val rms = sqrt(sum / (to - from))
        return if (rms <= 0.0) -120f else (20 * log10(rms)).toFloat()
    }

    /** dB to gain from [levelDb] to [TARGET_DB]: never negative, at most [MAX_GAIN_DB]. */
    fun gainDb(levelDb: Float): Float = (TARGET_DB - levelDb).coerceIn(0f, MAX_GAIN_DB)

    /** [pcm] times [gainDb], clipped to 16 bits; the same array when no gain. */
    fun apply(pcm: ShortArray, gainDb: Float): ShortArray {
        if (gainDb <= 0.05f) return pcm
        val g = 10.0.pow(gainDb / 20.0)
        return ShortArray(pcm.size) { i -> (pcm[i] * g).coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }

    /**
     * A whole utterance brought up to [TARGET_DB], judged by its loud frames (the top fifth by
     * level), and never further than its peak allows without clipping. For whisper.
     */
    fun normalize(pcm: ShortArray, frame: Int = AmbientConfig.FRAME): ShortArray {
        if (pcm.isEmpty()) return pcm
        val levels = (0 until (pcm.size + frame - 1) / frame).map { f -> rmsDb(pcm, f * frame, min(pcm.size, (f + 1) * frame)) }.sorted()
        val loud = levels.subList((levels.size * 4) / 5, levels.size).average().toFloat()
        var peak = 1
        for (s in pcm) peak = max(peak, kotlin.math.abs(s.toInt()))
        val headroom = (20 * log10(32767.0 / peak)).toFloat()
        return apply(pcm, min(gainDb(loud), headroom))
    }

    /**
     * Running gain for the wake detector's frames: follows the level of speech-like frames (those
     * above [gate]), changes slowly so the detector sees a steady voice, and stays put in silence.
     */
    class Agc {
        var gainDb = 0f
            private set
        private var speechDb = TARGET_DB

        fun process(frame: ShortArray, gate: Float): ShortArray {
            val db = rmsDb(frame)
            if (db > gate) {
                speechDb = 0.8f * speechDb + 0.2f * db
                gainDb = 0.7f * gainDb + 0.3f * gainDb(speechDb)
            }
            return apply(frame, gainDb)
        }
    }
}
