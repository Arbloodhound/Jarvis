package com.agent.jarvis.listen

import com.agent.jarvis.listen.WaitCue.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitCueTest {
    // Command 5 heard at t=1_000; the late cue is checked 20 s later.
    private val heard = 1_000L
    private val now = heard + WaitCue.LATE_AFTER_MS

    private fun late(
        seq: Long = 5, clearedUpTo: Long = 0, startedSeq: Long = 5, startedAt: Long = heard + 100,
        answeredAt: Long = 0, doneAt: Long = 0, hushed: Boolean = false, talking: Boolean = false,
        onItEnabled: Boolean = true,
    ) = WaitCue.late(seq, clearedUpTo, startedSeq, startedAt, answeredAt, doneAt, hushed, talking, onItEnabled)

    @Test fun timingsAreOnItThenTwentySeconds() {
        assertEquals(2_500L, WaitCue.ON_IT_AFTER_MS)
        assertEquals(20_000L, WaitCue.LATE_AFTER_MS)
        assertTrue(WaitCue.LATE_AFTER_MS > WaitCue.ON_IT_AFTER_MS)
    }

    @Test fun runningWithNothingSaidIsStillWorking() =
        assertEquals(Cue.STILL_WORKING, late())

    @Test fun answeredOrFinishedSaysNothing() {
        assertEquals(Cue.NONE, late(answeredAt = heard + 5_000))
        assertEquals(Cue.NONE, late(doneAt = heard + 5_000))
    }

    @Test fun anAnswerToAnEarlierCommandDoesNotCount() =
        assertEquals(Cue.STILL_WORKING, late(answeredAt = heard + 50))   // before this one started

    @Test fun stoppedOrReplacedSaysNothing() {
        assertEquals(Cue.NONE, late(clearedUpTo = 5))
        assertEquals(Cue.NONE, late(clearedUpTo = 7, startedSeq = 3))
    }

    @Test fun hushedSaysNothing() {
        assertEquals(Cue.NONE, late(hushed = true))
        assertEquals(Cue.NONE, late(hushed = true, startedSeq = 4, startedAt = 0))
    }

    @Test fun aLaterCommandHasStartedSoThisOneIsDone() =
        assertEquals(Cue.NONE, late(startedSeq = 6))

    @Test fun queuedBehindASilentRunIsStillBusy() =
        assertEquals(Cue.STILL_BUSY, late(startedSeq = 4, startedAt = 0, answeredAt = heard))

    @Test fun talkingSaysNothing() {
        assertEquals(Cue.NONE, late(startedSeq = 4, startedAt = 0, talking = true))
        assertEquals(Cue.NONE, late(talking = true))
    }

    @Test fun stillWorkingFollowsTheOnItSettingButTheBusyLineDoesNot() {
        assertEquals(Cue.NONE, late(onItEnabled = false))
        assertEquals(Cue.STILL_BUSY, late(startedSeq = 4, startedAt = 0, onItEnabled = false))
    }

    @Test fun talkingIsSpeechPlayingOrSomethingSaidJustNow() {
        assertTrue(WaitCue.talking(speaking = true, answeredAt = 0, nowMs = now))
        assertTrue(WaitCue.talking(speaking = false, answeredAt = now - 1_000, nowMs = now))
        assertFalse(WaitCue.talking(speaking = false, answeredAt = now - WaitCue.TALKING_MS, nowMs = now))
        assertFalse(WaitCue.talking(speaking = false, answeredAt = 0, nowMs = now))
        assertFalse("a clock that went back is not talking", WaitCue.talking(false, answeredAt = now + 1, nowMs = now))
    }

    @Test fun texts() {
        assertNull(WaitCue.text(Cue.NONE))
        assertEquals("Still working on it.", WaitCue.text(Cue.STILL_WORKING))
        assertEquals("I'm still busy with the last thing — I'll get to it, or say stop.", WaitCue.text(Cue.STILL_BUSY))
    }
}
