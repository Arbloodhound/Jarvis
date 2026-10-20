package com.agent.ultra.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedPolicyTest {
    @Test fun harmlessRequestsRunOnALockedPhone() {
        for (r in listOf("turn on the flashlight", "set an alarm for 7 am", "what time is it", "pause the music",
                "how far is the moon", "note: buy milk", "volume to 40 percent"))
            assertTrue(r, LockedPolicy.allowed(r))
    }

    @Test fun privateRequestsNeedTheUnlock() {
        for (r in listOf("read my messages", "what did Sam say yesterday", "what's in my notes", "take a screenshot",
                "open whatsapp", "where am I", "read my notifications", "what do you remember", "send a text to mum",
                "turn on wifi", "who is my boss"))
            assertFalse(r, LockedPolicy.allowed(r))
    }
}
