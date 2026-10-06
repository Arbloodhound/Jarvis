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
 *   MemoryRecall.init(context)                                  // once; idempotent
 *   val hits = MemoryRecall.relevant("what did Sam say about the dentist yesterday", now)
 *   val block = MemoryRecall.brief(hits)                        // "" when nothing was found
 *
 * Heard lines are found three ways and scored together by [RecallScore]: (a) meaning — cosine
 * between the query's sentence vector and each stored line vector ([SentenceEmbedder], brute
 * force over the newest [SCAN_LIMIT] vectors plus the asked-for window), (b) words — FTS4
 * prefix match on the query's content words, (c) recency, and (d) the time window parsed
 * from the question by [TimeWindow]. With no embedder (not downloaded yet, or ONNX failed)
 * (a) is skipped and the rest still work.
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

    /** More places to remember from (e.g. notes); each is asked in turn, failures skipped. */
    val extraSources: MutableList<RecallSource> = CopyOnWriteArrayList()

    @Volatile private var app: Context? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** Up to [limit] hits, best first. Never throws (a failing source just contributes nothing). */
    suspend fun relevant(query: String, now: Long, limit: Int = 6): List<Hit> = withContext(Dispatchers.IO) {
        val window = TimeWindow.parse(query, now)
        val stripped = TimeWindow.strip(query, window).trim()
        val all = ArrayList<Hit>()
        app?.let { ctx ->
            try { all += heard(ctx, stripped, window, now, limit) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w(TAG, "recall (heard) failed: ${e.message}") }
        }
        for (s in extraSources) {
            try { all += s.search(stripped, window, limit) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w(TAG, "recall source ${s.javaClass.simpleName} failed: ${e.message}") }
        }
        RecallScore.merge(all, limit)
    }

    /** A compact dated "What I remember" block for a prompt; "" for no hits. */
    fun brief(hits: List<Hit>, maxChars: Int = 800): String = RecallScore.brief(hits, maxChars)

    private fun heard(ctx: Context, query: String, window: TimeWindow?, now: Long, limit: Int): List<Hit> {
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
        val emb = if (hasTerms) SentenceEmbedder.shared(ctx) else null
        if (hasTerms && emb == null) MemoryIndexer.kick(ctx)      // fetches + loads in the background
        if (emb != null) try {
            MemoryIndexer.catchUp(mem, emb)
            val q = emb.embed(query)
            val top = PriorityQueue<Pair<Long, Float>>(compareBy { it.second })
            fun consider(id: Long, v: ByteArray) {
                val c = MemoryVectors.cosine(q, v) ?: return
                cos[id] = c
                top.add(id to c)
                if (top.size > SEM_TOP) top.poll()
            }
            if (window != null) mem.forEachVector(window, SCAN_LIMIT, ::consider)
            mem.forEachVector(null, SCAN_LIMIT, ::consider)
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
            Hit(r.text, r.ts, s, SOURCE_HEARD)
        }.sortedByDescending { it.score }.take(limit * 2)
    }
}
