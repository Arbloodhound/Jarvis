package com.agent.jarvis.research

import com.agent.jarvis.agent.OnionSafety
import java.net.URI

/**
 * Forum threads and wiki indexes the owner points at, read generically: posts (author, date,
 * text), explicit next-page links only, and the onion links a wiki index lists, each screened.
 *
 * ## Scope (owner, 2026-10-09)
 * Reading hidden wikis and forums through Tor with scam-vs-legit screening, and product/company
 * verification across clearnet and onion. Explicitly OUT of scope ("I don't need special skills for
 * the dark net", "I don't wanna find illegal items"): marketplace navigation, vendor or seller
 * reputation or vetting, and any purchasing. Nothing here knows about markets or vendors; it reads
 * posts and links.
 *
 * Heuristics with known limits: a thread is the most common repeated container whose class says
 * post / message / comment / entry / reply; JavaScript-rendered and login-walled boards extract
 * poorly or not at all. Nothing is ever invented: an empty page gives no posts. Pure, JVM-tested,
 * apart from [thread] which fetches through the function it's given (Tor only).
 */
object Forums {

    data class Post(val author: String, val date: String, val text: String)

    data class Thread(val url: String, val posts: List<Post>, val pagesFetched: Int)

    private val CONTAINER = Regex(
        "<(div|article|section|tr|li)\\b[^>]*class=\"[^\"]*?\\b(post|message|comment|entry|reply)[^\"]*\"[^>]*>",
        RegexOption.IGNORE_CASE)
    private val AUTHOR = Regex(
        "class=\"[^\"]*(author|username|user-name|poster)[^\"]*\"[^>]*>(?:\\s*<[^>]+>)*\\s*([^<]{1,80}?)\\s*<",
        RegexOption.IGNORE_CASE)
    private val DATE = Regex(
        "<time[^>]*datetime=\"([^\"]+)\"|<time[^>]*>([^<]{1,60})<|class=\"[^\"]*(date|time|posted)[^\"]*\"[^>]*>([^<]{1,60})<",
        RegexOption.IGNORE_CASE)
    private val NEXT = Regex(
        "<(?:a|link)\\b[^>]*rel=\"next\"[^>]*href=\"([^\"]+)\"|<(?:a|link)\\b[^>]*href=\"([^\"]+)\"[^>]*rel=\"next\"",
        RegexOption.IGNORE_CASE)
    private val PAGE_LINK = Regex("<a\\b[^>]*href=\"([^\"]*[?&](?:page|p)=(\\d+)[^\"]*)\"", RegexOption.IGNORE_CASE)
    private val PAGE_NUM = Regex("[?&](?:page|p)=(\\d+)")

    /** The posts on one thread page, in order; empty texts dropped, never invented. */
    fun posts(html: String): List<Post> {
        val found = LinkedHashMap<String, MutableList<String>>()
        val lastEnd = HashMap<String, Int>()
        for (m in CONTAINER.findAll(html)) {
            val tag = m.groupValues[1].lowercase()
            val key = tag + ":" + m.groupValues[2].lowercase()
            if (m.range.first < (lastEnd[key] ?: -1)) continue          // inside a container already taken
            val end = closeOf(html, tag, m.range.last + 1)
            lastEnd[key] = end
            found.getOrPut(key) { ArrayList() } += html.substring(m.range.last + 1, end)
        }
        val best = found.values.maxByOrNull { it.size } ?: return emptyList()
        val seen = HashSet<String>()
        return best.mapNotNull { block ->
            val text = OnionSafety.text(block)
            if (text.isBlank() || !seen.add(text)) return@mapNotNull null
            val author = AUTHOR.find(block)?.groupValues?.get(2)?.let(::unescape)?.trim().orEmpty().take(80)
            val date = DATE.find(block)?.groupValues?.let { g -> g[1].ifEmpty { g[2] }.ifEmpty { g[4] } }?.let(::unescape)?.trim().orEmpty().take(60)
            Post(author, date, text)
        }
    }

    /** Where the container opened just before [from] closes: the matching close tag of the same name. */
    private fun closeOf(html: String, tag: String, from: Int): Int {
        val open = Regex("<$tag\\b", RegexOption.IGNORE_CASE)
        val close = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE)
        var depth = 1
        var pos = from
        while (pos < html.length) {
            val c = close.find(html, pos) ?: return html.length
            val o = open.find(html, pos)
            if (o != null && o.range.first < c.range.first) { depth++; pos = o.range.last + 1 }
            else { depth--; if (depth == 0) return c.range.first; pos = c.range.last + 1 }
        }
        return html.length
    }

    /**
     * The next page of [pageUrl] only when the page names one: a rel="next" link, else a link to
     * page N+1. Same host only; null when the page names none — a page number is never guessed.
     */
    fun nextPage(pageUrl: String, html: String): String? {
        val explicit = NEXT.find(html)?.groupValues?.let { it[1].ifEmpty { it[2] } }
        val cur = PAGE_NUM.find(pageUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val href = explicit ?: PAGE_LINK.findAll(html).firstOrNull { it.groupValues[2].toIntOrNull() == cur + 1 }?.groupValues?.get(1)
            ?: return null
        val abs = resolve(pageUrl, unescape(href)) ?: return null
        val host = runCatching { URI(pageUrl).host?.lowercase() }.getOrNull()
        val nextHost = runCatching { URI(abs).host?.lowercase() }.getOrNull()
        return abs.takeIf { host != null && host == nextHost && it != pageUrl }
    }

    internal fun resolve(base: String, href: String): String? =
        runCatching { URI(base).resolve(href.trim()).toString() }.getOrNull()

    internal fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")

    /**
     * A thread through [fetch] (the Tor proxy's — never the normal client for an onion): up to
     * [maxPages] pages, following only [nextPage]. [firstHtml] is the first page when it was
     * already read. A page that fails ends the thread.
     */
    suspend fun thread(url: String, fetch: suspend (String) -> String?, maxPages: Int = 3, firstHtml: String? = null): Thread {
        val posts = ArrayList<Post>()
        val seen = HashSet<String>()
        var page: String? = url
        var pages = 0
        var html = firstHtml
        while (page != null && pages < maxPages) {
            val h = html ?: fetch(page) ?: break
            html = null
            pages++
            for (p in posts(h)) if (seen.add(p.text)) posts += p
            page = nextPage(page, h)
        }
        return Thread(url, posts, pages)
    }

    /** A link a wiki index lists, with its address-only verdict. */
    data class WikiLink(val url: String, val label: String, val result: OnionSafety.Result)

    private val ONION_HREF = Regex("href=\"((?:https?://)?(?:[a-z0-9-]+\\.)*[a-z2-7]{56}\\.onion(?:[:/][^\"\\s<>]*)?)\"[^>]*>([\\s\\S]{0,200}?)</a>", RegexOption.IGNORE_CASE)

    /**
     * The onion links a wiki index lists, the wikis way: each screened on its address
     * ([OnionSafety.classify] without the page — TRUSTED, LIKELY_PHISH or UNKNOWN), one per address.
     * The label is the link's text. Nothing is fetched here.
     */
    fun wikiLinks(html: String, known: Map<String, String>): List<WikiLink> {
        val seen = HashSet<String>()
        return ONION_HREF.findAll(html).mapNotNull { m ->
            val url = m.groupValues[1].let { if ("://" in it) it else "http://$it" }
            val r = OnionSafety.classify(url, null, known) ?: return@mapNotNull null
            if (!seen.add(r.address)) return@mapNotNull null
            val label = OnionSafety.text(m.groupValues[2]).take(80).ifBlank { OnionSafety.short(r.address) }
            WikiLink(url, label, r)
        }.toList()
    }
}
