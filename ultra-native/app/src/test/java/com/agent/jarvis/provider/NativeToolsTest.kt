package com.agent.jarvis.provider

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolsTest {
    @Test fun streamedFragmentsBecomeTheTextProtocolCall() {
        val acc = NativeTools.CallAccumulator()
        acc.add(JSONArray("""[{"index":0,"id":"c1","function":{"name":"wifi_","arguments":""}}]"""))
        acc.add(JSONArray("""[{"index":0,"function":{"name":"toggle","arguments":"{\"on\":"}}]"""))
        acc.add(JSONArray("""[{"index":0,"function":{"arguments":"false}"}}]"""))
        val call = JSONObject(acc.asTextCall()!!)
        assertEquals("wifi_toggle", call.getString("tool"))
        assertEquals(false, call.getJSONObject("params").getBoolean("on"))
    }

    @Test fun noCallIsNullAndBadArgumentsAreEmpty() {
        assertNull(NativeTools.CallAccumulator().asTextCall())
        val acc = NativeTools.CallAccumulator()
        acc.add(JSONArray("""[{"index":0,"function":{"name":"screenshot","arguments":"{oops"}}]"""))
        assertEquals("{}", JSONObject(acc.asTextCall()!!).getJSONObject("params").toString())
    }

    @Test fun definitionsUseTheCatalogLine() {
        val defs = NativeTools.definitions(listOf("sms_read", "app_launch"), "  app_launch — open app. params: {target}\n  sms_read — read inbox.")
        assertEquals("app_launch", defs.getJSONObject(0).getJSONObject("function").getString("name"))
        assertTrue(defs.getJSONObject(0).getJSONObject("function").getString("description").startsWith("app_launch — open app"))
    }

    @Test fun rejectionIsOnlyAToolComplaint() {
        assertTrue(NativeTools.rejectedTools("HTTP 400: {\"error\":\"tools are not supported for this model\"}"))
        assertFalse(NativeTools.rejectedTools("HTTP 401: invalid key"))
        assertFalse(NativeTools.rejectedTools("HTTP 500: function crashed"))
    }
}
