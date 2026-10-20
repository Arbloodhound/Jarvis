package com.agent.ultra.listen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueQueueTest {
    private fun job(id: Long, samples: Int?, tags: Boolean = true) = CueJob(
        rowid = id, ts = id, plain = "line $id", db = FloatArray(3), audioMs = 96L,
        pcm = samples?.let { ShortArray(it) }, floorDb = -60f, words = emptyList(), vad = emptyList(), gapBeforeMs = 900L,
        speakerTags = tags,
    )

    @Test fun keepsOrderAndDropsTheOldestOverTheCount() {
        val q = CueQueue(maxJobs = 3, maxSamples = 1_000)
        assertEquals(0, q.add(job(1, null)))
        q.add(job(2, null)); q.add(job(3, null))
        assertEquals(1, q.add(job(4, null)))
        assertEquals(3, q.size)
        assertEquals(listOf(2L, 3L, 4L), generateSequence { q.poll() }.map { it.rowid }.toList())
        assertNull(q.poll())
    }

    @Test fun overTheAudioBudgetTheOldestLinesLoseTheirAudioNotTheirPlace() {
        val q = CueQueue(maxJobs = 10, maxSamples = 250)
        q.add(job(1, 100)); q.add(job(2, null)); q.add(job(3, 100))
        assertEquals(200L, q.audioSamples)
        assertEquals(1, q.add(job(4, 100)))                  // 300 > 250: line 1 lets go of its audio
        assertEquals(200L, q.audioSamples)
        assertEquals(4, q.size)
        val out = generateSequence { q.poll() }.toList()
        assertEquals(listOf(1L, 2L, 3L, 4L), out.map { it.rowid })
        assertNull(out[0].pcm)
        assertEquals("line 1", out[0].plain)                  // the rest of the line is kept
        assertEquals(3, out[0].db.size)
        assertNotNull(out[2].pcm); assertNotNull(out[3].pcm)
        assertEquals(0L, q.audioSamples)
    }

    @Test fun theNewestLineKeepsItsAudioHoweverLong() {
        val q = CueQueue(maxJobs = 10, maxSamples = 100)
        q.add(job(1, 50))
        assertEquals(1, q.add(job(2, 500)))
        assertEquals(500L, q.audioSamples)
        assertNull(q.poll()!!.pcm)
        assertEquals(500, q.poll()!!.pcm!!.size)
    }

    @Test fun droppedLinesReturnTheirAudioToTheBudget() {
        val q = CueQueue(maxJobs = 2, maxSamples = 1_000)
        q.add(job(1, 400)); q.add(job(2, 400))
        q.add(job(3, 400))                                    // line 1 dropped by count
        assertEquals(800L, q.audioSamples)
    }

    @Test fun defaultBudgetIsTwoMinutesOfAudio() {
        assertEquals(24, CueQueue.MAX_JOBS)
        assertEquals(120L * AmbientConfig.SAMPLE_RATE, CueQueue.MAX_SAMPLES)
    }

    @Test fun withoutAudioIsTheSameLine() {
        val j = job(5, 10)
        val w = j.withoutAudio()
        assertNull(w.pcm)
        assertEquals(j.rowid, w.rowid); assertEquals(j.ts, w.ts); assertEquals(j.audioMs, w.audioMs)
        assertEquals(j.gapBeforeMs, w.gapBeforeMs); assertEquals(j.floorDb, w.floorDb, 0f)
        assertTrue(w.speakerTags)
        assertFalse(job(7, 10, tags = false).withoutAudio().speakerTags)
        assertTrue(j.db === w.db)
        val none = job(6, null)
        assertTrue(none.withoutAudio() === none)
    }

    // ── PcmTap (enrolment's copy of ambient capture) ──────────────────────

    @Test fun tapCollectsUpToItsLengthFromReusedBuffers() {
        val tap = PcmTap(5)
        val frame = shortArrayOf(1, 2, 3)
        tap.offer(frame)
        frame[0] = 9; frame[1] = 9; frame[2] = 9                // the caller reuses its buffer
        assertFalse(tap.full)
        tap.offer(shortArrayOf(4, 5, 6, 7), n = 2)
        assertTrue(tap.full)
        tap.offer(shortArrayOf(8))                              // extra is ignored
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), tap.take())
    }

    @Test fun tapWithTooLittleAudioGivesNothing() {
        val tap = PcmTap(10)
        assertNull(tap.take())
        tap.offer(ShortArray(4))
        assertNull(tap.take())                                  // under half: the mic wasn't really there
        tap.offer(ShortArray(1))
        assertEquals(5, tap.take()!!.size)
    }
}
