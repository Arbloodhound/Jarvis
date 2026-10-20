package com.agent.ultra.ui

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownTest {
    @Test fun marksAreDrawnNotShown() {
        val a = ChatMarkdown.render("## Result\n- **Wi-Fi** is on\n- see [docs](https://x.org/a) and `adb`")
        assertEquals("Result\n• Wi-Fi is on\n• see docs (https://x.org/a) and adb", a.text)
        assertTrue(a.spanStyles.any { it.item.fontWeight == FontWeight.Bold && a.text.substring(it.start, it.end) == "Wi-Fi" })
    }

    @Test fun halfStreamedMarksStayLiteral() {
        assertEquals("price **12", ChatMarkdown.render("price **12").text)
        assertEquals("2 * 3 * 4", ChatMarkdown.render("2 * 3 * 4").text)
    }
}
