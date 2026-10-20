package com.agent.jarvis.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolArgsTest {
    private fun obj(json: String) = JSONObject(json)

    @Test fun boolReadsJsonBooleansAndTheirStrings() {
        assertEquals(true, ToolArgs.bool(obj("""{"on":true}"""), "on"))
        assertEquals(false, ToolArgs.bool(obj("""{"on":false}"""), "on"))
        assertEquals(true, ToolArgs.bool(obj("""{"on":"TRUE"}"""), "on"))
        assertEquals(false, ToolArgs.bool(obj("""{"on":" false "}"""), "on"))
    }

    @Test fun boolIsNullForAnythingElse() {
        assertNull(ToolArgs.bool(obj("""{"on":"off"}"""), "on"))
        assertNull(ToolArgs.bool(obj("""{"on":"1"}"""), "on"))
        assertNull(ToolArgs.bool(obj("""{"on":1}"""), "on"))
        assertNull(ToolArgs.bool(obj("""{}"""), "on"))
        assertNull(ToolArgs.bool(obj("""{"on":null}"""), "on"))
    }

    @Test fun intReadsWholeNumbers() {
        assertEquals(50, ToolArgs.int(obj("""{"p":50}"""), "p"))
        assertEquals(50, ToolArgs.int(obj("""{"p":50.0}"""), "p"))
        assertEquals(50, ToolArgs.int(obj("""{"p":"50"}"""), "p"))
        assertEquals(50, ToolArgs.int(JSONObject().put("p", 50.0), "p"))
        assertEquals(-3, ToolArgs.int(obj("""{"p":" -3 "}"""), "p"))
    }

    @Test fun intIsNullForAnythingElse() {
        assertNull(ToolArgs.int(obj("""{"p":50.5}"""), "p"))
        assertNull(ToolArgs.int(obj("""{"p":"50.5"}"""), "p"))
        assertNull(ToolArgs.int(obj("""{"p":"loud"}"""), "p"))
        assertNull(ToolArgs.int(obj("""{"p":true}"""), "p"))
        assertNull(ToolArgs.int(obj("""{}"""), "p"))
        assertNull(ToolArgs.int(obj("""{"p":null}"""), "p"))
        assertNull(ToolArgs.int(obj("""{"p":99999999999}"""), "p"))
    }
}
