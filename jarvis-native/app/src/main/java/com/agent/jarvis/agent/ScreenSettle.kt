package com.agent.jarvis.agent

import kotlinx.coroutines.delay

/**
 * Waiting after a tap or an app launch until the screen has changed and gone quiet. Settled =
 * an accessibility event from the app arrived after the action, then none for [quietMs] (events are
 * batched every 200 ms, accessibility_service_config notificationTimeout). Never longer than [maxMs].
 */
object ScreenSettle {
    fun settled(nowMs: Long, sinceMs: Long, lastEventMs: Long, quietMs: Long, minMs: Long): Boolean =
        lastEventMs > sinceMs && nowMs - lastEventMs >= quietMs && nowMs - sinceMs >= minMs

    /** Waits until settled or [maxMs] after [sinceMs]; returns how long it waited (ms). */
    suspend fun await(
        sinceMs: Long,
        maxMs: Long,
        quietMs: Long = 300,
        minMs: Long = 150,
        pollMs: Long = 40,
        lastEvent: () -> Long = { com.agent.jarvis.AgentAccessibilityService.lastOtherAppEventUptime() },
        now: () -> Long = ::mark,
    ): Long {
        // The time slept counts too, so under a test's virtual clock the cap still ends the wait.
        var slept = 0L
        while (true) {
            val t = now()
            val waited = maxOf(t - sinceMs, slept)
            if (waited >= maxMs || settled(t, sinceMs, lastEvent(), quietMs, minMs)) return waited
            delay(pollMs)
            slept += pollMs
        }
    }

    /** Monotonic ms, the same clock the accessibility service stamps events with. */
    fun mark(): Long = System.nanoTime() / 1_000_000L
}
