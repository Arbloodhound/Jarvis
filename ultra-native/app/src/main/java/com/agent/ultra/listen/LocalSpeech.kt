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
import kotlinx.coroutines.coroutineScope
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
    /** Live words while the user is still talking: what was said so far, re-read every ~1.5 s. */
    private val onPartial: ((String) -> Unit)? = null,
    private val onResult: (String) -> Unit,
) {
    /** Audio recorded so far in this listen, for the live words. Guarded by itself. */
    private val live = ArrayList<ShortArray>()
    private var job: Job? = null
    /** Set by [finish]: stop recording and transcribe what was heard so far. */
    @Volatile private var finishNow = false
    private val main = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) { main.post(block) }

    fun start(): Boolean {
        val micOk = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!micOk) { ui { onError?.invoke("Microphone permission is needed") }; return false }
        stop()
        finishNow = false
        job = scope.launch { listenOnce() }
        return true
    }

    /** Throw the recording away. */
    fun stop() { job?.cancel(); job = null }

    /** The mic's stop button: stop recording now and transcribe what was said (not throw it away,
     *  as cancelling did: the chat got nothing, owner 2026-10-18). */
    fun finish() { finishNow = true }

    private suspend fun listenOnce() {
        if (!WhisperNative.available) { ui { onError?.invoke("Speech model library missing from this build") }; return }
        if (!LocalWhisper.modelPresent(context)) {
            ui { onState?.invoke("downloading") }
            if (!LocalWhisper.fetchModel(context)) {
                ui { onError?.invoke("Couldn't download the speech model (needs internet once)") }
                return
            }
        }
        // Live words (owner, 2026-10-18: "show me the text of what I'm saying", like a keyboard's
        // voice typing): the recording so far is transcribed again every PARTIAL_EVERY_MS on the
        // fast cores. The final read below waits for one in flight (one whisper context).
        val pcm = coroutineScope {
            val partials = onPartial?.let { show ->
                launch {
                    while (isActive) {
                        delay(PARTIAL_EVERY_MS)
                        val snap = synchronized(live) {
                            if (live.isEmpty()) null else {
                                val keep = live.takeLast(PARTIAL_MAX_FRAMES)
                                val out = ShortArray(keep.sumOf { it.size })
                                var off = 0
                                for (c in keep) { c.copyInto(out, off); off += c.size }
                                out
                            }
                        } ?: continue
                        if (snap.size < AmbientConfig.SAMPLE_RATE / 2) continue
                        val words = accepted(LocalWhisper.transcribe(context, snap))
                        if (words != null && isActive) ui { show(words) }
                    }
                }
            }
            try { record() } finally { partials?.cancel() }
        } ?: return
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
        synchronized(live) { live.clear() }
        val seg = Segmenter { pcm, _ -> if (heard == null) heard = pcm }
        // A command, not a room: a noisy floor can keep a segment open to the 28 s cap, so it never
        // "ends right away". Cut it sooner.
        seg.maxMs = MAX_COMMAND_MS
        // Everything since recording began, for [finish] and the time cap: what is mid-utterance then.
        val all = ArrayList<ShortArray>()
        val frame = ShortArray(AmbientConfig.FRAME)
        val started = System.currentTimeMillis()
        try {
            rec.startRecording()
            ui { onState?.invoke("listening") }
            while (currentCoroutineContext().isActive && heard == null) {
                val n = rec.read(frame, 0, frame.size)
                if (n < 0) { ui { onError?.invoke("Microphone error") }; return null }
                if (n == 0) continue
                val chunk = if (n == frame.size) frame.copyOf() else frame.copyOf(n)
                all += chunk
                synchronized(live) { live += chunk }
                seg.push(chunk)
                if (finishNow) break
                val waited = System.currentTimeMillis() - started
                if (!seg.speaking && waited > SILENCE_TIMEOUT_MS) {
                    ui { onError?.invoke("I didn't hear anything") }; return null
                }
                if (waited > MAX_LISTEN_MS) break
            }
            if (heard == null && all.isNotEmpty() && currentCoroutineContext().isActive) {
                val out = ShortArray(all.sumOf { it.size })
                var off = 0
                for (c in all) { c.copyInto(out, off); off += c.size }
                heard = out
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
        private const val MAX_COMMAND_MS = 12_000
        private const val PARTIAL_EVERY_MS = 1_500L
        /** Live words re-read at most the last ~15 s (32 ms frames). */
        private const val PARTIAL_MAX_FRAMES = 470

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
    private var loadedName = ""
    private var idle: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val IDLE_MS = 60_000L

    fun modelPresent(context: Context) =
        File(context.filesDir, AmbientConfig.ASR_MODEL).let { it.exists() && it.length() >= 10_000_000L }

    fun fetchModel(context: Context): Boolean {
        val dir = context.filesDir
        val ok = ModelFetcher.ensure(dir, AmbientConfig.ASR_MODEL, AmbientConfig.ASR_URL, 10_000_000L)
        ModelFetcher.ensure(dir, AmbientConfig.VAD_MODEL, AmbientConfig.VAD_URL, 100_000L)   // optional
        // The larger model for next time, in the background (base answers meanwhile).
        val cfg = AmbientConfig(context)
        if (ok && cfg.accurateSpeech && !cfg.smallTooSlow && !AmbientConfig.smallReady(dir))
            scope.launch(Dispatchers.IO) { AmbientConfig.fetchSmall(dir) }
        return ok
    }

    suspend fun transcribe(context: Context, pcm: ShortArray): WhisperNative.Result? = lock.withLock {
        idle?.cancel()
        val dir = context.filesDir
        // small when it's downloaded and wanted (quiet speech), else base.
        val name = AmbientConfig(context).asrModel(dir)
        if (handle != 0L && name != loadedName) { WhisperNative.nativeFree(handle); handle = 0L }
        if (handle == 0L) {
            val vad = File(dir, AmbientConfig.VAD_MODEL).let { if (it.exists()) it.absolutePath else "" }
            handle = WhisperNative.nativeInit(File(dir, name).absolutePath, vad)
            if (handle == 0L && name != AmbientConfig.ASR_MODEL) {
                handle = WhisperNative.nativeInit(File(dir, AmbientConfig.ASR_MODEL).absolutePath, vad)
                loadedName = AmbientConfig.ASR_MODEL
            } else loadedName = name
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
