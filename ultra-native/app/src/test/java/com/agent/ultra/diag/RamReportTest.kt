package com.agent.ultra.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RamReportTest {
    private val meminfo = """
        MemTotal:       15443000 kB
        MemFree:          512000 kB
        MemAvailable:    3686400 kB
        Cached:          3072000 kB
        SwapTotal:       8388608 kB
        SwapFree:        6291456 kB
        AnonPages:       6144000 kB
        Mapped:          2048000 kB
    """.trimIndent()

    @Test fun parsesKilobytesIntoMegabytes() {
        val m = RamReport.parseKbLines(meminfo)
        assertEquals(15081L, m["MemTotal"])
        assertEquals(3600L, m["MemAvailable"])
    }

    @Test fun theRestIsAndroidAndOtherApps() {
        val mem = RamReport.parseKbLines(meminfo)
        val self = mapOf("VmRSS" to 3000L, "RssFile" to 2400L, "RssAnon" to 600L, "VmHWM" to 3500L)
        val out = RamReport.lines(mem, self, listOf("native-heap" to 400L), listOf("main brain loaded"))
        assertTrue(out[0], "in use 11481 MB" in out[0])
        assertTrue(out.any { it.startsWith("RAM phone kernel: slab 0") })
        assertTrue(out.any { "Android + other apps (rest of in use): 8481 MB" in it })
        assertTrue(out.any { "swap used 2048 of 8192 MB" in it })
        assertTrue(out.any { "native-heap 400 MB" in it })
    }
}
