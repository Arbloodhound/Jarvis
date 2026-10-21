package com.agent.jarvis.agent

/**
 * When the on-device brain gets web results first. Asked for in words ("search the web for …",
 * "google …", "look up …"), or a question about something current (news, weather, prices, scores,
 * "latest", "today's"). The app searches ([Tools] web_search: DuckDuckGo, Wikipedia) and the results
 * go to the brain in a block; the model never browses by itself.
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
            "trending|election|this year|" +
            // Finding products: always a search.
            "cheapest|best deals?|on sale|in stock|where (?:can|do|to) (?:i )?(?:buy|get|find)|for sale|reviews? of|" +
            "find me|look for|looking for|compare)\\b",
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

    /** Room for a research brief (sources, pages, prices) in the prompt. */
    const val RESEARCH_CHARS = 3000

    /** The block the brain gets with the results. */
    fun brief(query: String, results: String): String =
        "Web results, searched just now for \"$query\" (say where the answer came from):\n" + results.trim()
}
