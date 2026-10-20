package com.agent.jarvis.research

/**
 * Seller reputation, merge, rank — port of the TESTED Python
 * reference (jarvis-v4/shopping/reputation.py).
 *
 * Works on seller/listing pages the caller already fetched (and
 * where relevant screened). URL-agnostic like the rest of
 * shopping/: no fetching, no transport selection, no realm
 * branching here.
 *
 * - sellerReputation: rating, review count, feedback snippets,
 *   disputes, account age — every claim cites its source URL.
 * - mergeSellers: same seller across marketplaces, matching
 *   method SHOWN (exact/strong/weak + evidence). No silent merges.
 * - rankSellers: LABELLED mechanical rule only. Suspicious sellers
 *   stay listed with their flag — the table informs, the user
 *   decides. No buy language, no permit/forbid.
 *
 * SOURCE ONLY: not compiled in this sandbox. Pure logic — no
 * Android deps.
 */
object SellerReputation {

    const val SORT_KEY_LABEL =
        "rating (highest first), then review count (highest), then disputes (fewest)"

    data class Page(val url: String, val content: String)

    data class Reputation(
        val seller: String,
        val rating: Double?,
        val reviewCount: Int?,
        val feedback: List<Feedback>,
        val disputes: Int?,
        val accountAge: String,
        val sources: List<String>,
    )

    data class Feedback(val text: String, val source: String)

    data class SellerEntry(val seller: String, val reputation: Reputation?)

    data class MergeGroup(val sellers: List<SellerEntry>, val method: String, val evidence: String)

    data class Ranked(val sellers: List<SellerEntry>, val sort: String, val count: Int)

    private val TAG = Regex("<[^>]+>")
    private val RATING_RE = Regex("(\\d(?:[.,]\\d)?)\\s*(?:/|out of)\\s*5(?:\\.0)?")
    private val RATING_PCT_RE = Regex("\\b(\\d{1,3})\\s*%\\s*(?:positive|approval)?")
    private val REVIEWS_RE = Regex("([\\d,]+)\\s*(?:reviews|ratings|feedback|transactions)")
    private val DISPUTE_RE = Regex("(?i)([\\d,]+)\\s*(?:disputes|negative feedback|complaints)")
    private val AGE_RE = Regex(
        "(?i)(?:member since|joined|on (?:the )?platform since|account (?:age|created))\\s*[:\\-–—]?\\s*([\\w ,./]{3,30})")
    private val SNIPPET_RE = Regex("<(?:p|div|li)[^>]*>(.{20,300}?)</(?:p|div|li)>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private fun clean(t: String) = TAG.replace(t, " ").replace(Regex("\\s+"), " ").trim()
    private fun toInt(s: String) = s.replace(",", "").toIntOrNull()

    /** Reputation evidence for one seller from caller-supplied pages. */
    fun sellerReputation(seller: String, pages: List<Page>): Reputation {
        var rating: Double? = null
        var reviewCount: Int? = null
        var disputes: Int? = null
        var accountAge = ""
        val feedback = ArrayList<Feedback>()
        val sources = ArrayList<String>()
        for (pg in pages) {
            val url = pg.url.trim()
            val text = clean(pg.content)
            if (url.isEmpty() || text.isEmpty()) continue
            if (url !in sources) sources += url
            if (rating == null) {
                RATING_RE.find(text)?.let {
                    rating = it.groupValues[1].replace(",", ".").toDoubleOrNull()
                } ?: RATING_PCT_RE.find(text)?.let {
                    rating = (it.groupValues[1].toIntOrNull() ?: 0) / 20.0
                }
            }
            if (reviewCount == null)
                REVIEWS_RE.find(text)?.let { reviewCount = toInt(it.groupValues[1]) }
            if (disputes == null)
                DISPUTE_RE.find(text)?.let { disputes = toInt(it.groupValues[1]) }
            if (accountAge.isEmpty())
                AGE_RE.find(text)?.let { accountAge = it.groupValues[1].trim().take(40) }
            for (sm in SNIPPET_RE.findAll(pg.content)) {
                val snip = clean(sm.groupValues[1])
                if (feedback.size < 5 && snip.length > 25)
                    feedback += Feedback(snip.take(200), url)
            }
        }
        return Reputation(seller, rating, reviewCount, feedback, disputes, accountAge, sources)
    }

    private fun norm(name: String) = name.lowercase().replace(Regex("[^a-z0-9]"), "")

    /**
     * Merge the same seller across marketplaces. Each group shows
     * its method and evidence — no silent merges.
     */
    fun mergeSellers(candidates: List<SellerEntry>): List<MergeGroup> {
        val groups = ArrayList<MergeGroup>()
        val order = mapOf("exact" to 0, "strong" to 1, "weak" to 2)
        for (cand in candidates) {
            var placed = false
            for (gi in groups.indices) {
                val g = groups[gi]
                val repName = g.sellers[0].seller
                val n1 = norm(cand.seller)
                val n2 = norm(repName)
                var method: String? = null
                var evidence = ""
                if (n1.isNotEmpty() && n1 == n2) {
                    method = "exact"; evidence = "name '${cand.seller}'"
                } else if (n1.isNotEmpty() && n2.isNotEmpty() && (n1 in n2 || n2 in n1)) {
                    method = "strong"; evidence = "substring match '${cand.seller}' ~ '$repName'"
                } else {
                    val t1 = Regex("[a-z0-9]+").findAll(n1).map { it.value }.toSet()
                    val t2 = Regex("[a-z0-9]+").findAll(n2).map { it.value }.toSet()
                    if (t1.isNotEmpty() && t2.isNotEmpty()) {
                        val overlap = (t1 intersect t2).size.toDouble() / maxOf(t1.size, t2.size)
                        if (overlap >= 0.5) {
                            method = "weak"
                            evidence = "token overlap ${"%.2f".format(overlap)}: ${(t1 intersect t2).sorted()}"
                        }
                    }
                }
                if (method != null) {
                    val sellers = g.sellers + cand
                    // keep the weakest method honestly labeled
                    val newMethod =
                        if ((order[method] ?: 0) > (order[g.method] ?: 0)) method else g.method
                    val newEvidence = if (newMethod == method) evidence else g.evidence
                    groups[gi] = g.copy(sellers = sellers, method = newMethod, evidence = newEvidence)
                    placed = true
                    break
                }
            }
            if (!placed) groups += MergeGroup(listOf(cand), "exact", "single entry '${cand.seller}'")
        }
        return groups
    }

    /** Ranked by the LABELLED mechanical rule only. */
    fun rankSellers(sellers: List<SellerEntry>): Ranked {
        val ranked = sellers.sortedWith(compareBy(
            { -(it.reputation?.rating ?: -1.0) },
            { -(it.reputation?.reviewCount ?: -1) },
            { it.reputation?.disputes ?: Int.MAX_VALUE },
            { it.seller },
        ))
        return Ranked(ranked, SORT_KEY_LABEL, ranked.size)
    }
}
