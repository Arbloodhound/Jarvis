package com.agent.ultra.listen

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * The short sound when Jarvis is woken ("Hey Jarvis"), so the owner knows it is listening for
 * the command. Played on the media stream (the volume the owner hears Jarvis speak at), and
 * never twice within [MIN_GAP_MS]: the acoustic gate's fire and the text match of the same
 * utterance are one wake. Ambient listening is NOT muted for it: the command usually follows
 * the name without a pause, and muting would cut it off; the tone is 150 ms and has no words
 * for whisper to transcribe.
 */
object WakeChime {
    const val MIN_GAP_MS = 2_000L
    private const val TONE_MS = 150
    private const val VOLUME = 70   // of 100, relative to the media volume

    @Volatile private var last = 0L

    /** Whether a chime at [now] would be a second one for the same wake. Pure. */
    fun due(now: Long, lastAt: Long): Boolean = lastAt == 0L || now - lastAt >= MIN_GAP_MS

    fun play() {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (!due(now, last)) return
            last = now
        }
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, TONE_MS)
            Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, TONE_MS + 200L)
        } catch (e: RuntimeException) {   // no audio resources: waking still works, silently
            android.util.Log.w("UltraAmbient", "wake chime unavailable: ${e.message}")
        }
    }
}
