package com.agent.jarvis.local

import android.content.Context
import com.agent.jarvis.agent.ThermalGuard
import com.agent.jarvis.agent.ThermalPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The on-device model engine. Owns model lifecycle (file presence, memory
 * headroom check, load) and exposes one suspend generate(). Single-flight:
 * the agent loop is sequential, so a mutex serialises generation, and freeing waits for it.
 *
 * Two per process ([Role]): the THINKER ([shared]) answers and reasons; the LIBRARIAN
 * ([librarian]) is a small model that sorts and searches memory ([complete]). Each has its own
 * prefs, model file and native handle; every native load/generate/free of both goes through one
 * process-wide [nativeMutex], because the shim keeps its callback state in process globals.
 */
class LocalModelEngine(private val context: Context, val role: Role = Role.THINKER) {

    /** Which job an engine does; [prefsName] is its own prefs file. THINKER's is the name every
     *  install already has, so a phone that chose a model keeps it. */
    enum class Role(val prefsName: String) {
        THINKER("ultra_local_model"),
        LIBRARIAN("ultra_local_model_librarian"),
    }

    /** What a load does about the other engine of the pair ([loadDecision]). */
    enum class LoadDecision { LOAD, LIBRARIAN_YIELDS, REFUSE }

    private val prefs = context.getSharedPreferences(role.prefsName, Context.MODE_PRIVATE)
    private val downloadInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Progress 0..1 of the download in flight, or null when none is running.
     * Lives on the engine, not the screen: a download outlives the Settings
     * screen that started it, and the screen has to find it again on return. */
    val downloadProgress = kotlinx.coroutines.flow.MutableStateFlow<Float?>(null)
    private var handle: Long = 0

    /** The model used while the user has not chosen one: for the thinker, [defaultFor] this
     *  phone's RAM and what is already on disk; for the librarian, [LIBRARIAN_DEFAULT].
     *  Resolved once per process and read by all three getters below, so label, URL and file
     *  always agree. */
    private val defaultChoice: ModelChoice by lazy {
        if (role == Role.LIBRARIAN) LIBRARIAN_DEFAULT else defaultFor(totalRamBytes(context), downloadedFileNames())
    }

    /** Where the weights come from. Any GGUF the llama.cpp build understands. */
    var modelUrl: String
        get() = prefs.getString(K_URL, null) ?: defaultChoice.url
        private set(v) { prefs.edit().putString(K_URL, v).apply() }

    /** Filename on disk. Distinct per model, so switching does not clobber
     * a model you already downloaded — swapping back is free. */
    var modelFileName: String
        get() = prefs.getString(K_FILE, null) ?: defaultChoice.fileName
        private set(v) { prefs.edit().putString(K_FILE, v).apply() }

    /** The stored label; without one, the label of the preset whose file is in use, so the
     *  label can't name a different model than the file that is loaded. */
    val modelLabel: String
        get() = prefs.getString(K_LABEL, null)
            ?: modelFileName.let { f -> PRESETS.firstOrNull { it.fileName == f }?.label ?: f }

    private val modelFile: File get() = File(context.filesDir, "models/" + modelFileName)

    val modelPresent: Boolean get() = modelFile.exists() && modelFile.length() > 100_000_000

    /**
     * Is this model quick enough to be worth using as the fast path?
     *
     * The on-device model has two jobs: answer offline, and answer simple
     * device commands faster than a round trip to the cloud. A 7B model does
     * the first job far better and the second job far worse — measured, a
     * flagship takes tens of seconds on a request the cloud answers in about a
     * second. Above this size the local route is reserved for being offline
     * and for an explicit /local.
     */
    val suitableForFastPath: Boolean
        // Thinking models are excluded: the fast path's JSON prompt has no think
        // budget, and a <think> trace in front of the tool call breaks the parse.
        get() = !isThinkingModel && modelFileSizeBytes in 1..FAST_PATH_MAX_BYTES
    val loaded: Boolean get() = handle != 0L

    private val totalRam: Long by lazy { totalRamBytes(context) }
    /** The ctx the loaded native context was created with (nativeContextSize); 0 while unloaded. */
    @Volatile private var loadedCtx = 0
    /** What the next load will use: for the thinker, [contextForRoom] the memory free beside the
     *  other engine ([roomBytes], default: read now) and the selected file. The librarian stays at
     *  [CTX_SIZE]: its prompts are short, and its KV must fit beside the thinker. */
    private fun plannedContext(roomBytes: Long = roomNow()): Int =
        if (role == Role.LIBRARIAN) CTX_SIZE
        else contextForRoom(roomBytes, totalRam, modelFileName, modelFileSizeBytes)

    /** availMem less the other engine's mapped weights ([otherMappedBytes]); <= 0 = unknown. */
    private fun roomNow(): Long {
        val other = otherEngine()
        val otherMapped = if (other == null) 0L else otherMappedBytes(
            other.loaded, other.residentLoaded, other.modelFilePath == modelFilePath, other.modelFileSizeBytes)
        return roomBesideOther(availableRam(), otherMapped)
    }

    /** Threads this engine generates with at full speed: [baseThreads] for the thinker, at most
     *  [LIBRARIAN_THREADS] for the librarian (on the fastest LLM cores: the two never generate at
     *  once, so it takes no cores from the thinker; fewer threads leave the rest of the phone room). */
    private val engineThreads: Int
        get() = if (role == Role.LIBRARIAN) minOf(LIBRARIAN_THREADS, baseThreads()) else baseThreads()

    /** Workers the thinker's pinned pool is created with: one per LLM core (up to 6), more than
     *  the [engineThreads] it decodes with, so the speed test can still try 5 and 6 threads. This
     *  is the layout the Pixel speed test measured (6-worker pool, 4 decoding = fastest); idle
     *  workers just wait. The librarian's pool is [ACTION_THREADS]: as the action model it decodes
     *  with all of them (the thinker waits on its tool call); background memory work uses
     *  [engineThreads]. */
    private val poolThreads: Int
        get() = if (role == Role.LIBRARIAN) minOf(ACTION_THREADS, baseThreads())
            else maxOf(engineThreads, CpuTopology.current.llmCpus.size)

    /**
     * Context window (tokens) a prompt plus its output must fit: the loaded model's, or before a
     * load, the one the load will choose (same pure function, same inputs). The native side
     * rejects a prompt longer than this minus maxTokens.
     */
    val contextSize: Int get() = loadedCtx.takeIf { it > 0 && handle != 0L } ?: plannedContext()
    val modelFileSizeBytes: Long get() = if (modelFile.exists()) modelFile.length() else 0L
    /** Qwen3 Thinking MoE presets: ChatML prompts, template-opened <think> trace. */
    /** Qwen3 thinking models: the MoE, and the small Qwen3 that stands in for it on a small phone.
     *  Qwen3.5 ("qwen35-") too: same ChatML, and its template also opens the <think> block itself
     *  (Qwen/Qwen3.5-4B chat_template.jinja: add_generation_prompt -> "<|im_start|>assistant\n<think>\n";
     *  thinking off -> "<think>\n\n</think>\n\n", what LocalPrompt.buildNoThink emits). */
    val isThinkingModel: Boolean get() = modelFile.name.let {
        it.startsWith("qwen3moe") || it.startsWith("qwen3-") || it.startsWith("qwen35-")
    }
    val modelFilePath: String get() = modelFile.absolutePath

    /**
     * Point the engine at a different model. Unloads the current weights first
     * — the native context holds an mmap of the old file, and on a 3.5GB phone
     * two sets of weights do not fit. Does not download; call downloadModel()
     * afterwards if the new file is not already on disk.
     */
    suspend fun selectModel(choice: ModelChoice) {
        unload()
        modelUrl = choice.url
        modelFileName = choice.fileName
        prefs.edit().putString(K_LABEL, choice.label).apply()
        android.util.Log.i("UltraLlm", "selected model: ${choice.label} (${choice.fileName})")
    }

    /** Delete the weights for the current selection. */
    suspend fun deleteModelFile(): Boolean {
        unload()
        return modelFile.exists() && modelFile.delete()
    }

    /** Models on disk, so the user can see what a switch would cost to undo. Finished files
     *  only: an unfinished download is in [partialDownloads]. */
    fun downloadedFileNames(): Set<String> =
        File(context.filesDir, "models").listFiles()
            ?.filter { it.isFile && !it.name.endsWith(PART_SUFFIX) && it.length() > 100_000_000 }
            ?.map { it.name }?.toSet() ?: emptySet()

    /**
     * Unfinished downloads on disk: model file name -> bytes so far. Each belongs to its own
     * model (the .part is named after it) and continues when that model is chosen again, e.g. a
     * 4B download started before the 16 GB default became the 8B. Settings lists them so they
     * don't take space unseen.
     */
    fun partialDownloads(): Map<String, Long> =
        File(context.filesDir, "models").listFiles()
            ?.filter { it.isFile && it.name.endsWith(PART_SUFFIX) && it.length() > 0 }
            ?.associate { it.name.removeSuffix(PART_SUFFIX) to ParallelDownload.bytesSoFar(it) } ?: emptyMap()

    /** Delete the unfinished download of [fileName]; false while either engine downloads it. */
    fun deletePartial(fileName: String): Boolean {
        if (fileName in downloadsInFlight) return false
        val part = File(File(context.filesDir, "models"), fileName + PART_SUFFIX)
        ParallelDownload.planFile(part).delete()
        return part.delete()
    }

    /**
     * Download the model into app-private storage, reporting progress 0..1.
     * 806MB (Gemma) to ~10GB (the MoE a 16GB phone defaults to) over Wi-Fi; streaming write, resumable by re-invocation (partial
     * file is deleted on failure — no half-models).
     */
    suspend fun downloadModel(onProgress: (Float) -> Unit): Result<Long> = withContext(Dispatchers.IO) {
        // One download at a time. Two of them write the same model.part and
        // the result is a corrupt file that still looks the right size.
        if (!downloadInFlight.compareAndSet(false, true)) {
            android.util.Log.w("UltraLlm", "download already running — ignoring second request")
            return@withContext Result.failure(IllegalStateException("a download is already running"))
        }
        // Snapshot the target now. modelFile follows the current selection, and
        // choosing another model while this runs must not land these bytes
        // under that model's name.
        val target = modelFile
        val url = modelUrl
        // The other engine may have picked the same file: one writer per .part across both.
        if (!downloadsInFlight.add(target.name)) {
            downloadInFlight.set(false)
            android.util.Log.w("UltraLlm", "${target.name} is already downloading — ignoring second request")
            return@withContext Result.failure(IllegalStateException("this model is already downloading"))
        }
        // Per-model partial file: a leftover from one model can never be
        // renamed into another model's name.
        val tmp = File(target.parentFile, target.name + PART_SUFFIX)
        downloadProgress.value = 0f
        android.util.Log.i("UltraLlm", "download start: $url -> ${target.name} (have ${tmp.length() / 1_048_576} MB)")
        // A 10 GB file over a phone's Wi-Fi will be interrupted. The bytes already in the .part
        // are kept and the rest is asked for with a Range request, retrying with a back-off;
        // it used to delete the .part on any error and start again from zero. The CPU wake lock
        // keeps the CPU awake; Settings also keeps the screen on while it runs, because a phone
        // asleep in Doze cuts a background app's network.
        val wake = (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
            ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:modelDownload")
        try {
            wake?.acquire(6 * 60 * 60 * 1000L)
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            target.parentFile?.mkdirs()
            var lastError: Exception? = null
            // Several connections at once when the server serves byte ranges (ParallelDownload);
            // asked once, on the first attempt that reaches it.
            var parallelTotal: Long? = null
            var probed = false
            for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
                if (attempt > 1) {
                    val wait = DownloadResume.backoffMs(attempt - 1)
                    android.util.Log.i("UltraLlm", "download retry $attempt/$MAX_DOWNLOAD_ATTEMPTS in ${wait / 1000}s " +
                        "(have ${tmp.length() / 1_048_576} MB): ${lastError?.message}")
                    // Not cancellable: the download is started from the Settings screen's scope, and
                    // leaving Settings mid-wait must not end it (the transfer itself already outlives it).
                    withContext(kotlinx.coroutines.NonCancellable) { kotlinx.coroutines.delay(wait) }
                }
                try {
                    if (!probed) {
                        val probe = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0").build()
                        parallelTotal = client.newCall(probe).execute().use { r ->
                            ParallelDownload.totalFromProbe(r.code, r.header("Content-Range"))
                        }?.takeIf { it >= ParallelDownload.MIN_PARALLEL_BYTES }
                        probed = true
                        android.util.Log.i("UltraLlm", "download: ${if (parallelTotal != null)
                            "${ParallelDownload.CONNECTIONS} connections, ${parallelTotal!! / 1_048_576} MB" else "one connection"}")
                    }
                    val ptotal = parallelTotal
                    if (ptotal != null) {
                        ParallelDownload.run(client, url, tmp, ptotal) { written ->
                            downloadProgress.value = written.toFloat() / ptotal
                            onProgress(written.toFloat() / ptotal)
                        }
                        if (!tmp.renameTo(target)) {
                            return@withContext Result.failure(Exception("could not move the download into place"))
                        }
                        android.util.Log.i("UltraLlm", "download complete: ${ptotal / 1_048_576} MB (attempt $attempt, parallel)")
                        return@withContext Result.success(ptotal)
                    }
                    // One connection: a .part written by the parallel download is sparse (its length
                    // isn't what it holds), so it can't be resumed this way.
                    ParallelDownload.planFile(tmp).let { if (it.exists()) { it.delete(); tmp.delete() } }
                    val existing = if (tmp.exists()) tmp.length() else 0L
                    val req = okhttp3.Request.Builder().url(url).apply {
                        DownloadResume.rangeHeader(existing)?.let { header("Range", it) }
                    }.build()
                    client.newCall(req).execute().use { resp ->
                        val len = resp.body?.contentLength() ?: -1
                        android.util.Log.i("UltraLlm", "download HTTP ${resp.code}, length=$len, from=$existing")
                        val plan = DownloadResume.plan(existing, resp.code, len, resp.header("Content-Range"))
                        if (plan == null) {
                            if (resp.code == 416) tmp.delete()           // .part doesn't match the file: start over
                            if (resp.code in 400..499 && resp.code != 416 && resp.code != 429)
                                return@withContext Result.failure(Exception("HTTP ${resp.code}"))
                            throw java.io.IOException("HTTP ${resp.code}")
                        }
                        val total = plan.total
                        var written = plan.startAt
                        if (!plan.complete) {
                            var lastReport = 0L
                            resp.body!!.byteStream().use { input ->
                                java.io.FileOutputStream(tmp, plan.append).use { out ->
                                    val buf = ByteArray(256 * 1024)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        written += n
                                        val now = System.currentTimeMillis()
                                        if (total > 0 && now - lastReport > 1000) {
                                            lastReport = now
                                            downloadProgress.value = written.toFloat() / total
                                            onProgress(written.toFloat() / total)
                                        }
                                    }
                                }
                            }
                        }
                        if (total > 0 && written != total) {
                            if (written > total) tmp.delete()               // can't be repaired by resuming
                            throw java.io.IOException("short read: $written/$total")
                        }
                        if (!tmp.renameTo(target)) {
                            return@withContext Result.failure(Exception("could not move the download into place"))
                        }
                        android.util.Log.i("UltraLlm", "download complete: ${written / 1_048_576} MB (attempt $attempt)")
                        return@withContext Result.success(written)
                    }
                } catch (e: java.io.IOException) {
                    lastError = e                                       // keep the .part; retry resumes it
                }
            }
            android.util.Log.w("UltraLlm", "download gave up after $MAX_DOWNLOAD_ATTEMPTS attempts", lastError)
            Result.failure(Exception("${lastError?.message ?: "network error"} — tap Download again to continue " +
                "from ${tmp.length() / 1_048_576} MB"))
        } catch (e: Exception) {
            android.util.Log.w("UltraLlm", "download failed", e)
            Result.failure(e)
        } finally {
            runCatching { if (wake?.isHeld == true) wake.release() }
            downloadProgress.value = null
            downloadsInFlight.remove(target.name)
            downloadInFlight.set(false)
        }
    }

    /**
     * Load if memory allows. llama.cpp mmaps the weights — pages load on
     * demand and evict under pressure, so the guard is for the KV cache plus
     * working headroom, not the whole file. The 806MB Gemma model ran clean
     * through llama-bench on this device class with ~850MB available.
     *
     * Always on Dispatchers.IO. The chat brain runs on the main thread and called this
     * directly: on the Pixel 10 Pro, loading the 10 GB MoE sat in nativeLoad (llama_mmap)
     * long past Android's 5 s input timeout, and both on-device ANRs of 2026-10-06 have
     * exactly that stack (main → Brain.runUnlocked → ensureLoaded → nativeLoad).
     */
    suspend fun ensureLoaded(): Boolean =
        if (loaded) true else withContext(Dispatchers.IO) { thinkerDemanding { loadWithRoom() } }

    /** The other engine of the pair, if it was ever created (not created = not loaded). */
    private fun otherEngine(): LocalModelEngine? =
        if (role == Role.THINKER) librarianInstance else sharedInstance

    /**
     * Runs [block] with [thinkerDemand] raised when this is the thinker: while it is, the
     * librarian neither loads nor starts a generation, and one already running stops at its next
     * token ([generate]). Nests (a generate's load raises it again).
     */
    private inline fun <T> thinkerDemanding(block: () -> T): T {
        if (role != Role.THINKER) return block()
        thinkerDemand.incrementAndGet()
        try { return block() } finally { thinkerDemand.decrementAndGet() }
    }

    /**
     * Load, after checking memory against the other engine ([loadDecision]). Decided under
     * [nativeMutex], so the other engine can't load between the check and this load. The
     * librarian's yield (its unload) runs outside the mutex: unload takes the librarian's own
     * generation lock first, and the lock order everywhere is generation lock, then [nativeMutex].
     *
     * Bounded: the thinker runs this inside [thinkerDemanding], so a librarian it unloaded can't
     * load again before the thinker's next round (its load refuses while the thinker wants the
     * engine); the second round therefore sees it unloaded. Should it somehow still be loaded
     * after [MAX_YIELD_ROUNDS], the thinker is not loaded beside it: it fails like a missing
     * model, rather than putting both in a memory that holds one.
     */
    private suspend fun loadWithRoom(): Boolean {
        repeat(MAX_YIELD_ROUNDS) { round ->
            val yielding = nativeMutex.withLock {
                if (handle != 0L) return true
                if (!LlmNative.available || !modelPresent) return loadBlocking()   // logs why
                if (role == Role.LIBRARIAN && thinkerDemand.get() > 0) {
                    android.util.Log.i("UltraLlm", "$role load deferred: the thinker is using the engine")
                    return false
                }
                val other = otherEngine()
                val otherLoaded = other != null && other.loaded
                // Mapped weights are page cache, which availMem counts as free; they must stay.
                val otherMapped = if (other == null) 0L else otherMappedBytes(
                    otherLoaded, other.residentLoaded, other.modelFilePath == modelFilePath, other.modelFileSizeBytes)
                // A resident copy shares no pages with the other engine's mapping, even of the same file.
                val otherMappedBesideCopy = if (other == null) 0L else otherMappedBytes(
                    otherLoaded, other.residentLoaded, false, other.modelFileSizeBytes)
                val avail = availableRam()
                // One reading for both the ctx and the decision; the load uses this same ctx.
                val ctx = plannedContext(roomBesideOther(avail, otherMapped))
                val need = residentNeedBytes(modelFileName, modelFileSizeBytes, ctx)
                // The thinker doesn't settle for a smaller window to sit beside the librarian:
                // if the librarian's mapped weights are what shrinks it, the librarian gives way
                // (not on the last round: a librarian that somehow stayed loaded then only costs ctx).
                val decision = if (role == Role.THINKER && otherLoaded && round < MAX_YIELD_ROUNDS - 1 &&
                    ctx < plannedContext(avail))
                    LoadDecision.LIBRARIAN_YIELDS else loadDecision(role, avail, need, otherLoaded, otherMapped)
                when (decision) {
                    LoadDecision.LOAD -> return loadBlocking(otherMappedBesideCopy, ctx)
                    LoadDecision.REFUSE -> {
                        android.util.Log.w("UltraLlm", "$role refused: availMB=${avail / MIB} " +
                            "otherMappedMB=${otherMapped / MIB} needMB=${need / MIB} (+${MIN_AVAIL_BYTES / MIB} headroom)")
                        return false
                    }
                    LoadDecision.LIBRARIAN_YIELDS -> {
                        android.util.Log.i("UltraLlm", "$role needs room: availMB=${avail / MIB} " +
                            "otherMappedMB=${otherMapped / MIB} needMB=${need / MIB} — unloading the librarian")
                        other
                    }
                }
            }
            yielding?.unload()
        }
        android.util.Log.w("UltraLlm", "$role not loaded: the librarian was still loaded after " +
            "$MAX_YIELD_ROUNDS rounds, and both don't fit")
        return false
    }

    /**
     * Load the model in the background now, so the first question doesn't wait for 10 GB to be
     * read in (the Pixel 10 Pro's ANRs showed how long that is). Called when Jarvis opens and when
     * ambient listening starts, if Settings → "Get the brain ready in advance" is on. A question
     * asked meanwhile simply waits for this same load (ensureLoaded holds one lock).
     */
    fun preloadInBackground(reason: String) {
        if (loaded) { if (!residentLoaded) warmPageCache(reason); return }
        if (!modelPresent || !LlmNative.available || downloadProgress.value != null) return
        preloadScope.launch {
            val t0 = System.currentTimeMillis()
            val ok = ensureLoaded()
            android.util.Log.i("UltraLlm", "preload ($reason) ok=$ok in ${System.currentTimeMillis() - t0}ms")
            // Read the brain's own system prompt once now, so the first real question reuses it from
            // the cache instead of reading ~750 tokens first (Real-use check 2.6.28: ~25 s of 32).
            if (ok && role == Role.THINKER) warmPrompt?.let { build ->
                val t1 = System.currentTimeMillis()
                val r = generate(build(), maxTokens = 1)
                android.util.Log.i("UltraLlm", "prompt warm-up ok=${r.isSuccess} in ${System.currentTimeMillis() - t1}ms")
            }
        }
    }

    @Volatile private var lastWarmMs = 0L
    @Volatile private var residentLoaded = false

    /**
     * Read the whole model file once, sequentially, in the background. The weights are mmap'd, so
     * their pages ARE the file's page cache: one fast sequential pass puts them in memory, where
     * otherwise each missing page is a separate slow storage read in the middle of generating.
     * First Pixel 10 Pro test (2026-10-07): "hi" ran at 1.6 tok/s with 42,299 major page faults
     * and 8.6 GB free, i.e. the MoE's pages had been dropped and were faulted back in one by one.
     * Throttled to once per 5 minutes; a pass over an already-cached file is a quick memory copy.
     */
    private fun warmPageCache(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastWarmMs < 5 * 60_000L) return
        lastWarmMs = now
        val f = modelFile
        preloadScope.launch { busyWhile {
            val t0 = System.currentTimeMillis()
            var n = 0L
            try {
                java.io.FileInputStream(f).use { input ->
                    val buf = ByteArray(4 shl 20)
                    while (true) { val r = input.read(buf); if (r < 0) break; n += r }
                }
            } catch (e: Exception) {
                android.util.Log.w("UltraLlm", "warm ($reason) failed after ${n / 1_048_576} MB: ${e.message}")
                return@launch
            }
            android.util.Log.i("UltraLlm", "warm ($reason) read ${n / 1_048_576} MB in ${System.currentTimeMillis() - t0}ms " +
                "availMB=${availableRam() / 1_048_576}")
        } }
    }

    /** Bytes of an unfinished download of the selected model (0 = none): "Continue download". */
    fun partialBytes(): Long = File(modelFile.parentFile, modelFile.name + PART_SUFFIX).let { if (it.exists()) it.length() else 0L }

    /**
     * Caller holds [nativeMutex] (it serialises loads of both engines, and against generate/free).
     * [otherMappedBytes]: the other engine's mapped weights ([otherMappedBytes] in the companion,
     * same file included), which availMem counts as free but which must stay; a resident copy can't use them.
     */
    private fun loadBlocking(otherMappedBytes: Long = 0L, plannedCtx: Int = 0): Boolean {
        if (loaded) return true
        if (!LlmNative.available) return false          // build without llama.cpp (emulator)
        if (!modelPresent) {
            android.util.Log.w("UltraLlm", "$role model file missing: ${modelFile.absolutePath}")
            return false
        }
        busyWhile {
            if (handle != 0L) return true
            val avail = availableRam()
            if (avail > 0 && avail < MIN_AVAIL_BYTES) {
                android.util.Log.w("UltraLlm", "refusing load: avail=${avail / 1_048_576}MB need=${MIN_AVAIL_BYTES / 1_048_576}MB")
                return false
            }
            val t0 = System.currentTimeMillis()
            val topo = CpuTopology.current
            // The librarian's few workers go on the fastest of the LLM cores (none listed = unpinned).
            val threads = engineThreads
            val pool = poolThreads
            val cpus = if (role == Role.LIBRARIAN) topo.fastestFirst.filter { it in topo.llmCpus }.take(pool).sorted()
                else topo.llmCpus
            android.util.Log.i("UltraLlm", "$role cpus llm=$cpus whisper=${topo.whisperCpus} threads=$threads pool=$pool")
            val ctx = if (plannedCtx > 0) plannedCtx else plannedContext()
            // A resident copy can't give pages back: what a ctx above 4096 adds (KV, mask) must
            // also fit beside it. At 4096 the check is unchanged (its headroom covered that size).
            // The librarian is always mapped: the setting is about the thinker, and availMem
            // counts the thinker's mapped weights as free, which a resident copy would take.
            val resident = role == Role.THINKER && residentLoad(
                com.agent.jarvis.ui.UltraPrefs.keepBrainInMemory(context), roomBesideOther(avail, otherMappedBytes),
                modelFileSizeBytes + ctxGrowthBytes(modelFileName, ctx))
            android.util.Log.i("UltraLlm", "$role load resident=$resident ctx=$ctx availMB=${avail / 1_048_576} " +
                "otherMappedMB=${otherMappedBytes / 1_048_576} " +
                "fileMB=${modelFileSizeBytes / 1_048_576} totalMB=${totalRam / 1_048_576}")
            handle = LlmNative.nativeLoad(modelFile.absolutePath, pool, ctx, cpus.toIntArray(), resident)
            loadedCtx = if (handle != 0L) LlmNative.nativeContextSize(handle) else 0
            android.util.Log.i("UltraLlm", "nativeLoad -> handle=$handle ctx=$loadedCtx in ${System.currentTimeMillis() - t0}ms")
            residentLoaded = resident && handle != 0L
            // A resident copy can't be dropped, so only the mapped mode needs the page cache warmed.
            if (handle != 0L && !resident) warmPageCache("after load")
            // Now, measured with the thinker in memory: the speed boost's draft, if it fits.
            if (handle != 0L) syncDraft(null)
            return handle != 0L
        }
    }

    /** The draft's ctx while one is attached to [handle] (speed boost); 0 = none. */
    @Volatile private var draftCtx = 0
    /** The draft failed to attach for this load (missing vocab match, load or ctx failure):
     *  not retried until the thinker is loaded again. */
    @Volatile private var draftFailed = false

    /** Whether the next generations use the speed boost's draft model (debug tests read it). */
    val draftAttached: Boolean get() = draftCtx > 0 && handle != 0L

    /** [drafted, accepted] of this engine's last generation; drafted = -1 = ran without a draft. */
    @Volatile var lastSpecStats: IntArray = intArrayOf(-1, 0)
        private set

    /**
     * Attach or drop the draft model to match what is wanted: [want] null = the setting
     * (Settings → "Speed boost") and the file being on the phone; true/false = the debug speed
     * test's explicit choice. Thinker only, Qwen3 thinkers only. Attaches only when
     * [DraftModel.contextFor] finds room with the thinker already in memory, so it can't push
     * the thinker into paging; when it doesn't fit, generation just runs without it (asked
     * again next time, a cheap memory reading). Caller holds [nativeMutex].
     */
    private fun syncDraft(want: Boolean?) {
        val h = handle
        if (h == 0L || role != Role.THINKER) return
        val wanted = (want ?: com.agent.jarvis.ui.UltraPrefs.speedBoost(context)) &&
            DraftModel.eligible(modelFileName) && DraftModel.present(context)
        if (!wanted) {
            // Only the setting turning it off frees it; the speed test's "without" run keeps it
            // loaded and simply doesn't use it (draftMax 0).
            if (draftCtx > 0 && want == null) {
                LlmNative.nativeDetachDraft(h)
                draftCtx = 0
                android.util.Log.i("UltraLlm", "draft detached (speed boost off)")
            }
            return
        }
        if (draftCtx > 0 || draftFailed) return
        val other = otherEngine()
        val otherMapped = if (other == null) 0L else otherMappedBytes(
            other.loaded, other.residentLoaded, other.modelFilePath == modelFilePath, other.modelFileSizeBytes)
        val avail = availableRam()
        val room = DraftModel.roomAfterThinker(avail, otherMapped, if (residentLoaded) 0L else modelFileSizeBytes)
        val ctx = DraftModel.contextFor(room, loadedCtx)
        if (ctx == 0) {
            android.util.Log.i("UltraLlm", "draft skipped: roomMB=${room / MIB} (need " +
                "${DraftModel.needBytes(CTX_SIZE) / MIB} + ${DraftModel.FREE_AFTER_BYTES / MIB} free)")
            return
        }
        val t0 = System.currentTimeMillis()
        val r = LlmNative.nativeAttachDraft(h, DraftModel.file(context).absolutePath, ctx)
        if (r > 0) draftCtx = r else draftFailed = true
        android.util.Log.i("UltraLlm", "draft attach -> $r (ctx=$ctx roomMB=${room / MIB} " +
            "needMB=${DraftModel.needBytes(ctx) / MIB}) in ${System.currentTimeMillis() - t0}ms")
    }

    /**
     * One generation at a time, enforced rather than assumed.
     *
     * The header of this class claimed a lock guarded the native context. It
     * did not: the lock covered loading and freeing, and `generate` called into
     * the engine with nothing held at all. Two callers reach this object from
     * independent coroutine scopes — the chat screen and the voice session —
     * and the C++ side keeps its callback references in process globals with no
     * mutex of its own. A voice command arriving while a chat reply was
     * streaming would overwrite those globals and re-enter a context that
     * cannot be re-entered.
     *
     * A coroutine Mutex rather than `synchronized`, because this is a suspend
     * function: blocking a thread here would hold it for the whole generation
     * and can deadlock against the dispatcher. Waiting is the correct
     * behaviour — the second request runs when the first finishes.
     *
     * Per engine: it keeps this engine's queue and makes [unload] wait for its
     * generation. The native call itself also holds the process-wide [nativeMutex],
     * because the thinker and the librarian share those native globals.
     */
    private val genMutex = kotlinx.coroutines.sync.Mutex()

    private val decodeSpeed = DecodeSpeed()

    /** Recent decode speed of the model in use (tokens/s, EMA of real generations), or null
     *  before one has run since load. Read-only; reset on unload and on a model switch. */
    val decodeTokPerSec: Double? get() = decodeSpeed.tokPerSec(modelFile.name)

    /** True while a generation of THIS engine holds [genMutex], a load runs or the page cache is
     * warmed. Ambient listening watches [anyBusy] (either engine) and holds whisper back meanwhile:
     * both want the same big cores and memory bandwidth (Pixel 10 Pro ANR dumps). */
    val busy: kotlinx.coroutines.flow.StateFlow<Boolean> get() = busyFlow
    private val busyFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var busyCount = 0                       // guarded by busyFlow; load nests in generate

    private inline fun <T> busyWhile(block: () -> T): T {
        synchronized(busyFlow) { busyFlow.value = ++busyCount > 0 }
        anyBusyChange(+1)
        try { return block() } finally {
            synchronized(busyFlow) { busyFlow.value = --busyCount > 0 }
            anyBusyChange(-1)
        }
    }

    /**
     * [thinkBudget] non-null: the reasoning trace is capped, hidden from [onToken] and
     * from the returned text (only the answer comes back). Threads: the thermal policy's
     * count for the level read once the lock is held ([threads] > 0 can only lower it); native
     * always gets an explicit count, and it stays fixed for the whole call.
     * [onStart] runs once the lock is held and the model is loaded, right before decoding:
     * the moment "thinking" really begins (not while queued behind another generation).
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int,
        thinkBudget: com.agent.jarvis.agent.ThinkBudget? = null,
        threads: Int = 0,
        /** Benchmark only: run exactly [threads] (capped at the loaded count), ignoring the thermal
         *  policy. The Pixel speed sweep asked for 4/5/6 and every run got 2 (policy said HOT). */
        exactThreads: Boolean = false,
        /** Speed boost's draft model: null = the setting; true/false = the debug speed test's
         *  with/without runs. Same output either way (ultra_llm.cpp generateSpeculative). */
        draft: Boolean? = null,
        /** Benchmark only: draft length (> 0), instead of [DraftModel.DRAFT_MAX]. */
        draftLen: Int = 0,
        /** A tool call the person is waiting on (the action model): the librarian decodes with its whole
         *  pool, [ACTION_THREADS], not the background [LIBRARIAN_THREADS] (Pixel log 2026-10-19: the first
         *  tool call took 61 s on 2 cores). */
        foreground: Boolean = false,
        onStart: () -> Unit = {},
        onToken: (String) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) { thinkerDemanding {
        // The librarian gives way to the thinker: it doesn't start while the thinker wants the
        // engine (checked again once each lock is held), and stops at the next token when the
        // thinker asks meanwhile. The caller gets [YieldedToThinker] and may try again later.
        val yields = role == Role.LIBRARIAN
        if (yields && thinkerDemand.get() > 0) return@withContext Result.failure(YieldedToThinker())
        genMutex.withLock { busyWhile {
            if (yields && thinkerDemand.get() > 0) return@withLock Result.failure(YieldedToThinker())
            // Outside nativeMutex: a load takes it itself (and the thinker's may unload the librarian).
            if (!ensureLoaded()) return@withLock Result.failure(
                if (yields && thinkerDemand.get() > 0) YieldedToThinker()
                else IllegalStateException(if (yields) "memory model not available" else "on-device model not available")
            )
            nativeMutex.withLock {
                if (yields && thinkerDemand.get() > 0) return@withLock Result.failure(YieldedToThinker())
                // Read the handle INSIDE the lock. Reading it outside was the
                // use-after-free: unload could zero and free it between the check
                // and the call.
                val h = handle
                if (h == 0L) return@withLock Result.failure(
                    IllegalStateException("on-device model was unloaded")
                )
                // Before the timing starts: a first attach reads the draft's file.
                syncDraft(draft)
                val draftMax = if (draft != false && draftAttached)
                    (if (draftLen > 0) draftLen else DraftModel.DRAFT_MAX) else 0
                val sb = StringBuilder()
                val t0 = System.currentTimeMillis()
                val faults0 = PageFaults.read()
                val nThreads = if (exactThreads && threads > 0) minOf(threads, poolThreads)
                    else ThermalPolicy.resolveThreads(threads, ThermalGuard.level,
                        if (role == Role.LIBRARIAN && foreground) poolThreads else engineThreads)
                onStart()
                // Decode speed: time from the first sampled token to the last, so prompt prefill
                // (which grows with history) doesn't drag the estimate down.
                var pieces = 0
                var tFirst = 0L
                var tLast = 0L
                var stopped = false
                var cancelled = false
                // A cancelled caller (the librarian when the phone is picked up, a stopped test)
                // stops at the next token instead of holding the cores until maxTokens.
                val callerJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                val n = LlmNative.nativeGenerate(h, prompt, maxTokens, 0.2f, nThreads, draftMax) cb@{ piece ->
                    if (yields && thinkerDemand.get() > 0) { stopped = true; return@cb LlmNative.STOP }
                    if (callerJob?.isActive == false) { cancelled = true; return@cb LlmNative.STOP }
                    tLast = System.currentTimeMillis()
                    if (pieces++ == 0) tFirst = tLast
                    if (thinkBudget == null) {
                        sb.append(piece); onToken(piece); null
                    } else {
                        val step = thinkBudget.onToken(piece)
                        if (step.visible.isNotEmpty()) { sb.append(step.visible); onToken(step.visible) }
                        step.inject
                    }
                }
                thinkBudget?.flush()?.takeIf { it.isNotEmpty() }?.let { sb.append(it); onToken(it) }
                // Thrash check: after the first answer, majfaults should stay near 0 and tok/s steady.
                val ms = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                val faults1 = PageFaults.read()
                val spec = LlmNative.nativeSpecStats(h).takeIf { it.size == 2 } ?: intArrayOf(-1, 0)
                lastSpecStats = spec
                android.util.Log.i("UltraLlm", "gen tokens=$n ms=$ms tokPerSec=${"%.1f".format(n * 1000.0 / ms)} " +
                    "majfaults=${if (faults0 >= 0 && faults1 >= 0) faults1 - faults0 else -1} " +
                    "availMB=${availableRam() / 1_048_576} model=${modelFile.name} cpu=${LlmNative.variant} threads=$nThreads " +
                    "draft=${specLabel(spec)}")
                if (n >= 0 && pieces > 1) decodeSpeed.observe(modelFile.name, pieces - 1, tLast - tFirst)
                thinkBudget?.let {
                    android.util.Log.d("UltraRoute", "thinkTokens=${it.thinkTokens} forced=${it.forced} budget=${it.budgetTokens}")
                }
                when {
                    n < 0 -> Result.failure(IllegalStateException("native generate failed ($n)"))
                    cancelled -> {
                        android.util.Log.i("UltraLlm", "$role stopped after $pieces tokens: caller cancelled")
                        Result.failure(kotlinx.coroutines.CancellationException("caller cancelled"))
                    }
                    stopped -> {
                        android.util.Log.i("UltraLlm", "$role stopped after $pieces tokens: the thinker asked for the engine")
                        Result.failure(YieldedToThinker())
                    }
                    else -> Result.success(sb.toString())
                }
            }
        } }
    } }

    /**
     * A plain answer without reasoning: the librarian's call for sorting and searching memory.
     * [prompt] is a finished prompt, normally [com.agent.jarvis.agent.LocalPrompt.buildNoThink];
     * any think block the model writes anyway is removed from the result. Same queueing and
     * locks as [generate], at this engine's thread count; safe from any coroutine (it switches
     * to Dispatchers.IO). On the librarian it never runs while the thinker generates or loads:
     * it fails with [YieldedToThinker] instead (before starting, or at the next token if the
     * thinker asks while it runs; reading the prompt itself can't be interrupted), and the
     * caller tries again later.
     */
    suspend fun complete(prompt: String, maxTokens: Int): Result<String> =
        generate(prompt, maxTokens).map { com.agent.jarvis.agent.ThinkBudget.stripForHistory(it) }

    /**
     * Free the model, waiting for any generation already running.
     *
     * Freeing under a live generation is a use-after-free in native code — the
     * kind that corrupts memory quietly rather than crashing where the mistake
     * was. "Free memory" is a button a user presses whenever they like, so this
     * has to be safe at any moment, not only at a convenient one.
     */
    suspend fun unload() = withContext(Dispatchers.IO) {
        genMutex.withLock {
            nativeMutex.withLock {
                if (handle != 0L) {
                    LlmNative.nativeFree(handle)       // frees the draft too
                    handle = 0
                    draftCtx = 0
                    draftFailed = false
                    decodeSpeed.reset()
                    loadedCtx = 0
                    residentLoaded = false
                    android.util.Log.i("UltraLlm", "$role unloaded")
                }
            }
        }
    }

    private fun availableRam(): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.availMem
    } catch (_: Exception) { -1 }

    companion object {
        /** The thinker's usual prompt start (set by Brain), read once after a preload: see [preloadInBackground]. */
        @Volatile var warmPrompt: (() -> String)? = null
        /** One engine per role per process. The weights are mmap'd against a
         * single native context — a second instance would load them twice. */
        @Volatile private var sharedInstance: LocalModelEngine? = null
        @Volatile private var librarianInstance: LocalModelEngine? = null
        private val preloadScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

        /**
         * Every native load, generate and free, of both engines. The shim keeps the token
         * callback of the generation in flight in process globals (and its backend init
         * flag is unguarded), so two native calls must never overlap, whichever engine
         * makes them. Lock order: an engine's own generation lock first, then this one.
         */
        private val nativeMutex = kotlinx.coroutines.sync.Mutex()

        /** An unfinished download: the model's file name plus this. */
        const val PART_SUFFIX = ".part"

        /** Thinker calls in progress ([thinkerDemanding]): a generate, or a load. While above 0
         *  the librarian doesn't load or start, and one running stops at its next token. */
        private val thinkerDemand = java.util.concurrent.atomic.AtomicInteger(0)

        /** Rounds of [loadWithRoom]: the thinker needs at most two (yield, then load). */
        const val MAX_YIELD_ROUNDS = 3

        private val anyBusyFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        private var anyBusyCount = 0                // guarded by anyBusyFlow

        /** True while either engine is [busy]: what ambient listening waits on. */
        val anyBusy: kotlinx.coroutines.flow.StateFlow<Boolean> get() = anyBusyFlow

        private fun anyBusyChange(delta: Int) =
            synchronized(anyBusyFlow) { anyBusyCount += delta; anyBusyFlow.value = anyBusyCount > 0 }

        /**
         * The other engine's weights that availMem counts as free but which must stay in memory
         * (mapped pages in page cache). 0 when it is not loaded, when it holds a resident copy
         * (availMem already excludes that), or when it maps the same file as this load (the pages
         * are shared, and this load's own need already counts them once). Pure, JVM-tested.
         */
        fun otherMappedBytes(otherLoaded: Boolean, otherResident: Boolean, sameFile: Boolean, otherFileBytes: Long): Long =
            if (!otherLoaded || otherResident || sameFile) 0L else otherFileBytes.coerceAtLeast(0L)

        /** Memory a resident load may count on: [availBytes] less [otherMappedBytes]. Unknown
         *  memory (<= 0) stays unknown, which [residentLoad] treats as "map it". Pure, JVM-tested. */
        fun roomBesideOther(availBytes: Long, otherMappedBytes: Long): Long =
            if (availBytes <= 0) availBytes else (availBytes - otherMappedBytes.coerceAtLeast(0L)).coerceAtLeast(0L)

        /** "off" (no draft used), or "accepted/drafted (rate%)" for the gen log line. Pure, JVM-tested. */
        fun specLabel(stats: IntArray): String {
            val drafted = stats.getOrElse(0) { -1 }
            val accepted = stats.getOrElse(1) { 0 }
            if (drafted < 0) return "off"
            val pct = if (drafted > 0) accepted * 100 / drafted else 0
            return "$accepted/$drafted($pct%)"
        }

        /** Model files being downloaded by either engine: both may pick the same file. */
        private val downloadsInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** The thinker: the main on-device model (prefs "ultra_local_model", as before). */
        fun shared(context: Context): LocalModelEngine =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: LocalModelEngine(context.applicationContext)
                    .also { sharedInstance = it }
            }

        /** The librarian: a small second model that sorts and searches memory ([complete]). */
        fun librarian(context: Context): LocalModelEngine =
            librarianInstance ?: synchronized(this) {
                librarianInstance ?: LocalModelEngine(context.applicationContext, Role.LIBRARIAN)
                    .also { librarianInstance = it }
            }

        /** The librarian's thread count: a few workers, so it never takes the thinker's cores. */
        const val LIBRARIAN_THREADS = 2

        /** The librarian's pool, all used when it is the action model on a tool call (owner, 2026-10-20). */
        const val ACTION_THREADS = 4

        /** Largest preset offered as the librarian (Settings → MEMORY MODEL). */
        const val LIBRARIAN_MAX_MB = 1_500

        /** Presets that can be the librarian: Qwen3 (its prompt, LocalPrompt.buildNoThink, is Qwen3
         *  ChatML with the /no_think switch; Gemma and Llama use other templates), runnable on a
         *  phone, at most [LIBRARIAN_MAX_MB]. */
        val LIBRARIAN_PRESETS: List<ModelChoice>
            get() = PRESETS.filter {
                !it.desktopOnly && it.approxMb in 1..LIBRARIAN_MAX_MB && it.fileName.startsWith("qwen3-")
            }

        /**
         * Bytes a load needs in free memory to run without paging: weights + KV(ctx) + compute
         * buffer, i.e. [memoryNeedBytes] without the shares planned for Android and the rest of
         * this app (those are already outside what availMem reports).
         */
        fun residentNeedBytes(fileName: String, modelBytes: Long, ctx: Int): Long =
            memoryNeedBytes(fileName, modelBytes, ctx) - ANDROID_RESERVE_BYTES - APP_MARGIN_BYTES

        /**
         * Whether [role] may load now. Pure, JVM-tested (TwoModelsTest).
         *
         * Fits when [availBytes] minus [otherMappedBytes] (the other engine's mapped weights:
         * availMem counts them as reclaimable page cache, but they must stay in memory to run
         * at speed; 0 when it is not loaded or holds a resident copy, which availMem already
         * excludes) covers [needBytes] ([residentNeedBytes]) plus [MIN_AVAIL_BYTES].
         * When it doesn't, the librarian gives way, never the thinker: a librarian is refused;
         * a thinker unloads a loaded librarian ([LoadDecision.LIBRARIAN_YIELDS]), and alone it
         * loads as it always did (its own [MIN_AVAIL_BYTES] guard still applies).
         * Unknown memory (<= 0) loads, as before.
         */
        fun loadDecision(
            role: Role, availBytes: Long, needBytes: Long, otherLoaded: Boolean, otherMappedBytes: Long,
        ): LoadDecision {
            if (availBytes <= 0) return LoadDecision.LOAD
            val room = availBytes - (if (otherLoaded) otherMappedBytes.coerceAtLeast(0) else 0L)
            return when {
                room >= needBytes + MIN_AVAIL_BYTES -> LoadDecision.LOAD
                role == Role.LIBRARIAN -> LoadDecision.REFUSE
                otherLoaded -> LoadDecision.LIBRARIAN_YIELDS
                else -> LoadDecision.LOAD
            }
        }

        // Measured on the A15 5G (llama-bench, Gemma 3 1B Q4_K_M):
        // 4 threads = 10.1 tok/s generation, 16.6 tok/s prompt eval.
        const val THREADS = 4

        /** Full-speed generate thread count; the thermal policy scales down from it. One per
         *  [CpuTopology.MAX_LLM_THREADS] (4, the Pixel's fastest); [THREADS] when the core layout
         *  can't be read. The thinker's pool has a worker per LLM core ([poolThreads]); a call
         *  never decodes with more than this outside the speed test. */
        fun baseThreads(): Int = CpuTopology.current.llmThreads
        /** The context every model gets at least (what all loads used before [contextFor]). */
        const val CTX_SIZE = 4096

        /** Context windows [contextFor] picks from, smallest first. */
        val CTX_STEPS = intArrayOf(CTX_SIZE, 8192, 16384)
        private const val MIB = 1_048_576L
        /** Left for Android and every other app: a planning figure (4.5 GiB), not a measurement. */
        const val ANDROID_RESERVE_BYTES = 4_608L * MIB
        /** The rest of this app (UI, Whisper, Java heap) plus error in the estimates below. */
        const val APP_MARGIN_BYTES = 512L * MIB
        /** = cparams.n_ubatch in ultra_llm.cpp (pinned there); the compute buffer is sized for it. */
        const val N_UBATCH = 512
        /** Compute-buffer activations besides logits and mask at N_UBATCH tokens: an estimate. */
        const val ACTIVATIONS_BYTES = 128L * MIB

        /**
         * KV-cache shape of a model: per token, layers x kvHeads x headDim elements each for K and V.
         * [vocab] sizes the logits part of the compute buffer; [trainCtx] caps the context
         * (llama.cpp only warns past it, the model degrades). [stateBytes]: a fixed per-sequence
         * recurrent state on top (hybrid models; 0 for plain attention). For a hybrid model
         * [layers] counts only its full-attention layers, the only ones with a KV cache.
         */
        internal data class KvProfile(
            val layers: Int, val kvHeads: Int, val headDim: Int, val vocab: Int,
            val trainCtx: Int = Int.MAX_VALUE, val stateBytes: Long = 0,
        ) {
            /** q8_0: a 32-element block is 34 bytes (32 int8 + one f16 scale). */
            val kvBytesPerToken: Long get() = layers.toLong() * kvHeads * headDim * 2 * 34 / 32
        }

        /** Shapes from each model's config, keyed by the preset file name's prefix. */
        private val KV_PROFILES = listOf(
            "qwen3moe" to KvProfile(48, 4, 128, 151_936),      // Qwen3-30B-A3B, all MoE presets + fine-tunes
            "qwen3-1.7b" to KvProfile(28, 8, 128, 151_936),
            "qwen3-4b" to KvProfile(36, 8, 128, 151_936),           // Qwen3-4B (2507 thinking, abliterated)
            "qwen3-8b" to KvProfile(36, 8, 128, 151_936),
            // Qwen3.5-4B (Qwen/Qwen3.5-4B config.json, text_config): hybrid, 32 layers of which every
            // 4th (full_attention_interval 4) is full attention -> 8 KV layers (llama.cpp 8345f33
            // qwen35.cpp marks the rest recurrent; the hybrid memory's attention filter keeps only
            // the non-recurrent ones), num_key_value_heads 4, head_dim 256, vocab 248,320. The 24
            // Gated DeltaNet layers keep an f32 state per sequence (hparams n_embd_r + n_embd_s:
            // conv 3 x (4096 + 2 x 16 x 128) + ssm 128 x 4096 floats) = 24 x 548,864 x 4 B.
            // Read from the base model's config, not from the abliterated GGUF's metadata.
            "qwen35-4b" to KvProfile(8, 4, 256, 248_320, stateBytes = 24L * 548_864 * 4),
            "gemma3-1b" to KvProfile(26, 1, 256, 262_144),
            "llama32-1b" to KvProfile(16, 8, 64, 128_256),
            "qwen25-15b" to KvProfile(28, 2, 128, 151_936),
            "qwen25-3b" to KvProfile(36, 2, 128, 151_936),
            "phi35-mini" to KvProfile(32, 32, 96, 32_064),
            "qwen25-7b" to KvProfile(28, 4, 128, 152_064),
            "llama31-8b" to KvProfile(32, 8, 128, 128_256),
            "gemma2-9b" to KvProfile(42, 8, 256, 256_000, trainCtx = 8192),
            "qwen25-14b" to KvProfile(48, 8, 128, 152_064),
        )
        /** Any other file (Gemma 4 E2B, a custom GGUF): Phi-3.5's KV (the largest per token among
         *  the presets that fit a phone) and Gemma's vocab, so an unknown model is over-counted;
         *  capped at 8192 since its training context is not known here. */
        internal val UNKNOWN_PROFILE = KvProfile(32, 32, 96, 262_144, trainCtx = 8192)

        internal fun kvProfile(fileName: String): KvProfile =
            KV_PROFILES.firstOrNull { fileName.startsWith(it.first) }?.second ?: UNKNOWN_PROFILE

        /**
         * Bytes a load at [ctx] needs in RAM, Android's share included: weights + q8_0 KV(ctx) +
         * compute buffer + [ANDROID_RESERVE_BYTES] + [APP_MARGIN_BYTES]. Compute buffer, checked in
         * llama.cpp 8345f33 (llama-context.cpp sched_reserve, llama-graph.cpp): reserved for
         * min(n_ctx, n_ubatch) = 512 tokens, so n_batch = ctx does not grow it; it holds logits for
         * 512 outputs (512 x vocab x 4 B: ~297 MiB Qwen, 512 MiB Gemma 3), the flash-attention KQ
         * mask (n_ctx x 512 x f16; counted twice for every model, as Gemma's sliding-window
         * layers add a second one), and activations
         * ([ACTIVATIONS_BYTES], estimated, not measured). Flash attention reads q8_0 K/V directly
         * (no f16 copy) and its CPU scratch is tiled, independent of n_ctx.
         */
        fun memoryNeedBytes(fileName: String, modelBytes: Long, ctx: Int): Long {
            val p = kvProfile(fileName)
            val compute = N_UBATCH.toLong() * p.vocab * 4 + 2L * ctx * N_UBATCH * 2 + ACTIVATIONS_BYTES
            return modelBytes + p.kvBytesPerToken * ctx + p.stateBytes + compute + ANDROID_RESERVE_BYTES + APP_MARGIN_BYTES
        }

        /** Memory a load at [ctx] needs beyond the same load at [CTX_SIZE] (KV + mask growth). */
        fun ctxGrowthBytes(fileName: String, ctx: Int): Long =
            memoryNeedBytes(fileName, 0, ctx) - memoryNeedBytes(fileName, 0, CTX_SIZE)

        /**
         * Context window for this model on this phone: the largest of [CTX_STEPS] whose
         * [memoryNeedBytes] fits in [totalRamBytes] (and within the model's training context);
         * never below [CTX_SIZE], which every model ran at before (whether a model fits at all
         * is [fitFor]'s call). Unknown RAM or size (<= 0) -> [CTX_SIZE]. Pure, JVM-tested.
         *
         * q8_0 KV per ctx (4096 / 8192 / 16384), MiB:
         *  - Qwen3-30B-A3B MoE (48 x 4 x 128): 204 / 408 / 816 (51 KiB per token)
         *  - Qwen3 1.7B 238 / 476 / 952; Gemma 3 1B 55 / 110 / 221; Llama 3.2 1B 68 / 136 / 272;
         *    Qwen2.5 1.5B 60 / 119 / 238; Qwen2.5 3B 77 / 153 / 306; Qwen2.5 7B 119 / 238 / 476;
         *    Llama 3.1 8B 272 / 544 / 1088; Qwen2.5 14B 408 / 816 / 1632;
         *    Phi-3.5 mini (and unknown files) 816 / 1632 / 3264; Gemma 2 9B 714 / 1428 (capped 8192).
         *
         * MoE IQ2_M preset (10240 MiB of weights) on a 16 GB phone, bytes:
         * weights 10,737,418,240 + compute 311,164,928 logits + 134,217,728 activations
         * + reserve 4,831,838,208 + margin 536,870,912 = 16,551,510,016 before KV and mask;
         *  - 8192:  + KV 427,819,008 + mask 16,777,216 = 16,996,106,240
         *  - 16384: + KV 855,638,016 + mask 33,554,432 = 17,440,702,464 > 16 GiB (17,179,869,184)
         * so 16384 never fits beside the MoE IQ2_M on a 16 GB phone, and 8192 only where the phone
         * reports at least ~17.0e9 bytes total (a phone reporting 15-16e9 stays at 4096). The real
         * file size is used at load, so a smaller file moves these lines down by the difference.
         * MoE IQ2_S (9300 MiB): 8192 from ~16.01e9, 16384 from ~16.46e9. Small models (1-3B) reach
         * 16384 from about 7-8.1e9 total (an 8 GB phone); below ~6.7e9 they stay at 4096.
         */
        fun contextFor(totalRamBytes: Long, fileName: String, modelBytes: Long): Int {
            if (totalRamBytes <= 0 || modelBytes <= 0) return CTX_SIZE
            val cap = kvProfile(fileName).trainCtx
            return CTX_STEPS.filter { it <= cap && memoryNeedBytes(fileName, modelBytes, it) <= totalRamBytes }
                .maxOrNull() ?: CTX_SIZE
        }

        /**
         * Free memory the thinker's load must leave behind. Pixel 10 Pro XL: the 8B loaded
         * resident at 16384 (ctx from [contextFor], i.e. from TOTAL RAM) left ~2.4 GB free and
         * paged; 3 GiB is more than that, and also leaves the librarian room to load later
         * (Qwen3 1.7B Q4_0 needs ~1.7 GB at 4096 plus [MIN_AVAIL_BYTES]). A planning figure,
         * not a measured no-paging threshold.
         */
        const val THINKER_FREE_AFTER_LOAD_BYTES = 3_072L * MIB

        /**
         * Context window for the thinker from the memory free NOW: the largest of [CTX_STEPS]
         * whose [residentNeedBytes] leaves [THINKER_FREE_AFTER_LOAD_BYTES] of [roomBytes], never
         * above [contextFor] (total RAM, training context) and never below [CTX_SIZE].
         * [roomBytes]: availMem less the weights a loaded librarian maps ([roomBesideOther]),
         * which availMem counts as free but which must stay. Unknown (<= 0) -> [contextFor], as
         * before. Pure, JVM-tested (ContextSizeTest).
         *
         * Pixel 10 Pro XL, 15,081 MiB total, ~8.6e9 B free: Qwen3-8B Q4_K_S (4,802,012,992 B)
         * needs ~5.58e9 at 4096, 5.91e9 at 8192, 6.56e9 at 16384 -> 4096 (8192 would leave 2.69e9).
         * Qwen3-4B Q4_0 (2,375,773,312 B): 3.48e9 at 8192, 4.14e9 at 16384 -> 16384, also beside
         * a loaded 1.7B librarian (room 8.6e9 - 1.06e9 mapped = 7.54e9).
         */
        fun contextForRoom(roomBytes: Long, totalRamBytes: Long, fileName: String, modelBytes: Long): Int {
            val upper = contextFor(totalRamBytes, fileName, modelBytes)
            if (roomBytes <= 0 || modelBytes <= 0) return upper
            return CTX_STEPS.filter {
                it <= upper && residentNeedBytes(fileName, modelBytes, it) + THINKER_FREE_AFTER_LOAD_BYTES <= roomBytes
            }.maxOrNull() ?: CTX_SIZE
        }

        /** [contextFor] a preset by its listed size (before the file is on disk). */
        fun contextFor(totalRamBytes: Long, model: ModelChoice): Int =
            contextFor(totalRamBytes, model.fileName, model.approxMb * MIB)
        /** Over a 10 GB download: 5, 10, 20, 40, then 60 s apart — about 25 minutes of trying. */
        const val MAX_DOWNLOAD_ATTEMPTS = 30
        const val MIN_AVAIL_BYTES = 500L * 1024 * 1024
        /** Free memory a resident load must leave behind, beyond the model file itself. */
        const val RESIDENT_HEADROOM_BYTES = 1_024L * 1024 * 1024

        /**
         * Load the weights into the app's own memory (true) or map the file (false). Resident
         * only when the setting is on and memory now holds the whole file plus headroom; an
         * unknown reading (<= 0) maps, the safe choice. Pure, JVM-tested.
         */
        fun residentLoad(settingOn: Boolean, availBytes: Long, fileBytes: Long): Boolean =
            settingOn && availBytes > 0 && fileBytes > 0 && availBytes >= fileBytes + RESIDENT_HEADROOM_BYTES
        const val FAST_PATH_MAX_BYTES = 2_500L * 1024 * 1024
        private const val K_URL = "model_url"
        private const val K_FILE = "model_file"
        private const val K_LABEL = "model_label"

        /**
         * Presets. Every URL here was checked to resolve, and the sizes are the
         * real content-length, not the model card's claim. Anything larger than
         * about 2GB is not worth trying on a 3.5GB phone — the weights are
         * mmap'd, but the KV cache and the rest of the app still need room.
         */
        val PRESETS = listOf(
            // Fast-path models (2026-10-08). Measured on the Pixel 10 Pro: the 9.7 GB MoE ran at
            // 0.4–1.6 tok/s because only ~8.6 GB is ever available there, so its pages are read
            // back from storage continuously (25k–227k major faults per answer). Q4_0 / Q4_K are
            // repacked at load for i8mm (llama.cpp repack.cpp); IQ2 never is. Same Qwen3 chat
            // template and <think> handling as the MoE ("qwen3-" file names). Files and sizes
            // checked on the Hub listing, 2026-10-06 (unsloth has no Q4_0 for the 8B: Q4_K_S,
            // also repacked).
            ModelChoice(
                "Qwen3-4B Thinking 2507 (Q4_0) — fast, strongest reasoning",
                "https://huggingface.co/unsloth/Qwen3-4B-Thinking-2507-GGUF/resolve/main/Qwen3-4B-Thinking-2507-Q4_0.gguf",
                "qwen3-4b-thinking-2507-q4_0.gguf", 2266,
                "Reasoning model small enough to stay fully in memory with room to spare, on the phone CPU's fast path. Strong maths/reasoning for its size and good at tool calls.",
            ),
            ModelChoice(
                "Qwen3-4B Thinking 2507 ABLITERATED (Q4_K_M) — default",
                "https://huggingface.co/mradermacher/Huihui-Qwen3-4B-Thinking-2507-abliterated-GGUF/resolve/main/Huihui-Qwen3-4B-Thinking-2507-abliterated.Q4_K_M.gguf",
                "qwen3-4b-thinking-2507-abliterated-q4km.gguf", 2382,
                "The same 4B with refusals removed (Q4_K_M, also on the fast path, speed boost included). The default since 2026-10-07 (owner's choice).",
            ),
            // Qwen3.5-4B, abliterated by huihui-ai, quantised by mradermacher. File and size checked
            // on the Hub listing, 2026-10-07: 2,707,514,688 B. Qwen's card for the stock Qwen3.5-4B
            // (thinking): GPQA Diamond 76.2 vs 65.8 for Qwen3-4B-Thinking-2507; the abliterated build
            // is unmeasured (abliteration can cost some reasoning). A hybrid model (llama.cpp arch
            // qwen35): the native side can't trim its state, so a turn reuses the previous prompt
            // only when it extends it, and the speed-boost draft (Qwen3 vocabulary) never applies.
            ModelChoice(
                "Qwen3.5-4B abliterated (Q4_K_M) — strongest reasoning, new",
                "https://huggingface.co/mradermacher/Huihui-Qwen3.5-4B-abliterated-GGUF/resolve/main/Huihui-Qwen3.5-4B-abliterated.Q4_K_M.gguf",
                "qwen35-4b-abliterated-q4km.gguf", 2582,
                "Newer generation of the 4B, refusals removed. On Qwen's published tests the stock Qwen3.5-4B reasons clearly better than the 4B Thinking 2507. Untested on this phone: speed unknown, and no speed boost for it. The 4B Thinking stays the safe default.",
            ),
            ModelChoice(
                "Qwen3-8B (Q4_K_S) — bigger, slower",
                "https://huggingface.co/unsloth/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_S.gguf",
                "qwen3-8b-q4ks.gguf", 4580,
                "Twice the 4B's size, roughly half its speed. On Qwen's published reasoning, maths and tool-call tests the 4B Thinking 2507 scores higher; the 8B knows slightly more general facts.",
            ),
            // The fine-tune from Qwen3_30B_A3B_LoRA_Colab.ipynb (its REPO / OUT_M / OUT_S). These 404
            // until that notebook has run and uploaded. Own file names, so a phone holding the stock
            // MoE still fetches these (the app knows a model by its file name).
            ModelChoice(
                "Jarvis fine-tune (IQ2_M) — after the training run",
                "https://huggingface.co/arbloodhound/jarvis-qwen3moe-abliterated-gguf/resolve/main/qwen3moe-jarvis-iq2m.gguf",
                "qwen3moe-jarvis-iq2m.gguf", 10240,
                "Your domains baked into the abliterated MoE. Pick this one if the Pixel test passed IQ2_M. Download fails until the notebook has uploaded it.",
            ),
            ModelChoice(
                "Jarvis fine-tune (IQ2_S) — after the training run",
                "https://huggingface.co/arbloodhound/jarvis-qwen3moe-abliterated-gguf/resolve/main/qwen3moe-jarvis-iq2s.gguf",
                "qwen3moe-jarvis-iq2s.gguf", 9300,
                "The same fine-tune, lighter. Pick this one if IQ2_M was borderline on the Pixel. Download fails until the notebook has uploaded it.",
            ),
            ModelChoice(
                "Qwen3-30B-A3B Thinking ABLITERATED (IQ2_M) — on-device MoE",
                "https://huggingface.co/mradermacher/Huihui-Qwen3-30B-A3B-Thinking-2507-abliterated-i1-GGUF/resolve/main/Huihui-Qwen3-30B-A3B-Thinking-2507-abliterated.i1-IQ2_M.gguf",
                "qwen3moe-abliterated-iq2m.gguf", 10240,
                "Strongest reasoning, but too big to stay in memory on a 16 GB Pixel: measured 0.4–1.6 words/s there (only ~8.6 GB is free, the file is 9.7 GB). Needs a phone with more free memory.",
            ),
            ModelChoice(
                "Qwen3-30B-A3B Thinking ABLITERATED (IQ2_S) — roomier fallback",
                "https://huggingface.co/mradermacher/Huihui-Qwen3-30B-A3B-Thinking-2507-abliterated-i1-GGUF/resolve/main/Huihui-Qwen3-30B-A3B-Thinking-2507-abliterated.i1-IQ2_S.gguf",
                "qwen3moe-abliterated-iq2s.gguf", 9300,
                "Same abliterated MoE, lighter. ~9.3GB — more headroom if IQ2_M pages under a long context.",
            ),
            ModelChoice(
                "Qwen3-30B-A3B Thinking (IQ2_M) — stock, comparison",
                "https://huggingface.co/unsloth/Qwen3-30B-A3B-Thinking-2507-GGUF/resolve/main/Qwen3-30B-A3B-Thinking-2507-UD-IQ2_M.gguf",
                "qwen3moe-thinking-iq2m.gguf", 10800,
                "Non-abliterated (safety-tuned) build of the same MoE, for comparison. ~10.8GB — ~0.1GB free, knife-edge. Prefer the abliterated IQ2_M.",
            ),
            ModelChoice(
                "Qwen3-30B-A3B Thinking (IQ3) — DESKTOP ONLY, exceeds 16GB",
                "https://huggingface.co/unsloth/Qwen3-30B-A3B-Thinking-2507-GGUF/resolve/main/Qwen3-30B-A3B-Thinking-2507-UD-IQ3_XXS.gguf",
                "qwen3moe-thinking-iq3xxs.gguf", 12900,
                "DO NOT run on the phone — ~12.9GB weights put total demand ~2GB over 16GB. Listed for desktop reference / quality ceiling only.",
                desktopOnly = true,
            ),
            // URL not checked from the build machine (Hugging Face is blocked there): a failed
            // download on the phone means the file moved, not that the app is broken.
            ModelChoice(
                "Qwen3 1.7B (Q4_K_M) — for small phones",
                "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
                "qwen3-1.7b-q4km.gguf", 1110,
                "Same Qwen3 prompt format and <think> handling as the 4B, small enough for a 4GB phone. Noticeably weaker reasoning than the 4B Thinking: only for phones the 4B doesn't fit.",
            ),
            // The librarian's default (Settings → MEMORY MODEL). File and size checked on the Hub
            // listing (unsloth/Qwen3-1.7B-GGUF), 2026-10-06: 1,056,782,912 B. Q4_0 is repacked
            // for i8mm at load, like the 4B.
            ModelChoice(
                "Qwen3 1.7B (Q4_0) — memory model",
                "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_0.gguf",
                "qwen3-1.7b-q4_0.gguf", 1008,
                "Small and quick. Sorts and searches what Jarvis remembers, beside the main model.",
            ),
            ModelChoice(
                "Gemma 3 1B (Q4_K_M) — small-phone default",
                "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/google_gemma-3-1b-it-Q4_K_M.gguf",
                "gemma3-1b-q4km.gguf", 768,
                "Smallest and fastest. Measured 10.1 tok/s on a mid-range phone; quicker on a flagship. The one the tool loop was tuned against.",
            ),
            ModelChoice(
                "Llama 3.2 1B (Q4_K_M)",
                "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                "llama32-1b-q4km.gguf", 770,
                "Same size class as the default. Different instruction style — worth a try if Gemma misreads you.",
            ),
            ModelChoice(
                "Gemma 3 1B (Q8_0) — higher quality",
                "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/google_gemma-3-1b-it-Q8_0.gguf",
                "gemma3-1b-q8.gguf", 1019,
                "Same model, less quantisation damage. Bigger and slower.",
            ),
            ModelChoice(
                "Qwen2.5 1.5B (Q4_K_M)",
                "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
                "qwen25-15b-q4km.gguf", 940,
                "Larger, generally stronger at structured output. Slower than the 1B models.",
            ),
            ModelChoice(
                "Qwen2.5 3B (Q4_K_M)",
                "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
                "qwen25-3b-q4km.gguf", 1840,
                "Noticeably better reasoning than the 1B models. A good middle ground.",
            ),
            ModelChoice(
                "Phi-3.5 mini (3.8B, Q4_K_M)",
                "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
                "phi35-mini-q4km.gguf", 2282,
                "Strong for its size on instruction following and structured output.",
            ),
            ModelChoice(
                "Gemma 4 E2B (2.8B, Q4_K_M)",
                "https://huggingface.co/bartowski/google_gemma-4-E2B-it-GGUF/resolve/main/google_gemma-4-E2B-it-Q4_K_M.gguf",
                "gemma4-e2b-q4km.gguf", 3302,
                "Google's latest small model. Better reasoning than Gemma 3 at similar speed. Needs 6GB+ RAM.",
            ),
            // Liquid AI LFM2.5 (2026-10-18, owner: "something faster"). Files and sizes from the Hub
            // listing (LiquidAI/*-GGUF): 1,593,894,912 B and 4,844,678,368 B. The lfm2 / lfm2moe
            // architectures are in the pinned llama.cpp (8345f33). Not thinking models: they answer
            // without a hidden trace. Speed and tool-call quality on the Pixel are unmeasured.
            ModelChoice(
                "LFM2.5 2.6B (Q4_0) — fast, answers without thinking (refusals on)",
                "https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF/resolve/main/LFM2.5-2.6B-Q4_0.gguf",
                "lfm25-2.6b-q4_0.gguf", 1520,
                "Liquid AI's on-device model: a hybrid design built to run fast on phone CPUs. No hidden thinking, so the reply starts almost at once.",
            ),
            ModelChoice(
                "LFM2.5 8B-A1B MoE (Q4_0) — fast and bigger",
                "https://huggingface.co/LiquidAI/LFM2.5-8B-A1B-GGUF/resolve/main/LFM2.5-8B-A1B-Q4_0.gguf",
                "lfm25-8b-a1b-q4_0.gguf", 4620,
                "8B of knowledge, but only about 1B works per word, so it runs near 1B-model speed. Needs 8GB+ RAM.",
            ),
            ModelChoice(
                "Qwen2.5 7B (Q4_K_M)",
                "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
                "qwen25-7b-q4km.gguf", 4466,
                "Flagship territory. The first size that can drive a tool loop on its own rather than just answering.",
            ),
            ModelChoice(
                "Llama 3.1 8B (Q4_K_M)",
                "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
                "llama31-8b-q4km.gguf", 4692,
                "Same family as the cloud model this app was tuned against.",
            ),
            ModelChoice(
                "Gemma 2 9B (Q4_K_M)",
                "https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf",
                "gemma2-9b-q4km.gguf", 5494,
                "Large. Only worth trying on a phone with plenty of memory to spare.",
            ),
            ModelChoice(
                "Qwen2.5 14B (Q4_K_M)",
                "https://huggingface.co/bartowski/Qwen2.5-14B-Instruct-GGUF/resolve/main/Qwen2.5-14B-Instruct-Q4_K_M.gguf",
                "qwen25-14b-q4km.gguf", 8571,
                "Beyond what a phone runs well. Listed so the ceiling is visible, not because it is advised.",
            ),
        )

        /**
         * How a model sits on THIS phone. Sizes are the real download size,
         * checked against the server rather than taken from a model card.
         *
         * llama.cpp memory-maps the weights, so a model does not have to fit
         * in free memory to load — but it does have to stay resident to run at
         * a usable speed, and it shares the phone with everything else. The
         * bands below are fractions of total RAM: generous enough to use a
         * flagship properly, honest about where it starts to hurt.
         *
         * The weights are not the whole bill: the KV cache and compute buffer
         * at CTX_SIZE come on top, so they are added before the fraction is taken.
         * (A larger ctx is only chosen by [contextFor], against its own full memory budget.)
         */
        fun fitFor(model: ModelChoice, totalRamBytes: Long): Fit {
            if (model.desktopOnly) return Fit.TOO_BIG
            if (totalRamBytes <= 0 || model.approxMb <= 0) return Fit.COMFORTABLE
            val fraction = ((model.approxMb + runtimeOverheadMb(model)) * 1_048_576.0) / totalRamBytes
            // MoE weights (filename marks them) are mmap'd and only ~3B active, so they
            // run at high RAM fraction where a dense model would not — allow up to ~0.85
            // on a flagship, flagged TIGHT. Dense models keep the stricter bands.
            val isMoe = model.fileName.contains("moe")
            return when {
                fraction <= 0.25 -> Fit.COMFORTABLE
                fraction <= 0.45 -> Fit.TIGHT
                isMoe && fraction <= 0.85 -> Fit.TIGHT
                else -> Fit.TOO_BIG
            }
        }

        /**
         * KV cache + compute buffer at CTX_SIZE, on top of the weights. A per-size-class
         * figure, not per-model arithmetic — deliberately rounded up.
         *
         * KV is q8_0 (ultra_llm.cpp nativeLoad), about 1.06 bytes per element:
         * layers x kv_heads x head_dim x 2 (K,V) x 4096 cells. Qwen3-30B-A3B
         * (48 x 4 x 128) is ~204 MiB; dense 7B-14B ~120-410 MiB; 1B-3B Gemma / Llama /
         * Qwen2.5 ~55-77 MiB, Qwen3 1.7B ~240 MiB. llama.cpp zeroes the KV buffer at
         * load, so all of it is resident. The compute buffer reserves room for
         * 512 x vocab logits (~300 MiB for Qwen), but the shim asks for one output per
         * decode, so most of that reservation is never touched; the resident working
         * set is an estimate (tens of MB to ~100 MB), not a measurement.
         *
         * Large (MoE, or > 4 GB weights, i.e. dense 7B and up): 512 MiB covers the MoE
         * with margin and dense models up to 14B. Small: 256 MiB covers the 1B-3B
         * presets. Known undercounts: Phi-3.5 mini (no GQA, ~816 MiB KV) and Gemma 2 9B
         * (~714 MiB KV). Gemma 4 E2B's KV layout was not checked. Assumes the q8_0 load
         * succeeds; nativeLoad's f16 fallback (head dim not a multiple of 32) roughly
         * doubles the KV part, and no preset here has such a head dim.
         */
        fun runtimeOverheadMb(model: ModelChoice): Int =
            if (model.fileName.contains("moe") || model.approxMb > SMALL_MODEL_MAX_MB) {
                LARGE_MODEL_OVERHEAD_MB
            } else {
                SMALL_MODEL_OVERHEAD_MB
            }

        const val SMALL_MODEL_MAX_MB = 4_000
        const val SMALL_MODEL_OVERHEAD_MB = 256
        const val LARGE_MODEL_OVERHEAD_MB = 512

        fun totalRamBytes(context: Context): Long = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        } catch (_: Exception) { 0L }

        /** Safe default stays the small Gemma, not the 12.9GB MoE now at index 0.
         *  It is what any phone gets unless [defaultFor] shows the MoE fits. */
        val DEFAULT = PRESETS.first { it.fileName == "gemma3-1b-q4km.gguf" }

        /** The abliterated MoE IQ2_M (kept for reference / ContextSizeTest; no longer a default). */
        val MOE_DEFAULT = PRESETS.first { it.fileName == "qwen3moe-abliterated-iq2m.gguf" }

        /** What a phone with room for it defaults to: Qwen3-4B Thinking 2507 abliterated, Q4_K_M (the
         *  owner's choice, 2026-10-07: the same model as [STOCK_4B] with refusals removed). */
        val FAST_DEFAULT = PRESETS.first { it.fileName == "qwen3-4b-thinking-2507-abliterated-q4km.gguf" }

        /** The default before that: the stock Qwen3-4B Thinking 2507, Q4_0. An install that has it on disk
         *  keeps using it until the abliterated one is downloaded ([defaultFor]). */
        val STOCK_4B = PRESETS.first { it.fileName == "qwen3-4b-thinking-2507-q4_0.gguf" }

        /** The default reasoning brain: Qwen3-4B Thinking 2507, and its abliterated build (same base
         *  model), plus the Qwen3.5-4B abliterated (scores higher still on Qwen's published tests).
         *  A slow brain test on these says keep it and how to speed it up, never "switch to a
         *  smaller model" (all smaller presets reason worse): KitReport.advice. */
        val REASONING_DEFAULTS: Set<String> =
            setOf(FAST_DEFAULT.fileName, STOCK_4B.fileName, "qwen35-4b-abliterated-q4km.gguf")

        /** Qwen3-8B Q4_K_S: the 14 GB+ default before Qwen3-4B Thinking 2507 replaced it. Still
         *  selectable, and kept by an install that already has it on disk ([defaultFor]). */
        val BIG_DEFAULT = PRESETS.first { it.fileName == "qwen3-8b-q4ks.gguf" }

        /** The librarian's model until the user chooses one: Qwen3 1.7B Q4_0. */
        val LIBRARIAN_DEFAULT = PRESETS.first { it.fileName == "qwen3-1.7b-q4_0.gguf" }

        /**
         * The model used before the user has chosen one. Pure, JVM-tested (DefaultModelTest).
         *
         * Qwen3-4B Thinking 2507 Q4_0 wherever [fitFor] says it fits (8 GB and up), 16 GB phones
         * included. In Qwen's published thinking-mode results it scores above Qwen3-8B on
         * reasoning (GPQA 65.8 vs 62.0, AIME25 81.3 vs 67.3, LiveBench 71.8 vs 67.1), instruction
         * following (IFEval 87.4 vs 85.0) and tool calls (BFCL-v3 71.2 vs 68.1); the 8B is ahead
         * only on MMLU-Redux (87.5 vs 86.1). 4B figures re-checked on the Qwen3-4B-Thinking-2507
         * model card; the 8B's are from the Qwen3 Tech Report (arXiv 2505.09388, Table 17) as
         * noted in HANDOFF.md, not re-checked. Speed on the owner's Pixel 10 Pro XL, from his
         * earlier runs as recorded in HANDOFF.md: 8-11 tok/s, against 2-4 for the 8B (paging).
         * Everywhere else, and when RAM is unknown (<= 0, where fitFor answers COMFORTABLE for
         * anything), the small Gemma. Only names the model; nothing is downloaded. A choice the
         * user made (prefs set) always wins over this.
         *
         * [downloaded] (file names in models/): an install from before this default may have
         * downloaded an earlier default without saving any prefs (the 8B on a 14 GB+ phone, or
         * Gemma). If the 4B is not on disk, stay on the earlier one that is, so an update never
         * points a working install at a missing multi-GB file; Settings suggests the 4B instead
         * ([fasterThinkerHint]).
         */
        fun defaultFor(totalRamBytes: Long, downloaded: Set<String> = emptySet()): ModelChoice {
            // Not the MoE any more: on the 16 GB Pixel it can't stay in memory (see PRESETS).
            val byRam = if (totalRamBytes > 0 && fitFor(FAST_DEFAULT, totalRamBytes) != Fit.TOO_BIG) FAST_DEFAULT else DEFAULT
            if (byRam.fileName in downloaded) return byRam
            val earlier = listOf(STOCK_4B, BIG_DEFAULT, DEFAULT).filter {
                it == DEFAULT || (totalRamBytes > 0 && fitFor(it, totalRamBytes) != Fit.TOO_BIG)
            }
            return earlier.firstOrNull { it.fileName in downloaded } ?: byRam
        }

        /** One line for the model picker while the thinker is the 8B; null for any other file.
         *  Pure, JVM-tested (DefaultModelTest). */
        fun fasterThinkerHint(currentFileName: String): String? = when (currentFileName) {
            BIG_DEFAULT.fileName -> "4B-Thinking is faster and scores higher on reasoning benchmarks."
            STOCK_4B.fileName -> "The default is now the abliterated 4B (same model, refusals removed): pick it below to switch."
            else -> null
        }
    }
}

/** The librarian gave way to the thinker (didn't start, or stopped early); try again later. */
class YieldedToThinker : IllegalStateException("memory model gave way to the main model")

/** A downloadable on-device model. */
data class ModelChoice(
    val label: String,
    val url: String,
    val fileName: String,
    val approxMb: Int,
    val note: String,
    /** Listed for reference only; never offered as runnable on a phone. */
    val desktopOnly: Boolean = false,
)

/** How a model sits on a given phone. */
enum class Fit { COMFORTABLE, TIGHT, TOO_BIG }
