package com.agent.ultra

import com.agent.ultra.gate.Gate
import com.agent.ultra.gate.Manifest
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the user actually said a thing, or it merely appears inside what
 * they said.
 *
 * Being wrong here in one direction refuses something they asked for, which
 * they see and can confirm. Being wrong in the other permits something they
 * never asked for, which they never see at all. The rule leans accordingly.
 */
class OriginTest {

    private val gate = Gate(Manifest.fromJson(JSONObject("""{"tools":[]}""")))

    private fun said(value: String, request: String) = gate.saidByUser(value, request)

    @Test
    fun `a word buried inside other words is not the user asking for it`() {
        // The old rule was plain containment, so all of these passed as "the
        // user said it". A check that anything short passes is not a check.
        assertFalse(said("on", "turn the flashlight off please"))
        assertFalse(said("1", "open the 12th item"))
        assertFalse(said("set", "open settings"))
    }

    @Test
    fun `a common word the user really did say still counts, and that is the limit`() {
        // Honest about what this does not fix. Whole-word matching narrows the
        // hole a great deal but cannot close it: in "send a message" the user
        // genuinely said "a", so an argument of "a" is traceable to them. The
        // remaining defence is that a value that trivial is not a meaningful
        // target for any tool.
        assertTrue(said("a", "send a message to the office"))
        assertTrue(said("to", "navigate to the settings screen"))
    }

    @Test
    fun `a word the user really said is theirs`() {
        assertTrue(said("on", "turn the flashlight on"))
        assertTrue(said("settings", "open settings"))
    }

    @Test
    fun `a phrase must appear in order and unbroken`() {
        assertTrue(said("morning briefing", "run my morning briefing now"))
        assertFalse(said("morning briefing", "briefing in the morning"))
        assertFalse(said("morning briefing", "morning report and evening briefing"))
    }

    @Test
    fun `a fragment of a longer word is not a word`() {
        // The same shape that made "tor" match Calculator.
        assertFalse(said("bank", "open my banking app"))
        assertFalse(said("cat", "check the category list"))
    }

    @Test
    fun `punctuation around what they said does not hide it`() {
        assertTrue(said("news.ycombinator.com", "open news.ycombinator.com."))
        assertTrue(said("settings", "open settings, then wifi"))
        assertTrue(said("hello", """send "hello" to sam"""))
    }

    @Test
    fun `capitals and spacing do not matter`() {
        assertTrue(said("Settings", "open   SETTINGS  now"))
    }

    @Test
    fun `nothing is not something the user said`() {
        assertFalse(said("", "open settings"))
        assertFalse(said("settings", ""))
    }

    @Test
    fun `a value longer than the request cannot have been said`() {
        assertFalse(said("open the settings screen and turn on wifi", "wifi"))
    }
}
