package com.agent.jarvis.agent

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Answers a few whole utterances with no model at all: a greeting, thanks, a goodbye, the time,
 * the date.
 *
 * Only the WHOLE utterance matches (after [IntentRouter.requestPart], trim, lowercase, dropping
 * punctuation, and dropping a leading or trailing "jarvis" / "hey jarvis"). Anything with more
 * content ("hi, what's the weather", "thanks, now turn on wifi") is null, and so are the words
 * that answer a confirmation card ("ok", "yes", "no", "sure", "cool", "nice").
 */
object QuickReply {

    private val GREETINGS = mapOf(
        "hi" to "Hi. What can I do for you?",
        "hey" to "Hi. What can I do for you?",
        "hello" to "Hi. What can I do for you?",
        "hiya" to "Hi. What can I do for you?",
        "good morning" to "Good morning. What can I do for you?",
        "good afternoon" to "Good afternoon. What can I do for you?",
        "good evening" to "Good evening. What can I do for you?",
    )
    private val THANKS = setOf("thanks", "thank you", "thank you very much", "thanks a lot", "thx", "cheers")
    private val BYES = mapOf("bye" to "Bye.", "goodbye" to "Bye.", "good night" to "Good night.")
    private val TIME = setOf("what time is it", "whats the time", "what is the time")
    private val DATE = setOf("whats the date", "what is the date", "what day is it", "whats todays date")

    // Same style as LocalPrompt.nowLine ("EEEE d MMMM yyyy, h:mm a", Locale.UK).
    private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.UK)
    private val DATE_FMT = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

    /** The canned reply to [request], or null when it needs the brain. */
    fun reply(request: String, now: ZonedDateTime): String? {
        val t = normalize(request)
        if (t.isEmpty()) return null
        GREETINGS[t]?.let { return it }
        if (t in THANKS) return "You're welcome."
        BYES[t]?.let { return it }
        // Lowercased: some JDKs write "PM" for Locale.UK, others "pm".
        if (t in TIME) return "It's ${now.format(TIME_FMT).lowercase(Locale.UK)}."
        if (t in DATE) return "It's ${now.format(DATE_FMT)}."
        return null
    }

    /** The request part, lowercased, letters/digits/single spaces only, the name dropped. */
    internal fun normalize(request: String): String {
        var t = IntentRouter.requestPart(request).trim().lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .replace(Regex("\\s+"), " ").trim()
        t = t.removePrefix("hey jarvis ").let { if (it == t) t.removePrefix("jarvis ") else it }
        if (t == "hey jarvis" || t == "jarvis") return ""
        t = t.removeSuffix(" hey jarvis").let { if (it == t) t.removeSuffix(" jarvis") else it }
        return t.trim()
    }
}
