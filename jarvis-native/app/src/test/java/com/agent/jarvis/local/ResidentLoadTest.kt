package com.agent.jarvis.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResidentLoadTest {
    private val gb = 1_073_741_824L

    @Test fun residentWhenRoomy() = assertTrue(LocalModelEngine.residentLoad(true, 12 * gb, 10 * gb))
    @Test fun residentAtExactHeadroom() = assertTrue(LocalModelEngine.residentLoad(true, 11 * gb, 10 * gb))
    @Test fun mappedWhenShort() = assertFalse(LocalModelEngine.residentLoad(true, 10 * gb + gb / 2, 10 * gb))
    @Test fun mappedWhenSettingOff() = assertFalse(LocalModelEngine.residentLoad(false, 14 * gb, 10 * gb))
    @Test fun mappedWhenMemoryUnknown() = assertFalse(LocalModelEngine.residentLoad(true, -1, 10 * gb))
    @Test fun mappedWhenFileUnknown() = assertFalse(LocalModelEngine.residentLoad(true, 14 * gb, 0))
}
