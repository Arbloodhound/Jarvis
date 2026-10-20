package com.agent.jarvis.listen

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
 *
 * Must be cancellable: when the screen comes on, the charger comes out or the big brain needs
 * the cores, the librarian cancels the call and waits for it to return, so a blocking native
 * generation loop has to check for cancellation (e.g. per token) and stop.
 */
fun interface SmallModel {
    suspend fun complete(prompt: String, maxTokens: Int): Result<String>
}

/** The overheard rows still to organise. */
interface HeardSource {
    /** Up to [limit] rows not yet organised, in stored order. */
    fun unprocessed(limit: Int): List<HeardLine>
    /** Rows up to and including [id] are done; [ts] is the newest ts among them. */
    fun markProcessed(id: Long, ts: Long)
}

/** Where notes go; [MemoryNotes] on the phone. */
interface NoteStore {
    /** Notes of [kind] that might repeat [text] (shared words), newest first. */
    fun candidates(kind: NoteKind, text: String, limit: Int): List<Note>
    fun insert(note: Note): Long
    fun update(note: Note)
    /** Notes of [kind] (null = any) created at or after [ts], newest first ([LibrarianLinks]). */
    fun createdSince(ts: Long, kind: NoteKind?, limit: Int): List<Note> = emptyList()
    /** Notes sharing words with [text], any kind, created before [beforeTs], best first ([LibrarianLinks]). */
    fun related(text: String, beforeTs: Long, limit: Int): List<Note> = emptyList()
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
 *
 * With [patterns] the prompt carries the observable-pattern taxonomy ([CuePattern]) and the
 * parser checks each observation's quotes against the chunk. With [readings] too, a chunk that
 * produced observations gets a second, short call ([ReadingPrompt]) for hedged possible
 * readings; if that call fails the observations are filed without readings (not retried).
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
        /** Observation notes filed, and how many of them carry a possible reading. */
        val observations: Int = 0,
        val readings: Int = 0,
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
        patterns: Boolean = true,
        readings: Boolean = true,
        minAgeMs: Long = MIN_AGE_MS,
        /** Try to store a durable draft as a verified claim; true when stored (so it is NOT also
         *  written as a flat note). Default no-op → everything stays a note (unchanged behaviour). */
        promote: suspend (NoteDraft) -> Boolean = { false },
    ): Result {
        val fetched = source.unprocessed(MAX_ROWS + 1)   // one extra: is there more after this batch?
        val rows = fetched.take(MAX_ROWS)
        val cutoff = now() - minAgeMs
        val ready = rows.takeWhile { it.ts <= cutoff }
        var chunks = LibrarianChunker.chunks(ready)
        // The row after the last ready one (too young, or past the batch) continues the same
        // conversation: hold the last chunk back so a plan and its time aren't split. Never hold
        // back the only chunk of a full batch (it would never move).
        val next = fetched.getOrNull(ready.size)
        if (next != null && chunks.isNotEmpty() &&
            next.ts - chunks.last().last().ts <= LibrarianChunker.MAX_GAP_MS &&
            (next.ts > cutoff || chunks.size > 1)
        ) chunks = chunks.dropLast(1)
        var done = 0; var added = 0; var merged = 0; var rejected = 0; var skipped = 0
        var observations = 0; var withReading = 0
        var stop = Stop.DONE
        for (chunk in chunks) {
            if (done + skipped >= MAX_CHUNKS) { stop = Stop.CAP; break }
            if (!keepGoing()) { stop = Stop.GATE; break }
            val last = chunk.maxOf { it.id }
            val lastTs = chunk.maxOf { it.ts }
            val out = try {
                model.complete(LibrarianPrompt.build(chunk, zone, patterns), LibrarianPrompt.maxTokens(patterns))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                kotlin.Result.failure(e)
            }
            // Cancelled mid-call (screen on, charger out, brain busy, service stopped) is not a
            // model failure, even when the model turned it into Result.failure (runCatching):
            // the rows stay queued and nothing counts towards skipping them. A model's own
            // timeout while we are still active does count.
            currentCoroutineContext().ensureActive()
            val text = out.getOrNull()
            if (text == null) {
                val n = failures.failed(chunk.first().id)
                log("model failed on rows ${chunk.first().id}..$last ($n/$MAX_FAILURES): ${out.exceptionOrNull()?.message}")
                if (n < MAX_FAILURES) { stop = Stop.MODEL_FAILED; break }
                failures.clear()
                source.markProcessed(last, lastTs); skipped++
                continue
            }
            failures.clear()
            val parsed = NoteParser.parse(text, chunk)
            rejected += parsed.rejected
            var drafts = when {
                !patterns -> parsed.notes.filter { it.obs == null }
                !readings -> parsed.notes.map { d -> d.obs?.let { d.copy(obs = it.copy(reading = null)) } ?: d }
                else -> parsed.notes
            }
            // Second, short pass: possible readings, only for observations that came without one.
            val bare = drafts.indices.filter { drafts[it].obs != null && drafts[it].obs?.reading == null }.take(ReadingPrompt.MAX_OBS)
            if (patterns && readings && bare.isNotEmpty() && keepGoing()) {
                val list = bare.map { drafts[it].text to drafts[it].obs!! }
                val rOut = try {
                    model.complete(ReadingPrompt.build(list), ReadingPrompt.MAX_TOKENS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    kotlin.Result.failure(e)
                }
                currentCoroutineContext().ensureActive()
                val rText = rOut.getOrNull()
                if (rText == null) log("reading pass failed on rows ${chunk.first().id}..$last: ${rOut.exceptionOrNull()?.message}")
                else {
                    val (found, rRejected) = NoteParser.parseReadings(rText, list.size)
                    rejected += rRejected
                    val byDraft = found.mapKeys { bare[it.key] }
                    drafts = drafts.mapIndexed { i, d ->
                        byDraft[i]?.let { r -> d.copy(obs = d.obs!!.copy(reading = r)) } ?: d
                    }
                }
            }
            observations += drafts.count { it.obs != null }
            withReading += drafts.count { it.obs?.reading != null }
            // A durable draft (fact / person) goes to the verified claim store, not a flat note; if
            // the claim is rejected it falls back to a note, so nothing is lost. Everything else is a
            // note as before.
            val toFile = ArrayList<NoteDraft>(drafts.size)
            for (d in drafts) {
                val stored = if (ClaimKind.promotes(d.kind)) {
                    currentCoroutineContext().ensureActive()
                    try { promote(d) } catch (e: CancellationException) { throw e } catch (e: Exception) { log("promote failed: ${e.message}"); false }
                } else false
                if (!stored) toFile += d
            }
            val (a, m) = file(toFile, chunk, store, zone, now())
            added += a; merged += m
            source.markProcessed(last, lastTs); done++
        }
        val more = stop == Stop.CAP || (stop == Stop.DONE && fetched.size > MAX_ROWS && ready.size == rows.size)
        return Result(done, added, merged, rejected, skipped, stop, more, observations, withReading)
    }

    /** Files [drafts] from [chunk]: new notes inserted, duplicates merged. Returns (added, merged).
     *  Durable drafts already stored as verified claims are filtered out before this (see [run]), so
     *  a fact lives as a claim OR a note, never both. */
    fun file(drafts: List<NoteDraft>, chunk: List<HeardLine>, store: NoteStore, zone: ZoneId, now: Long): Pair<Int, Int> {
        var added = 0; var merged = 0
        val ids = chunk.map { it.id }
        for (d in drafts) {
            if (d.obs != null) {
                // Observations keep their own evidence and are never merged: a pattern seen again
                // is a second data point, not a duplicate.
                store.insert(Note(
                    kind = NoteKind.OBSERVATION, text = ObservationText.render(d.text, d.obs, zone),
                    whenTs = null, whenEnd = null, heardTs = d.obs.quoteTs.minOrNull() ?: chunk.first().ts,
                    sourceIds = ids, createdTs = now, updatedTs = now, who = d.obs.who,
                ))
                added++
                continue
            }
            if (d.kind == NoteKind.SUMMARY) {
                // One per conversation: two talks that sound alike are still two talks.
                store.insert(Note(
                    kind = NoteKind.SUMMARY, text = d.text, whenTs = null, whenEnd = null, heardTs = chunk.first().ts,
                    sourceIds = ids, createdTs = now, updatedTs = now, who = d.who, tags = d.tags,
                ))
                added++
                continue
            }
            val heard = LibrarianChunker.anchorFor(chunk, d.whenPhrase)
            val span = TimeResolver.resolve(d.whenPhrase, heard, zone)
            val fresh = Note(
                kind = d.kind, text = d.text, whenTs = span?.start, whenEnd = span?.end,
                heardTs = heard, sourceIds = ids, createdTs = now, updatedTs = now, who = d.who, tags = d.tags,
            )
            val dup = NoteMerge.findDuplicate(fresh, store.candidates(d.kind, d.text, CANDIDATES))
            if (dup != null) { store.update(NoteMerge.merge(dup, fresh, now)); merged++ }
            else { store.insert(fresh); added++ }
        }
        return added to merged
    }
}

/**
 * The librarian: while the screen is off and the big on-device brain idle, on the charger or
 * (off it) with enough battery ([mayRun]), the small model turns overheard rows into short dated notes ([MemoryNotes]) the big
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
     * "Is the big brain busy?" Null = [com.agent.jarvis.local.LocalModelEngine]'s busy flag. Set
     * this if the small model shares that flag: the librarian must not stop itself.
     */
    @Volatile var brainBusy: (() -> Boolean)? = null

    /** Is the small model actually usable right now (e.g. its file downloaded)? Null = yes. Checked
     *  before every run so a missing model never turns into chunk failures that get skipped. */
    @Volatile var modelReady: (() -> Boolean)? = null

    const val FIRST_CHECK_MS = 60_000L
    const val IDLE_CHECK_MS = 10 * 60_000L
    const val MORE_CHECK_MS = 60_000L
    const val WATCH_MS = 5_000L

    private const val TAG = "JarvisLibrarian"
    private val failures = LibrarianRun.Failures()
    private var job: Job? = null
    /** The run in progress, so a "Hey Jarvis" can stop it at once ([yieldNow]). */
    @Volatile private var current: Job? = null
    /** No runs before this (epoch ms): the owner is talking to Jarvis. */
    @Volatile private var holdUntil = 0L
    /** How long a wake keeps the librarian off: a command, its answer, a follow-up. */
    const val YIELD_MS = 3 * 60_000L

    /**
     * Jarvis was woken: stop the run in progress now (its generation ends at the next token) and
     * start none for [YIELD_MS]. A command waited for the librarian's whole chunk before, up to a
     * couple of minutes of silence after "Hey Jarvis" (2026-10-07, once it ran off the charger too).
     */
    fun yieldNow() {
        holdUntil = System.currentTimeMillis() + YIELD_MS
        current?.cancel()
    }

    /** Below this battery level, off the charger, it waits for the charger. */
    const val MIN_BATTERY_UNPLUGGED = 50

    /**
     * Pure, JVM-tested: may the librarian work now? Screen off and the brain idle, and either on
     * the charger or (since the owner asked for many more notes, 2026-10-07) off it with at least
     * [MIN_BATTERY_UNPLUGGED]% and battery saver off: the 1.7B model on two cores for a few minutes.
     */
    fun mayRun(
        enabled: Boolean, hasModel: Boolean, charging: Boolean, screenOn: Boolean, brainBusy: Boolean,
        batteryPct: Int = 0, powerSave: Boolean = false,
    ): Boolean = enabled && hasModel && !screenOn && !brainBusy &&
        (charging || (batteryPct >= MIN_BATTERY_UNPLUGGED && !powerSave))

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
        if (System.currentTimeMillis() < holdUntil) return false
        if (!com.agent.jarvis.JarvisPower.isOn(ctx)) return false
        if (model == null || modelReady?.invoke() == false || !AmbientConfig(ctx).organiseMemory) return false
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm?.isInteractive != false) return false
        val charging = charging(ctx)
        val pct = if (charging) 100 else ctx.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        if (!charging && (pct < MIN_BATTERY_UNPLUGGED || pm.isPowerSaveMode)) return false
        val busy = brainBusy?.invoke() ?: com.agent.jarvis.local.LocalModelEngine.shared(ctx).busy.value
        return mayRun(enabled = true, hasModel = true, charging = charging, screenOn = false, brainBusy = busy,
            batteryPct = pct, powerSave = pm.isPowerSaveMode)
    }

    /**
     * "Organise now" on the Notes screen: one run straight away, screen on or off, charger or not,
     * and conversations of any age (the owner is asking for it). Still never beside the brain.
     * Returns a line for the screen.
     */
    suspend fun runNow(ctx: Context): String {
        val m = model ?: return "The memory model isn't set up. Download it in Settings → DOWNLOADS."
        if (modelReady?.invoke() == false) return "The memory model isn't downloaded yet (Settings → DOWNLOADS)."
        val idle = { !(brainBusy?.invoke() ?: com.agent.jarvis.local.LocalModelEngine.shared(ctx).busy.value) }
        if (!idle()) return "Jarvis is busy answering; try again in a moment."
        val mem = AmbientMemory.get(ctx)
        val source = object : HeardSource {
            override fun unprocessed(limit: Int): List<HeardLine> = mem.librarianQueue(limit)
            override fun markProcessed(id: Long, ts: Long) = mem.markLibrarian(id, ts)
        }
        val cfg = AmbientConfig(ctx)
        val r = passes(ctx, m, source, keepGoing = idle, minAgeMs = 0L, patterns = cfg.notePatterns, readings = cfg.patternReadings)
        return when {
            r.chunks == 0 && r.added == 0 && r.merged == 0 -> "Nothing new to organise."
            else -> "Organised ${r.chunks} conversation part(s): ${r.added} new note(s), ${r.merged} merged" +
                (if (r.links.added > 0) ", ${r.links.added} follow-up(s) linking to older notes" else "") + "." +
                if (r.more) " More is waiting; tap again." else ""
        }
    }

    /** One run if allowed; returns whether more rows are waiting. Stopped mid-call (cancelled)
     *  as soon as [allowed] turns false: the brain needs the cores, or the charger came out. */
    suspend fun runIfAllowed(ctx: Context): Boolean {
        if (!allowed(ctx)) return false
        val m = model ?: return false
        val mem = AmbientMemory.get(ctx)
        val source = object : HeardSource {
            override fun unprocessed(limit: Int): List<HeardLine> = mem.librarianQueue(limit)
            override fun markProcessed(id: Long, ts: Long) = mem.markLibrarian(id, ts)
        }
        val cfg = AmbientConfig(ctx)
        val result = coroutineScope {
            val work = async {
                passes(ctx, m, source, keepGoing = { allowed(ctx) }, minAgeMs = LibrarianRun.MIN_AGE_MS,
                    patterns = cfg.notePatterns, readings = cfg.patternReadings)
            }
            current = work
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
                current = null
            }
        } ?: return false
        return result.more
    }

    /** What one librarian turn did: the new speech, the follow-up notes, the re-read of old speech. */
    data class Passes(val main: LibrarianRun.Result, val links: LibrarianLinks.Result, val reread: LibrarianRun.Result?) {
        val more: Boolean get() = main.more || reread?.more == true
        val added: Int get() = main.added + links.added + (reread?.added ?: 0)
        val merged: Int get() = main.merged + links.merged + (reread?.merged ?: 0)
        val chunks: Int get() = main.chunks + (reread?.chunks ?: 0)
    }

    /**
     * New speech into notes; then, on its own, follow-up notes linking the new conversations to older
     * notes ([LibrarianLinks]); then, when nothing new is waiting, old speech read again with the
     * fuller prompt (summaries, who, tags, every note) from the re-read mark ([AmbientMemory.rereadQueue]).
     * Old speech is re-read without conversation patterns: their observations are never merged, so
     * they would be noted twice.
     */
    private suspend fun passes(
        ctx: Context, m: SmallModel, source: HeardSource, keepGoing: () -> Boolean, minAgeMs: Long,
        patterns: Boolean, readings: Boolean,
    ): Passes {
        val store = MemoryNotes.get(ctx)
        val zone = ZoneId.systemDefault()
        LibrarianPrompt.ownerName = com.agent.jarvis.ui.JarvisPrefs.ownerName(ctx)
        val log: (String) -> Unit = { android.util.Log.w(TAG, it) }
        val start = System.currentTimeMillis()
        // Promote durable new notes (facts, people) to verified claims — the two-lens verifier + NLI
        // gate runs inside writeVerified, so a contradiction supersedes instead of piling up. Built
        // once per pass; a failure here never breaks note filing (the caller catches it).
        val db = com.agent.jarvis.data.JarvisDatabase.get(ctx)
        val claims = com.agent.jarvis.harness.MemoryClaims(db.claims())
        val embDao = db.embeddings()
        val embedder = SentenceEmbedder.shared(ctx)
        val nli = com.agent.jarvis.harness.LlmNliHead(com.agent.jarvis.local.LocalModelEngine.librarian(ctx))
        val promote: suspend (NoteDraft) -> Boolean = promote@{ d ->
            val kind = ClaimKind.of(d.kind) ?: return@promote false
            val emb = embedder?.let { runCatching { it.embed(d.text) }.getOrNull() } ?: FloatArray(0)
            val outcome = claims.writeVerified(
                text = d.text, kind = kind, speaker = d.who ?: "Me", sourceMessage = d.text,
                nli = nli, embeddings = embDao, embedding = emb, modelName = SentenceEmbedder.MODEL,
                quote = d.text,
            )
            // Stored as a claim (new or reinforced) → don't also keep it as a note. A rejected claim
            // (ungrounded / contradiction the gate wouldn't take) falls back to a note.
            outcome is com.agent.jarvis.harness.MemoryClaims.WriteOutcome.Written ||
                outcome is com.agent.jarvis.harness.MemoryClaims.WriteOutcome.Reinforced
        }
        val main = LibrarianRun.run(source, store, m, zone, now = System::currentTimeMillis, keepGoing = keepGoing,
            failures = failures, log = log, patterns = patterns, readings = readings, minAgeMs = minAgeMs, promote = promote)
        val links = if (keepGoing()) LibrarianLinks.run(store, m, zone, since = start, now = System::currentTimeMillis,
            keepGoing = keepGoing, log = log) else LibrarianLinks.Result(0, 0, 0)
        val reread = if (!main.more && main.chunks == 0 && keepGoing()) {
            val mem = AmbientMemory.get(ctx)
            val old = object : HeardSource {
                override fun unprocessed(limit: Int): List<HeardLine> = mem.rereadQueue(limit)
                override fun markProcessed(id: Long, ts: Long) = mem.markReread(id)
            }
            LibrarianRun.run(old, store, m, zone, now = System::currentTimeMillis, keepGoing = keepGoing,
                failures = failures, log = log, patterns = false, readings = false, minAgeMs = 0L)
        } else null
        // Yesterday's conversation insights as a note, once a day (counting, no model).
        runCatching { Insights.dailyNote(ctx) }.onFailure { log("insights note failed: ${it.message}") }
        // Who unnamed voices are, from what was said ("I'm Sam", "thanks, Sam" + Sam answering).
        runCatching {
            val mem = AmbientMemory.get(ctx)
            val voices = mem.voices()
            val names = NameInference.infer(mem.voiceLines(System.currentTimeMillis() - 14 * 86_400_000L, 20_000),
                taken = voices.mapNotNull { it.name }.toSet(), ownerName = com.agent.jarvis.ui.JarvisPrefs.ownerName(ctx))
            for ((voice, name) in names) {
                mem.renameVoice(voice, name)
                android.util.Log.i(TAG, "named voice $voice \"$name\" from what was said")
            }
        }.onFailure { log("names: ${it.message}") }
        val p = Passes(main, links, reread)
        android.util.Log.i(TAG, "run: chunks=${main.chunks} added=${main.added} merged=${main.merged} " +
            "rejected=${main.rejected} skipped=${main.skipped} stop=${main.stop} observations=${main.observations} " +
            "readings=${main.readings} | links looked=${links.looked} added=${links.added} merged=${links.merged} | " +
            "reread chunks=${reread?.chunks ?: 0} added=${reread?.added ?: 0} merged=${reread?.merged ?: 0}")
        return p
    }

    private fun charging(ctx: Context): Boolean = try {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    } catch (e: Exception) {
        ctx.getSystemService(BatteryManager::class.java)?.isCharging ?: false
    }
}
