package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteParserTest {

    @Test fun parsesLinesAndIgnoresProse() {
        val r = NoteParser.parse(
            """
            Here are the notes:
            {"kind":"plan","text":"Dentist with Dr Lee","when":"tomorrow at 3pm"}
            - {"kind":"person","text":"Sam is Anna's brother"}
            2. {"kind":"todo","text":"Buy milk","when":null}
            """.trimIndent()
        )
        assertEquals(0, r.rejected)
        assertEquals(listOf(NoteKind.PLAN, NoteKind.PERSON, NoteKind.TODO), r.notes.map { it.kind })
        assertEquals("tomorrow at 3pm", r.notes[0].whenPhrase)
        assertNull(r.notes[1].whenPhrase)
        assertNull(r.notes[2].whenPhrase)
    }

    @Test fun stripsReasoningAndFences() {
        val r = NoteParser.parse(
            "<think>maybe {\"kind\":\"fact\",\"text\":\"not this\"}</think>\n```json\n" +
                "{\"kind\":\"fact\",\"text\":\"The gate code is 4512\"}\n```"
        )
        assertEquals(listOf("The gate code is 4512"), r.notes.map { it.text })
        assertEquals(0, r.rejected)
    }

    @Test fun unterminatedThinkIsDropped() {
        val r = NoteParser.parse("{\"kind\":\"fact\",\"text\":\"kept\"}\n<think>{\"kind\":\"fact\",\"text\":\"dropped\"}")
        assertEquals(listOf("kept"), r.notes.map { it.text })
    }

    @Test fun noneAndEmptyGiveNothing() {
        assertEquals(NoteParser.Result(emptyList(), 0), NoteParser.parse("NONE"))
        assertEquals(NoteParser.Result(emptyList(), 0), NoteParser.parse(""))
    }

    @Test fun acceptsAWholeArray() {
        val r = NoteParser.parse("[{\"kind\":\"todo\",\"text\":\"Call mum\"},{\"kind\":\"bogus\",\"text\":\"x\"}]")
        assertEquals(listOf("Call mum"), r.notes.map { it.text })
        assertEquals(1, r.rejected)
    }

    @Test fun rejectsMalformedObjects() {
        val r = NoteParser.parse(
            """
            {"kind":"weather","text":"It is sunny"}
            {"kind":"fact","text":""}
            {"kind":"fact" "text":"no comma"}
            {kind:"fact",text:"unquoted keys"}
            {"kind":"plan","text":"Cut off by the token li
            """.trimIndent()
        )
        assertTrue(r.notes.isEmpty())
        assertEquals(5, r.rejected)
    }

    @Test fun aNonStringWhenKeepsTheNoteWithoutATime() {
        val r = NoteParser.parse("{\"kind\":\"todo\",\"text\":\"Call Sam\",\"when\":3}")
        assertEquals(listOf(NoteDraft(NoteKind.TODO, "Call Sam", null)), r.notes)
        assertEquals(0, r.rejected)
    }

    @Test fun kindSynonymsAndCase() {
        val r = NoteParser.parse(
            "{\"kind\":\"Event\",\"text\":\"Party at Jo's\"}\n{\"kind\":\"reminder\",\"text\":\"Pay rent\"}\n" +
                "{\"kind\":\"preference\",\"text\":\"Anna hates olives\"}"
        )
        assertEquals(listOf(NoteKind.PLAN, NoteKind.TODO, NoteKind.FACT), r.notes.map { it.kind })
    }

    @Test fun textIsTidiedAndCapped() {
        val r = NoteParser.parse(
            "{\"kind\":\"fact\",\"text\":\"  two\\n  lines  \",\"when\":\"none\"}\n" +
                "{\"kind\":\"fact\",\"text\":\"" + "x".repeat(NoteParser.MAX_TEXT + 1) + "\"}"
        )
        assertEquals(listOf("two lines"), r.notes.map { it.text })
        assertNull(r.notes[0].whenPhrase)
        assertEquals(1, r.rejected)
    }

    @Test fun atMostMaxNotes() {
        val raw = (1..20).joinToString("\n") { "{\"kind\":\"fact\",\"text\":\"fact $it\"}" }
        assertEquals(NoteParser.MAX_NOTES, NoteParser.parse(raw).notes.size)
    }
}
