package com.agent.ultra.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * A route through an app, remembered as the screens it passes through.
 *
 * This replaces watching for taps. Android reports `TYPE_VIEW_CLICKED` at each
 * app's discretion and most controls never fire it — measured on this phone,
 * four deliberate taps in Chrome produced one event, and taps inside web
 * content produce none at all, because a rendered page is a compositor surface
 * rather than a tree of clickable views. A recording built from those events
 * sees some of what a person did, never all of it, and cannot tell which.
 *
 * Screens are different. Every one can be read whenever it is on top, and
 * [ScreenSignature] already fingerprints one independently of its content, so
 * the alarms list is the alarms list whether it holds three alarms or nine.
 * Watching where someone *ended up* is reliable in a way that watching how they
 * got there is not.
 *
 * ## What a journey is for
 *
 * Two things, and the second matters more than the first.
 *
 * It is a route: Chrome, then the menu, then History. Replaying it means
 * getting from each screen to the next, which the agent can search for using
 * the controls it already remembers per screen.
 *
 * And it is **certainty about arrival**. A plan's checkpoints currently come
 * from the model writing down text it expects to see, which it gets wrong in
 * three separate ways — it writes "none" when asked for a fallback, it writes a
 * paragraph of prose, and it writes the same expectation for two different
 * stages. A remembered fingerprint is not a guess. When the screen matches, the
 * stage is done, and no model turn was spent deciding.
 *
 * ## What is kept
 *
 * Fingerprints and package names. A fingerprint is a hash of which view ids and
 * widget kinds a screen is built from — it cannot be turned back into anything
 * that was on the screen, and deliberately excludes all text so that a new
 * message does not make an inbox a different screen.
 */
object ScreenJourney {

    /**
     * One place the user arrived at, and how the walker got there.
     *
     * `via` is filled in the first time a route is successfully walked: the
     * app's own id for the control that led here from the previous screen. It
     * turns the second walk from a search into a lookup — the difference
     * between an agent pressing six buttons to find a door and one that
     * remembers which door it is.
     *
     * **Only an id is ever stored, never a position.** A control's index is a
     * position in one reading of one screen and means something different the
     * next time; remembering it would be remembering noise, and the walker
     * would trust it. A control with no id simply stays unremembered and is
     * searched for again, which is no worse than before.
     */
    data class Waypoint(
        val pkg: String,
        val digest: String,
        val confidence: String,
        val via: String = "",
    ) {
        val key: String get() = "$pkg/$digest"
    }

    /**
     * Add a screen to a route, if it is somewhere new.
     *
     * A person sits on a screen for seconds while a sampler looks many times;
     * arriving once is one waypoint. Returning to a screen already visited is
     * kept, because going back is a real step in a route — only an unbroken
     * repeat of where we already are is dropped.
     */
    fun append(route: List<Waypoint>, next: Waypoint?): List<Waypoint> {
        if (next == null || !next.usable) return route
        if (route.lastOrNull()?.key == next.key) return route
        if (route.size >= MAX_WAYPOINTS) return route
        return route + next
    }

    private val Waypoint.usable: Boolean
        get() = pkg.isNotBlank() && digest.isNotBlank()

    /** Read the screen in front as a waypoint, or null if it cannot be read. */
    fun waypointOf(pkg: String, nodes: List<ScreenStructure.Node>): Waypoint? {
        if (pkg.isBlank() || nodes.isEmpty()) return null
        val fp = ScreenSignature.of(pkg, nodes)
        if (!fp.known) return null
        return Waypoint(pkg, fp.digest, fp.confidence.name)
    }

    /**
     * Is this route worth keeping?
     *
     * One screen is not a journey — it is where the phone happened to be. Two
     * means something actually happened.
     */
    fun worthKeeping(route: List<Waypoint>): Boolean = route.size >= 2

    /** Does the screen in front match where this stage was meant to arrive? */
    fun arrivedAt(target: Waypoint, pkg: String, nodes: List<ScreenStructure.Node>): Boolean {
        val here = waypointOf(pkg, nodes) ?: return false
        return here.key == target.key
    }

    fun toJson(route: List<Waypoint>): String {
        val arr = JSONArray()
        for (w in route) {
            arr.put(JSONObject().put("pkg", w.pkg).put("d", w.digest)
                .put("c", w.confidence).put("via", w.via))
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<Waypoint> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Waypoint(it.optString("pkg"), it.optString("d"), it.optString("c"), it.optString("via"))
            }
        }.filter { it.usable }
    } catch (_: Exception) { emptyList() }

    /**
     * How a route reads when the user asks what was learned.
     *
     * A fingerprint is meaningless to a person, so it says how many screens and
     * which apps rather than pretending the hash means something.
     */
    fun describe(route: List<Waypoint>): String {
        if (route.isEmpty()) return "no screens were recorded"
        val apps = route.map { it.pkg }.distinct()
        val hops = route.size
        return "$hops screen${if (hops == 1) "" else "s"}, " +
            (if (apps.size == 1) "all in ${apps.first()}" else "across ${apps.joinToString(" → ")}")
    }

    /** Has this route learned anything the stored one does not know? */
    fun learnedSomethingNew(before: List<Waypoint>, after: List<Waypoint>): Boolean {
        if (before.size != after.size) return false
        return before.indices.any { before[it].via != after[it].via && after[it].via.isNotBlank() }
    }

    /**
     * Doors that were learned once and are somewhere else now.
     *
     * Not the same as learning a door for the first time, and worth counting
     * separately: a blank hop becoming known is the agent getting better at a
     * route, while a known hop changing is the *app* changing underneath it.
     * Only the second is a reason to tell the user anything.
     *
     * The earlier version of `learnedSomethingNew` only noticed blank becoming
     * non-blank, so when a control moved the walk found the new way, reported
     * success, and then threw the discovery away — leaving the agent to search
     * for the same door again on every future run, permanently.
     */
    fun doorsThatMoved(before: List<Waypoint>, after: List<Waypoint>): Int {
        if (before.size != after.size) return 0
        return before.indices.count {
            before[it].via.isNotBlank() &&
                after[it].via.isNotBlank() &&
                before[it].via != after[it].via
        }
    }

    /** Long enough to hold a real task, short enough that a wander is not one. */
    private const val MAX_WAYPOINTS = 24
}
