package com.agent.jarvis.research

import com.agent.jarvis.agent.OnionSafety
import com.agent.jarvis.agent.OnionSafety.Verdict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DiscoveryTest {

    private val ddg = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
    private val clone = ddg.replaceFirst("gogg42", "gogg43")
    // Valid v3 addresses made for the tests; nobody's service.
    private val a = "ceirceirceirceirceirceirceirceirceirceirceirceircei7l4yd.onion"
    private val b = "eeqscijbeeqscijbeeqscijbeeqscijbeeqscijbeeqscijbeeqxx2id.onion"
    private val d = "geytcmjrgeytcmjrgeytcmjrgeytcmjrgeytcmjrgeytcmjrgeyqz5id.onion"
    private val e = "ifaucqkbifaucqkbifaucqkbifaucqkbifaucqkbifaucqkbifa2mryd.onion"
    private val forum = "kfivcukrkfivcukrkfivcukrkfivcukrkfivcukrkfivcukrkfiuoaid.onion"

    private fun link(x: String) = "<a href=\"http://$x/\">$x</a>"

    private val web = mapOf(
        "http://$ddg/" to "<title>DuckDuckGo</title>${link(a)}${link(b)}${link(clone)}",
        "http://$a/" to "<title>Privacy wiki</title>${link(d)}",
        "http://$b/" to "<title>Wallet</title><p>Enter your seed phrase to sync your wallet</p>${link(e)}",
        "http://$d/" to "<title>Deep</title>",
        "http://$e/" to "<title>Never reached</title>",
    )

    private class Net(val web: Map<String, String>, var up: Boolean = true) {
        val fetched = ArrayList<String>()
        suspend fun get(u: String): String? { fetched += u; return if (up) web[u] else null }
    }

    @Test fun crawlPoolsCleanFlagsBadAndNeverFollowsOrFetchesThem() = runBlocking {
        val net = Net(web)
        val c = Discovery(net::get, { net.up }, OnionSafety.DIRECTORY, { 5L }).crawl(listOf("http://$ddg/"), maxDepth = 1, maxPages = 10)
        assertEquals(listOf(a), c.pool.map { it.address })
        assertEquals("Privacy wiki", c.pool[0].title); assertEquals("http://$ddg/", c.pool[0].foundOn)
        assertEquals(setOf(b to Verdict.SCAM, clone to Verdict.LIKELY_PHISH), c.redFlags.map { it.address to it.verdict }.toSet())
        assertFalse(net.fetched.any { clone in it })        // a lookalike is never fetched
        assertFalse(net.fetched.any { e in it })            // a scam page's links are never followed
        assertFalse(net.fetched.any { d in it })            // depth bound
        assertEquals(3, c.pagesFetched)
    }

    @Test fun pageLimitIsEnforced() = runBlocking {
        val many = listOf(a, b, d, e, forum)
        val w = mapOf("http://$ddg/" to many.joinToString("") { link(it) }) + many.associate { "http://$it/" to "<title>ok</title>" }
        val net = Net(w)
        val c = Discovery(net::get, { true }, OnionSafety.DIRECTORY).crawl(listOf("http://$ddg/"), maxDepth = 1, maxPages = 3)
        assertEquals(3, net.fetched.size); assertEquals(3, c.pagesFetched)
        assertTrue(c.note!!.contains("page limit"))
    }

    @Test fun torDownStopsWithANoteAndNoOtherFetch() = runBlocking {
        val net = Net(web, up = false)
        val c = Discovery(net::get, { net.up }, OnionSafety.DIRECTORY).crawl(listOf("http://$ddg/", "http://$a/"))
        assertEquals(1, net.fetched.size)
        assertTrue(c.note!!.contains("Tor stopped answering"))
        assertTrue(c.pool.isEmpty())
    }

    @Test fun invalidSeedsAreSkippedNotFetched() = runBlocking {
        val net = Net(web)
        val c = Discovery(net::get, { true }, OnionSafety.DIRECTORY).crawl(listOf("http://example.com/", "abc.onion"))
        assertTrue(net.fetched.isEmpty()); assertEquals(2, c.errors.size)
    }

    @Test fun poolAndRedFlagLogPersistAndRefuseBadEntries() {
        val dir = Files.createTempDirectory("pool").toFile()
        try {
            assertTrue(Discovery.loadPool(dir).isEmpty())
            Discovery.savePool(dir, listOf(Discovery.PoolEntry(a, "wiki", "http://$ddg/", 1, Verdict.UNKNOWN, "")))
            val all = Discovery.savePool(dir, listOf(
                Discovery.PoolEntry(a, "dup", "", 2, Verdict.UNKNOWN, ""),
                Discovery.PoolEntry(b, "scam", "", 2, Verdict.SCAM, ""),
                Discovery.PoolEntry(clone, "bad checksum", "", 2, Verdict.UNKNOWN, ""),
                Discovery.PoolEntry(ddg, "ddg", "", 2, Verdict.TRUSTED, "")))
            assertEquals(listOf(a, ddg), all.map { it.address })
            assertEquals(listOf(a, ddg), Discovery.loadPool(dir).map { it.address })
            assertEquals("wiki", Discovery.loadPool(dir)[0].title)
            Discovery.logFlags(dir, listOf(Discovery.Flag(b, Verdict.SCAM, listOf("seed"), "http://$ddg/")), 9)
            assertEquals(listOf(b to listOf("seed")), Discovery.loadFlags(dir).map { it.address to it.reasons })
        } finally { dir.deleteRecursively() }
    }

    @Test fun threadMentionsCarryWhereTheyWereFound() = runBlocking {
        val thread = """<div class="post"><span class="author">carol</span><time datetime="2026-09-30">x</time>
            Sony XM5 support is at $a and mail help@acme-audio.com; avoid $clone</div>
            <div class="post"><span class="author">dave</span>unrelated chat $d</div>"""
        val net = Net(mapOf("http://$forum/t" to thread))
        val r = Discovery(net::get, { true }, OnionSafety.DIRECTORY, { 77L }).readThreads("xm5 support", listOf("http://$forum/t"))
        assertEquals(1, r.posts.size)
        val byValue = r.mentions.associateBy { it.value }
        assertEquals(Verdict.UNKNOWN, byValue.getValue(a).verdict)
        assertEquals(Verdict.LIKELY_PHISH, byValue.getValue(clone).verdict)
        assertEquals("email", byValue.getValue("help@acme-audio.com").kind)
        assertNull(byValue[d])                                                 // not in a matching post
        for (m in r.mentions) { assertEquals("http://$forum/t", m.thread); assertEquals("carol", m.author); assertEquals("2026-09-30", m.date); assertEquals(77L, m.fetchedAt) }
        val text = Discovery.render(null, r, emptyList(), emptyList())
        assertFalse(text.contains(clone))
        assertTrue(text.contains("Red flag: a post mentions duckduck…zczad.onion"))
    }
}
