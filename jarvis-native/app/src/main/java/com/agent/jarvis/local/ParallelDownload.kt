package com.agent.jarvis.local

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * A model file fetched over several connections at once (owner, 2026-10-07: "a way to increase
 * download speed of the models"). One connection to a CDN is often held well below what the phone's
 * Wi-Fi can take; [CONNECTIONS] ranges of the same file in parallel add up. Each connection writes
 * its own byte range straight into the one `.part` file (no second copy on disk).
 *
 * Resumable: `<file>.part.plan` records each range and how much of it is written (saved only after
 * the bytes are synced), so a dropped connection or a killed app continues where it stopped. A
 * `.part` left by the old one-connection download becomes the first, finished range. A server that
 * doesn't answer a range request with exactly that range is never trusted; the caller then falls
 * back to the one-connection download. The plan logic is pure and JVM-tested.
 */
object ParallelDownload {
    const val CONNECTIONS = 4
    /** Below this the extra connections aren't worth it. */
    const val MIN_PARALLEL_BYTES = 200L * 1_048_576
    const val PLAN_SUFFIX = ".plan"

    /** One byte range [start, end) and how much of it is written. */
    data class Seg(val start: Long, val end: Long, val done: Long) {
        val length get() = end - start
        val finished get() = done >= length
    }

    data class Plan(val total: Long, val segs: List<Seg>) {
        val written get() = segs.sumOf { minOf(it.done, it.length) }
        val finished get() = segs.all { it.finished }
    }

    /** A fresh plan for a [total]-byte file whose first [have] bytes are already in the .part. */
    fun plan(total: Long, have: Long, connections: Int = CONNECTIONS): Plan {
        val h = have.coerceIn(0, total)
        val segs = ArrayList<Seg>()
        if (h > 0) segs += Seg(0, h, h)
        val rest = total - h
        val n = connections.coerceAtLeast(1)
        if (rest > 0) {
            val size = (rest + n - 1) / n
            var s = h
            while (s < total) { val e = minOf(total, s + size); segs += Seg(s, e, 0); s = e }
        }
        return Plan(total, segs)
    }

    fun encode(p: Plan): String = buildString {
        append("total ").append(p.total).append('\n')
        for (s in p.segs) append(s.start).append(' ').append(s.end).append(' ').append(s.done).append('\n')
    }

    /** The plan written by [encode], or null if it isn't a consistent plan for a [total]-byte file. */
    fun decode(text: String, total: Long): Plan? {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty() || lines[0] != "total $total") return null
        val segs = lines.drop(1).map { l ->
            val p = l.trim().split(' ').mapNotNull { it.toLongOrNull() }
            if (p.size != 3) return null
            Seg(p[0], p[1], p[2])
        }
        // Contiguous from 0 to total, nothing claimed past its range.
        var at = 0L
        for (s in segs) {
            if (s.start != at || s.end <= s.start || s.done < 0 || s.done > s.length) return null
            at = s.end
        }
        return if (at == total) Plan(total, segs) else null
    }

    /** "bytes a-b/total" names exactly this range of this file. */
    fun rangeMatches(contentRange: String?, start: Long, endInclusive: Long, total: Long): Boolean =
        contentRange?.trim() == "bytes $start-$endInclusive/$total"

    /** The file's size from a "bytes 0-0/N" probe answer, or null when ranges aren't served. */
    fun totalFromProbe(code: Int, contentRange: String?): Long? {
        if (code != 206) return null
        val m = Regex("^bytes 0-0/(\\d+)$").find(contentRange?.trim().orEmpty()) ?: return null
        return m.groupValues[1].toLongOrNull()?.takeIf { it > 0 }
    }

    fun planFile(part: File) = File(part.path + PLAN_SUFFIX)

    /** Bytes really written to [part] (its plan's count when it has one; the file length otherwise). */
    fun bytesSoFar(part: File): Long {
        val pf = planFile(part)
        if (!pf.exists()) return part.length()
        val total = pf.readLines().firstOrNull()?.removePrefix("total ")?.toLongOrNull() ?: return 0
        return decode(pf.readText(), total)?.written ?: 0
    }

    /**
     * Download [url] ([total] bytes) into [part] over [CONNECTIONS] connections, continuing any plan
     * on disk. Returns when [part] holds every byte (its plan removed); throws IOException when a
     * connection fails (progress kept for the next call). [onProgress] gets bytes written so far.
     */
    suspend fun run(
        client: okhttp3.OkHttpClient, url: String, part: File, total: Long, onProgress: (Long) -> Unit,
    ) = kotlinx.coroutines.coroutineScope {
        val pf = planFile(part)
        val existing = if (pf.exists()) decode(pf.readText(), total) else null
        // A plan that doesn't fit this file (it changed on the server): its .part is sparse and stale.
        if (pf.exists() && existing == null) { pf.delete(); part.delete() }
        // No plan: what the .part holds came from the one-connection download, in order from byte 0.
        val plan = existing ?: plan(total, if (part.exists()) minOf(part.length(), total) else 0L)
        val done = LongArray(plan.segs.size) { plan.segs[it].done }
        val raf = RandomAccessFile(part, "rw")
        val lock = Any()
        fun save() {
            // Counts first, then sync: every byte counted was written before the count was taken,
            // so it is on disk before the plan that counts it.
            val snap = synchronized(lock) { done.copyOf() }
            raf.fd.sync()
            val tmp = File(pf.path + ".tmp")
            tmp.writeText(encode(Plan(total, plan.segs.mapIndexed { i, s -> s.copy(done = snap[i]) })))
            tmp.renameTo(pf)
        }
        try {
            if (existing == null) save()
            val saver = launch(kotlinx.coroutines.Dispatchers.IO) {
                while (true) {
                    kotlinx.coroutines.delay(2_000)
                    save()
                    val snap = synchronized(lock) { done.copyOf() }
                    onProgress(snap.indices.sumOf { minOf(snap[it], plan.segs[it].length) })
                }
            }
            try {
                plan.segs.indices.filter { !plan.segs[it].finished }.map { i ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        val s = plan.segs[i]
                        val from = s.start + done[i]
                        val last = s.end - 1
                        val req = okhttp3.Request.Builder().url(url).header("Range", "bytes=$from-$last").build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.code != 206 || !rangeMatches(resp.header("Content-Range"), from, last, total))
                                throw IOException("range $from-$last answered ${resp.code} ${resp.header("Content-Range")}")
                            val ch = RandomAccessFile(part, "rw")
                            try {
                                resp.body!!.byteStream().use { input ->
                                    val buf = ByteArray(256 * 1024)
                                    var pos = from
                                    while (pos <= last) {
                                        val n = input.read(buf, 0, minOf(buf.size.toLong(), last - pos + 1).toInt())
                                        if (n < 0) break
                                        synchronized(ch) { ch.seek(pos); ch.write(buf, 0, n) }
                                        pos += n
                                        synchronized(lock) { done[i] = pos - s.start }
                                    }
                                    if (pos <= last) throw IOException("range $from-$last ended early at $pos")
                                }
                                ch.fd.sync()
                            } finally { ch.close() }
                        }
                    }
                }.awaitAll()
            } finally {
                saver.cancel()
                save()
            }
            if (raf.length() > total) raf.setLength(total)
        } finally {
            raf.close()
        }
        onProgress(total)
        if (part.length() != total) throw IOException("download is ${part.length()} bytes, expected $total")
        pf.delete()
    }
}
