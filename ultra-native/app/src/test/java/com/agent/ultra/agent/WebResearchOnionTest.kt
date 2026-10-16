package com.agent.ultra.agent

import com.agent.ultra.net.FakeSocks
import com.agent.ultra.net.TorProxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The .onion policy in research, and the verification brief's wording. */
class WebResearchOnionTest {

    private val onion = "aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dyp3kead.onion"
    private val ddg = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
    private val page = "<html><title>Hidden wiki</title><body>" + "A page of links and notes about privacy tools. ".repeat(5) + "</body></html>"

    private class Recorder(val html: String?) { val fetched = ArrayList<String>(); suspend fun get(u: String): String? { fetched += u; return html } }

    @Test fun routeOrderOfflineThenSettingThenProxy() {
        assertEquals(WebResearch.OnionRoute.OFFLINE, WebResearch.onionRoute(true, true, true))
        assertEquals(WebResearch.OnionRoute.OFF, WebResearch.onionRoute(false, false, true))
        assertEquals(WebResearch.OnionRoute.PROXY_DOWN, WebResearch.onionRoute(false, true, false))
        assertEquals(WebResearch.OnionRoute.TOR, WebResearch.onionRoute(false, true, true))
    }

    @Test fun settingOffExcludesWithOneLineAndFetchesNothing() = runBlocking {
        val rec = Recorder(page)
        var probed = false
        val r = WebResearchRun.onionStep(listOf("http://$onion/"), OnionAccess(false, false, OnionSafety.DIRECTORY, { probed = true; true }, rec::get))
        assertEquals(listOf("Note: onion research via Tor is off (Settings) — skipped 1 onion link."), r.lines)
        assertTrue(r.sources.isEmpty()); assertTrue(rec.fetched.isEmpty()); assertFalse(probed)
    }

    @Test fun proxyDownExcludesWithOneLine() = runBlocking {
        val rec = Recorder(page)
        val r = WebResearchRun.onionStep(listOf("http://$onion/", "http://$ddg/"), OnionAccess(true, false, OnionSafety.DIRECTORY, { false }, rec::get))
        assertEquals(listOf("Note: Tor isn't running (start Orbot) — skipped 2 onion links."), r.lines)
        assertTrue(r.sources.isEmpty()); assertTrue(rec.fetched.isEmpty())
    }

    @Test fun onWithProxyUpFetchesThroughTheProxyNeverTheNormalClient() = runBlocking {
        FakeSocks(page).use { s ->
            val tor = TorProxy(port = s.port)
            val r = WebResearchRun.onionStep(listOf("http://$onion/wiki"), OnionAccess(true, false, OnionSafety.DIRECTORY, tor::isAvailable, tor::get))
            assertEquals(1, r.sources.size)
            assertTrue(r.sources[0].label.contains("unlisted address — not verified"))
            assertEquals("Hidden wiki", r.sources[0].title)
            assertEquals(onion, s.connects.single().host)
        }
        // the normal client refuses an onion URL outright
        assertNull(WebResearchRun.get("http://$onion/"))
    }

    @Test fun lookalikeIsNeverFetchedAndScamPageIsLeftOut() = runBlocking {
        val clone = ddg.replaceFirst("gogg42", "gogg43")
        val rec = Recorder("<p>Restore wallet: enter your seed phrase here</p>")
        val r = WebResearchRun.onionStep(listOf("http://$clone/", "http://$onion/"), OnionAccess(true, false, OnionSafety.DIRECTORY, { true }, rec::get))
        assertEquals(listOf("http://$onion/"), rec.fetched)
        assertTrue(r.sources.isEmpty())
        assertEquals(2, r.lines.size)
        assertTrue(r.lines.all { it.startsWith("Red flag:") && "not a link to visit" in it })
        assertTrue(r.lines.none { clone in it || onion in it })          // shortened, never a link
    }

    private val noBuy = Regex("\\b(buy|buying|recommend\\w*|best (pick|choice|option)|you should (get|go with)|go with)\\b", RegexOption.IGNORE_CASE)

    @Test fun verificationBriefHasNoBuyOrRecommendWordingAndEndsWithAQuestion() {
        val hits = listOf(listOf(
            WebResearch.Hit("Sony WH-1000XM5", "https://www.bestbuy.com/x", "Now $329.99"),
            WebResearch.Hit("XM5 deal", "https://cheap-audio.shop/x", "Only $99.00"),
            WebResearch.Hit("XM5 at Amazon", "https://www.amazon.com/x", "$349.99 2026"),
        ))
        for (req in listOf("cheapest sony xm5 headphones", "is acme audio legit", "verify this product sony xm5")) {
            val kind = WebResearch.kind(req)
            val b = WebResearch.brief(req, kind, listOf("q"), WebResearch.rank(hits, kind), emptyMap(), now = 2026)
            val rules = b.substringAfterLast("\n")
            assertFalse("buy/recommend wording in: $rules", noBuy.containsMatchIn(rules))
            assertTrue(rules.contains("No pick, no verdict on which one to get, no purchase advice"))
            assertTrue(rules.contains("End with one question asking what the owner wants next"))
            assertTrue(b.contains(WebResearch.ORDER_KEY))
        }
        val read = mapOf("https://www.bestbuy.com/x" to "Now \$329.99", "https://cheap-audio.shop/x" to "Only \$99.00",
            "https://www.amazon.com/x" to "\$349.99 2026")
        val p = WebResearch.brief("cheapest xm5", WebResearch.Kind.PRODUCT, listOf("q"), WebResearch.rank(hits, WebResearch.Kind.PRODUCT), read, now = 2026)
        assertTrue(p.contains("Price evidence, sorted by price (lowest first)"))
        assertTrue(p.indexOf("$99.00 [3]") < p.indexOf("$329.99 [1]"))
        assertTrue(p.contains("Red flag: $99.00 at [3] cheap-audio.shop is far below the others"))
    }

    @Test fun rulesAndLinesSurviveALongBrief() {
        val hits = listOf((1..8).map { WebResearch.Hit("Result $it", "https://site$it.com/p", "x".repeat(200)) })
        val pages = hits[0].associate { it.url to "y".repeat(1400) }
        val onion = WebResearch.OnionReport(lines = listOf("Note: Tor isn't running (start Orbot) — skipped 1 onion link."))
        val b = WebResearch.brief("is acme legit", WebResearch.Kind.FACT, listOf("q"), WebResearch.rank(hits, WebResearch.Kind.FACT), pages, onion = onion)
        assertTrue(b.length <= 3000)
        assertTrue(b.contains("Note: Tor isn't running"))
        assertTrue(b.endsWith("Prefer recent sources for prices and specs."))
    }

    @Test fun onionSourcesComeFirstAndIdentityLinesCheckClaims() {
        val clone = ddg.replaceFirst("gogg42", "gogg43")
        val hits = listOf(listOf(
            WebResearch.Hit("Acme official", "https://acme.com/", "Our onion: $onion"),
            WebResearch.Hit("Mirror list", "https://lists.example/", "Acme onion $clone"),
        ))
        val report = WebResearch.OnionReport(
            sources = listOf(WebResearch.OnionSource("http://$ddg/", "DuckDuckGo", "onion via Tor · known-good address (DuckDuckGo)", "search")),
            claims = mapOf("https://acme.com/" to listOf(onion), "https://lists.example/" to listOf(clone)),
            known = OnionSafety.DIRECTORY)
        val b = WebResearch.brief("verify this product acme", WebResearch.Kind.FACT, listOf("q"), WebResearch.rank(hits, WebResearch.Kind.FACT), emptyMap(), onion = report)
        assertTrue(b.contains("[1] DuckDuckGo — $ddg (onion via Tor · known-good address (DuckDuckGo))"))
        assertTrue(b.contains("[2] Acme official — acme.com"))
        assertTrue(b.contains("Onion identity: $onion (named on [2] acme.com) is not in the known-good list"))
        assertTrue(b.contains("Red flag: an onion address named on [3] lists.example (duckduck…zczad.onion) is a likely phishing clone"))
        assertFalse(b.contains(clone))
    }

    @Test fun explicitPhrasesPickTheMode() {
        assertEquals(WebResearch.Mode.DEEP, WebResearch.mode("Deep research on privacy wikis"))
        assertEquals("privacy wikis", WebResearch.target("Deep research on privacy wikis"))
        assertEquals(WebResearch.Mode.INVESTIGATE, WebResearch.mode("Jarvis, investigate Acme Audio Ltd"))
        assertEquals("Acme Audio Ltd", WebResearch.target("Jarvis, investigate Acme Audio Ltd"))
        assertEquals(WebResearch.Mode.VERIFY_PRODUCT, WebResearch.mode("verify this product: Sony WH-1000XM5"))
        assertEquals("Sony WH-1000XM5", WebResearch.target("verify this product: Sony WH-1000XM5"))
        assertEquals(WebResearch.Mode.VERIFY_PRODUCT, WebResearch.mode("check this company acme audio"))
        assertEquals(WebResearch.Mode.NORMAL, WebResearch.mode("what is deep research"))
        assertEquals(WebResearch.Mode.NORMAL, WebResearch.mode("cheapest xm5"))
        assertEquals(WebResearch.Mode.NORMAL, WebResearch.mode("check the company's stock price"))
        assertEquals(WebResearch.Mode.NORMAL, WebResearch.mode("investigate why my battery drains"))
        assertEquals(WebResearch.Mode.VERIFY_PRODUCT, WebResearch.mode("verify the product Sony XM5"))
    }

    @Test fun onlyRealOrScamQuestionsGetTheVerificationRules() {
        assertFalse(WebResearch.verifying("how to clone a git repo"))
        assertFalse(WebResearch.verifying("is the Mona Lisa authentic"))
        assertFalse(WebResearch.verifying("what does fake news mean"))
        assertTrue(WebResearch.verifying("is acme audio legit"))
        assertTrue(WebResearch.verifying("is this site real or fake"))
        assertTrue(WebResearch.verifying("is cheap-audio.shop a scam"))
        assertTrue(WebResearch.verifying("is this company real"))
        assertTrue(WebResearch.plan("is the Mona Lisa authentic", 2026).last().contains("wikipedia"))
    }

    @Test fun onionUrlsNeverGoIntoClearnetQueries() {
        assertEquals("Sony XM5", WebResearchRun.stripUrls("Sony XM5 http://$onion/t/1", listOf("http://$onion/t/1")))
        assertEquals("Sony XM5", WebResearchRun.stripUrls("$onion Sony XM5", listOf("http://$onion")))
    }
}
