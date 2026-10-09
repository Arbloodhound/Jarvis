package com.agent.ultra.research

import com.agent.ultra.agent.OnionSafety
import com.agent.ultra.agent.OnionSafety.Verdict
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Deep research over Tor: a bounded crawl for onion links, and reading the forum threads the owner
 * points at. Runs only when the owner asks for it ("deep research …" / "investigate …") with both
 * Settings switches on — "Onion research via Tor" and "Deep research over Tor", off by default.
 * Never on its own.
 *
 * ## Scope (owner, 2026-10-09)
 * Reading hidden wikis and forums through Tor with scam-vs-legit screening, and checking products
 * and companies across clearnet and onion. Explicitly OUT of scope ("I don't need special skills for
 * the dark net", "I don't wanna find illegal items"): marketplace navigation, vendor or seller
 * reputation or vetting, and any purchasing.
 *
 * ## The crawl ([crawl])
 * Breadth first from the known-good set, the owner's own list and any start pages the owner gave,
 * with [maxDepth] and [maxPages] (fetch attempts) always enforced, and a deadline. Every address is
 * classified first: a LIKELY_PHISH address is never fetched; a page that screens SCAM or
 * LIKELY_PHISH goes to the red-flag log and its links are never followed. TRUSTED and
 * screened-clean pages found by the crawl (not the start pages) go into the link pool, each traced
 * to the page it was found on. The only fetcher is the one passed in — the Tor proxy; when a fetch
 * fails and the proxy no longer answers, the crawl stops with a note. There is no clearnet fallback.
 *
 * ## Threads ([readThreads])
 * The forum treatment ([Forums.thread]): posts with author and date, explicit next-page links only;
 * the onion addresses and contact strings the posts mention, each with the thread, author and date
 * it came from. Mentioned addresses are judged on the address alone (not fetched).
 */
class Discovery(
    private val fetch: suspend (String) -> String?,
    private val proxyUp: () -> Boolean,
    private val known: Map<String, String>,
    private val now: () -> Long = System::currentTimeMillis,
) {

    data class PoolEntry(val address: String, val title: String, val foundOn: String, val foundAt: Long, val verdict: Verdict, val notes: String)

    data class Flag(val address: String, val verdict: Verdict, val reasons: List<String>, val foundOn: String?)

    data class Crawl(val pool: List<PoolEntry>, val redFlags: List<Flag>, val errors: List<String>, val pagesFetched: Int, val note: String?)

    /** One thing a thread post mentions: an onion address (with its verdict) or a contact string. */
    data class Mention(val kind: String, val value: String, val verdict: Verdict?, val reasons: List<String>,
                       val thread: String, val author: String, val date: String, val fetchedAt: Long)

    data class ThreadResearch(val threads: List<Forums.Thread>, val mentions: List<Mention>, val posts: List<Pair<String, Forums.Post>>)

    suspend fun crawl(seeds: List<String>, maxDepth: Int = 1, maxPages: Int = 12, deadline: Long = Long.MAX_VALUE): Crawl {
        val pool = ArrayList<PoolEntry>()
        val flags = ArrayList<Flag>()
        val errors = ArrayList<String>()
        val queued = HashSet<String>()
        val queue = ArrayDeque<Triple<String, Int, String?>>()
        for (s in seeds) {
            val addr = OnionSafety.onionHost(s)?.let(OnionSafety::address)
            if (addr == null || !OnionSafety.isV3(addr)) { errors += "skipped start page ${s.take(80)}: not a valid v3 onion address"; continue }
            if (queued.add(addr)) queue.addLast(Triple(if ("://" in s) s.trim() else "http://$addr/", 0, null))
        }
        var fetched = 0
        var note: String? = null
        while (queue.isNotEmpty()) {
            if (fetched >= maxPages) { note = "Stopped at the page limit ($maxPages pages)."; break }
            if (now() > deadline) { note = "Stopped at the time limit."; break }
            val (url, depth, foundOn) = queue.removeFirst()
            val pre = OnionSafety.classify(url, null, known) ?: continue
            if (pre.verdict == Verdict.LIKELY_PHISH) { flags += Flag(pre.address, pre.verdict, pre.reasons, foundOn); continue }
            fetched++
            val html = fetch(url)
            if (html == null) {
                if (!proxyUp()) { note = "Tor stopped answering — crawl stopped; nothing was fetched over the normal connection."; break }
                errors += "couldn't load ${OnionSafety.short(pre.address)} over Tor"; continue
            }
            val r = OnionSafety.classify(url, html, known) ?: continue
            if (r.verdict == Verdict.SCAM || r.verdict == Verdict.LIKELY_PHISH) { flags += Flag(r.address, r.verdict, r.reasons, foundOn); continue }
            if (depth >= 1) pool += PoolEntry(r.address, OnionSafety.title(html).orEmpty().take(80), foundOn.orEmpty(), now(), r.verdict, r.reasons.joinToString("; ").take(300))
            if (depth < maxDepth) for (link in OnionSafety.urlsIn(html)) {
                val a = OnionSafety.onionHost(link)?.let(OnionSafety::address) ?: continue
                if (queue.size < 200 && queued.add(a)) queue.addLast(Triple(link, depth + 1, url))
            }
        }
        if (note == null && queue.isNotEmpty()) note = "Stopped with ${queue.size} links not visited."
        return Crawl(pool, flags, errors, fetched, note)
    }

    suspend fun readThreads(query: String, urls: List<String>, maxThreads: Int = 3, maxPagesPerThread: Int = 3,
                            firstPages: Map<String, String> = emptyMap()): ThreadResearch {
        val words = query.lowercase().split(Regex("[^\\p{L}\\d]+")).filter { it.length >= 3 }
        val threads = ArrayList<Forums.Thread>()
        val mentions = ArrayList<Mention>()
        val posts = ArrayList<Pair<String, Forums.Post>>()
        val seen = HashSet<String>()
        for (url in urls.take(maxThreads)) {
            val t = Forums.thread(url, fetch, maxPagesPerThread, firstPages[url])
            if (t.pagesFetched == 0) continue
            threads += t
            val at = now()
            for (p in t.posts) {
                val lower = p.text.lowercase()
                if (words.isNotEmpty() && words.none { it in lower }) continue
                posts += url to p
                for (a in OnionSafety.addressesIn(p.text)) {
                    val r = OnionSafety.classify("http://$a/", null, known) ?: continue
                    if (seen.add("onion|$a")) mentions += Mention("onion", a, r.verdict, r.reasons, url, p.author, p.date, at)
                }
                for (c in Contacts.extract(p.text, url, at)) {
                    if (c.kind == "name_role") continue
                    if (seen.add("${c.kind}|${c.value.lowercase()}")) mentions += Mention(c.kind, c.value, null, emptyList(), url, p.author, p.date, at)
                }
            }
        }
        return ThreadResearch(threads, mentions, posts)
    }

    companion object {
        const val POOL_FILE = "onion_link_pool.json"
        const val FLAG_FILE = "onion_red_flags.json"
        private const val FLAG_CAP = 300

        /** The saved link pool in [dir]; a missing or broken file is an empty pool. */
        fun loadPool(dir: File): List<PoolEntry> = try {
            val a = JSONObject(File(dir, POOL_FILE).readText()).getJSONArray("entries")
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val addr = o.optString("onion")
                val v = runCatching { Verdict.valueOf(o.optString("verdict")) }.getOrNull()
                if (!OnionSafety.isV3(addr) || (v != Verdict.TRUSTED && v != Verdict.UNKNOWN)) null
                else PoolEntry(addr, o.optString("title"), o.optString("found_on"), o.optLong("found_at"), v, o.optString("notes"))
            }
        } catch (_: Exception) { emptyList() }

        /**
         * [entries] added to the pool in [dir], one per address. Only valid v3 addresses whose
         * verdict is TRUSTED or UNKNOWN (screened clean) are ever stored. The whole pool back.
         */
        fun savePool(dir: File, entries: List<PoolEntry>): List<PoolEntry> {
            val all = LinkedHashMap<String, PoolEntry>()
            for (e in loadPool(dir) + entries) {
                if (!OnionSafety.isV3(e.address) || (e.verdict != Verdict.TRUSTED && e.verdict != Verdict.UNKNOWN)) continue
                all.putIfAbsent(e.address, e)
            }
            val a = JSONArray()
            for (e in all.values) a.put(JSONObject().put("onion", e.address).put("title", e.title).put("found_on", e.foundOn)
                .put("found_at", e.foundAt).put("verdict", e.verdict.name).put("notes", e.notes))
            dir.mkdirs()
            File(dir, POOL_FILE).writeText(JSONObject().put("entries", a).toString(1))
            return all.values.toList()
        }

        /** [flags] appended to the red-flag log in [dir] (the newest [FLAG_CAP] kept). */
        fun logFlags(dir: File, flags: List<Flag>, at: Long) {
            if (flags.isEmpty()) return
            val f = File(dir, FLAG_FILE)
            val a = try { JSONObject(f.readText()).getJSONArray("entries") } catch (_: Exception) { JSONArray() }
            for (x in flags) a.put(JSONObject().put("onion", x.address).put("verdict", x.verdict.name)
                .put("reasons", JSONArray(x.reasons)).put("found_on", x.foundOn ?: "").put("at", at))
            val keep = JSONArray()
            for (i in maxOf(0, a.length() - FLAG_CAP) until a.length()) keep.put(a.get(i))
            dir.mkdirs()
            f.writeText(JSONObject().put("entries", keep).toString(1))
        }

        /** The red-flag log in [dir], oldest first. */
        fun loadFlags(dir: File): List<Flag> = try {
            val a = JSONObject(File(dir, FLAG_FILE).readText()).getJSONArray("entries")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val rs = o.optJSONArray("reasons")
                Flag(o.optString("onion"), Verdict.valueOf(o.optString("verdict")),
                    (0 until (rs?.length() ?: 0)).map { rs!!.getString(it) }, o.optString("found_on").ifBlank { null })
            }
        } catch (_: Exception) { emptyList() }

        /**
         * The deep-research section for the brain: what the crawl found (pooled links with where
         * they were found), red flags (shortened, never links), thread posts and mentions with
         * provenance, wiki links with their verdicts, notes. Pure, JVM-tested.
         */
        fun render(crawl: Crawl?, threads: ThreadResearch?, wikis: List<Pair<String, List<Forums.WikiLink>>>, notes: List<String>): String {
            // Every flagged address is shown shortened, wherever it appears (post text included).
            val bad = (crawl?.redFlags?.map { it.address }.orEmpty() +
                threads?.mentions?.filter { it.verdict == Verdict.LIKELY_PHISH || it.verdict == Verdict.SCAM }?.map { it.value }.orEmpty() +
                wikis.flatMap { (_, ls) -> ls.filter { it.result.verdict == Verdict.LIKELY_PHISH }.map { it.result.address } }).distinct()
            return OnionSafety.redact(renderRaw(crawl, threads, wikis, notes), bad)
        }

        private fun renderRaw(crawl: Crawl?, threads: ThreadResearch?, wikis: List<Pair<String, List<Forums.WikiLink>>>, notes: List<String>): String = buildString {
            append("Deep research over Tor (bounded crawl; onion pages only, through Tor):\n")
            notes.forEach { append(it).append('\n') }
            crawl?.let { c ->
                append("Crawl: ${c.pagesFetched} pages fetched, ${c.pool.size} new links pooled, ${c.redFlags.size} red flags.")
                c.note?.let { append(' ').append(it) }
                append('\n')
                c.pool.take(10).forEach { e ->
                    append("- ${e.address} — ${e.title.ifBlank { "untitled" }} (${if (e.verdict == Verdict.TRUSTED) "known-good" else "unlisted, screened clean — not verified"}; found on ${OnionSafety.onionHost(e.foundOn)?.let { h -> OnionSafety.short(OnionSafety.address(h)) } ?: e.foundOn})\n")
                }
                c.redFlags.take(6).forEach { f -> append("Red flag: ${OnionSafety.short(f.address)} — ${f.verdict.name.lowercase().replace('_', ' ')}: ${f.reasons.joinToString("; ")}. Never followed; not a link to visit.\n") }
                c.errors.take(3).forEach { append("Note: ").append(it).append('\n') }
            }
            for ((index, links) in wikis) {
                append("Wiki index ${OnionSafety.onionHost(index)?.let(OnionSafety::address).orEmpty()}: ${links.size} onion links.\n")
                links.filter { it.result.verdict != Verdict.LIKELY_PHISH }.take(10).forEach { l ->
                    append("- ${l.label} — ${l.result.address} (${if (l.result.verdict == Verdict.TRUSTED) "known-good" else "unlisted — not verified"})\n")
                }
                links.filter { it.result.verdict == Verdict.LIKELY_PHISH }.forEach { l ->
                    append("Red flag: the index lists ${OnionSafety.short(l.result.address)} as \"${l.label}\" — ${l.result.reasons.joinToString("; ")}. Not a link to visit.\n")
                }
            }
            threads?.let { t ->
                t.threads.forEach { th -> append("Thread ${th.url}: ${th.posts.size} posts over ${th.pagesFetched} pages.\n") }
                t.posts.take(6).forEach { (url, p) ->
                    append("- ${p.author.ifBlank { "unknown" }}${if (p.date.isNotBlank()) ", ${p.date}" else ""} (${OnionSafety.onionHost(url)?.let { OnionSafety.short(OnionSafety.address(it)) } ?: url}): ${p.text.take(240)}\n")
                }
                t.mentions.take(12).forEach { m ->
                    val where = "in ${m.thread}, post by ${m.author.ifBlank { "unknown" }}${if (m.date.isNotBlank()) " (${m.date})" else ""}"
                    append(when {
                        m.kind != "onion" -> "Mentioned ${m.kind}: ${m.value} — $where\n"
                        m.verdict == Verdict.LIKELY_PHISH -> "Red flag: a post mentions ${OnionSafety.short(m.value)} — ${m.reasons.joinToString("; ")} — $where. Not a link to visit.\n"
                        else -> "Mentioned onion: ${m.value} (${if (m.verdict == Verdict.TRUSTED) "known-good" else "unlisted — not verified"}) — $where\n"
                    })
                }
            }
        }
    }
}
