package com.agent.jarvis.agent

/**
 * What new words do to a run already in progress.
 *
 * - [Decision.Stop]: "stop", "cancel that", "never mind", "shut up"… on their own. Everything
 *   running stops, speech included.
 * - [Decision.After]: "also …", "and then …", "after that …", "… too": the run goes on and the
 *   new request runs when it ends.
 * - [Decision.Replace]: anything else while a run is going. The run stops and [Decision.Replace.request]
 *   runs instead: the new words alone when they stand on their own ("open YouTube"), or with the
 *   earlier request in front when they only make sense against it ("make it 7", "no, louder").
 * - [Decision.Run]: nothing is running; an ordinary request.
 */
object Interrupt {

    sealed class Decision {
        object Stop : Decision()
        data class After(val request: String) : Decision()
        data class Replace(val request: String) : Decision()
        data class Run(val request: String) : Decision()
    }

    /**
     * [request] as the brain gets it (an ambient command may carry overheard transcript before
     * [IntentRouter.REQUEST_MARKER]; only the words after it are judged). [running] is the
     * request part of the run in progress, or null when nothing runs.
     */
    fun decide(request: String, running: String?): Decision {
        val words = IntentRouter.requestPart(request)
        if (isStop(words)) return Decision.Stop
        if (running.isNullOrBlank()) return Decision.Run(request)
        addOn(words)?.let { return Decision.After(withWords(request, it)) }
        return Decision.Replace(withWords(request, revise(words, running)))
    }

    /** The words are nothing but a stop: "stop", "Jarvis, stop it", "okay never mind", "shut up". */
    fun isStop(text: String): Boolean {
        var t = normalize(text)
        // "stop, stop" / "stop stop stop"
        t = t.replace(Regex("^(stop )+stop$"), "stop")
        return t in STOPS
    }

    /** The new request without its "also" / "and then" / "too", or null when it isn't an add-on. */
    fun addOn(text: String): String? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        ADD_START.find(t)?.let { m ->
            val rest = t.substring(m.range.last + 1).trim().trimStart(',', ' ')
            return rest.takeIf { it.split(' ').size >= 2 }
        }
        ADD_END.find(t)?.let { m ->
            val rest = t.substring(0, m.range.first).trim().trimEnd(',', ' ')
            return rest.takeIf { it.split(' ').size >= 2 }
        }
        return null
    }

    /**
     * The request to run instead of [running]: [words] alone when they stand on their own, or
     * "[running] — change: [words]" when they refer back to it ("make it 7", "no, the other one",
     * "louder"). The earlier request goes first so the request is still routed as that kind of task
     * (an alarm stays an alarm); the model reads the change after it.
     */
    fun revise(words: String, running: String): String {
        val clean = words.trim().replace(Regex("\\s+"), " ")
            .replace(LEAD_FILLER, "").replace(TRAIL_INSTEAD, "").trim().trimEnd(',', ' ')
        val n = clean.split(' ').count { it.isNotBlank() }
        // A word or two that isn't a command of its own ("7am", "the blue one") only means
        // something against the earlier request; "open YouTube" stands alone.
        val refersBack = (n <= 2 && !COMMAND_START.containsMatchIn(clean)) ||
            REFERS_BACK.containsMatchIn(clean) || CHANGE_START.containsMatchIn(clean)
        return if (refersBack) "${running.trim()} — change: $clean" else clean
    }

    /** [request] with its request part replaced by [words] (overheard context kept in front). */
    private fun withWords(request: String, words: String): String {
        val i = request.lastIndexOf(IntentRouter.REQUEST_MARKER)
        return if (i < 0) words else request.substring(0, i + IntentRouter.REQUEST_MARKER.length) + words
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^a-z' ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
            .replace(FILLER, " ")
            .replace(Regex("\\s+"), " ").trim()

    private val FILLER = Regex("\\b(hey|hi|ok|okay|jarvis|please|now|right now|just|oh|um|uh|no no|alright|all right)\\b")

    private val STOPS = setOf(
        "stop", "stop it", "stop that", "stop this", "stop talking", "stop doing that", "stop everything",
        "cancel", "cancel it", "cancel that", "cancel this", "cancel everything",
        "never mind", "nevermind", "forget it", "forget that", "shut up", "be quiet", "quiet",
        "enough", "that's enough", "thats enough", "hold on", "hang on", "wait", "abort", "halt",
        "pause", "no stop", "don't do that", "do not do that", "don't", "stop stop",
    )

    private val ADD_START = Regex("^(and also|also|and then|then|after that|afterwards|plus|and)\\b[,]?", RegexOption.IGNORE_CASE)
    private val ADD_END = Regex("[, ]+(too|as well)[.!?]?$", RegexOption.IGNORE_CASE)
    private val LEAD_FILLER = Regex("^((no|actually|wait|sorry|oh|um|uh|hmm|ok|okay|jarvis|hey jarvis)[,.!]?\\s+)+", RegexOption.IGNORE_CASE)
    private val TRAIL_INSTEAD = Regex("[, ]+instead[.!?]?$", RegexOption.IGNORE_CASE)
    private val REFERS_BACK = Regex("\\b(it|that|this|them|those|one|same|again|more|less|louder|quieter|faster|slower|bigger|smaller|earlier|later|other)\\b", RegexOption.IGNORE_CASE)
    private val COMMAND_START = Regex("^(open|play|call|text|message|turn|set|search|google|take|show|read|start|find|go|send|launch|close|what|what's|who|how|where|when|why|tell|navigate|remind|note)\\b", RegexOption.IGNORE_CASE)
    private val CHANGE_START = Regex("^(make|change|set it|not|but|only|without|with|for)\\b", RegexOption.IGNORE_CASE)
}
