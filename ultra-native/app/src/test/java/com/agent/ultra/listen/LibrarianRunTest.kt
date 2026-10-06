package com.agent.ultra.listen

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** A full librarian batch on the JVM: fake small model, in-memory queue and notes. */
class LibrarianRunTest {
    private val zone = ZoneId.of("Europe/London")
    private val min = 60_000L

    /** Tuesday 6 October 2026, local time. */
    private fun at(h: Int, m: Int = 0, day: Int = 6) =
        LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()

    private class Queue(var rows: List<HeardLine>) : HeardSource {
        var mark = 0L
        override fun unprocessed(limit: Int) = rows.filter { it.id > mark }.take(limit)
        override fun markProcessed(id: Long, ts: Long) { mark = id }
    }

    /** Stands in for MemoryNotes: candidates = same kind sharing a word, like the FTS lookup. */
    private class Shelf : NoteStore {
        val notes = ArrayList<Note>()
        override fun candidates(kind: NoteKind, text: String, limit: Int): List<Note> {
            val words = NoteMerge.tokens(text)
            return notes.filter { it.kind == kind && NoteMerge.tokens(it.text).any { w -> w in words } }
                .sortedByDescending { it.updatedTs }.take(limit)
        }
        override fun insert(note: Note): Long {
            val id = notes.size + 1L
            notes += note.copy(id = id)
            return id
        }
        override fun update(note: Note) { notes[notes.indexOfFirst { it.id == note.id }] = note }
    }

    /** Answers by the first transcript line in the prompt; records every prompt. */
    private class FakeModel(val answers: Map<String, Result<String>>) : SmallModel {
        val prompts = ArrayList<String>()
        override suspend fun complete(prompt: String, maxTokens: Int): Result<String> {
            prompts += prompt
            val reading = prompt.contains(ReadingPrompt.SYSTEM)
            val plain = prompt.contains(LibrarianPrompt.SYSTEM_PLAIN)
            assertEquals(
                if (reading) ReadingPrompt.MAX_TOKENS else if (plain) LibrarianPrompt.MAX_TOKENS_PLAIN else LibrarianPrompt.MAX_TOKENS,
                maxTokens,
            )
            val key = answers.keys.firstOrNull { prompt.contains(it) }
            return answers[key] ?: Result.success("NONE")
        }
    }

    private suspend fun run(
        q: Queue, shelf: Shelf, model: SmallModel, now: Long,
        failures: LibrarianRun.Failures = LibrarianRun.Failures(), keepGoing: () -> Boolean = { true },
    ) = LibrarianRun.run(q, shelf, model, zone, { now }, keepGoing, failures)

    private val evening = listOf(
        HeardLine(1, at(18, 0), "so the dentist moved it"),
        HeardLine(2, at(18, 1), "it's tomorrow at 3 now with Dr Lee"),
        HeardLine(3, at(18, 2), "and don't forget to buy milk"),
        // ten minutes of silence: a different conversation
        HeardLine(4, at(18, 12), "the weather on the radio says rain"),
        HeardLine(5, at(18, 13), "turn it off"),
    )

    @Test fun fullBatchFilesNotesAndMarksRows() = runTest {
        val q = Queue(evening)
        val shelf = Shelf()
        val model = FakeModel(mapOf(
            "so the dentist" to Result.success(
                "<think>let me see</think>\n" +
                    "{\"kind\":\"plan\",\"text\":\"Dentist appointment with Dr Lee\",\"when\":\"tomorrow at 3\"}\n" +
                    "{\"kind\":\"todo\",\"text\":\"Buy milk\"}\n" +
                    "{\"kind\":\"plan\",\"text\":\"broken" // cut off by the token limit
            ),
            // malformed: prose only, no notes at all
            "the weather" to Result.success("Sure! I can help organise your notes."),
        ))
        val r = run(q, shelf, model, now = at(20))
        assertEquals(LibrarianRun.Result(2, 2, 0, 1, 0, LibrarianRun.Stop.DONE, false), r)
        assertEquals(2, model.prompts.size)
        assertEquals(5L, q.mark)

        val dentist = shelf.notes.single { it.kind == NoteKind.PLAN }
        assertEquals("Dentist appointment with Dr Lee", dentist.text)
        assertEquals(at(15, day = 7), dentist.whenTs)          // resolved against 18:01 on the 6th
        assertNull(dentist.whenEnd)
        assertEquals(at(18, 1), dentist.heardTs)
        assertEquals(listOf(1L, 2L, 3L), dentist.sourceIds)
        val milk = shelf.notes.single { it.kind == NoteKind.TODO }
        assertNull(milk.whenTs)
        assertEquals(at(18, 0), milk.heardTs)
        assertEquals(at(20), milk.createdTs)

        // nothing new: no model call
        assertEquals(LibrarianRun.Result(0, 0, 0, 0, 0, LibrarianRun.Stop.DONE, false), run(q, shelf, model, at(21)))
        assertEquals(2, model.prompts.size)
    }

    @Test fun theSamePlanHeardAgainIsMerged() = runTest {
        val q = Queue(evening.take(3))
        val shelf = Shelf()
        run(q, shelf, FakeModel(mapOf("so the dentist" to Result.success(
            "{\"kind\":\"plan\",\"text\":\"Dentist appointment with Dr Lee\",\"when\":\"tomorrow at 3\"}"))), at(20))
        q.rows = q.rows + HeardLine(6, at(19, 0), "remember the dentist with Dr Lee at 3 tomorrow, on Main Street")
        val r = run(q, shelf, FakeModel(mapOf("remember the dentist" to Result.success(
            "{\"kind\":\"plan\",\"text\":\"Dentist appointment with Dr Lee on Main Street\",\"when\":\"at 3 tomorrow\"}"))), at(21))
        assertEquals(1, r.merged)
        assertEquals(0, r.added)
        val n = shelf.notes.single()
        assertEquals("Dentist appointment with Dr Lee on Main Street", n.text)
        assertEquals(at(15, day = 7), n.whenTs)
        assertEquals(listOf(1L, 2L, 3L, 6L), n.sourceIds)
        assertEquals(at(20), n.createdTs)
        assertEquals(at(21), n.updatedTs)
    }

    @Test fun recentSpeechWaits() = runTest {
        val q = Queue(evening)
        val model = FakeModel(emptyMap())
        // at 18:20 only the first conversation (ended 18:02) is 10 minutes old
        val r = run(q, Shelf(), model, now = at(18, 20))
        assertEquals(1, r.chunks)
        assertEquals(3L, q.mark)
    }

    @Test fun aConversationStillGoingOnIsNotCutAtTheAgeCutoff() = runTest {
        // 18:00..18:02 is old enough at 18:12 but 18:03 is not, and it continues the same talk
        val rows = evening.take(3) + HeardLine(4, at(18, 3), "at the Main Street clinic")
        val q = Queue(rows)
        val model = FakeModel(emptyMap())
        val r = run(q, Shelf(), model, now = at(18, 12))
        assertEquals(0, r.chunks)
        assertEquals(0L, q.mark)
        assertTrue(model.prompts.isEmpty())
        // once it has all aged, the whole conversation goes in one chunk
        assertEquals(1, run(q, Shelf(), model, now = at(18, 14)).chunks)
        assertTrue(model.prompts.single().contains("Main Street clinic"))
        assertEquals(4L, q.mark)
    }

    @Test fun aCancelledCallIsNotAFailure() = runTest {
        val q = Queue(evening)
        val failures = LibrarianRun.Failures()
        // a runCatching-style model turns cancellation into Result.failure
        val model = SmallModel { _, _ -> runCatching { kotlinx.coroutines.awaitCancellation() } }
        repeat(4) {
            val job = launch { run(q, Shelf(), model, at(20), failures) }
            testScheduler.runCurrent()
            job.cancel()                          // the watcher: screen came on
            job.join()
        }
        assertEquals(0L, q.mark)                  // never skipped
        assertEquals(1, failures.failed(1L))      // and nothing was counted
    }

    @Test fun modelFailureLeavesRowsQueuedThenSkipsAfterThree() = runTest {
        val q = Queue(evening)
        val failures = LibrarianRun.Failures()
        val model = FakeModel(mapOf("so the dentist" to Result.failure(IllegalStateException("not loaded"))))
        repeat(2) {
            val r = run(q, Shelf(), model, at(20), failures)
            assertEquals(LibrarianRun.Stop.MODEL_FAILED, r.stop)
            assertEquals(0L, q.mark)
        }
        val r = run(q, Shelf(), model, at(20), failures)
        assertEquals(1, r.skipped)
        assertEquals(1, r.chunks)              // the second conversation went through
        assertEquals(5L, q.mark)
    }

    @Test fun aThrowingModelCountsAsAFailure() = runTest {
        val q = Queue(evening)
        val r = run(q, Shelf(), SmallModel { _, _ -> throw RuntimeException("native crash") }, at(20))
        assertEquals(LibrarianRun.Stop.MODEL_FAILED, r.stop)
        assertEquals(0L, q.mark)
    }

    @Test fun stopsWhenNoLongerAllowed() = runTest {
        val q = Queue(evening)
        val model = FakeModel(emptyMap())
        var checks = 0
        val r = run(q, Shelf(), model, at(20), keepGoing = { ++checks <= 1 })
        assertEquals(LibrarianRun.Stop.GATE, r.stop)
        assertEquals(1, model.prompts.size)
        assertEquals(3L, q.mark)
    }

    @Test fun workPerRunIsCapped() = runTest {
        // 12 conversations, each 10 minutes apart
        val rows = (1L..12L).map { HeardLine(it, at(8) + it * 10 * min, "conversation $it") }
        val q = Queue(rows)
        val r = run(q, Shelf(), FakeModel(emptyMap()), at(20))
        assertEquals(LibrarianRun.MAX_CHUNKS, r.chunks)
        assertEquals(LibrarianRun.Stop.CAP, r.stop)
        assertTrue(r.more)
        assertEquals(8L, q.mark)
        val rest = run(q, Shelf(), FakeModel(emptyMap()), at(20))
        assertEquals(4, rest.chunks)
        assertFalse(rest.more)
    }

    private val talk = listOf(
        HeardLine(1, at(18, 0), "[Me] are you coming on Friday?"),
        HeardLine(2, at(18, 1), "[Sam] I mean, um, [pause 1.8s] maybe Friday, I'll see"),
        HeardLine(3, at(18, 2), "[Me] I'll book the table for 7pm on Friday"),
    )
    private val talkNotes =
        "{\"kind\":\"observation\",\"pattern\":\"hedging\",\"who\":\"Sam\",\"quote\":\"maybe Friday, I'll see\",\"confidence\":\"med\",\"text\":\"Sam hedged about Friday\"}\n" +
            "{\"kind\":\"observation\",\"pattern\":\"hedging\",\"who\":\"Sam\",\"quote\":\"I'll definitely come\",\"confidence\":\"high\",\"text\":\"Sam promised to come\"}\n" +
            "{\"kind\":\"plan\",\"text\":\"Table booked for Friday\",\"when\":\"Friday at 7pm\"}"

    @Test fun observationsAreVerifiedFiledAndGetASecondPassReading() = runTest {
        val q = Queue(talk)
        val shelf = Shelf()
        val model = FakeModel(mapOf(
            ReadingPrompt.SYSTEM to Result.success(
                "{\"obs\":1,\"id\":\"avoidance\",\"hedge\":\"may suggest\",\"confidence\":\"low\",\"alt\":\"Sam may not know his shifts yet\"}"),
            "are you coming" to Result.success(talkNotes),
        ))
        val r = run(q, shelf, model, at(20))
        assertEquals(2, model.prompts.size)
        assertTrue(model.prompts[1].contains("1. hedging, Sam: \"maybe Friday, I'll see\""))
        assertEquals(1, r.rejected)                       // the invented quote
        assertEquals(1, r.observations)
        assertEquals(1, r.readings)
        val o = shelf.notes.single { it.kind == NoteKind.OBSERVATION }
        assertEquals(
            "Observation (hedging, med confidence): Sam hedged about Friday. Evidence: Sam at 18:01: \"maybe Friday, I'll see\". " +
                "Possible reading (low confidence): may suggest avoidance (moving away from the topic). Alternative: Sam may not know his shifts yet.",
            o.text,
        )
        assertEquals(at(18, 1), o.heardTs)
        assertNull(o.whenTs)
        assertEquals(1, shelf.notes.count { it.kind == NoteKind.PLAN })    // the old kinds still work
        // the same pattern seen again is a second data point, never merged
        q.rows = q.rows + talk.map { it.copy(id = it.id + 10, ts = it.ts + 3_600_000L) }
        run(q, shelf, model, at(21))
        assertEquals(2, shelf.notes.count { it.kind == NoteKind.OBSERVATION })
    }

    @Test fun readingsOffOrPatternsOff() = runTest {
        val model = FakeModel(mapOf("are you coming" to Result.success(talkNotes)))
        val noReadings = Shelf()
        val r = LibrarianRun.run(Queue(talk), noReadings, model, zone, { at(20) }, { true }, LibrarianRun.Failures(), readings = false)
        assertEquals(1, model.prompts.size)              // no second pass
        assertEquals(1, r.observations)
        assertFalse(noReadings.notes.single { it.kind == NoteKind.OBSERVATION }.text.contains("reading"))

        val noPatterns = Shelf()
        LibrarianRun.run(Queue(talk), noPatterns, model, zone, { at(20) }, { true }, LibrarianRun.Failures(), patterns = false)
        assertTrue(model.prompts.last().contains(LibrarianPrompt.SYSTEM_PLAIN))
        assertEquals(listOf(NoteKind.PLAN), noPatterns.notes.map { it.kind })
    }

    @Test fun aFailedReadingPassStillFilesTheObservation() = runTest {
        val model = FakeModel(mapOf(
            ReadingPrompt.SYSTEM to Result.failure(IllegalStateException("oom")),
            "are you coming" to Result.success(talkNotes),
        ))
        val q = Queue(talk)
        val shelf = Shelf()
        val r = run(q, shelf, model, at(20))
        assertEquals(LibrarianRun.Stop.DONE, r.stop)
        assertEquals(3L, q.mark)
        assertEquals(0, r.readings)
        assertEquals(1, shelf.notes.count { it.kind == NoteKind.OBSERVATION })
    }

    @Test fun gate() {
        assertTrue(Librarian.mayRun(enabled = true, hasModel = true, charging = true, screenOn = false, brainBusy = false))
        assertFalse(Librarian.mayRun(enabled = false, hasModel = true, charging = true, screenOn = false, brainBusy = false))
        assertFalse(Librarian.mayRun(enabled = true, hasModel = false, charging = true, screenOn = false, brainBusy = false))
        assertFalse(Librarian.mayRun(enabled = true, hasModel = true, charging = false, screenOn = false, brainBusy = false))
        assertFalse(Librarian.mayRun(enabled = true, hasModel = true, charging = true, screenOn = true, brainBusy = false))
        assertFalse(Librarian.mayRun(enabled = true, hasModel = true, charging = true, screenOn = false, brainBusy = true))
    }
}
