package com.agent.jarvis.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAskTest {
    @Test fun screenDrivingRequests() {
        assertTrue(ScreenAsk.matches("Use phone screen to search just go on Google and search weather"))
        assertTrue(ScreenAsk.matches("go on Google and search the weather"))
        assertTrue(ScreenAsk.matches("open Chrome and search for ferry times"))
    }
    @Test fun ordinaryRequestsAreNot() {
        assertFalse(ScreenAsk.matches("what is the weather"))
        assertFalse(ScreenAsk.matches("turn on the flashlight"))
        assertFalse(ScreenAsk.matches("open Spotify"))
    }

    @Test fun goOnGoogleAndSearchIsAnOnDeviceNavigatorTask() {
        val t = OfflineNav.task("Use phone screen to search just go on Google and search weather today")
        assertTrue(t != null && t.appHint == "browser")
        assertTrue(OfflineNav.task("what is the weather") == null)
    }
}
