package com.agent.ultra.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** [WebResearch] over the network: the planned queries in parallel, then the best pages read. */
object WebResearchRun {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).followRedirects(true).build()
    private const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"
    /** Pages read in full for the brief. */
    private const val PAGES = 3
    /** The whole search, queries and pages: past this the brain answers with what's in. */
    const val BUDGET_MS = 20_000L

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en").build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.let { b -> if ((b.contentType()?.subtype ?: "html").contains("html")) b.string().take(600_000) else null }
        }
    }.getOrNull()

    /** The research brief for [request], or null when nothing came back. */
    suspend fun run(request: String, onStatus: (String) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        val kind = WebResearch.kind(request)
        val queries = WebResearch.plan(request, java.time.Year.now().value)
        var pages: Map<String, String> = emptyMap()
        var ranked: List<WebResearch.Ranked> = emptyList()
        withTimeoutOrNull(BUDGET_MS) {
            onStatus("searching ${queries.size} ways")
            val hits = coroutineScope {
                queries.map { q -> async { get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8"))
                    ?.let { WebResearch.parseDdg(it) }.orEmpty() } }.awaitAll()
            }
            ranked = WebResearch.rank(hits, kind)
            onStatus("reading ${minOf(PAGES, ranked.size)} pages")
            pages = coroutineScope {
                ranked.take(PAGES).map { r -> async { r.hit.url to get(r.hit.url)?.let { WebResearch.pageText(it) } } }.awaitAll()
            }.mapNotNull { (u, t) -> t?.takeIf { it.length > 80 }?.let { u to it } }.toMap()
        }
        android.util.Log.i("UltraWeb", "research kind=$kind queries=${queries.size} results=${ranked.size} pages=${pages.size}")
        if (ranked.isEmpty()) null else WebResearch.brief(request, kind, queries, ranked, pages)
    }
}
