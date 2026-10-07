package com.agent.ultra.agent

/**
 * When the on-device brain gets web results first (owner, 2026-10-07: it kept saying it can't
 * browse the internet, though the phone is online). Asked for in words ("search the web for …",
 * "google …", "look up …"), or a question about something current (news, weather, prices, scores,
 * "latest", "today's"). The app searches ([Tools] web_search: DuckDuckGo, Wikipedia) and the results
 * go to the brain in a block; the model never browses by itself. Pure, JVM-tested.
 */
object WebNeed {
    private val ASKED = Regex(
        "^(?:please )?(?:can you |could you )?(?:search|google|look up|lookup|find out|check online|browse)" +
            "(?: (?:the )?(?:web|internet|online|google))?(?: for| about| what| whether| if| how)?\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val CURRENT = Regex(
        "\\b(latest|news|headlines?|today'?s|tonight'?s|this week'?s|right now|currently|current|up to date|" +
            "weather|forecast|temperature outside|price of|prices?|cost of|stock|share price|exchange rate|" +
            "scores?|who won|results? of|fixtures?|opening hours|open now|near me|release date|came out|" +
            "trending|election|this year)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** The search to run for [request], or null when it needs none. Pure. */
    fun query(request: String): String? {
        val t = IntentRouter.requestPart(request).trim().trimEnd('?', '.', '!')
        if (t.isEmpty() || t.length > 300) return null
        // About what was heard, not the web. A time word alone ("who won last night") is not that.
        if (MemoryFocus.detect(t) != null || MemoryBrief.wants(t).let { it.search && it.reason != "past time" }) return null
        ASKED.find(t)?.let { return it.groupValues[1].trim().ifBlank { null } }
        return if (CURRENT.containsMatchIn(t)) t else null
    }

    /** The block the brain gets with the results. */
    fun brief(query: String, results: String): String =
        "Web results, searched just now for \"$query\" (say where the answer came from):\n" + results.trim()
}
