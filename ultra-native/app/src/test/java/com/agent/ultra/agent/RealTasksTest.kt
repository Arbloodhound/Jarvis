package com.agent.ultra.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Twenty everyday requests and the on-device path each one takes (owner, 2026-10-19: "a set of
 * 20 real tasks with a pass rate"). Mirrors the order in Brain's on-device branch: a screen job
 * that opens an app first, then the tool loop (a model-free command when FastCommand matches),
 * then the screen driver, else a text answer. Apps are assumed installed.
 *
 * This proves the routing only. Whether the model then picks the right tool, and the tool works,
 * is checked on the phone: the same list is in tools/REAL_TASKS.md.
 */
class RealTasksTest {
    private fun route(t: String): String {
        val req = IntentRouter.requestPart(t)
        if (OfflineNav.opensAppFirst(req) && OfflineNav.task(req) != null) return "nav"
        if (LocalToolCall.offlineRoute(t, canDecline = true, chains = true))
            return FastCommand.match(req)?.let { "fast:${it.first}" } ?: "tool"
        if (OfflineNav.task(req) != null) return "nav"
        return "chat"
    }

    @Test fun twentyRealTasksTakeTheRightPath() {
        val tasks = listOf(
            "turn on the flashlight" to "fast:flashlight_toggle",
            "what's my battery level" to "fast:battery_status",
            "set the volume to 30 percent" to "fast:volume_set",
            "turn off wifi" to "fast:wifi_toggle",
            "call mom" to "fast:call_phone",
            "text Sam I'm running late" to "tool",
            "set an alarm for 7am" to "tool",
            "make a note: buy milk" to "tool",
            "open spotify" to "tool",
            "search the web for the weather in Miami" to "tool",
            "what's on my screen" to "tool",
            "open settings and turn on dark mode" to "nav",
            "open youtube and search for cat videos" to "nav",
            "call mom and then text Sam I'm on my way" to "tool",
            "take a screenshot" to "fast:screenshot",
            "go on google and search for pizza near me" to "nav",
            "what's the capital of France" to "chat",
            "write me a short poem about the sea" to "chat",
            "hang up" to "fast:call_end",
            "turn on do not disturb" to "fast:do_not_disturb",
        )
        val wrong = tasks.filter { (t, want) -> route(t) != want }.map { (t, want) -> "$t: want $want, got ${route(t)}" }
        println("REAL TASKS: ${tasks.size - wrong.size}/${tasks.size} on the right path")
        assertEquals(emptyList<String>(), wrong)
    }
}
