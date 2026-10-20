package com.agent.ultra.listen

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fills heard_vec: one sentence vector per heard line, so [MemoryRecall] can search by meaning.
 *
 * [kick] starts one background pass (never two at once): download the embedder if it isn't
 * there yet (~23 MB, resumable), then walk heard rows oldest → newest from the stored
 * high-water mark, 16 at a time. A pass stops as soon as the brain is generating or the phone
 * is past NORMAL thermal state; the next kick carries on where it stopped. Kicked by the
 * ambient service hourly (with prune) and by [MemoryRecall] when it finds no embedder.
 * [catchUp] embeds the newest few lines inline, so something said a minute ago is findable
 * even while the backlog is still being worked through.
 */
object MemoryIndexer {
    private const val TAG = "UltraMemory"
    private const val BATCH = 16
    private const val PAUSE_MS = 50L
    private const val FETCH_RETRY_MS = 3 * 3_600_000L

    @Volatile private var fetchFailedAt = 0L

    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                val n = pass(app)
                if (n > 0) android.util.Log.i(TAG, "indexer: embedded $n lines")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "indexer pass failed: ${e.message}")
            } finally { running.set(false) }
        }
    }

    private suspend fun pass(ctx: Context): Int {
        val mem = AmbientMemory.get(ctx)
        if (mem.maxRowid() == 0L) return 0              // nothing heard: don't download for nothing
        if (!SentenceEmbedder.downloaded(ctx)) {
            // ~23 MB: only on an unmetered network, and a failed download backs off 3 h, 6 h,
            // 12 h … up to a week, across restarts too (a .part resumes; a size that no longer
            // matches the Hub would otherwise re-download on every kick).
            if (!unmetered(ctx)) return 0
            val now = System.currentTimeMillis()
            val fails = mem.metaGet(AmbientMemory.META_VEC_FETCH_FAILS) ?: 0L
            val failedAt = maxOf(fetchFailedAt, mem.metaGet(AmbientMemory.META_VEC_FETCH_FAIL) ?: 0L)
            if (fails > 0 && now - failedAt in 0 until fetchBackoffMs(fails)) return 0
            if (!SentenceEmbedder.fetch(ctx)) {
                fetchFailedAt = System.currentTimeMillis()
                mem.metaPut(AmbientMemory.META_VEC_FETCH_FAIL, fetchFailedAt)
                mem.metaPut(AmbientMemory.META_VEC_FETCH_FAILS, fails + 1)
                android.util.Log.w(TAG, "indexer: embedder download failed (${fails + 1}x); semantic recall off, keyword recall still on")
                return 0
            }
            mem.metaPut(AmbientMemory.META_VEC_FETCH_FAILS, 0L)
        }
        val emb = SentenceEmbedder.shared(ctx) ?: return 0
        // either model (thinker or librarian) generating: leave the CPU to it
        val busy = com.agent.ultra.local.LocalModelEngine.anyBusy
        // Read the generation BEFORE the mark: a wipe in between bumps it and our writes are refused.
        val gen = mem.generation()
        var hw = mem.metaGet(AmbientMemory.META_VEC_HW) ?: 0L
        if (mem.maxRowid() < hw) hw = 0L                // defensive: rowids restarted
        var done = 0
        while (true) {
            if (busy.value) return done
            if (com.agent.ultra.agent.ThermalGuard.level != com.agent.ultra.agent.ThermalPolicy.Level.NORMAL) return done
            val rows = mem.rowsAfter(hw, BATCH)
            if (rows.isEmpty()) return done
            val have = mem.hasVector(rows.map { it.rowid })
            val todo = rows.filter { it.rowid !in have }
            val vecs = if (todo.isEmpty()) emptyList() else embedAll(emb, todo)
            if (!mem.putVectors(todo, vecs, gen, newHw = rows.last().rowid)) {
                android.util.Log.i(TAG, "indexer: memory was erased mid-pass; stopping")
                return done
            }
            done += todo.size
            hw = rows.last().rowid
            delay(PAUSE_MS)
        }
    }

    /** 3 h × 2^(fails−1), at most a week. Pure. */
    internal fun fetchBackoffMs(fails: Long): Long {
        if (fails <= 0) return 0L
        val week = 7 * 86_400_000L
        return if (fails > 12) week else minOf(week, FETCH_RETRY_MS shl (fails - 1).toInt())
    }

    private fun unmetered(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        cm.activeNetwork != null && !cm.isActiveNetworkMetered
    } catch (_: Exception) { false }

    /** Embeds the newest [n] lines that have no vector yet (inline, from recall). */
    fun catchUp(mem: AmbientMemory, emb: SentenceEmbedder, n: Int = 16) {
        val gen = mem.generation()
        val rows = mem.newest(n)
        if (rows.isEmpty()) return
        val have = mem.hasVector(rows.map { it.rowid })
        val todo = rows.filter { it.rowid !in have }
        if (todo.isNotEmpty()) mem.putVectors(todo, embedAll(emb, todo), gen)
    }

    /** Quantised vectors for [rows]; if the batch fails, one by one, and a line that still
     * fails gets the empty "failed" marker so it is never retried or scored. */
    private fun embedAll(emb: SentenceEmbedder, rows: List<AmbientMemory.Row>): List<ByteArray> = try {
        emb.embed(rows.map { it.text }).map(MemoryVectors::quantize)
    } catch (e: Exception) {
        android.util.Log.w(TAG, "indexer: batch of ${rows.size} failed (${e.message}); one by one")
        rows.map { r -> try { MemoryVectors.quantize(emb.embed(r.text)) } catch (_: Exception) { ByteArray(0) } }
    }
}
