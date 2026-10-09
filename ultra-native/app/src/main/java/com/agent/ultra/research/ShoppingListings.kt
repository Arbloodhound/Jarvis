package com.agent.ultra.research

import com.agent.ultra.agent.WebResearch

/**
 * URL-agnostic listings extraction — port of the TESTED Python
 * reference (jarvis-v4/shopping/listings.py).
 *
 * Takes (content, url) and works on whatever it is given: a
 * clearnet shop page, a company's onion shop page, app screen text.
 * NEVER fetches, NEVER picks a transport, NEVER screens — the
 * caller does that (Tor transport + onion screening for .onion,
 * existing fetcher otherwise).
 *
 * Multi-seller marketplace pages yield ONE ROW PER SELLER-OFFER.
 * No per-site plugins, no product names, no category lists.
 *
 * The ONE policy carve-out is [excludeOnion] below — a SEPARATE
 * function, per the owner's design ("it stays general but has
 * that exclusion"). Extraction never calls it and never branches
 * on realm; the caller applies it explicitly.
 *
 * SOURCE ONLY: not compiled in this sandbox. For Claude-in-repo
 * to drop into ultra-native/.../research/ and run under the JVM
 * suite. Pure logic — no Android deps.
 */
object ShoppingListings {

    data class Row(
        val title: String,
        val price: WebResearch.Price?,
        val year: Int?,
        val seller: String,
        val stock: String,
        val condition: String,
        val location: String,
        val link: String,
        val sourceUrl: String,
        val fetchedAt: String,
    )

    data class Table(val rows: List<Row>, val sort: String, val count: Int)
    data class Filtered(val rows: List<Row>, val excluded: List<Row>, val count: Int)

    private val TAG = Regex("<[^>]+>")
    private val BLOCK_SPLIT = Regex("</(?:div|li|tr|article|section)>", RegexOption.IGNORE_CASE)
    private val HREF = Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val HEAD = Regex("<head.*?</head>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val HEADING = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val PRICE_RE = Regex("(\\$|€|£)\\s?([\\d,]+(?:\\.\\d{2})?)")
    private val YEAR_RE = Regex("\\b(19|20)\\d{2}\\b")
    private val SELLER_RE = Regex(
        "(?i:sold by|seller|vendor|offered by)\\s*[:\\-–—]?\\s*([A-Z0-9][\\w&'\\-]*(?:\\s+[A-Z0-9][\\w&'\\-]*){0,3})")
    private val STOCK_RE = Regex(
        "(?i)\\b(in stock|out of stock|only \\d+ left(?: in stock)?|available|pre[\\- ]?order|backorder)\\b")
    private val CONDITION_RE = Regex(
        "(?i)\\b(new|used\\s*[–\\-]?\\s*like new|refurbished|open box|pre[\\- ]?owned)\\b")
    private val LOCATION_RE = Regex(
        "(?i:ships from|located in|location|dispatched from)\\s*[:\\-–—]?\\s*([A-Z][\\w&'\\-]*(?:\\s+[A-Z][\\w&'\\-]*){0,3})")

    private fun clean(t: String) = TAG.replace(t, " ").replace(Regex("\\s+"), " ").trim()

    private fun parsePrice(chunk: String): WebResearch.Price? {
        val m = PRICE_RE.find(chunk) ?: return null
        val amount = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        val currency = when (m.groupValues[1]) {
            "€" -> "EUR"; "£" -> "GBP"; else -> "USD"
        }
        return WebResearch.Price(amount, currency, m.value)
    }

    private fun parseYear(chunk: String): Int? =
        YEAR_RE.findAll(chunk).mapNotNull { it.value.toIntOrNull() }.maxOrNull()

    /**
     * Extract listing rows from page CONTENT the caller already
     * fetched (and where relevant screened). One row per priced
     * block — multi-seller pages yield one row per seller-offer.
     * Blocks with no parseable price are skipped.
     */
    fun extractListings(content: String, url: String, fetchedAt: String = ""): Table {
        val u = url.trim()
        if (u.isEmpty() || content.isBlank()) return Table(emptyList(), "price (lowest first)", 0)
        val stamp = fetchedAt.ifEmpty { java.time.Instant.now().toString() }
        val page = HEAD.replace(if ("<" in content) content else "<div>$content</div>", " ")
        val rows = ArrayList<Row>()
        for (block in BLOCK_SPLIT.split(page)) {
            val chunk = clean(block)
            if (chunk.isEmpty()) continue
            val price = parsePrice(chunk) ?: continue
            val title = HEADING.find(block)?.let { clean(it.groupValues[1]) }
                ?: chunk.take(80)
            val seller = SELLER_RE.find(chunk)?.groupValues?.get(1)?.trim().orEmpty()
            val stock = STOCK_RE.find(chunk)?.groupValues?.get(1).orEmpty()
            val condition = CONDITION_RE.find(chunk)?.groupValues?.get(1).orEmpty()
            val location = LOCATION_RE.find(chunk)?.groupValues?.get(1)?.trim().orEmpty()
            val link = HREF.find(block)?.groupValues?.get(1) ?: u
            rows += Row(title, price, parseYear(chunk), seller, stock, condition,
                location, link, u, stamp)
        }
        return Table(rows, "price (lowest first)", rows.size)
    }

    /** One searchable/sortable table. Mechanical sorts only, sort key labeled. No buy language. */
    fun compareTable(rows: List<Row>, sort: String = "price"): Table = when (sort) {
        "price" -> Table(rows.sortedBy { it.price?.amount ?: Double.MAX_VALUE },
            "price (lowest first)", rows.size)
        "year" -> Table(rows.sortedByDescending { it.year ?: Int.MIN_VALUE },
            "year (newest first)", rows.size)
        else -> throw IllegalArgumentException("unknown sort $sort — refusing to guess")
    }

    // ------------------------------------------------- onion exclusion
    // SEPARATE policy function, per the owner's design: extraction
    // above stays general and URL-agnostic. The onion exclusion
    // lives HERE and ONLY here, applied explicitly by the caller —
    // never silently inside extraction.

    /** True if the URL's hostname ends with .onion (hostname parsing, not naive endswith). */
    fun isOnionUrl(url: String): Boolean {
        val host = try {
            java.net.URI(url.trim()).host?.lowercase().orEmpty()
        } catch (_: Exception) { "" }
        return host.endsWith(".onion") && host.length > ".onion".length
    }

    /**
     * Drop rows whose sourceUrl OR link is an onion URL. Returns the
     * kept rows, the excluded rows, and the kept count. The caller
     * decides when to apply this.
     */
    fun excludeOnion(rows: List<Row>): Filtered {
        val kept = ArrayList<Row>()
        val excluded = ArrayList<Row>()
        for (r in rows) {
            if (isOnionUrl(r.sourceUrl) || isOnionUrl(r.link)) excluded += r
            else kept += r
        }
        return Filtered(kept, excluded, kept.size)
    }
}
