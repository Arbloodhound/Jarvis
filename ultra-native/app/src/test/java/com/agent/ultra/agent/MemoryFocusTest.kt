package com.agent.ultra.agent

import com.agent.ultra.agent.MemoryFocus.Focus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFocusTest {
    private fun focus(s: String) = MemoryFocus.detect(s)?.focus

    @Test fun spokenOrTypedFocus() {
        assertEquals(Focus.NOTES, focus("search my notes for Lisbon"))
        assertEquals(Focus.NOTES, focus("What's in my notes about the dentist?"))
        assertEquals(Focus.NOTES, focus("read me my to-do list"))
        assertEquals(Focus.MEMORY, focus("check your memory for what Sam said"))
        assertEquals(Focus.MEMORY, focus("what does my memory say about Friday"))
        assertEquals(Focus.BOTH, focus("look through my memory and notes for the gate code"))
        for (s in listOf("what is memory", "how do I take good notes", "turn on the wifi", "what did Sam say yesterday"))
            assertNull(s, MemoryFocus.detect(s))
    }

    @Test fun queryDropsTheFocusWords() {
        assertEquals("Lisbon", MemoryFocus.detect("search my notes for Lisbon")!!.query)
        assertEquals("dentist", MemoryFocus.detect("What's in my notes about the dentist?")!!.query)
        assertEquals("", MemoryFocus.detect("read me my to-do list")!!.query)
        assertTrue(MemoryFocus.detect("read me my to-do list")!!.todosOnly)
        assertEquals("wifi password", MemoryFocus.detect("search my notes for the wifi password")!!.query)
    }

    @Test fun screens() {
        assertEquals("notes", MemoryFocus.screen("Open my notes"))
        assertEquals("memory", MemoryFocus.screen("show me my memory"))
        assertEquals("notes", MemoryFocus.screen("open my to-do list"))
        assertNull(MemoryFocus.screen("open chrome"))
        assertNull(MemoryFocus.screen("open my notes and read the first one"))
    }

    @Test fun briefSaysWhereAndWhatWasFound() {
        val ask = MemoryFocus.Ask(Focus.NOTES, "Lisbon")
        assertTrue(MemoryFocus.brief(ask, "Notes:\n- [todo] Renew passport").contains("the owner's notes"))
        assertTrue(MemoryFocus.brief(ask, null).endsWith("(nothing found for \"Lisbon\")"))
    }
}
