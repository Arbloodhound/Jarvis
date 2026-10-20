package com.agent.jarvis.agent

/**
 * Pure thermal policy (no Android deps, JVM-tested). Maps Android's thermal
 * headroom forecast + status to a level, with hysteresis so it doesn't flap.
 *
 * Headroom (PowerManager.getThermalHeadroom): 0.0 = cool, 1.0 = the point where
 * the OS starts SEVERE throttling. NaN = unsupported/not ready -> status only.
 */
object ThermalPolicy {

    enum class AmbientMode { NORMAL, SLOWED, WAKE_ONLY }

    // LLM threads per level come from threadsFor(level, base), not a fixed column here:
    // the full-speed count is the device's, not the policy's.
    // Ambient listening: whisper runs on the cores the LLM doesn't use (CpuTopology.whisperCpus),
    // so WARM leaves it alone, HOT runs it on one thread, and only CRITICAL pauses memory-only
    // capture (AmbientThrottle). WARM used to throttle it and HOT to drop overheard speech.
    enum class Level(val budgetScale: Float, val ambient: AmbientMode) {
        NORMAL(1.0f, AmbientMode.NORMAL),
        WARM(0.6f, AmbientMode.NORMAL),
        HOT(0.3f, AmbientMode.SLOWED),
        CRITICAL(0.0f, AmbientMode.WAKE_ONLY),   // device commands only
    }

    // Upward thresholds; coming back down needs HYST lower, so it doesn't oscillate.
    // getThermalHeadroom is a forecast scaled so that 1.0 is where the OS starts SEVERE
    // throttling; Android does not promise it is linear, nor that 0.0 is any particular state.
    // On the owner's Pixel 10 Pro XL a cool, idle phone reads ~0.58 (Feature check, 2026-10-06),
    // so the usable band is ~0.6..1.0, not 0..1. The old WARM at 0.65 sat 0.07 above idle and
    // cut threads and think budget at the first warm-up. Now: WARM 0.75 (~40% of the way from
    // idle to severe; it drops back below 0.70, still 0.12 above idle), HOT 0.87 (~70%; MODERATE
    // status also forces HOT, below), CRITICAL 0.95 unchanged (just short of SEVERE). One idle
    // reading from one phone: re-check against the next Feature check's "headroom=" line.
    const val WARM_AT = 0.75f
    const val HOT_AT = 0.87f
    const val CRIT_AT = 0.95f
    const val HYST = 0.05f

    // android.os.PowerManager.THERMAL_STATUS_* values (LIGHT is named for the tests: it sets no floor)
    const val STATUS_LIGHT = 1
    const val STATUS_MODERATE = 2
    const val STATUS_SEVERE = 3

    fun levelFor(headroom: Float, status: Int, prev: Level): Level {
        val fromHeadroom = if (headroom.isNaN()) Level.NORMAL else {
            fun at(up: Float, l: Level) = headroom >= (if (prev >= l) up - HYST else up)
            when {
                at(CRIT_AT, Level.CRITICAL) -> Level.CRITICAL
                at(HOT_AT, Level.HOT) -> Level.HOT
                at(WARM_AT, Level.WARM) -> Level.WARM
                else -> Level.NORMAL
            }
        }
        // The OS's own status is a floor from MODERATE up: there it is throttling, so we are.
        // LIGHT sets no floor: Android describes it as light throttling with no UI impact, and the
        // Pixel 10 Pro reported it from the third question of a brain test on. As a WARM floor it
        // cut the LLM's threads and think budget by itself; the headroom forecast above still
        // raises the level when the phone is really heating up.
        val fromStatus = when {
            status >= STATUS_SEVERE -> Level.CRITICAL
            status >= STATUS_MODERATE -> Level.HOT
            else -> Level.NORMAL
        }
        return maxOf(fromHeadroom, fromStatus)
    }

    private const val HOT_THREADS = 2

    /**
     * LLM threads at [level] when [base] is the full-speed count: NORMAL = base, WARM = two
     * thirds of it rounded (at least 2), HOT and CRITICAL = 2. Never above base, never below 1.
     * Base 4 gives 4/3/2/2, the table this replaced.
     */
    fun threadsFor(level: Level, base: Int): Int {
        val b = base.coerceAtLeast(1)
        val n = when (level) {
            Level.NORMAL -> b
            Level.WARM -> maxOf(HOT_THREADS, (2 * b + 1) / 3)
            Level.HOT, Level.CRITICAL -> HOT_THREADS
        }
        return n.coerceIn(1, b)
    }

    /**
     * The count one native generate call gets: the policy's for [level], or [requested] if the
     * caller chose fewer. Never 0 — the native shim reads 0 as "keep the last count", so one
     * HOT reply used to leave every later default call on 2 threads until a thinking call came.
     */
    fun resolveThreads(requested: Int, level: Level, base: Int): Int {
        val policy = threadsFor(level, base)
        return if (requested > 0) minOf(requested, policy) else policy
    }

    /** Scale a routed think budget. At CRITICAL only device-command-sized thinking remains. */
    fun scaleBudget(budget: Int, level: Level): Int =
        if (level == Level.CRITICAL) minOf(budget, IntentRouter.Tier.DEVICE.thinkBudget)
        else (budget * level.budgetScale).toInt()

    /** Said before a non-device answer at CRITICAL, so a short reply has a reason the person can act on. */
    const val COOLING_MESSAGE = "I'm running hot, so I'll keep this short until the phone cools down."

    /** The line to put before the answer, or null. Device commands stay silent: they are short anyway. */
    fun coolingNote(level: Level, tier: IntentRouter.Tier): String? =
        if (level == Level.CRITICAL && tier != IntentRouter.Tier.DEVICE) COOLING_MESSAGE else null
}
