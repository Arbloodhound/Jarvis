package com.agent.ultra.listen

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Ambient transcript store. Its OWN SQLite file, deliberately not the Room
 * UltraDatabase: this is high-churn, capped, and purgeable, and must never
 * complicate the agent's schema migrations. FTS4 so the agent can search it
 * as a tool; a capacity trigger so it can never grow without bound.
 */
class AmbientMemory private constructor(ctx: Context, name: String = "ambient.db") :
    SQLiteOpenHelper(ctx.applicationContext, name, null, 3) {

    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE heard USING fts4(" +
                "text, ts UNINDEXED, conf UNINDEXED, speaker UNINDEXED, notindexed=ts, notindexed=conf)"
        )
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v INTEGER)")
        createVectors(db)
        createCues(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) createVectors(db)
        if (o < 3) createCues(db)
    }

    /**
     * v3: transcript cues, kept OUT of `heard` so keyword search and the sentence index only ever
     * see the plain words. heard_tag: one tagged body per heard row (same rowid, ts copied so a
     * reused rowid never matches), [TranscriptCueWorker] writes it; voice 0 = no voiceprint,
     * [CueTags.UNLABELLED] (-1) = stored with speaker tags off (read without a label).
     * voices: the voiceprints (biometric: on this phone only; [wipe] erases them).
     */
    private fun createCues(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS heard_tag(rowid INTEGER PRIMARY KEY, ts INTEGER NOT NULL, " +
            "voice INTEGER NOT NULL DEFAULT 0, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS voices(id INTEGER PRIMARY KEY AUTOINCREMENT, num INTEGER NOT NULL, " +
            "name TEXT, owner INTEGER NOT NULL DEFAULT 0, centroid BLOB NOT NULL, n INTEGER NOT NULL, " +
            "last_seen INTEGER NOT NULL, rate TEXT, loud TEXT)")
    }

    /** v2: one sentence vector per heard row (same rowid), filled by [MemoryIndexer].
     * vec is [MemoryVectors.quantize]d (388 bytes); empty = failed to embed, skipped. ts is
     * copied from heard so time windows use an index instead of scanning the FTS table. */
    private fun createVectors(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS heard_vec(rowid INTEGER PRIMARY KEY, ts INTEGER NOT NULL, vec BLOB NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS heard_vec_ts ON heard_vec(ts)")
    }

    /** Stores a plain heard line; returns its rowid (-1 on failure). */
    fun record(text: String, ts: Long, conf: Float): Long =
        writableDatabase.insert("heard", null, ContentValues().apply {
            put("text", text); put("ts", ts); put("conf", conf); put("speaker", "")
        })

    // ── Transcript cues + voiceprints ──────────────────────────────────────

    /** Stores [body] (the tagged line without its label) for heard row [rowid], only if that row
     * still exists with the same [ts] (it may have been erased or pruned meanwhile). */
    fun putTag(rowid: Long, ts: Long, voiceId: Long, body: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO heard_tag(rowid,ts,voice,body) " +
                "SELECT rowid,?,?,? FROM heard WHERE rowid=? AND ts=CAST(? AS INTEGER)",
            arrayOf<Any>(ts, voiceId, body, rowid, ts.toString())
        )
    }

    fun voices(): List<Voice> {
        val out = ArrayList<Voice>()
        readableDatabase.rawQuery(
            "SELECT id,num,name,owner,centroid,n,last_seen,rate,loud FROM voices ORDER BY owner DESC, last_seen DESC", null
        ).use { c ->
            while (c.moveToNext()) out += Voice(
                id = c.getLong(0), num = c.getInt(1), name = c.getString(2), owner = c.getInt(3) != 0,
                centroid = floats(c.getBlob(4)), n = c.getInt(5), lastSeen = c.getLong(6),
                rate = Baseline.decode(c.getString(7)), loud = Baseline.decode(c.getString(8)),
            )
        }
        return out
    }

    /** Heard lines per voice id (for Settings). */
    fun voiceLineCounts(): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        readableDatabase.rawQuery("SELECT voice,COUNT(*) FROM heard_tag GROUP BY voice", null)
            .use { c -> while (c.moveToNext()) out[c.getLong(0)] = c.getInt(1) }
        return out
    }

    /** A new unnamed voice ("Speaker N", N counting up from 2: the owner is the first speaker).
     * Forgets [evict] first, in the same transaction. Returns the new id. */
    fun addVoice(centroid: FloatArray, now: Long, evict: Long? = null): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (evict != null) deleteVoiceIn(db, evict)
            val num = (metaGet(META_VOICE_NEXT) ?: 2L).toInt()
            metaPut(META_VOICE_NEXT, num + 1L)
            val id = db.insert("voices", null, ContentValues().apply {
                put("num", num); put("owner", 0); put("centroid", bytes(centroid)); put("n", 1)
                put("last_seen", now)
            })
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    /** What the cue worker learned about voice [was] (the row as it read it). Name and owner are
     * never touched here (the owner may be renaming it in Settings at the same moment). Applied
     * only if the row is still as read ([Voice.n], [Voice.lastSeen]): an enrolment, merge or
     * delete in between wins, and this line's update is dropped. Returns whether it applied. */
    fun updateVoice(was: Voice, centroid: FloatArray, n: Int, lastSeen: Long, rate: Baseline, loud: Baseline): Boolean =
        writableDatabase.update("voices", ContentValues().apply {
            put("centroid", bytes(centroid)); put("n", n); put("last_seen", lastSeen)
            put("rate", rate.encode()); put("loud", loud.encode())
        }, "id=? AND n=? AND last_seen=?", arrayOf(was.id.toString(), was.n.toString(), was.lastSeen.toString())) > 0

    /**
     * Gives voice [id] a name. A name another voice already has (any case) means they are the
     * same person (clustering split them): the two are merged into that one, its lines included.
     * A blank name clears it. Returns the id the voice now lives under.
     */
    fun renameVoice(id: Long, rawName: String): Long {
        val name = CueTags.cleanName(rawName)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val all = voices()
            val me = all.firstOrNull { it.id == id } ?: return id
            val twin = name?.let { n -> all.firstOrNull { it.id != id && it.name?.equals(n, ignoreCase = true) == true } }
            val result = if (twin != null) {
                mergeIn(db, me, twin); twin.id
            } else {
                db.update("voices", ContentValues().apply { if (name == null) putNull("name") else put("name", name) },
                    "id=?", arrayOf(id.toString()))
                id
            }
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    /** Forgets voice [id]; its lines say "[Unknown]" from now on. */
    fun deleteVoice(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try { deleteVoiceIn(db, id); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    private fun deleteVoiceIn(db: SQLiteDatabase, id: Long) {
        db.execSQL("UPDATE heard_tag SET voice=0 WHERE voice=?", arrayOf<Any>(id))
        db.execSQL("DELETE FROM voices WHERE id=?", arrayOf<Any>(id))
    }

    /** [from]'s lines and centroid go to [into]; [from] is deleted. Owner wins. */
    private fun mergeIn(db: SQLiteDatabase, from: Voice, into: Voice) {
        val c = VoiceMatch.merge(into.centroid, into.n, from.centroid, from.n)
        db.update("voices", ContentValues().apply {
            put("centroid", bytes(c)); put("n", minOf(VoiceMatch.CENTROID_CAP, into.n + from.n))
            put("last_seen", maxOf(into.lastSeen, from.lastSeen))
            if (from.owner) put("owner", 1)
        }, "id=?", arrayOf(into.id.toString()))
        db.execSQL("UPDATE heard_tag SET voice=? WHERE voice=?", arrayOf<Any>(into.id, from.id))
        db.execSQL("DELETE FROM voices WHERE id=?", arrayOf<Any>(from.id))
    }

    /**
     * The owner's enrolled voice ([emb], from ~10 s of them speaking). Replaces an earlier
     * enrolment; otherwise a voice already heard that matches ([VoiceMatch.MATCH]) becomes the
     * owner, so its earlier lines turn into "[Me]"; otherwise a new owner voice. Counts as
     * [ENROL_WEIGHT] lines, so everyday audio moves it only slowly.
     */
    fun enrolOwner(emb: FloatArray, now: Long) {
        val e = VoiceMatch.normalize(emb)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val all = voices()
            val owner = all.firstOrNull { it.owner }
            val match = VoiceMatch.best(all.filter { !it.owner && it.name.isNullOrBlank() }, e)
                ?.takeIf { it.second >= VoiceMatch.MATCH }?.first
            val target = owner ?: match
            if (target != null) {
                db.update("voices", ContentValues().apply {
                    put("owner", 1); put("centroid", bytes(e)); put("n", ENROL_WEIGHT); put("last_seen", now)
                }, "id=?", arrayOf(target.id.toString()))
                // already enrolled, and the voice heard as "Speaker N" was the owner all along
                if (owner != null && match != null) mergeIn(db, match, owner.copy(centroid = e, n = ENROL_WEIGHT))
            } else {
                db.insert("voices", null, ContentValues().apply {
                    put("num", 1); put("owner", 1); put("centroid", bytes(e)); put("n", ENROL_WEIGHT)
                    put("last_seen", now)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun ownerEnrolled(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM voices WHERE owner=1 LIMIT 1", null).use { it.moveToFirst() }

    /** Forgets every voiceprint (lines keep their tags, labelled "[Unknown]"; lines stored with
     *  speaker tags off keep no label). */
    fun wipeVoices() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE heard_tag SET voice=0 WHERE voice>0")   // unlabelled lines stay unlabelled
            db.execSQL("DELETE FROM voices")
            db.execSQL("DELETE FROM meta WHERE k=?", arrayOf(META_VOICE_NEXT))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Verbatim lines in a time window, oldest first, char-budgeted from the NEWEST end:
     * what was said just before the wake word matters most. */
    fun since(sinceTs: Long, budgetChars: Int): String {
        val lines = ArrayList<String>()
        var used = 0
        readableDatabase.rawQuery(
            // ts is stored as INTEGER in an FTS4 column (no affinity); a bound TEXT arg
            // sorts above every integer, so ts>=? without the CAST never matched.
            "SELECT ts,text FROM heard WHERE ts>=CAST(? AS INTEGER) ORDER BY ts DESC", arrayOf(sinceTs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val line = c.getString(1)
                if (used + line.length + 1 > budgetChars) break
                lines += line; used += line.length + 1
            }
        }
        return lines.asReversed().joinToString("\n").trim()
    }

    class Row(val rowid: Long, val ts: Long, val text: String)

    // ── Vectors (semantic recall) ──────────────────────────────────────────

    /** Heard rows with rowid > [afterRowid], oldest first (the indexer's forward pass). */
    fun rowsAfter(afterRowid: Long, limit: Int): List<Row> = rows(
        "SELECT rowid,ts,text FROM heard WHERE rowid>CAST(? AS INTEGER) ORDER BY rowid ASC LIMIT ?",
        arrayOf(afterRowid.toString(), limit.toString())
    )

    /** The newest [limit] heard rows (recall embeds these inline so just-said lines are found). */
    fun newest(limit: Int): List<Row> =
        rows("SELECT rowid,ts,text FROM heard ORDER BY rowid DESC LIMIT ?", arrayOf(limit.toString()))

    fun maxRowid(): Long = readableDatabase.rawQuery("SELECT IFNULL(MAX(rowid),0) FROM heard", null)
        .use { it.moveToFirst(); it.getLong(0) }

    /** Which of [ids] already have a vector row (including the empty "failed" marker). */
    fun hasVector(ids: Collection<Long>): Set<Long> {
        val out = HashSet<Long>()
        for (chunk in ids.chunked(500)) readableDatabase.rawQuery(
            "SELECT rowid FROM heard_vec WHERE rowid IN (${chunk.joinToString(",")})", null
        ).use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    fun metaGet(k: String): Long? = readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k))
        .use { if (it.moveToFirst()) it.getLong(0) else null }

    fun metaPut(k: String, v: Long) {
        writableDatabase.insertWithOnConflict(
            "meta", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** Bumped by [wipe] (and by [prune] when it empties the table): rowids may then be reused,
     * so an indexer pass that started before must not write its high-water mark. */
    fun generation(): Long = metaGet(META_VEC_GEN) ?: 0L

    /**
     * Stores [vecs] (same order as [rows]); an empty blob marks a row that failed to embed.
     * A vector is written only if its heard row still exists with the same ts, so a row erased
     * or pruned while it was being embedded (and a reused rowid) never gets a stale vector.
     * With [newHw], also advances the indexer's high-water mark, but only if [generation] is
     * still [gen]. All in one transaction (SQLite serialises it against [wipe] / [prune]).
     * False = the store was wiped meanwhile; nothing was written.
     */
    fun putVectors(rows: List<Row>, vecs: List<ByteArray>, gen: Long, newHw: Long? = null): Boolean {
        require(rows.size == vecs.size)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (generation() != gen) return false
            db.compileStatement(
                "INSERT OR REPLACE INTO heard_vec(rowid,ts,vec) " +
                    "SELECT rowid,?,? FROM heard WHERE rowid=? AND ts=CAST(? AS INTEGER)"
            ).use { st ->
                for (i in rows.indices) {
                    st.clearBindings()
                    st.bindLong(1, rows[i].ts); st.bindBlob(2, vecs[i])
                    st.bindLong(3, rows[i].rowid); st.bindString(4, rows[i].ts.toString())
                    st.executeInsert()
                }
            }
            if (newHw != null) metaPut(META_VEC_HW, newHw)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    /** Streams stored, non-empty vectors, newest first, inside [window] when given, at most
     * [limit] of them (brute force; see [MemoryRecall.SCAN_LIMIT] for the size). heard_vec.ts
     * is a plain INTEGER column, so bound text args get integer affinity here (no CAST needed). */
    fun forEachVector(
        window: TimeWindow?, limit: Int, excluding: TimeWindow? = null,
        each: (rowid: Long, vec: ByteArray) -> Unit,
    ) {
        val (sql, args) = when {
            window != null ->
                "SELECT rowid,vec FROM heard_vec WHERE ts>=? AND ts<? AND length(vec)>0 ORDER BY ts DESC LIMIT ?" to
                    arrayOf(window.fromMs.toString(), window.toMs.toString(), limit.toString())
            // Everything except [excluding] (already scanned by a window pass).
            excluding != null ->
                "SELECT rowid,vec FROM heard_vec WHERE length(vec)>0 AND NOT (ts>=? AND ts<?) ORDER BY rowid DESC LIMIT ?" to
                    arrayOf(excluding.fromMs.toString(), excluding.toMs.toString(), limit.toString())
            else -> "SELECT rowid,vec FROM heard_vec WHERE length(vec)>0 ORDER BY rowid DESC LIMIT ?" to
                arrayOf(limit.toString())
        }
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) each(c.getLong(0), c.getBlob(1)) }
    }

    /** Rows by id (texts for vector hits). */
    fun byIds(ids: Collection<Long>): List<Row> = ids.chunked(500).flatMap { chunk ->
        rows("SELECT rowid,ts,text FROM heard WHERE rowid IN (${chunk.joinToString(",")})", null)
    }

    /** Stored vectors by id (for keyword / time-window hits). */
    fun vectorsFor(ids: Collection<Long>): Map<Long, ByteArray> {
        val out = HashMap<Long, ByteArray>()
        for (chunk in ids.chunked(500)) readableDatabase.rawQuery(
            "SELECT rowid,vec FROM heard_vec WHERE rowid IN (${chunk.joinToString(",")})", null
        ).use { c -> while (c.moveToNext()) out[c.getLong(0)] = c.getBlob(1) }
        return out
    }

    /** FTS [match] (FTS4 query syntax), newest first, inside [window] when given. CAST: see [since]. */
    fun keywordRows(match: String, window: TimeWindow?, limit: Int): List<Row> = when {
        match.isBlank() -> emptyList()
        window == null -> rows(
            "SELECT rowid,ts,text FROM heard WHERE text MATCH ? ORDER BY ts DESC LIMIT ?",
            arrayOf(match, limit.toString())
        )
        else -> rows(
            "SELECT rowid,ts,text FROM heard WHERE text MATCH ? AND ts>=CAST(? AS INTEGER) " +
                "AND ts<CAST(? AS INTEGER) ORDER BY ts DESC LIMIT ?",
            arrayOf(match, window.fromMs.toString(), window.toMs.toString(), limit.toString())
        )
    }

    /** Everything heard in [fromMs, toMs), newest first. CAST: see [since]. */
    fun windowRows(fromMs: Long, toMs: Long, limit: Int): List<Row> = rows(
        "SELECT rowid,ts,text FROM heard WHERE ts>=CAST(? AS INTEGER) AND ts<CAST(? AS INTEGER) ORDER BY ts DESC LIMIT ?",
        arrayOf(fromMs.toString(), toMs.toString(), limit.toString())
    )

    private fun rows(sql: String, args: Array<String>?): List<Row> {
        val out = ArrayList<Row>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out += Row(c.getLong(0), c.getLong(1), c.getString(2))
        }
        return out
    }

    /** Enforce retention + row cap. Call hourly, not per-insert. */
    fun prune(retentionDays: Int, maxRows: Int) {
        val db = writableDatabase
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        db.execSQL("DELETE FROM heard WHERE ts < ?", arrayOf(cutoff))
        val count = db.rawQuery("SELECT COUNT(*) FROM heard", null).use { it.moveToFirst(); it.getLong(0) }
        if (count > maxRows) {
            db.execSQL(
                "DELETE FROM heard WHERE rowid IN (" +
                    "SELECT rowid FROM heard ORDER BY ts ASC LIMIT ?)",
                arrayOf((count - maxRows).toString())
            )
        }
        db.beginTransaction()
        try {
            // Vectors go with their rows.
            db.execSQL("DELETE FROM heard_vec WHERE rowid NOT IN (SELECT rowid FROM heard)")
            db.execSQL("DELETE FROM heard_tag WHERE rowid NOT IN (SELECT rowid FROM heard)")
            // Everything pruned: rowids restart from 1, so the indexer must too.
            if (maxRowid() == 0L) resetIndex(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * Rows stored after rowid [afterId], in stored order: the librarian's queue (see [Librarian]).
     * [HeardLine.text] (= [HeardLine.tagged]) is "[label] body" with the cues ([CueTags]), the
     * label resolved now (so a rename covers old lines); just the body when it was stored with
     * speaker tags off ([CueTags.read]); or the plain line when the row has no
     * cues (heard before this existed, or the cue worker hasn't reached it yet).
     * [HeardLine.plain] is always the plain line.
     */
    fun heardAfter(afterId: Long, limit: Int): List<HeardLine> = tagged(
        "WHERE h.rowid>CAST(? AS INTEGER) ORDER BY h.rowid ASC LIMIT ?", arrayOf(afterId.toString(), limit.toString()))

    /** Lines since [sinceTs] with their voice, oldest first: [NameInference]'s input. */
    fun voiceLines(sinceTs: Long, limit: Int): List<NameInference.Line> {
        val out = ArrayList<NameInference.Line>()
        readableDatabase.rawQuery(
            "SELECT h.ts,h.text,t.voice,v.owner,v.name FROM heard h " +
                "JOIN heard_tag t ON t.rowid=h.rowid AND t.ts=h.ts " +
                "LEFT JOIN voices v ON v.id=t.voice " +
                "WHERE h.ts>=CAST(? AS INTEGER) ORDER BY h.ts ASC LIMIT ?",
            arrayOf(sinceTs.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val voice = if (c.isNull(2)) 0L else c.getLong(2)
                out += NameInference.Line(
                    voice = if (voice > 0 && !c.isNull(3)) voice else 0L,
                    owner = !c.isNull(3) && c.getInt(3) != 0,
                    named = !c.isNull(4) && c.getString(4).isNotBlank(),
                    ts = c.getLong(0), text = c.getString(1),
                )
            }
        }
        return out
    }

    /** The newest [limit] lines with their speaker / cue tags ("[Me] …", "[Sam] [pause 1.2s] …"),
     *  newest first: the Memory screen. */
    fun newestTagged(limit: Int): List<HeardLine> = tagged("ORDER BY h.rowid DESC LIMIT ?", arrayOf(limit.toString()))

    private fun tagged(where: String, args: Array<String>): List<HeardLine> {
        val out = ArrayList<HeardLine>()
        readableDatabase.rawQuery(
            "SELECT h.rowid,h.ts,h.text,t.body,t.voice,v.id,v.owner,v.name,v.num FROM heard h " +
                "LEFT JOIN heard_tag t ON t.rowid=h.rowid AND t.ts=h.ts " +
                "LEFT JOIN voices v ON v.id=t.voice " + where,
            args
        ).use { c ->
            while (c.moveToNext()) {
                val plain = c.getString(2)
                val tagged = if (c.isNull(3)) plain else CueTags.read(
                    c.getString(3), c.getLong(4),
                    if (c.isNull(5)) null else CueTags.label(c.getInt(6) != 0, c.getString(7), c.getInt(8)),
                )
                out += HeardLine(c.getLong(0), c.getLong(1), text = tagged, plain = plain)
            }
        }
        return out
    }

    /**
     * The librarian's queue: up to [limit] rows after its mark, in stored order. The mark is the
     * last organised rowid plus the newest ts among organised rows. Rowids start again at 1 once
     * the table is emptied (wipe, or retention pruning every row), and a run that was mid-call
     * during a wipe can write its old mark back afterwards; either way the newest row at or below
     * the mark is then NEWER than anything organised, and the mark goes back to 0. One rowid
     * lookup, no scan.
     */
    fun librarianQueue(limit: Int): List<HeardLine> {
        val (mark, markTs) = librarianMark()
        if (mark > 0) {
            val topTs = readableDatabase.rawQuery(
                "SELECT ts FROM heard WHERE rowid<=CAST(? AS INTEGER) ORDER BY rowid DESC LIMIT 1",
                arrayOf(mark.toString())
            ).use { if (it.moveToFirst()) it.getLong(0) else null }
            if (topTs != null && topTs > markTs) {
                markLibrarian(0L, 0L, reset = true)
                return heardAfter(0L, limit)
            }
        }
        return heardAfter(mark, limit)
    }

    /**
     * The re-read pass ([Librarian]): rows already organised under an older, sparser prompt, read
     * again from the start so they get summaries, who-said-it, tags and more notes. Up to [limit]
     * rows after its own mark and at or below the librarian's mark (newer rows are the normal pass's).
     */
    fun rereadQueue(limit: Int): List<HeardLine> {
        val upTo = librarianMark().first
        val from = metaGet(META_REREAD) ?: 0L
        if (from >= upTo) return emptyList()
        return heardAfter(from, limit).filter { it.id <= upTo }
    }

    fun markReread(id: Long) = metaPut(META_REREAD, id)

    /** Rows up to [id] are organised; [ts] is the newest ts among them. [reset] = overwrite. */
    fun markLibrarian(id: Long, ts: Long, reset: Boolean = false) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val keepTs = if (reset) ts else maxOf(ts, librarianMark().second)
            db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('librarian_mark',?)", arrayOf<Any>(id))
            db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('librarian_mark_ts',?)", arrayOf<Any>(keepTs))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun librarianMark(): Pair<Long, Long> {
        var mark = 0L; var ts = 0L
        readableDatabase.rawQuery("SELECT k,v FROM meta WHERE k IN ('librarian_mark','librarian_mark_ts')", null).use { c ->
            while (c.moveToNext()) if (c.getString(0) == "librarian_mark") mark = c.getLong(1) else ts = c.getLong(1)
        }
        return mark to ts
    }

    /** Blocking (disk); call off the main thread. */
    fun wipe() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM heard")
            db.execSQL("DELETE FROM heard_vec")
            // cues and voiceprints go with what was heard
            db.execSQL("DELETE FROM heard_tag")
            db.execSQL("DELETE FROM voices")
            db.execSQL("DELETE FROM meta WHERE k=?", arrayOf(META_VOICE_NEXT))
            resetIndex(db)
            // new rows start again at rowid 1: an old librarian mark would skip them
            db.execSQL("DELETE FROM meta WHERE k IN ('librarian_mark','librarian_mark_ts')")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun resetIndex(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM meta WHERE k=?", arrayOf(META_VEC_HW))
        metaPut(META_VEC_GEN, generation() + 1)
    }

    companion object {
        /** meta key: the number the next new voice gets ("Speaker N"). */
        const val META_VOICE_NEXT = "voice_next"
        /** An enrolment counts as this many heard lines in the owner's centroid. */
        const val ENROL_WEIGHT = 20

        /** Float32 little-endian, as stored in voices.centroid. */
        fun bytes(v: FloatArray): ByteArray {
            val b = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (x in v) b.putFloat(x)
            return b.array()
        }

        fun floats(b: ByteArray): FloatArray {
            val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { buf.getFloat() }
        }

        /** meta key: every heard row with rowid ≤ this has been offered to the embedder. */
        const val META_VEC_HW = "vec_hw"
        /** meta key: see [generation]. */
        const val META_VEC_GEN = "vec_gen"
        const val META_REREAD = "reread_mark"
        /** meta key: when the embedder download last failed (epoch ms), for a persisted backoff. */
        const val META_VEC_FETCH_FAIL = "vec_fetch_fail"
        /** meta key: consecutive failed embedder downloads (for the growing backoff). */
        const val META_VEC_FETCH_FAILS = "vec_fetch_fails"

        @Volatile private var i: AmbientMemory? = null
        fun get(c: Context) = i ?: synchronized(this) {
            i ?: AmbientMemory(c).also { i = it }
        }

        /** A separate store in its own file [name] (never the real "ambient.db"): the debug
         * feature check fills one with test lines, then closes it and deletes the file. */
        fun sandbox(c: Context, name: String): AmbientMemory {
            require(name != "ambient.db") { "the sandbox must not be the real store" }
            return AmbientMemory(c, name)
        }
    }
}
