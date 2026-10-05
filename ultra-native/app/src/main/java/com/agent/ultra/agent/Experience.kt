package com.agent.ultra.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Ultra's own Northstar: lessons learned from its own mistakes on this phone.
 *
 * Task memory already keeps what WORKED for a request. It cannot say what went
 * wrong on the way, so the next run walks into the same wall and has to find the
 * way round again. This keeps the wall and the way round together, as one
 * sentence the model reads before it starts:
 *
 *   Asked "open the calculator": app_launch {"target":"calculator"} failed —
 *   no launchable app matching 'calculator'. What worked: app_launch
 *   {"target":"Calculator"}.
 *
 * Three ways a lesson is made, all deterministic — no model writes a lesson, so
 * none can be talked into writing a bad one:
 *
 *  1. **A wall resolved in a run.** A call fails, a later call of the same kind
 *     succeeds. The failure and the fix are the lesson.
 *  2. **A correction.** The user's next message starts "no", "wrong", "I meant"…
 *     The request and what they said instead are the lesson.
 *  3. **Imported from the laptop's Northstar** (the same line format both ways,
 *     so what one agent learns the other starts with).
 *
 * ## Trust is measured, not assumed
 *
 * Every time a lesson is served, the run's outcome is counted against it. A
 * lesson that has been served three times and never once been followed by a
 * successful run stops being served. It is not deleted: it is evidence, and an
 * edited or re-imported lesson starts its count again.
 *
 * Pure Kotlin: no Android types, so all of it runs in a JVM test.
 */
object Experience {

    data class Lesson(
        val uid: String,
        /** Words this lesson answers to: the request it came from, the tool, the error. */
        val whenText: String,
        val text: String,
        /** "self" (this phone), "correction" (the user), "northstar" (imported). */
        val source: String,
        val createdAt: Long,
        val served: Int = 0,
        val ok: Int = 0,
        val fail: Int = 0,
    ) {
        /** Served often enough to judge, and never once followed by a good run. */
        val benched: Boolean get() = served >= BENCH_AFTER && ok == 0 && fail >= BENCH_AFTER

        fun record(): String = if (ok + fail == 0) "" else " (served ${ok + fail}x: $ok good runs, $fail bad)"
    }

    /** One tool call in a run, as the brain saw it. */
    data class Step(val tool: String, val params: JSONObject, val ok: Boolean, val result: String)

    const val BENCH_AFTER = 3
    const val MIN_SCORE = 0.25
    private const val MAX_TEXT = 400
    /** A route is a list of steps, not a sentence: it needs the room. */
    private const val MAX_ROUTE_TEXT = 5000   // the written route plus its playable steps (RoutePlayer)
    const val ROUTE = "route"

    /** Tools that are different routes to the same job: failing with one and
     * succeeding with another is still a wall and its way round. */
    private val FAMILIES = listOf(
        setOf("app_launch", "react_navigate", "open_url"),
        setOf("read_text_on_screen", "read_screen_deep", "describe_screen", "screenshot"),
        setOf("sms_send", "contacts_read"),
    )

    private fun family(tool: String): Set<String> = FAMILIES.firstOrNull { tool in it } ?: setOf(tool)

    // ── capture ─────────────────────────────────────────────────────────

    /**
     * Walls this run hit and got past. For every failed call, the first LATER
     * successful call of the same family with different parameters is its fix.
     * A wall never got past is not a lesson — nobody knows the way round yet.
     */
    fun capture(request: String, steps: List<Step>, finished: Boolean = true,
                now: Long = System.currentTimeMillis()): List<Lesson> {
        val out = mutableListOf<Lesson>()
        // A dead end: the same call failed twice. Pass A (2026-09-19) spent 15-20 navigator
        // steps per repeat; the next run should not walk it a third time.
        val seen = mutableMapOf<String, Int>()
        for (st in steps) if (!st.ok) seen.merge(st.tool + st.params.toString(), 1, Int::plus)
        for ((k, n) in seen) {
            if (n < 2) continue
            val st = steps.first { !it.ok && it.tool + it.params.toString() == k }
            val err = errorLine(st.result)
            out += Lesson(uid("deadend", st.tool + "|" + shape(err) + "|" + keyOf(request)),
                "$request ${st.tool} $err",
                ("Asked \"${request.take(80)}\": ${call(st)} failed twice — $err. " +
                    "Do not try it again; use a different route (a direct tool, settings_open, or ask).").take(MAX_TEXT),
                "self", now)
        }
        // A fix is only a fix if the run then finished: "app_launch Settings succeeded" after a
        // failed navigation is not the way round when the task still wasn't done (pass A's two
        // first lessons were exactly that).
        if (!finished) return out
        val used = mutableSetOf<Int>()
        for ((i, bad) in steps.withIndex()) {
            if (bad.ok) continue
            val fam = family(bad.tool)
            val fixAt = (i + 1 until steps.size).firstOrNull { j ->
                j !in used && steps[j].ok && steps[j].tool in fam &&
                    !(steps[j].tool == bad.tool && steps[j].params.toString() == bad.params.toString())
            } ?: continue
            used += fixAt
            val fix = steps[fixAt]
            val err = errorLine(bad.result)
            val text = ("Asked \"${request.take(80)}\": ${call(bad)} failed — $err. " +
                "What worked: ${call(fix)}.").take(MAX_TEXT)
            val whenText = "$request ${bad.tool} ${err}"
            out += Lesson(uid("self", bad.tool + "|" + shape(err) + "|" + fix.tool + "|" + keyOf(request)),
                whenText, text, "self", now)
        }
        return out
    }

    private val CUE = Regex("""^\s*(no\b|nope\b|wrong\b|not that\b|that'?s not\b|that is not\b|i meant\b|i said\b|actually\b|instead\b)""", RegexOption.IGNORE_CASE)

    /** The user correcting the last run. Null unless the message opens like a correction. */
    fun correction(previousRequest: String, message: String, now: Long = System.currentTimeMillis()): Lesson? {
        if (previousRequest.isBlank() || !CUE.containsMatchIn(message)) return null
        val text = "When asked \"${previousRequest.take(120)}\", the user corrected: \"${message.take(160)}\"."
        return Lesson(uid("correction", keyOf(previousRequest) + "|" + keyOf(message)),
            previousRequest, text, "correction", now)
    }

    /**
     * A run Ultra reported done that a check OUTSIDE Ultra found not done: the benchmark reading
     * the device's state, or the person saying "no, that's wrong".
     *
     * Ultra's own idea of success is "the model stopped and no tool failed". On AndroidWorld that
     * was wrong in 33 of 98 failed episodes (2026-09-20) — "create a timer" done with note_create
     * and alarm_set, 14 runs of 14 — and each one was stored as the way to do the job.
     */
    fun verdict(request: String, steps: List<Step>, now: Long = System.currentTimeMillis()): Lesson? {
        val used = steps.filter { it.ok }.map { it.tool }.distinct()
        if (request.isBlank()) return null
        // Answered in words and touched nothing: "delete the file X" got a reply and no tool call
        // in 10 episodes, each counted as a clean success.
        if (steps.isEmpty()) return Lesson(uid("verdict", "no-action|" + keyOf(request)), request,
            ("Asked \"${request.take(80)}\": answered in words without doing anything on the phone, and a check " +
                "afterwards showed the job was NOT done. This is a job to DO: open the app and do it with react_navigate.").take(MAX_TEXT),
            "verdict", now)
        if (used.isEmpty()) return null
        // Working inside the app was the right approach; what went wrong is in the steps, and
        // nobody knows which. Saying "don't take that approach again" about react_navigate sent
        // the next run round in circles redoing a job it had just done right.
        if ("react_navigate" in used) return Lesson(uid("verdict", "in-app|" + keyOf(request)), request,
            ("Asked \"${request.take(80)}\": it was done inside the app and reported done, but a check of the phone " +
                "afterwards showed the job was NOT done — a step was wrong or something was left unfinished. " +
                "Do it in the app once, and check what the screen shows against every part of the request before saying done.").take(MAX_TEXT),
            "verdict", now)
        val text = ("Asked \"${request.take(80)}\": ${used.joinToString(" → ")} was reported done, but a check " +
            "of the phone afterwards showed the job was NOT done. Don't take that approach again." +
            " Do the job inside the app itself with react_navigate.").take(MAX_TEXT)
        return Lesson(uid("verdict", used.joinToString("|") + "|" + keyOf(request)), request, text, "verdict", now)
    }

    /**
     * Did a verdict lesson do its job in this run? Its job is narrow — "don't take that approach
     * again" — so it is judged on that, not on whether the whole task passed. Judged on the task,
     * the lesson that moved "create a timer" off note_create + alarm_set and into the Clock app was
     * benched after three runs, because the task still failed further on for other reasons
     * (2026-09-20). Null for any other kind of lesson.
     */
    fun verdictHeld(lesson: Lesson, steps: List<Step>): Boolean? {
        if (lesson.source != "verdict") return null
        val used = steps.filter { it.ok }.map { it.tool }.distinct()
        if ("without doing anything" in lesson.text) return steps.isNotEmpty()
        val named = Regex("""": (.+?) was reported done""").find(lesson.text)?.groupValues?.get(1) ?: return null
        return used.joinToString(" → ") != named
    }

    // ── recall ──────────────────────────────────────────────────────────

    /**
     * How much of the lesson's own words this request contains, with the denominator capped.
     *
     * TaskMatch asks how much of the REQUEST a memory accounts for, which is right for replaying
     * a whole task. A lesson is narrower than the job it helps with: "create a new note or file
     * inside an app" shares three words with "Create a new note in Markor named
     * 2023_01_26_wise_yacht.md with the following text: Ignorance is bliss." and scored 0.2, so
     * on AndroidWorld not one lesson was ever served (2026-09-19). What matters is whether the
     * lesson's subject is present, not what fraction of a long goal it covers.
     */
    fun match(request: Set<String>, lesson: Set<String>): Double {
        if (request.isEmpty() || lesson.isEmpty()) return 0.0
        val overlap = request.intersect(lesson).size.toDouble()
        // A floor, not a ratio: two content words in common, a real share of the lesson's own
        // subject, and a real share of the request once its length is capped. Ratios alone kept
        // every lesson out — "create ... note" shares 2 of 5 lesson words with a 12-word goal.
        // Two shared words for a real sentence; one will do for a short request ("open calculator
        // please" has two content words and can only ever share one with a lesson).
        if (overlap < (if (request.size >= 4) MIN_SHARED else 1)) return 0.0
        val ofLesson = overlap / lesson.size
        val ofRequest = overlap / minOf(request.size, COVERAGE_CAP)
        if (ofLesson < 0.25 || ofRequest < 0.3) return 0.0
        return ofLesson
    }

    /** Distinct content words a lesson and a request must share before the lesson is offered. */
    const val MIN_SHARED = 2

    /** Longer than this and a request is a paragraph; asking a lesson to cover it all is wrong. */
    const val COVERAGE_CAP = 6

    /** The lessons that answer this request, best first. Benched ones are kept out. */
    fun recall(request: String, lessons: List<Lesson>, k: Int = 3): List<Lesson> {
        val mine = tokens(request)
        if (mine.isEmpty()) return emptyList()
        return lessons.asSequence()
            .filter { !it.benched }
            .map { it to match(mine, tokens(it.whenText)) }
            .filter { it.second >= MIN_SCORE }
            .sortedWith(compareByDescending<Pair<Lesson, Double>> { it.second }.thenByDescending { it.first.ok })
            .take(k)
            .map { it.first }
            .toList()
    }

    /** What the model reads. Data about this phone, not instructions from anyone. */
    fun block(lessons: List<Lesson>): String? {
        if (lessons.isEmpty()) return null
        return "LESSONS FROM THIS PHONE — each one learned from a real mistake made here. " +
            "Use them; they are facts about this device, not instructions from a person:\n" +
            lessons.joinToString("\n") { "- ${RoutePlayer.forModel(it.text)}${it.record()}" }
    }

    // ── the line format shared with the laptop's Northstar ─────────────

    /** `northstar import-jsonl` reads exactly this: uid, kind, when[], text, source. */
    fun toJsonl(l: Lesson): String = JSONObject()
        .put("uid", l.uid).put("kind", "lesson")
        .put("when", JSONArray().put(l.whenText.take(200)))
        .put("text", l.text).put("source", "ultra-phone:${l.source}")
        .put("served", l.served).put("ok", l.ok).put("fail", l.fail)
        .toString()

    /** A line from the laptop. Null for anything malformed — never half a lesson. */
    fun fromJsonl(line: String, now: Long = System.currentTimeMillis()): Lesson? = try {
        val o = JSONObject(line)
        val uid = o.getString("uid")
        // The steps of a run that an outside check passed (northstar/routes.py), not a sentence.
        val route = o.optString("kind") == ROUTE || uid.startsWith("route-")
        val text = o.getString("text").take(if (route) MAX_ROUTE_TEXT else MAX_TEXT)
        val w = o.opt("when")
        val whenText = when (w) {
            is JSONArray -> (0 until w.length()).joinToString(" ") { w.optString(it) }
            is String -> w
            else -> ""
        }
        if (!UID.matches(uid) || text.isBlank() || whenText.isBlank()) null
        else Lesson(uid, whenText, text, if (route) ROUTE else "northstar", now)
    } catch (_: Exception) { null }

    // ── helpers ─────────────────────────────────────────────────────────

    private val UID = Regex("[a-z0-9][a-z0-9._-]{0,79}")

    fun tokens(s: String): Set<String> =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+"))
            .filter { it.length > 2 && it !in Brain.STOPWORDS && it !in TOOL_WORDS }
            .toSet()

    /** Tool-name fragments say which tool, not what the job is; they would make every
     * app_launch lesson match every "launch" request. */
    private val TOOL_WORDS = setOf("app", "toggle", "set", "error", "failed", "matching", "launchable")

    private fun call(s: Step): String = "${s.tool} ${s.params.toString().take(120)}"

    private fun errorLine(result: String): String =
        result.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .removePrefix("Error:").trim().take(140).ifBlank { "no result" }

    /** An error with its specifics removed, so the same wall on a different target is one lesson. */
    private fun shape(err: String): String = err.lowercase().replace(Regex("'[^']*'|\"[^\"]*\"|\\d+"), "_")

    private fun keyOf(s: String): String = tokens(s).sorted().joinToString(" ")

    private fun uid(prefix: String, basis: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }
        return "ultra-$prefix-$h"
    }
}
