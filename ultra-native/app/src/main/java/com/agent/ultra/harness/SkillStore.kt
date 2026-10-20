package com.agent.ultra.harness

import java.io.File

/**
 * Lazy skill loading. The repo has no skill system (checked all 332 files).
 *
 * Contract: discovery scans precedence-tiered roots for SKILL.md files and
 * caches metadata only. The prompt gets a bounded directory (name +
 * description + location). Full bodies load on demand via search + read,
 * capped at 256 KiB. An allowlist restricts which skills a session sees.
 */
class SkillStore(private val allowlist: Set<String>? = null) {

    data class Entry(val name: String, val description: String, val location: File)

    companion object {
        const val MAX_DIR_CHARS = 4000
        const val MAX_READ_BYTES = 256 * 1024
    }

    private val entries = mutableMapOf<String, Entry>()

    /** Scan roots; first root wins on name collisions. Returns entry count. */
    fun discover(roots: List<File>): Int {
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.name == "SKILL.md" }
                .forEach { f ->
                    val fm = parseFrontmatter(f)
                    val name = fm["name"]
                        ?: f.parentFile.name.takeIf { it.isNotEmpty() } ?: return@forEach
                    if (name in entries) return@forEach
                    entries[name] = Entry(name, fm["description"] ?: "", f)
                }
        }
        return entries.size
    }

    private fun eligible(name: String) = allowlist == null || name in allowlist

    /** Bounded directory for prompt injection. Metadata only, never bodies. */
    fun directory(): String {
        val lines = mutableListOf<String>()
        var total = 0
        for (name in entries.keys.sorted()) {
            if (!eligible(name)) continue
            val e = entries.getValue(name)
            val line = "- ${e.name}: ${e.description} [${e.location.path}]"
            if (total + line.length > MAX_DIR_CHARS) break
            lines += line
            total += line.length
        }
        return lines.joinToString("\n")
    }

    /** Lexical search; name matches weigh 2x. */
    fun search(query: String): List<String> {
        val q = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        return entries.values
            .filter { eligible(it.name) }
            .map { e ->
                val nameHits = q.intersect(e.name.lowercase().replace("-", " ").split(" ").toSet()).size
                val descHits = q.intersect(e.description.lowercase().split(Regex("\\s+")).toSet()).size
                Triple(2 * nameHits + descHits, e.name, e)
            }
            .filter { it.first > 0 }
            .sortedWith(compareByDescending<Triple<Int, String, Entry>> { it.first }.thenBy { it.second })
            .map { it.second }
    }

    /** Full SKILL.md, size-capped. Null when missing or not allowed. */
    fun read(name: String): String? {
        val e = entries[name] ?: return null
        if (!eligible(name)) return null
        return try {
            e.location.inputStream().use { ins ->
                val buf = ByteArray(MAX_READ_BYTES + 1)
                var total = 0
                var n = ins.read(buf, total, buf.size - total)
                while (n > 0 && total < buf.size) { total += n; n = ins.read(buf, total, buf.size - total) }
                String(buf, 0, minOf(total, MAX_READ_BYTES), Charsets.UTF_8)
            }
        } catch (_: Exception) { null }
    }

    private fun parseFrontmatter(f: File): Map<String, String> {
        return try {
            val text = f.bufferedReader().use { it.readText().take(4096) }
            if (!text.startsWith("---")) return emptyMap()
            val end = text.indexOf("\n---", 3)
            if (end < 0) return emptyMap()
            text.substring(3, end).lines()
                .mapNotNull { line ->
                    val i = line.indexOf(':')
                    if (i < 0) null else line.substring(0, i).trim() to
                        line.substring(i + 1).trim().trim('"').trim('\'')
                }.toMap()
        } catch (_: Exception) { emptyMap() }
    }
}
