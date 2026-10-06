package com.agent.ultra.listen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.agent.ultra.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Always-on ambient listener. A microphone foreground service: the mic
 * indicator will stay visible and cannot be hidden — that is the OS telling
 * the truth, and we keep it that way.
 *
 * Pipeline per frame:
 *   AudioRecord → Segmenter (energy gate) → whisper(+Silero VAD) → text filter
 *   → every line stored as memory → WakeFilter → only addressed speech runs.
 *
 * Phone mic only. The G2 mic is a later source that writes into the same
 * Segmenter; nothing else changes when it lands.
 */
class AmbientListenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null
    private lateinit var cfg: AmbientConfig
    private lateinit var mem: AmbientMemory
    private lateinit var wake: WakeFilter
    @Volatile private var whisper = 0L
    @Volatile private var gate: WakeGate? = null
    @Volatile private var cmdWindowUntil = 0L   // written by the capture loop and the whisper worker
    // Each acoustic wake opens a numbered window. An utterance is stamped, on the capture thread
    // as it ends, with the window open at that moment (0 = none). Judging it later, when the
    // queued transcription runs, let speech that ended BEFORE the wake word count as the command.
    @Volatile private var windowId = 0L
    private var consumedWindowId = 0L            // whisper worker only
    private var lastMemoryAt = 0L          // thermal throttle: last memory-only transcription
    // The cores the LLM doesn't run on (the little ones on big.LITTLE); empty = unpinned.
    private val whisperCpus by lazy { com.agent.ultra.local.CpuTopology.current.whisperCpus.toIntArray() }
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile var mutedUntil = 0L          // echo guard: set by the TTS layer while speaking

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cfg = AmbientConfig(this)
        mem = AmbientMemory.get(this)
        wake = WakeFilter(cfg)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A microphone FGS throws without RECORD_AUDIO, and Android 15 refuses one
        // started from BOOT_COMPLETED. Either way: stop quietly, never crash.
        val micOk = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        try {
            if (!micOk) throw SecurityException("RECORD_AUDIO not granted")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification())
            }
        } catch (e: Exception) {
            android.util.Log.w("UltraAmbient", "cannot start listening: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWake()
        if (captureJob == null) captureJob = scope.launch { runPipeline() }
        // "Hey Jarvis" shouldn't have to wait for the brain to load either.
        if (com.agent.ultra.ui.UltraPrefs.preloadBrain(this)) {
            com.agent.ultra.local.LocalModelEngine.shared(applicationContext).preloadInBackground("ambient listening started")
        }
        return START_STICKY
    }

    /** Owns the native handles: they are created here and freed in this coroutine's
     * finally, after capture and transcription have both stopped. Freeing them from
     * onDestroy raced an in-flight whisper_full / OrtSession.run. */
    private suspend fun runPipeline() {
        try {
            if (!initModels()) { stopSelf(); return }
            val utterances = Channel<Utterance>(4, BufferOverflow.DROP_OLDEST)
            coroutineScope {
                // whisper runs off the capture thread, so reading the mic (and feeding
                // the wake gate) never stalls for the length of a transcription.
                launch { for (u in utterances) onUtterance(u.pcm, u.endedAt, u.windowId) }
                try { capture(utterances) } finally { utterances.close() }
            }
            // Capture ended on its own (mic error), not by onDestroy: don't stay "listening".
            if (currentCoroutineContext().isActive) stopSelf()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("UltraAmbient", "pipeline failed: ${e.message}")
            stopSelf()
        } finally {
            gate?.close(); gate = null
            if (whisper != 0L) { WhisperNative.nativeFree(whisper); whisper = 0L }
        }
    }

    private class Utterance(val pcm: ShortArray, val endedAt: Long, val windowId: Long)

    /** Returns false if listening can't start. Checks for cancellation between the
     * slow steps, so a stop during the first-run download doesn't init and leak. */
    private suspend fun initModels(): Boolean {
        if (!WhisperNative.available) return false
        val modelDir = filesDir
        // First run: fetch models (~60 MB, once). ASR is required; VAD and the
        // wake-word trio are optional and fail soft (text-match wake still works).
        if (!ModelFetcher.ensure(modelDir, AmbientConfig.ASR_MODEL, AmbientConfig.ASR_URL, 10_000_000L)) {
            android.util.Log.w("UltraAmbient", "no ASR model and download failed; stopping")
            return false
        }
        ModelFetcher.ensure(modelDir, AmbientConfig.VAD_MODEL, AmbientConfig.VAD_URL, 100_000L)
        ModelFetcher.ensure(modelDir, WakeGate.MEL, WakeGate.MEL_URL, 500_000L)
        ModelFetcher.ensure(modelDir, WakeGate.EMB, WakeGate.EMB_URL, 500_000L)
        if (WakeGate.cfgName(this@AmbientListenService) == WakeGate.DEFAULT_CLASSIFIER) {
            ModelFetcher.ensure(modelDir, WakeGate.DEFAULT_CLASSIFIER, WakeGate.JARVIS_URL, 500_000L)
        }
        currentCoroutineContext().ensureActive()
        whisper = WhisperNative.nativeInit(
            java.io.File(modelDir, AmbientConfig.ASR_MODEL).absolutePath,
            java.io.File(modelDir, AmbientConfig.VAD_MODEL).absolutePath.let {
                if (java.io.File(it).exists()) it else ""
            }
        )
        if (whisper == 0L) return false
        currentCoroutineContext().ensureActive()
        gate = WakeGate(this@AmbientListenService,
            threshold = 0.5f).takeIf { it.ready }   // null => fall back to text-match wake
        return true
    }

    /** Reads the mic until cancelled. A dead or failing AudioRecord (audioserver
     * restart) is rebuilt a few times; it never spins on an error code. */
    private suspend fun capture(out: Channel<Utterance>) {
        val seg = Segmenter { pcm, _ ->
            val now = System.currentTimeMillis()
            val open = gate != null && now < cmdWindowUntil
            out.trySend(Utterance(pcm, now, if (open) windowId else 0L))
        }
        var failures = 0
        while (currentCoroutineContext().isActive && failures < MAX_MIC_FAILURES) {
            if (captureOnce(seg)) return
            failures++
            seg.reset()
            android.util.Log.w("UltraAmbient", "mic failed ($failures/$MAX_MIC_FAILURES); retrying")
            delay(1_000L * failures)
        }
        if (failures >= MAX_MIC_FAILURES) android.util.Log.w("UltraAmbient", "mic keeps failing; stopping")
    }

    /** One AudioRecord's life. True when stopped by cancellation, false on a mic error. */
    private suspend fun captureOnce(seg: Segmenter): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            AmbientConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AmbientConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            // Size is in BYTES: 16 frames of 16-bit audio (~0.5 s) of slack.
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, AmbientConfig.FRAME * 2 * 16)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }

        val frame = ShortArray(AmbientConfig.FRAME)
        var lastPrune = 0L
        try {
            rec.startRecording()
            while (currentCoroutineContext().isActive) {
                val n = rec.read(frame, 0, frame.size)
                if (n < 0) {                        // ERROR_DEAD_OBJECT etc. never recover in place
                    android.util.Log.w("UltraAmbient", "AudioRecord.read error $n")
                    return false
                }
                if (n == 0) continue
                if (System.currentTimeMillis() < mutedUntil) { seg.reset(); gate?.reset(); continue }
                val fr = if (n == frame.size) frame else frame.copyOf(n)
                // Acoustic gate: when present, it decides when we're addressed.
                // whisper then runs only for the command window + throttled memory.
                if (gate?.accept(fr) == true) {
                    windowId++
                    cmdWindowUntil = System.currentTimeMillis() + AmbientConfig.FOLLOWUP_MS
                }
                seg.push(fr)

                val now = System.currentTimeMillis()
                if (now - lastPrune > 3_600_000L) {
                    lastPrune = now
                    mem.prune(cfg.retentionDays, cfg.maxRows)
                }
            }
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("UltraAmbient", "capture failed: ${e.message}")
            return false
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    /** [t] is when the utterance ended and [wid] the wake window open then (0 = none): the
     * command window is judged when the speech ended, not when the queued transcription runs.
     * A window is spent by one command, even if more of its speech is still queued. */
    private fun onUtterance(pcm: ShortArray, t: Long, wid: Long) {
        val windowOpen = gate != null && wid != 0L && wid != consumedWindowId
        val plan = AmbientThrottle.decide(
            com.agent.ultra.agent.ThermalGuard.level.ambient, windowOpen, gate != null, t, lastMemoryAt,
        )
        if (!plan.transcribe) return                       // hot: memory-only capture dropped
        if (!windowOpen) lastMemoryAt = t
        val r = WhisperNative.transcribe(whisper, pcm, plan.threads, cfg.wakeName, whisperCpus)
            ?: return
        // Text filters: drop low-confidence (hallucinated) and trivially short lines.
        if (r.confidence < AmbientConfig.MIN_TOKEN_P) return
        val words = r.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < AmbientConfig.MIN_WORDS) return
        if (isWhisperNoise(r.text)) return

        val now = System.currentTimeMillis()
        mem.record(r.text, now, r.confidence)          // EVERYTHING kept as memory

        // Acoustic gate fired within the window -> this utterance is the command,
        // no need for the spoken name in the text. Falls through to text-match
        // when no gate is loaded. The utterance the gate fired in usually holds the
        // wake phrase itself: strip it, and if nothing is left ("Hey Jarvis." then a
        // pause), keep the window open for the next utterance instead of spending it.
        if (windowOpen) {
            val command = WakeFilter.stripWakePhrase(r.text, wakeWords())
            if (command.length < 2) {
                if (windowId == wid) cmdWindowUntil = now + AmbientConfig.FOLLOWUP_MS
                return
            }
            consumedWindowId = wid
            // Close it, unless the gate has already opened a newer window meanwhile.
            if (windowId == wid) cmdWindowUntil = 0L
            dispatch(buildContextualRequest(command, now))
            return
        }

        when (val v = wake.classify(r.text, now)) {
            is WakeFilter.Verdict.Ambient -> Unit        // store only; stay silent
            is WakeFilter.Verdict.Addressed -> {
                if (v.command.isBlank()) return          // bare name; follow-up will carry the command
                dispatch(buildContextualRequest(v.command, now))
            }
        }
    }

    /** Words the acoustic gate's phrase is made of: the configured name, plus the
     * classifier's own name ("hey_jarvis.onnx" -> "jarvis"). */
    private fun wakeWords(): List<String> {
        val fromModel = WakeGate.cfgName(this).substringBeforeLast('.')
            .replace(Regex("_v\\d.*$"), "").split('_', '-', ' ')
            .filter { it.length > 2 && it.lowercase() !in WakeFilter.WAKE_PREFIXES }
        return (listOf(cfg.wakeName) + fromModel).filter { it.isNotBlank() }
    }

    /** The voice screen's brain answers when it is open; otherwise a brain owned by
     * this service does, so a command is never dropped because no Activity is alive. */
    private fun dispatch(request: String) {
        AmbientBridge.onCommand?.let { it(request); return }
        // Main, as VoiceActivity's lifecycleScope runs it.
        scope.launch(Dispatchers.Main) {
            // Same rule as the voice screen: never act for whoever is talking to a locked
            // phone. A service can't ask for the unlock itself, so it says so and stops.
            val km = getSystemService(android.app.KeyguardManager::class.java)
            if (km != null && km.isKeyguardLocked) {
                val speaker = speakerRef ?: com.agent.ultra.ui.Speaker(this@AmbientListenService).also { speakerRef = it }
                val msg = "Unlock your phone to continue."
                mutedUntil = System.currentTimeMillis() + EchoGuard.muteMsFor(msg)
                speaker.speak(msg) { mutedUntil = System.currentTimeMillis() + AmbientConfig.ECHO_GUARD_MS }
                return@launch
            }
            try { fallbackBrain().run(request) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("UltraAmbient", "command failed: ${e.message}") }
        }
    }

    private var brainRef: com.agent.ultra.agent.Brain? = null
    private var speakerRef: com.agent.ultra.ui.Speaker? = null

    @Synchronized private fun fallbackBrain(): com.agent.ultra.agent.Brain = brainRef ?: run {
        val speaker = speakerRef ?: com.agent.ultra.ui.Speaker(this).also { speakerRef = it }
        com.agent.ultra.agent.Brain(
            applicationContext, com.agent.ultra.local.LocalModelEngine.shared(applicationContext)
        ).also { b ->
            b.onAnswer = { text ->
                // Same echo guard as VoiceActivity: mute while speaking, bounded.
                mutedUntil = System.currentTimeMillis() + EchoGuard.muteMsFor(text)
                speaker.speak(text) {
                    mutedUntil = System.currentTimeMillis() + AmbientConfig.ECHO_GUARD_MS
                }
            }
            brainRef = b
        }
    }

    /** Prepend recent verbatim transcript so the agent answers with what it overheard. */
    private fun buildContextualRequest(command: String, now: Long): String {
        val recent = mem.since(now - AmbientConfig.RECENT_WINDOW_MS, AmbientConfig.CONTEXT_BUDGET_CHARS)
        return if (recent.isBlank()) command
        else "[Ambient transcript, last 15 min — context only, may be noisy]\n$recent\n\n" +
            "${com.agent.ultra.agent.IntentRouter.REQUEST_MARKER}$command"
    }

    private fun acquireWake() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ultra:ambient").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ambient listening", NotificationManager.IMPORTANCE_LOW)
        )
        val tap = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Jarvis is listening")
            .setContentText("Transcribing locally. Say \"${cfg.wakeName}\" to talk.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).setContentIntent(tap)
            .build()
    }

    override fun onDestroy() {
        // Native handles are freed by runPipeline's finally once in-flight calls return.
        captureJob?.cancel(); scope.cancel()
        try { speakerRef?.shutdown() } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ambient_listen"
        private const val NOTIF_ID = 7101
        private const val MAX_MIC_FAILURES = 3
        @Volatile var instance: AmbientListenService? = null

        /** Starts listening if it can. A service started with startForegroundService must call
         * startForeground, and without the mic permission it can't (microphone type): Android
         * then crashes the app when the service stops. So don't start it at all. */
        fun start(ctx: Context): Boolean {
            val micOk = androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!micOk) {
                android.util.Log.w("UltraAmbient", "not starting: RECORD_AUDIO not granted")
                return false
            }
            val i = Intent(ctx, AmbientListenService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                true
            } catch (e: Exception) {
                android.util.Log.w("UltraAmbient", "not starting: ${e.message}")
                false
            }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AmbientListenService::class.java)) }

        /** whisper emits stock phrases on silence/music; drop the common ones. */
        private val NOISE = setOf(
            "you", "thank you", "thanks for watching", "thank you.", "[blank_audio]",
            "[ silence ]", "(silence)", ".", "bye.", "so", "okay", "uh"
        )
        fun isWhisperNoise(t: String) = t.trim().lowercase().trim('.', ' ') in NOISE
    }
}

/** Decouples the service from the agent. VoiceActivity/Brain set onCommand. */
object AmbientBridge {
    @Volatile var onCommand: ((String) -> Unit)? = null
}
