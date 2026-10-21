package com.agent.jarvis.agent

/**
 * OCR lines put back into reading order for the brain: top to bottom, and lines sitting side by
 * side on the same row joined with " · " (a price next to a name stays with it). The takeover
 * overlay's own words ("Jarvis is working", "Stop") are dropped, and so is the status bar's clock
 * and battery strip at the very top. The recognition is in [ScreenVision].
 */
object OcrLayout {

    data class Line(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val midY get() = (top + bottom) / 2
        val height get() = (bottom - top).coerceAtLeast(1)
    }

    /** Jarvis's own overlay, which is in every screenshot taken during a takeover. */
    private val OWN = setOf("jarvis is working", "stop")

    /** The top strip (clock, signal, battery) as a share of the screen height. */
    private const val STATUS_BAR = 0.035

    const val MAX_CHARS = 2500

    fun format(lines: List<Line>, width: Int, height: Int, max: Int = MAX_CHARS): String {
        val kept = lines.filter { l ->
            val t = l.text.trim()
            t.isNotEmpty() && t.lowercase() !in OWN && l.bottom > height * STATUS_BAR
        }.sortedWith(compareBy({ it.top }, { it.left }))
        val rows = ArrayList<MutableList<Line>>()
        for (l in kept) {
            // Same row: its middle falls inside the row's first line, vertically.
            val row = rows.lastOrNull()?.takeIf { r -> val f = r.first(); l.midY in f.top..f.bottom }
            if (row != null) row += l else rows += mutableListOf(l)
        }
        val out = StringBuilder()
        for (r in rows) {
            val line = r.sortedBy { it.left }.joinToString(" · ") { it.text.trim() }
            if (out.length + line.length + 1 > max) { out.append('…'); break }
            out.append(line).append('\n')
        }
        return out.toString().trimEnd()
    }

    /**
     * Whether the accessibility text of a screen is too thin to rely on, so the picture should be
     * read as well: under [minChars] letters or digits once the bookkeeping lines are set aside.
     */
    fun sparse(treeText: String, minChars: Int = 60): Boolean =
        treeText.lineSequence()
            .filterNot { it.startsWith("Current app:") || it.startsWith("Screen:") || it.startsWith("(") }
            .sumOf { line -> line.count { it.isLetterOrDigit() } } < minChars
}
