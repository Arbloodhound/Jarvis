package com.agent.ultra.research

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browse brief assembly: pages with provenance, blocked URLs repeated, notes carried. Pure —
 * the live transports/Tor are not exercised here.
 */
class BrowseRunTest {

    private fun result() = AutonomousBrowse.Result(
        pages = listOf(
            AutonomousBrowse.Page(
                "https://example.com/a", "A Title", "Some body text about the task.",
                AutonomousBrowse.Provenance("search results", "2026-10-15T00:00:00Z", "clearnet"),
            ),
            AutonomousBrowse.Page(
                "http://" + "b".repeat(56) + ".onion/p", "Onion Page", "Onion body.",
                AutonomousBrowse.Provenance("clearnet page", "2026-10-15T00:00:01Z", "onion"),
            ),
        ),
        blocked = listOf(
            AutonomousBrowse.Blocked("http://" + "c".repeat(56) + ".onion/x", "likely_phish", listOf("clone of a known address")),
        ),
        notes = listOf("onion leg skipped: Tor is opt-in (not requested)"),
        cancelled = false,
        pagesFetched = 2,
    )

    @Test fun renderShowsProvenanceAndRepeatsBlocked() {
        val text = BrowseRun.render("find background on acme", result())
        assertTrue(text.contains("[clearnet]"))
        assertTrue(text.contains("[onion]"))
        assertTrue(text.contains("found on search results"))
        assertTrue(text.contains("Blocked:"))
        assertTrue(text.contains("likely_phish"))
        assertTrue(text.contains("clone of a known address"))
        assertTrue(text.contains("Note: onion leg skipped"))
        assertTrue(text.contains("no onion page was ever fetched over the normal connection"))
    }

    @Test fun cancelledRunIsMarked() {
        val r = result().copy(cancelled = true)
        assertTrue(BrowseRun.render("t", r).contains("stopped early"))
    }

    @Test fun emptyResultRendersCleanly() {
        val r = AutonomousBrowse.Result(emptyList(), emptyList(), emptyList(), false, 0)
        val text = BrowseRun.render("t", r)
        assertTrue(text.contains("No pages were read"))
        assertFalse(text.contains("Blocked:"))
    }
}
