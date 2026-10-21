package com.agent.jarvis.ui

/**
 * The transient "it is working" line under the chat while the on-device model is silent.
 *
 * Never part of a [ChatMessage]: it is not persisted and never spoken.
 *
 * Pure, JVM-tested (ChatStatusTest).
 */
object ChatStatus {
    /** NAVIGATING: the on-device model is driving another app ([com.agent.jarvis.agent.OfflineNavigator]);
     * the chat shows a Stop button while this phase is up. */
    enum class Phase { LOADING, THINKING, NAVIGATING }

    /** One status episode. Identity matters: [ChatStore.clearStatus] only clears its own.
     * [detail] (e.g. "step 3/12") follows the phase text when non-blank. */
    class Status(val phase: Phase, val sinceMs: Long, val detail: String = "")

    /** The thinking trace a status carries ("💭 …words"), or null for any other status. */
    fun thoughtOf(s: Status): String? =
        if (s.phase == Phase.THINKING && s.detail.startsWith("💭"))
            s.detail.removePrefix("💭").trim().trimStart('…', ' ').ifBlank { null }
        else null

    /** Elapsed seconds appear only once the wait is noticeable. */
    const val SHOW_SECONDS_AFTER_MS = 3_000L

    /** The line to show for [phase] after [elapsedMs]. */
    fun label(phase: Phase, elapsedMs: Long, detail: String = ""): String {
        val text = when (phase) {
            Phase.LOADING -> "Loading the brain…"
            Phase.THINKING -> "Thinking…"
            Phase.NAVIGATING -> "Working on the phone…"
        }
        val base = if (detail.isBlank()) text else "$text ${detail.trim()}"
        return if (elapsedMs >= SHOW_SECONDS_AFTER_MS) "$base ${elapsedMs / 1000} s" else base
    }
}
