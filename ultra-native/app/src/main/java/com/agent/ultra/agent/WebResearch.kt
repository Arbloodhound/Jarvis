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

    private val VERIFY = Regex(
        // Questions about whether something is real or a scam — not "how to clone a repo" or "is the Mona Lisa authentic".
        "\\b(legit|legitimate|scammers?|real or fake|counterfeits?|phishing (site|clone|link|page)|trustworthy|reputable|" +
            "(is|are) .{1,40}? (a )?scams?|(is|are) (this|that|it|the|they) (company|site|shop|store|seller|product|website) " +
            "(real|safe|genuine))\\b|\\.onion\\b", RegexOption.IGNORE_CASE)

    /**
     * A product or company check: is it real, is the seller or site genuine, a scam, a phishing
     * clone? Those get the verification rules in [brief] (evidence laid out, no pick, a question
     * at the end).
     */
    fun verifying(request: String): Boolean = kind(request) == Kind.PRODUCT || VERIFY.containsMatchIn(request) || mode(request) != Mode.NORMAL

    /**
     * What the owner explicitly asked for. Only these phrases, at the start of the request, start
     * the bigger runs — never anything inferred: "deep research …" (the Tor discovery crawl, also
     * needs Settings → "Deep research over Tor"), "investigate …" (a case file), "verify this
     * product …" / "check this company …" (the clearnet + onion product check).
     */
    enum class Mode { NORMAL, DEEP, INVESTIGATE, VERIFY_PRODUCT }

    private const val LEAD = "^\\s*(?:(?:hey )?jarvis,?\\s+)?(?:please\\s+|can you\\s+|could you\\s+)?"
    private val DEEP = Regex("$LEAD(?:do (?:a |some )?)?deep[- ]research(?:\\s+(?:on|into|for|about))?\\b", RegexOption.IGNORE_CASE)
    // "investigate why my battery drains" is a question, not a case file.
    private val INVESTIGATE = Regex("$LEAD(?:investigate(?!\\s+(?:why|how|what|whether|if|when|where)\\b)|open a case (?:on|into|about)|run an investigation (?:on|into))\\b", RegexOption.IGNORE_CASE)
    // "verify this product", "check this company", "verify the product" — not "check the company's stock price".
    private val VERIFY_PRODUCT = Regex("$LEAD(?:(?:verify|check) (?:this|that)|verify(?: the| a)?) (?:product|company)(?![\\w'’])(?:\\s*[:,-])?", RegexOption.IGNORE_CASE)

    /** Pure, JVM-tested. */
    fun mode(request: String): Mode = when {
        DEEP.containsMatchIn(request) -> Mode.DEEP
        INVESTIGATE.containsMatchIn(request) -> Mode.INVESTIGATE
        VERIFY_PRODUCT.containsMatchIn(request) -> Mode.VERIFY_PRODUCT
        else -> Mode.NORMAL
    }

    /** The request with its [mode] phrase taken off the front: what to research. Pure, JVM-tested. */
    fun target(request: String): String = when (mode(request)) {
        Mode.DEEP -> request.replaceFirst(DEEP, "")
        Mode.INVESTIGATE -> request.replaceFirst(INVESTIGATE, "")
        Mode.VERIFY_PRODUCT -> request.replaceFirst(VERIFY_PRODUCT, "")
        Mode.NORMAL -> request
    }.trim().trimEnd('?', '.', '!').trim()

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
            Kind.FACT -> listOf(s, "\"$core\" $year",
                // Is it real / a scam: what people who dealt with it say, not an encyclopedia.
                if (VERIFY.containsMatchIn(request)) "$core scam OR legit site:reddit.com OR site:trustpilot.com"
                else "$core site:wikipedia.org OR site:reuters.com OR site:apnews.com OR site:bbc.com")
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

    /** What a research run may do with a .onion link (Settings → "Onion research via Tor"). */
    enum class OnionRoute { TOR, OFF, PROXY_DOWN, OFFLINE }

    /**
     * Pure, JVM-tested. Offline only wins over everything; then the setting; then whether Orbot's
     * proxy answers. Only [OnionRoute.TOR] fetches, and only through the Tor proxy — an onion link
     * is never fetched with the normal client.
     */
    fun onionRoute(offlineOnly: Boolean, settingOn: Boolean, proxyUp: Boolean): OnionRoute = when {
        offlineOnly -> OnionRoute.OFFLINE
        !settingOn -> OnionRoute.OFF
        !proxyUp -> OnionRoute.PROXY_DOWN
        else -> OnionRoute.TOR
    }

    /** The one line said when [n] onion links were skipped, or null when [route] reads them. */
    fun onionSkipLine(route: OnionRoute, n: Int): String? {
        val links = if (n == 1) "1 onion link" else "$n onion links"
        return when (route) {
            OnionRoute.TOR -> null
            OnionRoute.OFFLINE -> "Note: offline only is on — skipped $links."
            OnionRoute.OFF -> "Note: onion research via Tor is off (Settings) — skipped $links."
            OnionRoute.PROXY_DOWN -> "Note: Tor isn't running (start Orbot) — skipped $links."
        }
    }

    /** A LIKELY_PHISH or SCAM onion, named so the owner can recognise it — never as a link to visit. */
    fun redFlagLine(r: OnionSafety.Result): String {
        val what = if (r.verdict == OnionSafety.Verdict.SCAM) "a scam page" else "a likely phishing clone"
        return "Red flag: ${OnionSafety.short(r.address)} is $what — ${r.reasons.joinToString("; ")}. " +
            "Left out of the sources; not a link to visit."
    }

    /** An onion page read over Tor that passed screening: a source like any other, with its label. */
    data class OnionSource(val url: String, val title: String, val label: String, val text: String)

    /**
     * Onion results for [brief]: pages read over Tor ([sources], listed first), the skip / red-flag
     * lines ([lines]), and the onion addresses each clearnet page mentions ([claims], url →
     * addresses) checked against [known] (address → name). With the setting off it holds at most a
     * skip line.
     */
    data class OnionReport(
        val sources: List<OnionSource> = emptyList(),
        val lines: List<String> = emptyList(),
        val claims: Map<String, List<String>> = emptyMap(),
        val known: Map<String, String> = emptyMap(),
    ) {
        operator fun plus(o: OnionReport) = OnionReport(sources + o.sources, lines + o.lines, claims + o.claims, known + o.known)
        val isEmpty: Boolean get() = sources.isEmpty() && lines.isEmpty() && claims.isEmpty()
    }

    /**
     * Do the onion addresses that clearnet pages give match? [numbered] is each source's number
     * and URL; [claims] the addresses each page mentions. A known-good address is named as such;
     * a lookalike of one is a red flag (shortened, not a link); two pages giving addresses that
     * look alike is a red flag (at most one is genuine); any other address counts only if the
     * company's own site publishes it. Pure, JVM-tested.
     */
    fun onionIdentityLines(numbered: List<Pair<Int, String>>, claims: Map<String, List<String>>, known: Map<String, String>): List<String> {
        val byAddr = LinkedHashMap<String, MutableList<String>>()
        for ((n, url) in numbered) claims[url]?.forEach { a -> byAddr.getOrPut(OnionSafety.address(a)) { ArrayList() } += "[$n] ${domainOf(url)}" }
        val knownAddr = known.keys.map(OnionSafety::address).toSet()
        val out = ArrayList<String>()
        for ((addr, where) in byAddr.entries.take(4)) {
            val r = OnionSafety.classify("http://$addr/", null, known) ?: continue
            val on = where.distinct().joinToString(", ")
            out += when (r.verdict) {
                OnionSafety.Verdict.TRUSTED -> "Onion identity: $addr (named on $on) is ${r.name ?: "a known-good service"}'s known-good address."
                OnionSafety.Verdict.LIKELY_PHISH, OnionSafety.Verdict.SCAM -> "Red flag: an onion address named on $on " +
                    "(${OnionSafety.short(addr)}) is a likely phishing clone — ${r.reasons.joinToString("; ")}. Not a link to visit."
                OnionSafety.Verdict.UNKNOWN -> "Onion identity: $addr (named on $on) is not in the known-good list — " +
                    "it is the company's only if the company's own site publishes this exact address."
            }
        }
        val addrs = byAddr.keys.toList()
        for (i in addrs.indices) for (j in i + 1 until addrs.size) {
            val a = addrs[i]; val b = addrs[j]
            if (a in knownAddr && b in knownAddr) continue
            if (OnionSafety.lookalikeOf(a, listOf(b)) != null) out += "Red flag: ${byAddr.getValue(a).first()} and " +
                "${byAddr.getValue(b).first()} give onion addresses that look alike but differ " +
                "(${OnionSafety.short(a)} vs ${OnionSafety.short(b)}) — at most one is genuine."
        }
        return out
    }

    data class PriceSeen(val price: Price, val n: Int, val domain: String, val year: Int?)

    /** Said instead of a price when only the search snippet shows one. */
    const val SNIPPET_PRICE_NOTE = "price: not verified — only the search snippet shows one and the page wasn't read; don't quote it"


    /**
     * Every price the sources' READ pages show, sorted mechanically — by currency, then amount,
     * lowest first — each with its source [n], site and the newest year the source mentions.
     * A price from a search snippet is never evidence (search results are stale and often a
     * different item; Muse's shopping rule, MUSE_RESEARCH §10.7): a source whose page wasn't read
     * gives no price here. Pure, JVM-tested.
     */
    fun priceEvidence(sources: List<Triple<Int, Hit, String?>>, now: Int, max: Int = 8): List<PriceSeen> =
        sources.flatMap { (n, h, page) ->
            (if (page == null) emptyList() else prices(page, 3)).map { PriceSeen(it, n, h.domain, yearOf(h.title, h.snippet, h.url, now)) }
        }.distinctBy { Triple(it.n, it.price.currency, it.price.amount) }
            .sortedWith(compareBy({ it.price.currency }, { it.price.amount })).take(max)

    /**
     * Prices far below the rest (under 60% of the median in the same currency, with at least 3
     * prices): a red flag to check — a different item, used, or a fake shop. Pure, JVM-tested.
     */
    fun priceRedFlags(seen: List<PriceSeen>): List<String> = seen.groupBy { it.price.currency }.values.flatMap { same ->
        if (same.size < 3) return@flatMap emptyList()
        val sorted = same.map { it.price.amount }.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        same.filter { it.price.amount < median * 0.6 }.map {
            "Red flag: ${it.price.text} at [${it.n}] ${it.domain} is far below the others " +
                "(median ${it.price.currency}${"%.2f".format(java.util.Locale.ROOT, median)}) — check it's the same item, new, and a genuine site."
        }
    }

    private fun PriceSeen.line() = "${price.text} [$n] $domain " + (year?.let { "($it)" } ?: "(undated)")

    /** How the sources are listed — said in the brief, so the order is never read as a pick. */
    const val ORDER_KEY = "Sources are listed by search agreement (how many searches found each, then search position), " +
        "onion pages read over Tor first — an order, not a judgement of quality."

    /** The closing question every verification answer ends with. */
    const val NEXT_QUESTION = "End with one question asking what the owner wants next " +
        "(for example: compare more sites, check one site, or read reviews)."

    /**
     * The rules for a product or company check ([verifying]): lay out the evidence, no pick, no
     * purchase advice, a question at the end. No buying or recommending words in them.
     */
    fun verifyRules(kind: Kind): String = (if (kind == Kind.PRODUCT)
        "Using these: lay the evidence out, option by option, in the price-evidence order above (sorted by price, lowest first) " +
            "and say that's the order: price with its year, where (site [n]), why that source is or isn't trustworthy " +
            "(known retailer, reviews or forum consensus, official site) and any red flags (price far below the others, " +
            "unknown site, few or no reviews, used/refurbished). "
    else "Using these: lay out what the evidence shows, source by source. ") +
        "Say whether the product is real (independent sources describe it) and whether the company is real (its own site, " +
        "reviews, how long it has been around). An onion address counts as the company's only when the company's own " +
        "clearnet site publishes the exact same address. Say what the sources agree on and where they disagree; cite them as [n]. " +
        "Repeat every Red flag line. No pick, no verdict on which one to get, no purchase advice — show the work. " + NEXT_QUESTION

    /**
     * The block the brain reads: numbered sources (site, how trusted, how many searches found it,
     * prices for products, the newest year the result mentions ([yearOf], up to [now]), what the page
     * says) and how to use them. Onion pages read over Tor ([onion]) come first; skip, red-flag and
     * onion-identity lines go at the top. When it's too long the sources are cut, never the lines
     * at the top or the rules at the end.
     */
    fun brief(request: String, kind: Kind, queries: List<String>, ranked: List<Ranked>, pages: Map<String, String>, max: Int = 3000,
              now: Int = java.time.Year.now().value, onion: OnionReport = OnionReport(), rules: Boolean = true): String {
        val clear = ranked.take(8)
        val first = onion.sources.size
        val numbered = clear.mapIndexed { i, r -> (first + i + 1) to r.hit.url }
        val verify = verifying(request)
        val seen = if (kind == Kind.PRODUCT) priceEvidence(clear.mapIndexed { i, r -> Triple(first + i + 1, r.hit, pages[r.hit.url]) }, now) else emptyList()
        val head = buildString {
            append("Searched: ").append(queries.joinToString(" | ")).append('\n')
            val lines = onion.lines + onionIdentityLines(numbered, onion.claims, onion.known) + priceRedFlags(seen)
            lines.distinct().forEach { append(it).append('\n') }
            if (verify && (clear.isNotEmpty() || onion.sources.isNotEmpty())) append(ORDER_KEY).append('\n')
        }
        val body0 = buildString {
            onion.sources.forEachIndexed { i, o ->
                append('[').append(i + 1).append("] ").append(o.title).append(" — ")
                    .append(OnionSafety.onionHost(o.url)?.let(OnionSafety::address).orEmpty()).append(" (").append(o.label).append(")\n")
                append(o.url).append('\n')
                if (o.text.isNotBlank()) append(o.text.take(600)).append('\n')
            }
            clear.forEachIndexed { i, r ->
                val h = r.hit
                append('[').append(first + i + 1).append("] ").append(h.title).append(" — ").append(h.domain)
                trust(h.domain)?.let { append(" (").append(it).append(')') }
                yearOf(h.title, h.snippet, h.url, now)?.let { append(" · dated ").append(it) }
                if (r.foundBy > 1) append(" · found by ").append(r.foundBy).append(" searches")
                append('\n').append(h.url).append('\n')
                val page = pages[h.url]
                val text = page ?: h.snippet
                if (kind == Kind.PRODUCT) {
                    if (page != null) prices(page).takeIf { it.isNotEmpty() }?.let { ps ->
                        append("prices seen: ").append(ps.joinToString(", ") { it.text }).append('\n')
                    } else if (prices(h.snippet).isNotEmpty()) append(SNIPPET_PRICE_NOTE).append('\n')
                }
                if (text.isNotBlank()) {
                    if (page == null) append("search snippet (page not read): ")
                        .append(PRICE.replace(text, "[price not verified]").take(220)).append('\n')
                    else append(text.take(600)).append('\n')
                }
            }
        }
        // rules = false: the caller adds its own (the product check's table and question), so none here to contradict them.
        val tail = if (!rules) "" else buildString {
            if (seen.isNotEmpty()) append("Price evidence, sorted by price (lowest first), year = newest the source mentions: ")
                .append(seen.joinToString(" · ") { it.line() }).append('\n')
            append(when {
                verify -> verifyRules(kind)
                else -> "Using these: answer from what two or more sources agree on, cite them as [n], say where they disagree, " +
                    "and prefer reference and official sources over forums for facts."
            })
            append(" Prefer recent sources for prices and specs.")
        }
        // A lookalike address a page names is shown shortened everywhere, never as a link to visit.
        val phish = onion.claims.values.flatten().map(OnionSafety::address).distinct()
            .filter { OnionSafety.classify("http://$it/", null, onion.known)?.verdict == OnionSafety.Verdict.LIKELY_PHISH }
        val body = OnionSafety.redact(body0, phish)
        val room = max - head.length - tail.length
        return when {
            body.length <= room -> head + body + tail
            room > 1 -> head + body.take(room - 1) + "…" + tail
            else -> (head + tail).let { if (it.length <= max) it else it.take(max - 1) + "…" }
        }
    }
}
