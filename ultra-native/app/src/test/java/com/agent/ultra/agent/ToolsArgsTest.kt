package com.agent.ultra.agent

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device toggles and volume refuse a missing or unreadable value instead of guessing one
 * (optBoolean("on", true) used to turn Wi-Fi ON for {} and for {"on":"off"}).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolsArgsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun run(tool: String, json: String) =
        runBlocking { Tools(ctx, AgentController(ctx)).execute(tool, JSONObject(json)) }

    @Test fun togglesRefuseAMissingOrUnreadableOn() {
        for (tool in listOf("flashlight_toggle", "wifi_toggle", "bluetooth_toggle", "do_not_disturb")) {
            for (json in listOf("{}", """{"on":"off"}""", """{"on":null}""")) {
                val r = run(tool, json)
                assertTrue("$tool $json -> $r", r.startsWith("Error: $tool needs \"on\""))
            }
        }
    }

    @Test fun volumeRefusesAMissingOrFractionalPercent() {
        for (json in listOf("{}", """{"percent":"loud"}""", """{"percent":50.5}""")) {
            val r = run("volume_set", json)
            assertTrue("volume_set $json -> $r", r.startsWith("Error: volume_set needs \"percent\""))
        }
    }

    @Test fun navigateRefusesABlankGoal() {
        val r = run("react_navigate", """{"goal":"  "}""")
        assertTrue(r, r.startsWith("Error: react_navigate needs a 'goal'"))
    }
}
