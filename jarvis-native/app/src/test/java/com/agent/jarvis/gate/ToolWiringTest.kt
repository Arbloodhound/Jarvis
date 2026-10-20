package com.agent.jarvis.gate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every tool Tools.execute handles is declared in the gate's manifest, and the other way round. A
 * handled tool missing from the manifest is refused by Gate.enforceCall as "undeclared_tool" on
 * every call (found 2026-10-07: ambient_recall was advertised to the model and always blocked).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolWiringTest {
    @Test fun everyHandledToolIsInTheManifestAndViceVersa() {
        val src = File("src/main/java/com/agent/jarvis/agent/Tools.kt").readText()
        val start = src.indexOf("return try {\n            when (tool) {")
        // Only the dispatch itself: tool functions below it have `when`s of their own.
        val end = src.indexOf("\n    private ", start).let { if (it < 0) src.length else it }
        val body = src.substring(start, end)
        val handled = Regex("^ {16}((?:\"[a-z_]+\"(?:,\\s*)?)+)\\s*->", RegexOption.MULTILINE).findAll(body)
            .flatMap { m -> Regex("\"([a-z_]+)\"").findAll(m.groupValues[1]).map { it.groupValues[1] } }.toSortedSet()
        val tools = JSONObject(File("src/main/assets/jarvis.manifest.json").readText()).getJSONArray("tools")
        val declared = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSortedSet()
        assertEquals("handled but not in the manifest", emptySet<String>(), handled - declared)
        assertEquals("in the manifest but not handled", emptySet<String>(), declared - handled)
        // The on-device loop's catalog: a tool it offers but the gate doesn't declare is always blocked.
        assertEquals("offered on-device but not in the manifest", emptySet<String>(),
            com.agent.jarvis.agent.LocalToolCall.TOOLS.toSortedSet() - declared)
    }
}
