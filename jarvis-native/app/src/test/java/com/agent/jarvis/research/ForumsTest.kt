package com.agent.jarvis.research

import com.agent.jarvis.agent.OnionSafety
import com.agent.jarvis.agent.OnionSafety.Verdict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForumsTest {

    private val forum = "aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dyp3kead.onion"
    private val ddg = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"

    private fun page(n: Int, next: String?) = """
        <html><body><div class="thread">
        <div class="post" id="p${n}a"><span class="username"><a href="/u/1">alice$n</a></span><time datetime="2026-0$n-01">x</time>
          <div class="post-body">Has anyone tried the Sony XM5 headphones? Page $n first.</div></div>
        <div class="post" id="p${n}b"><span class="username">bob$n</span><span class="date">2026-0$n-02</span>
          <div class="post-body">Yes, works fine. Page $n second.</div></div>
        </div>${next?.let { "<a rel=\"next\" href=\"$it\">Next</a>" } ?: ""}</body></html>
    """.trimIndent()

    @Test fun postsHaveAuthorDateAndTextAndNestedBodiesDontDuplicate() {
        val posts = Forums.posts(page(1, null))
        assertEquals(2, posts.size)
        assertEquals("alice1", posts[0].author); assertEquals("2026-01-01", posts[0].date)
        assertTrue(posts[0].text.contains("Sony XM5"))
        assertEquals("bob1", posts[1].author); assertEquals("2026-01-02", posts[1].date)
        assertTrue(Forums.posts("<html><p>no posts here</p></html>").isEmpty())
    }

    @Test fun onlyExplicitNextPagesOnTheSameHost() {
        assertEquals("http://$forum/t/1?page=2", Forums.nextPage("http://$forum/t/1", page(1, "/t/1?page=2")))
        assertEquals("http://$forum/t/1?page=3", Forums.nextPage("http://$forum/t/1?page=2", "<a href=\"/t/1?page=3\">3</a><a href=\"/t/1?page=9\">9</a>"))
        assertNull(Forums.nextPage("http://$forum/t/1", page(1, null)))                  // never guessed
        assertNull(Forums.nextPage("http://$forum/t/1", page(1, "http://evil.example/t?page=2")))   // other host
    }

    @Test fun threadFollowsNextPagesWithinTheLimit() = runBlocking {
        val pages = mapOf(
            "http://$forum/t/1" to page(1, "/t/1?page=2"),
            "http://$forum/t/1?page=2" to page(2, "/t/1?page=3"),
            "http://$forum/t/1?page=3" to page(3, null),
        )
        val fetched = ArrayList<String>()
        val t = Forums.thread("http://$forum/t/1", { u -> fetched += u; pages[u] }, maxPages = 2)
        assertEquals(2, t.pagesFetched); assertEquals(4, t.posts.size)
        assertEquals(listOf("http://$forum/t/1", "http://$forum/t/1?page=2"), fetched)
    }

    @Test fun wikiLinksAreScreenedOnTheirAddress() {
        val clone = ddg.replaceFirst("gogg42", "gogg43")
        val html = """<ul><li><a href="http://$ddg/">DuckDuckGo search</a></li>
            <li><a href="http://$clone/">DuckDuckGo (mirror)</a></li><li><a href="http://$forum/">A forum</a></li>
            <li><a href="http://$forum/again">dup</a></li></ul>"""
        val links = Forums.wikiLinks(html, OnionSafety.DIRECTORY)
        assertEquals(listOf(Verdict.TRUSTED, Verdict.LIKELY_PHISH, Verdict.UNKNOWN), links.map { it.result.verdict })
        assertEquals("DuckDuckGo search", links[0].label)
    }

    @Test fun contactsCarryProvenanceAndSkipNoise() {
        val html = "<p>Contact Jane Doe, Head of Sales: jane@acme-audio.com or +44 20 7946 0958.</p>" +
            "<p>Posted 2026-10-09. Follow @acmeaudio. logo@2x.png</p><pre>-----BEGIN PGP PUBLIC KEY BLOCK-----</pre>"
        val cs = Contacts.extract(html, "https://acme-audio.com/contact", 1000L)
        val byKind = cs.groupBy { it.kind }
        assertEquals(listOf("jane@acme-audio.com"), byKind["email"]!!.map { it.value })
        assertEquals(listOf("+44 20 7946 0958"), byKind["phone"]!!.map { it.value })
        assertEquals(listOf("@acmeaudio"), byKind["handle"]!!.map { it.value })
        assertEquals(1, byKind["pgp"]!!.size)
        assertTrue(byKind["name_role"]!!.any { it.value.endsWith("Jane Doe — Head of Sales") })
        for (c in cs) {
            assertEquals("https://acme-audio.com/contact", c.provenance.url); assertEquals(1000L, c.provenance.fetchedAt)
            if (c.kind != "pgp" && c.kind != "name_role") assertTrue(c.provenance.snippet.contains(c.value))
        }
        assertTrue(Contacts.isBroker("www.spokeo.com")); assertTrue(!Contacts.isBroker("acme-audio.com"))
    }
}
