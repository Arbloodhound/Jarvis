package com.agent.ultra.listen

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.PriorityQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "What do I remember about X?" over everything heard (and, through [extraSources], notes).
 *
 *   val hits = MemoryRecall.relevant(context, "what did Sam say about the dentist yesterday", now)
 *   val block = MemoryRecall.brief(hits)                        // "" when nothing was found
 *
 * Heard lines are found three ways and scored together by [RecallScore]: (a) meaning — cosine
 * between the query's sentence vector and each stored line vector ([SentenceEmbedder], brute
 * force over the newest [SCAN_LIMIT] vectors plus the asked-for window), (b) words — FTS4
 * prefix match on the query's content words, (c) recency, and (d) the time window parsed
 * from the question by [TimeWindow]. With no embedder (not downloaded yet, not loaded yet, or
 * ONNX failed) (a) is skipped, the background indexer is kicked, and the rest still works.
 * A question never loads the model or downloads anything inline.
 *
 * Expected size: ambient keeps ≤ 90 days / 250k lines; real speech is likely a few hundred
 * to a few thousand lines a day, so N ≈ 10k–100k vectors. A full scan of [SCAN_LIMIT] (40k ×
 * 388 B ≈ 15 MB read, 15M int8 multiply-adds) is the cost of one query; older lines are still
 * reachable by keyword and by a time window. NOT measured on a phone.
 */
object MemoryRecall {
    const val SOURCE_HEARD = "heard"
    const val SCAN_LIMIT = 40_000
    private const val TAG = "UltraMemory"
    private const val SEM_TOP = 48
    private const val KEYWORD_ROWS = 100
    private const val WINDOW_ROWS = 200

    /** More places to remember from (e.g. notes); each is asked in turn, failures skipped.
     * One line to add the librarian's notes:
     * `MemoryRecall.extraSources += MemoryRecall.textSource("notes") { q, n -> MemoryNotes.get(ctx).search(q, n).map { it.ts to it.text } }` */
    val extraSources: MutableList<RecallSource> = CopyOnWriteArrayList()

    /**
     * A [RecallSource] over a plain search returning (ts, text) pairs: its hits are scored by
     * keyword overlap (at least [RecallScore.SOURCE_FLOOR]), recency and the time window, on
     * the same scale as heard lines.
     */
    fun textSource(name: String, search: suspend (query: String, limit: Int) -> List<Pair<Long, String>>) =
        RecallSource { query, window, nowMs, limit ->
            val terms = RecallScore.terms(query)
            search(query, limit).mapNotNull { (ts, text) ->
                RecallScore.sourceScore(terms, text, ts, nowMs, window)?.let { RecallHit(ts, text, it, name) }
            }
        }

    /** Up to [limit] hits, best first. Never throws (a failing source just contributes
     * nothing). Runs on Dispatchers.IO. */
    suspend fun relevant(context: Context, query: String, nowMs: Long, limit: Int = 5): List<RecallHit> =
        withContext(Dispatchers.IO) {
            val ctx = context.applicationContext
            val window = TimeWindow.parse(query, nowMs)
            val stripped = TimeWindow.strip(query, window).trim()
            val all = ArrayList<RecallHit>()
            try { all += heard(ctx, stripped, window, nowMs, limit) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w(TAG, "recall (heard) failed: ${e.message}") }
            for (s in extraSources) {
                try { all += s.search(stripped, window, nowMs, limit) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.w(TAG, "recall source ${s.javaClass.simpleName} failed: ${e.message}") }
            }
            RecallScore.merge(all, limit)
        }

    /** A compact dated "What I remember (with times…)" block for a prompt; "" for no hits. */
    fun brief(hits: List<RecallHit>, maxChars: Int = 600): String = RecallScore.brief(hits, maxChars)

    private fun heard(ctx: Context, query: String, window: TimeWindow?, now: Long, limit: Int): List<RecallHit> {
        val mem = AmbientMemory.get(ctx)
        val terms = RecallScore.terms(query)
        val hasTerms = terms.isNotEmpty()
        val rows = LinkedHashMap<Long, AmbientMemory.Row>()
        fun add(list: List<AmbientMemory.Row>) { for (r in list) rows.putIfAbsent(r.rowid, r) }

        if (hasTerms) {
            val fts = RecallScore.ftsQuery(terms)
            if (window != null) add(mem.keywordRows(fts, window, KEYWORD_ROWS))
            add(mem.keywordRows(fts, null, KEYWORD_ROWS))
        }
        if (window != null) add(mem.windowRows(window.fromMs, window.toMs, if (hasTerms) KEYWORD_ROWS else WINDOW_ROWS))
        else if (!hasTerms) add(mem.newest(limit * 3))

        val cos = HashMap<Long, Float>()
        val emb = if (hasTerms) SentenceEmbedder.loaded() else null
        if (hasTerms && emb == null) MemoryIndexer.kick(ctx)      // fetches + loads in the background
        if (emb != null) try {
            // Just-said lines without a vector yet — but not while the brain is generating
            // (the indexer stays off then too); they are still found by keyword.
            if (!com.agent.ultra.local.LocalModelEngine.shared(ctx).busy.value) MemoryIndexer.catchUp(mem, emb)
            val q = emb.embed(query)
            // Only the best SEM_TOP survive the scan; the window pass and the rest-of-history
            // pass don't overlap, so no id is seen twice.
            val top = PriorityQueue<Pair<Long, Float>>(compareBy { it.second })
            fun consider(id: Long, v: ByteArray) {
                val c = MemoryVectors.cosine(q, v) ?: return
                if (top.size >= SEM_TOP && c <= top.peek()!!.second) return
                top.add(id to c)
                if (top.size > SEM_TOP) top.poll()
            }
            if (window != null) mem.forEachVector(window, SCAN_LIMIT, each = ::consider)
            mem.forEachVector(null, SCAN_LIMIT, excluding = window, each = ::consider)
            for ((id, c) in top) cos[id] = c
            add(mem.byIds(top.map { it.first }.filter { it !in rows }))
            val missing = rows.keys.filter { it !in cos }
            for ((id, v) in mem.vectorsFor(missing)) MemoryVectors.cosine(q, v)?.let { cos[id] = it }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "recall: semantic part failed (${e.message}); keyword + time only")
            cos.clear()
        }

        val halfLife = window?.let { (it.toMs - it.fromMs).coerceAtLeast(3_600_000L) } ?: RecallScore.HALF_LIFE_MS
        return rows.values.mapNotNull { r ->
            val s = RecallScore.score(
                cosine = cos[r.rowid],
                keyword = RecallScore.keywordFraction(terms, r.text),
                recency = RecallScore.recency(r.ts, now, halfLife),
                inWindow = window?.contains(r.ts),
                hasTerms = hasTerms,
            ) ?: return@mapNotNull null
            RecallHit(r.ts, r.text, s, SOURCE_HEARD)
        }.sortedByDescending { it.score }.take(limit * 2)
    }
}
