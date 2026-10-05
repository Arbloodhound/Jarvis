package com.agent.ultra.listen

/**
 * Decides what an utterance IS, after it is transcribed. Everything is stored
 * as memory; only addressed speech is answered. Matching is on text, so the
 * expensive wake-word acoustics are avoided — whisper already ran.
 */
class WakeFilter(private val cfg: AmbientConfig) {

    sealed interface Verdict {
        /** Store only; do not answer. */
        object Ambient : Verdict
        /** Addressed to the agent; [command] is the request (may be blank → listen for follow-up). */
        data class Addressed(val command: String) : Verdict
    }

    private var followUpUntil = 0L

    fun classify(text: String, now: Long): Verdict {
        val name = cfg.wakeName.lowercase()
        val t = text.trim()
        val low = t.lowercase()

        if (now < followUpUntil) {           // we just heard a bare name; take this as the command
            followUpUntil = 0L
            return Verdict.Addressed(t)
        }

        val idx = findName(low, name)
        if (idx < 0) return Verdict.Ambient

        // Strip the name + one optional vocative comma, keep the remainder as the command.
        val after = t.substring(idx + name.length).trimStart(',', ' ', ':', '-')
        return if (after.length < 2) {
            followUpUntil = now + AmbientConfig.FOLLOWUP_MS
            Verdict.Addressed("")            // bare "Ultra" — wait for the next utterance
        } else {
            Verdict.Addressed(after)
        }
    }

    /** Word-boundary match so "ultraviolet" / "illustrator" don't trigger. */
    private fun findName(haystack: String, name: String): Int {
        var from = 0
        while (true) {
            val i = haystack.indexOf(name, from)
            if (i < 0) return -1
            val before = if (i == 0) ' ' else haystack[i - 1]
            val afterPos = i + name.length
            val after = if (afterPos >= haystack.length) ' ' else haystack[afterPos]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return i
            from = i + 1
        }
    }
}
