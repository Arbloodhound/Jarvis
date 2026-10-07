package com.agent.ultra.listen

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Speech-to-text with Jarvis's own whisper model: no Google, and no network after the model's
 * one-time download. The voice screen and the chat mic used Android's SpeechRecognizer, which on
 * a Pixel is Google's recognizer and on a de-Googled phone is usually absent altogether.
 *
 * One-shot, like VoiceInput: listens until the speaker pauses (the same [Segmenter] ambient
 * listening uses), then transcribes. Callbacks arrive on the main thread.
 */
class LocalSpeech(
    private val context: Context,
    private val onState: ((String) -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null,
    private val onResult: (String) -> Unit,
) {
    private var job: Job? = null
    private val main = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) { main.post(block) }

    fun start(): Boolean {
        val micOk = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!micOk) { ui { onError?.invoke("Microphone permission is needed") }; return false }
        stop()
        job = scope.launch { listenOnce() }
        return true
    }

    fun stop() { job?.cancel(); job = null }

    private suspend fun listenOnce() {
        if (!WhisperNative.available) { ui { onError?.invoke("Speech model library missing from this build") }; return }
        if (!LocalWhisper.modelPresent(context)) {
            ui { onState?.invoke("downloading") }
            if (!LocalWhisper.fetchModel(context)) {
                ui { onError?.invoke("Couldn't download the speech model (needs internet once)") }
                return
            }
        }
        val pcm = record() ?: return
        ui { onState?.invoke("thinking") }
        val r = LocalWhisper.transcribe(context, pcm)
        val text = accepted(r)
        ui { if (text != null) onResult(text) else onError?.invoke("I didn't catch that") }
    }

    /** Mic until the first complete utterance. Null when cancelled or nothing usable was heard. */
    private suspend fun record(): ShortArray? {
        val sr = AmbientConfig.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, AmbientConfig.FRAME * 2 * 16))
        } catch (e: Exception) {
            ui { onError?.invoke("Microphone unavailable") }; return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); ui { onError?.invoke("Microphone unavailable") }; return null
        }
        var heard: ShortArray? = null
        val seg = Segmenter { pcm, _ -> if (heard == null) heard = pcm }
        val frame = ShortArray(AmbientConfig.FRAME)
        val started = System.currentTimeMillis()
        try {
            rec.startRecording()
            ui { onState?.invoke("listening") }
            while (currentCoroutineContext().isActive && heard == null) {
                val n = rec.read(frame, 0, frame.size)
                if (n < 0) { ui { onError?.invoke("Microphone error") }; return null }
                if (n == 0) continue
                seg.push(if (n == frame.size) frame else frame.copyOf(n))
                val waited = System.currentTimeMillis() - started
                if (!seg.speaking && waited > SILENCE_TIMEOUT_MS) {
                    ui { onError?.invoke("I didn't hear anything") }; return null
                }
                if (waited > MAX_LISTEN_MS) break
            }
            return heard
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private const val SILENCE_TIMEOUT_MS = 8_000L
        private const val MAX_LISTEN_MS = 30_000L

        /** The text to use, or null for nothing / likely hallucination. One-word commands
         * ("flashlight") are fine here, unlike ambient memory's two-word floor. Pure, JVM-tested. */
        fun accepted(r: WhisperNative.Result?): String? {
            val t = r?.text?.trim().orEmpty()
            if (t.isEmpty() || r == null || r.confidence < AmbientConfig.MIN_TOKEN_P) return null
            if (AmbientListenService.isWhisperNoise(t)) return null
            return t
        }
    }
}

/**
 * One whisper context for interactive speech, separate from the ambient service's (a context
 * takes one caller at a time). Loaded on first use, freed after a minute idle so it isn't
 * holding ~100 MB next to the LLM.
 */
object LocalWhisper {
    private val lock = Mutex()
    private var handle = 0L
    private var idle: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val IDLE_MS = 60_000L

    fun modelPresent(context: Context) =
        File(context.filesDir, AmbientConfig.ASR_MODEL).let { it.exists() && it.length() >= 10_000_000L }

    fun fetchModel(context: Context): Boolean {
        val dir = context.filesDir
        val ok = ModelFetcher.ensure(dir, AmbientConfig.ASR_MODEL, AmbientConfig.ASR_URL, 10_000_000L)
        ModelFetcher.ensure(dir, AmbientConfig.VAD_MODEL, AmbientConfig.VAD_URL, 100_000L)   // optional
        return ok
    }

    suspend fun transcribe(context: Context, pcm: ShortArray): WhisperNative.Result? = lock.withLock {
        idle?.cancel()
        if (handle == 0L) {
            val dir = context.filesDir
            val vad = File(dir, AmbientConfig.VAD_MODEL).let { if (it.exists()) it.absolutePath else "" }
            handle = WhisperNative.nativeInit(File(dir, AmbientConfig.ASR_MODEL).absolutePath, vad)
            if (handle == 0L) return@withLock null
        }
        // The user is waiting and the LLM is idle while they talk: use the fast cores.
        val topo = com.agent.ultra.local.CpuTopology.current
        val threads = if (topo.llmCpus.isEmpty()) 4 else minOf(4, topo.llmCpus.size)
        val r = try {
            WhisperNative.transcribe(handle, MicGain.normalize(pcm), threads, "Jarvis", topo.llmCpus.toIntArray())   // far speech brought up
        } catch (e: Exception) {
            android.util.Log.w("UltraAmbient", "local speech failed: ${e.message}"); null
        }
        idle = scope.launch {
            delay(IDLE_MS)
            lock.withLock { if (handle != 0L) { WhisperNative.nativeFree(handle); handle = 0L } }
        }
        r
    }
}
