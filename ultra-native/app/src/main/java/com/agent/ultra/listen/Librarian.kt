package com.agent.ultra.listen

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The small on-device model, as the librarian sees it. [complete] gets a complete prompt
 * (ChatML, see [LibrarianPrompt.build]) and returns the raw generated text. A failure means
 * "couldn't run now" (not loaded, busy, out of memory): the rows are tried again later.
 */
fun interface SmallModel {
    suspend fun complete(prompt: String, maxTokens: Int): Result<String>
}

/** The overheard rows still to organise. */
interface HeardSource {
    /** Up to [limit] rows not yet organised, in stored order. */
    fun unprocessed(limit: Int): List<HeardLine>
    /** Rows up to and including [id] are done. */
    fun markProcessed(id: Long)
}

/** Where notes go; [MemoryNotes] on the phone. */
interface NoteStore {
    /** Notes of [kind] that might repeat [text] (shared words), newest first. */
    fun candidates(kind: NoteKind, text: String, limit: Int): List<Note>
    fun insert(note: Note): Long
    fun update(note: Note)
}

/**
 * One librarian run over the queue. Pure (no Android), JVM-tested with a fake model.
 *
 * Takes up to [MAX_ROWS] unorganised rows older than [MIN_AGE_MS] (a conversation still going
 * on isn't cut in half), cuts them into chunks ([LibrarianChunker]), and for each of at most
 * [MAX_CHUNKS] chunks: asks the model, parses its notes ([NoteParser]), resolves their time
 * words against when they were said ([TimeResolver]), files each one as new or merged into a
 * duplicate ([NoteMerge]), then marks the chunk's rows done. Malformed output is still "done":
 * asking the same model the same thing again would give the same answer. A model failure stops
 * the run with the rows left in the queue; after [MAX_FAILURES] failures on the same chunk it is
 * skipped so one bad chunk can't block the queue for ever. [keepGoing] is checked before every
 * chunk (charging, screen, brain busy).
 */
object LibrarianRun {
    const val MAX_ROWS = 400
    const val MAX_CHUNKS = 8
    const val MIN_AGE_MS = 10 * 60_000L
    const val MAX_FAILURES = 3
    const val CANDIDATES = 50

    enum class Stop { DONE, CAP, GATE, MODEL_FAILED }

    data class Result(
        val chunks: Int, val added: Int, val merged: Int, val rejected: Int, val skipped: Int,
        val stop: Stop,
        /** More rows are waiting: run again soon. */
        val more: Boolean,
    )

    /** Consecutive model failures, keyed by the chunk's first row; lives as long as the process. */
    class Failures {
        private var rowId = -1L
        private var count = 0
        fun failed(firstRowId: Long): Int {
            if (firstRowId != rowId) { rowId = firstRowId; count = 0 }
            return ++count
        }
        fun clear() { rowId = -1L; count = 0 }
    }

    suspend fun run(
        source: HeardSource,
        store: NoteStore,
        model: SmallModel,
        zone: ZoneId,
        now: () -> Long,
        keepGoing: () -> Boolean,
        failures: Failures,
        log: (String) -> Unit = {},
    ): Result {
        val rows = source.unprocessed(MAX_ROWS)
        val cutoff = now() - MIN_AGE_MS
        val ready = rows.takeWhile { it.ts <= cutoff }
        val chunks = LibrarianChunker.chunks(ready)
        var done = 0; var added = 0; var merged = 0; var rejected = 0; var skipped = 0
        var stop = Stop.DONE
        for (chunk in chunks) {
            if (done + skipped >= MAX_CHUNKS) { stop = Stop.CAP; break }
            if (!keepGoing()) { stop = Stop.GATE; break }
            val last = chunk.maxOf { it.id }
            val out = try {
                model.complete(LibrarianPrompt.build(chunk, zone), LibrarianPrompt.MAX_TOKENS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                kotlin.Result.failure(e)
            }
            val text = out.getOrNull()
            if (text == null) {
                val n = failures.failed(chunk.first().id)
                log("model failed on rows ${chunk.first().id}..$last ($n/$MAX_FAILURES): ${out.exceptionOrNull()?.message}")
                if (n < MAX_FAILURES) { stop = Stop.MODEL_FAILED; break }
                failures.clear()
                source.markProcessed(last); skipped++
                continue
            }
            failures.clear()
            val parsed = NoteParser.parse(text)
            val (a, m) = file(parsed.notes, chunk, store, zone, now())
            added += a; merged += m; rejected += parsed.rejected
            source.markProcessed(last); done++
        }
        val more = stop == Stop.CAP || (stop == Stop.DONE && rows.size >= MAX_ROWS && ready.size == rows.size)
        return Result(done, added, merged, rejected, skipped, stop, more)
    }

    /** Files [drafts] from [chunk]: new notes inserted, duplicates merged. Returns (added, merged). */
    fun file(drafts: List<NoteDraft>, chunk: List<HeardLine>, store: NoteStore, zone: ZoneId, now: Long): Pair<Int, Int> {
        var added = 0; var merged = 0
        val ids = chunk.map { it.id }
        for (d in drafts) {
            val heard = LibrarianChunker.anchorFor(chunk, d.whenPhrase)
            val span = TimeResolver.resolve(d.whenPhrase, heard, zone)
            val fresh = Note(
                kind = d.kind, text = d.text, whenTs = span?.start, whenEnd = span?.end,
                heardTs = heard, sourceIds = ids, createdTs = now, updatedTs = now,
            )
            val dup = NoteMerge.findDuplicate(fresh, store.candidates(d.kind, d.text, CANDIDATES))
            if (dup != null) { store.update(NoteMerge.merge(dup, fresh, now)); merged++ }
            else { store.insert(fresh); added++ }
        }
        return added to merged
    }
}

/**
 * The librarian: while the phone is charging with the screen off and the big on-device brain
 * idle, the small model turns overheard rows into short dated notes ([MemoryNotes]) the big
 * model can search. WorkManager is not a dependency of this app, so it is a check every
 * [IDLE_CHECK_MS] inside the ambient listening service ([start]), which only exists while
 * there is something being heard.
 *
 * The coordinator sets [model]; with none set the librarian never runs.
 */
object Librarian {
    /** The small model. Null = no librarian. */
    @Volatile var model: SmallModel? = null

    /**
     * "Is the big brain busy?" Null = [com.agent.ultra.local.LocalModelEngine]'s busy flag. Set
     * this if the small model shares that flag: the librarian must not stop itself.
     */
    @Volatile var brainBusy: (() -> Boolean)? = null

    const val FIRST_CHECK_MS = 60_000L
    const val IDLE_CHECK_MS = 10 * 60_000L
    const val MORE_CHECK_MS = 60_000L
    const val WATCH_MS = 5_000L

    private const val TAG = "UltraLibrarian"
    private val failures = LibrarianRun.Failures()
    private var job: Job? = null

    /** Pure, JVM-tested: may the librarian work now? */
    fun mayRun(enabled: Boolean, hasModel: Boolean, charging: Boolean, screenOn: Boolean, brainBusy: Boolean): Boolean =
        enabled && hasModel && charging && !screenOn && !brainBusy

    /** Starts the periodic check in [scope] (the ambient service's); once per scope lifetime. */
    fun start(context: Context, scope: CoroutineScope) {
        val ctx = context.applicationContext
        synchronized(this) {
            if (job?.isActive == true) return
            // IO: every check and run does SQLite work (the service scope is Dispatchers.Default)
            job = scope.launch(Dispatchers.IO) {
                delay(FIRST_CHECK_MS)
                while (true) {
                    val more = try {
                        runIfAllowed(ctx)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "run failed: ${e.message}")
                        false
                    }
                    delay(if (more) MORE_CHECK_MS else IDLE_CHECK_MS)
                }
            }
        }
    }

    /** [mayRun] on the live state, cheapest checks first and short-circuited: the usual answer
     *  (no model, not charging, screen on) costs no engine lookup. */
    fun allowed(ctx: Context): Boolean {
        if (model == null || !AmbientConfig(ctx).organiseMemory || !charging(ctx)) return false
        if (ctx.getSystemService(PowerManager::class.java)?.isInteractive != false) return false
        val busy = brainBusy?.invoke() ?: com.agent.ultra.local.LocalModelEngine.shared(ctx).busy.value
        return mayRun(enabled = true, hasModel = true, charging = true, screenOn = false, brainBusy = busy)
    }

    /** One run if allowed; returns whether more rows are waiting. Stopped mid-call (cancelled)
     *  as soon as [allowed] turns false: the brain needs the cores, or the charger came out. */
    suspend fun runIfAllowed(ctx: Context): Boolean {
        if (!allowed(ctx)) return false
        val m = model ?: return false
        val mem = AmbientMemory.get(ctx)
        val source = object : HeardSource {
            override fun unprocessed(limit: Int): List<HeardLine> {
                var mark = mem.librarianMark
                if (mark > mem.maxRowId()) { mark = 0L; mem.librarianMark = 0L }   // rows were renumbered
                return mem.heardAfter(mark, limit)
            }
            override fun markProcessed(id: Long) { mem.librarianMark = id }
        }
        val result = coroutineScope {
            val work = async {
                LibrarianRun.run(
                    source, MemoryNotes.get(ctx), m, ZoneId.systemDefault(),
                    now = System::currentTimeMillis, keepGoing = { allowed(ctx) }, failures = failures,
                    log = { android.util.Log.w(TAG, it) },
                )
            }
            val watch = launch {
                while (true) {
                    delay(WATCH_MS)
                    if (!allowed(ctx)) { work.cancel(); break }
                }
            }
            try {
                work.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()   // the service stopped: rethrow
                android.util.Log.i(TAG, "stopped: no longer charging/idle")
                null
            } finally {
                watch.cancel()
            }
        } ?: return false
        android.util.Log.i(TAG, "run: chunks=${result.chunks} added=${result.added} merged=${result.merged} " +
            "rejected=${result.rejected} skipped=${result.skipped} stop=${result.stop}")
        return result.more
    }

    private fun charging(ctx: Context): Boolean = try {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    } catch (e: Exception) {
        ctx.getSystemService(BatteryManager::class.java)?.isCharging ?: false
    }
}
