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
    SQLiteOpenHelper(ctx.applicationContext, "ambient.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE heard USING fts4(" +
                "text, ts UNINDEXED, conf UNINDEXED, speaker UNINDEXED, notindexed=ts, notindexed=conf)"
        )
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

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

    /** FTS search, newest first — backs the ambient_recall agent tool. */
    fun search(query: String, limit: Int): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        val q = query.trim().ifEmpty { return out }
        readableDatabase.rawQuery(
            "SELECT ts,text FROM heard WHERE text MATCH ? ORDER BY ts DESC LIMIT ?",
            arrayOf(q, limit.toString())
        ).use { c -> while (c.moveToNext()) out += c.getLong(0) to c.getString(1) }
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
    }

    /** Rows stored after rowid [afterId], in stored order: the librarian's queue (see [Librarian]). */
    fun heardAfter(afterId: Long, limit: Int): List<HeardLine> {
        val out = ArrayList<HeardLine>()
        readableDatabase.rawQuery(
            "SELECT rowid,ts,text FROM heard WHERE rowid>CAST(? AS INTEGER) ORDER BY rowid ASC LIMIT ?",
            arrayOf(afterId.toString(), limit.toString())
        ).use { c -> while (c.moveToNext()) out += HeardLine(c.getLong(0), c.getLong(1), c.getString(2)) }
        return out
    }

    fun maxRowId(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(MAX(rowid),0) FROM heard", null).use { it.moveToFirst(); it.getLong(0) }

    /** Last heard rowid the librarian has organised (0 = none). */
    var librarianMark: Long
        get() = readableDatabase.rawQuery("SELECT v FROM meta WHERE k='librarian_mark'", null)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        set(v) = writableDatabase.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('librarian_mark',?)", arrayOf<Any>(v))

    fun wipe() {
        writableDatabase.execSQL("DELETE FROM heard")
        // new rows start again at rowid 1: an old mark would skip them
        writableDatabase.execSQL("DELETE FROM meta WHERE k='librarian_mark'")
    }

    companion object {
        @Volatile private var i: AmbientMemory? = null
        fun get(c: Context) = i ?: synchronized(this) {
            i ?: AmbientMemory(c).also { i = it }
        }
    }
}
