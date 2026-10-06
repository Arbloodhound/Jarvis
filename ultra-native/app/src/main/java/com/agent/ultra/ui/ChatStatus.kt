package com.agent.ultra.ui

/**
 * The transient "it is working" line under the chat while the on-device model is silent.
 *
 * Why: with the Qwen3 thinking MoE the first answer after opening the app waits for a
 * 10 GB load, and every answer then writes a hidden <think> trace (ThinkBudget) before
 * the first visible token. Both phases showed nothing at all.
 *
 * Never part of a [ChatMessage]: it is not persisted and never spoken.
 *
 * Pure, JVM-tested (ChatStatusTest).
 */
object ChatStatus {
    enum class Phase { LOADING, THINKING }

    /** One status episode. Identity matters: [ChatStore.clearStatus] only clears its own. */
    class Status(val phase: Phase, val sinceMs: Long)

    /** Elapsed seconds appear only once the wait is noticeable. */
    const val SHOW_SECONDS_AFTER_MS = 3_000L

    /** The line to show for [phase] after [elapsedMs]. */
    fun label(phase: Phase, elapsedMs: Long): String {
        val base = when (phase) {
            Phase.LOADING -> "Loading the brain…"
            Phase.THINKING -> "Thinking…"
        }
        return if (elapsedMs >= SHOW_SECONDS_AFTER_MS) "$base ${elapsedMs / 1000} s" else base
    }
}
