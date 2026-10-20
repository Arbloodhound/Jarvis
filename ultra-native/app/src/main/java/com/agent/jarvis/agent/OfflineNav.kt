package com.agent.jarvis.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Multi-step phone navigation on the on-device thinker (Qwen3-8B), for when there is no cloud
 * model to drive [ReActNavigator]. Pure, JVM-tested: which requests it takes, the compact screen
 * the model reads, the one-line action it must answer with, loop detection and the budgets.
 * [OfflineNavigator] runs the loop on the phone.
 *
 * Why so small: an 8B decodes a few tokens a second on the phone and reads the prompt at a few
 * tens. The cloud observation (every tappable, every read-only line, plan, notes) runs to several
 * thousand characters; here the screen is numbered actionable elements only, each label cut short,
 * under a hard character cap, and the answer is one short line with no reasoning trace.
 *
 * Prompt layout is fixed rules first, then the goal, then the append-only step history, then the
 * screen: the engine keeps the longest common token prefix between calls (ultra_llm.cpp), so each
 * step re-reads only the screen and the newest history line.
 */
object OfflineNav {

    // ── Budgets ─────────────────────────────────────────────────────────────────────────────

    /** Steps the model may take before the run ends. */
    const val MAX_STEPS = 12
    /** Tokens one answer may decode. "type 3 \"a longer search\"" is ~15; done "..." a little more. */
    const val STEP_TOKENS = 64
    /** The whole run, wall clock. At ~25 s a step (see [expectedStepSeconds]) 12 steps fit. */
    const val MAX_MILLIS = 6 * 60_000L

    /** Listed elements at most, and the hard character cap on the whole screen block. */
    const val MAX_ELEMENTS = 30
    const val MAX_SCREEN_CHARS = 1500
    /** Characters of one label; a second label of the same row gets [EXTRA_CHARS]. */
    const val LABEL_CHARS = 40
    const val EXTRA_CHARS = 30
    /** One history line, and how many characters of typed text it quotes. */
    const val HISTORY_LINE_CHARS = 100

    /** Why the run must end now, or null to go on. [stepsDone] = actions already taken. */
    fun budgetStop(stepsDone: Int, elapsedMs: Long, maxSteps: Int = MAX_STEPS, maxMillis: Long = MAX_MILLIS): String? = when {
        stepsDone >= maxSteps -> "ran out of steps ($maxSteps)"
        elapsedMs >= maxMillis -> "ran out of time (${maxMillis / 60_000} min)"
        else -> null
    }

    /**
     * Seconds one step costs on the phone: reading the prompt tokens not already in the KV cache,
     * then decoding the answer. [decodeTokPerSec] is [com.agent.jarvis.local.DecodeSpeed]'s estimate;
     * nothing measures prefill speed, so [prefillTokPerSec] is the caller's assumption. Null when
     * either speed is unknown or not positive.
     */
    fun expectedStepSeconds(newPromptTokens: Int, answerTokens: Int, decodeTokPerSec: Double?, prefillTokPerSec: Double?): Double? {
        if (decodeTokPerSec == null || prefillTokPerSec == null) return null
        if (!(decodeTokPerSec > 0.0) || !(prefillTokPerSec > 0.0)) return null
        return newPromptTokens / prefillTokPerSec + answerTokens / decodeTokPerSec
    }

    // ── Which requests ──────────────────────────────────────────────────────────────────────

    /** A navigation job: [goal] as said, [appHint] the app it names ("" = the screen in front). */
    data class Task(val goal: String, val appHint: String)

    private val POLITE = Regex("^(?:(?:hey\\s+)?jarvis[,\\s]+)?(?:please\\s+|can you\\s+|could you\\s+|would you\\s+)*", RegexOption.IGNORE_CASE)
    private val QUESTION = Regex("^(?:what|why|how|who|when|where|which|is|are|do|does|did|should|explain|tell me about)\\b", RegexOption.IGNORE_CASE)
    /** Verbs that are a thing done on a screen. */
    private val STEP = Regex(
        "^(?:tap|press|click|scroll|swipe|search|find|look up|go to|navigate|select|choose|type|enter|write|" +
            "start|stop|pause|resume|turn|switch|toggle|enable|disable|change|set|add|create|make|delete|remove|" +
            "send|reply|play|check|show|open|clear|reset|sort|filter|mark|share|save|call|text|message|read)\\b",
        RegexOption.IGNORE_CASE,
    )
    /** Driving the screen in front: "tap Start", "scroll down". */
    private val UI_VERB = Regex("^(?:tap|press|click|scroll|swipe)\\b", RegexOption.IGNORE_CASE)
    /** "open Settings and turn on dark mode", "launch chrome, then search cats". */
    private val OPEN_THEN = Regex(
        "^(?:open|launch|start|go\\s+(?:in)?to|switch\\s+to)\\s+(?:the\\s+|my\\s+)?(.{2,40}?)(?:\\s+app)?" +
            "\\s*(?:,\\s*(?:and\\s+)?(?:then\\s+)?|\\s+and\\s+then\\s+|\\s+then\\s+|\\s+and\\s+)(.+)$",
        RegexOption.IGNORE_CASE,
    )
    /** "search for cats in chrome", "start the stopwatch in the clock app". The last "in/on X" wins
     * (greedy first group), and X is one to three words. */
    private val IN_APP = Regex(
        "^(.+)\\s+(?:in|on|inside|using)\\s+(?:the\\s+|my\\s+)?([a-z][a-z0-9.+&'-]*(?:\\s+[a-z0-9.+&'-]+){0,2}?)(?:\\s+app)?\\s*[.!]?$",
        RegexOption.IGNORE_CASE,
    )
    /** "on my phone", "on monday": not an app. */
    private val NOT_AN_APP = Regex(
        "^(?:phone|device|screen|this|it|them|me|silent|vibrate|loud|max|full|repeat|" +
            "morning|afternoon|evening|night|" +
            "monday|tuesday|wednesday|thursday|friday|saturday|sunday|today|tonight|tomorrow|weekdays?|weekends?|" +
            "time|top|the top|the bottom|bottom|mute|speaker|wifi|wi-fi|bluetooth|data|" +
            // On-screen controls, not apps: "type hello in the search box".
            ".*\\b(?:box|field|bar|button|tab|menu|list|page|row|keyboard))$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Is this a job of several steps on screens, and in which app? Null when it is not.
     *
     * Deliberately narrow, and only consulted after the one-shot device commands
     * ([LocalToolCall.offlineRoute]) have had their turn: "turn on wifi" is a tool, not a walk
     * through Settings. Questions are never navigation. The app named by "in X" is a guess the
     * caller must confirm is installed before treating the request as navigation.
     */
    fun task(request: String): Task? {
        val r = request.trim().replace(Regex("\\s+"), " ").let { POLITE.replace(it, "") }.trim().trimEnd('?')
        if (r.isEmpty() || QUESTION.containsMatchIn(r)) return null
        OPEN_THEN.find(r)?.let { m ->
            val app = m.groupValues[1].trim().lowercase()
            val rest = m.groupValues[2].trim()
            if (STEP.containsMatchIn(rest) && !NOT_AN_APP.matches(app)) return Task(r, app)
        }
        IN_APP.find(r)?.let { m ->
            val what = m.groupValues[1].trim()
            val app = m.groupValues[2].trim().lowercase()
            // "turn on X" is a switch, not "in app X": the thing done must be more than a verb.
            if (STEP.containsMatchIn(what) && what.contains(' ') && !NOT_AN_APP.matches(app)) return Task(r, app)
        }
        // "go on Google and search X", "use the browser to search X": the browser, whichever is installed
        // (GrapheneOS has no Google app: [AgentController.findPackage] maps these names to the default browser).
        if (WEB_SEARCH.containsMatchIn(r)) return Task(r, "browser")
        if (UI_VERB.containsMatchIn(r)) return Task(r, "")
        return null
    }

    /** "open X and <step>" or "go on Google and search X": a screen job from its first words, so it goes to
     * the screen driver before the tool loop. Not the looser "… in X" form: in "text Sam I'm on my way",
     * "way" is no app. */
    fun opensAppFirst(request: String): Boolean {
        val r = request.trim().replace(Regex("\\s+"), " ").let { POLITE.replace(it, "") }.trim()
        return OPEN_THEN.find(r)?.let { STEP.containsMatchIn(it.groupValues[2].trim()) } == true || WEB_SEARCH.containsMatchIn(r)
    }

    private val WEB_SEARCH = Regex(
        "\\b(?:go\\s+(?:on|to|onto)|open|use|in|on)\\s+(?:the\\s+|my\\s+)?(?:google|chrome|(?:web\\s+)?browser|the\\s+web|the\\s+internet)\\b.*?\\bsearch(?:\\s+for)?\\s+\\S",
        RegexOption.IGNORE_CASE,
    )

    // ── Perceive ────────────────────────────────────────────────────────────────────────────

    /** One listed element: [n] is what the model says, [node] the flat index the navigator taps. */
    data class Element(val n: Int, val node: Int, val label: String, val typeable: Boolean)

    /** [text] is what the model reads; [hidden] actionable elements did not fit. */
    data class Screen(val text: String, val elements: List<Element>, val hidden: Int) {
        fun element(n: Int): Element? = elements.getOrNull(n - 1)?.takeIf { it.n == n }
    }

    /** Fewer actionable elements than this and the screen is read as a picture as well. */
    const val THIN_ELEMENTS = 3
    const val PICTURE_CHARS = 700

    /**
     * [screen] with what a picture of it shows ([seen]: OCR lines, or the vision model's
     * description) added below the numbered list, marked as not tappable by number. Unchanged when
     * [seen] is blank.
     */
    fun withPicture(screen: Screen, seen: String?): Screen {
        val t = seen?.trim().orEmpty()
        if (t.isEmpty()) return screen
        val block = "Seen in a picture of the screen (read only, NOT tappable by number):\n" +
            (if (t.length <= PICTURE_CHARS) t else t.take(PICTURE_CHARS - 1).trimEnd() + "…")
        return screen.copy(text = screen.text.trimEnd() + "\n" + block)
    }

    private fun cut(s: String, max: Int): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    private fun key(label: String) = label.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun quote(s: String) = s.replace('"', '\'')

    private class Node(val o: JSONObject) {
        val i = o.optInt("i", -1)
        val p = o.optInt("p", -1)
        val label = o.optString("t").ifBlank { o.optString("d") }.trim()
        val clickable = o.optBoolean("c", false)
        val viaParent = o.optBoolean("ca", false)
        val editable = o.optBoolean("e", false)
        val vid = o.optString("vid")
        // The live dump carries centres; captured fixtures only bounds.
        val y = if (o.has("y")) o.optDouble("y", 0.0) else (o.optDouble("tp", 0.0) + o.optDouble("b", 0.0)) / 2
        val x = if (o.has("x")) o.optDouble("x", 0.0)
            else if (o.has("l") && o.has("r")) (o.optDouble("l", 0.0) + o.optDouble("r", 0.0)) / 2 else 0.0
    }

    private class Group(val tapNode: Int, val x: Double, val y: Double, val labels: MutableList<String>, val typeable: Boolean)

    /**
     * The screen as a short numbered list of what can be tapped or typed into.
     *
     * - Only actionable nodes (clickable, clickable through a parent, editable) on the display.
     * - A row whose words sit on children is one element: its first words, plus its second in
     *   parentheses ("Wi-Fi" (Connected)). Words that read as a commitment ([ActionGate]) are never
     *   folded into another element: they are listed, and tapped, as themselves, so the gate judges
     *   the words actually pressed.
     * - The element taps a node that carries its words where there is one, never a wordless
     *   container in its place: the gate reads the tapped node's own words.
     * - A wordless button is named by the app's id for it, else by where it is.
     * - Same words at the same spot (a tab's icon and its text) are listed once.
     * - At most [maxElements], and the block stops before [maxChars]; what did not fit is counted.
     */
    fun compact(
        flatJson: String,
        screenW: Int = 1080,
        screenH: Int = 2400,
        maxElements: Int = MAX_ELEMENTS,
        maxChars: Int = MAX_SCREEN_CHARS,
    ): Screen {
        val nodes = try {
            val arr = JSONArray(flatJson)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::Node) }
        } catch (_: Exception) { emptyList() }
        val byIndex = nodes.associateBy { it.i }
        // The nearest clickable ancestor: the control a child's words belong to.
        fun owner(n: Node): Node {
            if (n.clickable || !n.viaParent) return n
            var cur = byIndex[n.p]
            var guard = 0
            while (cur != null && guard++ < 64) {
                if (cur.clickable) return cur
                cur = byIndex[cur.p]
            }
            return n
        }
        // Containers whose words are on a descendant: listed by those words, not as wordless.
        val wordsBelow = mutableSetOf<Int>()
        for (n in nodes) if (n.label.isNotBlank() && (n.clickable || n.viaParent)) {
            var cur = byIndex[n.p]
            var guard = 0
            while (cur != null && guard++ < 64) { wordsBelow += cur.i; cur = byIndex[cur.p] }
        }
        val groups = LinkedHashMap<Any, Group>()
        for (n in nodes) {
            if (n.y < 0 || n.y > screenH) continue
            if (n.editable) {
                groups[n.i] = Group(n.i, n.x, n.y, mutableListOf<String>().apply { if (n.label.isNotBlank()) add(n.label) }, true)
                continue
            }
            if (!(n.clickable || n.viaParent)) continue
            if (n.label.isBlank()) {
                if (!n.clickable || n.i in wordsBelow) continue
                val vidWords = ReActNavigator.vidAsWords(n.vid).takeIf { it.length in 3..40 && it.any(Char::isLetter) }
                val name = if (vidWords != null) "(icon: $vidWords)" else {
                    val side = if (n.x > screenW * 0.66) "right" else if (n.x < screenW * 0.33) "left" else "middle"
                    val band = if (n.y > screenH * 0.75) "bottom" else if (n.y < screenH * 0.25) "top" else "middle"
                    "(icon, $band $side)"
                }
                groups[n.i] = Group(n.i, n.x, n.y, mutableListOf(name), false)
                continue
            }
            val own = owner(n)
            val g = groups[own.i]
            if (g == null || g.typeable || ActionGate.commitmentIn(n.label) != null) {
                // A commitment word gets its own element unless it is the first words of the row.
                val k: Any = if (g == null) own.i else "n${n.i}"
                groups[k] = Group(n.i, n.x, n.y, mutableListOf(n.label), false)
            } else if (g.labels.none { key(it) == key(n.label) }) {
                g.labels += n.label
            }
        }
        val elements = mutableListOf<Element>()
        val lines = mutableListOf<String>()
        val seen = mutableMapOf<String, MutableList<Pair<Double, Double>>>()
        var used = 0
        var hidden = 0
        for (g in groups.values) {
            val first = g.labels.firstOrNull().orEmpty()
            if (!g.typeable) {
                val at = seen.getOrPut(key(first)) { mutableListOf() }
                if (at.any { (px, py) -> ReActNavigator.sameSpot(px, py, g.x, g.y) }) continue
                at += g.x to g.y
            }
            val n = elements.size + 1
            val main = if (first.startsWith("(icon")) first else "\"${quote(cut(first, LABEL_CHARS))}\""
            val extra = g.labels.getOrNull(1)?.let { " (${quote(cut(it, EXTRA_CHARS))})" }.orEmpty()
            val line = if (g.typeable) "$n [box] " + (if (first.isBlank()) "(empty)" else main) + extra
                else "$n $main$extra"
            if (elements.size >= maxElements || used + line.length + 1 > maxChars) { hidden++; continue }
            used += line.length + 1
            lines += line
            elements += Element(n, g.tapNode, first, g.typeable)
        }
        val text = when {
            elements.isEmpty() -> "(nothing to tap or type into — try scroll down or back)"
            hidden > 0 -> lines.joinToString("\n") + "\n($hidden more not shown — scroll down)"
            else -> lines.joinToString("\n")
        }
        return Screen(text, elements, hidden)
    }

    /**
     * What "the screen changed" and "same screen" are judged on: every word on it, tappable or
     * not, plus the app. A timer's display changes while its keypad does not.
     */
    fun signature(flatJson: String, pkg: String = ""): String = try {
        val arr = JSONArray(flatJson)
        val words = (0 until arr.length()).joinToString("\n") { i ->
            arr.getJSONObject(i).let { it.optString("t") + "|" + it.optString("d") }
        }
        "$pkg#${arr.length()}#${words.length}#${words.hashCode()}"
    } catch (_: Exception) { "$pkg#raw#${flatJson.hashCode()}" }

    // ── Think ───────────────────────────────────────────────────────────────────────────────

    val SYSTEM = """
You operate an Android phone for the user, one action at a time. You see the current screen as a numbered list of things you can tap or type into ([box] = text box). Reply with exactly ONE line, one of:
tap N
type N "text"
scroll down
scroll up
back
home
open_app NAME
done "what was done, or the answer"
fail "why it cannot be done"
Rules: N is a number from the list; only a [box] takes type. No explanation, no other words. Say done only when the screen shows the goal is complete. If a step did nothing, choose something else.
Examples:
tap 4
type 2 "pizza near me"
done "Wi-Fi is on"
""".trim()

    /** The no-think prompt for one step. [history] is append-only; [note] (a parse error) goes last. */
    fun prompt(goal: String, history: List<String>, screen: Screen, app: String, note: String? = null): String {
        val user = buildString {
            append("GOAL: ").append(goal.trim()).append('\n')
            if (history.isNotEmpty()) {
                append("DONE SO FAR:\n")
                history.forEach { append(it).append('\n') }
            }
            append("SCREEN").append(if (app.isNotBlank()) " ($app)" else "").append(":\n")
            append(screen.text).append('\n')
            if (!note.isNullOrBlank()) append("NOTE: ").append(note.trim()).append('\n')
            append("Next action:")
        }
        return LocalPrompt.buildNoThink(SYSTEM, user)
    }

    // ── Act: the action vocabulary ──────────────────────────────────────────────────────────

    sealed class Action {
        data class Tap(val n: Int) : Action()
        data class Type(val n: Int, val text: String) : Action()
        data class Scroll(val down: Boolean) : Action()
        object Back : Action()
        object Home : Action()
        data class OpenApp(val name: String) : Action()
        data class Done(val answer: String) : Action()
        data class Fail(val reason: String) : Action()

        /** The action in the model's own words: history lines and the loop key. */
        fun said(): String = when (this) {
            is Tap -> "tap $n"
            is Type -> "type $n \"$text\""
            is Scroll -> if (down) "scroll down" else "scroll up"
            Back -> "back"
            Home -> "home"
            is OpenApp -> "open_app $name"
            is Done -> "done \"$answer\""
            is Fail -> "fail \"$reason\""
        }
    }

    sealed class Parsed {
        data class Ok(val action: Action) : Parsed()
        /** [why] is told to the model when it is asked once more. */
        data class Bad(val why: String) : Parsed()
    }

    private const val MAX_TYPED = 200
    private val QUOTED = "[\"“”]([^\"“”]*)[\"“”]"
    private val TAP = Regex("^tap\\s*[(\\[]?\\s*(\\d{1,3})\\s*[)\\]]?$", RegexOption.IGNORE_CASE)
    private val TYPE = Regex("^type\\s*[(\\[]?\\s*(\\d{1,3})\\s*[)\\]]?\\s*,?\\s*$QUOTED$", RegexOption.IGNORE_CASE)
    private val SCROLL = Regex("^scroll\\s*\\(?\\s*(up|down)\\s*\\)?$", RegexOption.IGNORE_CASE)
    private val BACK = Regex("^back\\s*(\\(\\s*\\))?$", RegexOption.IGNORE_CASE)
    private val HOME = Regex("^home\\s*(\\(\\s*\\))?$", RegexOption.IGNORE_CASE)
    private val OPEN = Regex("^open_app\\s+[\"“]?([^\"“”]{1,40}?)[\"”]?$", RegexOption.IGNORE_CASE)
    private val DONE = Regex("^done(?:\\s*:?\\s*$QUOTED)?$", RegexOption.IGNORE_CASE)
    private val FAIL = Regex("^fail(?:\\s*:?\\s*$QUOTED)?$", RegexOption.IGNORE_CASE)

    /**
     * The one action in a reply, checked against the screen it was chosen on. Strict: the first
     * non-empty line after any think block, with only a leading "Action:" / bullet / backticks
     * forgiven, must be exactly one form of the vocabulary. A number must be listed; type needs a
     * [box]; typed text is non-empty and at most [MAX_TYPED] characters.
     */
    fun parse(reply: String, screen: Screen): Parsed {
        val line = LocalToolCall.visibleText(reply).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.replace(Regex("^(?:next\\s+)?action\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            ?.trim('`', ' ', '-', '*', '•', '\t')?.trim()
            ?: return Parsed.Bad("the reply was empty")
        if (line.isEmpty()) return Parsed.Bad("the reply was empty")
        TAP.matchEntire(line)?.let { m ->
            val n = m.groupValues[1].toInt()
            screen.element(n) ?: return Parsed.Bad("$n is not on the list")
            return Parsed.Ok(Action.Tap(n))
        }
        TYPE.matchEntire(line)?.let { m ->
            val n = m.groupValues[1].toInt()
            val e = screen.element(n) ?: return Parsed.Bad("$n is not on the list")
            if (!e.typeable) return Parsed.Bad("$n is not a [box]")
            val text = m.groupValues[2]
            if (text.isBlank()) return Parsed.Bad("type needs some text")
            if (text.length > MAX_TYPED) return Parsed.Bad("the text is too long")
            return Parsed.Ok(Action.Type(n, text))
        }
        SCROLL.matchEntire(line)?.let { return Parsed.Ok(Action.Scroll(it.groupValues[1].equals("down", true))) }
        if (BACK.matches(line)) return Parsed.Ok(Action.Back)
        if (HOME.matches(line)) return Parsed.Ok(Action.Home)
        OPEN.matchEntire(line)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isEmpty()) return Parsed.Bad("open_app needs an app name")
            return Parsed.Ok(Action.OpenApp(name))
        }
        DONE.matchEntire(line)?.let { return Parsed.Ok(Action.Done(it.groupValues[1].trim())) }
        FAIL.matchEntire(line)?.let { return Parsed.Ok(Action.Fail(it.groupValues[1].trim())) }
        return Parsed.Bad("\"${cut(line, 40)}\" is not one of the actions")
    }

    /** Note for the one re-ask after a reply that did not parse. */
    fun reaskNote(why: String): String =
        "Your last reply was not usable ($why). Reply with ONE line only, for example: tap 3"

    /**
     * The action in [ReActNavigator]'s own syntax, against the listed node, for the act hook:
     * tap(I), type(I, "text"), scroll(down|up), back(), home(). Null for what is not a screen
     * action (open_app, done, fail) — the caller handles those.
     */
    fun toNavigator(action: Action, screen: Screen): String? = when (action) {
        is Action.Tap -> screen.element(action.n)?.let { "tap(${it.node})" }
        is Action.Type -> screen.element(action.n)?.takeIf { it.typeable }?.let { "type(${it.node}, \"${action.text}\")" }
        is Action.Scroll -> if (action.down) "scroll(down)" else "scroll(up)"
        Action.Back -> "back()"
        Action.Home -> "home()"
        is Action.OpenApp, is Action.Done, is Action.Fail -> null
    }

    /** One history line: the action, what it was ("Wi-Fi"), and what happened. */
    fun historyLine(step: Int, action: Action, screen: Screen, outcome: String): String {
        val what = when (action) {
            is Action.Tap -> screen.element(action.n)?.label?.takeIf { it.isNotBlank() }?.let { " (\"${quote(cut(it, 30))}\")" }.orEmpty()
            is Action.Type -> screen.element(action.n)?.label?.takeIf { it.isNotBlank() }?.let { " into \"${quote(cut(it, 20))}\"" }.orEmpty()
            else -> ""
        }
        val said = if (action is Action.Type) "type ${action.n} \"${cut(action.text, 30)}\"" else action.said()
        return cut("$step. $said$what -> $outcome", HISTORY_LINE_CHARS)
    }

    // ── Verify: loops ───────────────────────────────────────────────────────────────────────

    /**
     * Same screen, same action, twice: the run is looping. The screen is the [signature] (every
     * word on it), so pressing "5" twice on a keypad whose display shows the digits is not a loop;
     * pressing a button that changes nothing, twice, is.
     */
    class LoopGuard {
        private val tried = mutableSetOf<String>()

        /** Record [action] on [screenSig]; true when it was already tried there. */
        fun repeated(screenSig: String, action: Action): Boolean = !tried.add(screenSig + "\u0000" + action.said())
    }
}
