package com.agent.ultra.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.agent.ultra.listen.LocalVoice
import com.k2fsa.sherpa.onnx.GeneratedAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Answers spoken aloud, on-device. With [UltraPrefs.ownVoice] on, Jarvis's own voice
 * ([LocalVoice]: sherpa-onnx, no Google, no network); otherwise — or whenever that can't speak
 * (library missing, voice not downloaded yet, synthesis error) — Android's TextToSpeech, which
 * is only created when first needed, so a working own voice never wakes the platform engine.
 *
 * whenDone fires exactly once per speak: when speech finishes, fails, or [stop] cuts it off (the
 * echo guards in VoiceActivity / AmbientListenService unmute on it). A new speak replaces any
 * unfinished one (QUEUE_FLUSH) and drops the old whenDone, as the platform engine always has:
 * firing it would end the echo guard of the speech now starting.
 *
 * Start and finish are logged (`UltraSpeak`) — that is how speak-back gets verified on a
 * machine that cannot hear the phone.
 */
class Speaker(
    context: Context,
    /** True only for the Real-use check's spoken "Hey Jarvis": the listener must hear that one. */
    private val heardByListener: Boolean = false,
) {
    /** Whether this speaker is talking now (counted in [talkingCount] for the listener's echo guard). */
    @Volatile private var talking = false

    private fun markTalking() {
        if (heardByListener || talking) return
        talking = true; talkingCount.incrementAndGet()
    }

    private fun markIdle() {
        if (!talking) return
        talking = false; talkingCount.decrementAndGet(); lastTalkEndAt = System.currentTimeMillis()
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    // IO: audio writes and native synthesis block their thread for as long as they run.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One own-voice utterance: its job and, once opened, its AudioTrack. */
    private class Playback {
        var job: Job? = null
        @Volatile var track: AudioTrack? = null
    }

    // Guards everything below. Every speak and stop takes a new generation; callbacks for an
    // older one (a flushed platform utterance, a cancelled own-voice job) are ignored.
    private val lock = Any()
    private var gen = 0L
    private var whenDoneCb: (() -> Unit)? = null
    private var own: Playback? = null

    private var tts: TextToSpeech? = null
    private var creating = false
    private var ready = false
    private var failed = false
    private var pending: String? = null
    private var pendingGen = 0L
    private var shut = false

    init {
        // Without the own voice, warm the platform engine now (it starts asynchronously), as
        // before; with it, the engine is only created if a fallback ever needs it.
        if (!UltraPrefs.ownVoice(app)) {
            synchronized(lock) { creating = true }
            main.post { createEngine() }
        }
    }

    /** Speak text. Held until the platform engine is ready if that is the path and it is starting. */
    fun speak(text: String, whenDone: (() -> Unit)? = null) {
        val clean = forSpeech(text)
        if (clean.isBlank()) { whenDone?.invoke(); return }
        markTalking()
        val ownVoice = UltraPrefs.ownVoice(app)
        val old: Playback?
        val g: Long
        synchronized(lock) {
            pending = null; whenDoneCb = whenDone; g = ++gen
            old = own
            // Library load and file checks happen in the job, off the caller's thread.
            own = if (ownVoice) Playback().also { p -> p.job = scope.launch { speakOwn(clean, g, p) } } else null
        }
        silence(old)
        if (ownVoice) {
            try { synchronized(lock) { tts }?.stop() } catch (_: Exception) {}
            return
        }
        platformSpeak(clean, g)
    }

    fun stop() {
        markIdle()
        val old: Playback?
        val cb = synchronized(lock) {
            gen++; pending = null; old = own; own = null
            whenDoneCb.also { whenDoneCb = null }
        }
        silence(old)
        try { synchronized(lock) { tts }?.stop() } catch (_: Exception) {}
        cb?.invoke()
    }

    fun shutdown() {
        stop()
        scope.cancel()
        val t = synchronized(lock) { shut = true; tts.also { tts = null; ready = false } }
        try { t?.shutdown() } catch (_: Exception) {}
    }

    /** Fire whenDone if [g] is still the current speech. */
    private fun finish(g: Long) {
        if (synchronized(lock) { g == gen }) markIdle()
        val cb = synchronized(lock) { if (g != gen) null else whenDoneCb.also { whenDoneCb = null } }
        cb?.invoke()
    }

    // ── Audio output ─────────────────────────────────────────────────────

    /**
     * Media volume, the one the volume buttons set (owner, 2026-10-07: "I never heard it talk";
     * logs showed speech playing). USAGE_ASSISTANT before: on a Pixel that can be its own volume, or
     * silent while the app isn't the phone's assistant.
     */
    private val ATTRS: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audio = app.getSystemService(android.media.AudioManager::class.java)
    @Volatile private var focus: android.media.AudioFocusRequest? = null

    /** Music ducks while Jarvis talks. */
    private fun takeFocus() {
        if (android.os.Build.VERSION.SDK_INT < 26 || audio == null) return
        try {
            val r = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(ATTRS).build()
            audio.requestAudioFocus(r); focus = r
        } catch (_: Exception) {}
    }

    private fun dropFocus() {
        val r = focus ?: return
        focus = null
        if (android.os.Build.VERSION.SDK_INT >= 26) try { audio?.abandonAudioFocusRequest(r) } catch (_: Exception) {}
    }

    // ── Own voice ────────────────────────────────────────────────────────

    private fun silence(p: Playback?) {
        if (p == null) return
        p.job?.cancel()
        // pause + flush silences it now and frees a blocked write(); the job releases it.
        try { p.track?.let { it.pause(); it.flush() } } catch (_: IllegalStateException) {}
    }

    private suspend fun speakOwn(clean: String, g: Long, p: Playback) {
        if (!LocalVoice.available || !LocalVoice.voicePresent(app)) {
            if (LocalVoice.available) LocalVoice.fetchInBackground(app)
            platformSpeak(clean, g)
            return
        }
        // The memory model gives way while Jarvis talks: synthesis is slower than speech when they share
        // the fast cores (Real-use check, Pixel 2026-10-07: Kokoro 1.4-1.5x real time beside it).
        com.agent.ultra.listen.Librarian.yieldNow()
        val parts = LocalVoice.chunks(clean, firstMax = FIRST_PART_CHARS)
        Log.i(TAG, "SPEAK OWN (${clean.length} chars, ${parts.size} parts) id=ultra-$g")
        var fallbackFrom = -1
        var written = 0          // parts fully handed to the track
        var t: AudioTrack? = null
        try {
            var frames = 0L
            var rate = 0
            coroutineScope {
                // Synthesis runs one piece ahead of playback, so speech starts after the first.
                val made = Channel<Pair<Int, GeneratedAudio?>>(capacity = 1)
                val producer = launch {
                    for (i in parts.indices) {
                        val a = LocalVoice.generate(app, parts[i])
                        made.send(i to a)
                        if (a == null) break
                    }
                    made.close()
                }
                for ((i, a) in made) {
                    if (a == null) { fallbackFrom = i; break }
                    if (t == null) {
                        rate = a.sampleRate
                        t = openTrack(rate)
                        if (t == null) { fallbackFrom = i; break }
                        takeFocus()
                        p.track = t
                        if (!isActive) break
                        t!!.play()
                    }
                    if (a.sampleRate != rate) { fallbackFrom = i; break }
                    val pcm = LocalVoice.toPcm16(a.samples)
                    if (!write(t!!, pcm)) { fallbackFrom = i; break }
                    frames += pcm.size
                    written = i + 1
                }
                producer.cancel()
            }
            t?.let { if (frames > 0) drain(it, frames, rate) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "own voice playback failed: ${e.message}")
            // Don't repeat what was already heard; the part cut off mid-way is said again.
            fallbackFrom = if (written < parts.size) written else -1
        } finally {
            t?.let { tr -> p.track = null; try { tr.release() } catch (_: Exception) {} }
            dropFocus()
        }
        if (!currentCoroutineContext().isActive) return   // stopped or replaced
        if (fallbackFrom >= 0) {
            Log.w(TAG, "own voice failed at part $fallbackFrom; platform engine says the rest")
            platformSpeak(parts.drop(fallbackFrom).joinToString(" "), g)
        } else {
            Log.i(TAG, "SPEAK DONE id=ultra-$g")
            finish(g)
        }
    }

    private fun openTrack(rate: Int): AudioTrack? = try {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) null else AudioTrack.Builder()
            .setAudioAttributes(
                ATTRS
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            // ~200 ms: small, so playback starts as soon as the first piece is written.
            .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .let { tr -> if (tr.state == AudioTrack.STATE_INITIALIZED) tr else { tr.release(); null } }
    } catch (e: Exception) {
        Log.w(TAG, "own voice audio output unavailable: ${e.message}"); null
    }

    /** Blocking writes in short slices, so a stop is noticed within one slice. False on error. */
    private suspend fun write(t: AudioTrack, pcm: ShortArray): Boolean {
        var off = 0
        while (off < pcm.size) {
            if (!currentCoroutineContext().isActive) return false
            val n = t.write(pcm, off, minOf(WRITE_SLICE, pcm.size - off))
            if (n < 0) return false
            if (n == 0) delay(10)
            off += n
        }
        return true
    }

    /**
     * Let what was written play out, so whenDone (and the echo guard) follows the sound. A
     * buffer's worth of silence goes in after the speech: a stream track may not start until
     * its buffer is full, and stop() can't be used to flush it out — the head position reads 0
     * once a track is stopped, so the end could no longer be seen.
     */
    private suspend fun drain(t: AudioTrack, frames: Long, rate: Int) {
        fun head() = t.playbackHeadPosition.toLong() and 0xffffffffL
        // Bounded by what is left to play, in case the output stalls (device unplugged).
        val deadline = System.currentTimeMillis() + (frames - head()).coerceAtLeast(0) * 1000 / rate + 1_000
        val pad = ShortArray(t.bufferSizeInFrames.coerceAtLeast(rate / 5))
        var off = 0
        while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
            if (off < pad.size) {
                val n = t.write(pad, off, pad.size - off, AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) break
                off += n
            }
            if (head() >= frames) break
            delay(20)
        }
        try { t.pause(); t.flush() } catch (_: IllegalStateException) {}   // drop the trailing pad
    }

    // ── Platform TextToSpeech ────────────────────────────────────────────

    private fun platformSpeak(clean: String, g: Long) {
        var unavailable = false
        var create = false
        synchronized(lock) {
            if (g != gen) return
            when {
                failed -> unavailable = true
                !ready -> {
                    pending = clean; pendingGen = g
                    if (tts == null && !creating) { creating = true; create = true }
                }
                else -> {
                    Log.i(TAG, "SPEAK QUEUE (${clean.length} chars) id=ultra-$g")
                    tts?.let { applyVoice(it) }
                    val r = tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "ultra-$g")
                    if (r != TextToSpeech.SUCCESS) unavailable = true
                }
            }
        }
        if (create) main.post { createEngine() }
        if (unavailable) { Log.w(TAG, "cannot speak — engine unavailable"); finish(g) }
    }

    /** Settings → JARVIS'S VOICE on the phone's engine: the chosen installed voice, speed, pitch.
     *  Read on every speak, so a change applies to the next sentence. */
    private fun applyVoice(engine: TextToSpeech) {
        try {
            engine.setSpeechRate(VoicePrefs.speed(app))
            engine.setPitch(VoicePrefs.pitch(app))
            val want = VoicePrefs.phoneVoice(app)
            if (want.isNotEmpty() && engine.voice?.name != want) {
                engine.voices?.firstOrNull { it.name == want }?.let { engine.voice = it }
            }
        } catch (e: Exception) { Log.w(TAG, "couldn't apply the phone voice settings: ${e.message}") }
    }

    private fun createEngine() {
        if (synchronized(lock) { shut }) return
        try {
            val engine = TextToSpeech(app) { status -> onEngineInit(status) }
            synchronized(lock) { tts = engine }
        } catch (e: Exception) {
            Log.w(TAG, "engine construction failed", e)
            engineFailed()
        }
    }

    private fun onEngineInit(status: Int) {
        val engine = synchronized(lock) { tts }
        if (status != TextToSpeech.SUCCESS || engine == null) {
            Log.w(TAG, "engine init failed status=$status")
            engineFailed()
            return
        }
        try { engine.setAudioAttributes(ATTRS) } catch (_: Exception) {}   // media volume, as the own voice
        val r = engine.setLanguage(Locale.getDefault())
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "SPEAK START id=$utteranceId")
            }
            override fun onDone(utteranceId: String?) {
                Log.i(TAG, "SPEAK DONE id=$utteranceId")
                finish(genOf(utteranceId))
            }
            @Deprecated("required by the base class")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "SPEAK ERROR id=$utteranceId")
                finish(genOf(utteranceId))
            }
        })
        Log.i(TAG, "engine ready")
        val held: Pair<String, Long>? = synchronized(lock) {
            ready = true; creating = false
            pending?.let { p -> pending = null; p to pendingGen }
        }
        held?.let { (p, g) -> platformSpeak(p, g) }
    }

    /** Anything held for the engine will never be spoken; release whoever is waiting on it
     * (the voice session sits on 🔊). */
    private fun engineFailed() {
        val g = synchronized(lock) {
            failed = true; creating = false
            if (pending != null) { pending = null; pendingGen } else null
        }
        g?.let { finish(it) }
    }

    companion object {
        private val talkingCount = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile private var lastTalkEndAt = 0L
        /** How long after Jarvis stops talking the listener still ignores the mic (room echo). */
        private const val ECHO_TAIL_MS = 700L

        /**
         * Jarvis is talking somewhere in the app (chat, voice screen, ambient, a test), or just
         * stopped: the ambient listener must not take it as someone speaking. Before, only the
         * ambient service's own speech muted it, so a chat answer that said "Jarvis" could wake it
         * and Jarvis answered itself.
         */
        fun mutesListener(now: Long = System.currentTimeMillis()): Boolean =
            talkingCount.get() > 0 || now - lastTalkEndAt < ECHO_TAIL_MS

        /** "Hey Jarvis" over Jarvis's speech: listen at once, no echo tail. */
        fun clearEchoTail() { lastTalkEndAt = 0L }
        private const val TAG = "UltraSpeak"
        /** The first piece synthesised: Piper made a 169-char sentence in one go before a word was
         *  heard (Pixel 2026-10-17 22:47). ~80 chars is a few seconds of speech. */
        private const val FIRST_PART_CHARS = 80
        private const val WRITE_SLICE = 2048   // samples, ~93 ms at 22 kHz

        private fun genOf(utteranceId: String?): Long =
            utteranceId?.removePrefix("ultra-")?.toLongOrNull() ?: -1L

        /** Strip what reads badly aloud: emoji, code fences, raw JSON, markdown. */
        fun forSpeech(raw: String): String = raw
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("\\{[^{}]*\"tool\"[\\s\\S]*?\\}"), " ")
            .replace(Regex("[*_`#>]"), "")
            .replace(Regex("https?://\\S+")) { m ->
                // Read domains, not full URLs — "google dot com", not the query string.
                Regex("https?://(?:www\\.)?([^/\\s]+)").find(m.value)
                    ?.groupValues?.get(1)?.replace(".", " dot ") ?: " a link "
            }
            .replace(Regex("[\\p{So}\\p{Cn}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(600)
    }
}
