package com.agent.ultra.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure, JVM-tested: [DecodeSpeed] running per-model decode-speed estimate. */
class DecodeSpeedTest {
    private val m = "moe.gguf"

    @Test fun unknownAtStart() = assertNull(DecodeSpeed().tokPerSec(m))

    @Test fun firstSampleIsTheEstimate() {
        val d = DecodeSpeed()
        d.observe(m, 32, 20_000)                       // 1.6 tok/s
        assertEquals(1.6, d.tokPerSec(m)!!, 1e-9)
    }

    @Test fun emaBlendsRecentRuns() {
        val d = DecodeSpeed()
        d.observe(m, 100, 10_000)                      // 10
        d.observe(m, 40, 10_000)                       // 4 → 0.5×4 + 0.5×10 = 7
        assertEquals(7.0, d.tokPerSec(m)!!, 1e-9)
    }

    @Test fun tinyRunsIgnored() {
        val d = DecodeSpeed()
        d.observe(m, 5, 10_000)                        // too few tokens
        d.observe(m, 64, 100)                          // too short in time
        assertNull(d.tokPerSec(m))
        d.observe(m, 50, 10_000)
        d.observe(m, 3, 100)
        assertEquals(5.0, d.tokPerSec(m)!!, 1e-9)
    }

    @Test fun otherModelStartsOver() {
        val d = DecodeSpeed()
        d.observe(m, 100, 10_000)
        assertNull(d.tokPerSec("gemma.gguf"))
        d.observe("gemma.gguf", 200, 10_000)
        assertEquals(20.0, d.tokPerSec("gemma.gguf")!!, 1e-9)
        assertNull(d.tokPerSec(m))
    }

    @Test fun resetForgets() {
        val d = DecodeSpeed()
        d.observe(m, 100, 10_000)
        d.reset()
        assertNull(d.tokPerSec(m))
    }
}
