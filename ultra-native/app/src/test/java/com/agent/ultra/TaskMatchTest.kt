package com.agent.ultra

import com.agent.ultra.agent.TaskMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a remembered task is the task being asked for now.
 *
 * Getting this wrong is not a wasted turn. It is the agent being told that a
 * job succeeded before, and quietly doing that job instead of the one it was
 * given.
 */
class TaskMatchTest {

    private fun t(vararg w: String) = w.toSet()

    // Threshold the brain applies to the returned score.
    private val ACCEPTED = 0.5

    @Test
    fun `the same job phrased differently still matches`() {
        // The reason containment was chosen in the first place; it must survive.
        // Same subject, different wording — this is exactly what containment
        // exists for, and it must keep matching.
        assertTrue(TaskMatch.score(t("battery", "level"), t("battery", "doing")) >= ACCEPTED)
        assertTrue(TaskMatch.score(t("location"), t("location", "whats")) >= ACCEPTED)
        assertTrue(TaskMatch.score(t("battery", "level"), t("battery", "level", "check")) >= ACCEPTED)
    }

    @Test
    fun `a short memory cannot claim a long compound request`() {
        // "battery level" used to score a perfect 1.0 against this, and the
        // banking half of the sentence would simply evaporate.
        val remembered = t("battery", "level")
        val asked = t("check", "battery", "level", "then", "open", "banking", "app", "pay", "bill")
        assertEquals(0.0, TaskMatch.score(asked, remembered), 0.0001)
    }

    @Test
    fun `a memory that explains almost none of the request is rejected`() {
        val remembered = t("open")
        val asked = t("open", "history", "page", "from", "three", "dot", "menu", "chrome")
        assertTrue(TaskMatch.score(asked, remembered) < ACCEPTED)
    }

    @Test
    fun `no shared words is no match`() {
        assertEquals(0.0, TaskMatch.score(t("battery", "level"), t("send", "message")), 0.0001)
    }

    @Test
    fun `an empty request matches nothing`() {
        assertEquals(0.0, TaskMatch.score(emptySet(), t("battery")), 0.0001)
        assertEquals(0.0, TaskMatch.score(t("battery"), emptySet()), 0.0001)
    }

    @Test
    fun `an identical request is a perfect match`() {
        val same = t("open", "youtube")
        assertEquals(1.0, TaskMatch.score(same, same), 0.0001)
    }
}
