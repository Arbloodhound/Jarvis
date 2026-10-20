package com.agent.ultra.research

import com.agent.ultra.agent.OnionAccess
import com.agent.ultra.agent.OnionSafety
import com.agent.ultra.agent.WebResearch
import com.agent.ultra.agent.WebResearchRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The real transports for [AutonomousBrowse]: DuckDuckGo search and clearnet fetch from
 * [WebResearchRun], the onion leg from [OnionAccess] (the Tor proxy) with [OnionSafety] doing the
 * classify-before-fetch and the screening. The hard constraints are [AutonomousBrowse]'s own and
 * stay there — no exclusion logic, no marketplace navigation, onion leg only when Tor is opted in
 * AND reachable, classify before fetch, blocked never fetched, Tor-down is a clean deny with no
 * clearnet fallback. This file only hands it the transports; it adds no onion filtering of its own.
 *
 * [render] is pure (JVM-tested); [run] is the thin wiring.
 */
object BrowseRun {

    /** Fetch bounds across both legs — small on a phone. */
    const val BROWSE_PAGES = 12
    const val BROWSE_DEPTH = 2

    /** The browse result for the brain: pages with provenance, blocked URLs repeated, notes. Pure. */
    fun render(task: String, r: AutonomousBrowse.Result): String = buildString {
        append("Autonomous browse — ").append(task).append(".\n")
        append("${r.pagesFetched} page(s) read")
        if (r.cancelled) append(" (stopped early)")
        append(".\n")
        if (r.pages.isEmpty()) append("No pages were read.\n")
        r.pages.take(12).forEach { p ->
            append("- [${p.provenance.realm}] ${p.title.ifBlank { p.url }} (${p.url}; found on ${p.provenance.foundOn})\n")
            val t = p.text.trim()
            if (t.isNotEmpty()) append("  ").append(t.take(240)).append('\n')
        }
        r.blocked.take(8).forEach { b ->
            append("Blocked: ${b.url} — ${b.verdict}: ${b.reasons.joinToString("; ")}. Not fetched.\n")
        }
        r.notes.forEach { append("Note: ").append(it).append('\n') }
        append("Report only what these pages say, citing each page's URL; repeat every Blocked line; ")
            .append("no onion page was ever fetched over the normal connection.")
    }

    /** Browse autonomously for [task] over the real transports. */
    suspend fun run(task: String, onStatus: (String) -> Unit = {}, onion: OnionAccess = OnionAccess.OFF): String =
        withContext(Dispatchers.IO) {
            val job = coroutineContext[Job]
            val cancel = { job != null && !job.isActive }

            val searcher = AutonomousBrowse.Searcher { q, _ ->
                runBlocking { WebResearchRun.hits(q) }.map { mapOf("url" to it.url, "title" to it.title, "snippet" to it.snippet) }
            }
            val searchRunner = AutonomousBrowse.SearchRunner { q, _ ->
                val hits = runBlocking { WebResearchRun.hits(q) }
                mapOf("brief" to hits.take(5).joinToString("\n") { "${it.title} — ${it.snippet}" })
            }
            val fetcher = AutonomousBrowse.Fetcher { url, _ -> runBlocking { WebResearchRun.get(url) } }
            val planner = AutonomousBrowse.Planner { t -> WebResearch.plan(t, java.time.Year.now().value) }
            // Classify-before-fetch and the page screen both go through OnionSafety, with the owner's
            // known-good set — never any filtering, only the scam/phish verdict AutonomousBrowse acts on.
            val screener = AutonomousBrowse.OnionScreener { url, html, _ ->
                val res = OnionSafety.classify(url, html, onion.known)
                if (res != null && (res.verdict == OnionSafety.Verdict.SCAM || res.verdict == OnionSafety.Verdict.LIKELY_PHISH))
                    mapOf("scam" to true, "reasons" to res.reasons)
                else mapOf("scam" to false, "reasons" to emptyList<String>())
            }
            val tor = object : AutonomousBrowse.TorClient {
                override fun isAvailable(): Boolean = onion.available()
                override fun classifyOnion(url: String): Map<String, Any?> {
                    val res = OnionSafety.classify(url, null, onion.known)
                        ?: return mapOf("verdict" to "unknown", "reasons" to emptyList<String>())
                    return mapOf("verdict" to res.verdict.name.lowercase(), "reasons" to res.reasons)
                }
                override fun transport(url: String): String = runBlocking { onion.fetch(url) } ?: ""
                override val knownGood: Set<String> get() = onion.known.keys
            }

            onStatus("browsing")
            val result = AutonomousBrowse.browse(
                task, searchRunner, searcher, fetcher, planner, screener,
                tor = tor, torOptIn = onion.enabled,
                maxPages = BROWSE_PAGES, maxDepth = BROWSE_DEPTH, shouldCancel = cancel,
            )
            render(task, result)
        }
}
