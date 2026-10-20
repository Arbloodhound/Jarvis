package com.agent.jarvis.listen

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NotesFromMemoryTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(d: Int, h: Int) = LocalDateTime.of(2026, 10, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    // ── "make a note of what Sam said …" / "save that as a note" ──────────────────────────

    @Test fun noteRequests() {
        assertEquals(MemoryNoteRequest.Kind.FromMemory("Sam said about the boiler"),
            MemoryNoteRequest.parse("Make a note of what Sam said about the boiler"))
        assertEquals(MemoryNoteRequest.Kind.FromMemory("we talked about at dinner"),
            MemoryNoteRequest.parse("note down everything we talked about at dinner"))
        assertEquals(MemoryNoteRequest.Kind.LastAnswer, MemoryNoteRequest.parse("save that as a note"))
        assertEquals(MemoryNoteRequest.Kind.LastAnswer, MemoryNoteRequest.parse("Note that down."))
        // a new fact is a plain note, not a memory search
        assertNull(MemoryNoteRequest.parse("make a note to buy milk"))
        assertNull(MemoryNoteRequest.parse("what did Sam say"))
    }

    @Test fun noteTextFromTheModel() {
        assertEquals("Sam said the boiler needs a new valve (Tue 6 Oct)",
            MemoryNoteRequest.noteText("Note: Sam said the boiler needs a new valve (Tue 6 Oct)"))
        assertNull(MemoryNoteRequest.noteText("Nothing found."))
        assertEquals("Plumber on Friday", MemoryNoteRequest.fromAnswer("Plumber on Friday (on-device)"))
    }

    // ── who and tags ───────────────────────────────────────────────────────────────────────

    @Test fun whoAndTagsParsed() {
        val r = NoteParser.parse(
            "{\"kind\":\"todo\",\"text\":\"Book the plumber\",\"who\":\"[Speaker 2]\",\"tags\":[\"Home\",\"#plumbing\",\"x\",\"home\",\"a\",\"money\",\"more\"]}\n" +
                "{\"kind\":\"summary\",\"text\":\"Sam and Me talked about the boiler\",\"who\":\"Sam, Me\",\"tags\":\"home, repairs\"}")
        assertEquals(0, r.rejected)
        assertEquals("Speaker 2", r.notes[0].who)
        assertEquals(listOf("home", "plumbing", "money"), r.notes[0].tags)   // cleaned, distinct, at most 3
        assertEquals(NoteKind.SUMMARY, r.notes[1].kind)
        assertEquals(listOf("home", "repairs"), r.notes[1].tags)
    }

    @Test fun mergeKeepsWhoAndUnitesTags() {
        val a = Note(1, NoteKind.FACT, "Gate code 4512", null, null, 1, emptyList(), 1, 1, who = "Me", tags = listOf("home"))
        val b = a.copy(id = 0, who = "Sam", tags = listOf("codes"))
        val m = NoteMerge.merge(a, b, 2)
        assertEquals("Me", m.who)
        assertEquals(listOf("home", "codes"), m.tags)
        assertTrue(m.describe(zone).contains(", Me] Gate code 4512 #home #codes"))
    }

    // ── the librarian runs more often ─────────────────────────────────────────────────────

    @Test fun runsOffTheChargerWithEnoughBattery() {
        assertTrue(Librarian.mayRun(true, true, charging = false, screenOn = false, brainBusy = false, batteryPct = 60))
        assertFalse(Librarian.mayRun(true, true, charging = false, screenOn = false, brainBusy = false, batteryPct = 40))
        assertFalse(Librarian.mayRun(true, true, charging = false, screenOn = false, brainBusy = false, batteryPct = 90, powerSave = true))
        assertFalse(Librarian.mayRun(true, true, charging = true, screenOn = true, brainBusy = false))
    }

    // ── follow-up notes linking new conversations to older notes, on its own ──────────────

    private class Store : NoteStore {
        val notes = ArrayList<Note>()
        override fun candidates(kind: NoteKind, text: String, limit: Int) =
            notes.filter { it.kind == kind && NoteMerge.similar(it.text, text) }.take(limit)
        override fun insert(note: Note): Long { val id = notes.size + 1L; notes += note.copy(id = id); return id }
        override fun update(note: Note) { notes[notes.indexOfFirst { it.id == note.id }] = note }
        override fun createdSince(ts: Long, kind: NoteKind?, limit: Int) =
            notes.filter { it.createdTs >= ts && (kind == null || it.kind == kind) }.take(limit)
        override fun related(text: String, beforeTs: Long, limit: Int): List<Note> {
            val w = NoteMerge.tokens(text)
            return notes.filter { it.createdTs < beforeTs && NoteMerge.tokens(it.text).any { t -> t in w } }.take(limit)
        }
    }

    @Test fun linksANewConversationToAnOlderNote() = runBlocking {
        val s = Store()
        s.insert(Note(kind = NoteKind.TODO, text = "Sam needs to book the boiler repair", whenTs = null, whenEnd = null,
            heardTs = at(5, 10), sourceIds = listOf(1), createdTs = at(5, 11), updatedTs = at(5, 11), who = "Sam"))
        s.insert(Note(kind = NoteKind.SUMMARY, text = "Sam talked about the boiler again and the plumber's price",
            whenTs = null, whenEnd = null, heardTs = at(7, 9), sourceIds = listOf(9), createdTs = at(7, 10), updatedTs = at(7, 10)))
        val prompts = ArrayList<String>()
        val model = SmallModel { p, _ ->
            prompts += p
            Result.success("{\"kind\":\"todo\",\"text\":\"Sam has a plumber's price now but the boiler repair from Mon 5 Oct is still not booked\",\"who\":\"Sam\",\"tags\":[\"home\"]}")
        }
        val r = LibrarianLinks.run(s, model, zone, since = at(7, 0), now = { at(7, 12) }, keepGoing = { true })
        assertEquals(1, r.looked)
        assertEquals(1, r.added)
        assertTrue(prompts.single().contains("Sam needs to book the boiler repair"))
        val link = s.notes.last()
        assertEquals(listOf("follow-up", "home"), link.tags)
        assertEquals(listOf(9L), link.sourceIds)
    }

    @Test fun aLinkNoteCopiedFromThePromptExamplesIsDropped() = runBlocking {
        val s = Store()
        s.insert(Note(kind = NoteKind.TODO, text = "Sam needs to book the boiler repair", whenTs = null, whenEnd = null,
            heardTs = at(5, 10), sourceIds = listOf(1), createdTs = at(5, 11), updatedTs = at(5, 11), who = "Sam"))
        s.insert(Note(kind = NoteKind.SUMMARY, text = "Sam talked about the boiler again and the plumber's price",
            whenTs = null, whenEnd = null, heardTs = at(7, 9), sourceIds = listOf(9), createdTs = at(7, 10), updatedTs = at(7, 10)))
        // The model echoes both examples of its prompt (one lightly altered) instead of linking what it was shown.
        val out = "{\"kind\":\"todo\",\"text\":\"The gutter clearing Nadia raised on Tue 6 Oct is still not done\",\"who\":\"Nadia\",\"tags\":[\"home\"]}\n" +
            "{\"kind\":\"fact\",\"text\":\"Omar mentioned the allotment plot again, he first said it on Mon 5 Oct\",\"who\":\"Omar\",\"tags\":[\"garden\"]}"
        val before = s.notes.size
        val r = LibrarianLinks.run(s, SmallModel { _, _ -> Result.success(out) }, zone,
            since = at(7, 0), now = { at(7, 12) }, keepGoing = { true })
        assertEquals(1, r.looked)
        assertEquals(0, r.added)
        assertEquals(0, r.merged)
        assertEquals(before, s.notes.size)
    }

    @Test fun linkPromptExamplesAreInItsPromptAndCountAsExamples() {
        for (t in LibrarianLinks.EXAMPLE_TEXTS) {
            assertTrue(t, LibrarianLinks.SYSTEM.contains(t))
            assertTrue(t, LibrarianPrompt.copiesExample(t))
        }
    }

    @Test fun noOlderNotesNoLinkCall() = runBlocking {
        val s = Store()
        s.insert(Note(kind = NoteKind.SUMMARY, text = "Quiet chat about nothing", whenTs = null, whenEnd = null,
            heardTs = at(7, 9), sourceIds = emptyList(), createdTs = at(7, 10), updatedTs = at(7, 10)))
        var called = false
        val r = LibrarianLinks.run(s, SmallModel { _, _ -> called = true; Result.success("NONE") }, zone,
            since = at(7, 0), now = { at(7, 12) }, keepGoing = { true })
        assertFalse(called)
        assertEquals(0, r.looked)
    }
}
