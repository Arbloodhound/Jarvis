package com.agent.ultra.agent

import com.agent.ultra.ui.ChatStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietStageTest {

    private fun stage(
        entered: Boolean = true, tool: String? = null, cloud: Boolean = false,
        phase: ChatStatus.Phase? = null, detail: String = "",
    ) = QuietStage.stage(entered, tool, cloud, phase, detail)

    @Test fun notYetHoldingTheLockIsWaitingForIt() {
        assertEquals("waiting-for-run-lock", stage(entered = false, tool = "web_search", cloud = true,
            phase = ChatStatus.Phase.LOADING))
    }

    @Test fun aRunningToolComesFirst() {
        assertEquals("tool:web_search", stage(tool = "web_search", cloud = true, phase = ChatStatus.Phase.THINKING))
    }

    @Test fun thenAnOpenCloudCall() {
        assertEquals("cloud call", stage(cloud = true, phase = ChatStatus.Phase.THINKING))
    }

    @Test fun thenTheStatusLine() {
        assertEquals("loading model", stage(phase = ChatStatus.Phase.LOADING))
        assertEquals("navigating", stage(phase = ChatStatus.Phase.NAVIGATING, detail = "step 3/12"))
        assertEquals("thinking", stage(phase = ChatStatus.Phase.THINKING, detail = "💭 …so the user wants"))
        assertEquals("web fetch", stage(phase = ChatStatus.Phase.THINKING, detail = "searching the web"))
        assertEquals("prefill-or-thinking", stage(phase = ChatStatus.Phase.THINKING))
    }

    @Test fun nothingUpIsWorking() {
        assertEquals("working", stage())
    }

    @Test fun theStageNeverCarriesTheThoughtOrSearchText() {
        for (detail in listOf("💭 …my PIN is 1234", "reading bbc.co.uk/news?q=secret")) {
            val s = stage(phase = ChatStatus.Phase.THINKING, detail = detail)
            assertTrue(s, s in setOf("thinking", "web fetch"))
        }
    }

    @Test fun aToolNameKeepsOnlyNameCharacters() {
        assertEquals("sms_send", QuietStage.toolName("sms_send"))
        assertEquals("sms_sendmessagehi", QuietStage.toolName("sms_send message=\"hi\""))
        assertEquals(40, QuietStage.toolName("a".repeat(100)).length)
        assertEquals("?", QuietStage.toolName("  \"!"))
    }

    @Test fun theRouteCountsOnlyWhenRecordedForThisRequest() {
        assertEquals("cloud/request", QuietStage.route("cloud/request", 1_000L, 1_000L))
        assertEquals("cloud/request", QuietStage.route("cloud/request", 1_500L, 1_000L))
        assertEquals("undecided", QuietStage.route("cloud/request", 999L, 1_000L))
        assertEquals("undecided", QuietStage.route(null, 5_000L, 1_000L))
    }
}
