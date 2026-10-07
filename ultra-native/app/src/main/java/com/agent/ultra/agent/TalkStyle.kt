package com.agent.ultra.agent

/**
 * Settings → HOW JARVIS TALKS: personality and answer length as lines for the system prompt, and
 * the think time for spoken requests. Pure, JVM-tested.
 */
object TalkStyle {

    enum class Personality(val label: String, val line: String) {
        BUTLER("Butler", "Personality: a calm, capable butler like Jarvis — polite, dry wit, never servile."),
        FRIENDLY("Friendly", "Personality: warm, upbeat and casual, like a helpful friend."),
        PROFESSIONAL("Professional", "Personality: professional and precise; no jokes, no small talk."),
        WITTY("Witty", "Personality: quick and playful, with light humour, but still gets the job done."),
        PLAIN("Plain", ""),
    }

    enum class Length(val label: String, val line: String) {
        SHORT("Short", "Keep answers to one or two short sentences unless asked for more."),
        NORMAL("Normal", ""),
        DETAILED("Detailed", "Give thorough, detailed answers with the useful specifics."),
    }

    /** Seconds of silent thinking allowed before a spoken request is answered. */
    enum class SpokenThink(val label: String, val seconds: Double) {
        INSTANT("Instant", 3.0),
        QUICK("Quick", WaitBudget.SPOKEN_SILENT_S),
        CAREFUL("Careful", 12.0),
    }

    /** The prompt lines for [p] and [l], or "" when both are the plain defaults. */
    fun instructions(p: Personality, l: Length): String =
        listOf(p.line, l.line).filter { it.isNotBlank() }.joinToString("\n")

    /** [style] and the owner's own [custom] instructions together (either may be blank). */
    fun combine(style: String, custom: String): String =
        listOf(style.trim(), custom.trim()).filter { it.isNotEmpty() }.joinToString("\n")
}
