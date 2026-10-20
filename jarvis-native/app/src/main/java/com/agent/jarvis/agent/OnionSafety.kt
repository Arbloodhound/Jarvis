package com.agent.jarvis.agent

/**
 * Is this .onion address the real service or a phishing clone, and does this onion page show scam
 * signals? Defensive screening for research over Tor (Settings → "Onion research via Tor").
 *
 * ## Scope (owner, 2026-10-09)
 * In scope: reading hidden wikis and forums through Tor with scam-vs-legit screening, and checking
 * products and companies across clearnet and onion (is it real, do the onion and clearnet
 * identities match, price evidence, scam signals). Jarvis shows its work and then asks; it never
 * says "buy this".
 *
 * Explicitly OUT of scope, in the owner's words "I don't need special skills for the dark net" and
 * "I don't wanna find illegal items": no marketplace navigation, no vendor or seller reputation or
 * vetting, no purchasing, and no crawler that discovers new onions on its own.
 *
 * ## What the checks rest on
 * - A v3 address is 56 base32 characters encoding the service's ed25519 public key, a 2-byte
 *   checksum and a version byte (3) — rend-spec-v3 §6 "Encoding onion addresses". The address IS
 *   the identity: one character different is a different service. [isV3] checks the checksum, so a
 *   mistyped or made-up address is caught locally, without any network.
 * - Phishing clones are made to look right at a glance: within 2 edits of the real address
 *   ([LOOKALIKE_MAX], the reference design's threshold), or a vanity address that copies the real
 *   one's first characters ([PREFIX_CLONE]; matching 6 base32 characters by chance is about 1 in
 *   10⁹, so a shared 6-character start is deliberate).
 * - A real service never asks for a wallet seed phrase or private key in a web page; a page that
 *   does is a scam whatever else it says.
 *
 * [classify]: TRUSTED only for an address in the known-good set; an unlisted address that screens
 * clean is UNKNOWN, never TRUSTED. Pure, JVM-tested.
 */
object OnionSafety {

    enum class Verdict { TRUSTED, LIKELY_PHISH, SCAM, UNKNOWN }

    data class Result(val verdict: Verdict, val address: String, val reasons: List<String>, val name: String? = null)

    enum class Signal(val words: String, val verdict: Verdict?) {
        SEED_REQUEST("asks for a wallet seed phrase or private key (real services never do)", Verdict.SCAM),
        DOUBLING("promises to double the crypto you send", Verdict.SCAM),
        IMPERSONATION("calls itself a known service but is not that service's address", Verdict.LIKELY_PHISH),
        LOGIN_FORM("has a password field", null),
    }

    data class Screen(val signals: List<Signal>, val reasons: List<String>) {
        /** The worst verdict a signal gives: SCAM before LIKELY_PHISH; null when none does. */
        val verdict: Verdict? get() = signals.mapNotNull { it.verdict }.minByOrNull { if (it == Verdict.SCAM) 0 else 1 }
    }

    /**
     * Known-good onion addresses, each published by the organisation itself on its own clearnet
     * site. Only addresses with that evidence; never an invented or guessed one.
     *
     * - DuckDuckGo — duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion. Evidence: the
     *   v4 reference directory records that DuckDuckGo's own help page
     *   (https://duckduckgo.com/duckduckgo-help-pages/privacy/no-tracking) links its "Tor onion
     *   service" text to this address (checked by the reference author 2026-10-09). NOT re-fetched
     *   when this was written: that page was unreachable from the build machine (proxy 403). The
     *   v3 checksum and version byte were verified locally ([isV3]).
     *
     * Left out on purpose:
     * - ProPublica (p53lf57q…): the reference directory's own note says its verify_via page
     *   (ProPublica's announcement) could not be fetched, so the address is supported only by
     *   third-party lists. It can go in the owner's own list once checked on propublica.org.
     * - Tor Project Debian repository: marked unverified in the reference directory.
     */
    val DIRECTORY: Map<String, String> = linkedMapOf(
        "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion" to "DuckDuckGo",
    )

    /** Edits (Levenshtein) within which an unlisted address counts as a clone of a known one. */
    const val LOOKALIKE_MAX = 2

    /** Leading characters a vanity clone copies: this many shared with a known address is a clone. */
    const val PREFIX_CLONE = 6

    private const val V3_LEN = 56
    private val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"

    /** The .onion host of [url] (scheme optional), lowercased; null when it isn't an onion. */
    fun onionHost(url: String): String? {
        val s = url.trim()
        val rest = if ("://" in s) s.substringAfter("://") else s
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase().trimEnd('.')
        return host.takeIf { it.endsWith(".onion") && it.length > ".onion".length }
    }

    /** The service address of an onion host: "www.<56>.onion" → "<56>.onion". */
    fun address(host: String): String {
        val labels = host.lowercase().trimEnd('.').split('.')
        return if (labels.size >= 2) labels[labels.size - 2] + ".onion" else host.lowercase()
    }

    /** A well-formed v3 address: 56 base32 characters whose checksum and version byte (3) are right. */
    fun isV3(host: String): Boolean {
        val label = address(host).removeSuffix(".onion")
        if (label.length != V3_LEN || label.any { it !in BASE32 }) return false
        val raw = base32(label) ?: return false            // 35 bytes: key 32, checksum 2, version 1
        val key = raw.copyOfRange(0, 32)
        val version = raw[34]
        if (version != 3.toByte()) return false
        val sum = Sha3.sha3256(".onion checksum".toByteArray(Charsets.US_ASCII) + key + byteArrayOf(version))
        return sum[0] == raw[32] && sum[1] == raw[33]
    }

    private fun base32(s: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var buf = 0L; var bits = 0
        for (c in s) {
            val v = BASE32.indexOf(c); if (v < 0) return null
            buf = (buf shl 5) or v.toLong(); bits += 5
            if (bits >= 8) { bits -= 8; out.write(((buf shr bits) and 0xFF).toInt()) }
        }
        return out.toByteArray()
    }

    /** Edit distance (insert, delete, substitute). */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * The known-good address [host] imitates, or null: within [LOOKALIKE_MAX] edits of one, or
     * sharing its first [PREFIX_CLONE] characters, without being identical to any known address.
     */
    fun lookalikeOf(host: String, knownGood: Collection<String>): String? {
        val a = address(host)
        val known = knownGood.map { address(it) }
        if (a in known) return null
        val label = a.removeSuffix(".onion")
        return known.firstOrNull { g ->
            val gl = g.removeSuffix(".onion")
            levenshtein(label, gl) <= LOOKALIKE_MAX ||
                (label.length >= PREFIX_CLONE && gl.length >= PREFIX_CLONE && label.take(PREFIX_CLONE) == gl.take(PREFIX_CLONE))
        }
    }

    private fun rx(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private const val SECRET = "(seed phrase|seed words|recovery phrase|secret phrase|mnemonic|private key|backup phrase|1[28][- ]word( phrase)?|24[- ]word( phrase)?)"
    private const val GIVE = "(enter|import|restore|provide|type|paste|submit|input|sync|validate|verify|confirm)"
    private val SEED_A = rx("""\b$SECRET\b.{0,80}?\b$GIVE\b""")
    private val SEED_B = rx("""\b$GIVE\b.{0,80}?\b$SECRET\b""")
    private val NEGATION = rx("""\b(never|not|don'?t|do not|no one|nobody|won'?t)\b""")
    private val SEND_GET = rx("""\bsend\b.{0,40}?\b(0?\.\d+|\d[\d,.]*)\s*(btc|bitcoin|eth|xmr|usdt|ltc)\b.{0,60}?\b(get|receive|back)\b.{0,20}?\b(2x|double|twice|triple|3x)\b""")
    private val PASSWORD = rx("""<input[^>]+type\s*=\s*["']?password""")
    private val TITLE = rx("""<title[^>]*>(.*?)</title>""")

    /** The readable text of [html]: scripts and styles dropped, tags removed, spaces collapsed. */
    fun text(html: String): String = html
        .replace(rx("<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("<[^>]*>"), " ").replace("&amp;", "&").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()

    fun title(html: String): String? =
        TITLE.find(html)?.groupValues?.get(1)?.let { text(it) }?.takeIf { it.isNotBlank() }

    /**
     * Scam and phishing signals on an onion page. [host] is the page's onion host; [known] maps
     * known-good addresses to names, for impersonation (the page's title starts with a known
     * service's name but the address isn't that service's). A seed or private-key request reads
     * as a warning, not an ask, when "never"/"don't"/"not" comes just before it ("never enter your
     * seed phrase"). A page that quotes a doubling scam is flagged too: a false flag only leaves a
     * page out of the brief.
     */
    fun screenPage(html: String, host: String?, known: Map<String, String> = emptyMap()): Screen {
        val body = text(html)
        val signals = ArrayList<Signal>()
        val reasons = ArrayList<String>()
        val seed = (SEED_A.findAll(body) + SEED_B.findAll(body)).any { m ->
            // Only a negation in the same clause counts ("never enter…"); "Don't wait — enter…" still asks.
            val before = body.substring(maxOf(0, m.range.first - 30), m.range.first)
            !NEGATION.containsMatchIn(before.substring(before.indexOfLast { it in ".!?;:—–-" } + 1))
        }
        if (seed) { signals += Signal.SEED_REQUEST; reasons += Signal.SEED_REQUEST.words }
        val doubling = SEND_GET.containsMatchIn(body) ||
            ScamSignals.Sign.DOUBLING in ScamSignals.assess("", body.take(20_000)).signs
        if (doubling) { signals += Signal.DOUBLING; reasons += Signal.DOUBLING.words }
        val t = title(html)
        if (host != null && t != null) {
            val addr = address(host)
            known.entries.firstOrNull { (k, name) ->
                name.isNotBlank() && address(k) != addr &&
                    Regex("^" + Regex.escape(name) + "(?![\\p{L}\\d])", RegexOption.IGNORE_CASE).containsMatchIn(t.trim())
            }?.let { (_, name) ->
                signals += Signal.IMPERSONATION
                reasons += "calls itself $name but is not $name's known address"
            }
        }
        if (PASSWORD.containsMatchIn(html)) { signals += Signal.LOGIN_FORM; reasons += Signal.LOGIN_FORM.words }
        return Screen(signals, reasons)
    }

    /**
     * The verdict for [url] (null when it isn't an onion URL). [html] is the page when it was read
     * (null: judged on the address alone). [known] is the known-good set, address → name
     * ([DIRECTORY] plus the owner's own list, trusted the same).
     * - TRUSTED: the address is in [known].
     * - LIKELY_PHISH: a lookalike of a known address ([lookalikeOf]), or the page impersonates one.
     * - SCAM: the page asks for a seed phrase / private key or promises to double crypto.
     * - UNKNOWN: anything else, including unlisted-and-clean and malformed addresses. Never trusted.
     */
    fun classify(url: String, html: String?, known: Map<String, String>): Result? {
        val host = onionHost(url) ?: return null
        val addr = address(host)
        val knownAddr = known.mapKeys { address(it.key) }
        knownAddr[addr]?.let { name ->
            return Result(Verdict.TRUSTED, addr, listOf("known-good address" + if (name.isNotBlank()) " ($name)" else ""), name)
        }
        lookalikeOf(addr, knownAddr.keys)?.let { good ->
            val name = knownAddr[good].orEmpty().ifBlank { "a known-good service" }
            val d = levenshtein(addr.removeSuffix(".onion"), good.removeSuffix(".onion"))
            val why = if (d <= LOOKALIKE_MAX) "$d character${if (d == 1) "" else "s"} away from $name's known address"
                else "starts like $name's known address but isn't it"
            return Result(Verdict.LIKELY_PHISH, addr, listOf("likely phishing clone: $why"), name)
        }
        if (!isV3(addr)) return Result(Verdict.UNKNOWN, addr,
            listOf("not a valid v3 onion address (wrong length, characters or checksum)"))
        if (html == null) return Result(Verdict.UNKNOWN, addr, listOf("unlisted address, page not read — not verified"))
        val s = screenPage(html, host, knownAddr)
        return when (s.verdict) {
            Verdict.SCAM -> Result(Verdict.SCAM, addr, s.signals.filter { it.verdict == Verdict.SCAM }.map { it.words })
            Verdict.LIKELY_PHISH -> Result(Verdict.LIKELY_PHISH, addr, s.reasons.filter { it.startsWith("calls itself") })
            else -> Result(Verdict.UNKNOWN, addr, listOf("unlisted address, page screened clean — not verified") +
                (if (Signal.LOGIN_FORM in s.signals) listOf(Signal.LOGIN_FORM.words) else emptyList()))
        }
    }

    private val ONION_URL = Regex("""(?<![a-z0-9.-])(?:https?://)?(?:[a-z0-9-]+\.)*(?<![a-z0-9])[a-z2-7]{56}\.onion\b(?::\d{1,5})?(?:/[^\s"'<>)\]]*)?""", RegexOption.IGNORE_CASE)
    private val ONION_ADDR = Regex("""(?<![a-z0-9])([a-z2-7]{56})\.onion\b""", RegexOption.IGNORE_CASE)

    /** v3-shaped onion URLs written in [text] (a request, a page), in order; a bare address becomes http://. */
    fun urlsIn(text: String): List<String> = ONION_URL.findAll(text).map { m ->
        val u = m.value.trimEnd('.', ',', ';', ':', '!', '?')
        if (u.contains("://")) u else "http://$u"
    }.distinctBy { onionHost(it)?.let(::address) }.toList()

    /** v3-shaped onion addresses mentioned in [text], lowercased, in order. */
    fun addressesIn(text: String): List<String> =
        ONION_ADDR.findAll(text).map { it.groupValues[1].lowercase() + ".onion" }.distinct().toList()

    /** "duckduck…zczad.onion": enough to recognise, not a link to visit. */
    fun short(address: String): String {
        val l = address.removeSuffix(".onion")
        return if (l.length <= 14) address else l.take(8) + "…" + l.takeLast(5) + ".onion"
    }

    /** [text] with each of [addresses] shown [short] — a flagged address is never left as a link to visit. */
    fun redact(text: String, addresses: Collection<String>): String = addresses.fold(text) { t, a ->
        val label = address(a).removeSuffix(".onion")
        if (label.length <= 14) t else t.replace(label, short(address(a)).removeSuffix(".onion"), ignoreCase = true)
    }

    /**
     * The owner's own verified addresses from the text kept in Settings: one per line, the
     * address (or a URL) first, an optional name after it. Lines without a valid v3 address are
     * dropped — a wrong address is worse than none. Address → name, in order.
     */
    fun parseOwn(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            val first = parts.firstOrNull().orEmpty()
            val addr = onionHost(first)?.let(::address) ?: continue
            if (!isV3(addr)) continue
            out.putIfAbsent(addr, parts.getOrNull(1)?.trim().orEmpty().take(40))
        }
        return out
    }

    /** [parseOwn] written back, one "address name" per line: what Settings stores. */
    fun cleanOwn(text: String): String =
        parseOwn(text).entries.joinToString("\n") { (a, n) -> if (n.isBlank()) a else "$a $n" }

    /** The known-good set: [DIRECTORY] plus the owner's own list (an own name wins when given). */
    fun knownGood(ownText: String): Map<String, String> {
        val out = LinkedHashMap(DIRECTORY)
        for ((a, n) in parseOwn(ownText)) out[a] = n.ifBlank { out[a] ?: "your list" }
        return out
    }
}

/** SHA3-256 (FIPS 202), for the v3 onion checksum: Android has no SHA3 MessageDigest before API 34. Pure, JVM-tested. */
internal object Sha3 {
    private val RC = listOf(
        0x0000000000000001UL, 0x0000000000008082UL, 0x800000000000808AUL, 0x8000000080008000UL,
        0x000000000000808BUL, 0x0000000080000001UL, 0x8000000080008081UL, 0x8000000000008009UL,
        0x000000000000008AUL, 0x0000000000000088UL, 0x0000000080008009UL, 0x000000008000000AUL,
        0x000000008000808BUL, 0x800000000000008BUL, 0x8000000000008089UL, 0x8000000000008003UL,
        0x8000000000008002UL, 0x8000000000000080UL, 0x000000000000800AUL, 0x800000008000000AUL,
        0x8000000080008081UL, 0x8000000000008080UL, 0x0000000080000001UL, 0x8000000080008008UL,
    ).map { it.toLong() }.toLongArray()
    private val ROT = intArrayOf(1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44)
    private val PI = intArrayOf(10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1)
    private const val RATE = 136

    private fun keccakF(st: LongArray) {
        val bc = LongArray(5)
        for (round in 0 until 24) {
            for (i in 0 until 5) bc[i] = st[i] xor st[i + 5] xor st[i + 10] xor st[i + 15] xor st[i + 20]
            for (i in 0 until 5) {
                val t = bc[(i + 4) % 5] xor java.lang.Long.rotateLeft(bc[(i + 1) % 5], 1)
                for (j in 0 until 25 step 5) st[j + i] = st[j + i] xor t
            }
            var t = st[1]
            for (i in 0 until 24) {
                val j = PI[i]; val tmp = st[j]
                st[j] = java.lang.Long.rotateLeft(t, ROT[i]); t = tmp
            }
            for (j in 0 until 25 step 5) {
                for (i in 0 until 5) bc[i] = st[j + i]
                for (i in 0 until 5) st[j + i] = st[j + i] xor (bc[(i + 1) % 5].inv() and bc[(i + 2) % 5])
            }
            st[0] = st[0] xor RC[round]
        }
    }

    fun sha3256(input: ByteArray): ByteArray {
        val st = LongArray(25)
        val padded = input.size / RATE * RATE + RATE
        val msg = input.copyOf(padded)
        msg[input.size] = (msg[input.size].toInt() xor 0x06).toByte()
        msg[padded - 1] = (msg[padded - 1].toInt() xor 0x80).toByte()
        for (off in 0 until padded step RATE) {
            for (i in 0 until RATE / 8) {
                var lane = 0L
                for (b in 0 until 8) lane = lane or ((msg[off + i * 8 + b].toLong() and 0xFF) shl (8 * b))
                st[i] = st[i] xor lane
            }
            keccakF(st)
        }
        return ByteArray(32) { i -> (st[i / 8] ushr (8 * (i % 8))).toByte() }
    }
}
