package com.agent.ultra

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which saved routine a spoken phrase means.
 *
 * The rule is the same one the task-memory matcher had to learn: a shorter
 * remembered name must not claim a request that merely contains it. Getting
 * this wrong runs something the user did not ask for and reports success.
 */
class RecipeResolveTest {

    /** The resolution rule, standing free of the database. */
    private fun resolve(spoken: String, names: List<String>): String? {
        val n = spoken.lowercase().trim()
        names.firstOrNull { it == n }?.let { return it }
        return names.filter { n.contains(it) }.maxByOrNull { it.length }
            ?: names.filter { it.contains(n) }.minByOrNull { it.length }
    }

    @Test
    fun `the most specific name wins`() {
        // The live failure: "morning sites" was picked while "my morning sites"
        // sat right there, and a different routine ran.
        assertEquals("my morning sites",
            resolve("run my morning sites", listOf("morning sites", "my morning sites")))
    }

    @Test
    fun `order in the database does not decide it`() {
        val both = listOf("my morning sites", "morning sites")
        assertEquals("my morning sites", resolve("run my morning sites", both))
        assertEquals("my morning sites", resolve("run my morning sites", both.reversed()))
    }

    @Test
    fun `an exact name still wins outright`() {
        assertEquals("morning sites",
            resolve("morning sites", listOf("morning sites", "my morning sites")))
    }

    @Test
    fun `a shorter request still finds the routine it names`() {
        assertEquals("morning briefing",
            resolve("morning", listOf("morning briefing", "evening wrap")))
    }

    @Test
    fun `a name the user chose is not collapsed into another`() {
        // Stripping "my" when NAMING made these one routine: saving the second
        // silently replaced the first, and the agent then ran something nobody
        // asked for.
        fun nameOf(s: String) = s.lowercase()
            .replace(Regex("^(run|start|do|play|execute)\\s+"), "")
            .replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
        assertEquals("my morning sites", nameOf("my morning sites"))
        assertEquals("morning sites", nameOf("morning sites"))
        org.junit.Assert.assertNotEquals(nameOf("my morning sites"), nameOf("morning sites"))
        // A command verb wrapped around a name is still not part of the name.
        assertEquals("school run", nameOf("run school run"))
    }

    @Test
    fun `nothing matching resolves to nothing`() {
        assertEquals(null, resolve("pay the gas bill", listOf("morning sites")))
    }
}
