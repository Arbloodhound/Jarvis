package com.agent.jarvis.ui

import com.agent.jarvis.ui.ChatStatus.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStatusTest {
    @Test fun phaseTexts() {
        assertEquals("Loading the brain…", ChatStatus.label(Phase.LOADING, 0))
        assertEquals("Thinking…", ChatStatus.label(Phase.THINKING, 0))
    }

    @Test fun secondsOnlyOnceNoticeable() {
        assertEquals("Thinking…", ChatStatus.label(Phase.THINKING, 2_999))
        assertEquals("Thinking… 3 s", ChatStatus.label(Phase.THINKING, 3_000))
        assertEquals("Loading the brain… 42 s", ChatStatus.label(Phase.LOADING, 42_900))
    }

    @Test fun navigatingShowsTheStep() {
        assertEquals("Working on the phone…", ChatStatus.label(Phase.NAVIGATING, 0))
        assertEquals("Working on the phone… step 3/12", ChatStatus.label(Phase.NAVIGATING, 0, "step 3/12"))
        assertEquals("Working on the phone… step 3/12 · tap 4 7 s", ChatStatus.label(Phase.NAVIGATING, 7_000, "step 3/12 · tap 4"))
    }

    @Test fun negativeElapsedShowsNoSeconds() =
        assertEquals("Thinking…", ChatStatus.label(Phase.THINKING, -5_000))

    @Test fun thoughtOfKeepsOnlyTheThinkingTrace() {
        assertEquals("check the alarm list", ChatStatus.thoughtOf(ChatStatus.Status(ChatStatus.Phase.THINKING, 0, "💭 …check the alarm list")))
        assertEquals(null, ChatStatus.thoughtOf(ChatStatus.Status(ChatStatus.Phase.THINKING, 0, "searching the web")))
        assertEquals(null, ChatStatus.thoughtOf(ChatStatus.Status(ChatStatus.Phase.THINKING, 0, "💭 …")))
        assertEquals(null, ChatStatus.thoughtOf(ChatStatus.Status(ChatStatus.Phase.LOADING, 0, "💭 x")))
    }
}
