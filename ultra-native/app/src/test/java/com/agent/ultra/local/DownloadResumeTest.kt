package com.agent.ultra.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResumeTest {
    @Test fun noRangeForFreshStart() = assertNull(DownloadResume.rangeHeader(0))
    @Test fun rangeFromExisting() = assertEquals("bytes=500-", DownloadResume.rangeHeader(500))

    @Test fun partialAppends() =
        assertEquals(DownloadResume.Plan(true, 500, 1000), DownloadResume.plan(500, 206, 500, "bytes 500-999/1000"))
    @Test fun partialWrongStartRejected() = assertNull(DownloadResume.plan(500, 206, 500, "bytes 0-999/1000"))
    @Test fun partialUnknownTotalUsesLength() =
        assertEquals(1000L, DownloadResume.plan(500, 206, 500, "bytes 500-999/*")!!.total)
    @Test fun fullBodyRestarts() =
        assertEquals(DownloadResume.Plan(false, 0, 1000), DownloadResume.plan(500, 200, 1000, null))
    @Test fun alreadyCompleteOn416() = assertTrue(DownloadResume.plan(1000, 416, 0, "bytes */1000")!!.complete)
    @Test fun mismatched416Unusable() = assertNull(DownloadResume.plan(900, 416, 0, "bytes */1000"))
    @Test fun serverErrorUnusable() = assertNull(DownloadResume.plan(0, 503, -1, null))
    @Test fun notCompleteMidway() = assertFalse(DownloadResume.Plan(true, 500, 1000).complete)
    @Test fun unknownTotalNeverComplete() = assertFalse(DownloadResume.Plan(false, 0, -1).complete)

    @Test fun totalParsed() = assertEquals(1000L, DownloadResume.totalOf("bytes 0-1/1000"))
    @Test fun totalStarIsNull() = assertNull(DownloadResume.totalOf("bytes 0-1/*"))
    @Test fun startParsed() = assertEquals(42L, DownloadResume.rangeStart("bytes 42-99/100"))

    @Test fun backoffGrows() = assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L),
        (1..6).map { DownloadResume.backoffMs(it) })
}
