package com.agent.jarvis.agent

/**
 * The app the user was really in before Jarvis came forward.
 *
 * "What's on my screen", asked in Jarvis's own chat, read only Jarvis: the read rightly refuses
 * Jarvis's window (`BLOCKED self-read`), so the answer was "the screen was empty" (issue #17,
 * phone DIAG 2026-10-20). The screen the person means is the app under the chat. A dictation
 * overlay (Wispr Flow) and the like flash in front for a few hundred milliseconds while typing
 * into Jarvis; those never count, so an app counts only once it has held the front [minMs].
 *
 * Pure (time is passed in), so it is tested off the phone.
 */
class ForegroundHistory(private val self: String, private val minMs: Long = 1500) {
    private var current: String? = null
    private var since = 0L

    /** The last app other than Jarvis that held the front at least [minMs], or null. */
    @Volatile var lastOther: String? = null
        private set

    @Synchronized fun onForeground(pkg: String, nowMs: Long) {
        if (pkg == current) return
        val prev = current
        if (prev != null && prev != self && nowMs - since >= minMs) lastOther = prev
        current = pkg
        since = nowMs
    }
}
