package com.agent.jarvis.harness

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * §5 Ideas — cards drawn from the user's real context (notes, goals, recent work), each rated for
 * feasibility, personal fit and novelty (0..1), ranked, and either acted on or dismissed by the user.
 *
 * Rules: an idea must cite at least one source from the user's context (no free-floating
 * suggestions); a near-duplicate of an open or dismissed idea isn't added again (no nagging);
 * dismissing one idea never mutes a whole topic. Ranking is a labelled formula ([RANK_RULE]).
 * The idea generator (LLM) is supplied by the app. JSON file; JVM-tested.
 */
class Ideas(private val file: File) {

    enum class State { OPEN, ACTED, DISMISSED }

    data class Idea(val id: String, val title: String, val why: String, val sources: List<String>,
                    val feasibility: Double, val fit: Double, val novelty: Double,
                    val state: State = State.OPEN, val createdAtMs: Long, val dismissReason: String = "") {
        val score: Double get() = W_FIT * fit + W_FEASIBILITY * feasibility + W_NOVELTY * novelty
    }

    private val ideas = ArrayList<Idea>()

    init { load() }

    /** Adds a candidate. Null when it was refused: no source, a rating out of range, or a duplicate. */
    fun add(title: String, why: String, sources: List<String>, feasibility: Double, fit: Double, novelty: Double,
            nowMs: Long = System.currentTimeMillis()): Idea? {
        if (title.isBlank() || sources.none { it.isNotBlank() }) return null
        if (listOf(feasibility, fit, novelty).any { it !in 0.0..1.0 }) return null
        val key = norm(title)
        if (ideas.any { it.state != State.ACTED && similar(norm(it.title), key) }) return null
        val idea = Idea("idea:" + java.util.UUID.randomUUID().toString().take(10), title.trim(), why.trim(),
            sources.filter { it.isNotBlank() }, feasibility, fit, novelty, State.OPEN, nowMs)
        ideas += idea
        save()
        return idea
    }

    /** Open ideas, best first by [RANK_RULE]; ties broken newest first. */
    fun ranked(limit: Int = 10): List<Idea> = ideas.filter { it.state == State.OPEN }
        .sortedWith(compareByDescending<Idea> { it.score }.thenByDescending { it.createdAtMs }).take(limit)

    fun act(id: String): Boolean = set(id) { it.copy(state = State.ACTED) }

    fun dismiss(id: String, reason: String = ""): Boolean = set(id) { it.copy(state = State.DISMISSED, dismissReason = reason) }

    fun get(id: String): Idea? = ideas.firstOrNull { it.id == id }

    fun history(): List<Idea> = ideas.filter { it.state != State.OPEN }.sortedByDescending { it.createdAtMs }

    private fun set(id: String, f: (Idea) -> Idea): Boolean {
        val i = ideas.indexOfFirst { it.id == id && it.state == State.OPEN }
        if (i < 0) return false
        ideas[i] = f(ideas[i]); save(); return true
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]+"), " ").split(Regex("\\s+"))
        .filter { it.length > 2 && it !in STOP }.toSortedSet().joinToString(" ")

    /** Same idea in other words: ≥ 70% word overlap (Jaccard). */
    private fun similar(a: String, b: String): Boolean {
        val x = a.split(' ').filter { it.isNotEmpty() }.toSet(); val y = b.split(' ').filter { it.isNotEmpty() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return a == b
        return (x intersect y).size.toDouble() / (x union y).size >= 0.7
    }

    private fun load() {
        if (!file.isFile) return
        try {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                val src = o.optJSONArray("sources") ?: JSONArray()
                ideas += Idea(o.getString("id"), o.getString("title"), o.optString("why"),
                    (0 until src.length()).map { src.getString(it) }, o.optDouble("feasibility"), o.optDouble("fit"),
                    o.optDouble("novelty"), State.valueOf(o.optString("state", "OPEN")), o.optLong("createdAtMs"), o.optString("dismissReason"))
            }
        } catch (_: Exception) { }
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(JSONArray().apply { ideas.forEach { i ->
            put(JSONObject().put("id", i.id).put("title", i.title).put("why", i.why).put("sources", JSONArray(i.sources))
                .put("feasibility", i.feasibility).put("fit", i.fit).put("novelty", i.novelty).put("state", i.state.name)
                .put("createdAtMs", i.createdAtMs).put("dismissReason", i.dismissReason))
        } }.toString())
    }

    companion object {
        const val W_FIT = 0.4
        const val W_FEASIBILITY = 0.35
        const val W_NOVELTY = 0.25
        const val RANK_RULE = "0.40 × personal fit + 0.35 × feasibility + 0.25 × novelty — an order, not a verdict"
        private val STOP = setOf("the", "and", "for", "with", "your", "you", "this", "that", "from", "into", "about")
        const val JOB_ID = "harness:ideas"
    }
}
