package com.agent.ultra.agent

import com.agent.ultra.data.RecipeDao
import com.agent.ultra.data.RecipeEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recipes — named, replayable tool sequences.
 *
 * Task memory already learns *which* tools succeeded for a request. A recipe is
 * the user promoting one of those runs into something they can name and re-run:
 * "save that as morning briefing" → "run morning briefing".
 *
 * A recipe stores the exact calls, arguments included. That matters for the
 * policy gate: on replay the arguments no longer appear in the user's words
 * ("run morning briefing" contains no URL), so every traceability contract
 * would block. The stored steps were user-attested when the recipe was
 * created — the user watched them run and then named them — so replay mints
 * them into the episode as confirmed targets. Nothing else is granted: taint,
 * spoof, and undeclared-tool checks all still apply to the replayed calls.
 */
class Recipes(private val dao: RecipeDao) {

    data class Step(val tool: String, val params: JSONObject)

    /**
     * Tools that manage the agent rather than do anything.
     *
     * Saving these as a routine produces something absurd: the store came out
     * of a test session holding two entries whose only step was "start
     * watching". Running one starts a recording, reports success, and does
     * nothing the user wanted — a routine that is purely a way to confuse
     * yourself later.
     */
    private val META_TOOLS = setOf(
        "watch_me", "stop_watching", "cancel_watching",
        "recipe_save", "recipe_run", "recipe_list", "recipe_delete",
    )

    suspend fun save(rawName: String, rawSteps: List<Step>): String {
        val name = normalizeForName(rawName)
        if (name.isEmpty()) return "Error: a recipe needs a name"
        val steps = rawSteps.filterNot { it.tool in META_TOOLS }
        if (steps.isEmpty()) return "Error: there is nothing to save — this conversation " +
            "has not done anything yet, only asked me to manage routines"
        val arr = JSONArray()
        for (s in steps) {
            arr.put(JSONObject().put("tool", s.tool).put("params", s.params))
        }
        val existing = dao.byName(name)
        // A taught route is not something to overwrite with a tool list that
        // happens to share a name. The user spent time showing it.
        if (existing != null && journeyOf(name) != null) {
            return "Error: \"$name\" is a route you showed me. Pick another name, or " +
                "delete that one first if you meant to replace it."
        }
        dao.upsert(
            RecipeEntity(
                name = name,
                stepsJson = arr.toString(),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                lastRun = existing?.lastRun ?: 0L,
                runCount = existing?.runCount ?: 0,
            )
        )
        val verb = if (existing == null) "Saved" else "Updated"
        return "$verb recipe \"$name\": ${steps.joinToString(" → ") { it.tool }}"
    }

    /**
     * Keep a routine that was learned by watching.
     *
     * Same table as a spoken recipe, so there is one place to look and one
     * "list my routines" that shows everything. The steps are UI steps rather
     * than tool calls, so the JSON is stored under a marker key: a runner can
     * tell the two apart without guessing, and an old build reading a new row
     * sees an empty tool list rather than misinterpreting it.
     */
    suspend fun saveJourney(rawName: String, routeJson: String, stepsJson: String): String {
        val name = normalizeForName(rawName)
        if (name.isEmpty()) return "Error: a routine needs a name"
        val payload = JSONObject()
            .put("journey", JSONArray(routeJson))
            .put("demonstrated", JSONArray(stepsJson))
            .toString()
        val existing = dao.byName(name)
        dao.upsert(
            RecipeEntity(
                name = name,
                stepsJson = payload,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                lastRun = existing?.lastRun ?: 0L,
                runCount = existing?.runCount ?: 0,
            )
        )
        return name
    }

    /**
     * Update just the route of a taught routine, keeping everything else.
     *
     * Used after a walk that worked out which control leads where. Rewriting
     * the whole row would silently drop the recorded taps stored beside it —
     * they are not relied on, but quietly discarding stored data because it is
     * currently unused is how data goes missing.
     */
    suspend fun updateJourney(rawName: String, routeJson: String): Boolean {
        val name = normalizeForName(rawName)
        val row = dao.byName(name) ?: return false
        return try {
            val existing = JSONObject(row.stepsJson)
            existing.put("journey", JSONArray(routeJson))
            dao.upsert(row.copy(stepsJson = existing.toString()))
            true
        } catch (_: Exception) { false }
    }

    /** The route of a routine learned by watching, or null if it is a tool recipe. */
    suspend fun journeyOf(rawName: String): String? {
        val row = dao.byName(normalize(rawName)) ?: return null
        return try {
            val o = JSONObject(row.stepsJson)
            if (o.has("journey")) o.getJSONArray("journey").toString() else null
        } catch (_: Exception) { null }
    }

    /** The UI steps of a routine learned by watching, or null if it is a tool recipe. */
    suspend fun demonstrationOf(rawName: String): String? {
        val row = dao.byName(normalize(rawName)) ?: return null
        return try {
            val o = JSONObject(row.stepsJson)
            if (o.has("demonstrated")) o.getJSONArray("demonstrated").toString() else null
        } catch (_: Exception) { null }
    }

    suspend fun stepsOf(rawName: String): List<Step>? {
        val row = dao.byName(normalize(rawName)) ?: return null
        return parse(row.stepsJson)
    }

    /**
     * Fuzzy lookup so "run my morning briefing" finds "morning briefing".
     *
     * The most specific name wins. Taking the first match in whatever order the
     * database returned meant "run my morning sites" could resolve to an older
     * routine called "morning sites" while one called "my morning sites" sat
     * right there — and the agent then ran something the user did not ask for
     * and reported success.
     *
     * Same shape as the task-memory bug: a shorter remembered thing claiming a
     * request that merely contains it. Length is the tiebreak because a longer
     * name that still fits is carrying more of what was actually said.
     */
    suspend fun resolve(spoken: String): RecipeEntity? {
        val n = normalize(spoken)
        if (n.isEmpty()) return null
        dao.byName(n)?.let { return it }
        val all = dao.list()
        return all.filter { n.contains(it.name) }.maxByOrNull { it.name.length }
            ?: all.filter { it.name.contains(n) }.minByOrNull { it.name.length }
    }

    suspend fun markRun(name: String) {
        val row = dao.byName(name) ?: return
        dao.upsert(row.copy(lastRun = System.currentTimeMillis(), runCount = row.runCount + 1))
    }


    /**
     * Record what a walk actually did, and keep whatever it worked out.
     *
     * Replaces a bare run counter. A count of runs cannot tell "walked it end
     * to end" from "gave up on the first hop", and an agent that reports the
     * second as the first is worse than one that reports nothing.
     *
     * Stored inside the recipe payload rather than in new columns: the payload
     * is already a JSON object built to hold more than one thing, and a
     * schema migration to hold three integers would be a migration written for
     * the convenience of the writer.
     */
    suspend fun noteWalk(
        rawName: String,
        completed: Boolean,
        routeJson: String,
        doorsMoved: Int,
    ): Boolean {
        val row = dao.byName(normalizeForName(rawName)) ?: return false
        return try {
            val payload = JSONObject(row.stepsJson)
            payload.put("journey", JSONArray(routeJson))
            val history = payload.optJSONObject("history") ?: JSONObject()
            history.put("attempted", history.optInt("attempted") + 1)
            if (completed) history.put("completed", history.optInt("completed") + 1)
            if (doorsMoved > 0) history.put("moved", history.optInt("moved") + doorsMoved)
            payload.put("history", history)
            dao.upsert(
                row.copy(
                    stepsJson = payload.toString(),
                    lastRun = System.currentTimeMillis(),
                    runCount = row.runCount + 1,
                )
            )
            true
        } catch (_: Exception) { false }
    }

    /** What the agent knows about a taught routine, or null if it is a tool recipe. */
    suspend fun competenceOf(rawName: String): Competence? {
        val row = dao.byName(normalize(rawName)) ?: return null
        return competenceIn(row.stepsJson)
    }

    /** The same, read straight from a stored payload. */
    private fun competenceIn(stepsJson: String): Competence? {
        val route = routeIn(stepsJson) ?: return null
        val history = try { JSONObject(stepsJson).optJSONObject("history") } catch (_: Exception) { null }
        return Competence.of(
            route,
            walksCompleted = history?.optInt("completed") ?: 0,
            walksAttempted = history?.optInt("attempted") ?: 0,
            doorsMoved = history?.optInt("moved") ?: 0,
        )
    }

    /** The route stored inside a recipe payload, or null if it holds tool steps. */
    private fun routeIn(stepsJson: String): List<ScreenJourney.Waypoint>? = try {
        val o = JSONObject(stepsJson)
        if (o.has("journey")) ScreenJourney.fromJson(o.getJSONArray("journey").toString()) else null
    } catch (_: Exception) { null }

    suspend fun list(): String {
        val all = dao.list()
        if (all.isEmpty()) return "No recipes saved yet. Run a task, then say: save that as <name>"
        return "Saved recipes:\n" + all.joinToString("\n") { r ->
            // A routine taught by demonstration has no tool steps — it is a
            // route through screens. Listing it by its (empty) step list
            // printed a bare name and nothing else, so the one place that
            // answers "what have I taught you" said nothing about the things
            // actually taught.
            val route = routeIn(r.stepsJson)
            if (route != null) {
                // A taught routine is described by what the agent knows about
                // it, not by how many screens it happens to contain. "3
                // screens" tells the user nothing they can act on; "you showed
                // me this, but I have never walked it myself" does.
                "• ${r.name}: ${ScreenJourney.describe(route)} — " +
                    (competenceIn(r.stepsJson)?.describe() ?: "")
            } else {
                val steps = parse(r.stepsJson).joinToString(" → ") { it.tool }
                val runs = if (r.runCount > 0) " (run ${r.runCount}×)" else ""
                "• ${r.name}: ${steps.ifBlank { "nothing recorded" }}$runs"
            }
        }
    }

    suspend fun delete(rawName: String): String {
        val name = normalize(rawName)
        val row = dao.byName(name) ?: return "Error: no recipe named \"$name\""
        dao.delete(row.name)
        return "Deleted recipe \"${row.name}\""
    }

    private fun parse(json: String): List<Step> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val tool = o.optString("tool")
            if (tool.isBlank()) null
            else Step(tool, o.optJSONObject("params") ?: JSONObject())
        }
    } catch (_: Exception) { emptyList() }

    companion object {
        /** Recipe names are matched loosely — strip filler and punctuation. */
        /**
         * The form used to LOOK SOMETHING UP.
         *
         * Strips the words people put in front of a name when asking for it —
         * "run", "my", "the" — so that "run my morning briefing" finds
         * "morning briefing".
         */
        fun normalize(s: String): String = s.lowercase()
            .replace(Regex("^(run|start|do|play|execute)\\s+"), "")
            .replace(Regex("^(my|the)\\s+"), "")
            .replace(Regex("\\s+recipe$"), "")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        /**
         * The form a routine is STORED under.
         *
         * Deliberately different, and the difference matters. Stripping "my"
         * when naming meant "my morning sites" and "morning sites" were the
         * same routine: saving one silently replaced the other, and the agent
         * then ran something the user had not asked for. Measured on the phone
         * — a newly taught route overwrote an older routine of nearly the same
         * name without a word about it.
         *
         * A name the user chose is theirs. Only the leading command verb goes,
         * because "run the school run" is a request wrapped around a name, not
         * a name.
         */
        fun normalizeForName(s: String): String = s.lowercase()
            .replace(Regex("^(run|start|do|play|execute)\\s+"), "")
            .replace(Regex("\\s+recipe$"), "")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
