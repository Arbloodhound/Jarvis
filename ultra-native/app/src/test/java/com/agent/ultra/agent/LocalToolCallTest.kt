package com.agent.ultra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolCallTest {

    // ── routing: which offline requests try the tool loop ──

    @Test fun deviceCommandsRouteToTheToolLoop() {
        for (s in listOf("turn on the flashlight", "open chrome", "what's my battery level",
                         "turn off wifi", "set an alarm for 7 30 am", "note this buy milk"))
            assertTrue(s, LocalToolCall.offlineRoute(s, canDecline = true))
    }

    @Test fun questionsThatShareDeviceWordsStayText() {
        for (s in listOf("how do I open a jar", "explain how bluetooth works", "why won't my wifi turn on"))
            assertFalse(s, LocalToolCall.offlineRoute(s, canDecline = true))
    }

    @Test fun nonDeviceAndUnsupportedRequestsStayText() {
        for (s in listOf("what is the capital of France", "hi", "set a timer for 5 minutes",
                         "turn on wifi and then open chrome", "open example.com"))
            assertFalse(s, LocalToolCall.offlineRoute(s, canDecline = true))
    }

    @Test fun ambientRequestsRouteOnTheCommandAlone() {
        val wrap = "Overheard: we should open the window and check the battery later.\n" + IntentRouter.REQUEST_MARKER
        assertTrue(LocalToolCall.offlineRoute(wrap + "turn on the flashlight", canDecline = true))
        assertFalse(LocalToolCall.offlineRoute(wrap + "what is the capital of France", canDecline = true))
    }

    @Test fun aModelThatCannotDeclineOnlyGetsRealDeviceCommands() {
        // The 1B prompt always names a tool: a keyword-shaped question would change the volume.
        for (s in listOf("what is the volume of a sphere", "how open are you to ideas"))
            assertFalse(s, LocalToolCall.offlineRoute(s, canDecline = false))
        for (s in listOf("turn on the flashlight", "open chrome", "what's my battery level", "turn off wifi"))
            assertTrue(s, LocalToolCall.offlineRoute(s, canDecline = false))
    }

    // ── prompt ──

    @Test fun promptIsTheModelsTemplateOpenedInThink() {
        val p = LocalToolCall.prompt("turn on the flashlight")
        assertTrue(p.startsWith("<|im_start|>system\n"))
        assertTrue(p.contains("<|im_start|>user\nturn on the flashlight<|im_end|>"))
        assertTrue(p.endsWith("<|im_start|>assistant\n<think>\n"))
    }

    @Test fun promptOffersEveryToolAndTheWayOut() {
        for (t in LocalToolCall.TOOLS) assertTrue(t, LocalToolCall.SYSTEM.contains("$t {"))
        assertTrue(LocalToolCall.SYSTEM.contains("{\"tool\":\"none\",\"params\":{}}"))
    }

    @Test fun promptCarriesOnlyTheCommandAndTheRecalledHint() {
        val p = LocalToolCall.prompt("chatter chatter\n" + IntentRouter.REQUEST_MARKER + "open chrome", "app_launch")
        assertFalse(p.contains("chatter"))
        assertTrue(p.contains("<|im_start|>user\nopen chrome<|im_end|>"))
        assertTrue(p.contains("previously worked with: app_launch"))
    }

    @Test fun budgetIsTheDeviceTier() = assertEquals(IntentRouter.Tier.DEVICE.thinkBudget, LocalToolCall.thinkBudget)

    // ── reading the call out of the post-think text ──

    private fun call(raw: String): LocalToolCall.Parsed.Call {
        val p = LocalToolCall.extract(raw)
        assertTrue("expected a call from: $raw, got $p", p is LocalToolCall.Parsed.Call)
        return p as LocalToolCall.Parsed.Call
    }

    @Test fun plainCall() {
        val c = call("{\"tool\":\"flashlight_toggle\",\"params\":{\"on\":true}}")
        assertEquals("flashlight_toggle", c.tool)
        assertTrue(c.params.getBoolean("on"))
    }

    @Test fun leftoverCloseTagAndWhitespaceAreTolerated() {
        val c = call("the user wants light</think>\n\n  {\"tool\":\"wifi_toggle\",\"params\":{\"on\":false}}\n")
        assertEquals("wifi_toggle", c.tool)
        assertFalse(c.params.getBoolean("on"))
    }

    @Test fun completeTraceEndMarkerAndFencesAreTolerated() {
        assertEquals("battery_status", call("<think>battery</think>{\"tool\":\"battery_status\",\"params\":{}}<|im_end|>").tool)
        assertEquals("app_launch", call("```json\n{\"tool\":\"app_launch\",\"params\":{\"target\":\"chrome\"}}\n```").tool)
    }

    @Test fun noneIsADecline() {
        assertEquals(LocalToolCall.Parsed.Declined, LocalToolCall.extract("{\"tool\":\"none\",\"params\":{}}"))
        assertEquals(LocalToolCall.Parsed.Declined, LocalToolCall.extract("\n{\"tool\":\"none\"}"))
    }

    @Test fun toolsOutsideTheOfferedSetAreNotRun() {
        assertEquals(LocalToolCall.Parsed.Unparsed,
            LocalToolCall.extract("{\"tool\":\"react_navigate\",\"params\":{\"goal\":\"hi\"}}"))
        assertEquals(LocalToolCall.Parsed.Unparsed,
            LocalToolCall.extract("{\"tool\":\"react_navigate\",\"params\":{\"goal\":\"x\"}}"))
    }

    @Test fun proseEmptyAndUnfinishedTracesAreUnparsed() {
        assertEquals(LocalToolCall.Parsed.Unparsed, LocalToolCall.extract("Sure, turning it on now."))
        assertEquals(LocalToolCall.Parsed.Unparsed, LocalToolCall.extract("   \n"))
        assertEquals(LocalToolCall.Parsed.Unparsed, LocalToolCall.extract("<think>still deciding {\"tool\":\"wifi_toggle\""))
    }

    @Test fun aReplyThatIsOnlyAToolNameIsABareName() {
        assertEquals(LocalToolCall.Parsed.BareName("flashlight_toggle"), LocalToolCall.extract("hm</think>\n`flashlight_toggle`\n"))
    }

    @Test fun toolNamesInsideProseOrForeignJsonAreNotCalls() {
        // A name mentioned while rejecting it, or inside another tool's params, must not run.
        assertEquals(LocalToolCall.Parsed.Unparsed,
            LocalToolCall.extract("This isn't wifi_toggle or anything else; no tool applies."))
        assertEquals(LocalToolCall.Parsed.Unparsed,
            LocalToolCall.extract("{\"tool\":\"react_navigate\",\"params\":{\"message\":\"use note_create\"}}"))
    }

    @Test fun visibleTextDropsTraceAndMarkers() {
        assertEquals("flashlight_toggle", LocalToolCall.visibleText("hm</think>\n flashlight_toggle <|im_end|>"))
        assertEquals("ok", LocalToolCall.visibleText("ok<think>trailing trace"))
    }
}

class LocalToolCallCallsTest {
    @org.junit.Test fun callsAndTextsAreOffered() {
        for (t in listOf("call_phone", "call_answer", "call_end", "call_speaker", "sms_send"))
            org.junit.Assert.assertTrue(t, t in LocalToolCall.TOOLS)
        val c = LocalToolCall.extract("{\"tool\":\"call_phone\",\"params\":{\"to\":\"mom\"}}")
        org.junit.Assert.assertTrue(c is LocalToolCall.Parsed.Call && c.tool == "call_phone")
    }
    @org.junit.Test fun callRequestsRouteToTheToolLoop() {
        for (r in listOf("call mom", "hang up", "put it on speaker", "text Sam I am running late", "answer the call"))
            org.junit.Assert.assertTrue(r, Brain.isSimpleLocalIntent(r))
    }
}
