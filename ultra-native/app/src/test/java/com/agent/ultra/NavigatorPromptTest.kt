package com.agent.ultra

import com.agent.ultra.agent.ReActNavigator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt has to actually contain what the loop computed.
 *
 * This exists because of a specific failure. The "you are repeating yourself"
 * hint was built into a local variable and never interpolated into the
 * returned string. It compiled, it was written up as shipped, and it never
 * reached the model once — Kotlin reports an unused local as a warning, which
 * scrolls past in a build log.
 *
 * Asserting on the loop's behaviour would not have caught it either. Only
 * reading the prompt does.
 */
class NavigatorPromptTest {

    private fun prompt(
        goal: String = "open the settings page",
        observation: String = "TAPPABLE:\n  [3] Settings",
        history: List<String> = emptyList(),
        repeatedNoOp: Int = 0,
        leftAppFor: Int = 0,
        target: String = "com.android.chrome",
    ) = ReActNavigator.buildPrompt(goal, observation, history, repeatedNoOp, leftAppFor, target)

    @Test
    fun `the goal and the screen are in the prompt`() {
        val p = prompt()
        assertTrue(p.contains("open the settings page"))
        assertTrue(p.contains("[3] Settings"))
    }

    @Test
    fun `the repeat hint reaches the model, not just a local variable`() {
        val p = prompt(repeatedNoOp = 2)
        assertTrue("the repeat hint must be in the prompt text", p.contains("repeating yourself"))
    }

    @Test
    fun `no repeat hint when the model is not repeating`() {
        assertFalse(prompt(repeatedNoOp = 0).contains("repeating yourself"))
        assertFalse(prompt(repeatedNoOp = 1).contains("repeating yourself"))
    }

    @Test
    fun `straying out of the target app is stated, with the app named`() {
        val p = prompt(leftAppFor = 2, target = "com.android.chrome")
        assertTrue(p.contains("outside com.android.chrome"))
        assertTrue("must say how to get back", p.contains("back()"))
    }

    @Test
    fun `a single step outside the app is a detour, not a warning`() {
        assertFalse(prompt(leftAppFor = 1).contains("You have been outside"))
    }

    @Test
    fun `both notes appear together when both apply`() {
        val p = prompt(repeatedNoOp = 3, leftAppFor = 3)
        assertTrue(p.contains("repeating yourself"))
        assertTrue(p.contains("You have been outside"))
    }

    @Test
    fun `no notes block at all when nothing is wrong`() {
        assertFalse(prompt().contains("NOTES:"))
    }

    @Test
    fun `history is included and capped at the last six steps`() {
        val history = (1..20).map { "step $it: tap($it) → screen changed" }
        val p = prompt(history = history)
        assertTrue("the most recent step must survive", p.contains("step 20:"))
        assertFalse("step 1 is too old to carry", p.contains("step 1:"))
    }

    @Test
    fun `the action vocabulary is always offered`() {
        val p = prompt()
        for (action in listOf("tap(INDEX)", "type(", "scroll(down)", "back()", "done")) {
            assertTrue("missing $action", p.contains(action))
        }
    }

    // 36 of 98 failed AndroidWorld episodes repeated an action already marked NO CHANGE (2026-09-20).
    @Test
    fun actionsThatDidNothingOnThisScreenAreNamedAsUnavailable() {
        val p = ReActNavigator.buildPrompt("create note", "TAPPABLE:\n  [1] (unlabelled button, top left)", emptyList(),
            dead = listOf("tap(1)", "scroll(down)"))
        assertTrue(p, p.contains("tap(1), scroll(down)") && p.contains("not available"))
    }

    @Test
    fun aRouteIsShownToEveryStepAndAbsentWhenThereIsNone() {
        val with = ReActNavigator.buildPrompt("create note", "TAPPABLE:\n  [1] x", emptyList(), route = "1. tap \"New\"")
        assertTrue(with, with.contains("A ROUTE THAT WORKED BEFORE") && with.contains("1. tap \"New\""))
        assertFalse(ReActNavigator.buildPrompt("create note", "TAPPABLE:\n  [1] x", emptyList()).contains("ROUTE"))
    }

    // The model asked to enter 16 wrote tap(16), the Alarm tab (AndroidWorld, 2026-09-20).
    @Test
    fun aTapMeansTheWordsFirstAndAnIndexOnlyWhenOneWasListed() {
        val words = listOf("1" to 3, "6" to 8, "0" to 13, "start" to 15, "alarm" to 16, "more options" to 1)
        val listed = setOf(6)   // one wordless button, shown as [6]
        assertTrue(ReActNavigator.resolveTap("tap(\"1\")", words, listed) == 3)
        assertTrue(ReActNavigator.resolveTap("tap(\"Start\")", words, listed) == 15)
        assertTrue("a bare digit is the key with that digit on it", ReActNavigator.resolveTap("tap(1)", words, listed) == 3)
        assertTrue("an index that was listed stays an index", ReActNavigator.resolveTap("tap(6)", words, listed) == 6)
        assertTrue("16 is neither listed nor anyone's words", ReActNavigator.resolveTap("tap(16)", words, listed) == null)
        assertTrue(ReActNavigator.resolveTap("tap(\"Pause\")", words, listed) == null)
        assertTrue(com.agent.ultra.agent.ModelOutput.action("ACTION: tap(\"Start\")") == "tap(\"Start\")")
    }
}
