package com.agent.jarvis.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * The little Markdown the models write in chat, drawn instead of shown as raw marks: **bold**,
 * *italic*, `code`, # headings, "- " bullets, and [text](url) shown as its text (the url after it).
 * Anything else stays as written. A half-streamed mark ("**bo") stays literal until it closes.
 * Pure, JVM-tested.
 */
object ChatMarkdown {
    private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`\n]+)`|(?<![*\w])\*(?=\S)([^*\n]+?)(?<=\S)\*(?!\w)|\[([^\]\n]+)]\((https?://[^)\s]+)\)""")

    fun render(text: String): AnnotatedString = buildAnnotatedString {
        text.lines().forEachIndexed { n, raw ->
            if (n > 0) append('\n')
            var line = raw
            val heading = Regex("^#{1,6}\\s+").find(line)
            if (heading != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(line.substring(heading.range.last + 1)) }
                return@forEachIndexed
            }
            Regex("^(\\s*)[-*]\\s+").find(line)?.let { b ->
                append(b.groupValues[1]); append("• ")
                line = line.substring(b.range.last + 1)
            }
            inline(line)
        }
    }

    private fun AnnotatedString.Builder.inline(s: String) {
        var at = 0
        for (m in INLINE.findAll(s)) {
            append(s.substring(at, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1]) }
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[2]) }
                g[3].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[3]) }
                else -> { append(g[4]); append(" ("); append(g[5]); append(")") }
            }
            at = m.range.last + 1
        }
        append(s.substring(at))
    }
}
