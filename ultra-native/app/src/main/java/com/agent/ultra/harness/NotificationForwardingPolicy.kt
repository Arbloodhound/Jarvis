package com.agent.ultra.harness

/*
 * MIT License — Copyright (c) 2026 OpenClaw Foundation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/**
 * Three-gate notification filter. A notification reaches the agent only if
 * it passes ALL three gates; anything else is dropped silently.
 *
 * Gate 1 — package: allowlist (or blocklist) over package names. The app's
 *          own package is always excluded.
 * Gate 2 — quiet hours: overnight windows handled in local time.
 * Gate 3 — burst limit: fixed per-minute window, per app.
 *
 * Adapted from OpenClaw's NotificationForwardingPolicy.kt
 * (https://github.com/openclaw/openclaw — apps/android/.../NotificationForwardingPolicy.kt).
 * The repo's UltraNotificationService has allow/blocklist + capture toggle
 * but no quiet hours, no rate limiting, no own-package exclusion; wire this
 * policy in front of it.
 */
data class IncomingNotification(
    val packageName: String,
    val title: String,
    val text: String = "",
    val postedAtMs: Long = System.currentTimeMillis(),
)

class NotificationForwardingPolicy(
    /** allowlist mode when non-empty; otherwise blocklist mode */
    private val allowedPackages: Set<String> = emptySet(),
    private val blockedPackages: Set<String> = emptySet(),
    private val ownPackage: String,
    private val quietStartHour: Int = 22,
    private val quietEndHour: Int = 7,
    private val maxPerMinutePerApp: Int = 5,
) {
    // app -> (windowMinute, count)
    private val buckets = mutableMapOf<String, Pair<Long, Int>>()

    private fun inQuietHours(nowMs: Long): Boolean {
        val h = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
            .get(java.util.Calendar.HOUR_OF_DAY)
        return if (quietStartHour <= quietEndHour) h in quietStartHour until quietEndHour
        else h >= quietStartHour || h < quietEndHour
    }

    private fun burstOk(pkg: String, nowMs: Long): Boolean {
        val window = nowMs / 60_000L
        val (start, count) = buckets[pkg] ?: (window to 0)
        val (w, c) = if (start != window) window to 0 else start to count
        if (c >= maxPerMinutePerApp) return false
        buckets[pkg] = w to c + 1
        return true
    }

    /** True only if all three gates pass. */
    fun shouldForward(n: IncomingNotification): Boolean {
        if (n.packageName == ownPackage) return false
        if (allowedPackages.isNotEmpty()) {
            if (n.packageName !in allowedPackages) return false
        } else if (n.packageName in blockedPackages) {
            return false
        }
        if (inQuietHours(n.postedAtMs)) return false
        if (!burstOk(n.packageName, n.postedAtMs)) return false
        return true
    }

    fun filter(all: List<IncomingNotification>) = all.filter(::shouldForward)
}
