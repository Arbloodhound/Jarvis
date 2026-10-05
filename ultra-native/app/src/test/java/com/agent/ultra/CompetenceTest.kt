package com.agent.ultra

import com.agent.ultra.agent.Competence
import com.agent.ultra.agent.ScreenJourney
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the agent is allowed to claim about itself.
 *
 * Every case here is written from the reader's side: the question is not
 * whether the number is right but whether a person who read the sentence would
 * be misled. An agent that overstates what it knows is worse than one that says
 * nothing, because the confident sentence is the one people act on.
 */
class CompetenceTest {

    private fun route(vararg vias: String) =
        listOf(ScreenJourney.Waypoint("com.android.chrome", "start", "IDS")) +
            vias.map { ScreenJourney.Waypoint("com.android.chrome", "d$it", "IDS", it) }

    @Test
    fun `a routine only watched never claims to have been done`() {
        val c = Competence.of(route("", ""), walksCompleted = 0, walksAttempted = 0, doorsMoved = 0)
        assertFalse(c.everWalked)
        assertEquals("you showed me this, but I have never walked it myself", c.describe())
    }

    @Test
    fun `knowing every door and having finished says so plainly`() {
        val c = Competence.of(
            route("menu_button", "open_history_menu_id"),
            walksCompleted = 3, walksAttempted = 3, doorsMoved = 0,
        )
        assertTrue(c.knowsTheWholeWay)
        assertEquals("I have walked this 3 times and know all 2 steps", c.describe())
    }

    @Test
    fun `partial knowledge is stated as partial`() {
        val c = Competence.of(
            route("menu_button", ""),
            walksCompleted = 0, walksAttempted = 2, doorsMoved = 0,
        )
        assertFalse(c.knowsTheWholeWay)
        assertEquals(
            "I know 1 of the 2 steps, and will search for the rest; it has not finished yet",
            c.describe(),
        )
    }

    /**
     * The case the whole proposal exists for: it worked, and then the app
     * changed. Silence here is what makes an agent feel unreliable for reasons
     * the user cannot name.
     */
    @Test
    fun `a door that moved is reported as the app having changed`() {
        val c = Competence.of(
            route("menu_button", "history_row"),
            walksCompleted = 4, walksAttempted = 5, doorsMoved = 1,
        )
        val said = c.describe()
        assertTrue(said, "the app has changed" in said)
        assertTrue(said, "once it did not finish" in said)
    }

    @Test
    fun `a one step route is not described in the plural`() {
        val c = Competence.of(route("menu_button"), 1, 1, 0)
        assertEquals("I have walked this once and know all 1 step", c.describe())
    }

    /** A hop with no door is not knowledge, however many times it was run. */
    @Test
    fun `running a route often does not imply knowing the way`() {
        val c = Competence.of(route("", ""), walksCompleted = 9, walksAttempted = 9, doorsMoved = 0)
        assertFalse(c.knowsTheWholeWay)
        assertTrue(c.describe(), "not worked out any" in c.describe())
    }

    /**
     * Routines taught before walks were counted hold doors but no history.
     * Claiming "never walked this" while holding both doors contradicts the
     * only way a door can be found.
     */
    @Test
    fun `knowing the doors is never reported as never having walked it`() {
        val c = Competence.of(
            route("menu_button", "open_history_menu_id"),
            walksCompleted = 0, walksAttempted = 0, doorsMoved = 0,
        )
        assertEquals("I know all 2 steps", c.describe())
    }

    /** Tried it, learned nothing: said as tried, not as never attempted. */
    @Test
    fun `attempting and learning nothing is not the same as never attempting`() {
        val c = Competence.of(route("", ""), walksCompleted = 0, walksAttempted = 2, doorsMoved = 0)
        assertTrue(c.describe(), c.describe().startsWith("I have tried this"))
    }
}
