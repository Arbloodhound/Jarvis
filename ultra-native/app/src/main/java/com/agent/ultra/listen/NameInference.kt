package com.agent.ultra.listen

/**
 * Works out who an unnamed voice ("Speaker 2") is from what is said (owner, 2026-10-07: "infer
 * people's names based on what it hears and what I tell it"). No model, only the transcript and
 * the voices: Pure, JVM-tested.
 *
 * Evidence for voice V being NAME:
 *  - V introduces itself: "I'm Sam", "my name is Sam", "this is Sam", "it's Sam here" ([SELF], 3 points);
 *  - another voice addresses NAME ("Thanks, Sam", "Sam, are you coming?") and V is the next voice
 *    to speak within [REPLY_MS] ([VOCATIVE], 1 point).
 * A voice gets a name when its best name has at least [MIN_SCORE] points and at least twice the
 * next one's; never the owner's voice, a voice that has a name already, a name another voice has
 * (joining two voices is the owner's call), or the owner's own name.
 */
object NameInference {
    const val MIN_SCORE = 3
    const val SELF = 3
    const val VOCATIVE = 1
    const val REPLY_MS = 15_000L

    /** One heard line with its voice ([voice] 0 = no match). */
    data class Line(val voice: Long, val owner: Boolean, val named: Boolean, val ts: Long, val text: String)

    private const val NAME = "([A-Z][a-z]{1,15}(?:-[A-Z][a-z]{1,15})?)"
    private val SELF_RX = Regex("\\b(?:I'm|I am|my name is|my name's|this is|it's|call me)\\s+$NAME\\b")
    private val VOC_START = Regex("^(?:(?i:hey|hi|hello|thanks|thank you|cheers|oi|right|okay|ok|so|and|yes|no|yeah)[,!]?\\s+)?$NAME[,!?]")
    private val VOC_END = Regex(",\\s*$NAME\\s*[.!?]*$")

    /** Capitalised words that are not names (sentence starts, greetings, days, our own labels). */
    private val NOT_NAMES = setOf(
        "I", "I'm", "Me", "My", "The", "This", "That", "It", "Its", "It's", "Okay", "Ok", "Yeah", "Yes", "No", "Hey", "Hi",
        "Hello", "Thanks", "Thank", "Sorry", "Well", "So", "Oh", "Right", "Sure", "Fine", "Good", "Great", "Cool",
        "Jarvis", "God", "Lord", "Sir", "Madam", "Mate", "Bro", "Dude", "Guys", "Everyone", "Everybody", "Man",
        "Speaker", "Unknown", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
        "November", "December", "Just", "Not", "Here", "There", "Done", "Ready", "Back", "Going", "Coming",
        "Please", "What", "Why", "Who", "When", "Where", "How", "Look", "Listen", "Wait", "Come", "Stop", "Now",
    )

    /** Words after "speaker 2 is …" that describe, not name (a typed name may be lower case). */
    private val NOT_TOLD = setOf(
        "loud", "quiet", "here", "there", "gone", "back", "right", "wrong", "nice", "rude", "funny", "annoying", "mine",
        "home", "busy", "tired", "done", "fine", "good", "bad", "new", "old", "late", "early", "away", "out", "in", "on",
        "off", "sure", "me", "him", "her", "them", "you", "a", "an", "the", "not", "who", "what", "someone", "nobody",
        "angry", "upset", "happy", "sad", "lying", "talking", "speaking", "wrong", "crazy", "weird", "fast", "slow",
    )

    private fun ok(name: String) = name !in NOT_NAMES && CueTags.cleanName(name) != null

    /** Points per (voice, name) from [lines], oldest first. */
    fun evidence(lines: List<Line>): Map<Long, Map<String, Int>> {
        val score = HashMap<Long, HashMap<String, Int>>()
        fun add(v: Long, name: String, pts: Int) { score.getOrPut(v) { HashMap() }.merge(name, pts, Int::plus) }
        for ((i, l) in lines.withIndex()) {
            val text = l.text.replace(Regex("\\[[^\\]]*\\]"), " ").replace(Regex("\\s+"), " ").trim()
            if (l.voice > 0 && !l.owner) SELF_RX.findAll(text).map { it.groupValues[1] }.filter(::ok).forEach { add(l.voice, it, SELF) }
            val called = listOfNotNull(VOC_START.find(text)?.groupValues?.get(1), VOC_END.find(text)?.groupValues?.get(1))
                .firstOrNull(::ok) ?: continue
            // the next voice to speak, other than this one, within REPLY_MS
            val reply = lines.subList(i + 1, lines.size).firstOrNull { it.voice > 0 && it.voice != l.voice }
                ?.takeIf { it.ts - l.ts <= REPLY_MS } ?: continue
            if (!reply.owner) add(reply.voice, called, VOCATIVE)
        }
        return score
    }

    /** Names to give: voice id → name, under the rules above. [taken]: names voices have already. */
    fun infer(lines: List<Line>, taken: Set<String>, ownerName: String): Map<Long, String> {
        val named = lines.filter { it.named || it.owner }.map { it.voice }.toSet()
        val takenLower = taken.map { it.lowercase() }.toSet() + ownerName.trim().lowercase()
        val out = HashMap<Long, String>()
        for ((voice, names) in evidence(lines)) {
            if (voice in named) continue
            val ranked = names.entries.sortedByDescending { it.value }
            val best = ranked.first()
            val second = ranked.getOrNull(1)?.value ?: 0
            if (best.value < MIN_SCORE || best.value < 2 * second) continue
            if (best.key.lowercase() in takenLower || best.key in out.values) continue
            out[voice] = best.key
        }
        return out
    }

    // ── What the owner tells Jarvis ───────────────────────────────────────────────────────

    sealed interface Who {
        data class SpeakerNum(val num: Int) : Who
        /** The last voice other than the owner's heard recently. */
        object LastOther : Who
    }

    private val TELL = Regex(
        "^(?:please )?(?:(?:remember|note|know) (?:that )?)?(?:" +
            "speaker (\\d{1,3})" +
            "|(?:that|the other|the last) (?:voice|person|speaker)" +
            "|(?:the )?(?:person|guy|girl|man|woman|one) (?:i (?:was|am|'m) (?:just )?)?(?:talking|speaking) (?:to|with)" +
            "|who i (?:was|am|'m) (?:just )?(?:talking|speaking) (?:to|with)" +
            ")(?: (?:just )?now)? (?:is|was|'s) (?:called |named )?([A-Za-z][A-Za-z'-]{1,20}(?: [A-Z][a-z'-]{1,20})?)$",
        RegexOption.IGNORE_CASE,
    )

    /** "Speaker 2 is Sam", "the person I was just talking to is Sam": who and the name. Pure. */
    fun told(request: String): Pair<Who, String>? {
        val m = TELL.find(request.trim().trimEnd('.', '!')) ?: return null
        val raw = m.groupValues[2].trim()
        if (raw.substringBefore(' ').lowercase() in NOT_TOLD) return null
        val name = raw.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        if (!ok(name.substringBefore(' '))) return null
        val who = m.groupValues[1].toIntOrNull()?.let { Who.SpeakerNum(it) } ?: Who.LastOther
        return who to name
    }
}
