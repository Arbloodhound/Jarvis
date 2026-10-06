package com.agent.ultra.listen

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * One search result from the organised notes. [text] is the note, followed by its date in
 * brackets when it has one ("Dentist with Dr Lee (Tue 7 Oct 2026 15:00)"); [ts] is when the
 * note is about if it has a time, otherwise when it was overheard; [kind] is person, plan,
 * todo or fact.
 */
data class NoteHit(val text: String, val ts: Long, val kind: String)

/**
 * The librarian's organised notes: short dated notes the small on-device model wrote from
 * overheard speech. Its OWN SQLite file (like [AmbientMemory], not the Room database): derived,
 * rebuildable, and never part of the agent's schema migrations. An FTS4 index over the text
 * (external content, kept in step by triggers) backs [search] and duplicate lookup.
 */
class MemoryNotes private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "memory_notes.db", null, 1), NoteStore {

    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    override fun onCreate(db: SQLiteDatabase) { SCHEMA.forEach(db::execSQL) }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    override fun candidates(kind: NoteKind, text: String, limit: Int): List<Note> {
        val q = NoteMerge.ftsQuery(text)
            ?: return notes("SELECT $COLS FROM notes WHERE kind=? ORDER BY updated_ts DESC LIMIT ?",
                kind.key, limit.toString())
        return notes(
            "SELECT $COLS FROM notes WHERE id IN (SELECT docid FROM notes_fts WHERE notes_fts MATCH ?) " +
                "AND kind=? ORDER BY updated_ts DESC LIMIT ?",
            q, kind.key, limit.toString(),
        )
    }

    override fun insert(note: Note): Long = writableDatabase.insertOrThrow("notes", null, values(note))

    override fun update(note: Note) {
        writableDatabase.update("notes", values(note), "id=?", arrayOf(note.id.toString()))
    }

    /** Best matches for [query] (most query words, then newest); the newest notes when the
     *  query has no searchable words. */
    suspend fun search(query: String, limit: Int): List<NoteHit> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val q = NoteMerge.ftsQuery(query)
        val found = if (q == null) {
            notes("SELECT $COLS FROM notes ORDER BY updated_ts DESC LIMIT ?", limit.toString())
        } else {
            NoteMerge.rank(query, notes(
                "SELECT $COLS FROM notes WHERE id IN (SELECT docid FROM notes_fts WHERE notes_fts MATCH ?) " +
                    "ORDER BY updated_ts DESC LIMIT ?",
                q, (limit * 5).coerceAtMost(200).toString(),
            ), limit)
        }
        val zone = ZoneId.systemDefault()
        found.map { hit(it, zone) }
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM notes", null).use { it.moveToFirst(); it.getInt(0) }

    /** Forget every note (the triggers empty the index with them). */
    fun wipe() { writableDatabase.execSQL("DELETE FROM notes") }

    private fun notes(sql: String, vararg args: String): List<Note> {
        val out = ArrayList<Note>()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out += row(c) }
        return out
    }

    private fun row(c: Cursor) = Note(
        id = c.getLong(0),
        kind = NoteKind.parse(c.getString(1)) ?: NoteKind.FACT,
        text = c.getString(2),
        whenTs = if (c.isNull(3)) null else c.getLong(3),
        whenEnd = if (c.isNull(4)) null else c.getLong(4),
        heardTs = c.getLong(5),
        sourceIds = c.getString(6).split(',').mapNotNull { it.trim().toLongOrNull() },
        createdTs = c.getLong(7),
        updatedTs = c.getLong(8),
    )

    private fun values(n: Note) = ContentValues().apply {
        put("kind", n.kind.key); put("text", n.text)
        if (n.whenTs == null) putNull("when_ts") else put("when_ts", n.whenTs)
        if (n.whenEnd == null) putNull("when_end") else put("when_end", n.whenEnd)
        put("heard_ts", n.heardTs); put("source_ids", n.sourceIds.joinToString(","))
        put("created_ts", n.createdTs); put("updated_ts", n.updatedTs)
    }

    companion object {
        private const val COLS = "id,kind,text,when_ts,when_end,heard_ts,source_ids,created_ts,updated_ts"

        val SCHEMA = listOf(
            "CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, text TEXT NOT NULL, " +
                "when_ts INTEGER, when_end INTEGER, heard_ts INTEGER NOT NULL, source_ids TEXT NOT NULL, " +
                "created_ts INTEGER NOT NULL, updated_ts INTEGER NOT NULL)",
            "CREATE INDEX notes_kind ON notes(kind, updated_ts)",
            "CREATE VIRTUAL TABLE notes_fts USING fts4(content=\"notes\", text)",
            // the documented FTS4 external-content pattern: remove the old text before the row
            // changes (the index reads it from the content table), add the new text after
            "CREATE TRIGGER notes_bu BEFORE UPDATE ON notes BEGIN DELETE FROM notes_fts WHERE docid=old.id; END",
            "CREATE TRIGGER notes_bd BEFORE DELETE ON notes BEGIN DELETE FROM notes_fts WHERE docid=old.id; END",
            "CREATE TRIGGER notes_au AFTER UPDATE ON notes BEGIN INSERT INTO notes_fts(docid, text) VALUES(new.id, new.text); END",
            "CREATE TRIGGER notes_ai AFTER INSERT ON notes BEGIN INSERT INTO notes_fts(docid, text) VALUES(new.id, new.text); END",
        )

        /** The hit for one note; see [NoteHit]. Pure. */
        fun hit(n: Note, zone: ZoneId): NoteHit =
            NoteHit(
                text = n.whenText(zone)?.let { "${n.text} ($it)" } ?: n.text,
                ts = n.whenTs ?: n.heardTs,
                kind = n.kind.key,
            )

        @Volatile private var i: MemoryNotes? = null
        fun get(c: Context) = i ?: synchronized(this) {
            i ?: MemoryNotes(c).also { i = it }
        }
    }
}
