package com.agent.ultra.gate

/**
 * Origin tracking (port of gatellml lang/origins.py): every argument value
 * carries the attested sources it was derived from. Origins mint by WHOLE-TOKEN
 * membership against the operator's request text (tracesToRequest) — substring
 * membership let "ce@example.com" trace through "alice@example.com".
 */

private val EMAIL_RE = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
private val URL_RE = Regex("""https?://\S+""")
private val IBAN_RE = Regex("""[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}""")

private val SECRET_PATTERNS = listOf(
    Regex("""sk-[A-Za-z0-9_\-]{16,}"""),
    Regex("""AKIA[0-9A-Z]{16}"""),
    Regex("""(?i)password\s*[:=]\s*\S+"""),
    Regex("""(?i)(api[_-]?key|token|secret)\s*[:=]\s*\S{8,}"""),
    Regex("""\b[0-9a-f]{40,}\b""", RegexOption.IGNORE_CASE),
    Regex("""\b[A-Za-z0-9+/]{40,}={0,2}\b"""),
)

fun norm(s: String): String = s.replace(Regex("""\s+"""), " ").trim().lowercase()

private val SENTENCE_BREAK = Regex("""\.(?=[A-Z][a-z]+(?:[\s,;:!?)]|$))""")

/**
 * Normalise the TRUSTED side only. "site.com.They sent" is a sentence break with
 * the space typo'd away. Never run this on an argument: a repair heuristic applied
 * to attacker-influenced text is a tool for the attacker ("alice@example.com.Do"
 * repaired into "alice@example.com. do" traces through a real sentence break, and
 * example.com.do is registrable). The word must end cleanly, so
 * "John.Smith@example.com" is left whole.
 */
fun normRequest(s: String): String = norm(SENTENCE_BREAK.replace(s, ". "))

/** Argument names that hold a destination. */
val RECIPIENT_ARG_RE = Regex(
    "recipients?|to|b?cc|email|address|user(name)?|channel|url|iban|account|payee|contact|attendees?|participant|phone|number" +
        "|dest(ination)?|webhook|host|link|forward",
    RegexOption.IGNORE_CASE)

/**
 * Any host-shaped token: labels plus an alphabetic TLD, or an IPv4 literal. The old
 * pattern carried a 17-TLD list (evil.ru, evil.dev were invisible), used \b (which
 * fails next to "_", so markdown "_evil.com_" hid) and backtracked to a named prefix
 * ("mybank.com.ru" matched as "mybank.com"). Greedy, with hard boundaries.
 */
val DOMAIN_RE = Regex(
    """(?<![A-Za-z0-9.-])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,24}(?![A-Za-z0-9-]|\.[A-Za-z0-9])""" +
        """|(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.]|\.\d)""",
    RegexOption.IGNORE_CASE)

private val INVISIBLE = Regex("[\\t\\r\\n\u00ad\u200b-\u200f\u2028-\u202e\u2060-\u2064\ufeff]")

/** Characters that render as nothing or reorder text: a zero-width space inside
 * "alice@example.com<ZWSP>.evil.com" ends the visible atom while IDNA resolves the rest. */
fun hasInvisible(text: String): Boolean = INVISIBLE.containsMatchIn(text)

/**
 * Whole-token trace: [value]'s words must appear in the request as whole words,
 * in order. The one matching rule for the gate — Gate.saidByUser and every
 * contract go through it, so containment cannot creep back in at one call site.
 * Punctuation is trimmed from both sides, so "...open news.ycombinator.com."
 * still matches. Both sides are normalised here; norm is idempotent.
 */
fun tracesToRequest(value: String, requestNorm: String): Boolean {
    // A non-ASCII character that lowercases INTO ASCII is a disguise, not a letter: the
    // Kelvin sign U+212A lowercases to "k", so "mi\u212Ae@" would trace as "mike@". Found
    // by the shared vectors run ON THE PHONE: Android's ICU regex treats \w as Unicode and
    // read the whole address, where the desktop JVM (ASCII \w) happened to split it.
    if (value.any { it.code > 127 && it.lowercaseChar().code < 128 }) return false
    fun tokens(s: String) = s.split(' ')
        .map { it.trim('.', ',', ';', ':', '!', '?', '"', '\'', '(', ')') }
        .filter { it.isNotEmpty() }
    val want = tokens(norm(value))
    val said = tokens(norm(requestNorm))
    if (want.isEmpty() || want.size > said.size) return false
    for (i in 0..(said.size - want.size)) {
        if (want.indices.all { said[i + it] == want[it] }) return true
    }
    return false
}

private val VERBS = setOf(
    "send", "create", "delete", "remove", "update", "add", "get", "set", "cancel", "reschedule",
    "append", "share", "post", "open", "to", "from", "new", "by", "the", "a", "an", "of", "in", "for")
private val ID_WORDS = setOf("id", "ids", "identifier", "#")
private val NUMBER_WORDS = setOf("number", "no", "nr", "num")      // only after the noun: "file number 5"
private val FILLERS = setOf("is", "of", "the", "with", ":")
private val NOUN_SYNONYMS = mapOf(
    "file" to listOf("document", "doc"), "event" to listOf("meeting", "appointment"),
    "email" to listOf("message", "mail"), "transaction" to listOf("payment", "transfer"),
    "sms" to listOf("text", "message"))
private val ORDINAL = Regex("""^\d+(st|nd|rd|th)$""", RegexOption.IGNORE_CASE)
private val REQUEST_TOKEN = Regex("""#|[a-z]+|\d+|[.!?;:]""")

/** sms_send -> {sms}; delete_file -> {file}. */
fun objectNouns(toolName: String): Set<String> =
    toolName.lowercase().split('_').filter { it.isNotEmpty() && it !in VERBS }.toSet()

fun isIdArg(name: String): Boolean =
    name.lowercase().let { it == "id" || it.endsWith("_id") || it.endsWith("_ids") }

private fun asciiDigits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

/** For an id-like argument: a number-ish value ('13', ' 5 ', '#5', '3.0',
 * '2024-05-15', '15th', non-ASCII digits) must be NAMED as an id, never matched
 * as free text. 'notes.txt' is free text. */
fun idNeedsNaming(value: String): Boolean {
    val v = value.trim()
    return v.isNotEmpty() && (v.none { it.isLetter() } || ORDINAL.matches(v))
}

/**
 * A bare number is only a target if the request names it AS an id. Whole-token
 * tracing let "3 pm" license delete_file("3"); a loose "near the noun" window
 * still let "I have no 5 star reviews" license file 5 and blocked "files 3, 4 and
 * 5". The number must directly follow the tool's object noun ("file 13"), an id
 * word ("with ID '13'", "whose identifier is 13"), or "<noun> number N". A list is
 * followed back to its head. A sentence break ends the match.
 */
fun numericNamed(value: String, toolName: String, requestNorm: String): Boolean {
    if (!asciiDigits(value)) return false
    val nouns = objectNouns(toolName).toMutableSet()
    for (n in nouns.toList()) nouns += NOUN_SYNONYMS[n].orEmpty()
    nouns += nouns.map { it + "s" }
    val toks = REQUEST_TOKEN.findAll(norm(requestNorm)).map { it.value }.toList()
    for (i in toks.indices) {
        if (toks[i] != value) continue
        var j = i - 1
        while (j >= 0 && (asciiDigits(toks[j]) || toks[j] == "and" || toks[j] == "or")) j--
        if (j >= 1 && toks[j] == "." && toks[j - 1] in NUMBER_WORDS) j--
        if (j < 0) continue
        if (toks[j] in nouns || toks[j] in ID_WORDS) return true
        var k = j
        while (k >= 0 && toks[k] in FILLERS) k--
        if (k >= 0 && k != j && toks[k] in ID_WORDS) return true
        if (k >= 1 && toks[k] in NUMBER_WORDS && toks[k - 1] in nouns) return true
    }
    return false
}

private fun squash(s: String) = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

private fun rot13(s: String) = s.map { c ->
    when (c) {
        in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
        in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
        else -> c
    }
}.joinToString("")

/**
 * Does outbound text carry this secret? Literal containment is beaten by one
 * inserted space. Compare on letters and digits only, and try the disguises a
 * model can produce without a tool: reversed, rot13, base64, hex, percent-
 * encoding, and the vendor prefix ("sk-") dropped. Raises the cost; not a proof.
 */
fun leaks(secret: String, outbound: String): Boolean {
    val sq = squash(secret)
    if (sq.length < 8) return false
    val raw = secret.toByteArray(Charsets.UTF_8)
    val forms = mutableSetOf(sq, sq.reversed(), squash(rot13(secret)),
        squash(java.util.Base64.getEncoder().encodeToString(raw)),
        raw.joinToString("") { "%02x".format(it) })
    val core = secret.replace(Regex("""^[A-Za-z]{2,6}[-_]"""), "")
    if (core != secret && squash(core).length >= 12) forms += squash(core)
    val decoded = try { java.net.URLDecoder.decode(outbound, "UTF-8") } catch (e: Exception) { outbound }
    val blobs = setOf(squash(outbound), squash(decoded), squash(decodedB64Runs(outbound)))
    if (forms.any { f -> f.isNotEmpty() && blobs.any { f in it } }) return true
    if (sq.length >= 16) {
        // a split secret: one of two pieces is always at least half of it
        val w = (sq.length + 1) / 2
        return (0..sq.length - w).any { i -> val piece = sq.substring(i, i + w); blobs.any { piece in it } }
    }
    return false
}

private val B64_RUN = Regex("""[A-Za-z0-9+/]{16,}={0,2}""")

/** Every base64-looking run, decoded: base64 of "key: <secret>" shifts the alignment, so
 * base64 of the secret alone never appears; decoding what is there finds it. */
private fun decodedB64Runs(text: String): String = B64_RUN.findAll(text.take(20000)).mapNotNull { m ->
    val run = m.value.trimEnd('=')
    val padded = run + "=".repeat((4 - run.length % 4) % 4)
    try { String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8) } catch (e: IllegalArgumentException) { null }
}.joinToString(" ")

/** Origin markers. v0 uses two: User (verbatim in the request) and
 * ToolOrigin (everything else). RequestSpan exists for the future resolve
 * channel — see gatellml SPEC section 2 R4. */
sealed interface Origin
object UserOrigin : Origin
data class ToolOrigin(val tool: String = "<untracked>") : Origin
data class RequestSpan(val mention: String) : Origin

data class OriginSet(
    val items: Set<Origin> = emptySet(),
    val taintHit: Boolean = false,
) {
    fun union(other: OriginSet) = OriginSet(items + other.items, taintHit || other.taintHit)

    fun hasUser() = items.any { it is UserOrigin }

    /** None = admissible. Mirrors origins.py OriginSet.satisfies. */
    fun satisfies(requestNorm: String): String? {
        for (o in items) {
            when (o) {
                is UserOrigin -> continue
                is RequestSpan -> {
                    if (tracesToRequest(o.mention, requestNorm)) continue
                    return "resolved entity '${o.mention}' does not trace to the user's request"
                }
                is ToolOrigin -> return "value originates from tool output, not from the user's request"
            }
        }
        return null
    }
}

fun extractAtoms(text: String): List<String> =
    (EMAIL_RE.findAll(text) + URL_RE.findAll(text) + IBAN_RE.findAll(text))
        .map { it.value }.toList()

fun findSecrets(text: String): List<String> {
    val found = mutableListOf<String>()
    for (pat in SECRET_PATTERNS) {
        for (m in pat.findAll(text)) {
            var s = m.value
            if (Regex("(?i)password|api|token|secret").containsMatchIn(s)) {
                s = s.split(Regex("[:=]"), limit = 2).last().trim()
            }
            if (s.length >= 8) found += s
        }
    }
    return found
}
