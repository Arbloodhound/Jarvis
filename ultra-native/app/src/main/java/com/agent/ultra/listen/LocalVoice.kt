package com.agent.ultra.listen

import android.content.Context
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Text-to-speech with Jarvis's own voice: sherpa-onnx running a Piper (VITS) voice on the phone.
 * Android's TextToSpeech is Google's engine on a Pixel and often missing on a de-Googled phone;
 * this needs neither, and no network after the voice's one-time download (~21 MB).
 *
 * The voice is Piper's en_US "norman" (medium, int8), chosen for its licence: trained from
 * scratch on public-domain LibriVox recordings. (libritts_r is fine-tuned from the lessac voice,
 * whose dataset licence is not an open one.)
 *
 * One OfflineTts, loaded on first use and freed after a minute idle, like [LocalWhisper].
 */
object LocalVoice {
    private const val TAG = "UltraSpeak"
    const val VOICE = "vits-piper-en_US-norman-medium-int8"
    const val VOICE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$VOICE.tar.bz2"
    /** The archive as published on 2025-12-02; a changed file is refused, not unpacked. */
    const val VOICE_SHA256 = "cb481a514bc213ccf3899391c0f27fdcc4e4b814ec30496f28089a027b5aa01b"
    private const val MODEL = "en_US-norman-medium.onnx"
    private const val ARCHIVE_MIN_BYTES = 15_000_000L
    /** Unpacked it is ~40 MB; anything far past that is not this voice. */
    private const val MAX_UNPACKED_BYTES = 200_000_000L
    private const val IDLE_MS = 60_000L
    private const val THREADS = 2
    private const val RETRY_MS = 10 * 60_000L

    /** libsherpa-onnx-jni.so loaded. False on a build without it (e.g. the x86_64 bench build). */
    val available: Boolean by lazy {
        try { System.loadLibrary("sherpa-onnx-jni"); true }
        catch (e: Throwable) { android.util.Log.w(TAG, "own voice library not loadable: ${e.message}"); false }
    }

    private fun dir(context: Context) = File(context.filesDir, VOICE)

    fun voicePresent(context: Context): Boolean {
        val d = dir(context)
        return File(d, MODEL).length() >= 1_000_000L && File(d, "tokens.txt").isFile &&
            File(d, "espeak-ng-data").isDirectory
    }

    private val fetching = AtomicBoolean(false)
    @Volatile private var lastFailAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Start the download if it isn't there, running or recently failed. Returns at once. */
    fun fetchInBackground(context: Context) {
        val app = context.applicationContext
        if (voicePresent(app) || System.currentTimeMillis() - lastFailAt < RETRY_MS) return
        if (!fetching.compareAndSet(false, true)) return
        scope.launch {
            try { if (!fetch(app)) lastFailAt = System.currentTimeMillis() } finally { fetching.set(false) }
        }
    }

    /** One install at a time: the speak-time background fetch and Settings → DOWNLOADS share the
     *  archive, its .part and the unpack folder. A second caller waits, then finds the voice. */
    private val fetchLock = Any()

    /** Download, check and unpack the voice. Blocking; call off main. */
    fun fetch(context: Context): Boolean = synchronized(fetchLock) {
        if (voicePresent(context)) return true
        val files = context.filesDir
        val name = "$VOICE.tar.bz2"
        val archive = File(files, name)
        val tmp = File(files, "$VOICE.unpacking")
        return try {
            if (!ModelFetcher.ensure(files, name, VOICE_URL, ARCHIVE_MIN_BYTES)) return false
            if (sha256(archive) != VOICE_SHA256) {
                android.util.Log.w(TAG, "own voice download failed its checksum; discarded")
                archive.delete(); return false
            }
            tmp.deleteRecursively()
            extractTarBz2(archive, tmp)
            val unpacked = File(tmp, VOICE)
            val dest = dir(context)
            dest.deleteRecursively()
            if (!unpacked.isDirectory || !unpacked.renameTo(dest)) throw IOException("unexpected archive layout")
            archive.delete()
            android.util.Log.i(TAG, "own voice installed: $VOICE")
            voicePresent(context)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "own voice install failed: ${e.message}")
            false
        } finally {
            tmp.deleteRecursively()
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
    private var idle: Job? = null

    /** Speech for [text], or null when the voice can't be loaded or synthesis fails. */
    suspend fun generate(context: Context, text: String): GeneratedAudio? = lock.withLock {
        idle?.cancel()
        val r = try {
            val engine = tts ?: run {
                val d = dir(context)
                OfflineTts(config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = File(d, MODEL).absolutePath,
                            tokens = File(d, "tokens.txt").absolutePath,
                            dataDir = File(d, "espeak-ng-data").absolutePath,
                        ),
                        numThreads = THREADS,
                        debug = false,
                        provider = "cpu",
                    ),
                )).also { tts = it }
            }
            engine.generate(text, sid = 0, speed = 1.0f).takeIf { it.samples.isNotEmpty() && it.sampleRate > 0 }
        } catch (e: Throwable) {   // UnsatisfiedLinkError, or IllegalArgumentException from a bad voice dir
            android.util.Log.w(TAG, "own voice synthesis failed: ${e.message}")
            // The files are there but sherpa can't load them: remove them, so the next speak
            // fetches a fresh copy instead of retrying a broken voice on every answer.
            if (tts == null && e is IllegalArgumentException) dir(context).deleteRecursively()
            null
        }
        idle = scope.launch {
            delay(IDLE_MS)
            lock.withLock { tts?.free(); tts = null }
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
