package com.agent.ultra.agent

/**
 * Bound the hidden think budget by WAIT TIME as well as by tokens. Pure, JVM-tested.
 *
 * The trace is hidden ([ThinkBudget] strips it), so every thinking token is silence for the
 * person. Pixel 10 Pro, Qwen3-30B-A3B MoE IQ2_M: "hi" = 193 tokens at 1.6 tok/s, ~160 of them
 * thinking, i.e. ~100 s of nothing. A token budget alone can't protect against a slow phone:
 *
 *   budget = min(tierBudget, max(floorTokens, floor(tokPerSec × maxSilentSeconds)))
 *
 * Unknown speed (no measured generation yet for this model) → the tier budget, as before.
 *
 * Max silent waits (thinking time before the first visible word):
 * - DEVICE 4 s, LIGHT 4 s: a command or a "hi" that is silent longer than a few seconds
 *   feels broken; people expect a reply at roughly conversational turn-taking speed.
 * - NORMAL 20 s: an ordinary question; the "Thinking… N s" status line is visible, and 20 s
 *   is about as long as people wait for a considered answer before re-asking.
 * - DEEP 60 s: the person asked for depth ("explain", "step by step"); a minute is the upper
 *   end of what is acceptable even then.
 *
 * Floors: below them the model has no room to orient and the forced wrap-up ("Time is
 * short…") lands before any useful thought, so the answer degrades more than the wait
 * improves. 16 tokens (DEVICE/LIGHT) is enough to pick a tool or a greeting; 64 (NORMAL) and
 * 128 (DEEP) leave a few sentences of planning. A floor never lifts the budget above the
 * tier budget. On a very slow phone the floor wins (16 tokens at 1.6 tok/s ≈ 10 s): that is
 * the price of an answer that still makes sense.
 */
object WaitBudget {

    /**
     * Settings → "Thinking depth". The caps above are [QUICK]; the others raise them for
     * NORMAL and DEEP requests only (a command or a greeting stays quick at every depth):
     *
     * | depth    | NORMAL wait / tokens | DEEP wait / tokens | WARM phone        |
     * |----------|----------------------|--------------------|-------------------|
     * | QUICK    | 20 s / 512           | 60 s / 1200        | x0.6 (as before)  |
     * | BALANCED | 45 s / 1024          | 120 s / 2048       | x0.6              |
     * | DEEP     | 180 s / 3072         | 300 s / 6144       | not cut (HOT is)  |
     *
     * Qwen3-4B Thinking 2507 at the Pixel's 8-11 tok/s: QUICK NORMAL = ~200 tokens of thought,
     * BALANCED ~450, DEEP ~1800 (3 min). The model card advises far longer traces (32k tokens)
     * for hard problems; the phone's context and the wait are the limits here. Every budget
     * is still clipped to the context ([ThinkBudget.clipToContext]), and the chat's
     * "Answer now" button ends the thinking at any point ([ThinkBudget.answerNow]).
     */
    enum class Depth { QUICK, BALANCED, DEEP }

    fun maxSilentSeconds(tier: IntentRouter.Tier): Double = maxSilentSeconds(tier, Depth.QUICK)

    fun maxSilentSeconds(tier: IntentRouter.Tier, depth: Depth): Double = when (tier) {
        IntentRouter.Tier.DEVICE -> 4.0
        IntentRouter.Tier.LIGHT -> 4.0
        IntentRouter.Tier.NORMAL -> when (depth) { Depth.QUICK -> 20.0; Depth.BALANCED -> 45.0; Depth.DEEP -> 180.0 }
        IntentRouter.Tier.DEEP -> when (depth) { Depth.QUICK -> 60.0; Depth.BALANCED -> 120.0; Depth.DEEP -> 300.0 }
    }

    /** Token ceiling of [tier] at [depth]: the tier's own budget at QUICK, and for commands and
     *  greetings at every depth. */
    fun tokenCeiling(tier: IntentRouter.Tier, depth: Depth): Int = when (tier) {
        IntentRouter.Tier.DEVICE, IntentRouter.Tier.LIGHT -> tier.thinkBudget
        IntentRouter.Tier.NORMAL -> when (depth) { Depth.QUICK -> tier.thinkBudget; Depth.BALANCED -> 1024; Depth.DEEP -> 3072 }
        IntentRouter.Tier.DEEP -> when (depth) { Depth.QUICK -> tier.thinkBudget; Depth.BALANCED -> 2048; Depth.DEEP -> 6144 }
    }

    /** The heat cut: [ThermalPolicy.scaleBudget], except that DEEP keeps its budget on a WARM
     *  phone (x0.6 there was what made the 20 s / 60 s caps tinier still). HOT and CRITICAL
     *  cut at every depth. */
    fun scaleForHeat(budget: Int, level: ThermalPolicy.Level, depth: Depth): Int =
        if (depth == Depth.DEEP && level == ThermalPolicy.Level.WARM) budget
        else ThermalPolicy.scaleBudget(budget, level)

    fun floorTokens(tier: IntentRouter.Tier): Int = when (tier) {
        IntentRouter.Tier.DEVICE, IntentRouter.Tier.LIGHT -> 16
        IntentRouter.Tier.NORMAL -> 64
        IntentRouter.Tier.DEEP -> 128
    }

    /**
     * Think budget for [tier] at [tokPerSec] and [depth]: min(ceiling, max(floor, speed x wait)).
     * Unknown speed (null, NaN, infinite or ≤ 0: the first answer after a load) → the tier's own
     * budget at every depth, so an unmeasured slow model never gets a multi-minute trace.
     */
    fun budget(tier: IntentRouter.Tier, tokPerSec: Double?, depth: Depth = Depth.QUICK): Int {
        if (tokPerSec == null || !tokPerSec.isFinite() || tokPerSec <= 0.0) return tier.thinkBudget
        val ceiling = tokenCeiling(tier, depth)
        val timed = kotlin.math.floor(tokPerSec * maxSilentSeconds(tier, depth))
            .coerceAtMost(ceiling.toDouble()).toInt()
        return maxOf(timed, floorTokens(tier)).coerceAtMost(ceiling)
    }

    /** Settings value → [Depth]; anything unknown → [Depth.BALANCED] (the default). */
    fun depthOf(name: String?): Depth = Depth.values().firstOrNull { it.name == name } ?: Depth.BALANCED
}
