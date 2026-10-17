package com.agent.ultra.harness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the screen-bound tap check and its ScreenGuard gate. */
class ScreenBoundsTest {

    private fun rect(l: Int, t: Int, r: Int, b: Int) = ScreenBounds.Rect(l, t, r, b)

    @Test fun tapInsideNodeBoundsIsScreenBound() {
        assertTrue(ScreenBounds.withinBounds(50, 50, rect(0, 0, 100, 100)))
    }

    @Test fun tapOutsideNodeBoundsIsNotScreenBound() {
        assertFalse(ScreenBounds.withinBounds(150, 50, rect(0, 0, 100, 100)))
        assertFalse(ScreenBounds.withinBounds(50, 150, rect(0, 0, 100, 100)))
    }

    @Test fun rightAndBottomAreExclusive() {
        assertTrue(ScreenBounds.withinBounds(0, 0, rect(0, 0, 100, 100)))
        assertFalse(ScreenBounds.withinBounds(100, 100, rect(0, 0, 100, 100)))
    }

    @Test fun missingNodeBoundsIsNeverScreenBound() {
        assertFalse(ScreenBounds.withinBounds(10, 10, null))
        assertFalse(ScreenBounds.withinBounds(10, 10, rect(5, 5, 5, 20))) // invalid (right == left)
    }

    @Test fun visionBoundsMustAlsoContainTheTap() {
        val node = rect(0, 0, 100, 100)
        assertTrue("corroborated by vision", ScreenBounds.withinBounds(50, 50, node, rect(40, 40, 60, 60)))
        assertFalse("in node but outside vision", ScreenBounds.withinBounds(50, 50, node, rect(0, 0, 10, 10)))
    }

    @Test fun requireScreenBoundThrowsOnUnboundTap() {
        val g = ScreenGuard()
        g.requireScreenBound(50, 50, rect(0, 0, 100, 100)) // inside: ok
        assertTrue(runCatching { g.requireScreenBound(200, 200, rect(0, 0, 100, 100)) }
            .exceptionOrNull() is UnboundTap)
        assertTrue(runCatching { g.requireScreenBound(50, 50, null) }
            .exceptionOrNull() is UnboundTap)
    }
}
