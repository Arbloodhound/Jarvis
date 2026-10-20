package com.agent.jarvis.harness

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * §5 Feed — a personal briefing generated on the scheduler (hourly, job [JOB_ID]). The user owns the
 * feed prompt ([setPrompt] / [resetPrompt]); every unit records the prompt that produced it
 * ([FeedUnit.sourcePrompt]), the user can reorder units, and opens / likes / dismissals are counted.
 *
 * The generator (an LLM call over the user's context) is supplied by the app; this class stores,
 * orders and tracks. A generation run never deletes or reorders what the user arranged: new units
 * go on top. Deleting a unit is the user's action ([removeByUser]). JSON file; JVM-tested.
 */
class Feed(private val file: File) {

    data class FeedUnit(val id: String, val title: String, val body: String, val category: String,
                        val sourcePrompt: String, val createdAtMs: Long,
                        val opens: Int = 0, val likes: Int = 0, val dismissed: Boolean = false)

    data class Draft(val title: String, val body: String, val category: String)

    private var prompt: String = DEFAULT_PROMPT
    private val units = ArrayList<FeedUnit>() // display order, top first

    init { load() }

    fun prompt() = prompt

    fun setPrompt(text: String) { require(text.isNotBlank()); prompt = text.trim(); save() }

    fun resetPrompt() { prompt = DEFAULT_PROMPT; save() }

    /** Units in the user's order, dismissed ones hidden. */
    fun units(): List<FeedUnit> = units.filter { !it.dismissed }

    /**
     * One generation run: [generator] gets the current prompt and the recent titles (to avoid
     * repeats); drafts whose title repeats a recent unit are dropped. Returns the units added.
     */
    suspend fun generate(nowMs: Long, generator: suspend (prompt: String, recentTitles: List<String>) -> List<Draft>): List<FeedUnit> {
        val recent = units.take(30).map { it.title }
        val seen = recent.map(::norm).toMutableSet()
        val added = generator(prompt, recent).filter { it.title.isNotBlank() && seen.add(norm(it.title)) }.map {
            FeedUnit("unit:" + java.util.UUID.randomUUID().toString().take(10), it.title.trim(), it.body.trim(),
                it.category.ifBlank { "general" }, prompt, nowMs)
        }
        units.addAll(0, added)
        while (units.size > MAX_UNITS) units.removeAt(units.lastIndex)
        save()
        return added
    }

    /** Move [id] to [position] (0 = top) in the visible order. */
    fun reorder(id: String, position: Int): Boolean {
        val i = units.indexOfFirst { it.id == id }
        if (i < 0) return false
        val u = units.removeAt(i)
        units.add(position.coerceIn(0, units.size), u)
        save()
        return true
    }

    fun engage(id: String, kind: String): Boolean {
        val i = units.indexOfFirst { it.id == id }
        if (i < 0) return false
        units[i] = when (kind) {
            "open" -> units[i].copy(opens = units[i].opens + 1)
            "like" -> units[i].copy(likes = units[i].likes + 1)
            "dismiss" -> units[i].copy(dismissed = true)
            else -> return false
        }
        save()
        return true
    }

    /** The user deletes a unit (Settings / long-press). Not an agent tool. */
    fun removeByUser(id: String): Boolean = units.removeAll { it.id == id }.also { if (it) save() }

    /** Engagement per category: what the generator should lean toward. */
    fun categoryInterest(): Map<String, Double> = units.groupBy { it.category }.mapValues { (_, us) ->
        us.sumOf { it.opens + 2.0 * it.likes - if (it.dismissed) 2.0 else 0.0 } / us.size
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun load() {
        if (!file.isFile) return
        try {
            val o = JSONObject(file.readText())
            prompt = o.optString("prompt", DEFAULT_PROMPT)
            val a = o.optJSONArray("units") ?: JSONArray()
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                units += FeedUnit(it.getString("id"), it.getString("title"), it.optString("body"), it.optString("category"),
                    it.optString("sourcePrompt"), it.optLong("createdAtMs"), it.optInt("opens"), it.optInt("likes"), it.optBoolean("dismissed"))
            }
        } catch (_: Exception) { /* a corrupt file starts an empty feed; never crashes the app */ }
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("prompt", prompt).put("units", JSONArray().apply { units.forEach { u ->
            put(JSONObject().put("id", u.id).put("title", u.title).put("body", u.body).put("category", u.category)
                .put("sourcePrompt", u.sourcePrompt).put("createdAtMs", u.createdAtMs).put("opens", u.opens)
                .put("likes", u.likes).put("dismissed", u.dismissed))
        } }).toString())
    }

    companion object {
        const val JOB_ID = "harness:feed"
        const val MAX_UNITS = 200
        const val DEFAULT_PROMPT = "A short personal briefing: what changed today in the things I follow, what's coming up, " +
            "and one useful idea. Only from my own notes, goals and the sources I asked for; say where each item came from."

        /** The hourly feed job for the scheduler (owner "harness"). */
        fun job(): CronJob = CronJob(id = JOB_ID, owner = "harness", task = "feed: generate the next units", everySecs = 3600)
    }
}
