package com.agent.ultra.local

import android.content.Context
import com.agent.ultra.listen.AmbientConfig
import com.agent.ultra.listen.LocalVoice
import com.agent.ultra.listen.LocalWhisper
import com.agent.ultra.listen.MemoryIndexer
import com.agent.ultra.listen.ModelFetcher
import com.agent.ultra.listen.SentenceEmbedder
import com.agent.ultra.listen.VoicePrints
import com.agent.ultra.listen.WakeGate
import com.agent.ultra.local.DownloadPlan.ItemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Every downloadable model on this phone ([assets]) and "Download all missing"
 * ([downloadAllMissing]). Each item is fetched by the code that already fetches it elsewhere —
 * [LocalModelEngine.downloadModel] for the two LLMs, [ModelFetcher.ensure] with the ambient
 * service's arguments for whisper / VAD / wake word, [LocalVoice.fetch], [SentenceEmbedder.fetch]
 * and [VoicePrints.fetch], and [ModelFetcher.ensure] at its exact size for the speed boost's
 * draft model ([DraftModel]) — so resume, exact-size and checksum rules stay theirs. What this adds
 * is that the user can ask: before it, the voice downloaded only when Jarvis was about to speak
 * an answer (spoken replies are off by default), and the embedder only from the memory indexer
 * (after a heard line, on Wi-Fi, past its retry backoff). An explicit download skips those gates.
 *
 * The run lives in this object's scope, not a screen's, so it carries on when Settings or the
 * chat is left; one item at a time, under a CPU wake lock (the LLM download keeps its own too).
 */
object Downloads {
    const val THINKER = "thinker"
    const val LIBRARIAN = "librarian"
    const val WHISPER = "whisper"
    const val VAD = "vad"
    const val WAKE = "wake"
    const val VOICE = "voice"
    const val EMBEDDER = "embedder"
    const val SPEAKER = "speaker"
    const val DRAFT = "draft"

    // Download sizes for the files with no exact size in code: measured from the published files
    // by an earlier session (whisper 59.7 MB, VAD 0.9, wake 1.1 + 1.3 + 1.3, voice 21.0). Shown
    // only; the fetchers check the files themselves.
    private const val WHISPER_BYTES = 59_700_000L
    private const val VAD_BYTES = 900_000L
    private const val MEL_BYTES = 1_100_000L
    private const val EMB_BYTES = 1_300_000L
    private const val JARVIS_BYTES = 1_300_000L
    private const val VOICE_BYTES = 21_000_000L

    // The ambient service's own minimum sizes (AmbientListenService.initModels).
    private const val ASR_MIN = 10_000_000L
    private const val VAD_MIN = 100_000L
    private const val WAKE_MIN = 500_000L

    data class RunState(val running: Boolean = false, val items: Map<String, ItemState> = emptyMap())

    private val stateFlow = MutableStateFlow(RunState())
    /** The run in progress or last finished, per item. */
    val state: StateFlow<RunState> get() = stateFlow

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Wake-word files the ambient service fetches: the two shared models, plus the stock
     *  "hey jarvis" classifier when that is the one in use (a custom one can't be downloaded). */
    private fun wakeFiles(ctx: Context): List<Triple<String, String, Long>> = buildList {
        add(Triple(WakeGate.MEL, WakeGate.MEL_URL, MEL_BYTES))
        add(Triple(WakeGate.EMB, WakeGate.EMB_URL, EMB_BYTES))
        if (WakeGate.cfgName(ctx) == WakeGate.DEFAULT_CLASSIFIER)
            add(Triple(WakeGate.DEFAULT_CLASSIFIER, WakeGate.JARVIS_URL, JARVIS_BYTES))
    }

    private fun len(f: File) = if (f.exists()) f.length() else 0L
    /** filesDir/models, where both LLMs and the speed boost's draft live. */
    private fun models(ctx: Context) = File(ctx.filesDir, "models")
    /** Bytes in ModelFetcher's unfinished-download file for [name]. */
    private fun part(dir: File, name: String) = len(File(dir, "$name.part"))

    private fun modelBytes(engine: LocalModelEngine): Long =
        LocalModelEngine.PRESETS.firstOrNull { it.fileName == engine.modelFileName }
            ?.approxMb?.toLong()?.times(1_048_576L) ?: 0L

    private fun shortLabel(engine: LocalModelEngine) = engine.modelLabel.substringBefore(" — ")

    /** Every downloadable asset and whether it is on the phone now. Cheap file checks only. */
    fun assets(context: Context): List<DownloadPlan.Asset> {
        val ctx = context.applicationContext
        val dir = ctx.filesDir
        val thinker = LocalModelEngine.shared(ctx)
        val librarian = LocalModelEngine.librarian(ctx)
        val wake = wakeFiles(ctx)
        return listOfNotNull(
            DownloadPlan.Asset(THINKER, "Brain: ${shortLabel(thinker)}", modelBytes(thinker),
                thinker.modelPresent, partBytes = thinker.partialBytes()),
            DownloadPlan.Asset(LIBRARIAN, "Memory model: ${shortLabel(librarian)}", modelBytes(librarian),
                librarian.modelPresent, partBytes = librarian.partialBytes()),
            // Only beside a brain it can speed up (the Qwen3 family: same tokenizer).
            if (!DraftModel.eligible(thinker.modelFileName)) null
            else DownloadPlan.Asset(DRAFT, "Speed boost for the brain (draft model)", DraftModel.BYTES,
                DraftModel.present(ctx), partBytes = part(models(ctx), DraftModel.FILE)),
            DownloadPlan.Asset(WHISPER, "Speech recognition (whisper)", WHISPER_BYTES,
                LocalWhisper.modelPresent(ctx), partBytes = part(dir, AmbientConfig.ASR_MODEL)),
            DownloadPlan.Asset(VAD, "Speech detector (VAD)", VAD_BYTES,
                len(File(dir, AmbientConfig.VAD_MODEL)) >= VAD_MIN, partBytes = part(dir, AmbientConfig.VAD_MODEL)),
            DownloadPlan.Asset(WAKE, "Wake word \"Hey Jarvis\"", wake.sumOf { it.third },
                wake.all { len(File(dir, it.first)) >= WAKE_MIN }, partBytes = wake.sumOf { part(dir, it.first) }),
            // The voice chosen in Settings → JARVIS'S VOICE (none to fetch for the phone's voice).
            LocalVoice.chosenModel(ctx).let { m -> DownloadPlan.Asset(VOICE, "Jarvis's voice${m?.let { " (${it.label})" } ?: ""}",
                m?.bytes ?: VOICE_BYTES, m == null || LocalVoice.voicePresent(ctx, m),
                partBytes = part(dir, "${m?.id ?: LocalVoice.VOICE}.tar.bz2")) },
            DownloadPlan.Asset(EMBEDDER, "Memory search (meaning)",
                SentenceEmbedder.MODEL_BYTES + SentenceEmbedder.VOCAB_BYTES, SentenceEmbedder.downloaded(ctx),
                partBytes = part(dir, SentenceEmbedder.MODEL) + part(dir, SentenceEmbedder.VOCAB)),
            DownloadPlan.Asset(SPEAKER, "Speaker voiceprints (used only if speaker tags are on)",
                VoicePrints.MODEL_BYTES, VoicePrints.downloaded(ctx), essential = false,
                partBytes = part(dir, VoicePrints.MODEL)),
        )
    }

    /** On a metered network (mobile data) now: the screens ask before starting a multi-GB run there. */
    fun onMobileData(context: Context): Boolean = try {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.activeNetwork != null && cm.isActiveNetworkMetered
    } catch (_: Exception) { false }

    /** Start downloading everything missing, smallest first. False when a run is already going
     *  or nothing is missing. Returns at once. */
    @Synchronized
    fun downloadAllMissing(context: Context): Boolean {
        val app = context.applicationContext
        if (job?.isActive == true) return false
        val order = DownloadPlan.order(assets(app)).map { it.id }
        if (order.isEmpty()) return false
        stateFlow.value = RunState(true, order.associateWith { ItemState.Queued })
        job = scope.launch {
            val wake = (app.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:downloadAll")
            try {
                wake?.acquire(6 * 60 * 60 * 1000L)
                DownloadPlan.runSequential(
                    order,
                    fetch = { id ->
                        coroutineScope {
                            val poll = launch {
                                while (true) { set(id, ItemState.Running(progress(app, id))); delay(1_000) }
                            }
                            try { fetch(app, id) } finally { poll.cancel() }
                        }
                    },
                    onDone = { o ->
                        android.util.Log.i(TAG, "download ${o.id}: ${if (o.ok) "ok" else "failed: ${o.error}"}")
                        set(o.id, if (o.ok) ItemState.Done else ItemState.Failed(o.error ?: "failed"))
                    },
                )
            } finally {
                runCatching { if (wake?.isHeld == true) wake.release() }
                synchronized(stateFlow) { stateFlow.value = stateFlow.value.copy(running = false) }
            }
        }
        return true
    }

    private fun set(id: String, s: ItemState) = synchronized(stateFlow) {
        stateFlow.value = stateFlow.value.let { it.copy(items = it.items + (id to s)) }
    }

    /** How far [id]'s download has got, from the engine's own progress or the .part on disk. */
    private fun progress(ctx: Context, id: String): Float? {
        val dir = ctx.filesDir
        return when (id) {
            THINKER -> LocalModelEngine.shared(ctx).downloadProgress.value
            LIBRARIAN -> LocalModelEngine.librarian(ctx).downloadProgress.value
            WHISPER -> DownloadPlan.fraction(part(dir, AmbientConfig.ASR_MODEL), WHISPER_BYTES)
            VAD -> DownloadPlan.fraction(part(dir, AmbientConfig.VAD_MODEL), VAD_BYTES)
            WAKE -> wakeFiles(ctx).let { w -> DownloadPlan.fraction(w.sumOf { part(dir, it.first) }, w.sumOf { it.third }) }
            VOICE -> LocalVoice.chosenModel(ctx).let { m ->
                DownloadPlan.fraction(part(dir, "${m?.id ?: LocalVoice.VOICE}.tar.bz2"), m?.bytes ?: VOICE_BYTES) }
            EMBEDDER -> DownloadPlan.fraction(part(dir, SentenceEmbedder.MODEL) + part(dir, SentenceEmbedder.VOCAB),
                SentenceEmbedder.MODEL_BYTES + SentenceEmbedder.VOCAB_BYTES)
            SPEAKER -> DownloadPlan.fraction(part(dir, VoicePrints.MODEL), VoicePrints.MODEL_BYTES)
            DRAFT -> DownloadPlan.fraction(part(models(ctx), DraftModel.FILE), DraftModel.BYTES)
            else -> null
        }
    }

    private const val RETRY = "didn't download — check the internet connection, then tap Download all missing again"

    /** Fetch one item with its existing download code. Null = on the phone now; else the error. */
    private suspend fun fetch(ctx: Context, id: String): String? {
        val dir = ctx.filesDir
        return when (id) {
            THINKER -> fetchModel(LocalModelEngine.shared(ctx))
            LIBRARIAN -> fetchModel(LocalModelEngine.librarian(ctx))
            WHISPER -> if (ModelFetcher.ensure(dir, AmbientConfig.ASR_MODEL, AmbientConfig.ASR_URL, ASR_MIN)) null else RETRY
            VAD -> if (ModelFetcher.ensure(dir, AmbientConfig.VAD_MODEL, AmbientConfig.VAD_URL, VAD_MIN)) null else RETRY
            WAKE -> {
                // Every file is tried, so one failure doesn't leave the others undownloaded.
                val failed = wakeFiles(ctx).filterNot { (name, url, _) -> ModelFetcher.ensure(dir, name, url, WAKE_MIN) }
                if (failed.isEmpty()) null else "${failed.joinToString { it.first }} $RETRY"
            }
            VOICE -> if (LocalVoice.fetch(ctx)) null else RETRY
            EMBEDDER -> if (SentenceEmbedder.fetch(ctx)) { MemoryIndexer.kick(ctx); null } else RETRY
            SPEAKER -> if (VoicePrints.fetch(ctx)) null else RETRY
            // Exact size: resumable, and a wrong-size file is never used (DraftModel.present).
            DRAFT -> models(ctx).let { m ->
                m.mkdirs()
                if (ModelFetcher.ensure(m, DraftModel.FILE, DraftModel.URL, exactBytes = DraftModel.BYTES)) null else RETRY
            }
            else -> "unknown item"
        }
    }

    /** [LocalModelEngine.downloadModel], after any download of it already running (Settings' model
     *  section) has finished. Resumes an unfinished one. */
    private suspend fun fetchModel(engine: LocalModelEngine): String? {
        var r: Result<Long> = Result.failure(IllegalStateException("a download is already running"))
        // downloadModel refuses (IllegalStateException) while another download of it runs; its
        // progress shows only once that one has started writing, so wait and ask again.
        for (attempt in 1..BUSY_RETRIES) {
            engine.downloadProgress.first { it == null }
            if (engine.modelPresent) return null
            r = engine.downloadModel { }
            if (r.exceptionOrNull() !is IllegalStateException) break
            delay(2_000)
        }
        return when {
            engine.modelPresent -> null
            r.isFailure -> r.exceptionOrNull()?.message ?: "failed"
            else -> "download didn't complete"
        }
    }

    private const val BUSY_RETRIES = 30

    private const val TAG = "UltraDownloads"
}
