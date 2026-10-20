package com.agent.jarvis.listen

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

    /** Keep listening for the request after a bare name until [untilMs] (Jarvis's "Yes?" took
     *  part of the window with the mic muted). */
    fun holdFollowUp(untilMs: Long) { followUpUntil = maxOf(followUpUntil, untilMs) }

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
        /** Words that may precede the name in a wake phrase ("hey jarvis", "ok jarvis", "hi jarvis";
         *  "hay" is whisper's spelling of a drawn-out "hey"). */
        val WAKE_PREFIXES = setOf("hey", "ok", "okay", "hi", "hay")

        /** Whisper's mishearings of "hey" before the name, seen on the phone: the Feature check's
         *  spoken "Hey Jarvis, what time is it?" came back as "I hate Jarvis. What time is it?"
         *  (ambient's own decode) and "I hate Joris." (2026-10-06 results file). Regex pieces. */
        private val MISHEARD_PREFIXES = listOf("i\\W+hate")

        /** Fillers whisper now keeps (WhisperNative.verbatimPrompt): "Um, hey Jarvis" still wakes,
         *  and "Hey Jarvis, um." is a bare wake (not the command "um."). */
        private const val FILLERS = "um+|uh+|uhm+|erm+|er|hmm+|mm+"

        private fun wakeHead(names: List<String>): Regex {
            val prefixes = (WAKE_PREFIXES + MISHEARD_PREFIXES).joinToString("|")
            return Regex(
                "^\\W*(?:(?:$FILLERS)\\W+)*(?:(?:$prefixes)\\W+)?(?:" + names.joinToString("|") { Regex.escape(it) } +
                    ")(?![\\p{L}\\p{N}])[\\s,.:;!?-]*" +
                    "(?:(?:$FILLERS)(?![\\p{L}\\p{N}])[\\s,.:;!?-]*)*",
                RegexOption.IGNORE_CASE,
            )
        }

        /**
         * Text-match wake: the utterance must START with the name, optionally after
         * "hey" / "ok" / "okay" / "hi" (or a known mishearing of "hey", [MISHEARD_PREFIXES])
         * ("Hey Jarvis, ...", "Jarvis, ..."). A name later in a
         * sentence ("I asked Jarvis yesterday") is just conversation. Returns the
         * command after the wake phrase ("" for a bare wake phrase), or null when not
         * addressed.
         */
        fun addressedCommand(text: String, name: String): String? {
            val n = name.trim()
            if (n.isEmpty()) return null
            val t = text.trim()
            val end = (wakeHead(listOf(n)).find(t)?.range?.last ?: nearHeadEnd(t, listOf(n))) ?: return null
            return t.substring(end + 1).trim()
        }

        /** Any wake word, then any word, then the usual separators and fillers; group 1 = the word. */
        private val NEAR_HEAD: Regex by lazy {
            val prefixes = (WAKE_PREFIXES + MISHEARD_PREFIXES).joinToString("|")
            Regex(
                "^\\W*(?:(?:$FILLERS)\\W+)*(?:$prefixes)\\W+([\\p{L}']+)(?![\\p{L}\\p{N}])[\\s,.:;!?-]*" +
                    "(?:(?:$FILLERS)(?![\\p{L}\\p{N}])[\\s,.:;!?-]*)*",
                RegexOption.IGNORE_CASE,
            )
        }

        /**
         * Last index of a wake head whose name whisper misspelt, or null: only right after a wake
         * word ("Hey Joris, what time is it?" — four of the Pixel's decodes of Jarvis's own
         * "Hey Jarvis", 2026-10-06, even with the name in whisper's prompt), and only a word that
         * [soundsLikeName]. A bare "Joris, …" with no "hey" before it is not a wake.
         */
        private fun nearHeadEnd(text: String, names: List<String>): Int? {
            val m = NEAR_HEAD.find(text) ?: return null
            val word = m.groupValues[1]
            return if (names.any { soundsLikeName(word, it) }) m.range.last else null
        }

        /**
         * Whether [word] is close enough to [name] to be whisper's misspelling of it: the same
         * first letter, at least 4 letters, length within 2 and at most 2 letter edits
         * (Joris / Jervis / Javis / Jarvus for Jarvis; not James, Doris or Travis). Pure.
         */
        fun soundsLikeName(word: String, name: String): Boolean {
            val w = word.lowercase().filter { it.isLetter() }
            val n = name.lowercase().filter { it.isLetter() }
            if (w.length < 4 || n.isEmpty() || w[0] != n[0] || kotlin.math.abs(w.length - n.length) > 2) return false
            return editDistance(w, n) <= 2
        }

        private fun editDistance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[b.length]
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
            val exact = wakeHead(alts).find(t)?.range?.last
            val rest = (if (exact != null) t.substring(exact + 1)
                else nearHeadEnd(t, alts)?.let { t.substring(it + 1) } ?: t).trim()
            val loneGreeting = Regex("^(?:$prefixes)\\W+[\\p{L}']+\\W*$", RegexOption.IGNORE_CASE)
            return if (loneGreeting.matches(rest)) "" else rest
        }
    }
}
