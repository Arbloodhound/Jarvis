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
import com.agent.ultra.ui.queue
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

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
    /** The wake window open when the current segment started (0 = none). Capture thread only. */
    private var segStartWindow = 0L
    private var consumedWindowId = 0L            // whisper worker only
    private val windowLock = Any()               // a new window vs. the worker holding one open
    @Volatile private var cues: TranscriptCueWorker? = null   // speaker/pause tags, after storing
    private val turnGaps = TurnGaps()      // whisper worker only: the pause before each utterance
    // The cores the LLM doesn't run on (the little ones on big.LITTLE); empty = unpinned.
    private val whisperCpus by lazy { com.agent.ultra.local.CpuTopology.current.whisperCpus.toIntArray() }
    private val llmCpus by lazy { com.agent.ultra.local.CpuTopology.current.llmCpus.toIntArray() }
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile var mutedUntil = 0L          // echo guard: set by the TTS layer while speaking

    /** Voice enrolment's copy of the mic while capture holds it ([VoicePrints.enrol]): every frame
     *  read goes here too, before the mute (which keeps the recording out of memory). */
    @Volatile var tap: PcmTap? = null

    /** True while capture has the microphone open and reading. */
    @Volatile var capturing = false
        private set

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ResumeNotice.clear(this)   // listening again: the "tap to turn it back on" notice is stale
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
        // The librarian's small model: Settings → MEMORY MODEL (Qwen3-1.7B). It runs only once that
        // file is downloaded, and yields to the main brain (LocalModelEngine.complete).
        if (Librarian.model == null) {
            val small = com.agent.ultra.local.LocalModelEngine.librarian(applicationContext)
            Librarian.modelReady = { small.modelPresent }
            Librarian.model = SmallModel { prompt, maxTokens -> small.complete(prompt, maxTokens) }
        }
        Librarian.start(this, scope)   // organises what was heard while charging, screen off
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
            // The worker moves these into its own bounded backlog before each transcription, so
            // this drop only matters if a single transcription outlasts 16 utterances.
            // Overflow only: elements left behind when the pipeline stops are not "dropped".
            var stopping = false
            val utterances = Channel<Utterance>(16, BufferOverflow.DROP_OLDEST) { d ->
                if (!stopping) logDropped("handoff queue full", d.pcm.size, d.windowId != 0L)
            }
            val cueWorker = TranscriptCueWorker(applicationContext, mem, cfg).also { cues = it }
            coroutineScope {
                // whisper runs off the capture thread, so reading the mic (and feeding
                // the wake gate) never stalls for the length of a transcription.
                launch { transcribeWorker(utterances) }
                // Cues (voiceprints etc.) are added after a line is stored, never in its way.
                launch { cueWorker.run() }
                try { capture(utterances) } finally { stopping = true; utterances.close(); cueWorker.close() }
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

    private class Utterance(val pcm: ShortArray, val endedAt: Long, val windowId: Long, val floorDb: Float)

    /**
     * Transcribes utterances in the order they ended, preferably not while the on-device brain is
     * generating, loading or warming (nor within [AmbientBacklog.SETTLE_MS] of it): they wait in a
     * bounded backlog ([AmbientBacklog]) and run once it is idle, or once the oldest has waited
     * [AmbientBacklog.maxHoldMs] (whisper is on its own cores). Wake-word detection is
     * unaffected (capture thread). A command heard meanwhile is judged with the window it was
     * stamped with, and [dispatch] holds it until the brain is idle, so it still runs after the
     * current answer.
     */
    private suspend fun transcribeWorker(utterances: Channel<Utterance>) = coroutineScope {
        // The thinker only: the librarian runs on the LLM cores, not whisper's, and stops for a wake
        // (Librarian.yieldNow). Holding speech for it delayed "Hey Jarvis" by up to the hold cap.
        val busy = com.agent.ultra.local.LocalModelEngine.shared(applicationContext).busy
        val busyChangedAt = java.util.concurrent.atomic.AtomicLong(0L)
        val watcher = launch { busy.collect { busyChangedAt.set(System.currentTimeMillis()) } }
        val backlog = ArrayDeque<Utterance>()
        val maxHold = AmbientBacklog.maxHoldMs(whisperPinned = whisperCpus.isNotEmpty())
        fun idleNow() = AmbientBacklog.mayTranscribe(busy.value, System.currentTimeMillis(), busyChangedAt.get())
        // Speech to Jarvis (a wake window) is transcribed at once, and, with whisper on its own cores,
        // everything heard while a run is going: it may be "stop" or a change to the run.
        fun urgent() = backlog.any { it.windowId != 0L } ||
            (whisperCpus.isNotEmpty() && com.agent.ultra.agent.Brain.anyRunning)
        fun ready() = urgent() || AmbientBacklog.shouldDrain(busy.value, System.currentTimeMillis(), busyChangedAt.get(),
            backlog.firstOrNull()?.endedAt, maxHold)
        var heldWindow = 0L
        var capLogged = false

        fun take(u: Utterance) {
            val dropped = AmbientBacklog.admit(backlog, u, { it.pcm.size }, { it.windowId != 0L })
            if (!ready()) {
                if (backlog.size == 1) android.util.Log.i("UltraAmbient", "brain busy: holding transcription")
                // Once per window: its first queued utterance may be a bare "Hey Jarvis." whose
                // judging (and window reopening) now happens late.
                if (u.windowId != 0L && u.windowId != heldWindow && u.windowId != consumedWindowId) {
                    heldWindow = u.windowId
                    synchronized(windowLock) {
                        cmdWindowUntil = AmbientBacklog.holdWindowUntil(cmdWindowUntil, windowId, u.windowId, u.endedAt)
                    }
                }
            }
            for (d in dropped) logDropped("backlog full", d.pcm.size, d.windowId != 0L)
        }

        try {
            while (true) {
                // Pull in whatever is waiting, so the backlog's drop rule (not the channel's) applies.
                while (true) {
                    val r = utterances.tryReceive()
                    if (r.isClosed) return@coroutineScope       // capture ended
                    take(r.getOrNull() ?: break)
                }
                if (backlog.isNotEmpty() && ready()) {
                    if (idleNow()) capLogged = false
                    else if (!capLogged) {
                        capLogged = true
                        android.util.Log.i("UltraAmbient", "brain busy over ${maxHold / 1000} s: transcribing " +
                            "${backlog.size} held utterance(s) anyway (whisper cpus=${whisperCpus.toList()})")
                    }
                    val u = backlog.removeFirst()
                    onUtterance(u.pcm, u.endedAt, u.windowId, u.floorDb)
                    continue
                }
                if (backlog.isEmpty()) {
                    take(utterances.receiveCatching().getOrNull() ?: return@coroutineScope)
                    continue
                }
                // Queued and busy: sleep until speech arrives, the brain has settled or the hold
                // cap is reached. select takes an element atomically, so none is lost when the
                // idle side wins.
                val idle = async {
                    while (!ready()) {
                        val wait = AmbientBacklog.waitMs(busy.value, System.currentTimeMillis(), busyChangedAt.get(),
                            backlog.firstOrNull()?.endedAt, maxHold)
                        if (busy.value) kotlinx.coroutines.withTimeoutOrNull(wait) { busy.first { !it } }
                        else delay(wait)
                    }
                }
                try {
                    select<Unit> {
                        utterances.onReceiveCatching { r -> r.getOrNull()?.let { take(it) } }
                        idle.onAwait {}
                    }
                } finally { idle.cancel() }
            }
        } finally {
            watcher.cancel()
            if (backlog.isNotEmpty()) android.util.Log.i("UltraAmbient", "stopping with ${backlog.size} untranscribed")
        }
    }

    private fun logDropped(why: String, samples: Int, window: Boolean) {
        val n = dropped.incrementAndGet()
        android.util.Log.w("UltraAmbient", "$why: dropped a ${samples * 1000L / AmbientConfig.SAMPLE_RATE} ms " +
            "utterance untranscribed (window=$window, $n dropped since start)")
    }

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
        asrName = cfg.asrModel(modelDir)
        whisper = loadAsr(asrName)
        if (whisper == 0L && asrName != AmbientConfig.ASR_MODEL) { asrName = AmbientConfig.ASR_MODEL; whisper = loadAsr(asrName) }
        if (whisper == 0L) return false
        android.util.Log.i("UltraAmbient", "speech model: $asrName")
        asrModelName = asrName
        // The larger model downloads in the background; the transcriber switches to it once it's here.
        if (cfg.accurateSpeech && !cfg.smallTooSlow && !AmbientConfig.smallReady(modelDir)) scope.launch(Dispatchers.IO) {
            if (AmbientConfig.fetchSmall(modelDir)) { wantAsr = AmbientConfig.ASR_SMALL_MODEL; android.util.Log.i("UltraAmbient", "small speech model downloaded") }
        }
        currentCoroutineContext().ensureActive()
        gate = WakeGate(this@AmbientListenService,
            threshold = 0.5f).takeIf { it.ready }   // null => fall back to text-match wake
        gateLoaded = gate != null
        return true
    }

    /** The speech model loaded ([AmbientConfig.asrModel]), and the one the transcriber should switch
     *  to before its next utterance (null = stay). Transcriber thread only, apart from [wantAsr]. */
    private var asrName = AmbientConfig.ASR_MODEL
    @Volatile private var wantAsr: String? = null
    private val asrSpeed = AsrSpeed()

    private fun loadAsr(name: String): Long = WhisperNative.nativeInit(
        java.io.File(filesDir, name).absolutePath,
        java.io.File(filesDir, AmbientConfig.VAD_MODEL).absolutePath.let { if (java.io.File(it).exists()) it else "" },
    )

    /** The accurate model has just been downloaded (from Settings): switch to it before the next
     *  utterance, if it's wanted. */
    fun useAccurateModel() {
        if (cfg.accurateSpeech && !cfg.smallTooSlow && AmbientConfig.smallReady(filesDir)) wantAsr = AmbientConfig.ASR_SMALL_MODEL
    }

    /** On the transcriber thread, between utterances: load [wantAsr] in place of the current model. */
    private fun switchAsrIfWanted() {
        val want = wantAsr ?: return
        wantAsr = null
        if (want == asrName) return
        val h = loadAsr(want)
        if (h == 0L) { android.util.Log.w("UltraAmbient", "couldn't load speech model $want; keeping $asrName"); return }
        val old = whisper
        whisper = h; asrName = want; asrModelName = want
        if (old != 0L) WhisperNative.nativeFree(old)
        android.util.Log.i("UltraAmbient", "speech model now: $want")
    }

    /** Reads the mic until cancelled. A dead or failing AudioRecord (audioserver
     * restart) is rebuilt a few times; it never spins on an error code. */
    private suspend fun capture(out: Channel<Utterance>) {
        lateinit var seg: Segmenter
        seg = Segmenter { pcm, _ ->
            val now = System.currentTimeMillis()
            // A command counts if it STARTED in the window: in a noisy room it can end after the
            // window closes (Real-use check 2.6.28: woke, then the command went to memory).
            val open = gate != null && (now < cmdWindowUntil || segStartWindow != 0L)
            val wid = if (now < cmdWindowUntil) windowId else segStartWindow
            segStartWindow = 0L
            lastSpeechAt = now
            out.trySend(Utterance(pcm, now, if (open) wid else 0L, seg.noiseFloorDb))
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
        // The phone's own automatic gain control where it has one: far speech comes up before the gate.
        val agcEffect = try {
            if (android.media.audiofx.AutomaticGainControl.isAvailable())
                android.media.audiofx.AutomaticGainControl.create(rec.audioSessionId)?.also { it.enabled = true }
            else null
        } catch (_: Exception) { null }
        // And its echo canceller: Jarvis's own voice is taken out of the mic, so "Hey Jarvis" said
        // over it (barge-in, below) is heard and its speech doesn't wake it.
        val aecEffect = try {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable())
                android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
            else null
        } catch (_: Exception) { null }
        val gateAgc = MicGain.Agc()

        val frame = ShortArray(AmbientConfig.FRAME)
        var lastPrune = 0L
        try {
            rec.startRecording()
            capturing = true
            while (currentCoroutineContext().isActive) {
                val n = rec.read(frame, 0, frame.size)
                if (n < 0) {                        // ERROR_DEAD_OBJECT etc. never recover in place
                    android.util.Log.w("UltraAmbient", "AudioRecord.read error $n")
                    return false
                }
                if (n == 0) continue
                tap?.offer(frame, n)
                val fr = if (n == frame.size) frame else frame.copyOf(n)
                if (System.currentTimeMillis() < mutedUntil || com.agent.ultra.ui.Speaker.mutesListener()) {
                    seg.reset()
                    // Barge-in (owner: "stop doing something when I tell it to"): while Jarvis talks,
                    // only the "Hey Jarvis" sound model listens. It firing cuts the speech off and
                    // opens a command window, so "Hey Jarvis, stop" works mid-sentence. Only while
                    // Jarvis is really speaking: the voice-print recorder mutes the mic too.
                    val g = gate
                    if (g != null && com.agent.ultra.ui.SpeechQueue.anyBusy && com.agent.ultra.ui.VoicePrefs.bargeIn(this)) {
                        if (g.accept(gateAgc.process(fr, CueAudio.gateDb(seg.noiseFloorDb)))) bargeIn()
                    } else g?.reset()
                    continue
                }
                // Acoustic gate: when present, it decides when we're addressed.
                // whisper then runs only for the command window + throttled memory.
                if (gate?.accept(gateAgc.process(fr, CueAudio.gateDb(seg.noiseFloorDb))) == true) {
                    // Heard in the middle of other sound: what came before is memory, not the command.
                    // A short segment is the wake phrase itself and stays (a bare "Hey Jarvis" gets "Yes?").
                    if (seg.openMs > WAKE_CUT_MS) seg.cut()
                    synchronized(windowLock) {
                        windowId++
                        cmdWindowUntil = System.currentTimeMillis() + AmbientConfig.FOLLOWUP_MS
                    }
                    woke("sound")
                    Librarian.yieldNow()   // the answer needs the brain: the memory model stops now
                    chime()
                }
                // A command window keeps segments short; otherwise the room-length cap.
                seg.maxMs = if (System.currentTimeMillis() < cmdWindowUntil) AmbientConfig.COMMAND_MAX_MS else AmbientConfig.MAX_UTTER_MS
                val wasSpeaking = seg.speaking
                seg.push(fr)
                if (seg.speaking && !wasSpeaking)
                    segStartWindow = if (gate != null && System.currentTimeMillis() < cmdWindowUntil) windowId else 0L

                val now = System.currentTimeMillis()
                if (now - lastPrune > 3_600_000L) {
                    lastPrune = now
                    mem.prune(cfg.retentionDays, cfg.maxRows)
                    MemoryIndexer.kick(this@AmbientListenService)   // background; skips while the brain is busy
                }
            }
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("UltraAmbient", "capture failed: ${e.message}")
            return false
        } finally {
            capturing = false
            try { agcEffect?.release() } catch (_: Exception) {}
            try { aecEffect?.release() } catch (_: Exception) {}
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    /** [t] is when the utterance ended and [wid] the wake window open then (0 = none): the
     * command window is judged when the speech ended, not when the queued transcription runs.
     * A window is spent by one command, even if more of its speech is still queued. */
    private fun onUtterance(pcm: ShortArray, t: Long, wid: Long, floorDb: Float) {
        // Silence since the previous utterance's speech (every utterance counts, stored or not).
        val db = CueAudio.frameDb(pcm)
        val audioMs = pcm.size * 1000L / AmbientConfig.SAMPLE_RATE
        val gapBefore = turnGaps.next(db, floorDb, t, audioMs)
        val windowOpen = gate != null && wid != 0L && wid != consumedWindowId
        val level = com.agent.ultra.agent.ThermalGuard.level
        val plan = AmbientThrottle.decide(level.ambient, windowOpen, gate != null)
        if (!plan.transcribe) {                            // CRITICAL: memory-only capture paused
            logDropped("thermal $level", pcm.size, false)
            return
        }
        // The fast cores whenever the thinker isn't answering: the memory model is background work and
        // stops for speech waiting to be heard (Pixel log 2026-10-07: whisper sat on the 2 slow cores
        // at 2.5-5x real time while the librarian sorted 263 lines, and 129 utterances were dropped).
        if (com.agent.ultra.local.LocalModelEngine.librarian(applicationContext).busy.value) Librarian.yieldNow()
        val (threads, cpus) = AmbientThrottle.placement(
            plan, com.agent.ultra.local.LocalModelEngine.shared(applicationContext).busy.value,
            level == com.agent.ultra.agent.ThermalPolicy.Level.NORMAL, llmCpus, whisperCpus)
        switchAsrIfWanted()
        val t0 = System.currentTimeMillis()
        val r = WhisperNative.transcribe(
            whisper, MicGain.normalize(pcm), threads, WhisperNative.verbatimPrompt(cfg.wakeName), cpus, timing = true,
        )
        // Whether whisper keeps up with speech: ms > audioMs means slower than real time.
        val took = System.currentTimeMillis() - t0
        android.util.Log.d("UltraAmbient", "whisper model=$asrName audioMs=$audioMs ms=$took " +
            "threads=$threads cpus=${cpus.toList()}")
        // small timed on the fast cores (brain idle): if it can't keep up with speech, base takes over.
        if (asrName == AmbientConfig.ASR_SMALL_MODEL && !com.agent.ultra.local.LocalModelEngine.anyBusy.value) {
            asrSpeed.record(audioMs, took)
            if (asrSpeed.tooSlow()) {
                android.util.Log.w("UltraAmbient", "small speech model slower than speech here: back to base")
                cfg.smallTooSlow = true
                wantAsr = AmbientConfig.ASR_MODEL
            }
        }
        if (r == null) return
        lastTranscribedAt = System.currentTimeMillis()
        lastHeardText = r.text.take(120)
        // While Jarvis is working or talking, "stop" on its own needs no "Hey Jarvis" (one word, so
        // before the two-word filter below).
        if ((com.agent.ultra.agent.Brain.anyRunning || com.agent.ultra.ui.SpeechQueue.anyBusy) && com.agent.ultra.ui.VoicePrefs.bareStop(this) &&
            r.confidence >= AmbientConfig.MIN_TOKEN_P && com.agent.ultra.agent.Interrupt.isStop(r.text)) {
            stopEverything()
            return
        }
        if (!kept(r)) return

        val now = System.currentTimeMillis()
        val rowid = mem.record(r.text, now, r.confidence)          // EVERYTHING kept as memory
        if (rowid > 0) cues?.offer(
            CueJob(rowid, now, r.text, db, audioMs, pcm, floorDb, r.words, r.vad, gapBefore, cfg.speakerTags))

        // Acoustic gate fired within the window -> this utterance is the command,
        // no need for the spoken name in the text. Falls through to text-match
        // when no gate is loaded. The utterance the gate fired in usually holds the
        // wake phrase itself: strip it, and if nothing is left ("Hey Jarvis." then a
        // pause), keep the window open for the next utterance instead of spending it.
        if (windowOpen) {
            val command = WakeFilter.stripWakePhrase(r.text, wakeWords())
            if (command.length < 2) {
                // "Hey Jarvis." and a pause: answer, then keep listening for the request (the
                // window reopens after the reply, while the mic was muted for it).
                if (windowId == wid) cmdWindowUntil = now + AmbientConfig.FOLLOWUP_MS
                say(com.agent.ultra.ui.VoicePrefs.wakeReply(this)) { if (windowId == wid) cmdWindowUntil = System.currentTimeMillis() + AmbientConfig.FOLLOWUP_MS }
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
                // Woken by the words (the acoustic gate missed, or there is none): chime now. Not
                // for the follow-up utterance after a bare name, which has no wake phrase of its own.
                if (WakeFilter.addressedCommand(r.text, cfg.wakeName) != null) { woke("words"); Librarian.yieldNow(); chime() }
                if (v.command.isBlank()) {               // bare name; follow-up will carry the command
                    say(com.agent.ultra.ui.VoicePrefs.wakeReply(this)) { wake.holdFollowUp(System.currentTimeMillis() + AmbientConfig.FOLLOWUP_MS) }
                    return
                }
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

    /** Commands heard while the brain was busy (transcribed past the hold cap), in order, numbered:
     *  a "stop" drops every one numbered up to [clearedUpTo]. */
    private val pendingCommands = Channel<Pair<Long, String>>(Channel.UNLIMITED)
    private var commandRunner: Job? = null
    private val commandSeq = java.util.concurrent.atomic.AtomicLong(0)
    private val clearedUpTo = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Runs [request] once the on-device brain is idle. Past the hold cap whisper transcribes
     * while the brain is still answering; a command it hears then waits here, so it runs after
     * the current answer (as when transcription itself waited) instead of beside it.
     */
    @Synchronized private fun dispatch(heardRequest: String) {
        // The memory model stops for a command; only an answer already being given makes it wait.
        Librarian.yieldNow()
        // Mid-run, what is said changes the run (owner, 2026-10-07): "stop" stops everything,
        // "also …" runs after it, anything else stops it and runs instead (Interrupt).
        val request = when (val d = com.agent.ultra.agent.Interrupt.decide(heardRequest, com.agent.ultra.agent.Brain.runningRequest)) {
            com.agent.ultra.agent.Interrupt.Decision.Stop -> { stopEverything(); return }
            is com.agent.ultra.agent.Interrupt.Decision.Run -> d.request
            is com.agent.ultra.agent.Interrupt.Decision.After -> {
                android.util.Log.i("UltraAmbient", "add-on heard mid-run: runs after it")
                answeredAt = System.currentTimeMillis()
                say("Okay, after this.")
                val seq = commandSeq.incrementAndGet()
                pendingCommands.trySend(seq to d.request)
                ensureCommandRunner()
                lateCue(seq)
                return
            }
            is com.agent.ultra.agent.Interrupt.Decision.Replace -> {
                android.util.Log.i("UltraAmbient", "change heard mid-run: the run stops, the new request runs")
                clearedUpTo.set(commandSeq.get())
                com.agent.ultra.agent.Brain.stopAll()
                com.agent.ultra.ui.SpeechQueue.stopAll()
                mutedUntil = 0L
                d.request
            }
        }
        ensureCommandRunner()
        // "On it." from when the command was heard, not from when it got its turn: a wait counts.
        val heard = System.currentTimeMillis()
        scope.launch {
            kotlinx.coroutines.delay(WaitCue.ON_IT_AFTER_MS)
            if (answeredAt < heard && com.agent.ultra.ui.VoicePrefs.sayOnIt(this@AmbientListenService)) say(WORKING_REPLY)
        }
        val seq = commandSeq.incrementAndGet()
        pendingCommands.trySend(seq to request)
        lateCue(seq)
    }

    /** The command the runner last started, and when (the late cue's "is it running yet"). */
    @Volatile private var startedSeq = 0L
    @Volatile private var startedAt = 0L

    /**
     * Once, [WaitCue.LATE_AFTER_MS] after command [seq] was heard: "Still working on it." if it is
     * running with nothing said yet, or "I'm still busy with the last thing…" if it is still
     * queued behind a run. Speaks only: the run and the queue are left as they are.
     */
    private fun lateCue(seq: Long) {
        val heard = System.currentTimeMillis()
        scope.launch {
            kotlinx.coroutines.delay(WaitCue.LATE_AFTER_MS)
            val now = System.currentTimeMillis()
            // Worked out as if "On it." were on: a quiet command is logged either way.
            val quiet = WaitCue.late(
                seq, clearedUpTo.get(), startedSeq, startedAt, answeredAt, lastCommandDoneAt,
                hushed = com.agent.ultra.agent.Brain.runningHushed,
                talking = WaitCue.talking(com.agent.ultra.ui.SpeechQueue.anyBusy, answeredAt, now),
                onItEnabled = true,
            )
            // Quiet for 20 s: one line in the exportable log saying where (QuietStage). Only this
            // service's own brain: the voice screen answers its commands itself (no cue then).
            if (quiet != WaitCue.Cue.NONE) com.agent.ultra.gate.GateAuditLog.recordQuiet(
                if (quiet == WaitCue.Cue.STILL_BUSY) com.agent.ultra.agent.QuietStage.QUEUED
                else brainRef?.runStage ?: com.agent.ultra.agent.QuietStage.WORKING,
                now - heard,
                if (quiet == WaitCue.Cue.STILL_BUSY) com.agent.ultra.agent.QuietStage.NO_ROUTE
                else com.agent.ultra.gate.GateAuditLog.routeSince(startedAt),
            )
            // "On it." off: "Still working on it." stays unsaid (WaitCue.late); the busy line is said.
            val cue = if (quiet == WaitCue.Cue.STILL_WORKING &&
                !com.agent.ultra.ui.VoicePrefs.sayOnIt(this@AmbientListenService)) WaitCue.Cue.NONE else quiet
            WaitCue.text(cue)?.let {
                android.util.Log.i("UltraAmbient", "late cue for command $seq: $cue")
                say(it)
            }
        }
    }

    @Synchronized private fun ensureCommandRunner() {
        val busy = com.agent.ultra.local.LocalModelEngine.shared(applicationContext).busy
        if (commandRunner == null) commandRunner = scope.launch {
            for ((seq, r) in pendingCommands) {
                if (seq <= clearedUpTo.get()) continue          // stopped before its turn
                if (busy.value) android.util.Log.i("UltraAmbient", "command heard while the brain is busy: runs after it")
                busy.first { !it }
                // A stopped run lets go of the engine at its next token but may still be unwinding.
                while (com.agent.ultra.agent.Brain.anyRunning) delay(100)
                if (seq <= clearedUpTo.get()) continue
                startedAt = System.currentTimeMillis(); startedSeq = seq
                dispatchNow(r)
            }
        }
    }

    /** The voice screen's brain answers when it is open; otherwise a brain owned by
     * this service does, so a command is never dropped because no Activity is alive. */
    private fun dispatchNow(request: String) {
        lastCommand = request; lastCommandAt = System.currentTimeMillis()
        AmbientBridge.onCommand?.let {
            answeredAt = System.currentTimeMillis()   // the voice screen answers it: no "On it." from here
            it(request); lastCommandDoneAt = System.currentTimeMillis(); return
        }
        // Main, as VoiceActivity's lifecycleScope runs it.
        scope.launch(Dispatchers.Main) {
            // Anyone can talk to a locked phone: only harmless requests run then (LockedPolicy),
            // and without the overheard transcript, which is private. A service can't ask for the
            // unlock itself, so for anything else it says so and stops.
            val km = getSystemService(android.app.KeyguardManager::class.java)
            var run = request
            if (km != null && km.isKeyguardLocked) {
                if (!com.agent.ultra.agent.LockedPolicy.allowed(request)) {
                    answeredAt = System.currentTimeMillis()
                    say(com.agent.ultra.agent.LockedPolicy.REFUSAL)
                    lastCommandDoneAt = System.currentTimeMillis()
                    return@launch
                }
                run = com.agent.ultra.agent.IntentRouter.requestPart(request)
            }
            try { fallbackBrain().run(run) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                // Said out loud, as the voice screen does: the owner heard "On it." and must not
                // be left with silence. Stop (a CancellationException) is rethrown above, unspoken.
                android.util.Log.w("UltraAmbient", "command failed: ${e.message}")
                answeredAt = System.currentTimeMillis()
                // Locked: no detail, it can carry private content (LockedPolicy strips it too).
                say(CommandFailure.reply(e, withDetail = km?.isKeyguardLocked != true))
            }
            finally { lastCommandDoneAt = System.currentTimeMillis() }
        }
    }

    private var brainRef: com.agent.ultra.agent.Brain? = null
    private var speakerRef: com.agent.ultra.ui.Speaker? = null
    /** When the service's brain last spoke an answer (the "On it." check). */
    @Volatile private var answeredAt = 0L

    /** Everything this service says, in order: replies, then answers sentence by sentence. The mic
     *  is muted for each piece (echo guard), and unmuted shortly after the last. */
    @Synchronized private fun speech(): com.agent.ultra.ui.SpeechQueue = speechRef ?: run {
        val speaker = speakerRef ?: com.agent.ultra.ui.Speaker(this).also { speakerRef = it }
        speaker.queue().also { q ->
            q.onStart = { text -> mutedUntil = System.currentTimeMillis() + EchoGuard.muteMsFor(text) }
            q.onIdle = { mutedUntil = System.currentTimeMillis() + AmbientConfig.ECHO_GUARD_MS }
            speechRef = q
        }
    }
    private var speechRef: com.agent.ultra.ui.SpeechQueue? = null

    /** Speaks [msg] in Jarvis's voice with the mic muted for it (echo guard), then [after]. */
    private fun say(msg: String, after: () -> Unit = {}) {
        mutedUntil = System.currentTimeMillis() + EchoGuard.muteMsFor(msg)
        speech().add(msg, after)
    }

    /** The wake beep, unless switched off (Settings → HOW JARVIS TALKS). */
    private fun chime() { if (com.agent.ultra.ui.VoicePrefs.wakeChime(this)) WakeChime.play() }

    /** "Hey Jarvis" heard over Jarvis's own speech: silence it, chime, and take the next words as a
     *  command (stop, change, or a new request: [dispatch]). */
    private fun bargeIn() {
        com.agent.ultra.agent.Brain.hushAll()
        com.agent.ultra.ui.SpeechQueue.stopAll()
        mutedUntil = 0L                      // after stopAll: its idle callback re-mutes briefly
        com.agent.ultra.ui.Speaker.clearEchoTail()
        synchronized(windowLock) {
            windowId++
            cmdWindowUntil = System.currentTimeMillis() + AmbientConfig.FOLLOWUP_MS
        }
        woke("barge-in")
        chime()
        android.util.Log.i("UltraAmbient", "barge-in: speech stopped by the wake word")
    }

    /** "Stop": every run, every queued command and all speech, then a short "Stopped." */
    private fun stopEverything() {
        clearedUpTo.set(commandSeq.get())
        val ran = com.agent.ultra.agent.Brain.stopAll()
        val spoke = com.agent.ultra.ui.SpeechQueue.stopAll()
        mutedUntil = 0L
        answeredAt = System.currentTimeMillis()
        lastCommandDoneAt = System.currentTimeMillis()
        android.util.Log.i("UltraAmbient", "STOP heard: run=$ran speech=$spoke")
        say(if (ran || spoke) "Stopped." else "Okay.")
    }

    @Synchronized private fun fallbackBrain(): com.agent.ultra.agent.Brain = brainRef ?: run {
        val speech = speech()
        com.agent.ultra.agent.Brain(
            applicationContext, com.agent.ultra.local.LocalModelEngine.shared(applicationContext)
        ).also { b ->
            // Spoken: no "Answer now" button, so keep thinking short, and say the answer as it is
            // written (sentence by sentence) instead of after the whole of it.
            b.maxThinkingDepth = com.agent.ultra.agent.WaitBudget.Depth.QUICK
            b.spokenAlways = true
            b.recordTurns = false   // what it is asked was heard, so it is in memory already
            b.onAnswer = { answeredAt = System.currentTimeMillis() }
            b.onSpeak = { text ->
                if (lastAnswerStartAt < lastCommandAt) lastAnswerStartAt = System.currentTimeMillis()
                answeredAt = System.currentTimeMillis()
                mutedUntil = System.currentTimeMillis() + EchoGuard.muteMsFor(text)
                speech.add(text)
            }
            brainRef = b
        }
    }

    /** Prepend recent verbatim transcript so the agent answers with what it overheard. */
    private fun buildContextualRequest(command: String, now: Long): String {
        // Only when the words need it: the transcript was in every spoken command, and reading
        // ~400 extra tokens before thinking is seconds of silence on the phone (owner: "answer
        // me right away").
        if (!TranscriptNeed.needed(command)) return command
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
        speechRef?.let { com.agent.ultra.ui.SpeechQueue.unregister(it) }
        try { speakerRef?.shutdown() } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        instance = null
        super.onDestroy()
    }

    companion object {
        /** What Jarvis says to a bare "Hey Jarvis" by default (Settings → HOW JARVIS TALKS changes
         *  it), and when an answer is still being thought out. */
        const val WAKE_REPLY = "Yes?"
        /** An open segment longer than this when the wake model fires holds other sound: cut it first. */
        private const val WAKE_CUT_MS = 2_500
        const val WORKING_REPLY = "On it."
        private const val CHANNEL = "ambient_listen"
        private const val NOTIF_ID = 7101
        private const val MAX_MIC_FAILURES = 3
        @Volatile var instance: AmbientListenService? = null

        // For the Feature check's "Ambient listening" line (this process only; 0 = not yet):
        // when speech last ended, when whisper last ran on it, and how many utterances were
        // dropped without being transcribed. "last heard" there is the newest STORED line, so
        // these tell "nothing said" from "said but held, dropped or filtered".
        @Volatile var lastSpeechAt = 0L
            private set
        @Volatile var lastTranscribedAt = 0L
            private set
        val dropped = java.util.concurrent.atomic.AtomicInteger(0)

        // For the Feature check's live wake test (this process only; 0 = not yet): when Jarvis
        // last woke and how ("sound" = the acoustic gate, "words" = the transcript), and the
        // last command it passed to the brain, with when that run finished.
        @Volatile var lastWakeAt = 0L
            private set
        @Volatile var lastWakeBy = ""
            private set
        @Volatile var lastCommandAt = 0L
            private set
        @Volatile var lastCommand = ""
            private set
        @Volatile var lastCommandDoneAt = 0L
            private set

        private fun woke(by: String) { lastWakeBy = by; lastWakeAt = System.currentTimeMillis() }

        /** When the first spoken words of an answer to [lastCommand] went out (the Real-use check). */
        @Volatile var lastAnswerStartAt = 0L
        /** The speech model the listener is running now (file name), "" before it starts. */
        @Volatile var asrModelName = ""
            private set

        /** The "Hey Jarvis" sound model loaded (else only the transcript's words wake it). */
        @Volatile var gateLoaded = false
            private set
        /** The last line whisper wrote, for Settings → VOICE's live status. */
        @Volatile var lastHeardText = ""
            private set

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

        /** From a screen the user opened: listening they left on but that isn't running (Android
         *  15+ won't start it at boot; an update stops it; the mic permission came later). */
        fun resumeIfOff(ctx: Context) {
            if (AmbientConfig(ctx).enabled && instance == null && start(ctx)) ResumeNotice.clear(ctx)
        }

        /** whisper emits stock phrases on silence/music; drop the common ones. */
        private val NOISE = setOf(
            "you", "thank you", "thanks for watching", "thank you.", "[blank_audio]",
            "[ silence ]", "(silence)", ".", "bye.", "so", "okay", "uh"
        )
        fun isWhisperNoise(t: String) = t.trim().lowercase().trim('.', ' ') in NOISE

        /** The text filters a heard line must pass to be stored or acted on (also used by the
         *  debug feature check, so it applies the same ones). */
        fun kept(r: WhisperNative.Result): Boolean {
            // drop low-confidence (hallucinated) and trivially short lines
            if (r.confidence < AmbientConfig.MIN_TOKEN_P) return false
            val words = r.text.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size < AmbientConfig.MIN_WORDS) return false
            if (isWhisperNoise(r.text)) return false
            // "Um, uh." alone (or the filler prompt echoed back on noise): nothing to keep or act on
            return !WhisperNative.onlyPromptFillers(r.text)
        }
    }
}

/** Decouples the service from the agent. VoiceActivity/Brain set onCommand. */
object AmbientBridge {
    @Volatile var onCommand: ((String) -> Unit)? = null
}

/** What the ambient service says when a command's run throws. Pure, JVM-tested. */
object CommandFailure {
    private const val MAX_DETAIL = 120
    const val GENERIC = "Something went wrong."

    /** "Something went wrong: …" (as VoiceActivity says it), the detail trimmed for speech;
     *  without [withDetail] (a locked phone) just "Something went wrong." */
    fun reply(e: Throwable, withDetail: Boolean = true): String {
        if (!withDetail) return GENERIC
        val detail = e.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            .ifBlank { e.javaClass.simpleName }
            .let { if (it.length > MAX_DETAIL) it.take(MAX_DETAIL).trimEnd() + "…" else it }
        return "Something went wrong: $detail"
    }
}
