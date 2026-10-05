package com.agent.ultra

import com.agent.ultra.agent.ModelOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading what the model actually said.
 *
 * Both parsers were written for a model that answers exactly as asked. Real
 * ones do not, and the cost was out of all proportion: one unparseable line
 * ended a fifteen-step task after half the work was already done.
 */
class ModelOutputTest {

    // ── Tool calls ──────────────────────────────────────────────────

    @Test
    fun `a plain tool call is read`() {
        val (tool, params) = ModelOutput.toolCall(
            """{"tool":"battery_status","params":{}}""")!!
        assertEquals("battery_status", tool)
        assertEquals(0, params.length())
    }

    @Test
    fun `a brace inside a value no longer truncates the call`() {
        // This silently degraded to a plain answer: the count closed early, the
        // substring was invalid JSON, and the user saw the agent SAY something
        // instead of DO something, with nothing explaining why.
        val (tool, params) = ModelOutput.toolCall(
            """{"tool":"sms_send","params":{"message":"see you at 8 {maybe}"}}""")!!
        assertEquals("sms_send", tool)
        assertEquals("see you at 8 {maybe}", params.getString("message"))
    }

    @Test
    fun `an escaped quote inside a value is not the end of it`() {
        val (_, params) = ModelOutput.toolCall(
            """{"tool":"sms_send","params":{"message":"he said \"hi\" {x}"}}""")!!
        assertTrue(params.getString("message").contains("hi"))
    }

    @Test
    fun `a fenced call with chatter around it is read`() {
        val (tool, _) = ModelOutput.toolCall(
            "Sure, I'll check that.\n```json\n{\"tool\":\"battery_status\",\"params\":{}}\n```\nOne moment.")!!
        assertEquals("battery_status", tool)
    }

    @Test
    fun `prose with no call is not a call`() {
        assertNull(ModelOutput.toolCall("Your battery is at 80% and charging."))
        assertNull(ModelOutput.toolCall(""))
    }

    @Test
    fun `an object with no tool name is not a call`() {
        assertNull(ModelOutput.toolCall("""{"params":{"x":1}}"""))
    }

    @Test
    fun `an unterminated object is refused rather than half-read`() {
        assertNull(ModelOutput.firstJsonObject("""{"tool":"x","params":{"a":1"""))
    }

    // ── Navigator actions ───────────────────────────────────────────

    @Test
    fun `a bare action is read`() {
        assertEquals("tap(5)", ModelOutput.action("tap(5)"))
        assertEquals("back()", ModelOutput.action("back()"))
        assertEquals("scroll(down)", ModelOutput.action("scroll(down)"))
    }

    @Test
    fun `an ACTION line wins, with its comment stripped`() {
        assertEquals("tap(12)", ModelOutput.action("ACTION: tap(12) // the menu button"))
    }

    @Test
    fun `a closing bracket in typed text no longer truncates it`() {
        // type("call me :)") used to type "call me :" and lose the rest.
        assertEquals("""type("call me :)")""", ModelOutput.action("""type("call me :)")"""))
    }

    @Test
    fun `a chatty reply that ends in a real action still works`() {
        // This used to end the whole run.
        val reply = """
            Looking at the screen, the History entry is not visible yet, so I
            should open the overflow menu first. That is index 12.

            tap(12)
        """.trimIndent()
        assertEquals("tap(12)", ModelOutput.action(reply))
    }

    @Test
    fun `an ACTION line that is not an action falls through to a real one`() {
        // "ACTION: I will now tap the menu" used to become a literal action
        // string that no executor understood, failing the step for a reason the
        // model could not see.
        val reply = "ACTION: I will now tap the menu\nActually the right one is tap(7)"
        assertEquals("tap(7)", ModelOutput.action(reply))
    }

    @Test
    fun `tap_index is not mistaken for tap`() {
        assertEquals("tap_index(3)", ModelOutput.action("tap_index(3)"))
    }

    @Test
    fun `done is recognised in a sentence`() {
        assertEquals("done", ModelOutput.action("The page is showing, so I am done"))
    }

    @Test
    fun `a reply with no action at all is still null`() {
        assertNull(ModelOutput.action("I am not sure what to do here."))
        assertNull(ModelOutput.action(""))
    }

    @Test
    fun `typed text with a single quote style works too`() {
        assertEquals("""type('hello')""", ModelOutput.action("""type('hello')"""))
    }

    // qwen3-235b, after a lesson worded "What worked: app_launch {…}" (2026-09-20).
    @Test
    fun aCallWrittenTheWayLessonsWriteThemIsStillACall() {
        val got = ModelOutput.toolCall("app_launch {\"target\":\"clock\"}")
        assertEquals("app_launch", got?.first)
        assertEquals("clock", got?.second?.optString("target"))
        assertNull("a sentence that mentions a tool is prose", ModelOutput.toolCall("I used app_launch {\"target\":\"clock\"} earlier and it worked."))
    }

    // llama-3.3-70b stopped mid-call (AndroidWorld MarkorCreateNote, 2026-09-20).
    @Test
    fun aCallCutShortIsClosedAndStillACall() {
        val got = ModelOutput.toolCall("{\"tool\":\"react_navigate\",\"params\":{\"goal\":\"Create a note with the text 'Cleanliness is next to godliness.'")
        assertEquals("react_navigate", got?.first)
        assertTrue(got!!.second.optString("goal").contains("Cleanliness"))
        assertNull(ModelOutput.toolCall("The tool {\"tool\" is what I would call"))
    }
}
