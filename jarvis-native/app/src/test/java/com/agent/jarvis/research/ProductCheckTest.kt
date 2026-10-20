package com.agent.jarvis.research

import com.agent.jarvis.agent.OnionSafety
import com.agent.jarvis.agent.OnionSafety.Verdict
import com.agent.jarvis.agent.WebResearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductCheckTest {

    private val ddg = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
    private val clone = ddg.replaceFirst("gogg42", "gogg43")
    private val forum = "kfivcukrkfivcukrkfivcukrkfivcukrkfivcukrkfivcukrkfiuoaid.onion"
    private val scam = "eeqscijbeeqscijbeeqscijbeeqscijbeeqscijbeeqscijbeeqxx2id.onion"
    private val acmeOnion = "ceirceirceirceirceirceirceirceirceirceirceirceircei7l4yd.onion"

    private val noBuy = Regex("\\b(buy|buying|recommend\\w*|permit\\w*|forbid\\w*|allowed|best (pick|choice))\\b", RegexOption.IGNORE_CASE)

    @Test fun onionLegScreensFirstAndBlocksPhishAndScam() = runBlocking {
        val web = mapOf(
            "http://$forum/t" to """<div class="post"><span class="author">erin</span><time datetime="2026-08-01">x</time>Sony XM5 headphones went for $280 in 2026</div>
                <div class="post"><span class="author">finn</span>unrelated</div>""",
            "http://$scam/" to "<p>Sony XM5 — send 0.01 BTC and receive 2x back</p>",
        )
        val fetched = ArrayList<String>()
        val leg = ProductCheck.onionLeg("Sony XM5", listOf("http://$forum/t", "http://$scam/", "http://$clone/"), { u -> fetched += u; web[u] }, OnionSafety.DIRECTORY, 2026)
        assertEquals(1, leg.mentions.size)
        val m = leg.mentions[0]
        assertEquals("erin", m.author); assertEquals(280.0, m.prices.single().amount, 0.0); assertEquals(2026, m.year)
        assertEquals(ProductCheck.Tier.UNLISTED_ONION, m.tier)
        assertEquals(setOf(scam to Verdict.SCAM, clone to Verdict.LIKELY_PHISH), leg.blocked.map { it.address to it.verdict }.toSet())
        assertFalse(fetched.any { clone in it })
    }

    @Test fun companyCheckMatchesOnionOnTheCompanysOwnSite() {
        val pages = mapOf(
            "https://www.acme-audio.com/about" to "<p>Our onion service: $acmeOnion</p>",
            "https://blog.example/acme" to "<p>Acme's onion is $acmeOnion</p>",
        )
        val c = ProductCheck.companyCheck("Acme Audio", pages, null, OnionSafety.DIRECTORY)
        assertEquals(listOf("acme-audio.com"), c.presence)
        assertEquals(ProductCheck.OnionMatch.MATCH, c.match)
        val third = ProductCheck.companyCheck("Acme Audio", mapOf("https://blog.example/acme" to "<p>$acmeOnion</p>"), null, OnionSafety.DIRECTORY)
        assertEquals(ProductCheck.OnionMatch.THIRD_PARTY_ONLY, third.match)
        assertEquals(ProductCheck.OnionMatch.LOOKALIKE, ProductCheck.companyCheck("DuckDuckGo", emptyMap(), "http://$clone/", OnionSafety.DIRECTORY).match)
        assertEquals(ProductCheck.OnionMatch.UNPUBLISHED, ProductCheck.companyCheck("Acme", emptyMap(), acmeOnion, OnionSafety.DIRECTORY).match)
        assertEquals(ProductCheck.OnionMatch.NONE, ProductCheck.companyCheck("Acme", emptyMap(), null, OnionSafety.DIRECTORY).match)
        val scammy = ProductCheck.companyCheck("Acme", mapOf("https://acme-giveaway.top/" to "<p>We double your bitcoin today</p>"), null, OnionSafety.DIRECTORY)
        assertTrue(scammy.scamSignals.single().startsWith("acme-giveaway.top:"))
        // a domain that only contains the name is not the company's own site
        val reseller = ProductCheck.companyCheck("Sony", mapOf("https://sony-outlet-deals.shop/" to "<p>$acmeOnion</p>"), null, OnionSafety.DIRECTORY)
        assertTrue(reseller.presence.isEmpty()); assertEquals(ProductCheck.OnionMatch.THIRD_PARTY_ONLY, reseller.match)
        assertEquals("sony", ProductCheck.siteName("shop.sony.co.uk")); assertEquals("acme-audio", ProductCheck.siteName("www.acme-audio.com"))
    }

    @Test fun tableIsSortedMechanicallyWithTheKeyLabelledAndEndsWithAQuestion() {
        val hitA = WebResearch.Hit("XM5", "https://www.bestbuy.com/x", "$329.99")
        val hitB = WebResearch.Hit("XM5", "https://cheap.example/x", "$199.00")
        val seen = WebResearch.priceEvidence(listOf(Triple(1, hitA, "XM5 now \$329.99"), Triple(2, hitB, "XM5 \$199.00")), 2026)
        val leg = ProductCheck.OnionLeg(
            listOf(ProductCheck.Mention("http://$ddg/", "", "", "XM5 $300", WebResearch.prices("XM5 $300"), null, ProductCheck.Tier.KNOWN_GOOD_ONION)),
            listOf(ProductCheck.Blocked(clone, Verdict.LIKELY_PHISH, listOf("1 character away from DuckDuckGo's known address"))), emptyList())
        val rows = ProductCheck.clearRows(seen) + ProductCheck.onionRows(leg)
        assertEquals(listOf(300.0, 329.99, 199.0), ProductCheck.sort(rows).map { it.price.amount })
        val text = ProductCheck.compare("Sony XM5", rows, leg, ProductCheck.Company("Sony", listOf("sony.com"), null, ProductCheck.OnionMatch.NONE, emptyList()))
        assertTrue(text.contains("Sorted by ${ProductCheck.SORT_KEY}"))
        assertTrue(text.trimEnd().endsWith(ProductCheck.QUESTION))
        assertFalse(text.contains(clone))
        assertTrue(text.contains("Red flag: duckduck…zczad.onion blocked as likely phish"))
        assertFalse("buy/permit wording: $text", noBuy.containsMatchIn(text.replace("bestbuy.com", "")))
    }
}
