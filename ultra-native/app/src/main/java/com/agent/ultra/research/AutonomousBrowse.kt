package com.agent.ultra.research

import java.time.Instant

/**
 * Autonomous browsing, clearnet + Tor — port of the TESTED Python
 * reference (jarvis-v4/browse/autonomous.py).
 *
 * browse() navigates on its own within hard bounds:
 * - Clearnet leg: runs the search pipeline, fetches result pages,
 *   follows outbound links up to maxDepth. Bounded by maxPages —
 *   never an unbounded crawl.
 * - Onion leg: ONLY when torOptIn=true AND Tor is reachable. Onion
 *   URLs are classified BEFORE fetching; likely_phish/scam are
 *   blocked and never followed. Unlisted onions are fetched through
 *   Tor and their pages screened BEFORE the content is used.
 *
 * Onion URLs found during clearnet browsing are routed to the Tor
 * leg — never fetched over clearnet (that would leak the
 * destination to the local resolver). Realm routing is not
 * filtering.
 *
 * Every collected page carries provenance (URL, where it was
 * found, when, which realm). Blocked URLs are reported, never
 * silently dropped. Cancel checks run between fetches; a cancelled
 * run returns partial results with cancelled=true.
 *
 * Deliberately NOT in this module:
 * - No marketplace navigation: this module reads pages. It never
 *   enumerates sellers, crawls market catalogs, or performs
 *   marketplace actions of any kind.
 * - No onion exclusion: there is deliberately NO onion filtering
 *   here. The onion-marketplace exclusion lives ONLY in
 *   ShoppingListings (excludeOnion/isOnionUrl) and is called by the
 *   shopping flow. This module never imports or calls it — per the
 *   owner's design, the exclusion stays a SEPARATE function.
 *
 * SOURCE ONLY: not compiled in this sandbox. For Claude-in-repo
 * to drop into ultra-native/.../research/ and run under the JVM
 * suite. Pure logic — no Android deps.
 */
object AutonomousBrowse {

    data class Provenance(
        val foundOn: String,
        val fetchedAt: String,
        val realm: String,
    )

    data class Page(
        val url: String,
        val title: String,
        val text: String,
        val provenance: Provenance,
    )

    data class Blocked(
        val url: String,
        val verdict: String,
        val reasons: List<String>,
    )

    data class Result(
        val pages: List<Page>,
        val blocked: List<Blocked>,
        val notes: List<String>,
        val cancelled: Boolean,
        val pagesFetched: Int,
    )

    /** (query, shouldCancel) -> {"brief" -> String, ...} */
    fun interface SearchRunner {
        fun run(query: String, shouldCancel: (() -> Boolean)?): Map<String, Any?>
    }

    /** (query, shouldCancel) -> [{url, ...}] */
    fun interface Searcher {
        fun ddgSearch(query: String, shouldCancel: (() -> Boolean)?): List<Map<String, String>>
    }

    /** (url, shouldCancel) -> page text or null */
    fun interface Fetcher {
        fun fetch(url: String, shouldCancel: (() -> Boolean)?): String?
    }

    /** task -> planned queries */
    fun interface Planner {
        fun plan(task: String): List<String>
    }

    /** Duck-typed Tor client. Used ONLY when torOptIn=true. */
    interface TorClient {
        fun isAvailable(): Boolean
        fun classifyOnion(url: String): Map<String, Any?>
        fun transport(url: String): String
        val knownGood: Set<String>
    }

    /** (url, html, knownGood) -> {"scam" -> Boolean, "reasons" -> List<String>} */
    fun interface OnionScreener {
        fun screen(url: String, html: String, knownGood: Set<String>): Map<String, Any?>
    }

    private val BLOCK_VERDICTS = setOf("likely_phish", "scam")

    private val URL_RE = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    private val TITLE_RE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TAG_RE = Regex("<[^>]+>")
    private val WS_RE = Regex("\\s+")
    private val ONION_URL_RE = Regex("https?://[^\\s\"'<>]*\\.onion[^\\s\"'<>]*", RegexOption.IGNORE_CASE)
    private val HOST_RE = Regex("^https?://([^/:?#]+)", RegexOption.IGNORE_CASE)

    private fun clean(t: String) = WS_RE.replace(TAG_RE.replace(t, " "), " ").trim()

    private fun pageTitle(content: String, url: String): String {
        val m = TITLE_RE.find(content)
        if (m != null) {
            val t = clean(m.groupValues[1]).take(80)
            if (t.isNotEmpty()) return t
        }
        return clean(content).split(". ").firstOrNull()?.take(80)?.ifEmpty { url } ?: url
    }

    private fun outboundUrls(text: String): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (m in URL_RE.findAll(text)) {
            val u = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')')
            if (u.isNotEmpty() && seen.add(u)) out.add(u)
        }
        return out
    }

    /**
     * Realm routing helper: the .onion host of a URL, or null.
     * Used ONLY to route onion URLs to the Tor transport — never
     * to drop or exclude them.
     */
    fun onionHost(url: String): String? {
        val host = HOST_RE.find(url)?.groupValues?.get(1)?.lowercase() ?: return null
        return if (host.endsWith(".onion")) host else null
    }

    private fun findOnionUrls(html: String): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (m in ONION_URL_RE.findAll(html)) {
            val u = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')')
            if (u.isNotEmpty() && seen.add(u)) out.add(u)
        }
        return out
    }

    private fun stamp() = Instant.now().toString()

    private fun mkPage(url: String, title: String, text: String, foundOn: String, realm: String) =
        Page(url, title, text.take(4000),
            Provenance(foundOn, stamp(), realm))

    /**
     * Browse autonomously for [task].
     *
     * @param tor used ONLY when [torOptIn] is true.
     * @param maxPages total fetch bound across BOTH legs.
     * @param maxDepth link-hops out from a seed page.
     */
    fun browse(
        task: String,
        searchRunner: SearchRunner,
        searcher: Searcher,
        fetcher: Fetcher,
        planner: Planner,
        onionScreener: OnionScreener,
        tor: TorClient? = null,
        torOptIn: Boolean = false,
        maxPages: Int = 20,
        maxDepth: Int = 3,
        shouldCancel: (() -> Boolean)? = null,
    ): Result {
        val pages = mutableListOf<Page>()
        val blocked = mutableListOf<Blocked>()
        val notes = mutableListOf<String>()
        var cancelled = false
        var fetched = 0
        val visited = mutableSetOf<String>()
        fun withinBudget() = fetched < maxPages
        fun isCancelled() = shouldCancel?.invoke() == true

        // ------------------------------------------------- clearnet leg
        if (isCancelled()) {
            return Result(pages, blocked, notes, true, 0)
        }
        val brief: String = try {
            (searchRunner.run(task, shouldCancel)["brief"] as? String) ?: ""
        } catch (e: Exception) {
            notes.add("clearnet search failed (${e.javaClass.simpleName}); continuing with direct seeds")
            ""
        }

        val seeds = mutableListOf<String>()
        try {
            for (pq in planner.plan(task).take(2)) {
                if (isCancelled()) break
                for (hit in searcher.ddgSearch(pq, shouldCancel)) {
                    val u = (hit["url"] ?: "").trim()
                    if (u.isNotEmpty() && u !in seeds) seeds.add(u)
                }
            }
        } catch (e: Exception) {
            notes.add("seed search failed (${e.javaClass.simpleName})")
        }

        val onionFrontier = mutableListOf<String>() // onion URLs routed to the Tor leg
        val queue = ArrayDeque<Triple<String, Int, String>>()
        seeds.forEach { queue.add(Triple(it, 0, "search results")) }
        while (queue.isNotEmpty() && withinBudget()) {
            if (isCancelled()) { cancelled = true; break }
            val (url, depth, foundOn) = queue.removeFirst()
            if (url in visited) continue
            if (onionHost(url) != null) {
                // Realm routing, NOT filtering: onion URLs go to the
                // Tor leg; they are never fetched over clearnet.
                if (url !in onionFrontier) onionFrontier.add(url)
                visited.add(url)
                continue
            }
            visited.add(url)
            val text: String? = try {
                fetcher.fetch(url, shouldCancel)
            } catch (e: Exception) {
                blocked.add(Blocked(url, "fetch_failed", listOf((e.message ?: "").take(120))))
                null
            }
            if (text.isNullOrEmpty()) continue
            fetched++
            pages.add(mkPage(url, pageTitle(text, url), text, foundOn, "clearnet"))
            if (depth < maxDepth) {
                for (ou in outboundUrls(text)) {
                    if (ou !in visited && withinBudget()) queue.add(Triple(ou, depth + 1, url))
                }
            }
        }

        // ---------------------------------------------------- onion leg
        if (!torOptIn) {
            notes.add("onion leg skipped: Tor is opt-in (not requested)")
        } else if (tor == null || !tor.isAvailable()) {
            notes.add("onion leg skipped: Tor unavailable — denied cleanly, no clearnet fallback")
            if (onionFrontier.isNotEmpty()) {
                blocked.add(Blocked(
                    "${onionFrontier.size} onion URL(s)",
                    "tor_unavailable",
                    listOf("Tor proxy not reachable; onion URLs from clearnet pages were routed here, not fetched over clearnet"),
                ))
            }
        } else {
            val known = try { tor.knownGood } catch (e: Exception) { emptySet() }
            val oqueue = ArrayDeque<Triple<String, Int, String>>()
            onionFrontier.forEach { oqueue.add(Triple(it, 0, "clearnet page")) }
            while (oqueue.isNotEmpty() && withinBudget()) {
                if (isCancelled()) { cancelled = true; break }
                val (url, depth, foundOn) = oqueue.removeFirst()
                val host = onionHost(url) ?: continue
                if (host in visited) continue
                visited.add(host)
                // Classify BEFORE fetching: phish/scam never fetched,
                // never followed.
                val verdict: Map<String, Any?> = try {
                    tor.classifyOnion(url)
                } catch (e: Exception) {
                    blocked.add(Blocked(url, "classify_failed", listOf((e.message ?: "").take(120))))
                    continue
                }
                val v = verdict["verdict"] as? String ?: "unknown"
                if (v in BLOCK_VERDICTS) {
                    @Suppress("UNCHECKED_CAST")
                    val reasons = verdict["reasons"] as? List<String> ?: emptyList()
                    blocked.add(Blocked(url, v, reasons))
                    continue
                }
                val html: String = try {
                    tor.transport(url)
                } catch (e: Exception) {
                    blocked.add(Blocked(url, "fetch_failed", listOf((e.message ?: "").take(120))))
                    continue
                }
                fetched++
                if (v == "unknown") {
                    // Unlisted onion: screen the page BEFORE its
                    // content is used.
                    val screen = onionScreener.screen(url, html, known)
                    if (screen["scam"] == true) {
                        @Suppress("UNCHECKED_CAST")
                        val reasons = screen["reasons"] as? List<String> ?: emptyList()
                        blocked.add(Blocked(url, "scam", reasons))
                        continue
                    }
                }
                pages.add(mkPage(url, pageTitle(html, url), html, foundOn, "onion"))
                if (depth < maxDepth) {
                    for (ou in findOnionUrls(html)) {
                        val oh = onionHost(ou) ?: continue
                        if (oh !in visited && withinBudget()) oqueue.add(Triple(ou, depth + 1, url))
                    }
                }
            }
        }

        if (brief.isNotEmpty() && !isCancelled()) {
            notes.add("search brief recorded (${brief.length} chars; not a fetched page)")
        }

        return Result(pages, blocked, notes, cancelled, fetched)
    }
}
