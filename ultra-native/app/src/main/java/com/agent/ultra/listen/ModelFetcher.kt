package com.agent.ultra.listen

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * First-run download of the ambient models into filesDir. Before this existed the
 * URLs in AmbientConfig were never used: on a fresh install the service found no
 * whisper model, nativeInit returned 0 and the service stopped without a word.
 *
 * Atomic: writes <name>.part, renames on success, so a dropped connection never
 * leaves a truncated model that whisper/ONNX would choke on. With [ensure]'s exactBytes
 * (the published size), a dropped connection keeps the .part and the next call resumes
 * it with a Range request (a server that ignores Range answers 200 and the file starts
 * over); without it (minBytes-only callers, which can't tell a spliced file from a good
 * one) a failed download is discarded as before. Blocking; call off main.
 */
object ModelFetcher {
    /**
     * True when [dir]/[name] is present. [minBytes]: anything smaller is incomplete.
     * [exactBytes] (> 0): the published size; a result of any other size is discarded.
     */
    fun ensure(dir: File, name: String, url: String, minBytes: Long = 1_000L, exactBytes: Long = -1L): Boolean =
        // One writer per file: Settings → DOWNLOADS, the ambient service and the chat mic can all
        // ask for the same model; two of them appending to one .part would splice it. A second
        // caller waits, then finds the finished file.
        synchronized(locks.computeIfAbsent(File(dir, name).absolutePath) { Any() }) {
            ensureLocked(dir, name, url, minBytes, exactBytes)
        }

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun ensureLocked(dir: File, name: String, url: String, minBytes: Long, exactBytes: Long): Boolean {
        val dest = File(dir, name)
        if (dest.exists() && complete(dest.length(), minBytes, exactBytes)) return true
        val part = File(dir, "$name.part")
        val resumable = exactBytes > 0
        if (!resumable || part.length() > exactBytes) part.delete()
        return try {
            val have = if (part.exists()) part.length() else 0L
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = c.responseCode
                when {
                    // Already have every byte (only possible with a Range request).
                    code == 416 && have > 0 && complete(have, minBytes, exactBytes) -> {}
                    // Append only if the server really resumed at our offset; any other 206
                    // (no / different Content-Range) would splice the wrong bytes in.
                    code == 206 && have > 0 -> {
                        if (!resumesAt(c.getHeaderField("Content-Range"), have, exactBytes)) {
                            part.delete()
                            return false
                        }
                        c.inputStream.use { inp -> FileOutputStream(part, true).use { inp.copyTo(it, 64 * 1024) } }
                    }
                    code in 200..299 ->
                        c.inputStream.use { inp -> FileOutputStream(part, false).use { inp.copyTo(it, 64 * 1024) } }
                    else -> {
                        if (code == 416) part.delete()   // a .part the server doesn't recognise
                        return false
                    }
                }
            } finally { c.disconnect() }
            if (!complete(part.length(), minBytes, exactBytes)) {
                // Short = keep for the next resume; any other wrong size = bad, start over.
                if (exactBytes > 0 && part.length() > exactBytes || exactBytes <= 0) part.delete()
                return false
            }
            part.renameTo(dest)
        } catch (e: Exception) {
            if (!resumable) part.delete()
            android.util.Log.w("UltraAmbient", "fetch $name failed (kept ${part.length()} B to resume): ${e.message}")
            false
        }
    }

    /** "bytes 1000-2999/3000" starts at [offset] and, when [total] > 0 and the server states
     * one (it may send a star), the whole file is [total] bytes (else it's a different file). */
    internal fun resumesAt(contentRange: String?, offset: Long, total: Long = -1L): Boolean {
        val m = contentRange?.trim()?.let { Regex("^bytes (\\d+)-\\d+/(\\d+|\\*)$").find(it) } ?: return false
        if (m.groupValues[1].toLongOrNull() != offset) return false
        val t = m.groupValues[2].toLongOrNull()
        return total <= 0 || t == null || t == total
    }

    internal fun complete(len: Long, minBytes: Long, exactBytes: Long): Boolean =
        if (exactBytes > 0) len == exactBytes else len >= minBytes
}
