package com.agent.ultra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetPolicyTest {

    @Test fun cloudTruthTable() {
        assertEquals(true, NetPolicy.cloudAllowed(offlineOnly = false, hasClient = true))
        assertEquals(false, NetPolicy.cloudAllowed(offlineOnly = false, hasClient = false))
        assertEquals(false, NetPolicy.cloudAllowed(offlineOnly = true, hasClient = true))
        assertEquals(false, NetPolicy.cloudAllowed(offlineOnly = true, hasClient = false))
    }

    @Test fun webTruthTable() {
        assertEquals(true, NetPolicy.webAllowed(offlineOnly = false))
        assertEquals(false, NetPolicy.webAllowed(offlineOnly = true))
    }

    /** Brain drops web results that start with "OFFLINE"; this one must be dropped too. */
    @Test fun offlineResultCarriesThePrefixBrainFiltersOn() {
        assertTrue(NetPolicy.OFFLINE_RESULT.startsWith("OFFLINE"))
    }
}
