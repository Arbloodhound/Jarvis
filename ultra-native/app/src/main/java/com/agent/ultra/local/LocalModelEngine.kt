package com.agent.ultra.local

import android.content.Context
import com.agent.ultra.agent.ThermalGuard
import com.agent.ultra.agent.ThermalPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The on-device model engine. Owns model lifecycle (file presence, memory
 * headroom check, load) and exposes one suspend generate(). Single-flight:
 * the agent loop is sequential, so a mutex serialises generation, and freeing waits for it.
 */
class LocalModelEngine(private val context: Context) {

    private val prefs = context.getSharedPreferences("ultra_local_model", Context.MODE_PRIVATE)
    private val downloadInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Progress 0..1 of the download in flight, or null when none is running.
     * Lives on the engine, not the screen: a download outlives the Settings
     * screen that started it, and the screen has to find it again on return. */
    val downloadProgress = kotlinx.coroutines.flow.MutableStateFlow<Float?>(null)
    private var handle: Long = 0
    private val lock = Any()

    /** Where the weights come from. Any GGUF the llama.cpp build understands. */
    var modelUrl: String
        get() = prefs.getString(K_URL, DEFAULT.url) ?: DEFAULT.url
        private set(v) { prefs.edit().putString(K_URL, v).apply() }

    /** Filename on disk. Distinct per model, so switching does not clobber
     * a model you already downloaded — swapping back is free. */
    var modelFileName: String
        get() = prefs.getString(K_FILE, DEFAULT.fileName) ?: DEFAULT.fileName
        private set(v) { prefs.edit().putString(K_FILE, v).apply() }

    val modelLabel: String
        get() = prefs.getString(K_LABEL, DEFAULT.label) ?: DEFAULT.label

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
    val modelFileSizeBytes: Long get() = if (modelFile.exists()) modelFile.length() else 0L
    /** Qwen3 Thinking MoE presets: ChatML prompts, template-opened <think> trace. */
    /** Qwen3 thinking models: the MoE, and the small Qwen3 that stands in for it on a small phone. */
    val isThinkingModel: Boolean get() = modelFile.name.let { it.startsWith("qwen3moe") || it.startsWith("qwen3-") }
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

    /** Models on disk, so the user can see what a switch would cost to undo. */
    fun downloadedFileNames(): Set<String> =
        File(context.filesDir, "models").listFiles()
            ?.filter { it.isFile && it.length() > 100_000_000 }
            ?.map { it.name }?.toSet() ?: emptySet()

    /**
     * Download the model into app-private storage, reporting progress 0..1.
     * 806MB over Wi-Fi; streaming write, resumable by re-invocation (partial
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
        // Per-model partial file: a leftover from one model can never be
        // renamed into another model's name.
        val tmp = File(target.parentFile, target.name + ".part")
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
        if (loaded) true else withContext(Dispatchers.IO) { loadBlocking() }

    /**
     * Load the model in the background now, so the first question doesn't wait for 10 GB to be
     * read in (the Pixel 10 Pro's ANRs showed how long that is). Called when Jarvis opens and when
     * ambient listening starts, if Settings → "Get the brain ready in advance" is on. A question
     * asked meanwhile simply waits for this same load (ensureLoaded holds one lock).
     */
    fun preloadInBackground(reason: String) {
        if (loaded || !modelPresent || !LlmNative.available || downloadProgress.value != null) return
        preloadScope.launch {
            val t0 = System.currentTimeMillis()
            val ok = ensureLoaded()
            android.util.Log.i("UltraLlm", "preload ($reason) ok=$ok in ${System.currentTimeMillis() - t0}ms")
        }
    }

    /** Bytes of an unfinished download of the selected model (0 = none): "Continue download". */
    fun partialBytes(): Long = File(modelFile.parentFile, modelFile.name + ".part").let { if (it.exists()) it.length() else 0L }

    private fun loadBlocking(): Boolean {
        if (loaded) return true
        if (!LlmNative.available) return false          // build without llama.cpp (emulator)
        if (!modelPresent) {
            android.util.Log.w("UltraLlm", "model file missing: ${modelFile.absolutePath}")
            return false
        }
        synchronized(lock) {
            if (handle != 0L) return true
            val avail = availableRam()
            if (avail > 0 && avail < MIN_AVAIL_BYTES) {
                android.util.Log.w("UltraLlm", "refusing load: avail=${avail / 1_048_576}MB need=${MIN_AVAIL_BYTES / 1_048_576}MB")
                return false
            }
            val t0 = System.currentTimeMillis()
            val topo = CpuTopology.current
            android.util.Log.i("UltraLlm", "cpus llm=${topo.llmCpus} whisper=${topo.whisperCpus} threads=${topo.llmThreads}")
            handle = LlmNative.nativeLoad(modelFile.absolutePath, topo.llmThreads, CTX_SIZE, topo.llmCpus.toIntArray())
            android.util.Log.i("UltraLlm", "nativeLoad -> handle=$handle in ${System.currentTimeMillis() - t0}ms")
            return handle != 0L
        }
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
     */
    private val genMutex = kotlinx.coroutines.sync.Mutex()

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
        thinkBudget: com.agent.ultra.agent.ThinkBudget? = null,
        threads: Int = 0,
        onStart: () -> Unit = {},
        onToken: (String) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        genMutex.withLock {
            if (!ensureLoaded()) return@withLock Result.failure(
                IllegalStateException("on-device model not available")
            )
            // Read the handle INSIDE the lock. Reading it outside was the
            // use-after-free: unload could zero and free it between the check
            // and the call.
            val h = handle
            if (h == 0L) return@withLock Result.failure(
                IllegalStateException("on-device model was unloaded")
            )
            val sb = StringBuilder()
            val t0 = System.currentTimeMillis()
            val faults0 = PageFaults.read()
            val nThreads = ThermalPolicy.resolveThreads(threads, ThermalGuard.level, baseThreads())
            onStart()
            val n = LlmNative.nativeGenerate(h, prompt, maxTokens, 0.2f, nThreads) { piece ->
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
            android.util.Log.i("UltraLlm", "gen tokens=$n ms=$ms tokPerSec=${"%.1f".format(n * 1000.0 / ms)} " +
                "majfaults=${if (faults0 >= 0 && faults1 >= 0) faults1 - faults0 else -1} " +
                "availMB=${availableRam() / 1_048_576} model=${modelFile.name} cpu=${LlmNative.variant} threads=$nThreads")
            thinkBudget?.let {
                android.util.Log.d("UltraRoute", "thinkTokens=${it.thinkTokens} forced=${it.forced} budget=${it.budgetTokens}")
            }
            if (n < 0) Result.failure(IllegalStateException("native generate failed ($n)"))
            else Result.success(sb.toString())
        }
    }

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
            synchronized(lock) {
                if (handle != 0L) {
                    LlmNative.nativeFree(handle)
                    handle = 0
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
        /** One engine per process. The weights are ~800MB mmap'd against a
         * single native context — a second instance would load them twice. */
        @Volatile private var sharedInstance: LocalModelEngine? = null
        private val preloadScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

        fun shared(context: Context): LocalModelEngine =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: LocalModelEngine(context.applicationContext)
                    .also { sharedInstance = it }
            }

        // Measured on the A15 5G (llama-bench, Gemma 3 1B Q4_K_M):
        // 4 threads = 10.1 tok/s generation, 16.6 tok/s prompt eval.
        const val THREADS = 4

        /** Full-speed generate thread count; the thermal policy scales down from it. One per
         *  big core (see [CpuTopology]); [THREADS] when the core layout can't be read. The
         *  model loads with exactly this many pinned workers; more per call is clamped. */
        fun baseThreads(): Int = CpuTopology.current.llmThreads
        const val CTX_SIZE = 4096
        /** Over a 10 GB download: 5, 10, 20, 40, then 60 s apart — about 25 minutes of trying. */
        const val MAX_DOWNLOAD_ATTEMPTS = 30
        const val MIN_AVAIL_BYTES = 500L * 1024 * 1024
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
                "Stock abliterated MoE (no fine-tune). 30B total / 3B active, best reasoning that fits 16GB. ~10.2GB → ~1.6GB free in an assistant-only setup. Cap context at 4k. Your fine-tune is the \"Jarvis fine-tune\" entry.",
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
                "Qwen3 1.7B (Q4_K_M) — MoE stand-in for small phones",
                "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
                "qwen3-1.7b-q4km.gguf", 1110,
                "Same Qwen3 prompt format and <think> handling as the MoE, small enough for a 4GB phone. Tests the offline brain path (think budget, hidden trace, routing) before the MoE is on a phone that fits it.",
            ),
            ModelChoice(
                "Gemma 3 1B (Q4_K_M) — default",
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
         *  The MoE is an opt-in choice the user picks knowing the RAM cost. */
        val DEFAULT = PRESETS.first { it.fileName == "gemma3-1b-q4km.gguf" }
    }
}

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
