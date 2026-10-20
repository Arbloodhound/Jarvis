package com.agent.jarvis.research

import com.agent.jarvis.agent.OnionSafety
import com.agent.jarvis.agent.OnionSafety.Verdict
import com.agent.jarvis.agent.WebResearch
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.File

/**
 * "Investigate …": a case file on a person, a company or a topic, built from public sources over
 * the research path, each finding and contact carrying where it came from.
 *
 * Research across clearnet and Tor: scam-vs-legit screening, and product and company
 * verification. ([Contacts.BROKERS] are skipped everywhere in this file.)
 *
 * ## Legs, in order
 * 1. clearnet — the normal research run; each source becomes a finding (url, snippet, fetch time).
 * 2. articles — the pages that run read; contact strings on them, each with provenance.
 * 3. tor — the deep-research pass ([Discovery]); skipped with the reason when unavailable.
 * 4. osint — operator queries (filetype:, inurl:, site: on the domains found in legs 1–2) and
 *    pivots on the contacts found, each hit a finding with its url and snippet.
 *
 * ## Invariants
 * [CaseFile.addFinding] and [CaseFile.addContact] refuse anything without a source URL — nothing
 * sourceless enters a case. LIKELY_PHISH / SCAM onions never become case links. Outreach is drafts
 * only ([Outreach]): there is no send path in this package; sending is a separate action the owner
 * approves per recipient. Each case is saved as JSON in app-private storage (files/cases/<id>.json).
 * The summary ends with a question. JVM-tested with fakes for every leg.
 */
class CaseFile(val id: String, val target: String, val createdAt: Long) {

    data class Leg(val name: String, val status: String, val note: String)
    data class Finding(val claim: String, val url: String, val snippet: String, val fetchedAt: Long, val realm: String)
    data class Link(val address: String, val title: String, val foundOn: String, val verdict: Verdict)
    data class RedFlag(val what: String, val reasons: List<String>, val foundOn: String)
    data class Draft(val to: String, val channel: String, val subject: String, val body: String, val provenance: Contacts.Provenance) {
        val status: String get() = "draft — never sent by Jarvis"
    }

    val legs = ArrayList<Leg>()
    val findings = ArrayList<Finding>()
    val contacts = ArrayList<Contacts.Contact>()
    val links = ArrayList<Link>()
    val redFlags = ArrayList<RedFlag>()
    val drafts = ArrayList<Draft>()

    fun leg(name: String, status: String, note: String = "") { legs += Leg(name, status, note) }

    /** Refused (IllegalArgumentException) without a source URL: a claim without a source is not evidence. */
    fun addFinding(claim: String, url: String, snippet: String, fetchedAt: Long, realm: String): Finding {
        require(url.isNotBlank()) { "finding refused: no source" }
        require(realm == "clearnet" || realm == "tor") { "finding refused: unknown realm $realm" }
        return Finding(claim.take(2000), url, snippet.take(300), fetchedAt, realm).also { findings += it }
    }

    /** Refused without a provenance URL; null for a duplicate (same kind and value). */
    fun addContact(c: Contacts.Contact): Contacts.Contact? {
        require(c.provenance.url.isNotBlank()) { "contact refused: no provenance" }
        require(c.value.isNotBlank()) { "contact refused: empty value" }
        if (contacts.any { it.kind == c.kind && it.value.equals(c.value, ignoreCase = true) }) return null
        return c.also { contacts += it }
    }

    /** Only TRUSTED or screened-clean (UNKNOWN) links; anything else becomes a red flag instead. */
    fun addLink(l: Link) {
        if (l.verdict != Verdict.TRUSTED && l.verdict != Verdict.UNKNOWN) { redFlags += RedFlag(OnionSafety.short(l.address), listOf("${l.verdict} reached the case merge"), l.foundOn); return }
        if (links.none { it.address == l.address }) links += l
    }

    fun toJson(): JSONObject = JSONObject().put("case_id", id).put("target", target).put("created_at", createdAt)
        .put("legs", JSONArray(legs.map { JSONObject().put("leg", it.name).put("status", it.status).put("note", it.note) }))
        .put("findings", JSONArray(findings.map { JSONObject().put("claim", it.claim).put("url", it.url).put("snippet", it.snippet).put("fetched_at", it.fetchedAt).put("realm", it.realm) }))
        .put("contacts", JSONArray(contacts.map { JSONObject().put("kind", it.kind).put("value", it.value).put("provenance", prov(it.provenance)) }))
        .put("links", JSONArray(links.map { JSONObject().put("onion", it.address).put("title", it.title).put("found_on", it.foundOn).put("verdict", it.verdict.name) }))
        .put("red_flags", JSONArray(redFlags.map { JSONObject().put("what", it.what).put("reasons", JSONArray(it.reasons)).put("found_on", it.foundOn) }))
        .put("outreach_drafts", JSONArray(drafts.map { JSONObject().put("to", it.to).put("channel", it.channel).put("subject", it.subject).put("body", it.body).put("provenance", prov(it.provenance)).put("status", it.status) }))

    private fun prov(p: Contacts.Provenance) = JSONObject().put("url", p.url).put("snippet", p.snippet).put("fetched_at", p.fetchedAt)

    /** Saved as [dir]/cases/<id>.json. The file. */
    fun save(dir: File): File {
        val d = File(dir, "cases").apply { mkdirs() }
        return File(d, "$id.json").apply { writeText(toJson().toString(1)) }
    }
}

/**
 * Outreach DRAFTS for a case contact. There is deliberately no way to send anything here — no
 * network, no messaging, no intent: the owner sends, per recipient, through a separate action they
 * approve. Placeholders in the subject and body: {value}, {kind}, {query}, {source_url}; any other
 * {placeholder} is refused so a draft never goes out with a hole in it. A contact without a source
 * is refused: Jarvis never drafts to a recipient it can't trace. Pure, JVM-tested.
 */
object Outreach {
    private val PLACEHOLDER = Regex("\\{([a-z_]+)\\}")
    private val KNOWN = setOf("value", "kind", "query", "source_url")

    fun draft(contact: Contacts.Contact, subject: String, body: String, query: String): CaseFile.Draft {
        require(contact.provenance.url.isNotBlank()) { "draft refused: contact has no source" }
        require(subject.isNotBlank() || body.isNotBlank()) { "draft refused: empty template" }
        val fields = mapOf("value" to contact.value, "kind" to contact.kind, "query" to query, "source_url" to contact.provenance.url)
        fun fill(t: String) = PLACEHOLDER.replace(t) { m ->
            val k = m.groupValues[1]
            require(k in KNOWN) { "draft refused: unknown placeholder {$k}" }
            fields.getValue(k)
        }
        return CaseFile.Draft(if (contact.kind == "email") contact.value else "", contact.kind, fill(subject), fill(body), contact.provenance)
    }
}

object Investigation {

    /** What the clearnet leg hands over: the sources (hit, page text if read) and the raw pages read. */
    data class Clearnet(val sources: List<Pair<WebResearch.Hit, String?>>, val rawPages: Map<String, String>, val fetchedAt: Long)

    /** The Tor leg's outcome, or why it didn't run. */
    data class Tor(val crawl: Discovery.Crawl?, val threads: Discovery.ThreadResearch?, val skipped: String?)

    const val QUESTION = "What do you want next — dig deeper (another leg or pivot), check one contact or source further, " +
        "draft a message to a contact (a draft only; you send it), or drop it?"

    /** The operator and pivot queries for leg 4, at most [max]. Pure, JVM-tested. */
    fun osintQueries(target: String, domains: List<String>, contacts: List<Contacts.Contact>, max: Int = 5): List<String> {
        val t = target.trim().replace("\"", "")
        val q = arrayListOf("\"$t\" filetype:pdf", "\"$t\" inurl:contact")
        domains.filterNot(Contacts::isBroker).distinct().take(3).forEach { q += "site:$it \"$t\"" }
        contacts.filter { it.kind == "email" || it.kind == "phone" }.take(2).forEach { q += "\"${it.value}\"" }
        return q.distinct().take(max)
    }

    suspend fun run(
        target: String,
        clearnet: suspend (String) -> Clearnet?,
        search: suspend (String) -> List<WebResearch.Hit>,
        tor: (suspend () -> Tor)?,
        now: () -> Long = System::currentTimeMillis,
        id: String = java.util.UUID.randomUUID().toString().replace("-", "").take(12),
    ): CaseFile {
        val case = CaseFile(id, target, now())
        // 1. clearnet
        val c = try { clearnet(target) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val domains = ArrayList<String>()
        if (c == null || c.sources.isEmpty()) case.leg("clearnet", "empty", "search returned nothing")
        else {
            var n = 0
            for ((h, text) in c.sources) {
                if (Contacts.isBroker(h.domain) || OnionSafety.onionHost(h.url) != null) continue
                case.addFinding(listOf(h.title, text ?: h.snippet).filter { it.isNotBlank() }.joinToString(" — ").take(600), h.url, h.snippet, c.fetchedAt, "clearnet")
                if (h.domain.isNotBlank() && h.domain !in domains) domains += h.domain
                n++
            }
            case.leg("clearnet", if (n > 0) "ok" else "empty", "$n sources")
        }
        // 2. articles
        if (c == null || c.rawPages.isEmpty()) case.leg("articles", "empty", "no pages read")
        else {
            var n = 0
            for ((url, html) in c.rawPages) {
                if (Contacts.isBroker(WebResearch.domainOf(url)) || OnionSafety.onionHost(url) != null) continue
                n++
                Contacts.extract(html, url, c.fetchedAt).forEach { case.addContact(it) }
            }
            case.leg("articles", if (n > 0) "ok" else "empty", "$n pages, ${case.contacts.size} contacts")
        }
        // 3. tor (opt-in)
        if (tor == null) case.leg("tor", "skipped", "Tor leg is opt-in (Settings: onion research and deep research) and wasn't on")
        else {
            val t = try { tor() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Tor(null, null, "error: ${e.message?.take(80)}") }
            if (t.skipped != null) case.leg("tor", "skipped", t.skipped)
            else {
                t.crawl?.pool?.forEach { case.addLink(CaseFile.Link(it.address, it.title, it.foundOn, it.verdict)) }
                t.crawl?.redFlags?.forEach { case.redFlags += CaseFile.RedFlag(OnionSafety.short(it.address), it.reasons, it.foundOn.orEmpty()) }
                t.threads?.let { th ->
                    val bad = th.mentions.filter { it.verdict == Verdict.LIKELY_PHISH || it.verdict == Verdict.SCAM }.map { it.value }
                    for ((url, p) in th.posts.take(10)) case.addFinding(OnionSafety.redact(p.text, bad).take(600), url, listOf(p.author, p.date).filter { it.isNotBlank() }.joinToString(", "), now(), "tor")
                    for (m in th.mentions) {
                        val prov = Contacts.Provenance(m.thread, "post by ${m.author.ifBlank { "unknown" }}${if (m.date.isNotBlank()) ", ${m.date}" else ""}", m.fetchedAt)
                        if (m.kind == "onion") {
                            if (m.verdict == Verdict.LIKELY_PHISH || m.verdict == Verdict.SCAM) case.redFlags += CaseFile.RedFlag(OnionSafety.short(m.value), m.reasons, m.thread)
                            else case.addLink(CaseFile.Link(m.value, "forum mention", m.thread, m.verdict ?: Verdict.UNKNOWN))
                        } else case.addContact(Contacts.Contact(m.kind, m.value, prov))
                    }
                }
                case.leg("tor", "ok", "${case.links.size} links, ${case.redFlags.size} red flags" + (t.crawl?.note?.let { " — $it" } ?: ""))
            }
        }
        // 4. osint
        val queries = osintQueries(target, domains, case.contacts)
        var hits = 0
        // In parallel (each search is bounded by the caller); merged in query order.
        val results = kotlinx.coroutines.coroutineScope {
            queries.map { q -> async { q to (try { search(q) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }) to now() } }.awaitAll()
        }
        for ((qr, at) in results) {
            val (q, res) = qr
            for (h in res.take(3)) {
                if (Contacts.isBroker(h.domain) || OnionSafety.onionHost(h.url) != null) continue
                if (case.findings.any { it.url == h.url }) continue
                case.addFinding("${h.title} — ${h.snippet}".take(600), h.url, "via: $q — ${h.snippet}", at, "clearnet"); hits++
            }
        }
        case.leg("osint", if (hits > 0) "ok" else "empty", "$hits results from ${queries.size} operator/pivot queries")
        return case
    }

    /** The case summary for the brain: legs, findings [n] with url and fetch time, contacts with provenance, links, red flags, the question. Pure, JVM-tested. */
    fun summary(case: CaseFile, max: Int = 3000): String {
        val head = "Investigation case ${case.id} — ${case.target}. Legs: " +
            case.legs.joinToString("; ") { "${it.name} ${it.status}${if (it.note.isNotBlank()) " (${it.note})" else ""}" } + "\n" +
            case.redFlags.take(5).joinToString("") { "Red flag: ${it.what} — ${it.reasons.joinToString("; ")}. Not a link to visit.\n" }
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val body = buildString {
            case.findings.take(10).forEachIndexed { i, f ->
                append("[${i + 1}] ${f.url} (${f.realm}, fetched ${time.format(java.util.Date(f.fetchedAt))} UTC): ${f.claim.take(220)}\n")
            }
            case.contacts.take(10).forEach { c -> append("Contact ${c.kind}: ${c.value} — from ${c.provenance.url}: \"${c.provenance.snippet.take(100)}\"\n") }
            case.links.take(6).forEach { l -> append("Onion link: ${l.address} (${if (l.verdict == Verdict.TRUSTED) "known-good" else "unlisted — not verified"}; found on ${l.foundOn})\n") }
        }
        val tail = "Report only what these sources say, citing [n] and where each contact was found; no guesses about the target beyond them. " +
            "Outreach is drafts only. End with: $QUESTION"
        val room = max - head.length - tail.length
        return head + (if (body.length <= room) body else body.take(maxOf(0, room - 1)) + "…") + tail
    }
}
