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
class AmbientMemory private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "ambient.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE heard USING fts4(" +
                "text, ts UNINDEXED, conf UNINDEXED, speaker UNINDEXED, notindexed=ts, notindexed=conf)"
        )
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v INTEGER)")
        createVectors(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) createVectors(db)
    }

    /** v2: one sentence vector per heard row (same rowid), filled by [MemoryIndexer].
     * vec is [MemoryVectors.quantize]d (388 bytes); empty = failed to embed, skipped. ts is
     * copied from heard so time windows use an index instead of scanning the FTS table. */
    private fun createVectors(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS heard_vec(rowid INTEGER PRIMARY KEY, ts INTEGER NOT NULL, vec BLOB NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS heard_vec_ts ON heard_vec(ts)")
    }

    fun record(text: String, ts: Long, conf: Float) {
        writableDatabase.insert("heard", null, ContentValues().apply {
            put("text", text); put("ts", ts); put("conf", conf); put("speaker", "")
        })
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
            // Everything pruned: rowids restart from 1, so the indexer must too.
            if (maxRowid() == 0L) resetIndex(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Blocking (disk); call off the main thread. */
    fun wipe() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM heard")
            db.execSQL("DELETE FROM heard_vec")
            resetIndex(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun resetIndex(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM meta WHERE k=?", arrayOf(META_VEC_HW))
        metaPut(META_VEC_GEN, generation() + 1)
    }

    companion object {
        /** meta key: every heard row with rowid ≤ this has been offered to the embedder. */
        const val META_VEC_HW = "vec_hw"
        /** meta key: see [generation]. */
        const val META_VEC_GEN = "vec_gen"
        /** meta key: when the embedder download last failed (epoch ms), for a persisted backoff. */
        const val META_VEC_FETCH_FAIL = "vec_fetch_fail"
        /** meta key: consecutive failed embedder downloads (for the growing backoff). */
        const val META_VEC_FETCH_FAILS = "vec_fetch_fails"

        @Volatile private var i: AmbientMemory? = null
        fun get(c: Context) = i ?: synchronized(this) {
            i ?: AmbientMemory(c).also { i = it }
        }
    }
}
