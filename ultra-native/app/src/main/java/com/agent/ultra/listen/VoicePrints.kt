package com.agent.ultra.listen

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speaker embeddings ("voiceprints") on the phone, with sherpa-onnx (already in the APK for
 * Jarvis's voice) and NVIDIA NeMo's TitaNet-small, as converted by sherpa-onnx. Biometric data:
 * nothing here ever leaves the phone; the embeddings live in ambient.db and Erase ambient memory
 * erases them.
 *
 * Model: nemo_en_titanet_small.onnx from sherpa-onnx's speaker-recongition-models release
 * (sic), 40,257,283 bytes, sha256 below (both checked from here 2026-10-06). On the host it
 * separated speakers clearly better than the 3D-Speaker CAM++ and WeSpeaker ResNet34 English
 * models on the same clips (see [VoiceMatch]). Downloaded once, on an unmetered network, like
 * the sentence embedder; or when the owner records their voice in Settings.
 */
object VoicePrints {
    private const val TAG = "UltraVoice"
    const val MODEL = "nemo_en_titanet_small.onnx"
    const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/nemo_en_titanet_small.onnx"
    const val MODEL_BYTES = 40_257_283L
    const val MODEL_SHA256 = "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e"
    private const val THREADS = 2
    private const val FETCH_RETRY_MS = 6 * 3_600_000L
    private const val LOAD_RETRY_MS = 6 * 3_600_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetching = AtomicBoolean(false)
    @Volatile private var fetchFailedAt = 0L
    @Volatile private var loadFailedAt = 0L
    private var extractor: SpeakerEmbeddingExtractor? = null

    fun downloaded(ctx: Context): Boolean = File(ctx.applicationContext.filesDir, MODEL).length() == MODEL_BYTES

    /** One download at a time: the background fetch and an enrolment share one .part file. Not
     *  the object's own monitor, which [embed] holds. */
    private val fetchLock = Any()

    /** Downloads and checks the model (blocking, ~40 MB). True when it is present and intact. A
     *  second caller waits for a download already running, then finds the file. */
    fun fetch(ctx: Context): Boolean = synchronized(fetchLock) {
        val dir = ctx.applicationContext.filesDir
        if (downloaded(ctx)) return true
        if (!ModelFetcher.ensure(dir, MODEL, MODEL_URL, exactBytes = MODEL_BYTES)) return false
        val f = File(dir, MODEL)
        if (sha256(f) != MODEL_SHA256) {
            android.util.Log.w(TAG, "voiceprint model failed its checksum; discarded")
            f.delete()
            return false
        }
        android.util.Log.i(TAG, "voiceprint model downloaded")
        return true
    }

    /** Starts the download in the background when it's missing, the network is unmetered and the
     * last try wasn't in the past [FETCH_RETRY_MS]. Returns at once. */
    fun fetchInBackground(ctx: Context) {
        val app = ctx.applicationContext
        if (downloaded(app) || System.currentTimeMillis() - fetchFailedAt in 0 until FETCH_RETRY_MS) return
        if (!unmetered(app) || !fetching.compareAndSet(false, true)) return
        scope.launch {
            try { if (!fetch(app)) fetchFailedAt = System.currentTimeMillis() } finally { fetching.set(false) }
        }
    }

    private fun unmetered(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        cm.activeNetwork != null && !cm.isActiveNetworkMetered
    } catch (_: Exception) { false }

    /**
     * The unit-length embedding of [pcm] (16 kHz mono) between sample [from] and [to], or null
     * when the model isn't downloaded / can't load or the audio is too short for it. Blocking
     * (tens of ms per second of audio on the host); call off the main thread. One call at a time.
     */
    @Synchronized
    fun embed(ctx: Context, pcm: ShortArray, from: Int = 0, to: Int = pcm.size): FloatArray? {
        val ex = extractor(ctx) ?: return null
        val a = from.coerceIn(0, pcm.size); val b = to.coerceIn(a, pcm.size)
        if (b - a < AmbientConfig.SAMPLE_RATE / 2) return null
        return try {
            val samples = FloatArray(b - a) { pcm[a + it] / 32768f }
            val stream = ex.createStream()
            try {
                stream.acceptWaveform(samples, AmbientConfig.SAMPLE_RATE)
                stream.inputFinished()
                if (!ex.isReady(stream)) null else VoiceMatch.normalize(ex.compute(stream))
            } finally { stream.release() }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "voiceprint failed: ${e.message}")
            null
        }
    }

    /** Frees the model (~40 MB). The next [embed] loads it again. */
    @Synchronized
    fun release() {
        try { extractor?.release() } catch (_: Throwable) {}
        extractor = null
    }

    private fun extractor(ctx: Context): SpeakerEmbeddingExtractor? {
        extractor?.let { return it }
        if (!downloaded(ctx) || !LocalVoice.available) return null
        if (loadFailedAt != 0L && System.currentTimeMillis() - loadFailedAt in 0 until LOAD_RETRY_MS) return null
        return try {
            SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(
                model = File(ctx.applicationContext.filesDir, MODEL).absolutePath,
                numThreads = THREADS, debug = false, provider = "cpu",
            )).also {
                extractor = it; loadFailedAt = 0L
                android.util.Log.i(TAG, "voiceprint model loaded (dim ${it.dim()})")
            }
        } catch (e: Throwable) {
            loadFailedAt = System.currentTimeMillis()
            // sherpa refusing the file itself (require ptr != 0): damaged bytes; fetch again
            if (e is IllegalArgumentException) File(ctx.applicationContext.filesDir, MODEL).delete()
            android.util.Log.w(TAG, "voiceprint model failed to load: ${e.message}")
            null
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ── Owner enrolment ────────────────────────────────────────────────────

    const val ENROL_MS = 10_000L
    /** At least this much speech in the recording, or it is refused. */
    const val ENROL_MIN_SPEECH_MS = 5_000L

    sealed class Enrol {
        object Done : Enrol()
        data class Failed(val why: String) : Enrol()
    }

    /**
     * Records ~10 s of the owner talking and stores it as their voice ([AmbientMemory.enrolOwner]).
     * Downloads the model first if needed (the owner asked, so any network). Ambient listening is
     * muted meanwhile so the recording isn't also stored as heard speech. [onState] gets
     * "downloading" / "recording" / "saving".
     */
    suspend fun enrol(ctx: Context, onState: (String) -> Unit): Enrol {
        val app = ctx.applicationContext
        if (!LocalVoice.available) return Enrol.Failed("The voice library is missing from this build.")
        if (!downloaded(app)) {
            onState("downloading")
            if (!fetch(app)) return Enrol.Failed("Couldn't download the voice model (about 40 MB). Check the connection.")
        }
        val ambient = AmbientListenService.instance
        // Never cut short a longer mute (Jarvis speaking: the echo guard's), and at the end lift
        // only our own: a mute someone else set meanwhile stands.
        val ours = System.currentTimeMillis() + ENROL_MS + 2_000L
        val mute = ambient?.let { maxOf(it.mutedUntil, ours) }
        if (ambient != null && mute != null) ambient.mutedUntil = mute
        onState("recording")
        val pcm = try { record(ENROL_MS) } finally {
            if (ambient != null && ambient.mutedUntil == mute && mute == ours) {
                ambient.mutedUntil = System.currentTimeMillis() + 300L
            }
        }
            ?: return Enrol.Failed("The microphone isn't available.")
        onState("saving")
        val db = CueAudio.frameDb(pcm)
        val gate = CueAudio.gateDb(quietFloor(db))
        val speech = speechOnly(pcm, db, gate)
        val speechMs = speech.size * 1000L / AmbientConfig.SAMPLE_RATE
        if (speechMs < ENROL_MIN_SPEECH_MS) return Enrol.Failed(
            "Heard only ${speechMs / 1000} s of speech. Talk for the whole 10 seconds" +
                if (ambient != null) ", or turn ambient listening off for a moment and try again." else "."
        )
        val emb = embed(app, speech)
        if (AmbientListenService.instance == null) release()   // nothing else will use it soon
        if (emb == null) return Enrol.Failed("Couldn't make a voiceprint from that recording.")
        AmbientMemory.get(app).enrolOwner(emb, System.currentTimeMillis())
        return Enrol.Done
    }

    /** The recording's own noise floor: its quietest tenth. */
    private fun quietFloor(db: FloatArray): Float =
        if (db.isEmpty()) -60f else db.sorted()[db.size / 10]

    /** The frames above [gate], joined. */
    private fun speechOnly(pcm: ShortArray, db: FloatArray, gate: Float): ShortArray {
        val f = AmbientConfig.FRAME
        val out = ArrayList<Short>(pcm.size)
        for (i in db.indices) if (db[i] > gate) for (j in i * f until minOf(pcm.size, (i + 1) * f)) out += pcm[j]
        return out.toShortArray()
    }

    @android.annotation.SuppressLint("MissingPermission")   // checked by the caller (Settings)
    private suspend fun record(ms: Long): ShortArray? {
        val sr = AmbientConfig.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, AmbientConfig.FRAME * 2 * 16))
        } catch (e: Exception) { return null }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val total = (sr * ms / 1000).toInt()
        val out = ShortArray(total)
        var got = 0
        try {
            rec.startRecording()
            while (got < total && currentCoroutineContext().isActive) {
                val n = rec.read(out, got, minOf(AmbientConfig.FRAME, total - got))
                if (n < 0) return null
                got += n
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        return if (got < total / 2) null else out.copyOf(got)
    }
}
