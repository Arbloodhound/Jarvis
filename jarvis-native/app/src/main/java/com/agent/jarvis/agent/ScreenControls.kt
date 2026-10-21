package com.agent.jarvis.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a screen's controls are. A control is identified by the view id the app gave it
 * (`#search_src_text`), which is stable across visits even when its tree index changes.
 *
 * Only the view id and what kind of control it is are remembered. Never the label, never the
 * contents. Web pages, which rarely give real ids, mostly cannot have their controls remembered.
 */
object ScreenControls {

    enum class Role { INPUT, BUTTON, SCROLLER }

    data class Control(
        val vid: String,
        val role: Role,
        /** Tree index on the screen this was read from. Valid for this read
         * only, never stored. */
        val index: Int = -1,
    )

    /**
     * The actionable, identifiable controls on a screen. A control with no view id is skipped.
     */
    fun of(nodes: List<Node>): List<Control> = nodes.asSequence()
        .filter { it.enabled }
        .filter { it.editable || it.clickable }
        .filter { it.vid.isNotBlank() && ScreenStructure.looksLikeAName(it.vid) }
        .map { Control(it.vid, roleOf(it), it.index) }
        .distinctBy { it.vid }
        .take(MAX_CONTROLS)
        .toList()

    private fun roleOf(n: Node): Role = when {
        n.editable -> Role.INPUT
        else -> Role.BUTTON
    }

    /**
     * The control that best answers what the agent is trying to do.
     *
     * Matching is on the view id alone, so it works on the developer's own
     * words: an intent of "search" finds `search_src_text`, `search_box`,
     * `menu_search`. A whole-word or prefix match, never a loose substring —
     * "add" must not select `address_bar`.
     */
    fun find(controls: List<Control>, intent: String, role: Role? = null): Control? {
        val want = intent.lowercase().trim()
        if (want.isBlank()) return null
        val pool = if (role == null) controls else controls.filter { it.role == role }
        // Whole word first.
        return pool.firstOrNull { words(it.vid).any { w -> w == want } }
            ?: pool.firstOrNull { words(it.vid).any { w -> w.startsWith(want) } }
            ?: pool.firstOrNull { it.vid.lowercase().contains(want) && want.length >= 5 }
    }

    private fun words(vid: String): List<String> = vid.lowercase().split('_', '-', '.')

    // ── Storage ─────────────────────────────────────────────────────

    fun toJson(controls: List<Control>): String {
        val arr = JSONArray()
        for (c in controls) {
            arr.put(JSONObject().put("v", c.vid).put("r", c.role.name))
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<Control> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val role = runCatching { Role.valueOf(o.optString("r")) }.getOrNull()
                ?: return@mapNotNull null
            Control(o.optString("v"), role)
        }
    } catch (_: Exception) { emptyList() }

    /** A screen with more controls than this is a keyboard or a menu of every setting. */
    private const val MAX_CONTROLS = 40
}

private typealias Node = ScreenStructure.Node
