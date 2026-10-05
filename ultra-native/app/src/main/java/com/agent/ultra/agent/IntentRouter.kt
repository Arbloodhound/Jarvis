package com.agent.ultra.agent

/**
 * Picks a thinking budget per request so quick commands stay quick and only real
 * questions pay for reasoning. Rules-based on purpose: zero model calls, ~microseconds,
 * predictable. Pure Kotlin, unit-tested on the JVM.
 */
object IntentRouter {

    enum class Tier(val thinkBudget: Int) {
        DEVICE(64),    // toggles, alarms, timers, open/launch, media, calls — tool call, barely any thought
        LIGHT(160),    // greetings, chit-chat, short recall
        NORMAL(512),   // default
        DEEP(1200),    // explain/why/derive/debug/compare, risk domains, long input
    }

    data class Route(val tier: Tier, val reason: String) {
        val thinkBudget get() = tier.thinkBudget
    }

    private fun rx(vararg words: String) =
        Regex("\\b(" + words.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    private val forceDeep = rx("think (hard|carefully|it through)", "take your time", "step by step", "in depth", "thoroughly")
    private val forceLight = rx("quick(ly)?", "briefly", "short answer", "one word", "tl;?dr")

    private val deviceVerb = Regex(
        "^(please\\s+)?(turn|switch|toggle|set|start|stop|pause|resume|play|skip|open|launch|close|call|text|" +
            "mute|unmute|enable|disable|increase|decrease|raise|lower|dim|brighten|cancel|snooze)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val deviceNoun = rx(
        "wi-?fi", "bluetooth", "flashlight", "torch", "alarm", "timer", "volume", "brightness", "airplane mode",
        "do not disturb", "dnd", "hotspot", "location", "music", "song", "playlist", "app", "camera", "screen",
    )
    private val deviceQuestion = Regex(
        "^(is|are|what'?s|what is|how much)\\b.*\\b(bluetooth|wi-?fi|battery|storage|volume|brightness|time|date)\\b",
        RegexOption.IGNORE_CASE,
    )
    // noun-first short commands: "volume up", "wifi off", "brightness max"
    private val deviceShort = Regex(
        "^(the\\s+)?(wi-?fi|bluetooth|flashlight|torch|volume|brightness|hotspot|music|dnd)\\s+(up|down|on|off|max|min|higher|lower)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val greeting = Regex(
        "^(hi|hey|hello|yo|thanks|thank you|thx|good (morning|night|evening)|ok(ay)?|cool|nice)\\b[!. ]*\\w{0,12}[!. ]*$",
        RegexOption.IGNORE_CASE,
    )
    private val recall = rx("what did (i|he|she|they|we|you) say", "remind me what", "what was (that|it) about", "earlier today")
    private val deepCue = rx(
        "why", "explain", "how does", "how do", "derive", "prove", "compare", "difference between", "analy[sz]e",
        "debug", "refactor", "algorithm", "design", "trade-?offs?", "pros and cons", "what if", "evaluate",
    )
    // Risk domains: system-prompt rule 15 (premise verification) needs room to reason.
    private val riskDomain = rx(
        "dose", "dosage", "mg", "interaction", "pharmacolog\\w*", "receptor", "synthes\\w*", "reaction", "molar",
        "opsec", "threat model", "diagnos\\w*", "symptom\\w*", "contraindicat\\w*",
    )
    private val codeCue = Regex("```|\\bfun |\\bdef |\\bclass |\\{\\s*$|;\\s*$|\\bstack ?trace\\b|\\bexception\\b", RegexOption.IGNORE_CASE)
    // device verb + a follow-on action ("open X and send/find/create ...") — rule 12, multi-step
    private val compound = rx("and (then )?(send|find|create|add|delete|search|write|reply|book|order)")

    /** Marker AmbientListenService puts between overheard context and the command. */
    const val REQUEST_MARKER = "[Request]\n"

    /** An ambient command arrives wrapped in overheard transcript; route on the command
     * alone, or the transcript's length and words decide the tier. */
    fun requestPart(text: String): String {
        val i = text.lastIndexOf(REQUEST_MARKER)
        return if (i < 0) text else text.substring(i + REQUEST_MARKER.length)
    }

    fun route(text: String): Route {
        val t = requestPart(text).trim()
        val words = t.split(Regex("\\s+")).count { it.isNotBlank() }

        if (forceDeep.containsMatchIn(t)) return Route(Tier.DEEP, "user asked for depth")
        if (forceLight.containsMatchIn(t) && !riskDomain.containsMatchIn(t)) return Route(Tier.LIGHT, "user asked for brevity")

        if (riskDomain.containsMatchIn(t)) return Route(Tier.DEEP, "risk domain: verify premises")
        if (codeCue.containsMatchIn(t)) return Route(Tier.DEEP, "code")

        val isDevice = (deviceVerb.containsMatchIn(t) && (deviceNoun.containsMatchIn(t) || words <= 6)) ||
            deviceQuestion.containsMatchIn(t) || deviceShort.containsMatchIn(t)
        if (isDevice && words <= 16) {
            return if (compound.containsMatchIn(t)) Route(Tier.NORMAL, "multi-step device task")
            else Route(Tier.DEVICE, "device command")
        }

        if (greeting.matches(t) || (words <= 3 && !deepCue.containsMatchIn(t))) return Route(Tier.LIGHT, "short / social")
        if (recall.containsMatchIn(t)) return Route(Tier.LIGHT, "ambient recall")
        if (deepCue.containsMatchIn(t) || words > 40) return Route(Tier.DEEP, "reasoning cue")
        return Route(Tier.NORMAL, "default")
    }
}
