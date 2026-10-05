package com.agent.ultra.agent

/**
 * A goal broken into checkpoints the engine can check for itself.
 *
 * The loop this replaces is purely reactive: look at the screen, ask the model
 * for one action, do it, repeat until a fixed budget runs out. It has no idea
 * whether it is getting closer. Two failures come straight out of that.
 *
 * The model spends turns deciding whether it has finished. "Go to google.com"
 * loaded the page on step one and then spent fourteen steps deliberating, which
 * was patched by checking the screen for a domain name — a fix that only works
 * for goals that name a website.
 *
 * And a run that is stuck looks exactly like a run that is working, right up
 * until the budget ends. "Iteration budget exhausted" is all it can say, having
 * kept no idea of how far it got.
 *
 * A plan fixes both by writing down, in advance, what each stage will look like
 * when it is done. The model produces the plan, because deciding how to
 * approach a goal is intent. The engine checks the checkpoints, counts the
 * budget and decides when to replan, because that is structure — and because a
 * model asked "are you finished?" will often say yes.
 */
object NavPlan {

    /**
     * One stage of a plan.
     *
     * @param description what to do, in the model's own words
     * @param expect text that will be on screen when this stage is done. The
     *   whole point: the engine can check this without spending a model turn.
     */
    data class Checkpoint(val description: String, val expect: String) {
        /**
         * Can the engine tell when this stage is done?
         *
         * Two ways a model gets this wrong, both seen on the first live run.
         * It writes "none" when it cannot name the text, exactly as asked —
         * and that must not become a gate no screen can ever open. And it
         * writes prose: one stage expected "The Eiffel Tower (/ˈaɪfəl/
         * EYE-fəl; French: La Tour Eiffel..." — a description of the page, not
         * a label on it, which nothing will ever match.
         *
         * An expectation the engine cannot check is not a failure. The stage
         * still guides what to do next; it just does not gate progress.
         */
        val checkable: Boolean
            get() {
                val e = expect.trim()
                return e.length in MIN_EXPECT_CHARS..MAX_EXPECT_CHARS &&
                    !NOT_AN_EXPECTATION.matches(e.lowercase())
            }
    }

    /**
     * Read a plan out of the model's reply.
     *
     * Deliberately forgiving about shape and strict about meaning. Numbered or
     * dashed lines both work, the `EXPECT:` marker is case-insensitive, and
     * anything that is not a step is ignored — a model that adds a sentence of
     * preamble should not cost a run. A step with no expectation is still kept,
     * because a stage worth doing is worth doing even if only the model can
     * tell when it is done.
     */
    fun parse(reply: String): List<Checkpoint> {
        val out = mutableListOf<Checkpoint>()
        for (raw in reply.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val body = STEP_PREFIX.find(line)?.let { line.removeRange(it.range).trim() }
                ?: continue
            if (body.isEmpty()) continue
            val split = EXPECT.find(body)
            val description = (if (split == null) body else body.take(split.range.first))
                .trim().trimEnd('|', '-', ',', ';').trim()
            val expect = split?.let { body.substring(it.range.last + 1).trim() }.orEmpty()
                .trim('"', '\'', '.', ' ')
            if (description.isEmpty()) continue
            out.add(Checkpoint(description.take(160), expect.take(80)))
            if (out.size >= MAX_STEPS) break
        }
        return out
    }

    /**
     * Is this checkpoint's expectation on screen?
     *
     * Case-insensitive containment, because the model writes "Search results"
     * where the page says "SEARCH RESULTS". An unstated expectation is never
     * satisfied by the engine — it falls to the model to say so, which is the
     * old behaviour and the honest default rather than guessing yes.
     */
    fun satisfied(checkpoint: Checkpoint, observation: String): Boolean {
        if (!checkpoint.checkable) return false
        val expect = checkpoint.expect.trim()
        if (observation.contains(expect, ignoreCase = true)) return true
        // A number is the same number however it is written. The plan said "7:52:22", the Clock
        // showed "07h 52m 22s", the stage never counted as reached, and a timer that was already
        // right was typed over until it was wrong (AndroidWorld ClockTimerEntry, 2026-09-20).
        // Only when the expectation is mostly a number: three or more digits, and few letters.
        val want = expect.filter(Char::isDigit).trimStart('0')
        if (want.length < 3 || expect.count(Char::isLetter) > 6) return false
        return observation.lineSequence().any { line ->
            val got = line.filter(Char::isDigit).trimStart('0')
            got == want && line.count(Char::isDigit) <= want.length + 3
        }
    }

    /**
     * How many steps a stage gets before the plan is reconsidered.
     *
     * Spread evenly, with a floor: a four-stage plan inside a fifteen-step
     * budget gives three steps each, and a stage that needs a scroll and a tap
     * would fail on arithmetic rather than on anything real.
     */
    fun budgetFor(totalSteps: Int, stages: Int): Int {
        if (stages <= 0) return totalSteps
        return maxOf(MIN_STAGE_STEPS, totalSteps / stages)
    }

    /** What to tell the user when a run ends part-way. Far more use than
     * "iteration budget exhausted", which says only that time ran out. */
    fun progressSummary(plan: List<Checkpoint>, reached: Int): String {
        if (plan.isEmpty()) return "no plan was formed"
        val done = reached.coerceIn(0, plan.size)
        if (done >= plan.size) return "all ${plan.size} stages done"
        val stuckOn = plan[done].description
        return "reached $done of ${plan.size} stages, stuck on: $stuckOn"
    }

    /** A route that finished a request like this one, as the planner and every step read it. */
    fun routeBlock(route: String): String = if (route.isBlank()) "" else
        "\nA ROUTE THAT WORKED BEFORE — the phone was checked afterwards and the job was done. " +
            "Its values were different from yours; the steps are the same:\n$route\n" +
            "Follow it. Where the screen in front of you differs from it, trust the screen.\n"

    /** The request that asks for a plan. Kept here beside the parser so the
     * shape asked for and the shape read are never edited apart. */
    fun prompt(goal: String, observation: String, route: String = ""): String =
        """You are driving an Android phone's UI to accomplish: "$goal"
${routeBlock(route)}
CURRENT SCREEN:
$observation

Break this into 2-4 stages. For each, give a short instruction and the text
that will be visible on screen once that stage is done.

Rules:
- EXPECT must be a SHORT piece of text you would actually SEE on the finished
  screen, copied as it would appear. A heading or a button label. Under eight
  words. Not a sentence describing the page, and not the page's contents.
- If you cannot name such text for a stage, write EXPECT: none.
- Fewer stages is better. Do not pad.
- The LAST stage must be the action that COMPLETES the task, not the screen you land on, and its
  EXPECT must be text that appears only once it is done (starting a stopwatch: EXPECT: Pause).
  Opening the right tab or screen is never the last stage.
- Stay inside the app that is on screen. Never plan to restart, reset or power off the
  phone, uninstall anything, sign out, grant a permission or change a setting unless
  the task itself says to. If the task is already done on this screen, write
  1. Nothing to do | EXPECT: none

Format, one per line:
1. <what to do> | EXPECT: <text that will be on screen>

PLAN:"""

    private val STEP_PREFIX = Regex("""^(?:\d{1,2}[.)]|[-*•])\s*""")
    private val EXPECT = Regex("""\|?\s*EXPECT\s*:""", RegexOption.IGNORE_CASE)

    /** Longer than this and it is an essay, not a plan. */
    private const val MAX_STEPS = 6

    /** Below this, an expectation matches half the screen. "OK" would be
     * satisfied by a cookie banner. */
    private const val MIN_EXPECT_CHARS = 3

    /** Longer than a label on a screen: the model has written a description of
     * the page rather than something printed on it. */
    private const val MAX_EXPECT_CHARS = 60

    /** What a model writes when it has nothing to name. */
    private val NOT_AN_EXPECTATION =
        Regex("""^(none|n/?a|nothing|unknown|unclear|any|-+)\.?$""")

    private const val MIN_STAGE_STEPS = 4
}
