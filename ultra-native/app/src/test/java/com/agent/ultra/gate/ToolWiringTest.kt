package com.agent.ultra.gate

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
        val src = File("src/main/java/com/agent/ultra/agent/Tools.kt").readText()
        val body = src.substring(src.indexOf("return try {\n            when (tool) {"))
        val handled = Regex("^ {16}((?:\"[a-z_]+\"(?:,\\s*)?)+)\\s*->", RegexOption.MULTILINE).findAll(body)
            .flatMap { m -> Regex("\"([a-z_]+)\"").findAll(m.groupValues[1]).map { it.groupValues[1] } }.toSortedSet()
        val tools = JSONObject(File("src/main/assets/ultra.manifest.json").readText()).getJSONArray("tools")
        val declared = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSortedSet()
        assertEquals("handled but not in the manifest", emptySet<String>(), handled - declared)
        assertEquals("in the manifest but not handled", emptySet<String>(), declared - handled)
    }
}
