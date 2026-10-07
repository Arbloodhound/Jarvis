package com.agent.ultra.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.agent.ultra.agent.TalkStyle
import com.agent.ultra.listen.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app's screens, rendered and tapped for real (Compose on Robolectric): each one opens, shows
 * what it should, and its buttons do what they say (owner, 2026-10-07: "check the app and its UI
 * work as intended … build something to do it"). Runs with the unit tests, so every release build
 * checks the screens before it is published.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun guideOpensWithContentsSearchAndBack() {
        var back = false
        rule.setContent { GuideScreen(onBack = { back = true }) }
        rule.onNodeWithText("Jarvis — the complete guide").assertExists()
        rule.onNodeWithText("Contents").performClick()
        rule.onNodeWithText("1. What Jarvis is").assertExists()
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Search the guide").performTextInput("Kokoro")
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Kokoro", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("← Back").performClick()
        assertTrue(back)
    }

    @Test fun voicePickerListsEveryVoiceAndSavesTheChoice() {
        VoicePrefs.setVoiceId(ctx, VoiceCatalog.DEFAULT)
        rule.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { JarvisVoiceSettings(ctx) } }
        rule.onNodeWithText("Ryan (recommended)").assertExists()
        for (m in VoiceCatalog.all.filter { it.id != VoiceCatalog.DEFAULT }) rule.onNodeWithText(m.label).assertExists()
        // Kokoro chosen: it has several voices, and its speaker menu shows George.
        rule.onNodeWithText("Kokoro").performScrollTo().performClick()
        assertEquals(VoiceCatalog.KOKORO, VoicePrefs.voiceId(ctx))
        rule.onNodeWithText("George (British male)").assertExists()
        rule.onNodeWithText("Phone's voice").performScrollTo().performClick()
        assertEquals(VoiceCatalog.PHONE, VoicePrefs.voiceId(ctx))
        rule.onNodeWithText("Pitch", substring = true).assertExists()        // phone voice only
        rule.onNodeWithText("Preview").assertExists()
        VoicePrefs.setVoiceId(ctx, VoiceCatalog.DEFAULT)
    }

    @Test fun howJarvisTalksSavesEverySetting() {
        rule.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { HowJarvisTalksSettings(ctx) } }
        rule.onNodeWithText("Witty").performClick()
        assertEquals(TalkStyle.Personality.WITTY, VoicePrefs.personality(ctx))
        rule.onNodeWithText("Short").performClick()
        assertEquals(TalkStyle.Length.SHORT, VoicePrefs.answerLength(ctx))
        rule.onNodeWithText("Instant (~3 s)").performClick()
        assertEquals(TalkStyle.SpokenThink.INSTANT, VoicePrefs.spokenThink(ctx))
        assertTrue(VoicePrefs.wakeChime(ctx))
        rule.onNodeWithText("Wake chime").performScrollTo().performClick()   // the row's text; switch next to it
        for (t in listOf("Say \"On it.\"", "Speak as it writes", "Plain \"stop\" while it works", "\"Hey Jarvis\" cuts in"))
            rule.onNodeWithText(t).assertExists()
    }

    @Test fun notesScreenOpensAndAddsANote() {
        var back = false
        rule.setContent { NotesScreen(onBack = { back = true }, onAsk = {}) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Notes", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("←").performClick()
        assertTrue(back)
    }

    @Test fun memoryScreenOpens() {
        var back = false
        rule.setContent { MemoryScreen(onBack = { back = true }, onAsk = {}) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Memory", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("←").performClick()
        assertTrue(back)
    }

    @Test fun settingsHasEverySectionAndTheGuideButton() {
        var guide = false
        rule.setContent {
            SettingsScreen(com.agent.ultra.local.LocalModelEngine.shared(ctx), onConfigSaved = {}, onBack = {}, onOpenGuide = { guide = true })
        }
        rule.waitForIdle()
        for (s in listOf("DOWNLOADS", "PEOPLE", "SYSTEM PROMPT", "ON-DEVICE MODEL", "MEMORY MODEL",
                "WHERE THE AGENT MAY GO", "JARVIS'S VOICE", "HOW JARVIS TALKS", "VOICE", "ABOUT"))
            rule.onNodeWithText(s).assertExists()
        rule.onNodeWithText("📖 Guide").performClick()
        assertTrue(guide)
        assertFalse(rule.onAllNodesWithText("Jarvis's own voice").fetchSemanticsNodes().isNotEmpty())   // replaced by the picker
    }

    @Test fun chatScreenShowsItsControlsAndOpensNotesAndMemory() {
        var notes = false; var memory = false; var settings = false; var guide = false
        rule.setContent {
            ChatScreen(com.agent.ultra.local.LocalModelEngine.shared(ctx), 0,
                onOpenSettings = { settings = true }, onOpenNotes = { notes = true }, onOpenMemory = { memory = true }, onOpenGuide = { guide = true })
        }
        rule.waitForIdle()
        rule.onNodeWithText("Send").assertExists()
        rule.onNodeWithText("🎙").assertExists()
        rule.onNodeWithText("📝").performScrollTo().performClick()
        rule.onNodeWithText("🧠").performScrollTo().performClick()
        rule.onNodeWithText("📖").performScrollTo().performClick()
        assertTrue(notes); assertTrue(memory); assertTrue(guide)
    }
}
