package com.agent.ultra.local

import org.junit.Assert.assertEquals
import org.junit.Test

class PageFaultsTest {
    @Test fun readsField12() = assertEquals(
        4242L,
        PageFaults.majorFrom("1234 (com.agent.ultra) S 1 1234 1234 0 -1 4194624 9876 0 4242 0 50 20 0 0 20 0 30 0 100"),
    )
    @Test fun commWithSpacesAndParens() = assertEquals(
        7L,
        PageFaults.majorFrom("99 (my (odd) app) R 1 99 99 0 -1 0 1 0 7 0 0 0 0 0 20 0 1 0 5"),
    )
    @Test fun garbageIsMinusOne() = assertEquals(-1L, PageFaults.majorFrom("nonsense"))
}
