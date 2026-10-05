package com.agent.ultra.gate

import org.json.JSONObject

/**
 * Computes a 0–100 risk score for a gate decision. Higher = riskier.
 *
 * The score doesn't block anything yet — existing rules do that. The score
 * is logged to the audit file so real usage data can calibrate thresholds
 * before the score becomes enforcement.
 *
 * Signals and their weights are conservative defaults. The whole point of
 * the audit log export is to collect the data that replaces these guesses.
 */
object RiskScorer {

    data class Score(
        val total: Int,
        val effectTier: Int,
        val obsQuality: Int,
        val taintExposure: Int,
        val traceability: Int,
    ) {
        fun toJson() = JSONObject().apply {
            put("total", total)
            put("effect", effectTier)
            put("obs", obsQuality)
            put("taint", taintExposure)
            put("trace", traceability)
        }
    }

    /**
     * Score a tool call given the gate's view of the episode at decision time.
     *
     * @param spec the tool's manifest entry (effects, contracts)
     * @param observations the episode's observation log
     * @param secrets secrets observed this episode
     * @param args the tool call arguments
     * @param requestNorm the normalised user request (with confirmed targets)
     */
    fun score(
        spec: ToolSpec,
        observations: ObservationLog,
        secrets: List<String>,
        args: JSONObject,
        requestNorm: String,
    ): Score {
        val effectTier = effectScore(spec)
        val obsQuality = observationScore(observations)
        val taintExposure = taintScore(spec, secrets, args)
        val traceability = traceScore(args, requestNorm)

        val raw = effectTier + obsQuality + taintExposure + traceability
        return Score(
            total = raw.coerceIn(0, 100),
            effectTier = effectTier,
            obsQuality = obsQuality,
            taintExposure = taintExposure,
            traceability = traceability,
        )
    }

    private fun effectScore(spec: ToolSpec): Int = when {
        Effect.EGRESS in spec.effects -> 40
        Effect.MUTATE in spec.effects -> 20
        Effect.RESOLVE in spec.effects -> 10
        else -> 0
    }

    private fun observationScore(obs: ObservationLog): Int {
        val toolObs = obs.toolObservations()
        return when {
            toolObs.isEmpty() -> 5
            obs.hasLowToolOnly() -> 25
            obs.hasHighToolObservation() -> 0
            else -> 10
        }
    }

    private fun taintScore(spec: ToolSpec, secrets: List<String>, args: JSONObject): Int {
        if (Effect.EGRESS !in spec.effects) return 0
        if (secrets.isEmpty()) return 0
        val blob = args.keys().asSequence()
            .joinToString(" ") { args.opt(it)?.toString() ?: "" }
        return if (secrets.any { it in blob }) 25 else 0
    }

    private fun traceScore(args: JSONObject, requestNorm: String): Int {
        if (args.length() == 0) return 0
        val untracedCount = args.keys().asSequence().count { key ->
            val v = args.opt(key)?.toString() ?: return@count false
            if (v.length <= 5) return@count false
            !requestNorm.contains(norm(v))
        }
        return when {
            untracedCount == 0 -> 0
            untracedCount == 1 -> 10
            else -> 20
        }
    }
}
