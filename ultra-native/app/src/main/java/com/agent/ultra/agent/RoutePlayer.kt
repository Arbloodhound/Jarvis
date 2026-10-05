package com.agent.ultra.agent

import org.json.JSONObject

/**
 * A route a check has already passed, played by the engine instead of re-read by a model.
 *
 * A route is the steps of a run that something OUTSIDE Ultra said did the job (northstar/routes.py
 * makes them from benchmark-passed episodes). Shown to a weak model as text, it helped a little:
 * llama-3.3-70b keyed a timer in correctly from one and then didn't know it had finished, wrote
 * `type(1,"name")` where the route said `type("name")`, and cut its own tool call short
 * (2026-09-20). Steps known to work are structure, and the engine owns structure — the same rule
 * that makes a saved recipe a lookup and not a judgment call (Brain.tryRecipeShortcut).
 *
 * Nothing here touches the phone. This turns a route and the person's request into the concrete
 * actions the navigator already knows how to perform — every one of which still goes through
 * the navigator's own checks (the action gate before a commitment, the typing check before a
 * keystroke). When anything doesn't fit — the request doesn't match the route's template, a step
 * can't be said as an action — the answer is "don't play", and the model carries on as before.
 */
object RoutePlayer {

    data class Op(val op: String, val words: String = "", val text: String = "", val dir: String = "",
                  val slots: List<String> = emptyList(), val box: Int = 0)
    data class Call(val tool: String, val app: String, val steps: List<Op>)
    data class Route(val template: String, val calls: List<Call>, val practice: Map<String, String> = emptyMap())

    private const val MARK = "STEPS: "

    /** The lesson as the model should read it: without the line that is only for the engine. */
    fun forModel(lessonText: String): String =
        lessonText.lineSequence().filterNot { it.startsWith(MARK) }.joinToString("\n")

    fun parse(lessonText: String): Route? = try {
        val line = lessonText.lineSequence().firstOrNull { it.startsWith(MARK) }
        if (line == null) null else {
            val o = JSONObject(line.removePrefix(MARK))
            val calls = o.getJSONArray("calls")
            Route(o.getString("template"), (0 until calls.length()).map { i ->
                val c = calls.getJSONObject(i)
                val steps = c.optJSONArray("steps")
                Call(c.optString("tool"), c.optString("app"), (0 until (steps?.length() ?: 0)).map { j ->
                    val s = steps!!.getJSONObject(j)
                    val sl = s.optJSONArray("slots")
                    Op(s.optString("op"), s.optString("words"), s.optString("text"), s.optString("dir"),
                        (0 until (sl?.length() ?: 0)).map { sl!!.getString(it) }, s.optInt("box", 0))
                })
            }, o.optJSONObject("practice")?.let { p -> p.keys().asSequence().associateWith { p.optString(it) } } ?: emptyMap())
        }
    } catch (_: Exception) { null }

    /**
     * The values in this request, by the route's template. Null when the request is not an
     * instance of the template — then the route is not for this request and must not be played.
     */
    fun bind(template: String, request: String): Map<String, String>? {
        val slot = Regex("<(v\\d+)>")
        val names = slot.findAll(template).map { it.groupValues[1] }.toList()
        if (names.size != names.toSet().size) return null
        val sb = StringBuilder("^\\s*")
        var at = 0
        for (m in slot.findAll(template)) {
            sb.append(literal(template.substring(at, m.range.first)))
            sb.append("(.+?)")
            at = m.range.last + 1
        }
        sb.append(literal(template.substring(at)))
        sb.append("\\s*$")
        val found = Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(request.trim()) ?: return null
        return names.mapIndexed { i, n -> n to found.groupValues[i + 1].trim() }.toMap()
    }

    /**
     * A route generalises VALUES, not KINDS. The `.md` route types a name without its extension and
     * lets the dialog add `.md`; played for a `.txt` request it made the wrong file, perfectly
     * (P8, 2026-09-20). So before anything is played the new values are compared in kind with the
     * ones the route was learned from: the same file extension, a number where there was a number.
     */
    fun sameKind(route: Route, values: Map<String, String>): Boolean = route.practice.all { (slot, was) ->
        val now = values[slot] ?: return@all false
        fun ext(s: String) = Regex("""\.([A-Za-z0-9]{1,4})[.,;:!?]*$""").find(s.trim())?.groupValues?.get(1)?.lowercase().orEmpty()
        ext(was) == ext(now) && was.trim().all(Char::isDigit) == now.trim().all(Char::isDigit)
    }

    /** Literal text of a template as a pattern: spacing is free, a closing full stop is optional. */
    private fun literal(text: String): String {
        if (text.isEmpty()) return ""
        val closing = text.trimEnd().takeLastWhile { it in ".!?" }
        val body = text.trimEnd().dropLast(closing.length)
        val parts = body.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("\\s+") { Regex.escape(it) }
        val lead = if (text.first().isWhitespace() && parts.isNotEmpty()) "\\s+" else ""
        val trail = if (text.last().isWhitespace()) "\\s+" else ""
        val end = if (closing.isEmpty()) "" else "(?:" + Regex.escape(closing) + ")?"
        return lead + parts + end + (if (closing.isEmpty()) trail else "\\s*")
    }

    /** `<v1>`, `<v1|strip>` (no closing punctuation), `<v1|stem>` (no file extension). Null if a value is missing. */
    fun fill(text: String, values: Map<String, String>): String? {
        var missing = false
        val out = Regex("<(v\\d+)(\\|strip|\\|stem)?>").replace(text) { m ->
            val v = values[m.groupValues[1]]
            if (v == null) { missing = true; "" } else when (m.groupValues[2]) {
                "|strip" -> v.trimEnd('.', ',', ';', ':', '!', '?')
                "|stem" -> v.trimEnd('.', ',', ';', ':', '!', '?').substringBeforeLast('.')
                else -> v
            }
        }
        return if (missing) null else out
    }

    /** The digits to key for these values: the first as it is, the rest two wide, leading zeros dropped. */
    fun keypadDigits(slots: List<String>, values: Map<String, String>): String? {
        val parts = slots.map { values[it]?.trim() ?: return null }
        if (parts.any { p -> p.isEmpty() || !p.all(Char::isDigit) }) return null
        val joined = parts.mapIndexed { i, p -> if (i == 0) p else p.padStart(2, '0') }.joinToString("")
        return joined.trimStart('0').ifEmpty { "0" }
    }

    /**
     * One call's steps as navigator actions, in order. Stops at the first step that can't be said
     * as an action (an "unknown" op, text the action grammar can't carry); `complete` tells
     * whether the whole call was covered.
     */
    data class Script(val actions: List<String>, val complete: Boolean)

    fun script(call: Call, values: Map<String, String>): Script {
        val out = mutableListOf<String>()
        for (op in call.steps) {
            val next: List<String>? = when (op.op) {
                "tap", "long_press", "scroll_to" -> fill(op.words, values)?.takeIf(::sayable)?.let { listOf("${op.op}(\"$it\")") }
                "type" -> fill(op.text, values)?.takeIf(::sayable)?.let {
                    listOf(if (op.box > 1) "type(#${op.box}, \"$it\")" else "type(\"$it\")")
                }
                "scroll" -> if (op.dir == "up" || op.dir == "down") listOf("scroll(${op.dir})") else null
                "back" -> listOf("back()")
                "keypad" -> keypadDigits(op.slots, values)?.map { "tap(\"$it\")" }
                else -> null
            }
            if (next == null) return Script(out, false)
            out += next
        }
        return Script(out, true)
    }

    /** The action grammar quotes its text and closes with a bracket; text holding either can't be carried. */
    private fun sayable(s: String) = s.isNotBlank() && '"' !in s && ')' !in s && '\n' !in s
}
