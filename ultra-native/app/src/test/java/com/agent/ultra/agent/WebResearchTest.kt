package com.agent.ultra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebResearchTest {
    @Test fun kinds() {
        assertEquals(WebResearch.Kind.PRODUCT, WebResearch.kind("find me the cheapest Sony WH-1000XM5"))
        assertEquals(WebResearch.Kind.PRODUCT, WebResearch.kind("where can I buy a Steam Deck OLED"))
        assertEquals(WebResearch.Kind.HOWTO, WebResearch.kind("how do I reset a router"))
        assertEquals(WebResearch.Kind.FACT, WebResearch.kind("who is the prime minister of Canada"))
    }

    @Test fun productPlanUsesOperators() {
        val q = WebResearch.plan("find me the cheapest Sony WH-1000XM5", 2026)
        assertEquals("the cheapest Sony WH-1000XM5", q[0])
        assertTrue(q.any { it.startsWith("\"Sony WH-1000XM5\" price") && it.contains("-used") })
        assertTrue(q.any { it.endsWith("site:reddit.com") })
        assertTrue(q.any { it == "best Sony WH-1000XM5 2026" })
    }

    @Test fun factPlanAddsReferenceSites() {
        val q = WebResearch.plan("search the web for the population of Japan", 2026)
        assertEquals("the population of Japan", q[0])
        assertTrue(q.any { "site:wikipedia.org" in it })
    }

    @Test fun parsesDuckDuckGoWithRealLinks() {
        val html = """
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.bestbuy.com%2Fsite%2Fsony%3Fskuid%3D1&amp;rut=x">Sony <b>WH-1000XM5</b> - Best Buy</a>
            <a class="result__snippet" href="x">Now <b>$329.99</b>, was $399.99.</a>
            <a rel="nofollow" class="result__a" href="https://www.reddit.com/r/headphones/xm5">XM5 worth it? : r/headphones</a>
            <a class="result__snippet" href="y">Most say yes for ANC.</a>
        """.trimIndent()
        val hits = WebResearch.parseDdg(html)
        assertEquals(2, hits.size)
        assertEquals("https://www.bestbuy.com/site/sony?skuid=1", hits[0].url)
        assertEquals("Sony WH-1000XM5 - Best Buy", hits[0].title)
        assertEquals("bestbuy.com", hits[0].domain)
        assertTrue(hits[0].snippet.contains("$329.99"))
    }

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("ddg/$name")!!.readText()

    // Saved DuckDuckGo pages (see the files' headers for where they come from), not hand-written markup.
    @Test fun parsesSavedPageWithDirectLinks() {
        val hits = WebResearch.parseDdg(fixture("html_direct_links.html"))
        assertEquals(
            listOf(
                "https://www.thespruce.com/evergreen-ground-cover-plants-4582146",
                "https://www.bhg.com/evergreen-groundcover-plants-8767875",
                "https://savvygardening.com/evergreen-groundcover/",
            ),
            hits.map { it.url },
        )
        assertEquals("15 Best Evergreen Ground Cover Plants - The Spruce", hits[0].title)
        assertTrue(hits[0].snippet.startsWith("Evergreen ground cover plants offer a practical way"))
        assertEquals("thespruce.com", hits[0].domain)
    }

    @Test fun parsesSavedPageWithRedirectLinks() {
        val hits = WebResearch.parseDdg(fixture("html_uddg_links.html"))
        assertEquals(
            listOf(
                "https://en.wikipedia.org/wiki/Foo_Fighters",
                "https://www.dictionary.com/e/tech-science/foo/",
                "https://foofighters.com/",
            ),
            hits.map { it.url },
        )
        assertEquals("foo | Meaning & Origin - Dictionary.com", hits[1].title)
        assertTrue(hits[1].snippet.startsWith("\"Foo was here\" was a popular piece of graffiti"))
        assertTrue(hits[1].snippet.contains("Holman's foo"))
    }

    @Test fun rankingRewardsConsensusAndTrust() {
        val shop = WebResearch.Hit("Best Buy", "https://www.bestbuy.com/x", "")
        val sketchy = WebResearch.Hit("Cheap!!", "https://super-deals-xm5.shop/x", "")
        val forum = WebResearch.Hit("r/headphones", "https://www.reddit.com/r/headphones/x", "")
        val r = WebResearch.rank(listOf(listOf(sketchy, shop), listOf(shop, forum), listOf(forum)), WebResearch.Kind.PRODUCT)
        assertEquals("bestbuy.com", r[0].hit.domain)
        assertEquals(2, r[0].foundBy)
        assertEquals("super-deals-xm5.shop", r.last().hit.domain)
    }

    @Test fun pricesAndTrust() {
        val p = WebResearch.prices("Now $329.99 (was $399.99), or £279 in the UK, 1,299.00 EUR bundle, version 3.5")
        assertEquals(listOf(329.99, 399.99, 279.0, 1299.0), p.map { it.amount })
        assertEquals("known retailer", WebResearch.trust("amazon.co.uk"))
        assertEquals("community / reviews", WebResearch.trust("old.reddit.com"))
        assertEquals("official", WebResearch.trust("nasa.gov"))
        assertEquals(null, WebResearch.trust("super-deals-xm5.shop"))
    }

    private fun amounts(text: String) = WebResearch.prices(text).map { it.amount }

    @Test fun pricesInEuropeanAndPrefixedFormats() {
        assertEquals(listOf(379.99), amounts("379,99 €"))
        assertEquals(listOf(1299.0), amounts("€1.299,00"))
        assertEquals(listOf(1299.0), amounts("€1.299"))
        assertEquals(listOf(1299.0), amounts("1.299 €"))
        assertEquals(listOf(1299.99), amounts("1.299,99 € inkl. MwSt."))
        assertEquals(listOf(1299.0), amounts("$1,299"))
        assertEquals(listOf(12.99), amounts("$12,99"))
        assertEquals(listOf(299.99), amounts("Now $299.99."))
        assertEquals(listOf(349.0, 399.0), amounts("$349.00, was $399.00."))
        assertEquals(listOf(1299.0), amounts("A$1,299"))
        assertEquals(listOf(799.0), amounts("US$799"))
        assertEquals(listOf(379.99), amounts("EUR 379.99"))
        assertEquals(listOf(279.0), amounts("£279, delivered"))
        assertEquals(emptyList<Double>(), amounts("version 3.5"))
        assertEquals(emptyList<Double>(), amounts("call 555 0199"))
        assertEquals(listOf(10.0, 20.0), amounts("€10 $20"))
        assertEquals(listOf(20.0), amounts("only 10 $20 left"))
    }

    @Test fun pricesKeepTheCurrencyAsWritten() {
        assertEquals(listOf("€", "€", "A$", "US$", "$", "£"),
            WebResearch.prices("379,99 € or EUR 5, A$1,299, US$799, $5, £3").map { it.currency })
        assertEquals("379,99 €", WebResearch.prices("now 379,99 € inkl.")[0].text)
    }

    @Test fun amountReadsBothSeparatorStyles() {
        assertEquals(1299.0, WebResearch.amount("1.299,00")!!, 0.0)
        assertEquals(1299.0, WebResearch.amount("1,299")!!, 0.0)
        assertEquals(1299.0, WebResearch.amount("1.299")!!, 0.0)
        assertEquals(12.99, WebResearch.amount("12,99")!!, 0.0)
        assertEquals(299.5, WebResearch.amount("299.5")!!, 0.0)
        assertEquals(1234567.89, WebResearch.amount("1,234,567.89")!!, 0.0)
        assertEquals(42.0, WebResearch.amount("42")!!, 0.0)
        assertEquals(null, WebResearch.amount("1.2345"))
        assertEquals(null, WebResearch.amount("abc"))
        assertEquals(null, WebResearch.amount(""))
    }

    @Test fun yearOfTakesTheNewestPastYear() {
        assertEquals(2025, WebResearch.yearOf("Best headphones 2024", "Updated March 2025", "https://x.com/a", 2026))
        assertEquals(2023, WebResearch.yearOf("Sony XM5 review", "", "https://www.rtings.com/2023/05/xm5", 2026))
        assertEquals(2024, WebResearch.yearOf("Best phones of 2031 (predictions) and 2024", "", "https://x.com", 2026))
        assertEquals(null, WebResearch.yearOf("Sony WH-1000XM5", "Now $329.99, sku 120265", "https://x.com/p?id=20251", 2026))
    }

    @Test fun briefTagsDatedSourcesAndAsksForRecent() {
        val hits = listOf(listOf(
            WebResearch.Hit("Best headphones 2025", "https://www.rtings.com/headphones/2025", "Updated for 2025"),
            WebResearch.Hit("Best Buy", "https://www.bestbuy.com/x", "Now $329.99"),
        ))
        val b = WebResearch.brief("cheapest xm5", WebResearch.Kind.PRODUCT, listOf("xm5"), WebResearch.rank(hits, WebResearch.Kind.PRODUCT), emptyMap(), now = 2026)
        assertTrue(b.contains("— rtings.com (community / reviews) · dated 2025\n"))
        assertTrue(b.contains("— bestbuy.com (known retailer)\n"))
        assertTrue(b.contains("Prefer recent sources for prices and specs."))
    }

    @Test fun pageTextDropsScriptsAndTags() {
        val t = WebResearch.pageText("<html><script>var x=1</script><nav>Menu</nav><h1>Title</h1><p>Body &amp; text</p></html>")
        assertTrue(t.contains("Title")); assertTrue(t.contains("Body & text"))
        assertTrue(!t.contains("var x") && !t.contains("Menu"))
    }

    @Test fun briefNumbersSourcesAndLaysOutTheEvidence() {
        val hits = listOf(listOf(WebResearch.Hit("Best Buy", "https://www.bestbuy.com/x", "Now $329.99")))
        val b = WebResearch.brief("cheapest xm5", WebResearch.Kind.PRODUCT, listOf("xm5"), WebResearch.rank(hits, WebResearch.Kind.PRODUCT), emptyMap())
        assertTrue(b.contains("[1] Best Buy — bestbuy.com (known retailer)"))
        assertTrue(b.contains("prices seen: $329.99"))
        assertTrue(b.contains("in the price-evidence order above (sorted by price, lowest first)"))
        assertTrue(!b.contains("ranked shortlist"))   // an order is not a pick (owner: never "buy this")
    }

    @Test fun ddgBotCheckAndRateLimitReadAsBlockedNotEmpty() {
        assertTrue(WebResearch.ddgBlocked(202, ""))
        assertTrue(WebResearch.ddgBlocked(200, "<form id=\"challenge-form\" action=\"//duckduckgo.com/anomaly.js\">"))
        assertTrue(!WebResearch.ddgBlocked(200, "<a class=\"result__a\" href=\"https://example.com\">Example</a>"))
    }
}
