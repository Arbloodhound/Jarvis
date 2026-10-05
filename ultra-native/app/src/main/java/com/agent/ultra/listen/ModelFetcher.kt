package com.agent.ultra.listen

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * First-run download of the ambient models into filesDir. Before this existed the
 * URLs in AmbientConfig were never used: on a fresh install the service found no
 * whisper model, nativeInit returned 0 and the service stopped without a word.
 *
 * Atomic: writes <name>.part, renames on success, so a dropped connection never
 * leaves a truncated model that whisper/ONNX would choke on. Blocking; call off main.
 */
object ModelFetcher {
    fun ensure(dir: File, name: String, url: String, minBytes: Long = 1_000L): Boolean {
        val dest = File(dir, name)
        if (dest.exists() && dest.length() >= minBytes) return true
        val part = File(dir, "$name.part")
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            }
            try {
                if (c.responseCode !in 200..299) return false
                c.inputStream.use { inp -> part.outputStream().use { inp.copyTo(it, 64 * 1024) } }
            } finally { c.disconnect() }
            if (part.length() < minBytes) { part.delete(); return false }
            part.renameTo(dest)
        } catch (e: Exception) {
            android.util.Log.w("UltraAmbient", "fetch $name failed: ${e.message}")
            part.delete(); false
        }
    }
}
