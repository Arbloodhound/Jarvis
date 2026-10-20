package com.agent.ultra.harness

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * §2 — recoverable delete. The agent never hard-deletes a file: [move] puts it in the trash and
 * records where it came from and when; [list] shows entries newest first; [restore] puts it back
 * (or, when the original place is gone or taken, in `restored/` — it never overwrites anything).
 *
 * Only the user empties the trash ([TrashAdmin.empty], called from Settings). The agent-facing
 * surface is [AgentTrash], which has no empty or purge.
 *
 * [allowedRoots]: only files under these directories can be trashed (the app's own workspace);
 * anything else is refused. Pure file operations; JVM-tested.
 */
class Trash(private val trashDir: File, private val allowedRoots: List<File>) : AgentTrash {

    data class Entry(val id: String, val originalPath: String, val trashedAtMs: Long, val isDirectory: Boolean, val restored: Boolean)

    class NotAllowed(msg: String) : Exception(msg)

    private val index = File(trashDir, "index.json")

    private fun load(): MutableList<Entry> = try {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let {
            Entry(it.getString("id"), it.getString("originalPath"), it.getLong("trashedAtMs"),
                it.optBoolean("isDirectory"), it.optBoolean("restored"))
        } }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    private fun save(all: List<Entry>) {
        trashDir.mkdirs()
        index.writeText(JSONArray().apply { all.forEach { e ->
            put(JSONObject().put("id", e.id).put("originalPath", e.originalPath).put("trashedAtMs", e.trashedAtMs)
                .put("isDirectory", e.isDirectory).put("restored", e.restored))
        } }.toString())
    }

    private fun payload(id: String) = File(File(trashDir, id), "payload")

    @Synchronized
    override fun move(path: File, nowMs: Long): String {
        val f = path.canonicalFile
        if (!f.exists()) throw NotAllowed("nothing at ${path.path}")
        if (allowedRoots.none { root -> f.path.startsWith(root.canonicalPath + File.separator) })
            throw NotAllowed("${path.path} is outside the app's workspace")
        if (f.path.startsWith(trashDir.canonicalPath)) throw NotAllowed("already in the trash")
        val id = "trash:" + java.util.UUID.randomUUID().toString().take(12)
        val dest = payload(id)
        dest.parentFile!!.mkdirs()
        if (!f.renameTo(dest)) {
            // Different filesystem: copy then remove the original only once the copy is complete.
            if (!f.copyRecursively(dest, overwrite = false)) throw NotAllowed("could not move ${path.path}")
            f.deleteRecursively()
        }
        val all = load()
        all += Entry(id, f.path, nowMs, dest.isDirectory, false)
        save(all)
        return id
    }

    override fun list(): List<Entry> = load().filter { !it.restored }.sortedByDescending { it.trashedAtMs }

    /** Returns where the file went back to. Never overwrites: a taken or missing place → restored/. */
    @Synchronized
    override fun restore(id: String): File {
        val all = load()
        val i = all.indexOfFirst { it.id == id && !it.restored }
        if (i < 0) throw NotAllowed("no trash entry $id")
        val e = all[i]
        val src = payload(id)
        val original = File(e.originalPath)
        val target = if (!original.exists() && original.parentFile?.isDirectory == true) original
            else uniqueIn(File(trashDir.parentFile ?: trashDir, "restored"), original.name)
        target.parentFile?.mkdirs()
        if (!src.renameTo(target)) {
            if (!src.copyRecursively(target, overwrite = false)) throw NotAllowed("could not restore $id")
            src.deleteRecursively()
        }
        File(trashDir, id).deleteRecursively()
        all[i] = e.copy(restored = true)
        save(all)
        return target
    }

    private fun uniqueIn(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var f = File(dir, name)
        var n = 1
        while (f.exists()) { f = File(dir, "$base ($n)$ext"); n++ }
        return f
    }

    /** Settings only. */
    internal fun emptyAll(): Int {
        val all = load()
        val active = all.filter { !it.restored }
        active.forEach { File(trashDir, it.id).deleteRecursively() }
        save(emptyList())
        return active.size
    }
}

/** What the agent may do with the trash: move in, look, restore. No empty, no purge. */
interface AgentTrash {
    fun move(path: File, nowMs: Long = System.currentTimeMillis()): String
    fun list(): List<Trash.Entry>
    fun restore(id: String): File
}

/** The user's Settings action. Not exposed as an agent tool. */
object TrashAdmin {
    fun empty(trash: Trash): Int = trash.emptyAll()
}
