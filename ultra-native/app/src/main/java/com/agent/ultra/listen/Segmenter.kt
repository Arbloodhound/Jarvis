package com.agent.ultra.listen

import com.agent.ultra.listen.AmbientConfig.Companion.FRAME_MS
import com.agent.ultra.listen.AmbientConfig.Companion.GATE_ABOVE_FLOOR_DB
import com.agent.ultra.listen.AmbientConfig.Companion.GATE_ABS_MIN_DB
import com.agent.ultra.listen.AmbientConfig.Companion.HANGOVER_MS
import com.agent.ultra.listen.AmbientConfig.Companion.MAX_UTTER_MS
import com.agent.ultra.listen.AmbientConfig.Companion.MIN_UTTER_MS
import com.agent.ultra.listen.AmbientConfig.Companion.PRE_ROLL_FRAMES
import com.agent.ultra.listen.AmbientConfig.Companion.START_FRAMES
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Energy gate + segmenter. Silence and steady background noise never leave
 * this class, so whisper (the expensive part) only runs on candidate speech.
 *
 * The noise floor adapts: it falls fast when the room gets quieter and rises
 * slowly, so a TV left on raises the bar instead of triggering forever.
 * Not thread-safe; feed it from one capture thread. Frames are copied on
 * push, so the caller may reuse its buffer.
 */
class Segmenter(private val onUtterance: (ShortArray, Int) -> Unit) {
    private var floorDb = -60f
    private val preRoll = ArrayDeque<ShortArray>()
    private val current = ArrayList<ShortArray>()
    private var loudRun = 0
    private var quietMs = 0
    private var inSpeech = false

    /** True while a segment is open (speech started, not yet closed by silence). */
    val speaking: Boolean get() = inSpeech

    /** The room's noise floor (dBFS) as the gate sees it; frozen while a segment is open, so
     * read in the utterance callback it is the floor that utterance was cut against. */
    val noiseFloorDb: Float get() = floorDb

    fun reset() {
        preRoll.clear(); current.clear()
        loudRun = 0; quietMs = 0; inSpeech = false
    }

    fun push(input: ShortArray) {
        // Own copy: the capture loop reuses one read buffer, and preRoll/current
        // keep frames across calls. Storing the caller's array made every
        // utterance N copies of the last 32 ms read.
        val frame = input.copyOf()
        val db = dbfs(frame)
        val loud = db > maxOf(floorDb + GATE_ABOVE_FLOOR_DB, GATE_ABS_MIN_DB)

        if (!inSpeech) {
            floorDb = if (db < floorDb) 0.90f * floorDb + 0.10f * db
                      else              0.995f * floorDb + 0.005f * db
            preRoll.addLast(frame)
            if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
            loudRun = if (loud) loudRun + 1 else 0
            if (loudRun >= START_FRAMES) {
                inSpeech = true
                current.addAll(preRoll)
                preRoll.clear()
                quietMs = 0
            }
            return
        }

        current.add(frame)
        quietMs = if (loud) 0 else quietMs + FRAME_MS
        if (quietMs >= HANGOVER_MS || current.size * FRAME_MS >= MAX_UTTER_MS) flush()
    }

    private fun flush() {
        val speechMs = current.size * FRAME_MS - quietMs
        if (speechMs >= MIN_UTTER_MS) {
            val out = ShortArray(current.sumOf { it.size })
            var o = 0
            for (f in current) { f.copyInto(out, o); o += f.size }
            onUtterance(out, speechMs)
        }
        current.clear()
        inSpeech = false; loudRun = 0; quietMs = 0
    }

    companion object {
        fun dbfs(f: ShortArray): Float {
            var s = 0.0
            for (x in f) { val v = x / 32768.0; s += v * v }
            val rms = sqrt(s / f.size).coerceAtLeast(1e-9)
            return (20 * log10(rms)).toFloat()
        }
    }
}
