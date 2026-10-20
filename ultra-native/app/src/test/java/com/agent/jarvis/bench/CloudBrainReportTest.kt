package com.agent.jarvis.bench

import com.agent.jarvis.agent.Brain
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudBrainReportTest {
    private val manifest: Set<String> by lazy {
        val tools = JSONObject(File("src/main/assets/ultra.manifest.json").readText()).getJSONArray("tools")
        (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSortedSet()
    }

    @Test fun theRealCatalogNamesOnlyRealTools() {
        val offered = CloudBrainReport.named(Brain.TOOL_CATALOG, manifest)
        assertTrue("the catalog offers tools", offered.size >= 30)
        // Argument values (global_action's actions, connector ids) are not flagged: the phone report
        // listed them as unknown tools (results 2026-10-17 14:32).
        assertEquals("named in the cloud catalog but not a real tool", emptySet<String>(),
            CloudBrainReport.unknownNames(Brain.TOOL_CATALOG, manifest))
        assertTrue(CloudBrainReport.ARG_VALUES.none { it in manifest })
        println("real tools never offered to the cloud: ${manifest - offered}")
    }

    @Test fun wholeWordsOnly() {
        val m = setOf("sms_read", "read_screen_deep", "read_text_on_screen")
        assertEquals(setOf("read_screen_deep"), CloudBrainReport.named("use read_screen_deep, not my_sms_reader", m))
    }

    @Test fun selfCheckFindsMissedAndInventedTools() {
        val m = setOf("web_search", "sms_send", "sms_read", "app_launch")
        val offered = setOf("web_search", "sms_send", "sms_read")
        val reply = "web_search\nsms_send\nphone_call\n\nCANNOT:\n- see images (image_view is not mine)\nUNCLEAR: none"
        val sc = CloudBrainReport.selfCheck(reply, offered, m)
        assertEquals(setOf("web_search", "sms_send"), sc.listed)
        assertEquals(setOf("sms_read"), sc.missed)
        assertEquals("only the tool list counts, not the CANNOT part", setOf("phone_call"), sc.invented)
    }

    @Test fun deadToolsAreOnlyOfferedOnesWithAccessOff() {
        val access = listOf(CloudBrainReport.Access("Accessibility service", false),
            CloudBrainReport.Access("SMS send", true), CloudBrainReport.Access("Contacts", false))
        val dead = CloudBrainReport.deadTools(access, setOf("react_navigate", "sms_send", "web_search"))
        assertEquals(listOf("react_navigate — offered to the model, but Accessibility service is off"), dead)
    }

    @Test fun everyAccessMappedToolIsARealTool() {
        assertEquals(emptySet<String>(), CloudBrainReport.NEEDS.values.flatten().toSet() - manifest)
    }

    @Test fun summaryHostAndTokens() {
        assertEquals("api.anthropic.com", CloudBrainReport.host("https://api.anthropic.com/v1?x=1"))
        assertEquals(3, CloudBrainReport.approxTokens(10))
        assertEquals(listOf("#1 system · 9 chars · You are J"),
            CloudBrainReport.requestSummary(listOf("system" to "You are\nJ"), preview = 9))
    }
}
