package com.agent.ultra.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStepsTest {
    private fun s(done: Boolean, required: Boolean = true) = SetupSteps.Step("x", "x", "x", done, required)

    @Test fun optionalStepsDoNotCount() {
        val steps = listOf(s(true), s(false), s(false, required = false))
        assertEquals(1 to 2, SetupSteps.progress(steps))
        assertFalse(SetupSteps.complete(steps))
        assertTrue(SetupSteps.complete(listOf(s(true), s(false, required = false))))
    }
}
