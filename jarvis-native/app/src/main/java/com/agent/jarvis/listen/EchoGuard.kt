package com.agent.jarvis.listen

/**
 * Bounded mic mute while Jarvis speaks. The done callback still unmutes early; this is the ceiling.
 */
object EchoGuard {
    private const val CHARS_PER_SEC = 12.0     // ~140 wpm
    private const val MARGIN_MS = 2_000L
    private const val MAX_MS = 120_000L

    fun muteMsFor(text: String): Long =
        ((text.length / CHARS_PER_SEC) * 1000).toLong().plus(MARGIN_MS).coerceAtMost(MAX_MS)
}
