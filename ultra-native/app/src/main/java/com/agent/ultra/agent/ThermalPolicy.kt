package com.agent.ultra.agent

/**
 * Pure thermal policy (no Android deps, JVM-tested). Maps Android's thermal
 * headroom forecast + status to a level, with hysteresis so it doesn't flap.
 *
 * Headroom (PowerManager.getThermalHeadroom): 0.0 = cool, 1.0 = the point where
 * the OS starts SEVERE throttling. NaN = unsupported/not ready -> status only.
 */
object ThermalPolicy {

    enum class AmbientMode { NORMAL, SLOWED, WAKE_ONLY }

    enum class Level(val threads: Int, val budgetScale: Float, val ambient: AmbientMode) {
        NORMAL(4, 1.0f, AmbientMode.NORMAL),
        WARM(3, 0.6f, AmbientMode.SLOWED),
        HOT(2, 0.3f, AmbientMode.WAKE_ONLY),
        CRITICAL(2, 0.0f, AmbientMode.WAKE_ONLY),   // device commands only
    }

    // upward thresholds; coming back down needs HYST lower, so it doesn't oscillate
    private const val WARM_AT = 0.65f
    private const val HOT_AT = 0.80f
    private const val CRIT_AT = 0.95f
    private const val HYST = 0.05f

    // android.os.PowerManager.THERMAL_STATUS_* values
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
        // the OS's own status is a floor: if it says we're throttling, we are
        val fromStatus = when {
            status >= STATUS_SEVERE -> Level.CRITICAL
            status >= STATUS_MODERATE -> Level.HOT
            status >= STATUS_LIGHT -> Level.WARM
            else -> Level.NORMAL
        }
        return maxOf(fromHeadroom, fromStatus)
    }

    /** Scale a routed think budget. At CRITICAL only device-command-sized thinking remains. */
    fun scaleBudget(budget: Int, level: Level): Int =
        if (level == Level.CRITICAL) minOf(budget, IntentRouter.Tier.DEVICE.thinkBudget)
        else (budget * level.budgetScale).toInt()

    /** Non-device requests at CRITICAL get this instead of a long generation. */
    const val COOLING_MESSAGE = "I'm running hot, so I'll keep this short until the phone cools down."
}
