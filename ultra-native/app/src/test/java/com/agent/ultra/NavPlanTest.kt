package com.agent.ultra

import com.agent.ultra.agent.NavPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Planning with checkpoints.
 *
 * The parser is deliberately forgiving about shape and strict about meaning: a
 * model that adds a line of preamble must not cost a run, and an expectation
 * too vague to check must not be treated as checkable.
 */
class NavPlanTest {

    @Test
    fun `a plan in the requested shape is read`() {
        val plan = NavPlan.parse(
            """
            1. Tap the address bar | EXPECT: Search or type URL
            2. Type wikipedia.org and go | EXPECT: Wikipedia, the free encyclopedia
            """.trimIndent()
        )
        assertEquals(2, plan.size)
        assertEquals("Tap the address bar", plan[0].description)
        assertEquals("Search or type URL", plan[0].expect)
        assertEquals("Wikipedia, the free encyclopedia", plan[1].expect)
    }

    @Test
    fun `preamble and trailing chatter are ignored`() {
        val plan = NavPlan.parse(
            """
            Sure! Here is my plan for this task:

            1. Open the menu | EXPECT: Settings
            2. Tap Settings | EXPECT: Display

            Let me know if you would like me to adjust this.
            """.trimIndent()
        )
        assertEquals(2, plan.size)
        assertEquals("Open the menu", plan[0].description)
    }

    @Test
    fun `dashes and bullets work as well as numbers`() {
        val plan = NavPlan.parse("- Do a thing | EXPECT: Done\n* Do another | EXPECT: Finished")
        assertEquals(2, plan.size)
    }

    @Test
    fun `a stage with no expectation is kept but is not engine-checkable`() {
        val plan = NavPlan.parse("1. Work out what to do next | EXPECT: none")
        assertEquals(1, plan.size)
        // "none" is a real string and would match nothing useful, but the point
        // is that the engine must not claim the stage is done on its own.
        assertFalse(NavPlan.satisfied(plan[0], "a screen that says nothing of the sort"))
    }

    @Test
    fun `a step with no text at all is dropped`() {
        assertTrue(NavPlan.parse("1.\n2. \n3. | EXPECT: x").size <= 1)
        assertTrue(NavPlan.parse("").isEmpty())
        assertTrue(NavPlan.parse("I could not work out a plan.").isEmpty())
    }

    @Test
    fun `a rambling reply is capped rather than run in full`() {
        val many = (1..20).joinToString("\n") { "$it. step $it | EXPECT: thing $it" }
        assertTrue("a plan of 20 stages is not a plan", NavPlan.parse(many).size <= 6)
    }

    // ── Checking a checkpoint ───────────────────────────────────────

    @Test
    fun `an expectation is matched regardless of case`() {
        val cp = NavPlan.Checkpoint("open it", "Search Results")
        assertTrue(NavPlan.satisfied(cp, "TAPPABLE:\n [2] SEARCH RESULTS"))
        assertTrue(NavPlan.satisfied(cp, "search results for headphones"))
    }

    @Test
    fun `an absent expectation is not satisfied`() {
        val cp = NavPlan.Checkpoint("open it", "Search Results")
        assertFalse(NavPlan.satisfied(cp, "TAPPABLE:\n [2] Home"))
    }

    @Test
    fun `a very short expectation is refused rather than matched loosely`() {
        // "OK" appears on a cookie banner, a dialog and half the buttons on a
        // phone. Treating it as proof a stage finished would end runs early
        // and claim success.
        val cp = NavPlan.Checkpoint("do it", "OK")
        assertFalse(NavPlan.satisfied(cp, "TAPPABLE:\n [1] OK  [2] Cancel"))
    }

    @Test
    fun `a blank expectation is never satisfied by the engine`() {
        val cp = NavPlan.Checkpoint("do something", "")
        assertFalse(cp.checkable)
        assertFalse(NavPlan.satisfied(cp, "literally anything at all"))
    }

    // ── Budget ──────────────────────────────────────────────────────

    @Test
    fun `steps are shared out between the stages`() {
        assertEquals(7, NavPlan.budgetFor(15, 2))
        assertEquals(5, NavPlan.budgetFor(15, 3))
    }

    @Test
    fun `a stage always gets enough steps to do something`() {
        // Four stages of fifteen steps is three each, and a stage needing a
        // scroll and a tap would fail on arithmetic rather than on anything
        // real.
        assertTrue("a stage needs room to act", NavPlan.budgetFor(15, 5) >= 4)
        assertTrue(NavPlan.budgetFor(4, 4) >= 4)
    }

    @Test
    fun `no plan means the whole budget`() {
        assertEquals(15, NavPlan.budgetFor(15, 0))
    }

    // ── Reporting ───────────────────────────────────────────────────

    @Test
    fun `an unfinished run says how far it got and what stopped it`() {
        val plan = listOf(
            NavPlan.Checkpoint("Tap the address bar", "Search or type URL"),
            NavPlan.Checkpoint("Type the site and go", "Wikipedia"),
            NavPlan.Checkpoint("Open the article", "Eiffel Tower"),
        )
        val msg = NavPlan.progressSummary(plan, reached = 2)
        assertTrue(msg.contains("2 of 3"))
        assertTrue("it must name the stage it stalled on", msg.contains("Open the article"))
    }

    @Test
    fun `a finished run says so`() {
        val plan = listOf(NavPlan.Checkpoint("a", "x"), NavPlan.Checkpoint("b", "y"))
        assertTrue(NavPlan.progressSummary(plan, reached = 2).contains("all 2 stages done"))
    }

    @Test
    fun `no plan is reported as no plan, not as zero progress`() {
        assertEquals("no plan was formed", NavPlan.progressSummary(emptyList(), 0))
    }

    @Test
    fun `the request asks for text that will be on screen`() {
        val p = NavPlan.prompt("search for cats", "TAPPABLE: [1] Home")
        assertTrue(p.contains("search for cats"))
        assertTrue(p.contains("[1] Home"))
        assertTrue("it must ask for visible text, not a description",
            p.contains("actually SEE"))
    }
}

/**
 * What a model actually writes, as opposed to what it was asked for.
 *
 * Both of these came off the first live run, not from imagination.
 */
class NavPlanRealRepliesTest {

    @Test
    fun `"none" is not a gate no screen can open`() {
        // The model was asked to write "none" when it could not name the text,
        // and did. Treating that as a checkpoint left the run pushing against
        // a door with no handle for its whole stage budget.
        for (word in listOf("none", "None", "N/A", "nothing", "unknown", "-")) {
            val cp = NavPlan.Checkpoint("do a thing", word)
            assertFalse("'$word' must not be checkable", cp.checkable)
            assertFalse(NavPlan.satisfied(cp, "a screen containing the word $word"))
        }
    }

    @Test
    fun `prose is not an expectation`() {
        // Verbatim from the run: the model gave the article's opening sentence
        // as the thing it expected to see.
        val cp = NavPlan.Checkpoint(
            "Tap the Wikipedia article result",
            "The Eiffel Tower (/ˈaɪfəl/ EYE-fəl; French: La Tour Eiffel [tuʁ ɛfɛl]) is an iro",
        )
        assertFalse("a sentence is a description, not a label", cp.checkable)
    }

    @Test
    fun `a real page heading is a good expectation`() {
        assertTrue(NavPlan.Checkpoint("open it", "Eiffel Tower - Wikipedia").checkable)
        assertTrue(NavPlan.Checkpoint("search", "Search results").checkable)
        assertTrue(NavPlan.Checkpoint("tap", "Add to cart").checkable)
    }

    @Test
    fun `a plan mixing checkable and uncheckable stages keeps both`() {
        val plan = NavPlan.parse(
            """
            1. Tap on [35] Search | EXPECT: none
            2. Type and submit | EXPECT: Eiffel Tower - Wikipedia
            """.trimIndent()
        )
        assertEquals(2, plan.size)
        assertFalse("stage 1 guides but does not gate", plan[0].checkable)
        assertTrue("stage 2 gates", plan[1].checkable)
    }

    // "7:52:22" planned, "07h 52m 22s" shown (AndroidWorld ClockTimerEntry, 2026-09-20).
    @Test
    fun aNumberIsTheSameNumberHoweverItIsWritten() {
        val screen = "TAPPABLE:\n  \"1\"\n  \"Start\"\n\nTEXT ON SCREEN (read-only, not tappable):\n  Timer\n  07h 52m 22s"
        assertTrue(NavPlan.satisfied(NavPlan.Checkpoint("key it in", "7:52:22"), screen))
        assertFalse(NavPlan.satisfied(NavPlan.Checkpoint("key it in", "7:52:22"), screen.replace("07h 52m 22s", "00h 07m 52s")))
        assertFalse(NavPlan.satisfied(NavPlan.Checkpoint("key it in", "16:35"), screen))
    }
}
