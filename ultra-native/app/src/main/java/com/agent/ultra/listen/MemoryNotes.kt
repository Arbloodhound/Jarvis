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
    SQLiteOpenHelper(ctx.applicationContext, "memory_notes.db", null, 2), NoteStore {

    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    override fun onCreate(db: SQLiteDatabase) { SCHEMA.forEach(db::execSQL) }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        // 2: who said it, and topic tags (the owner asked for speaker labels and tags on notes)
        if (o < 2) UPGRADE_2.forEach(db::execSQL)
    }

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
     *  query has no searchable words. Same as [search] with no time window. */
    suspend fun search(query: String, limit: Int): List<NoteHit> = search(query, limit, null, null)

    /**
     * Like [search], limited to notes that touch the window [fromTs, toTs) (epoch ms; null = open
     * on that side): notes overheard in it, or whose time ([Note.whenTs]..[Note.whenEnd]) overlaps
     * it. "What did they say yesterday" and "what's on Friday" both find their notes.
     */
    suspend fun search(query: String, limit: Int, fromTs: Long?, toTs: Long?): List<NoteHit> =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext emptyList()
            if (fromTs != null && toTs != null && toTs <= fromTs) return@withContext emptyList()
            val (window, wArgs) = windowSql(fromTs, toTs)
            val q = NoteMerge.ftsQuery(query)
            val found = if (q == null) {
                notes("SELECT $COLS FROM notes WHERE $window ORDER BY updated_ts DESC LIMIT ?",
                    *(wArgs + limit.toString()).toTypedArray())
            } else {
                NoteMerge.rank(query, notes(
                    "SELECT $COLS FROM notes WHERE id IN (SELECT docid FROM notes_fts WHERE notes_fts MATCH ?) " +
                        "AND $window ORDER BY updated_ts DESC LIMIT ?",
                    *(listOf(q) + wArgs + (limit * 5).coerceAtMost(200).toString()).toTypedArray(),
                ), limit)
            }
            val zone = ZoneId.systemDefault()
            found.map { hit(it, zone) }
        }

    /** A note the owner made ([UserNotes]): merged into a duplicate when there is one, as the
     *  librarian's are. Returns the note as stored. */
    fun addUser(text: String, now: Long = System.currentTimeMillis()): Note {
        val fresh = UserNotes.build(text, now, ZoneId.systemDefault())
        val dup = NoteMerge.findDuplicate(fresh, candidates(fresh.kind, fresh.text, 20))
        return if (dup != null) NoteMerge.merge(dup, fresh, now).also { update(it) }
            else fresh.copy(id = insert(fresh))
    }

    /** Notes the memory model copied from its prompt's examples instead of from speech: removed
     *  once per open (cheap: one scan of the newest notes). */
    fun dropCopiedExamples() = runCatching {
        notes("SELECT $COLS FROM notes ORDER BY id DESC LIMIT 2000").filter { LibrarianPrompt.copiesExample(it.text) }
            .forEach { delete(it.id) }
    }

    /** A conversation-insights note ([ConversationInsights.noteText]), tagged "insights". */
    fun addInsight(text: String, now: Long = System.currentTimeMillis()): Note {
        val n = Note(kind = NoteKind.SUMMARY, text = text, whenTs = null, whenEnd = null, heardTs = now,
            sourceIds = emptyList(), createdTs = now, updatedTs = now, who = null, tags = listOf("insights"))
        return n.copy(id = insert(n))
    }

    /** For the Notes screen: notes of [kind] (null = all) matching [query] (blank = all), newest first. */
    override fun createdSince(ts: Long, kind: NoteKind?, limit: Int): List<Note> =
        if (kind == null) notes("SELECT $COLS FROM notes WHERE created_ts>=CAST(? AS INTEGER) ORDER BY created_ts DESC LIMIT ?",
            ts.toString(), limit.toString())
        else notes("SELECT $COLS FROM notes WHERE created_ts>=CAST(? AS INTEGER) AND kind=? ORDER BY created_ts DESC LIMIT ?",
            ts.toString(), kind.key, limit.toString())

    override fun related(text: String, beforeTs: Long, limit: Int): List<Note> {
        val q = NoteMerge.ftsQuery(text) ?: return emptyList()
        return NoteMerge.rank(text, notes(
            "SELECT $COLS FROM notes WHERE id IN (SELECT docid FROM notes_fts WHERE notes_fts MATCH ?) " +
                "AND created_ts<CAST(? AS INTEGER) ORDER BY updated_ts DESC LIMIT ?",
            q, beforeTs.toString(), (limit * 5).coerceAtMost(200).toString(),
        ), limit)
    }

    /** Every tag in use, most used first (the Notes screen's tag chips). */
    fun allTags(limit: Int = 30): List<String> {
        val counts = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT tags FROM notes WHERE tags IS NOT NULL AND tags<>''", null).use { c ->
            while (c.moveToNext()) c.getString(0).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    fun list(kind: NoteKind?, query: String, limit: Int): List<Note> {
        val q = NoteMerge.ftsQuery(query)
        val kindSql = if (kind == null) "1" else "kind=?"
        val kindArgs = if (kind == null) emptyList() else listOf(kind.key)
        return if (q == null) notes("SELECT $COLS FROM notes WHERE $kindSql ORDER BY updated_ts DESC LIMIT ?",
            *(kindArgs + limit.toString()).toTypedArray())
        else notes("SELECT $COLS FROM notes WHERE id IN (SELECT docid FROM notes_fts WHERE notes_fts MATCH ?) " +
            "AND $kindSql ORDER BY updated_ts DESC LIMIT ?", *(listOf(q) + kindArgs + limit.toString()).toTypedArray())
    }

    fun delete(id: Long) { writableDatabase.delete("notes", "id=?", arrayOf(id.toString())) }

    /**
     * Once: notes saved as text files by the old note tool (files/notes/note-<ms>.txt, never read
     * by anything) are moved into this store with their original time; the folder is renamed so
     * it runs once. Returns how many.
     */
    fun importLegacyFiles(filesDir: java.io.File): Int {
        val dir = java.io.File(filesDir, "notes")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return 0
        var n = 0
        for (f in files.sortedBy { it.name }) {
            val text = runCatching { f.readText() }.getOrNull()?.trim().orEmpty()
            if (text.isEmpty()) continue
            val ts = f.name.removePrefix("note-").removeSuffix(".txt").toLongOrNull() ?: f.lastModified()
            addUser(text, ts); n++
        }
        dir.renameTo(java.io.File(filesDir, "notes-imported"))
        return n
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
        who = if (c.isNull(9)) null else c.getString(9),
        tags = if (c.isNull(10)) emptyList() else c.getString(10).split(',').map { it.trim() }.filter { it.isNotEmpty() },
    )

    private fun values(n: Note) = ContentValues().apply {
        put("kind", n.kind.key); put("text", n.text)
        if (n.whenTs == null) putNull("when_ts") else put("when_ts", n.whenTs)
        if (n.whenEnd == null) putNull("when_end") else put("when_end", n.whenEnd)
        put("heard_ts", n.heardTs); put("source_ids", n.sourceIds.joinToString(","))
        put("created_ts", n.createdTs); put("updated_ts", n.updatedTs)
        if (n.who == null) putNull("who") else put("who", n.who)
        put("tags", n.tags.joinToString(","))
    }

    companion object {
        private const val COLS = "id,kind,text,when_ts,when_end,heard_ts,source_ids,created_ts,updated_ts,who,tags"

        val UPGRADE_2 = listOf("ALTER TABLE notes ADD COLUMN who TEXT", "ALTER TABLE notes ADD COLUMN tags TEXT")

        val SCHEMA = listOf(
            "CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, text TEXT NOT NULL, " +
                "when_ts INTEGER, when_end INTEGER, heard_ts INTEGER NOT NULL, source_ids TEXT NOT NULL, " +
                "created_ts INTEGER NOT NULL, updated_ts INTEGER NOT NULL, who TEXT, tags TEXT)",
            "CREATE INDEX notes_kind ON notes(kind, updated_ts)",
            "CREATE VIRTUAL TABLE notes_fts USING fts4(content=\"notes\", text)",
            // the documented FTS4 external-content pattern: remove the old text before the row
            // changes (the index reads it from the content table), add the new text after
            "CREATE TRIGGER notes_bu BEFORE UPDATE ON notes BEGIN DELETE FROM notes_fts WHERE docid=old.id; END",
            "CREATE TRIGGER notes_bd BEFORE DELETE ON notes BEGIN DELETE FROM notes_fts WHERE docid=old.id; END",
            "CREATE TRIGGER notes_au AFTER UPDATE ON notes BEGIN INSERT INTO notes_fts(docid, text) VALUES(new.id, new.text); END",
            "CREATE TRIGGER notes_ai AFTER INSERT ON notes BEGIN INSERT INTO notes_fts(docid, text) VALUES(new.id, new.text); END",
        )

        /**
         * The SQL form of [inWindow]: a WHERE clause and its arguments ("1" = no window).
         * Integers go through CAST because rawQuery binds every argument as text.
         */
        fun windowSql(fromTs: Long?, toTs: Long?): Pair<String, List<String>> {
            if (fromTs == null && toTs == null) return "1" to emptyList()
            val heard = ArrayList<String>(); val span = ArrayList<String>(); val args = ArrayList<String>()
            if (fromTs != null) { heard += "heard_ts>=CAST(? AS INTEGER)"; args += fromTs.toString() }
            if (toTs != null) { heard += "heard_ts<CAST(? AS INTEGER)"; args += toTs.toString() }
            span += "when_ts IS NOT NULL"
            if (toTs != null) { span += "when_ts<CAST(? AS INTEGER)"; args += toTs.toString() }
            if (fromTs != null) { span += "COALESCE(when_end,when_ts)>=CAST(? AS INTEGER)"; args += fromTs.toString() }
            return "((${heard.joinToString(" AND ")}) OR (${span.joinToString(" AND ")}))" to args
        }

        /** Does [n] touch [fromTs, toTs)? Heard in it, or its time span overlaps it. Pure, JVM-tested;
         *  [windowSql] is the same rule in SQL. */
        fun inWindow(n: Note, fromTs: Long?, toTs: Long?): Boolean {
            val heard = (fromTs == null || n.heardTs >= fromTs) && (toTs == null || n.heardTs < toTs)
            val start = n.whenTs ?: return heard
            val end = n.whenEnd ?: start
            return heard || ((toTs == null || start < toTs) && (fromTs == null || end >= fromTs))
        }

        /** The hit for one note; see [NoteHit]. Pure. */
        fun hit(n: Note, zone: ZoneId): NoteHit =
            NoteHit(
                text = n.whenText(zone)?.let { "${n.text} ($it)" } ?: n.text,
                ts = n.whenTs ?: n.heardTs,
                kind = n.kind.key,
            )

        @Volatile private var i: MemoryNotes? = null
        fun get(c: Context) = i ?: synchronized(this) {
            i ?: MemoryNotes(c).also { i = it; it.dropCopiedExamples() }
        }
    }
}
