package com.agent.ultra.listen

/**
 * Bounded mic mute while Ultra speaks. Replaces mutedUntil = Long.MAX_VALUE, which
 * left the mic deaf forever if TTS never fired its "done" callback (error, interrupt).
 * The done callback still unmutes early; this is the ceiling. Pure, JVM-tested.
 */
object EchoGuard {
    private const val CHARS_PER_SEC = 12.0     // ~140 wpm, slightly slow on purpose
    private const val MARGIN_MS = 2_000L
    private const val MAX_MS = 120_000L

    fun muteMsFor(text: String): Long =
        ((text.length / CHARS_PER_SEC) * 1000).toLong().plus(MARGIN_MS).coerceAtMost(MAX_MS)
}
