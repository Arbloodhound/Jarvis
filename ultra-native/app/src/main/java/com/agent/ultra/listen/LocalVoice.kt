package com.agent.ultra.listen

import android.content.Context
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Text-to-speech with Jarvis's own voice: sherpa-onnx running a voice from [VoiceCatalog] on the
 * phone (Kokoro, or a Piper/VITS voice). Android's TextToSpeech is Google's engine on a Pixel and
 * often missing on a de-Googled phone; this needs neither, and no network after the voice's
 * one-time download (20–105 MB, checked against its pinned sha256).
 *
 * The voice is the one picked in Settings → JARVIS'S VOICE ([com.agent.ultra.ui.VoicePrefs]); until
 * it has downloaded, another downloaded voice speaks ([VoiceCatalog.speaking]). Norman (Piper, from
 * public-domain LibriVox recordings) was the only voice before 2026-10-07.
 *
 * One OfflineTts at a time, loaded on first use and freed after a minute idle, like [LocalWhisper].
 */
object LocalVoice {
    private const val TAG = "UltraSpeak"
    /** The first voice's folder name (kept: what a phone may already have unpacked). */
    const val VOICE = VoiceCatalog.NORMAN
    /** Unpacked, Kokoro is ~160 MB; anything far past the largest voice is not one of these. */
    private const val MAX_UNPACKED_BYTES = 400_000_000L
    private const val IDLE_MS = 60_000L
    private const val PIPER_THREADS = 2
    /** Kokoro is about 4x a Piper medium voice; its sentences are made while the next is said. */
    private const val KOKORO_THREADS = 3
    private const val RETRY_MS = 10 * 60_000L

    /** libsherpa-onnx-jni.so loaded. False on a build without it (e.g. the x86_64 bench build). */
    val available: Boolean by lazy {
        try { System.loadLibrary("sherpa-onnx-jni"); true }
        catch (e: Throwable) { android.util.Log.w(TAG, "own voice library not loadable: ${e.message}"); false }
    }

    private fun dir(context: Context, m: VoiceCatalog.Model) = File(context.filesDir, m.id)

    /** [m] is unpacked and complete. */
    fun voicePresent(context: Context, m: VoiceCatalog.Model): Boolean {
        val d = dir(context, m)
        if (File(d, m.modelFile).length() < 1_000_000L || !File(d, "tokens.txt").isFile ||
            !File(d, "espeak-ng-data").isDirectory) return false
        return m.kind != VoiceCatalog.Kind.KOKORO || File(d, "voices.bin").length() > 0
    }

    /** Some own voice can speak now (the chosen one, or a stand-in while it downloads). */
    fun voicePresent(context: Context): Boolean = speakingModel(context) != null

    /** The own voice that speaks now, or null (the phone's voice then). */
    fun speakingModel(context: Context): VoiceCatalog.Model? =
        VoiceCatalog.speaking(com.agent.ultra.ui.VoicePrefs.voiceId(context)) { voicePresent(context, it) }

    /** The chosen own voice (null when the phone's voice is chosen). */
    fun chosenModel(context: Context): VoiceCatalog.Model? =
        VoiceCatalog.byId(com.agent.ultra.ui.VoicePrefs.voiceId(context))

    private val fetching = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val lastFailAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Download [m] (default: the chosen voice) if it isn't there, running or recently failed. Returns at once. */
    fun fetchInBackground(context: Context, m: VoiceCatalog.Model? = chosenModel(context)) {
        if (m == null) return
        val app = context.applicationContext
        if (voicePresent(app, m) || System.currentTimeMillis() - (lastFailAt[m.id] ?: 0L) < RETRY_MS) return
        if (fetching.putIfAbsent(m.id, true) != null) return
        scope.launch {
            try { if (!fetch(app, m)) lastFailAt[m.id] = System.currentTimeMillis() } finally { fetching.remove(m.id) }
        }
    }

    /** [m] is downloading now. */
    fun fetchingNow(m: VoiceCatalog.Model): Boolean = fetching.containsKey(m.id)

    /** Bytes of [m]'s archive downloaded so far (for a progress line), 0 when none. */
    fun downloadedBytes(context: Context, m: VoiceCatalog.Model): Long =
        maxOf(File(context.filesDir, "${m.id}.tar.bz2.part").length(), File(context.filesDir, "${m.id}.tar.bz2").length())

    /** One install at a time: the speak-time background fetch and Settings share the archive,
     *  its .part and the unpack folder. A second caller waits, then finds the voice. */
    private val fetchLock = Any()

    /** Download, check and unpack the chosen voice. Blocking; call off main. */
    fun fetch(context: Context): Boolean = chosenModel(context)?.let { fetch(context, it) } ?: false

    /** Download, check and unpack [m]. Blocking; call off main. */
    fun fetch(context: Context, m: VoiceCatalog.Model): Boolean = synchronized(fetchLock) {
        if (voicePresent(context, m)) return true
        val files = context.filesDir
        val name = "${m.id}.tar.bz2"
        val archive = File(files, name)
        val tmp = File(files, "${m.id}.unpacking")
        return try {
            if (!ModelFetcher.ensure(files, name, m.url, m.bytes, exactBytes = m.bytes)) return false
            if (sha256(archive) != m.sha256) {
                android.util.Log.w(TAG, "voice ${m.id} failed its checksum; discarded")
                archive.delete(); return false
            }
            tmp.deleteRecursively()
            extractTarBz2(archive, tmp)
            val unpacked = File(tmp, m.id)
            val dest = dir(context, m)
            dest.deleteRecursively()
            if (!unpacked.isDirectory || !unpacked.renameTo(dest)) throw IOException("unexpected archive layout")
            archive.delete()
            android.util.Log.i(TAG, "own voice installed: ${m.id}")
            voicePresent(context, m)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "voice ${m.id} install failed: ${e.message}")
            false
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Remove a downloaded voice (Settings), freeing its space. */
    fun delete(context: Context, m: VoiceCatalog.Model) {
        scope.launch {
            lock.withLock { if (loadedId == m.id) { tts?.free(); tts = null; loadedId = "" } }
            synchronized(fetchLock) { dir(context, m).deleteRecursively() }
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

    /** Unpack [archive] into [dest]: regular files and directories only, every path checked by
     * [safeEntryPath] and again against [dest]'s canonical path. Throws on anything else.
     * JVM-tested. */
    internal fun extractTarBz2(archive: File, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalPath + File.separator
        var total = 0L
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val rel = safeEntryPath(e.name) ?: throw IOException("unsafe archive entry: ${e.name}")
                if (e.isSymbolicLink || e.isLink) throw IOException("link in archive: ${e.name}")
                val out = File(dest, rel)
                if (!out.canonicalPath.startsWith(root)) throw IOException("entry escapes target: ${e.name}")
                when {
                    e.isDirectory -> out.mkdirs()
                    e.isFile && !e.isCharacterDevice && !e.isBlockDevice && !e.isFIFO -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = tar.read(buf); if (n < 0) break
                                total += n
                                if (total > MAX_UNPACKED_BYTES) throw IOException("archive too large")
                                o.write(buf, 0, n)
                            }
                        }
                    }
                    else -> throw IOException("unsupported archive entry: ${e.name}")
                }
            }
        }
    }

    private val lock = Mutex()
    private var tts: OfflineTts? = null
    private var loadedId = ""
    private var idle: Job? = null

    private fun load(context: Context, m: VoiceCatalog.Model): OfflineTts {
        val d = dir(context, m)
        val espeak = File(d, "espeak-ng-data").absolutePath
        val tokens = File(d, "tokens.txt").absolutePath
        val model = when (m.kind) {
            VoiceCatalog.Kind.PIPER -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = File(d, m.modelFile).absolutePath, tokens = tokens, dataDir = espeak),
                numThreads = PIPER_THREADS, debug = false, provider = "cpu",
            )
            VoiceCatalog.Kind.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(d, m.modelFile).absolutePath, voices = File(d, "voices.bin").absolutePath,
                    tokens = tokens, dataDir = espeak,
                ),
                numThreads = KOKORO_THREADS, debug = false, provider = "cpu",
            )
        }
        return OfflineTts(config = OfflineTtsConfig(model = model))
    }

    /**
     * Speech for [text] in the voice that speaks now ([speakingModel]), at the chosen speaker and
     * speed; null when no own voice is downloaded, it can't be loaded or synthesis fails. Starts
     * the chosen voice's download if it isn't there yet.
     */
    suspend fun generate(context: Context, text: String): GeneratedAudio? = lock.withLock {
        idle?.cancel()
        val chosen = chosenModel(context)
        if (chosen != null && !voicePresent(context, chosen)) fetchInBackground(context, chosen)
        val m = speakingModel(context) ?: return@withLock null
        val sid = com.agent.ultra.ui.VoicePrefs.speaker(context, m.id)
        val speed = com.agent.ultra.ui.VoicePrefs.speed(context)
        val r = try {
            if (loadedId != m.id) { tts?.free(); tts = null; loadedId = "" }
            val engine = tts ?: load(context, m).also { tts = it; loadedId = m.id }
            engine.generate(text, sid = sid, speed = speed).takeIf { it.samples.isNotEmpty() && it.sampleRate > 0 }
        } catch (e: Throwable) {   // UnsatisfiedLinkError, or IllegalArgumentException from a bad voice dir
            android.util.Log.w(TAG, "own voice ${m.id} synthesis failed: ${e.message}")
            // The files are there but sherpa can't load them: remove them, so the next speak
            // fetches a fresh copy instead of retrying a broken voice on every answer.
            if (tts == null && e is IllegalArgumentException) dir(context, m).deleteRecursively()
            null
        }
        idle = scope.launch {
            delay(IDLE_MS)
            lock.withLock { tts?.free(); tts = null; loadedId = "" }
        }
        r
    }

    /**
     * The relative path to write a tar entry to, or null when it is unsafe: absolute (Unix or
     * Windows), a `..` segment, a NUL, or nothing left after removing `./`. Pure, JVM-tested.
     */
    fun safeEntryPath(name: String): String? {
        if (name.isEmpty() || '\u0000' in name) return null
        val n = name.replace('\\', '/')
        if (n.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(n)) return null
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /**
     * [text] in pieces of at most [max] chars, split after sentence ends (then commas, then
     * spaces, then anywhere), so the first piece is synthesised and playing while the rest are
     * still being made. No piece is blank. Pure, JVM-tested.
     */
    fun chunks(text: String, max: Int = 200): List<String> {
        val out = mutableListOf<String>()
        val limit = max.coerceAtLeast(1)
        for (sentence in text.split(Regex("(?<=[.!?;:…])\\s+"))) split(sentence.trim(), limit, out)
        return out
    }

    private fun split(s: String, max: Int, out: MutableList<String>) {
        if (s.isBlank()) return
        if (s.length <= max) { out += s; return }
        val head = s.substring(0, max + 1)
        val cut = head.lastIndexOf(", ").takeIf { it > 0 }?.plus(1)
            ?: head.lastIndexOf(' ').takeIf { it > 0 }
            ?: max
        out += s.substring(0, cut).trim()   // s is trimmed and cut >= 1: never blank
        split(s.substring(cut).trim(), max, out)
    }

    /** Float samples in [-1, 1] as 16-bit PCM; out-of-range values are clamped, not wrapped.
     * Pure, JVM-tested. */
    fun toPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { i ->
        val v = samples[i]
        when {
            v.isNaN() -> 0
            v >= 1f -> Short.MAX_VALUE
            v <= -1f -> Short.MIN_VALUE
            else -> (v * 32767f).toInt().toShort()
        }
    }
}
