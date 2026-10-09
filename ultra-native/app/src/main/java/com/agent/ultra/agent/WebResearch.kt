package com.agent.ultra.agent

import java.net.URLDecoder

/**
 * Searching like a researcher instead of one plain query (owner, 2026-10-07: "big emphasis on
 * SEARCHING … intelligently … intelligent product finding: verify sellers, use forums and social
 * consensus, cross-reference, best price"). Before, a question got one DuckDuckGo query and the
 * titles and snippets only (no links, no pages read).
 *
 * Now: [plan] writes 2–4 queries with search operators (exact phrases, site:, -exclusions, the
 * current year) for the kind of request; every query's results are merged, and a result found by
 * several queries or on a known-good site ranks first ([rank]); the best pages are fetched and read
 * ([pageText]); for products, prices are pulled out of the pages ([prices]) and each seller's site
 * is marked as a known retailer or not. [brief] hands the brain numbered sources and the rules for
 * using them: agree across sources, cite [n], say where they disagree, and for products a ranked
 * shortlist with price, seller, why it's trusted and any red flags. Pure, JVM-tested; the fetching
 * is in [WebResearchRun].
 */
object WebResearch {

    enum class Kind { PRODUCT, HOWTO, FACT }

    data class Hit(val title: String, val url: String, val snippet: String) {
        val domain: String get() = domainOf(url)
    }

    data class Ranked(val hit: Hit, val score: Double, val foundBy: Int)

    data class Price(val amount: Double, val currency: String, val text: String)

    private val PRODUCT = Regex(
        "\\b(buy|buying|price|prices|cheapest|cheap|best price|deal|deals|discount|where (can|do|to) (i )?(buy|get|find)|" +
            "in stock|for sale|order|shop|retailer|seller|vendor|find me|look for|looking for|compare|vs\\.?|versus|" +
            "best .{1,30} (under|for)|review|reviews|worth it)\\b", RegexOption.IGNORE_CASE)
    private val HOWTO = Regex("^(how (do|to|can|should)|what'?s the (best )?way|steps? to|guide|tutorial)\\b", RegexOption.IGNORE_CASE)
    private val FILLER = Regex("^(please |can you |could you |hey jarvis,? |jarvis,? )?(search( the web| online| google)?( for)?|google|look up|find( me)?|look for|check|research)\\s+", RegexOption.IGNORE_CASE)

    fun kind(request: String): Kind = when {
        PRODUCT.containsMatchIn(request) -> Kind.PRODUCT
        HOWTO.containsMatchIn(request.trim()) -> Kind.HOWTO
        else -> Kind.FACT
    }

    /** The subject of the request: polite and search words taken off the front. */
    fun subject(request: String): String =
        request.trim().trimEnd('?', '.', '!').replace(FILLER, "").replace(Regex("\\s+"), " ").trim()

    /**
     * 2–4 queries for [request]: the plain one first, then targeted ones. [year] keeps reviews and
     * prices current.
     */
    fun plan(request: String, year: Int): List<String> {
        val s = subject(request).ifBlank { request.trim() }
        val core = s.replace(Regex("\\b(the )?(cheapest|best price( for)?|best deal( on)?|where (can|do|to) (i )?(buy|get|find)|buy|for sale|deals? on|price of|prices? for)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim().ifBlank { s }
        val q = when (kind(request)) {
            Kind.PRODUCT -> listOf(
                s,
                "\"$core\" price -used -refurbished",
                "$core review site:reddit.com",
                "best $core $year",
            )
            Kind.HOWTO -> listOf(s, "$s step by step", "$s site:reddit.com OR site:stackexchange.com")
            Kind.FACT -> listOf(s, "\"$core\" $year", "$core site:wikipedia.org OR site:reuters.com OR site:apnews.com OR site:bbc.com")
        }
        return q.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * DuckDuckGo answered with its bot check or a rate limit instead of results: HTTP 202, or the
     * page carries its challenge form (the same signals SearXNG's and duckduckgo_search's
     * DuckDuckGo engines check). Read as "blocked", never as "no results".
     */
    fun ddgBlocked(code: Int, html: String): Boolean =
        code == 202 || "challenge-form" in html || "anomaly-modal" in html

    /** Results from a DuckDuckGo HTML page: the real link out of its redirect, title and snippet. */
    fun parseDdg(html: String): List<Hit> {
        val out = ArrayList<Hit>()
        val block = Regex("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>([\\s\\S]*?)(?=<a[^>]+class=\"result__a\"|$)")
        for (m in block.findAll(html)) {
            val url = realUrl(m.groupValues[1]) ?: continue
            val title = clean(m.groupValues[2])
            val snippet = Regex("class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>").find(m.groupValues[3])?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
            if (title.isNotEmpty() && url.startsWith("http") && "duckduckgo.com/y.js" !in url) out += Hit(title, url, snippet)
            if (out.size >= 10) break
        }
        return out
    }

    private fun realUrl(href: String): String? {
        val h = href.replace("&amp;", "&")
        val uddg = Regex("[?&]uddg=([^&]+)").find(h)?.groupValues?.get(1)
        val u = if (uddg != null) runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrNull() else h
        return u?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    private fun clean(s: String) = s.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&#x27;", "'")
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace(Regex("\\s+"), " ").trim()

    fun domainOf(url: String): String =
        Regex("^https?://(?:www\\.)?([^/:?#]+)").find(url)?.groupValues?.get(1)?.lowercase().orEmpty()

    /** Big, long-established retailers: a price there is real and returns are easy. */
    val RETAILERS = setOf(
        "amazon.com", "amazon.co.uk", "amazon.ca", "amazon.de", "ebay.com", "ebay.co.uk", "walmart.com", "target.com",
        "bestbuy.com", "costco.com", "newegg.com", "bhphotovideo.com", "argos.co.uk", "currys.co.uk", "johnlewis.com",
        "very.co.uk", "ao.com", "scan.co.uk", "overclockers.co.uk", "homedepot.com", "lowes.com", "ikea.com",
        "apple.com", "samsung.com", "dell.com", "lenovo.com", "microcenter.com", "adorama.com", "rei.com",
        "sephora.com", "ulta.com", "etsy.com", "aliexpress.com", "temu.com",
    )

    /** Places people compare notes: consensus, not adverts. */
    val COMMUNITIES = setOf("reddit.com", "stackexchange.com", "stackoverflow.com", "quora.com", "trustpilot.com",
        "rtings.com", "tomshardware.com", "wirecutter.com", "nytimes.com", "consumerreports.org", "camelcamelcamel.com",
        "pricespy.co.uk", "hotukdeals.com", "slickdeals.net", "youtube.com")

    /** Reference and news sites with editorial standards. */
    val REFERENCE = setOf("wikipedia.org", "reuters.com", "apnews.com", "bbc.com", "bbc.co.uk", "nature.com",
        "nih.gov", "who.int", "britannica.com", "theguardian.com", "nytimes.com")

    private fun inSet(domain: String, set: Set<String>) = set.any { domain == it || domain.endsWith(".$it") }

    fun trust(domain: String): String? = when {
        inSet(domain, RETAILERS) -> "known retailer"
        inSet(domain, COMMUNITIES) -> "community / reviews"
        inSet(domain, REFERENCE) -> "reference"
        domain.endsWith(".gov") || domain.endsWith(".edu") || domain.endsWith(".gov.uk") || domain.endsWith(".ac.uk") -> "official"
        else -> null
    }

    /** Merge every query's [hits]: one entry per URL, scored by how many queries found it (consensus),
     *  how high, and whether the site is a trusted kind for [kind]. Best first. */
    fun rank(hits: List<List<Hit>>, kind: Kind): List<Ranked> {
        val byUrl = LinkedHashMap<String, Pair<Hit, MutableList<Int>>>()
        for (list in hits) list.forEachIndexed { i, h ->
            val key = h.url.substringBefore('#').trimEnd('/')
            byUrl.getOrPut(key) { h to ArrayList() }.second += i
        }
        return byUrl.values.map { (h, positions) ->
            val t = trust(h.domain)
            val bonus = when {
                t == null -> 0.0
                kind == Kind.PRODUCT && (t == "known retailer" || t == "community / reviews") -> 1.5
                kind != Kind.PRODUCT && (t == "reference" || t == "official") -> 1.5
                else -> 0.7
            }
            val score = positions.size * 2.0 + positions.sumOf { 1.0 / (1 + it) } + bonus
            Ranked(h, score, positions.size)
        }.sortedByDescending { it.score }
    }

    /** The readable text of a page: scripts, styles, menus and tags removed, at most [max] chars. */
    fun pageText(html: String, max: Int = 1400): String = html
        .replace(Regex("<(script|style|noscript|svg|nav|footer|header|form)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<br\\s*/?>|</p>|</li>|</h[1-6]>", RegexOption.IGNORE_CASE), "\n")
        .let { clean(it.replace(Regex("<[^>]*>"), " ")) }
        .take(max)

    // A symbol or code before the number ("$1,299", "A$1,299", "EUR 379.99") or after it ("379,99 €",
    // "1,299.00 EUR"). Both "1,299.99" and "1.299,99" styles; [amount] decides which separator is the
    // decimal point. A trailing "." or "," (end of a sentence) is allowed. A symbol after the number
    // must not be followed by a digit, so "10 $20" reads as $20, not as 10 $.
    private const val CUR_PRE = "(?:US|AU|A|CA|C|NZ)?\\$|£|€|USD|GBP|EUR|CAD|AUD"
    private const val CUR_POST = "\\$|£|€|USD|GBP|EUR|CAD|AUD"
    private const val NUM = "\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?"
    private val PRICE = Regex(
        "(?<![\\w.])(?:($CUR_PRE)\\s?($NUM)(?!\\d)|($NUM)(?!\\d)\\s?($CUR_POST)(?![A-Za-z\\d]))")

    /**
     * The value of a price number as written: the last separator followed by exactly 1–2 digits is
     * the decimal point, a separator followed by exactly 3 digits is a thousands separator
     * ("1.299,00" → 1299.0, "1,299" → 1299.0, "12,99" → 12.99, "299.99" → 299.99). Null when it
     * isn't a number in either style. Pure, JVM-tested.
     */
    fun amount(num: String): Double? {
        val s = num.trim()
        if (s.isEmpty() || !s.first().isDigit() || !s.all { it.isDigit() || it == '.' || it == ',' }) return null
        val last = s.indexOfLast { it == '.' || it == ',' }
        if (last < 0) return s.toDoubleOrNull()
        val tail = s.length - last - 1
        val digits = when (tail) {
            1, 2 -> s.substring(0, last).filter { it.isDigit() } + "." + s.substring(last + 1)
            3 -> s.filter { it.isDigit() }
            else -> return null
        }
        return digits.toDoubleOrNull()
    }

    private fun currencyLabel(sym: String): String = when (sym) {
        "£", "GBP" -> "£"
        "€", "EUR" -> "€"
        "A$", "AU$", "AUD" -> "A$"
        "C$", "CA$", "CAD" -> "C$"
        "US$" -> "US$"
        "NZ$" -> "NZ$"
        else -> "$"
    }

    /** Prices written in [text], in order, at most [max]. */
    fun prices(text: String, max: Int = 6): List<Price> = PRICE.findAll(text).mapNotNull { m ->
        val sym = m.groupValues[1].ifEmpty { m.groupValues[4] }
        val num = amount(m.groupValues[2].ifEmpty { m.groupValues[3] }) ?: return@mapNotNull null
        if (num <= 0.0) null else Price(num, currencyLabel(sym), m.value.trim())
    }.take(max).toList()

    private val YEAR = Regex("(?<!\\d)(20\\d{2})(?!\\d)")

    /**
     * The newest year from 2000 to [now] written in a result's [title], [snippet] or [url] (a URL like
     * ".../2025/03/..." counts); later years are ignored. Null when none. Pure, JVM-tested.
     */
    fun yearOf(title: String, snippet: String, url: String, now: Int): Int? =
        listOf(title, snippet, url).flatMap { f -> YEAR.findAll(f).map { it.groupValues[1].toInt() }.toList() }
            .filter { it in 2000..now }.maxOrNull()

    /**
     * The block the brain reads: numbered sources (site, how trusted, how many searches found it,
     * prices for products, the newest year the result mentions ([yearOf], up to [now]), what the page
     * says) and how to use them.
     */
    fun brief(request: String, kind: Kind, queries: List<String>, ranked: List<Ranked>, pages: Map<String, String>, max: Int = 3000,
              now: Int = java.time.Year.now().value): String =
        buildString {
            append("Searched: ").append(queries.joinToString(" | ")).append('\n')
            ranked.take(8).forEachIndexed { i, r ->
                val h = r.hit
                append('[').append(i + 1).append("] ").append(h.title).append(" — ").append(h.domain)
                trust(h.domain)?.let { append(" (").append(it).append(')') }
                yearOf(h.title, h.snippet, h.url, now)?.let { append(" · dated ").append(it) }
                if (r.foundBy > 1) append(" · found by ").append(r.foundBy).append(" searches")
                append('\n').append(h.url).append('\n')
                val page = pages[h.url]
                val text = page ?: h.snippet
                if (kind == Kind.PRODUCT) prices(page ?: h.snippet).takeIf { it.isNotEmpty() }?.let { ps ->
                    append("prices seen: ").append(ps.joinToString(", ") { it.text }).append('\n')
                }
                if (text.isNotBlank()) append(text.take(if (page != null) 600 else 220)).append('\n')
            }
            append(when (kind) {
                Kind.PRODUCT -> "Using these: give a short ranked shortlist — price, where (site), why it's trustworthy (known retailer, " +
                    "reviews or forum consensus) and any red flags (price far below the others, unknown site, few or no reviews, " +
                    "used/refurbished). Say what the forums and reviews agree on. Cite sources as [n]. If prices disagree, say so."
                else -> "Using these: answer from what two or more sources agree on, cite them as [n], say where they disagree, " +
                    "and prefer reference and official sources over forums for facts."
            })
            append(" Prefer recent sources for prices and specs.")
        }.let { if (it.length <= max) it else it.take(max - 1) + "…" }
}
