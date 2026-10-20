package com.agent.jarvis.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorBrowserGuardTest {
    @Test fun namesTorBrowserButNotOtherApps() {
        assertTrue(TorBrowserGuard.mentionsTorBrowser("Tor Browser"))
        assertTrue(TorBrowserGuard.mentionsTorBrowser("open the onion site in torbrowser"))
        assertTrue(TorBrowserGuard.mentionsTorBrowser("org.torproject.torbrowser"))
        assertFalse(TorBrowserGuard.mentionsTorBrowser("Chrome"))
        assertFalse(TorBrowserGuard.mentionsTorBrowser("calculator monitor browser"))
    }
}
