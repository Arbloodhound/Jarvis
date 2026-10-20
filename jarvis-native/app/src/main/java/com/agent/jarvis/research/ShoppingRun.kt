package com.agent.jarvis.research

import com.agent.jarvis.agent.OnionAccess
import com.agent.jarvis.agent.WebResearch
import com.agent.jarvis.agent.WebResearchRun

/**
 * The shopping brief for the brain: candidate pages fetched on the ordinary web path, one row per
 * seller-offer ([ShoppingListings]), seller reputation merged and ranked ([SellerReputation]), laid
 * out as a sortable table. It informs and asks; it never picks a seller or says buy.
 *
 * ## The onion carve-out (owner's design)
 * Shopping is the ONE place the onion-marketplace exclusion applies: every run calls
 * [ShoppingListings.excludeOnion], so an .onion row (by its source or its link) is dropped and only
 * counted. The exclusion lives in [ShoppingListings] and is applied here, explicitly — extraction
 * itself never branches on realm. Product/company verification and the deep crawl do NOT exclude
 * onion; this does.
 *
 * [build] and [render] are pure (JVM-tested); [run] is the thin network wiring over [WebResearchRun].
 */
object ShoppingRun {

    const val SORT_PRICE = "price"

    data class Built(
        val offers: ShoppingListings.Table,
        val excludedOnion: Int,
        val ranked: SellerReputation.Ranked,
        val merges: List<SellerReputation.MergeGroup>,
    )

    /**
     * The listings and reputation from [pages] (url → raw HTML the caller already fetched). Rows on
     * onion URLs are excluded here (the shopping-only carve-out) and counted, never ranked. Pure.
     */
    fun build(pages: List<Pair<String, String>>, fetchedAt: String, sort: String = SORT_PRICE): Built {
        val raw = pages.flatMap { (url, html) -> ShoppingListings.extractListings(html, url, fetchedAt).rows }
        // The shopping-only carve-out: onion marketplace rows are dropped before anything is shown.
        val filtered = ShoppingListings.excludeOnion(raw)
        val offers = ShoppingListings.compareTable(filtered.rows, sort)
        // Same carve-out for reputation: an onion page never becomes a cited source either.
        val repPages = pages.filterNot { ShoppingListings.isOnionUrl(it.first) }
            .map { SellerReputation.Page(it.first, it.second) }
        val entries = offers.rows.map { it.seller }.filter { it.isNotBlank() }.distinct().map { s ->
            // Only pages that actually name this seller vouch for it — no reputation from a page about someone else.
            val own = repPages.filter { it.content.contains(s, ignoreCase = true) }
            SellerReputation.SellerEntry(s, if (own.isEmpty()) null else SellerReputation.sellerReputation(s, own))
        }
        val merges = SellerReputation.mergeSellers(entries)
        val ranked = SellerReputation.rankSellers(merges.map { it.sellers.first() })
        return Built(offers, filtered.excluded.size, ranked, merges)
    }

    /** The table for the brain: offers in the labelled order, seller reputation with its source, no pick. Pure. */
    fun render(query: String, built: Built): String = buildString {
        append("Shopping — ").append(query).append(". ")
        append("${built.offers.count} offers from ${built.ranked.count} sellers")
        if (built.excludedOnion > 0) append("; ${built.excludedOnion} onion marketplace row(s) excluded")
        append(".\nOffers sorted by ").append(built.offers.sort).append(" — an order, not a pick.\n")
        if (built.offers.rows.isEmpty()) append("No priced listings found in the pages read.\n")
        built.offers.rows.take(15).forEachIndexed { i, r ->
            append("- ").append(r.price?.text ?: "no price").append(" · ").append(r.title.take(60))
            if (r.seller.isNotBlank()) append(" · ").append(r.seller)
            if (r.stock.isNotBlank()) append(" · ").append(r.stock)
            if (r.condition.isNotBlank()) append(" · ").append(r.condition)
            append(" · ").append(WebResearch.domainOf(r.sourceUrl)).append(" [${i + 1}]\n")
        }
        if (built.ranked.sellers.isNotEmpty()) {
            append("Sellers ranked by ").append(built.ranked.sort).append(":\n")
            built.ranked.sellers.take(10).forEach { e ->
                append("- ").append(e.seller)
                e.reputation?.let { rep ->
                    rep.rating?.let { append(" · rating ${"%.1f".format(it)}/5") }
                    rep.reviewCount?.let { append(" · $it reviews") }
                    rep.disputes?.let { if (it > 0) append(" · $it disputes") }
                    if (rep.accountAge.isNotBlank()) append(" · since ${rep.accountAge}")
                    if (rep.sources.isNotEmpty()) append(" (from ${rep.sources.joinToString(", ") { WebResearch.domainOf(it) }})")
                } ?: append(" · no reputation evidence found")
                append('\n')
            }
        }
        built.merges.filter { it.sellers.size > 1 }.take(5).forEach { g ->
            append("Same seller across sources: ${g.sellers.joinToString(", ") { it.seller }} — ${g.method} (${g.evidence})\n")
        }
        append("Show the offers in the order above and say that order; cite each source as [n]; show seller reputation with its source; ")
            .append("flag a suspicious seller but keep it listed — the table informs, the user decides. ")
            .append("No pick, no verdict on which to buy, no purchase advice. End with: ")
            .append(ShoppingCheckout.DIRECTION_QUESTION)
    }

    /** The shopping run for [query] over the ordinary web path, or null when no pages came back. */
    suspend fun run(query: String, onStatus: (String) -> Unit = {}, onion: OnionAccess = OnionAccess.OFF, sort: String = SORT_PRICE): String? {
        val o = WebResearchRun.research(query, null, onStatus, onion)
        if (o.raw.isEmpty()) return null
        val stamp = java.time.Instant.ofEpochMilli(o.fetchedAt).toString()
        val built = build(o.raw.toList(), stamp, sort)
        if (built.offers.rows.isEmpty()) return null
        return render(query, built)
    }
}
