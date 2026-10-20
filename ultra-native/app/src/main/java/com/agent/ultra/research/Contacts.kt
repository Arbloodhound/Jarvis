package com.agent.ultra.research

import com.agent.ultra.agent.OnionSafety

/**
 * Contact strings on a page that was actually fetched — emails, phone numbers (conservative),
 * @handles, PGP blocks, and "Name — Role" pairs next to a contact — each with where it was found:
 * the page URL, a snippet around it and the fetch time. A contact without that provenance never
 * exists. Kinds come from the page, not from lists of people or companies.
 *
 * Scope: public pages the research already fetched, or that the owner pointed at. No people-search
 * or data-broker sites ([BROKERS] are skipped), no breached or leaked data. Pure, JVM-tested.
 */
object Contacts {

    data class Provenance(val url: String, val snippet: String, val fetchedAt: Long)

    data class Contact(val kind: String, val value: String, val provenance: Provenance)

    /**
     * People-search, data-broker and breach-lookup sites: never read for contacts or findings.
     * Not in the spec, and the owner's research is not meant to go through them.
     */
    val BROKERS = setOf(
        "spokeo.com", "whitepages.com", "beenverified.com", "truepeoplesearch.com", "fastpeoplesearch.com",
        "intelius.com", "peoplefinders.com", "radaris.com", "mylife.com", "instantcheckmate.com", "truthfinder.com",
        "192.com", "thatsthem.com", "peekyou.com", "pipl.com", "zabasearch.com", "spydialer.com", "usphonebook.com",
        "nuwber.com", "clustrmaps.com", "familytreenow.com", "peoplelooker.com", "checkpeople.com", "ussearch.com",
        "haveibeenpwned.com", "dehashed.com", "leakcheck.io", "snusbase.com", "intelx.io", "leak-lookup.com",
    )

    fun isBroker(domain: String): Boolean {
        val d = domain.lowercase().removePrefix("www.")
        return BROKERS.any { d == it || d.endsWith(".$it") }
    }

    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)*\\.([A-Za-z]{2,24})\\b")
    private val NOT_MAIL_TLD = setOf("png", "jpg", "jpeg", "gif", "svg", "webp", "css", "js", "ico")
    private val HANDLE = Regex("(?<![\\w@.])@([A-Za-z0-9_]{2,32})\\b")
    private val PGP = Regex("-----BEGIN PGP (?:PUBLIC KEY BLOCK|SIGNED MESSAGE|MESSAGE)-----")
    private val PHONE = Regex("(?<![\\w+])(?:\\+\\d{1,3}[\\s.-]?)?\\(?\\d[\\d\\s.()-]{5,20}\\d(?![\\w])")
    private val DATE = Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}")
    private val NAME_ROLE = Regex("([A-Z][a-z]+(?: [A-Z][a-z'.]+){1,2})\\s*[,:–—-]\\s*([A-Z][a-zA-Z &/.'-]{2,40})")

    /**
     * Contacts in [html] fetched from [url] at [fetchedAt], one per (kind, value). Onion email
     * addresses are left to the onion checks.
     */
    fun extract(html: String, url: String, fetchedAt: Long): List<Contact> {
        val text = OnionSafety.text(html)
        val out = ArrayList<Contact>()
        val seen = HashSet<String>()
        fun add(kind: String, value: String, at: Int) {
            val v = value.trim()
            if (v.isEmpty() || !seen.add("$kind|${v.lowercase()}")) return
            val snippet = text.substring(maxOf(0, at - 60), minOf(text.length, at + 80)).trim().take(140)
            out += Contact(kind, v, Provenance(url, snippet, fetchedAt))
        }
        val blocks = ArrayList<IntRange>()
        for (m in EMAIL.findAll(text)) {
            if (m.value.lowercase().contains(".onion") || m.groupValues[1].lowercase() in NOT_MAIL_TLD) continue
            add("email", m.value, m.range.first); blocks += (m.range.first - 300)..(m.range.last + 300)
        }
        for (m in PHONE.findAll(text)) {
            val raw = m.value.trim()
            val digits = raw.count { it.isDigit() }
            if (digits !in 7..15 || DATE.containsMatchIn(raw)) continue
            add("phone", raw, m.range.first); blocks += (m.range.first - 300)..(m.range.last + 300)
        }
        for (m in HANDLE.findAll(text)) add("handle", "@" + m.groupValues[1], m.range.first)
        if (PGP.containsMatchIn(html)) add("pgp", "PGP block present", 0)
        for (b in blocks) {
            val start = maxOf(0, b.first); val end = minOf(text.length, b.last)
            if (start >= end) continue
            for (m in NAME_ROLE.findAll(text.substring(start, end))) add("name_role", "${m.groupValues[1]} — ${m.groupValues[2].trim()}", start + m.range.first)
        }
        return out
    }
}
