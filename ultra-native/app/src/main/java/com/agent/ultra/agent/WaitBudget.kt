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

    fun maxSilentSeconds(tier: IntentRouter.Tier): Double = when (tier) {
        IntentRouter.Tier.DEVICE -> 4.0
        IntentRouter.Tier.LIGHT -> 4.0
        IntentRouter.Tier.NORMAL -> 20.0
        IntentRouter.Tier.DEEP -> 60.0
    }

    fun floorTokens(tier: IntentRouter.Tier): Int = when (tier) {
        IntentRouter.Tier.DEVICE, IntentRouter.Tier.LIGHT -> 16
        IntentRouter.Tier.NORMAL -> 64
        IntentRouter.Tier.DEEP -> 128
    }

    /** Think budget for [tier] at [tokPerSec] (null, NaN, infinite or ≤ 0 = unknown → the tier budget). */
    fun budget(tier: IntentRouter.Tier, tokPerSec: Double?): Int {
        val tierBudget = tier.thinkBudget
        if (tokPerSec == null || !tokPerSec.isFinite() || tokPerSec <= 0.0) return tierBudget
        val timed = kotlin.math.floor(tokPerSec * maxSilentSeconds(tier))
            .coerceAtMost(tierBudget.toDouble()).toInt()
        return maxOf(timed, floorTokens(tier)).coerceAtMost(tierBudget)
    }
}
