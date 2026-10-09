package com.agent.ultra.research

import com.agent.ultra.agent.OnionSafety.Verdict
import com.agent.ultra.agent.WebResearch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class InvestigationTest {

    private val ddg = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
    private val clone = ddg.replaceFirst("gogg42", "gogg43")
    private val good = "ceirceirceirceirceirceirceirceirceirceirceirceircei7l4yd.onion"

    private val clear = Investigation.Clearnet(
        listOf(
            WebResearch.Hit("Acme Audio — about", "https://acme-audio.com/about", "Acme Audio makes headphones") to "Founded 2019 in Leeds.",
            WebResearch.Hit("Acme on Spokeo", "https://www.spokeo.com/acme", "people search") to null,
        ),
        mapOf(
            "https://acme-audio.com/contact" to "<p>Press: press@acme-audio.com, +44 113 496 0000</p>",
            "https://www.spokeo.com/acme" to "<p>owner@private.example 555 123 4567</p>",
        ),
        fetchedAt = 1_000L)

    @Test fun legsMergeWithProvenanceBrokersSkippedAndTorOptIn() = runBlocking {
        val queries = ArrayList<String>()
        val case = Investigation.run("Acme Audio",
            clearnet = { clear },
            search = { q -> queries += q; listOf(WebResearch.Hit("Acme filing", "https://registry.example/acme.pdf", "annual report")) },
            tor = null, now = { 2_000L }, id = "case1")
        assertEquals(listOf("clearnet", "articles", "tor", "osint"), case.legs.map { it.name })
        assertEquals("skipped", case.legs[2].status)
        assertFalse(case.findings.any { "spokeo" in it.url })
        assertFalse(case.contacts.any { "spokeo" in it.provenance.url || it.value.contains("private.example") })
        assertTrue(case.findings.all { it.url.isNotBlank() && it.fetchedAt > 0 })
        val email = case.contacts.single { it.kind == "email" }
        assertEquals("press@acme-audio.com", email.value)
        assertEquals("https://acme-audio.com/contact", email.provenance.url); assertEquals(1_000L, email.provenance.fetchedAt)
        assertTrue(queries.contains("\"Acme Audio\" filetype:pdf"))
        assertTrue(queries.contains("site:acme-audio.com \"Acme Audio\""))
        assertTrue(queries.contains("\"press@acme-audio.com\""))
        assertTrue(queries.size <= 5)
        val pdf = case.findings.single { it.url == "https://registry.example/acme.pdf" }
        assertEquals(2_000L, pdf.fetchedAt); assertTrue(pdf.snippet.contains("annual report"))
        val s = Investigation.summary(case)
        assertTrue(s.trimEnd().endsWith(Investigation.QUESTION))
        assertTrue(s.contains("Contact email: press@acme-audio.com — from https://acme-audio.com/contact"))
    }

    @Test fun torLegAddsCleanLinksAndKeepsPhishOut() = runBlocking {
        val crawl = Discovery.Crawl(listOf(Discovery.PoolEntry(good, "wiki", "http://$ddg/", 5, Verdict.UNKNOWN, "")),
            listOf(Discovery.Flag(clone, Verdict.LIKELY_PHISH, listOf("1 character away"), "http://$ddg/")), emptyList(), 2, null)
        val mention = Discovery.Mention("onion", clone, Verdict.LIKELY_PHISH, listOf("lookalike"), "http://$good/t", "gus", "2026-01-01", 6)
        val threads = Discovery.ThreadResearch(emptyList(), listOf(mention), listOf("http://$good/t" to Forums.Post("gus", "2026-01-01", "try $clone")))
        val case = Investigation.run("privacy wikis", { null }, { emptyList() }, tor = { Investigation.Tor(crawl, threads, null) }, now = { 9L }, id = "c2")
        assertEquals(listOf(good), case.links.map { it.address })
        assertEquals(2, case.redFlags.size)
        assertFalse(case.toJson().toString().contains(clone))
        assertTrue(case.findings.single { it.realm == "tor" }.claim.contains("duckduck…zczad.onion"))
        case.addLink(CaseFile.Link(clone, "x", "y", Verdict.SCAM))              // merge-side re-check
        assertEquals(1, case.links.size)
    }

    @Test fun nothingSourcelessEntersACase() {
        val case = CaseFile("c3", "x", 0)
        try { case.addFinding("claim", "", "s", 1, "clearnet"); fail("sourceless finding accepted") } catch (_: IllegalArgumentException) {}
        try { case.addContact(Contacts.Contact("email", "a@b.com", Contacts.Provenance("", "", 1))); fail("sourceless contact accepted") } catch (_: IllegalArgumentException) {}
        assertTrue(case.findings.isEmpty() && case.contacts.isEmpty())
    }

    @Test fun outreachIsDraftsOnlyAndRefusesHolesAndUnsourcedContacts() {
        val c = Contacts.Contact("email", "press@acme-audio.com", Contacts.Provenance("https://acme-audio.com/contact", "Press: …", 1))
        val d = Outreach.draft(c, "About {query}", "Hello, I found {value} on {source_url}.", "Acme XM5")
        assertEquals("press@acme-audio.com", d.to); assertEquals("About Acme XM5", d.subject)
        assertEquals("Hello, I found press@acme-audio.com on https://acme-audio.com/contact.", d.body)
        assertEquals("draft — never sent by Jarvis", d.status)
        try { Outreach.draft(c, "Hi {name}", "", ""); fail("unknown placeholder accepted") } catch (_: IllegalArgumentException) {}
        try { Outreach.draft(c.copy(provenance = Contacts.Provenance("", "", 0)), "s", "b", ""); fail("unsourced") } catch (_: IllegalArgumentException) {}
        // No send path: nothing in the module can send.
        for (k in listOf(Outreach::class.java, CaseFile::class.java, Investigation::class.java)) {
            assertFalse(k.declaredMethods.any { it.name.contains("send", ignoreCase = true) })
        }
    }

    @Test fun caseIsSavedPerCaseInItsDirectory() = runBlocking {
        val dir = Files.createTempDirectory("cases").toFile()
        try {
            val case = Investigation.run("Acme Audio", { clear }, { emptyList() }, null, { 3L }, id = "abc123")
            val f = case.save(dir)
            assertEquals("abc123.json", f.name); assertEquals("cases", f.parentFile.name)
            val j = JSONObject(f.readText())
            assertEquals("Acme Audio", j.getString("target"))
            assertTrue(j.getJSONArray("contacts").getJSONObject(0).getJSONObject("provenance").getString("url").isNotBlank())
        } finally { dir.deleteRecursively() }
    }
}
