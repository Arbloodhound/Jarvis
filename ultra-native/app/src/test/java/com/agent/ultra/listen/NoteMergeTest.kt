package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class NoteMergeTest {
    private val hour = 3_600_000L

    private fun note(
        text: String, kind: NoteKind = NoteKind.PLAN, whenTs: Long? = null, id: Long = 0,
        heard: Long = 100, sources: List<Long> = listOf(1), updated: Long = 100,
    ) = Note(id, kind, text, whenTs, null, heard, sources, 100, updated)

    @Test fun similarWordingSameKindIsDuplicate() {
        assertTrue(NoteMerge.isDuplicate(note("Dentist appointment with Dr Lee"), note("dentist appointment, Dr Lee")))
        // the shorter note's words are all in the longer one
        assertTrue(NoteMerge.isDuplicate(note("Dentist appointment Dr Lee"), note("Dentist appointment with Dr Lee at the Main Street clinic")))
    }

    @Test fun differentKindOrWordingIsNot() {
        assertFalse(NoteMerge.isDuplicate(note("Dentist appointment with Dr Lee"), note("Dentist appointment with Dr Lee", NoteKind.TODO)))
        assertFalse(NoteMerge.isDuplicate(note("Dentist appointment with Dr Lee"), note("Haircut with Jo")))
    }

    @Test fun plansAtDifferentTimesAreDifferent() {
        assertFalse(NoteMerge.isDuplicate(note("Gym with Sam", whenTs = 0), note("Gym with Sam", whenTs = 24 * hour)))
        assertTrue(NoteMerge.isDuplicate(note("Gym with Sam", whenTs = 0), note("Gym with Sam", whenTs = hour)))
        assertTrue(NoteMerge.isDuplicate(note("Gym with Sam", whenTs = 0), note("Gym with Sam")))
        // facts ignore time
        assertTrue(NoteMerge.isDuplicate(note("Sam likes tea", NoteKind.FACT, 0), note("Sam likes tea", NoteKind.FACT, 99 * hour)))
    }

    @Test fun findDuplicatePicksTheMostSimilar() {
        val fresh = note("Dentist appointment with Dr Lee on Main Street")
        val a = note("Dentist appointment with Dr Lee", id = 1)
        val b = note("Dentist appointment with Dr Lee on Main Street", id = 2)
        assertEquals(2L, NoteMerge.findDuplicate(fresh, listOf(a, b))?.id)
        assertNull(NoteMerge.findDuplicate(fresh, listOf(note("Haircut", id = 3))))
    }

    @Test fun mergeKeepsDetailAndHistory() {
        val old = note("Dentist with Dr Lee", whenTs = 10 * hour, id = 7, heard = 50, sources = listOf(3, 4))
        val fresh = note("Dentist with Dr Lee at the Main Street clinic", whenTs = 11 * hour, heard = 90, sources = listOf(4, 9))
        val m = NoteMerge.merge(old, fresh, now = 500)
        assertEquals(7L, m.id)
        assertEquals("Dentist with Dr Lee at the Main Street clinic", m.text)
        assertEquals(11 * hour, m.whenTs)
        assertEquals(50L, m.heardTs)
        assertEquals(listOf(3L, 4L, 9L), m.sourceIds)
        assertEquals(100L, m.createdTs)
        assertEquals(500L, m.updatedTs)
        // a fresh note without a time keeps the old one's
        assertEquals(10 * hour, NoteMerge.merge(old, note("Dentist"), 500).whenTs)
    }

    @Test fun ftsQueryNeverPassesSyntaxThrough() {
        assertEquals("dentist* OR tomorrow*", NoteMerge.ftsQuery("\"Dentist\" -tomorrow AND the"))
        assertEquals("near* OR dr* OR lee*", NoteMerge.ftsQuery("NEAR Dr. Lee"))
        assertNull(NoteMerge.ftsQuery("a the ? *"))
    }

    @Test fun rankPrefersMoreMatchedWordsThenNewest() {
        val a = note("Sam lives in Leeds", id = 1, updated = 300)
        val b = note("Sam and Anna dentist", id = 2, updated = 100)
        val c = note("Anna dentist Tuesday", id = 3, updated = 200)
        assertEquals(listOf(3L, 2L), NoteMerge.rank("anna's dentist", listOf(a, b, c), 2).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), NoteMerge.rank("", listOf(a, b, c), 5).map { it.id })
    }

    @Test fun describeAndHit() {
        val zone = ZoneId.of("UTC")
        val timed = Note(1, NoteKind.PLAN, "Dentist", 1_791_385_200_000L, null, 5, listOf(1), 5, 5)  // 2026-10-07 15:00Z
        assertEquals("[plan, Wed 7 Oct 2026 15:00] Dentist", timed.describe(zone))
        val day = timed.copy(whenEnd = timed.whenTs!! + 1)
        assertEquals("Wed 7 Oct 2026", day.whenText(zone))
        val hit = MemoryNotes.hit(timed, zone)
        assertEquals(NoteHit("Dentist (Wed 7 Oct 2026 15:00)", 1_791_385_200_000L, "plan"), hit)
        assertEquals(NoteHit("Sam is Anna's brother", 5, "person"),
            MemoryNotes.hit(Note(2, NoteKind.PERSON, "Sam is Anna's brother", null, null, 5, listOf(1), 5, 5), zone))
    }

    @Test fun timeWindowMatchesHeardOrAbout() {
        val fact = note("Sam likes tea", NoteKind.FACT, heard = 50)
        assertTrue(MemoryNotes.inWindow(fact, null, null))
        assertTrue(MemoryNotes.inWindow(fact, 50, 60))      // from inclusive
        assertFalse(MemoryNotes.inWindow(fact, 0, 50))      // to exclusive
        // heard at 50, about 200..300: found by either window
        val plan = Note(1, NoteKind.PLAN, "Dentist", 200, 300, 50, listOf(1), 50, 50)
        assertTrue(MemoryNotes.inWindow(plan, 40, 60))
        assertTrue(MemoryNotes.inWindow(plan, 250, 260))    // inside the span
        assertTrue(MemoryNotes.inWindow(plan, 300, 400))    // touches the end
        assertFalse(MemoryNotes.inWindow(plan, 301, 400))
        assertFalse(MemoryNotes.inWindow(plan, 100, 200))   // ends where the span starts
        assertTrue(MemoryNotes.inWindow(plan, null, 201))
        assertFalse(MemoryNotes.inWindow(plan.copy(whenEnd = null), 201, null))   // a clock time is a point
        assertEquals("1" to emptyList<String>(), MemoryNotes.windowSql(null, null))
        val (sql, args) = MemoryNotes.windowSql(10, 20)
        assertEquals(sql.count { it == '?' }, args.size)
        assertEquals(listOf("10", "20", "20", "10"), args)
    }
}
