package com.agent.jarvis.agent

/**
 * Cuts an answer being written into sentences that can be spoken while the rest is still being
 * written (owner, 2026-10-07: "answer right away … vocally"). Before, nothing was said until the
 * whole answer was done. Pure, JVM-tested.
 *
 * A sentence ends at . ! ? or : followed by a space, or at a line break. The first piece waits for
 * [firstChars] and later ones for [minChars]: each piece is a separate synthesis, and the next is
 * only made once the last has been said, so short pieces (a lone "Sure.") left a gap after every
 * one or two words (owner, 2026-10-07: "it says one word … then again a bit afterwards"). "3.5" and "e.g." without a following space
 * don't end one.
 */
class SpeechChunker(private val minChars: Int = MIN_CHARS, private val firstChars: Int = FIRST_CHARS) {
    private val buf = StringBuilder()
    private var sent = 0

    /** Add written text; returns the sentences now complete, in order. */
    fun push(piece: String): List<String> {
        if (piece.isEmpty()) return emptyList()
        buf.append(piece)
        val out = mutableListOf<String>()
        while (true) {
            val cut = boundary() ?: break
            val s = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (s.isNotEmpty()) { out += s; sent++ }
        }
        return out
    }

    /** Whatever is left once the answer is finished, or null. */
    fun flush(): String? {
        val s = buf.toString().trim()
        buf.setLength(0)
        return s.ifEmpty { null }
    }

    /** Index just past the first sentence end that is long enough, or null. */
    private fun boundary(): Int? {
        val need = if (sent == 0) firstChars else minChars
        for (i in buf.indices) {
            val c = buf[i]
            val end = when {
                c == '\n' -> i + 1
                (c == '.' || c == '!' || c == '?' || c == ':') && i + 1 < buf.length && buf[i + 1].isWhitespace() -> i + 1
                else -> continue
            }
            if (buf.substring(0, end).trim().length >= need) return end
        }
        return null
    }

    companion object {
        const val FIRST_CHARS = 40
        const val MIN_CHARS = 140
    }
}
