package com.agent.jarvis.ui

/**
 * The in-app guide's markdown (assets/guide.md) as blocks for [GuideScreen]. Only what the guide
 * uses: "# " / "## " / "### " headings, "- " bullets, "> " tips, paragraphs (consecutive lines
 * joined), and **bold** inside a line. Pure, JVM-tested.
 */
object GuideText {
    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Bullet(val text: String) : Block()
        data class Tip(val text: String) : Block()
        data class Para(val text: String) : Block()
    }

    fun parse(md: String): List<Block> {
        val out = ArrayList<Block>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.setLength(0) }
        for (raw in md.lines()) {
            val line = raw.trimEnd()
            val t = line.trimStart()
            when {
                t.isEmpty() -> flush()
                t.startsWith("### ") -> { flush(); out += Block.Heading(3, t.removePrefix("### ").trim()) }
                t.startsWith("## ") -> { flush(); out += Block.Heading(2, t.removePrefix("## ").trim()) }
                t.startsWith("# ") -> { flush(); out += Block.Heading(1, t.removePrefix("# ").trim()) }
                t.startsWith("- ") || t.startsWith("* ") -> { flush(); out += Block.Bullet(t.substring(2).trim()) }
                t.startsWith(">") -> { flush(); out += Block.Tip(t.removePrefix(">").trim()) }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
        }
        flush()
        return out
    }

    /** [text] split into (piece, bold) runs at "**" marks; an unclosed "**" stays as text. */
    fun boldRuns(text: String): List<Pair<String, Boolean>> {
        val parts = text.split("**")
        if (parts.size % 2 == 0) return listOf(text to false)   // odd number of marks: leave as written
        return parts.mapIndexedNotNull { i, s -> if (s.isEmpty()) null else s to (i % 2 == 1) }
    }

    /** The index of each "## " section (its heading block) with its title: the contents list. */
    fun sections(blocks: List<Block>): List<Pair<Int, String>> =
        blocks.mapIndexedNotNull { i, b -> if (b is Block.Heading && b.level == 2) i to b.text else null }

    /** Blocks of the sections whose heading or text mention every word of [query] (case-insensitive),
     *  each section whole; all blocks for a blank query. */
    fun filter(blocks: List<Block>, query: String): List<Block> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return blocks
        val out = ArrayList<Block>()
        var section = ArrayList<Block>()
        fun take() { val text = section.joinToString(" ") { textOf(it) }.lowercase(); if (words.all { it in text }) out += section }
        for (b in blocks) {
            if (b is Block.Heading && b.level <= 3 && section.isNotEmpty()) { take(); section = ArrayList() }
            section += b
        }
        if (section.isNotEmpty()) take()
        return out
    }

    private fun textOf(b: Block) = when (b) {
        is Block.Heading -> b.text; is Block.Bullet -> b.text; is Block.Tip -> b.text; is Block.Para -> b.text
    }
}
