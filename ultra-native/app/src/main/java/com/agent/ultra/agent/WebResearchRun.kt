package com.agent.ultra.agent

import com.agent.ultra.net.OnionBlockingDns
import com.agent.ultra.net.TorProxy
import com.agent.ultra.net.cancelOnStop
import com.agent.ultra.research.Discovery
import com.agent.ultra.research.Forums
import com.agent.ultra.research.Investigation
import com.agent.ultra.research.ProductCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What research may do with .onion links, from Settings: [enabled] = "Onion research via Tor",
 * [deep] = "Deep research over Tor" (both off by default; offline only turns both off). The onion
 * fetcher is the Tor proxy's and nothing else's. [dir] is app-private storage for the link pool,
 * the red-flag log and investigation cases.
 *
 * Scope (owner, 2026-10-09): reading hidden wikis and forums through Tor with scam screening, and
 * product/company verification. OUT of scope: marketplace navigation, vendor/seller vetting, any
 * purchasing.
 */
class OnionAccess(
    val enabled: Boolean,
    val deep: Boolean,
    val known: Map<String, String>,
    private val probe: () -> Boolean,
    private val fetcher: suspend (String) -> String?,
) {
    fun available(): Boolean = enabled && probe()
    suspend fun fetch(url: String): String? = if (enabled) fetcher(url) else null

    companion object {
        val OFF = OnionAccess(false, false, OnionSafety.DIRECTORY, { false }, { null })

        /** From the owner's settings; [OFF] when the setting is off or offline only is on. */
        fun of(ctx: android.content.Context): OnionAccess {
            if (!com.agent.ultra.ui.UltraPrefs.onionResearch(ctx) || NetPolicy.offlineOnly(ctx)) return OFF
            // Resolved per use: the in-app Tor may start (or stop) after this was made.
            return OnionAccess(true, com.agent.ultra.ui.UltraPrefs.deepResearch(ctx),
                OnionSafety.knownGood(com.agent.ultra.ui.UltraPrefs.onionKnownGood(ctx)),
                { TorProxy.current().isAvailable() }, { u -> TorProxy.current().get(u) })
        }
    }
}

/** [WebResearch] over the network: the planned queries in parallel, then the best pages read. */
object WebResearchRun {
    // Never resolves an onion name (a redirect to one fails here instead of leaking to the resolver).
    private val http = OkHttpClient.Builder().dns(OnionBlockingDns())
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).followRedirects(true).build()
    private const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"
    /** Pages read in full for the brief. */
    private const val PAGES = 3
    /** The whole search, queries and pages: past this the brain answers with what's in. */
    const val BUDGET_MS = 20_000L
    /** Opening one page on screen and reading it. */
    const val SCREEN_MS = 15_000L
    /** One onion page over Tor (circuits to onion services are slow). */
    const val ONION_MS = 25_000L
    /** Onion links read per run. */
    private const val ONIONS = 3
    /** The deep-research crawl: pages and time, always enforced. */
    const val DEEP_PAGES = 12
    const val DEEP_MS = 90_000L

    /**
     * One page's HTML, or null when it failed. Stop (or the [BUDGET_MS] limit) cancels the request.
     * A .onion URL is refused here — it is never fetched with the normal client.
     */
    internal suspend fun get(url: String): String? = rawGet(url)?.second

    /** [get], plus the raw HTML is kept by the caller. */
    private suspend fun rawGet(url: String): Pair<String, String>? = try {
        if (OnionSafety.onionHost(url) != null) null else {
            val call = http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en").build())
            cancelOnStop(call) { call.execute().use { r ->
                if (!r.isSuccessful) null else r.body?.let { b -> if ((b.contentType()?.subtype ?: "html").contains("html")) url to b.string().take(600_000) else null }
            } }
        }
    } catch (e: CancellationException) { throw e   // Stop, not a failed page
    } catch (_: Exception) { null }

    /**
     * One DuckDuckGo HTML search, sent the way the maintained scrapers send it (ddgs, duckduckgo_search
     * and SearXNG all POST the form to html.duckduckgo.com/html with this Referer): a plain GET is the
     * request most likely to get the bot check. Null when blocked ([WebResearch.ddgBlocked]) or failed.
     */
    private suspend fun search(q: String): String? = try {
        val form = okhttp3.FormBody.Builder().add("q", q).add("b", "").build()
        val req = Request.Builder().url("https://html.duckduckgo.com/html/").post(form)
            .header("User-Agent", UA).header("Accept-Language", "en")
            .header("Referer", "https://html.duckduckgo.com/")
            .header("Sec-Fetch-Dest", "document").header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin").header("Sec-Fetch-User", "?1").build()
        val call = http.newCall(req)
        cancelOnStop(call) { call.execute().use { r ->
            val html = r.body?.string().orEmpty()
            if (!r.isSuccessful || WebResearch.ddgBlocked(r.code, html)) {
                android.util.Log.w("UltraWeb", "search blocked or failed: HTTP ${r.code}")
                null
            } else html
        } }
    } catch (e: CancellationException) { throw e   // Stop, not a failed search
    } catch (_: Exception) { null }

    /** One query's results (no pages read), onion results left out. For the investigation's pivots. */
    suspend fun hits(q: String): List<WebResearch.Hit> =
        search(q)?.let { WebResearch.parseDdg(it) }.orEmpty().filter { OnionSafety.onionHost(it.url) == null }

    /**
     * The onion links of one run under the onion policy: read only through the Tor proxy, only with
     * the setting on and the proxy up; otherwise left out with one line. Each is classified before
     * and after reading; LIKELY_PHISH / SCAM become red-flag lines, never sources.
     */
    internal suspend fun onionStep(urls: List<String>, access: OnionAccess): WebResearch.OnionReport {
        if (urls.isEmpty()) return WebResearch.OnionReport(known = access.known)
        val route = WebResearch.onionRoute(false, access.enabled, access.available())
        WebResearch.onionSkipLine(route, urls.size)?.let { return WebResearch.OnionReport(lines = listOf(it)) }
        val results = coroutineScope {
            urls.take(ONIONS).map { u -> async { u to readOnion(u, access) } }.awaitAll()
        }
        val sources = results.mapNotNull { (_, r) -> r.first }
        val lines = results.mapNotNull { (_, r) -> r.second }
        return WebResearch.OnionReport(sources, lines, known = access.known)
    }

    private suspend fun readOnion(url: String, access: OnionAccess): Pair<WebResearch.OnionSource?, String?> {
        val pre = OnionSafety.classify(url, null, access.known) ?: return null to null
        if (pre.verdict == OnionSafety.Verdict.LIKELY_PHISH) return null to WebResearch.redFlagLine(pre)
        if (!OnionSafety.isV3(pre.address)) return null to "Note: ${OnionSafety.short(pre.address)} isn't a valid onion address (wrong length or checksum) — skipped."
        val html = withTimeoutOrNull(ONION_MS) { access.fetch(url) }
            ?: return null to "Note: couldn't load ${OnionSafety.short(pre.address)} over Tor — skipped."
        val r = OnionSafety.classify(url, html, access.known) ?: return null to null
        if (r.verdict == OnionSafety.Verdict.SCAM || r.verdict == OnionSafety.Verdict.LIKELY_PHISH) return null to WebResearch.redFlagLine(r)
        val label = "onion via Tor · " + (if (r.verdict == OnionSafety.Verdict.TRUSTED) "known-good address (${r.name})"
            else "unlisted address — not verified") +
            (if (OnionSafety.Signal.LOGIN_FORM.words in r.reasons) " · has a login form" else "")
        return WebResearch.OnionSource(url, OnionSafety.title(html) ?: OnionSafety.short(r.address), label, WebResearch.pageText(html)) to null
    }

    /** One research run's parts, for the brief and for the bigger runs built on it. */
    class Outcome(
        val kind: WebResearch.Kind,
        val queries: List<String>,
        val ranked: List<WebResearch.Ranked>,
        val pages: Map<String, String>,
        /** The raw HTML of the clearnet pages read (url → HTML). */
        val raw: Map<String, String>,
        val onion: WebResearch.OnionReport,
        val fetchedAt: Long,
    ) {
        fun brief(request: String, max: Int = 3000, rules: Boolean = true) =
            WebResearch.brief(request, kind, queries, ranked, pages, max = max, onion = onion, rules = rules)
        val empty: Boolean get() = ranked.isEmpty() && onion.isEmpty
    }

    /** The research for [request]: clearnet as before; its onion links under [onionStep]. */
    internal suspend fun research(request: String, onScreen: (suspend (String) -> String?)?, onStatus: (String) -> Unit,
                                  onion: OnionAccess): Outcome = withContext(Dispatchers.IO) {
        val kind = WebResearch.kind(request)
        val queries = WebResearch.plan(request, java.time.Year.now().value)
        val asked = OnionSafety.urlsIn(request)
        var pages: Map<String, String> = emptyMap()
        var raw: Map<String, String> = emptyMap()
        var ranked: List<WebResearch.Ranked> = emptyList()
        var foundOnions: List<String> = emptyList()
        val report = coroutineScope {
            val askedJob = async { onionStep(asked, onion) }
            withTimeoutOrNull(BUDGET_MS) {
                onStatus("searching ${queries.size} ways")
                val hits = coroutineScope {
                    queries.map { q -> async { search(q)?.let { WebResearch.parseDdg(it) }.orEmpty() } }.awaitAll()
                }
                val (onionHits, clear) = WebResearch.rank(hits, kind).partition { OnionSafety.onionHost(it.hit.url) != null }
                ranked = clear
                foundOnions = onionHits.map { it.hit.url }
                onStatus("reading ${minOf(PAGES, ranked.size)} pages")
                raw = coroutineScope {
                    ranked.take(PAGES).map { r -> async { rawGet(r.hit.url) } }.awaitAll()
                }.filterNotNull().toMap()
                pages = raw.mapNotNull { (u, h) -> WebResearch.pageText(h).takeIf { it.length > 80 }?.let { u to it } }.toMap()
            }
            val askedAddr = asked.mapNotNull { OnionSafety.onionHost(it)?.let(OnionSafety::address) }.toSet()
            // Onion links that turned up in the results: same policy, after the clearnet budget.
            val extra = foundOnions.filter { OnionSafety.onionHost(it)?.let(OnionSafety::address) !in askedAddr }.take(2)
            askedJob.await() + onionStep(extra, onion)
        }
        // The browser on screen never opens an onion link (ranked holds clearnet results only).
        if (pages.isEmpty() && onScreen != null) ranked.firstOrNull()?.let { best ->
            onStatus("opening ${best.hit.domain} on screen")
            val seen = withTimeoutOrNull(SCREEN_MS) { onScreen(best.hit.url) }?.takeIf { it.length > 80 }
            if (seen != null) pages = mapOf(best.hit.url to seen.take(1400))
        }
        // Which onion addresses the clearnet pages name (only with the setting on: nothing onion happens off).
        val claims = if (!onion.enabled) emptyMap() else ranked.take(8).associate { r ->
            r.hit.url to (OnionSafety.addressesIn(raw[r.hit.url].orEmpty() + " " + r.hit.snippet + " " + r.hit.title))
        }.filterValues { it.isNotEmpty() }
        android.util.Log.i("UltraWeb", "research kind=$kind queries=${queries.size} results=${ranked.size} pages=${pages.size} onions=${report.sources.size}")
        Outcome(kind, queries, ranked, pages, raw, report.copy(claims = claims, known = onion.known), System.currentTimeMillis())
    }

    /**
     * The research brief for [request], or null when nothing came back. [onScreen] reads a page in
     * the browser on screen (its text after the page's scripts ran), for when none of the best
     * pages gave any text over plain HTTP — sites built in JavaScript. Outside [BUDGET_MS].
     *
     * Explicit phrases start the bigger runs ([WebResearch.mode]): "deep research …", "investigate …",
     * "verify this product …". [dir] is app-private storage for the link pool and cases.
     */
    suspend fun run(request: String, onScreen: (suspend (String) -> String?)? = null, onStatus: (String) -> Unit = {},
                    onion: OnionAccess = OnionAccess.OFF, dir: File? = null): String? = when (WebResearch.mode(request)) {
        WebResearch.Mode.NORMAL -> research(request, onScreen, onStatus, onion).let { if (it.empty) null else it.brief(request) }
        WebResearch.Mode.VERIFY_PRODUCT -> verifyProduct(request, onScreen, onStatus, onion)
        WebResearch.Mode.DEEP -> deepResearch(request, onScreen, onStatus, onion, dir)
        WebResearch.Mode.INVESTIGATE -> investigate(request, onScreen, onStatus, onion, dir)
    }

    private suspend fun verifyProduct(request: String, onScreen: (suspend (String) -> String?)?, onStatus: (String) -> Unit, onion: OnionAccess): String? {
        val urls = OnionSafety.urlsIn(request)
        // The onion links go to the onion leg only: never into clearnet search queries, never read twice.
        val product = stripUrls(WebResearch.target(request), urls).ifBlank { return null }
        val o = research(product, onScreen, onStatus, onion)
        val now = java.time.Year.now().value
        val seen = WebResearch.priceEvidence(o.ranked.take(8).mapIndexed { i, r -> Triple(o.onion.sources.size + i + 1, r.hit, o.pages[r.hit.url]) }, now)
        val route = WebResearch.onionRoute(false, onion.enabled, onion.available())
        val leg = if (urls.isEmpty()) null
            else if (route != WebResearch.OnionRoute.TOR) ProductCheck.OnionLeg(emptyList(), emptyList(), listOfNotNull(WebResearch.onionSkipLine(route, urls.size)))
            else withContext(Dispatchers.IO) {
                onStatus("reading onion sources through Tor")
                ProductCheck.onionLeg(product, urls, { u -> withTimeoutOrNull(ONION_MS) { onion.fetch(u) } }, onion.known, now)
            }
        val company = ProductCheck.companyCheck(product, o.raw, urls.firstOrNull(), onion.known)
        val table = ProductCheck.compare(product, ProductCheck.clearRows(seen) + (leg?.let(ProductCheck::onionRows) ?: emptyList()), leg, company)
        // One set of rules: the table's (its labelled order and its question); the brief gives the numbered sources only.
        return (if (o.empty) "" else o.brief(product, 1500, rules = false) + "\n") +
            table.let { if (it.length <= 1500) it else it.take(1300) + "…\nEnd with: " + ProductCheck.QUESTION }
    }

    /** [text] without the onion [urls] (or any other URL): what goes into clearnet search queries. */
    internal fun stripUrls(text: String, urls: List<String>): String =
        urls.fold(text) { t, u -> t.replace(u, " ").replace(u.removePrefix("http://"), " ") }
            .replace(Regex("(?i)https?://\\S*"), " ").replace(Regex("\\s+"), " ").trim()

    private suspend fun deepPass(query: String, given: List<String>, onion: OnionAccess, dir: File?, onStatus: (String) -> Unit): Pair<String, Investigation.Tor> =
        withContext(Dispatchers.IO) {
            val notes = ArrayList<String>()
            val route = WebResearch.onionRoute(false, onion.enabled, onion.available())
            if (!onion.deep || route != WebResearch.OnionRoute.TOR) {
                val why = when {
                    !onion.enabled -> "Deep research needs Settings → \"Onion research via Tor\" on — not run; nothing onion was fetched."
                    !onion.deep -> "Deep research over Tor is off (Settings) — not run; nothing onion was fetched."
                    else -> "Tor isn't running (start Orbot) — deep research stopped; nothing was fetched over the normal connection instead."
                }
                return@withContext why to Investigation.Tor(null, null, why)
            }
            onStatus("deep research over Tor")
            // One deadline for the whole pass (start pages, crawl, threads): DEEP_MS plus at most one fetch.
            val deadline = System.currentTimeMillis() + DEEP_MS
            val fetch: suspend (String) -> String? = { u ->
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) null else withTimeoutOrNull(minOf(ONION_MS, left)) { onion.fetch(u) }
            }
            val discovery = Discovery(fetch, onion::available, onion.known)
            val firstPages = HashMap<String, String>()
            val threads = ArrayList<String>()
            val wikis = ArrayList<Pair<String, List<Forums.WikiLink>>>()
            val bad = HashSet<String>()
            for (u in given.take(ONIONS)) {
                val pre = OnionSafety.classify(u, null, onion.known) ?: continue
                if (pre.verdict == OnionSafety.Verdict.LIKELY_PHISH) { notes += WebResearch.redFlagLine(pre); bad += pre.address; continue }
                val html = fetch(u)
                if (html == null) { notes += "Note: couldn't load ${OnionSafety.short(pre.address)} over Tor."; continue }
                val r = OnionSafety.classify(u, html, onion.known) ?: continue
                if (r.verdict == OnionSafety.Verdict.SCAM || r.verdict == OnionSafety.Verdict.LIKELY_PHISH) { notes += WebResearch.redFlagLine(r); bad += r.address; continue }
                if (Forums.posts(html).size >= 2) { threads += u; firstPages[u] = html }
                else Forums.wikiLinks(html, onion.known).takeIf { it.size >= 3 }?.let { wikis += u to it }
            }
            // Start pages: the known-good set (directory + the owner's list) and the owner's own
            // pages that weren't flagged. Flagged pages are never followed.
            val seeds = onion.known.keys.map { "http://$it/" } + given.filter { u ->
                OnionSafety.onionHost(u)?.let(OnionSafety::address) !in bad
            }
            val crawl = discovery.crawl(seeds, maxDepth = 1, maxPages = DEEP_PAGES, deadline = deadline)
            val read = if (threads.isEmpty()) null else discovery.readThreads(query, threads, firstPages = firstPages)
            if (dir != null) {
                runCatching { Discovery.savePool(dir, crawl.pool) }
                runCatching { Discovery.logFlags(dir, crawl.redFlags.map { it } + wikis.flatMap { (idx, ls) ->
                    ls.filter { it.result.verdict == OnionSafety.Verdict.LIKELY_PHISH }.map { Discovery.Flag(it.result.address, it.result.verdict, it.result.reasons, idx) }
                }, System.currentTimeMillis()) }
            }
            Discovery.render(crawl, read, wikis, notes) to Investigation.Tor(crawl, read, null)
        }

    private suspend fun deepResearch(request: String, onScreen: (suspend (String) -> String?)?, onStatus: (String) -> Unit,
                                     onion: OnionAccess, dir: File?): String? {
        val given = OnionSafety.urlsIn(request)
        val clearTopic = stripUrls(WebResearch.target(request), given)
        val (deep, _) = deepPass(clearTopic, given, onion, dir, onStatus)
        // The ordinary research for the topic (its onion links already went through the deep pass).
        val o = if (clearTopic.length < 3) null else research(clearTopic, onScreen, onStatus, onion)
        val brief = o?.takeIf { !it.empty }?.brief(request, 1500)
        return listOfNotNull(brief, deep.take(1300), WebResearch.NEXT_QUESTION).joinToString("\n\n")
    }

    private suspend fun investigate(request: String, onScreen: (suspend (String) -> String?)?, onStatus: (String) -> Unit,
                                    onion: OnionAccess, dir: File?): String? {
        val target = WebResearch.target(request).ifBlank { return null }
        val given = OnionSafety.urlsIn(request)
        val clearTarget = stripUrls(target, given).ifBlank { target }
        val torLeg: (suspend () -> Investigation.Tor)? = if (!onion.enabled || !onion.deep) null else ({ deepPass(clearTarget, given, onion, dir, onStatus).second })
        val case = Investigation.run(clearTarget,
            clearnet = { t ->
                val o = research(t, onScreen, onStatus, OnionAccess.OFF)
                Investigation.Clearnet(o.ranked.take(8).map { it.hit to o.pages[it.hit.url] }, o.raw, o.fetchedAt)
            },
            search = { q -> onStatus("checking: $q"); withTimeoutOrNull(BUDGET_MS) { hits(q) }.orEmpty() },
            tor = torLeg)
        if (dir != null) runCatching { case.save(dir) }
        return Investigation.summary(case)
    }
}
