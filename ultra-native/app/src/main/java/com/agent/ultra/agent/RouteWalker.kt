package com.agent.ultra.agent

/**
 * Walking a route the user once showed us.
 *
 * A recorded route is a list of screens, not a list of taps — Android reports a
 * tap only when an app chooses to, so what was captured is where the person
 * ended up, never how. Replaying it therefore means *searching*: from the screen
 * we are on, find the control that leads to the next screen in the route.
 *
 * ## Searching means tapping things, and that is the whole problem
 *
 * A blind search is an agent pressing buttons to see what they do, on someone's
 * real phone, in a banking app they allowed it into. "Delete account" and
 * "Confirm payment" are controls like any other and a search does not know the
 * difference.
 *
 * So the ordering below is not about efficiency. It is about what may be
 * touched at all:
 *
 * - Anything whose label reads like a commitment is **never tapped by the
 *   search**. Not deferred, not confirmed — a search is guessing, and guessing
 *   is not grounds to ask someone to approve a payment. If the route genuinely
 *   ends behind such a button, the walk stops and says so.
 * - Controls the app gave a name are tried before anonymous ones, because a
 *   named control is one the developer thought of as a control.
 * - A wrong guess is undone with `back()` before the next is tried, so the
 *   search leaves the phone where it found it.
 *
 * The result is a walker that can find its way through navigation and refuses
 * to find its way through a transaction.
 */
object RouteWalker {

    /**
     * Words that stop a control being tapped speculatively.
     *
     * Wider than the per-action gate's list on purpose. That gate asks a human
     * about a tap the *model* chose for a reason; this one governs taps chosen
     * by trial and error, where there is no reason at all — so the bar for
     * "leave it alone" is lower.
     */
    private val NEVER_GUESS = listOf(
        "pay", "buy", "purchase", "order", "checkout", "send", "transfer",
        "confirm", "submit", "delete", "remove", "erase", "clear", "reset",
        "sign out", "log out", "logout", "unsubscribe", "cancel subscription",
        "book", "reserve", "accept", "agree", "allow", "grant", "install",
        "uninstall", "call", "dial", "share", "post", "publish", "withdraw",
        "deposit", "confirm and", "place order",
    )

    /**
     * Would tapping this, purely to see what happens, risk doing something?
     *
     * Matched on whole words so "payment history" is safe to open while "pay"
     * is not, and so "account settings" is not caught by "count".
     */
    fun tooRiskyToGuess(label: String): Boolean {
        val l = label.lowercase().trim()
        if (l.isEmpty()) return false
        val words = l.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        for (phrase in NEVER_GUESS) {
            val parts = phrase.split(' ')
            if (parts.size == 1) {
                if (words.any { it == parts[0] }) return true
            } else {
                for (i in 0..(words.size - parts.size).coerceAtLeast(0)) {
                    if (i + parts.size <= words.size &&
                        parts.indices.all { words[i + it] == parts[it] }
                    ) return true
                }
            }
        }
        return false
    }

    /**
     * Controls that make a new context instead of moving within one.
     *
     * These are not dangerous, so banning them would be wrong. They are
     * *unreturnable*: pressing "New tab" gives you a blank page with no
     * history behind it, and back closes the browser rather than going back to
     * where the search started. Measured — a walk tried Chrome's "New tab"
     * on its fifth attempt, could not restore the screen it had been
     * searching, and stopped two controls short of the menu it needed.
     *
     * So they go last. If the route really does lead through a new tab, the
     * search still gets there; it just tries everything it can undo first.
     */
    private val HARD_TO_UNDO = listOf(
        "new tab", "new window", "new incognito", "new private",
        "add", "create", "compose", "new",
    )

    /** Would pressing this leave nowhere to go back to? */
    fun hardToUndo(label: String, vid: String): Boolean {
        val words = "$label $vid".lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        for (phrase in HARD_TO_UNDO) {
            val parts = phrase.split(' ')
            for (i in 0..(words.size - parts.size).coerceAtLeast(0)) {
                if (i + parts.size <= words.size &&
                    parts.indices.all { words[i + it] == parts[it] }
                ) return true
            }
        }
        return false
    }

    /** A control the walker is willing to try, and why it is ranked where it is. */
    data class Candidate(val index: Int, val vid: String, val label: String)

    /**
     * What to try next on this screen, best first.
     *
     * Anything unsafe to guess at is absent rather than last: this list is the
     * set of things that may be touched, not a preference order over all of
     * them.
     */
    fun candidates(
        nodes: List<ScreenStructure.Node>,
        alreadyTried: Set<String> = emptySet(),
    ): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (n in nodes) {
            if (!n.clickable || !n.enabled) continue
            val label = n.label
            val id = n.vid.takeIf { ScreenStructure.looksLikeAName(it) }.orEmpty()
            if (tooRiskyToGuess(label) || tooRiskyToGuess(id)) continue
            val key = if (id.isNotBlank()) "#$id" else "@${n.index}"
            if (key in alreadyTried) continue
            out += Candidate(n.index, id, label.take(40))
        }
        return rank(out)
    }

    /**
     * Candidates taken from the FLAT dump, which is what a tap indexes into.
     *
     * The first version picked candidates out of the screen *tree* and then
     * tapped by index — two different traversals of the same screen, so the
     * index meant a different node in each. Six taps went out in a third of a
     * second and none of them landed, because none of them referred to what the
     * walker thought it was pressing.
     *
     * The same class of mistake as the two flatteners disagreeing on their cap.
     * Choosing and acting must read the same list.
     */
    fun candidatesFromFlat(
        flatJson: String,
        alreadyTried: Set<String> = emptySet(),
        goal: String = "",
    ): List<Candidate> {
        val out = mutableListOf<Candidate>()
        try {
            val arr = org.json.JSONArray(flatJson)
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i) ?: continue
                if (!n.optBoolean("c", false)) continue
                val label = n.optString("t").ifBlank { n.optString("d") }.trim()
                val idx = n.optInt("i", -1)
                if (idx < 0) continue
                val rawVid = n.optString("vid")
                val vid = if (ScreenStructure.looksLikeAName(rawVid)) rawVid else ""
                // The id counts as a name for safety purposes. Plenty of real
                // controls carry no text at all — an icon button called
                // `delete_button` shows nothing a label check can read, and
                // checking only the label would wave it straight through.
                // Underscores split like spaces, so `confirm_payment` is
                // caught the same way the words would be.
                if (tooRiskyToGuess(label) || tooRiskyToGuess(vid)) continue
                val key = if (vid.isNotBlank()) "#$vid" else "@$idx"
                if (key in alreadyTried) continue
                out += Candidate(idx, vid, label.take(40))
            }
        } catch (_: Exception) { return emptyList() }
        return rank(out, goal)
    }

    /**
     * Best first, on two signals and no guesswork about meaning.
     *
     * A control the app named is one its developer thought of as a control; an
     * anonymous clickable View is as likely to be a layout wrapper. And among
     * those, anything a back press can undo comes before anything that cannot.
     */
    private fun rank(out: List<Candidate>, goal: String = ""): List<Candidate> {
        val wanted = meaningfulWords(goal)
        return out.sortedWith(
            compareByDescending<Candidate> { overlap(it, wanted) }
                .thenByDescending { it.vid.isNotBlank() }
                .thenBy { hardToUndo(it.label, it.vid) },
        ).take(MAX_CANDIDATES)
    }

    /**
     * How much a control's name echoes the name the user gave the routine.
     *
     * The only statement of intent anywhere in a replay is what the user
     * called it. "chrome history" and `open_history_menu_id` share a word, and
     * that word is the point of the routine. Using it is not guessing at what
     * a control means — it is reading the one label a person wrote.
     *
     * A hint, never a rule: this reorders candidates and removes none, so a
     * routine whose name shares nothing with any control searches exactly as
     * it did before. Everything unsafe to press is already gone by this stage.
     */
    private fun overlap(c: Candidate, wanted: Set<String>): Int {
        if (wanted.isEmpty()) return 0
        val mine = meaningfulWords(c.label + " " + c.vid)
        return mine.count { it in wanted }
    }

    /**
     * Words worth matching on.
     *
     * Drops the filler that appears in every routine name and every view id
     * alike — "open", "menu", "button", "the" — because a word shared by
     * everything separates nothing.
     */
    internal fun meaningfulWords(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z]+"))
            .filter { it.length > 2 && it !in FILLER }
            .toSet()

    private val FILLER = setOf(
        "the", "and", "for", "with", "run", "open", "show", "get", "got", "goto",
        "menu", "button", "item", "view", "layout", "text", "icon", "img",
        "container", "wrapper", "root", "main", "app", "page", "screen", "tab",
        "list", "row", "cell", "click", "tap", "please", "routine", "again",
    )

    /**
     * The control this hop used last time, if it is on screen and untried.
     *
     * Null when the route has not learned this hop yet, when the app has
     * changed and the control is gone, or when it has already been tried and
     * did not work — in every one of those the walker searches as before. A
     * remembered door is a shortcut, never an instruction.
     */
    fun rememberedChoice(
        flatJson: String,
        via: String,
        alreadyTried: Set<String> = emptySet(),
    ): Candidate? {
        if (via.isBlank() || "#$via" in alreadyTried) return null
        return candidatesFromFlat(flatJson, alreadyTried).firstOrNull { it.vid == via }
    }

    /** Ranking that also knows what the user called the thing they taught. */
    fun candidatesFor(flatJson: String, alreadyTried: Set<String>, goal: String): List<Candidate> =
        candidatesFromFlat(flatJson, alreadyTried, goal)

    /** How a candidate is remembered as tried, so the search does not loop. */
    fun keyOf(c: Candidate): String = if (c.vid.isNotBlank()) "#${c.vid}" else "@${c.index}"

    /**
     * Attempts allowed per hop before the walk gives up and says where it got to.
     *
     * Raised from six after watching a walk exhaust itself on a browser toolbar
     * — reload, home, the address bar, the microphone, the camera — and stop
     * one short of the menu button it needed. Six was an arbitrary number, and
     * the real bound is not the count: every candidate is safe to press by
     * construction, and a wrong one is undone before the next is tried. What
     * limits this is patience, not risk.
     */
    const val TRIES_PER_HOP = 12

    /** More than this on one screen and the search is thrashing, not searching. */
    private const val MAX_CANDIDATES = 12
}
