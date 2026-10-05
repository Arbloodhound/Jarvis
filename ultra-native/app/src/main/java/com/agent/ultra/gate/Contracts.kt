package com.agent.ultra.gate

import org.json.JSONObject

/**
 * Contracts (port of gatellml lang/contracts.py): the deliberately tiny,
 * decidable check fragment. Every check is O(size of argument); each returns
 * null on pass or a human-readable hint on violation.
 */

/** A bound argument: raw value plus its minted origin set. */
data class TrackedArg(val value: String, val origin: OriginSet)

interface Contract {
    /** null on pass, violation hint on failure. */
    fun check(bindings: Map<String, TrackedArg>, requestNorm: String): String?

    val name: String
    val arg: String?
}

data class OriginSubset(override val arg: String) : Contract {
    override val name = "origin_subset"
    override fun check(bindings: Map<String, TrackedArg>, requestNorm: String): String? {
        val v = bindings[arg] ?: return "missing argument '$arg'"
        return v.origin.satisfies(requestNorm)
    }
}

data class NotTainted(override val arg: String) : Contract {
    override val name = "not_tainted"
    override fun check(bindings: Map<String, TrackedArg>, requestNorm: String): String? {
        val v = bindings[arg] ?: return null   // an absent optional argument carries nothing to check
        if (v.origin.taintHit) return "argument '$arg' carries secret-shaped material"
        return null
    }
}

data class AtomInRequest(override val arg: String) : Contract {
    override val name = "atom_in_request"
    override fun check(bindings: Map<String, TrackedArg>, requestNorm: String): String? {
        val v = bindings[arg] ?: return null
        for (a in extractAtoms(v.value)) {
            if (!tracesToRequest(a, requestNorm)) return "target '${a.take(40)}' does not trace to the user's request"
        }
        return null
    }
}

data class LenCheck(override val arg: String, val minimum: Int) : Contract {
    override val name = "len"
    override fun check(bindings: Map<String, TrackedArg>, requestNorm: String): String? {
        val v = bindings[arg] ?: return "missing argument '$arg'"
        if (v.value.length < minimum) return "argument '$arg' too short"
        return null
    }
}

/** Recipient-named argument: extracted atoms (or the raw value when
 * non-atom-shaped) must trace to the request.
 *
 * One traced atom does not license the rest of the string. After the traced atoms
 * are blanked out, what remains may be a display name, but it may not hold another
 * address ("@"), another authority ("//"), or a host that does not trace. */
data class RecipientTraceable(override val arg: String) : Contract {
    override val name = "recipient_traceable"
    override fun check(bindings: Map<String, TrackedArg>, requestNorm: String): String? {
        val v = bindings[arg] ?: return null
        if (hasInvisible(v.value)) return "target contains invisible or control characters"
        // a required recipient given as "" names nobody (optional empties never get here)
        if (v.value.isBlank()) return "argument '$arg' names no target"
        val t = v.value.trim()
        if (t.length < 4 && t.all { it in '0'..'9' }) return "target '$t' is a bare small number, not a named recipient"
        val atoms = extractAtoms(v.value)
        val targets = if (atoms.isNotEmpty()) atoms else listOf(v.value)
        for (a in targets) {
            if (!tracesToRequest(a, requestNorm)) return "target '${a.take(40)}' does not trace to the user's request"
        }
        if (atoms.isNotEmpty()) {
            var rest = v.value
            for (a in atoms) rest = rest.replace(a, " ")
            if ('@' in rest || "//" in rest) return "target '${v.value.take(40)}' carries a second address behind a traced one"
            for (m in DOMAIN_RE.findAll(rest)) {
                if (!domainSaidByUser(m.value, requestNorm)) return "target '${m.value.take(40)}' does not trace to the user's request"
            }
        }
        return null
    }
}

/** Mutation rule: at least one declared argument must trace to the request.
 * Atom-level extension (same spirit as the research's display-name fix): a
 * paraphrased value also traces when an email/URL/domain token inside it
 * appears in the request. */
data class AnyArgTraceable(val args: List<String>) : Contract {
    override val name = "any_arg_traceable"
    override val arg: String? = null
    override fun check(bindings: Map<String, TrackedArg>, requestNorm: String): String? {
        for (a in args) {
            val v = bindings[a] ?: continue
            if (v.origin.satisfies(requestNorm) == null) return null
            // a bare number is decided by its origin (named next to its noun), never
            // by the free-text fallback
            if (isIdArg(a) && idNeedsNaming(v.value)) continue
            if (v.value.length >= 3 && tracesToRequest(v.value, requestNorm)) return null
            // Atom/domain-level trace for model paraphrases
            val tokens = extractAtoms(v.value) + DOMAIN_RE.findAll(v.value).map { stripWww(it.value) }
            if (tokens.any { domainSaidByUser(stripWww(it), requestNorm) }) return null
        }
        return "no mutation target traces to the user's request"
    }
}

/** The NAMED argument is the target and must itself trace. AnyArgTraceable lets
 * any one argument vouch for the call, so a date the user typed licenses a hotel
 * the attacker chose. Use where the policy author knows which argument is the target. */
data class TargetTraceable(override val arg: String) : Contract {
    override val name = "target_traceable"
    override fun check(bindings: Map<String, TrackedArg>, requestNorm: String): String? {
        val v = bindings[arg] ?: return "missing target argument '$arg'"
        if (v.origin.satisfies(requestNorm) == null) return null
        val strict = isIdArg(arg) && idNeedsNaming(v.value)
        if (!strict && v.value.isNotBlank() && tracesToRequest(v.value, requestNorm)) return null
        return "target '${v.value.take(40)}' does not trace to the user's request"
    }
}

/** Confirmation-spoof layer (parity with the regex gate): no argument may
 * claim prior user approval. */
class SpoofCheck : Contract {
    override val name = "spoof_check"
    override val arg: String? = null
    private val pat = Regex(
        """(user\s+(has\s+)?(\w+ly\s+)?(confirmed|approved|agreed|said\s+yes)|they\s+said\s+yes|confirmation\s*[:=]\s*(yes|true)|already\s+(confirmed|approved)|approved\s+by\s+the\s+user)""",
        RegexOption.IGNORE_CASE)
    override fun check(bindings: Map<String, TrackedArg>, requestNorm: String): String? {
        for ((k, v) in bindings) {
            if (pat.containsMatchIn(v.value)) return "argument '$k' claims prior user approval"
        }
        return null
    }
}

/** Every domain-shaped token in the argument (scheme-less included) must
 * appear in the request. Closes the scheme-less-payload gap. A leading
 * "www." is normalized away on both sides (www.google.com ≡ google.com). */
data class DomainInRequest(override val arg: String, val urlishOnly: Boolean = false) : Contract {
    override val name = "domain_in_request"
    override fun check(bindings: Map<String, TrackedArg>, requestNorm: String): String? {
        val v = bindings[arg] ?: return null
        for (m in DOMAIN_RE.findAll(v.value)) {
            // urlishOnly: the default contract for an undeclared egress argument checks
            // only hosts written like a link, so "recipe.docx" in a body is not a host
            val next = v.value.getOrNull(m.range.last + 1)
            if (urlishOnly && !(m.value.lowercase().startsWith("www.") || next == '/' || next == ':')) continue
            val dom = stripWww(m.value.lowercase())
            if (!domainSaidByUser(dom, requestNorm)) return "domain '${m.value}' does not trace to the user's request"
        }
        return null
    }
}

internal fun stripWww(d: String): String = d.removePrefix("www.")

/**
 * Whole-token domain trace. Containment let "bank.com" pass against a request
 * naming "mybank.com", and "mybank.co" against "mybank.com" — a different site
 * each time. The domain (or email/URL atom) must equal a request token, be the
 * host part of an address the user typed (alice@example.com names example.com),
 * or be the host of a URL the user typed.
 */
internal fun domainSaidByUser(token: String, requestNorm: String): Boolean {
    val want = stripWww(norm(token)).trimEnd('/')
    if (want.isEmpty()) return false
    if (tracesToRequest(want, requestNorm) || tracesToRequest("www.$want", requestNorm)) return true
    for (raw in norm(requestNorm).split(' ')) {
        val t = raw.trim('.', ',', ';', ':', '!', '?', '"', '\'', '(', ')')
        if (t.substringAfterLast('@', "") == want) return true
        val host = t.substringAfter("://", "").substringBefore('/').substringBefore('?')
        if (host.isNotEmpty() && stripWww(host) == want) return true
    }
    return false
}

fun contractsFromJson(arr: org.json.JSONArray?): List<Contract> {
    val out = mutableListOf<Contract>()
    if (arr == null) return out
    for (i in 0 until arr.length()) {
        val d: JSONObject = arr.getJSONObject(i)
        when (val kind = d.getString("kind")) {
            "origin_subset" -> out += OriginSubset(d.getString("arg"))
            "not_tainted" -> out += NotTainted(d.getString("arg"))
            "atom_in_request" -> out += AtomInRequest(d.getString("arg"))
            "recipient_traceable" -> out += RecipientTraceable(d.getString("arg"))
            "any_arg_traceable" -> {
                val argsArr = d.optJSONArray("args")
                val args = mutableListOf<String>()
                if (argsArr != null) for (j in 0 until argsArr.length()) args += argsArr.getString(j)
                out += AnyArgTraceable(args)
            }
            "target_traceable" -> out += TargetTraceable(d.getString("arg"))
            "domain_in_request" -> out += DomainInRequest(d.getString("arg"))
            "len" -> out += LenCheck(d.getString("arg"), d.optInt("minimum", 1))
            "spoof_check" -> out += SpoofCheck()
            else -> throw IllegalArgumentException("unknown contract kind: $kind")
        }
    }
    return out
}
