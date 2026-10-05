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

    /** Verbatim lines in a time window, oldest first, char-budgeted. */
    fun since(sinceTs: Long, budgetChars: Int): String {
        val sb = StringBuilder()
        readableDatabase.rawQuery(
            // ts is stored as INTEGER in an FTS4 column (no affinity); a bound TEXT arg
            // sorts above every integer, so ts>=? without the CAST never matched.
            "SELECT ts,text FROM heard WHERE ts>=CAST(? AS INTEGER) ORDER BY ts ASC", arrayOf(sinceTs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val line = c.getString(1)
                if (sb.length + line.length + 1 > budgetChars) break
                sb.append(line).append('\n')
            }
        }
        return sb.toString().trim()
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

    fun wipe() { writableDatabase.execSQL("DELETE FROM heard") }

    companion object {
        @Volatile private var i: AmbientMemory? = null
        fun get(c: Context) = i ?: synchronized(this) {
            i ?: AmbientMemory(c).also { i = it }
        }
    }
}
