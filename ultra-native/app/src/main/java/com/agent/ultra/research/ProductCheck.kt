package com.agent.ultra.research

import com.agent.ultra.agent.OnionSafety
import com.agent.ultra.agent.OnionSafety.Verdict
import com.agent.ultra.agent.ScamSignals
import com.agent.ultra.agent.WebResearch

/**
 * "Verify this product …": is the product real, is the company real, do its onion and clearnet
 * identities match, what's the price evidence, any scam signals — across clearnet and onion. It
 * lays the evidence out and asks; it never says buy, never permits or forbids.
 *
 * ## Scope (owner, 2026-10-09)
 * Product and company verification across clearnet and onion (owner's example: headphones), and
 * reading wikis and forums through Tor with scam screening. Explicitly OUT of scope ("I don't need
 * special skills for the dark net", "I don't wanna find illegal items"): marketplace navigation,
 * vendor or seller reputation or vetting, and any purchasing. There is no seller rating here and
 * no recommendation field; rows are evidence with their source.
 *
 * Legs: clearnet (the normal research run: pages, prices, sites) and onion ([onionLeg]: the onion
 * pages and forum threads the owner gave, read through Tor — known-good addresses labelled, every
 * unlisted onion screened before any of its words count, phishing and scam pages blocked and never
 * quoted). [companyCheck]: clearnet presence, whether a claimed onion address is published on the
 * company's own site, lookalikes of known addresses, scam signals on the pages. [compare]: one
 * table sorted mechanically with the key labelled, then a question. Pure apart from [onionLeg],
 * which reads through the function it's given (Tor only). JVM-tested.
 */
object ProductCheck {

    /** Trust tiers for the mechanical sort. An order, not a judgement of any seller. */
    enum class Tier(val words: String, val rank: Int) {
        KNOWN_GOOD_ONION("known-good onion", 4),
        LISTED_SITE("known site", 3),
        UNLISTED_ONION("unlisted onion, screened clean — not verified", 2),
        UNLISTED_SITE("unlisted site", 1),
    }

    data class Row(val realm: String, val source: String, val ref: String, val price: WebResearch.Price, val year: Int?, val tier: Tier)

    /** An onion page or post that mentions the product. */
    data class Mention(val url: String, val author: String, val date: String, val text: String, val prices: List<WebResearch.Price>, val year: Int?, val tier: Tier)

    data class Blocked(val address: String, val verdict: Verdict, val reasons: List<String>)

    data class OnionLeg(val mentions: List<Mention>, val blocked: List<Blocked>, val notes: List<String>)

    const val SORT_KEY = "trust tier (highest first), then currency, then price (lowest first) — an order, not a pick"

    const val QUESTION = "What do you want next — compare more sources, check the company further, read one of the sources, or drop it?"

    /** Words of [product] a mention must contain (all of them that are 3+ characters long). */
    internal fun matches(product: String, text: String): Boolean {
        val words = product.lowercase().split(Regex("[^\\p{L}\\d]+")).filter { it.length >= 3 }
        val t = text.lowercase()
        return words.isNotEmpty() && words.all { it in t }
    }

    /**
     * The onion leg over [urls] (pages and threads the owner gave). Each address is classified
     * first: a lookalike is blocked without fetching; an unlisted page is screened before any of
     * its words are used; SCAM / LIKELY_PHISH pages are blocked. Posts (or the page) that mention
     * [product] become mentions with prices and year.
     */
    suspend fun onionLeg(product: String, urls: List<String>, fetch: suspend (String) -> String?, known: Map<String, String>, now: Int): OnionLeg {
        val mentions = ArrayList<Mention>()
        val blocked = ArrayList<Blocked>()
        val notes = ArrayList<String>()
        for (url in urls.take(4)) {
            val pre = OnionSafety.classify(url, null, known) ?: continue
            if (pre.verdict == Verdict.LIKELY_PHISH) { blocked += Blocked(pre.address, pre.verdict, pre.reasons); continue }
            if (!OnionSafety.isV3(pre.address)) { notes += "Note: ${OnionSafety.short(pre.address)} isn't a valid onion address — skipped."; continue }
            val html = fetch(url)
            if (html == null) { notes += "Note: couldn't load ${OnionSafety.short(pre.address)} over Tor."; continue }
            val r = OnionSafety.classify(url, html, known) ?: continue
            if (r.verdict == Verdict.SCAM || r.verdict == Verdict.LIKELY_PHISH) { blocked += Blocked(r.address, r.verdict, r.reasons); continue }
            val tier = if (r.verdict == Verdict.TRUSTED) Tier.KNOWN_GOOD_ONION else Tier.UNLISTED_ONION
            val thread = Forums.thread(url, fetch, 3, html)
            val posts = thread.posts.ifEmpty { listOf(Forums.Post("", "", OnionSafety.text(html))) }
            for (p in posts) if (matches(product, p.text)) {
                val bad = OnionSafety.addressesIn(p.text).filter { OnionSafety.lookalikeOf(it, known.keys) != null }
                mentions += Mention(url, p.author, p.date, OnionSafety.redact(p.text, bad).take(600), WebResearch.prices(p.text, 4),
                    WebResearch.yearOf(p.date, p.text, "", now), tier)
            }
        }
        return OnionLeg(mentions, blocked, notes)
    }

    enum class OnionMatch(val words: String) {
        MATCH("published on the company's own site"),
        LOOKALIKE("a lookalike of a known-good address — likely phishing"),
        THIRD_PARTY_ONLY("named only on other sites, not on the company's own"),
        UNPUBLISHED("not found on any page read"),
        NONE("no onion address claimed"),
    }

    data class Company(val name: String, val presence: List<String>, val claimed: String?, val match: OnionMatch, val scamSignals: List<String>)

    /** The brand token used to recognise the company's own domain ("Sony WH-1000XM5" → "sony"). */
    internal fun brandToken(name: String): String =
        name.lowercase().split(Regex("[^\\p{L}\\d]+")).firstOrNull { it.length >= 3 }.orEmpty()

    /**
     * The company check over the clearnet pages read ([pages]: url → raw HTML) and the onion
     * address claimed ([claimed], from the request or a page). "Own site" = a domain containing
     * the company's first name word. Pure, JVM-tested.
     */
    fun companyCheck(name: String, pages: Map<String, String>, claimed: String?, known: Map<String, String>): Company {
        val brand = brandToken(name)
        val squashed = name.lowercase().filter { it.isLetterOrDigit() }
        // The site's own name must BE the company's name: "sony.com", "acme-audio.com" — not
        // "sony-outlet-deals.shop", which only contains it (a reseller or a fake). Still a heuristic.
        val own = pages.keys.filter { u ->
            val label = siteName(WebResearch.domainOf(u)).replace("-", "")
            brand.isNotEmpty() && (label == brand || label == squashed)
        }
        val presence = own.map(WebResearch::domainOf).distinct()
        val addr = claimed?.let { OnionSafety.onionHost(it) ?: it }?.let(OnionSafety::address)
            ?: pages.values.flatMap { OnionSafety.addressesIn(it) }.firstOrNull()
        val match = when {
            addr == null -> OnionMatch.NONE
            OnionSafety.classify("http://$addr/", null, known)?.verdict == Verdict.LIKELY_PHISH -> OnionMatch.LOOKALIKE
            own.any { addr in pages.getValue(it).lowercase() } -> OnionMatch.MATCH
            pages.values.any { addr in it.lowercase() } -> OnionMatch.THIRD_PARTY_ONLY
            else -> OnionMatch.UNPUBLISHED
        }
        val signals = pages.mapNotNull { (url, html) ->
            val s = OnionSafety.screenPage(html, null)
            val sms = ScamSignals.assess("", OnionSafety.text(html).take(20_000))
            val words = s.signals.filter { it.verdict != null }.map { it.words } + (if (sms.scam) listOf("scam-message pattern: ${sms.reasons}") else emptyList())
            if (words.isEmpty()) null else "${WebResearch.domainOf(url)}: ${words.joinToString("; ")}"
        }
        return Company(name, presence, addr, match, signals)
    }

    /** The registered name of a host: "shop.sony.co.uk" → "sony", "acme-audio.com" → "acme-audio". */
    internal fun siteName(host: String): String {
        val l = host.lowercase().removePrefix("www.").split('.')
        if (l.size < 2) return l.firstOrNull().orEmpty()
        val second = l[l.size - 2]
        return if (l.size >= 3 && second in setOf("co", "com", "org", "net", "ac", "gov") && l.last().length == 2) l[l.size - 3] else second
    }

    /** Clearnet price evidence as rows: known sites tier 3, others tier 1. */
    fun clearRows(seen: List<WebResearch.PriceSeen>): List<Row> = seen.map {
        Row("clearnet", it.domain, "[${it.n}]", it.price, it.year, if (WebResearch.trust(it.domain) != null) Tier.LISTED_SITE else Tier.UNLISTED_SITE)
    }

    fun onionRows(leg: OnionLeg): List<Row> = leg.mentions.flatMap { m ->
        m.prices.map { p -> Row("onion", OnionSafety.onionHost(m.url)?.let { OnionSafety.short(OnionSafety.address(it)) } ?: m.url,
            m.url, p, m.year, m.tier) }
    }

    /** Rows sorted by [SORT_KEY]. */
    fun sort(rows: List<Row>): List<Row> =
        rows.sortedWith(compareBy({ -it.tier.rank }, { it.price.currency }, { it.price.amount }))

    /**
     * The comparison for the brain: the table sorted by [SORT_KEY] (labelled), blocked sources
     * named (never as links), the company check, and [QUESTION] at the end. No pick, no
     * recommendation, no purchase advice. Pure, JVM-tested.
     */
    fun compare(product: String, rows: List<Row>, leg: OnionLeg?, company: Company?): String = buildString {
        append("Product check — ").append(product).append(" (clearnet")
        if (leg != null) append(" + onion")
        append("). Sorted by ").append(SORT_KEY).append(".\n")
        val sorted = sort(rows)
        if (sorted.isEmpty()) append("No prices found in the sources read.\n")
        sorted.take(12).forEach { r ->
            append("- ").append(r.price.text).append(" · ").append(r.realm).append(" · ").append(r.source).append(' ').append(r.ref)
                .append(" · ").append(r.year?.toString() ?: "undated").append(" · ").append(r.tier.words).append('\n')
        }
        leg?.let { l ->
            l.mentions.filter { it.prices.isEmpty() }.take(4).forEach { m ->
                append("Onion mention (no price) ${m.author.ifBlank { "" }}${if (m.date.isNotBlank()) " ${m.date}" else ""} on ${OnionSafety.onionHost(m.url)?.let(OnionSafety::address) ?: m.url}: ${m.text.take(200)}\n")
            }
            l.blocked.forEach { b -> append("Red flag: ${OnionSafety.short(b.address)} blocked as ${b.verdict.name.lowercase().replace('_', ' ')} — ${b.reasons.joinToString("; ")}. Not quoted; not a link to visit.\n") }
            l.notes.forEach { append(it).append('\n') }
            append("Unlisted onions are never trusted: their words are marked not verified even when they screen clean.\n")
        }
        company?.let { c ->
            append("Company check — ").append(c.name).append(": ")
            append(if (c.presence.isEmpty()) "no site of its own among the pages read" else "own site(s) seen: ${c.presence.joinToString(", ")}")
            append(". Onion identity: ")
            append(when (c.match) {
                OnionMatch.NONE -> c.match.words
                OnionMatch.LOOKALIKE -> "${OnionSafety.short(c.claimed.orEmpty())} is ${c.match.words}"
                else -> "${c.claimed} is ${c.match.words}"
            })
            append(". Scam signals: ").append(if (c.scamSignals.isEmpty()) "none found on the pages read" else c.scamSignals.joinToString(" | ")).append(".\n")
        }
        append("Show this evidence as it is, in the table order above and saying that order: cite sources as [n], say where they disagree, repeat every Red flag line. ")
            .append("No pick, no verdict on which one to get, no purchase advice. End with: ").append(QUESTION)
    }
}
