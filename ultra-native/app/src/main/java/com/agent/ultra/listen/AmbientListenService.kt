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
    private var whisper = 0L
    private var gate: WakeGate? = null
    private var cmdWindowUntil = 0L
    private var lastMemoryAt = 0L          // thermal throttle: last memory-only transcription
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
        startForeground(NOTIF_ID, notification())
        acquireWake()
        if (captureJob == null) captureJob = scope.launch { captureLoop() }
        return START_STICKY
    }

    private fun captureLoop() = scope.launch(Dispatchers.Default) {
        if (!WhisperNative.available) { stopSelf(); return@launch }
        val modelDir = filesDir
        // First run: fetch models (~60 MB, once). ASR is required; VAD and the
        // wake-word trio are optional and fail soft (text-match wake still works).
        if (!ModelFetcher.ensure(modelDir, AmbientConfig.ASR_MODEL, AmbientConfig.ASR_URL, 10_000_000L)) {
            android.util.Log.w("UltraAmbient", "no ASR model and download failed; stopping")
            stopSelf(); return@launch
        }
        ModelFetcher.ensure(modelDir, AmbientConfig.VAD_MODEL, AmbientConfig.VAD_URL, 100_000L)
        ModelFetcher.ensure(modelDir, WakeGate.MEL, WakeGate.MEL_URL, 500_000L)
        ModelFetcher.ensure(modelDir, WakeGate.EMB, WakeGate.EMB_URL, 500_000L)
        if (WakeGate.cfgName(this@AmbientListenService) == WakeGate.DEFAULT_CLASSIFIER) {
            ModelFetcher.ensure(modelDir, WakeGate.DEFAULT_CLASSIFIER, WakeGate.JARVIS_URL, 500_000L)
        }
        whisper = WhisperNative.nativeInit(
            java.io.File(modelDir, AmbientConfig.ASR_MODEL).absolutePath,
            java.io.File(modelDir, AmbientConfig.VAD_MODEL).absolutePath.let {
                if (java.io.File(it).exists()) it else ""
            }
        )
        if (whisper == 0L) { stopSelf(); return@launch }
        gate = WakeGate(this@AmbientListenService,
            threshold = 0.5f).takeIf { it.ready }   // null => fall back to text-match wake

        val minBuf = AudioRecord.getMinBufferSize(
            AmbientConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AmbientConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, AmbientConfig.FRAME * 8)
        )

        val seg = Segmenter { pcm, speechMs -> onUtterance(pcm, speechMs) }
        val frame = ShortArray(AmbientConfig.FRAME)
        var lastPrune = 0L
        try {
            rec.startRecording()
            while (isActive) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) continue
                if (System.currentTimeMillis() < mutedUntil) { seg.reset(); gate?.reset(); continue }
                val fr = if (n == frame.size) frame else frame.copyOf(n)
                // Acoustic gate: when present, it decides when we're addressed.
                // whisper then runs only for the command window + throttled memory.
                if (gate?.accept(fr) == true) {
                    cmdWindowUntil = System.currentTimeMillis() + AmbientConfig.FOLLOWUP_MS
                }
                seg.push(fr)

                val now = System.currentTimeMillis()
                if (now - lastPrune > 3_600_000L) {
                    lastPrune = now
                    mem.prune(cfg.retentionDays, cfg.maxRows)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    private fun onUtterance(pcm: ShortArray, speechMs: Int) {
        val t = System.currentTimeMillis()
        val windowOpen = gate != null && t < cmdWindowUntil
        val plan = AmbientThrottle.decide(
            com.agent.ultra.agent.ThermalGuard.level.ambient, windowOpen, gate != null, t, lastMemoryAt,
        )
        if (!plan.transcribe) return                       // hot: memory-only capture dropped
        if (!windowOpen) lastMemoryAt = t
        val r = WhisperNative.transcribe(whisper, pcm, plan.threads, cfg.wakeName)
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
        // when no gate is loaded.
        if (gate != null && now < cmdWindowUntil) {
            cmdWindowUntil = 0L
            AmbientBridge.onCommand?.invoke(buildContextualRequest(r.text, now))
            return
        }

        when (val v = wake.classify(r.text, now)) {
            is WakeFilter.Verdict.Ambient -> Unit        // store only; stay silent
            is WakeFilter.Verdict.Addressed -> {
                if (v.command.isBlank()) return          // bare name; follow-up will carry the command
                AmbientBridge.onCommand?.invoke(buildContextualRequest(v.command, now))
            }
        }
    }

    /** Prepend recent verbatim transcript so the agent answers with what it overheard. */
    private fun buildContextualRequest(command: String, now: Long): String {
        val recent = mem.since(now - AmbientConfig.RECENT_WINDOW_MS, AmbientConfig.CONTEXT_BUDGET_CHARS)
        return if (recent.isBlank()) command
        else "[Ambient transcript, last 15 min — context only, may be noisy]\n$recent\n\n[Request]\n$command"
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
            .setContentTitle("Ultra is listening")
            .setContentText("Transcribing locally. Say \"${cfg.wakeName}\" to talk.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).setContentIntent(tap)
            .build()
    }

    override fun onDestroy() {
        captureJob?.cancel(); scope.cancel()
        if (whisper != 0L) { WhisperNative.nativeFree(whisper); whisper = 0L }
        gate?.close(); gate = null
        wakeLock?.let { if (it.isHeld) it.release() }
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ambient_listen"
        private const val NOTIF_ID = 7101
        @Volatile var instance: AmbientListenService? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, AmbientListenService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
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
