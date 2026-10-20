package com.agent.jarvis.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkStyleTest {
    @Test fun plainAndNormalAddNothing() =
        assertEquals("", TalkStyle.instructions(TalkStyle.Personality.PLAIN, TalkStyle.Length.NORMAL))

    @Test fun personalityAndLengthBothGoIn() {
        val s = TalkStyle.instructions(TalkStyle.Personality.BUTLER, TalkStyle.Length.SHORT)
        assertTrue(s.contains("butler")); assertTrue(s.contains("one or two short sentences"))
        assertEquals(2, s.lines().size)
    }

    @Test fun combineSkipsBlanks() {
        assertEquals("a", TalkStyle.combine("a", "  "))
        assertEquals("b", TalkStyle.combine("", "b"))
        assertEquals("a\nb", TalkStyle.combine("a", "b"))
    }

    @Test fun thinkTimesAreOrdered() {
        val s = TalkStyle.SpokenThink.values().map { it.seconds }
        assertEquals(s.sorted(), s)
        assertEquals(WaitBudget.SPOKEN_SILENT_S, TalkStyle.SpokenThink.QUICK.seconds, 0.0)
    }

    @Test fun spokenBudgetFollowsTheThinkTime() {
        assertEquals(27, WaitBudget.spoken(512, 9.0, 3.0))
        assertEquals(108, WaitBudget.spoken(512, 9.0, 12.0))
        assertEquals(WaitBudget.SPOKEN_UNKNOWN_SPEED_TOKENS, WaitBudget.spoken(512, null, WaitBudget.SPOKEN_SILENT_S))
    }
}
