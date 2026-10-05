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
        val t = text.trim()

        if (now < followUpUntil) {           // we just heard a bare name; take this as the command
            followUpUntil = 0L
            return Verdict.Addressed(t)
        }

        val command = addressedCommand(t, cfg.wakeName) ?: return Verdict.Ambient
        return if (command.length < 2) {
            followUpUntil = now + AmbientConfig.FOLLOWUP_MS
            Verdict.Addressed("")            // bare "Hey Jarvis" — wait for the next utterance
        } else {
            Verdict.Addressed(command)
        }
    }

    companion object {
        /** Words that may precede the name in a wake phrase ("hey jarvis", "ok jarvis"). */
        val WAKE_PREFIXES = setOf("hey", "ok", "okay")

        private fun wakeHead(names: List<String>): Regex {
            val prefixes = WAKE_PREFIXES.joinToString("|")
            return Regex(
                "^\\W*(?:(?:$prefixes)\\W+)?(?:" + names.joinToString("|") { Regex.escape(it) } +
                    ")(?![\\p{L}\\p{N}])[\\s,.:;!?-]*",
                RegexOption.IGNORE_CASE,
            )
        }

        /**
         * Text-match wake: the utterance must START with the name, optionally after
         * "hey" / "ok" / "okay" ("Hey Jarvis, ...", "Jarvis, ..."). A name later in a
         * sentence ("I asked Jarvis yesterday") is just conversation. Returns the
         * command after the wake phrase ("" for a bare wake phrase), or null when not
         * addressed.
         */
        fun addressedCommand(text: String, name: String): String? {
            val n = name.trim()
            if (n.isEmpty()) return null
            val t = text.trim()
            val m = wakeHead(listOf(n)).find(t) ?: return null
            return t.substring(m.range.last + 1).trim()
        }

        /**
         * Removes a leading wake phrase ("Hey Jarvis," / "Ultra:") from a transcript and
         * returns what is left — the command, or "" if the utterance was only the wake
         * phrase. A lone "Hey <word>" is also treated as wake-only: whisper often
         * mishears the name ("Hey, Jervis."), and the acoustic gate already said it fired.
         */
        fun stripWakePhrase(text: String, names: List<String>): String {
            val t = text.trim()
            val alts = names.map { it.trim() }.filter { it.isNotEmpty() }
            if (alts.isEmpty()) return t
            val prefixes = WAKE_PREFIXES.joinToString("|")
            val rest = wakeHead(alts).replaceFirst(t, "").trim()
            val loneGreeting = Regex("^(?:$prefixes)\\W+[\\p{L}']+\\W*$", RegexOption.IGNORE_CASE)
            return if (loneGreeting.matches(rest)) "" else rest
        }
    }
}
