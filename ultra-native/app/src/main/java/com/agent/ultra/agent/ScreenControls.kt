package com.agent.ultra.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a screen's controls are.
 *
 * The navigator hands the model a freshly numbered list of everything on
 * screen and asks it to pick an index. Those numbers mean nothing beyond the
 * moment: the same search box is [3] now and [11] after the page shifts, so
 * every visit costs a model call to find a control the agent has already found
 * a hundred times.
 *
 * A control has an identity the app gave it. `#search_src_text` is the search
 * box in that app today, tomorrow and after an update that moves it down the
 * page. Remembering that turns "look at everything and choose" into "use the
 * one you already know".
 *
 * ## What is deliberately not remembered
 *
 * **Only the view id and what kind of control it is.** Never the label, never
 * the contents. A button's label is user content — "Send to Mum", "Pay
 * £240.00", a contact's name — and writing that to disk would turn a
 * structural memory into a record of what the user does. A view id is a
 * constant a developer typed into a layout file; it says where the button is,
 * not what it says.
 *
 * The cost of that line is that web pages, which rarely give real ids, mostly
 * cannot have their controls remembered. That is the right trade.
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
     * The actionable, identifiable controls on a screen.
     *
     * A control with no view id is skipped rather than remembered by label,
     * for the reason in the class comment. It is still perfectly usable in the
     * moment through the ordinary indexed list — this is about what survives
     * to the next visit.
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
        // Whole word first. Prefix matching alone chose `address_bar_container`
        // for "add", because "address" starts with it — and the actual add
        // button was sitting right there in the same list.
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

    /** A screen with more controls than this is a keyboard or a menu of every
     * setting; remembering all of it helps nobody. */
    private const val MAX_CONTROLS = 40
}

private typealias Node = ScreenStructure.Node
