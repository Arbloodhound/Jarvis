package com.agent.jarvis.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastCommandTest {
    private fun m(s: String) = FastCommand.match(s)?.let { it.first to it.second.toString() }

    @Test fun deviceCommands() {
        assertEquals("flashlight_toggle" to "{\"on\":true}", m("turn on the flashlight"))
        assertEquals("flashlight_toggle" to "{\"on\":false}", m("Hey Jarvis, flashlight off."))
        assertEquals("wifi_toggle" to "{\"on\":false}", m("please turn off wifi"))
        assertEquals("volume_set" to "{\"percent\":50}", m("set the volume to 50 percent"))
        assertEquals("battery_status" to "{}", m("what's my battery level"))
        assertEquals("global_action" to "{\"action\":\"home\"}", m("go home"))
        assertEquals("media_next" to "{}", m("skip this song"))
    }
    @Test fun darkModeIsItsOwnCommand() {
        assertEquals("dark_mode" to "{\"on\":true}", m("turn on dark mode"))
        assertEquals("dark_mode" to "{\"on\":false}", m("switch off the dark theme"))
        assertEquals("dark_mode" to "{\"on\":true}", m("night mode on"))
    }
    @Test fun callCommands() {
        assertEquals("call_end" to "{}", m("hang up"))
        assertEquals("call_answer" to "{}", m("answer the call"))
        assertEquals("call_speaker" to "{\"on\":true}", m("put it on speaker"))
        assertEquals("call_phone" to "{\"to\":\"mom\"}", m("call mom"))
    }
    @Test fun notFastWhenNotJustTheCommand() {
        assertNull(m("what is the volume of a sphere"))
        assertNull(m("turn on the flashlight and then call mom"))
        assertNull(m("how do I turn on the flashlight?"))
        assertNull(m("read my notifications"))
        assertNull(m("call mom and tell her I am late"))
        assertNull(m("play something by Queen"))
    }
}
