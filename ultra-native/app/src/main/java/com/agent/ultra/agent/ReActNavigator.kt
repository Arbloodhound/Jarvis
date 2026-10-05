package com.agent.ultra.agent

import com.agent.ultra.provider.OpenAiClient
import kotlinx.coroutines.delay
import org.json.JSONArray

/**
 * Perceive → think → act → verify loop for driving other apps' UIs.
 * Ported from the proven ReActLoop.ts: observe the a11y tree, hand the model
 * indexed action lists, execute one action, re-observe, verify change.
 */
class ReActNavigator(
    private val controller: AgentController,
    private val client: OpenAiClient,
) {
    companion object {
        private const val MAX_ITER = 15

        /** Ultra surfacing its own action-gate card is not drifting away from
         * the task — it is the task waiting for a tap. */
        private const val OWN_PACKAGE = "com.agent.ultra"

        /** How many steps a run may be repaid for being pushed out of its own
         * app. Capped so a run that is genuinely lost still ends. */
        private const val MAX_RECOVERY_GRACE = 5

        /** How many controls an app may accumulate across all its screens. */
        private const val APP_CONTROL_LIMIT = 80
        private val DOMAIN =
            Regex("[a-z0-9-]+\\.(com|org|net|io|gov|edu)", RegexOption.IGNORE_CASE)

        /**
         * Internal, not private, so a test can prove the notes actually reach the
         * model. The repeat hint was computed and never interpolated — it existed
         * in the source, was logged as shipped, and was never once sent.
         */
        /**
         * What a tap means, given what was listed. Elements with words are listed by their words
         * and have no number to confuse with a value; only wordless ones carry an [index].
         *   tap("Start") -> the element with those words
         *   tap(6)       -> listed index 6 if there is one, else the element whose words are "6"
         * Null when nothing fits: the model is told, not guessed for.
         */
        internal fun resolveTap(said: String, labelled: List<Pair<String, Int>>, listed: Set<Int>): Int? {
            fun byWords(words: String): Int? {
                val want = words.trim().lowercase()
                labelled.firstOrNull { it.first == want }?.let { return it.second }
                val starts = labelled.filter { it.first.startsWith(want) }
                if (starts.size == 1) return starts[0].second
                val has = labelled.filter { want.length >= 3 && it.first.contains(want) }
                return if (has.size == 1) has[0].second else null
            }
            Regex("""^tap\(\s*["'](.+)["']\s*\)$""", RegexOption.IGNORE_CASE).find(said.trim())
                ?.let { return byWords(it.groupValues[1]) }
            Regex("""^tap(?:_index)?\(\s*(\d+)\s*\)$""", RegexOption.IGNORE_CASE).find(said.trim())?.let {
                val n = it.groupValues[1].toInt()
                return if (n in listed) n else byWords(n.toString())
            }
            return null
        }

        internal fun buildPrompt(
            goal: String,
            observation: String,
            history: List<String>,
            repeatedNoOp: Int = 0,
            leftAppFor: Int = 0,
            target: String = "",
            dead: Collection<String> = emptyList(),
            route: String = "",
        ): String {
            val stuck = if (repeatedNoOp >= 2)
                "You are repeating yourself. Try back(), or type the destination directly."
            else ""
            // Two steps in a row outside the target app is not a detour any more.
            val strayed = if (leftAppFor >= 2)
                "You have been outside $target for $leftAppFor steps. Press back() until you are back in it."
            else ""
            // Named, not hinted at: "do not repeat this" in HISTORY was ignored in 36 of 98 failed
            // AndroidWorld episodes (2026-09-20). These are also refused in code if chosen.
            val tried = if (dead.isNotEmpty())
                "Already tried on this exact screen and nothing happened, so they are not available: " +
                    dead.joinToString(", ") + ". Choose something else."
            else ""
            val notes = listOf(stuck, strayed, tried).filter { it.isNotBlank() }
            val noteBlock = if (notes.isEmpty()) "" else "\nNOTES:\n" + notes.joinToString("\n") { "- $it" } + "\n"
            val hist = if (history.isEmpty()) "" else "\nHISTORY:\n" + history.takeLast(6).joinToString("\n")
            return """You are driving an Android phone's UI to accomplish: "$goal"
${NavPlan.routeBlock(route)}
CURRENT SCREEN:
$observation
$hist$noteBlock
Reply with exactly ONE action on one line, one of:
  tap("WORDS")      — tap a listed element by its words, e.g. tap("Start")
  tap(INDEX)        — only for an element listed with an [index] because it has no words
  long_press("WORDS") — press and hold: selects a file or a list row, opens its menu
  type("text")      — type into the first TYPEABLE field, then submit
  type(INDEX, "text") — type into a specific field
  scroll(down) / scroll(up)
  scroll_to("WORDS") — keep scrolling down until something with those words is on screen
  back()
  done              — only when the goal is visibly complete
To enter 16 on a keypad, tap("1") and then tap("6").

ACTION:"""
        }
    }

    data class NavResult(val success: Boolean, val summary: String, val steps: Int)

    /** Where the screens this navigator drives keep what has been learned about
     * them. Set by Brain, which owns the database. */
    var screenMemory: com.agent.ultra.data.ScreenMemoryDao? = null

    /** Controls known for the screen this run is working on. */
    private var known: List<ScreenControls.Control> = emptyList()

    /**
     * Has the goal visibly happened, without asking the model?
     *
     * The measured failure was "go to google.com in Chrome": the page loaded on
     * step one and the navigator then spent its whole budget deciding whether
     * it was finished. When the goal names a destination and the screen is
     * showing it, that is the answer - no model turn required.
     */
    private fun goalSatisfied(goal: String, observation: String): Boolean {
        val target = DOMAIN.find(goal)?.value ?: return false
        val bare = target.removePrefix("www.")
        return observation.contains(bare, ignoreCase = true)
    }

    suspend fun run(goal: String, appHint: String): String {
        val result = execute(goal, appHint)
        return if (result.success) "Goal achieved after ${result.steps} steps: ${result.summary}"
        else "Error: navigation incomplete after ${result.steps} steps: ${result.summary}"
    }

    suspend fun execute(goal: String, appHint: String): NavResult {
        if (!controller.serviceRunning) return NavResult(false, controller.serviceProblem, 0)


        stopReason = null

        // Resolve and launch the target app
        // An unknown app name is not a reason to stop: the thing the user asked about is often
        // already on screen ("Run the stopwatch" named no app, and the run died here —
        // AndroidWorld, 2026-09-19). Fall back to whatever is in front of us.
        val named = controller.findPackage(if (appHint.isBlank()) goal else appHint)
        // Never fall back onto Ultra itself: with the chat in front, an unresolved app name sent the
        // navigator tapping through its own UI (AndroidWorld, 2026-09-19).
        val pkg = named ?: controller.foregroundPackage()?.takeIf { it != OWN_PACKAGE }
            ?: return NavResult(false, "no app matching '$appHint' and nothing on screen", 0)
        if (named != null || pkg != controller.foregroundPackage()) {
            if (!controller.launchApp(pkg)) return NavResult(false, "could not launch $pkg", 0)
            delay(2500)
        } else {
            android.util.Log.i("UltraNav", "no app matching '$appHint'; working on what's on screen ($pkg)")
        }

        known = loadOrLearnControls(pkg)

        var observation = observe()
        // Launching the app may already have satisfied the goal.
        if (goalSatisfied(goal, observation)) {
            return NavResult(true, "already showing the goal", 0)
        }
        // A route a check has passed is played before any model is asked anything. What it
        // can't finish, the model picks up from where it stopped.
        val played = playRoute(pkg)
        if (played != null) {
            if (played.finished) return NavResult(true, played.note, played.steps)
            observation = observe()
        }
        // Ask for a plan before acting. A failure here is not fatal: an empty
        // plan runs the old single-goal loop, which is what happened before
        // any of this existed.
        var plan = requestPlan(goal, observation)
        var stage = 0
        var stageSteps = 0
        // Was this stage's expectation already true when the stage began?
        //
        // Measured: a plan gave stages 1 and 2 the same expectation, "New tab",
        // which the screen already showed. Both stages completed instantly
        // without the menu ever opening, and stage 3 — the one that mattered —
        // became unreachable. A checkpoint has to mark a CHANGE. A condition
        // that held before the stage started is not evidence the stage did
        // anything, so the stage falls back to being guidance.
        var stagePreSatisfied = false
        var stageBudget = NavPlan.budgetFor(MAX_ITER, plan.size)
        var replanned = false
        // The plan stops steering the run when it is abandoned, but it is kept
        // so the run can still report how far it got.
        var planActive = plan.isNotEmpty()
        if (plan.isNotEmpty()) {
            stagePreSatisfied = NavPlan.satisfied(plan[0], observation)
            android.util.Log.i("UltraNav", "PLAN ${plan.size} stages, $stageBudget steps each: " +
                plan.joinToString(" | ") { "${it.description} => ${it.expect.ifBlank { "(model decides)" }}" })
        }

        var lastTreePrefix = ""
        var stuckCount = 0
        var lastAction = ""
        var repeatedNoOp = 0
        // Actions that changed nothing, per exact screen: offered to the model as unavailable and
        // refused if picked anyway.
        val dead = mutableMapOf<String, MutableSet<String>>()
        // Taps asked for that nothing on the screen fits; cleared when the screen changes.
        val missing = mutableSetOf<String>()
        var unparseable = 0
        var leftAppFor = 0
        val history = mutableListOf<String>()
        if (played != null) history += played.note

        // Steps spent because something else took the screen are not steps the
        // agent wasted. Measured: an alarm app taking the foreground mid-task
        // cost four steps to notice and back out of, and the run then died of
        // "iteration budget exhausted" having recovered correctly. Recovery is
        // repaid, up to a cap so a genuinely lost run still ends.
        var grace = 0
        var iter = 0
        while (iter < MAX_ITER + grace) {
            // A judgement, not a failed step: end the run rather than let the
            // model try a different way to do the thing we just refused.
            stopReason?.let { return NavResult(false, it, iter) }
            iter++
            val aim = (if (planActive) plan.getOrNull(stage) else null)?.let {
                "${it.description}  (part of: $goal)"
            } ?: goal
            val deadHere = dead[observation].orEmpty()
            // What did nothing here is taken off the list the model chooses from, and named by
            // its words. Told "tap(13) is not available", it chose tap("0") fourteen more times:
            // it never knew [13] was the 0 key (2026-09-20).
            val deadWords = deadHere.map { a ->
                Regex("""^tap\((\d+)\)$""").find(a)?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { n -> labelled.firstOrNull { it.second == n }?.first }?.let { "tap(\"$it\")" } ?: a
            } + missing
            val offered = observation.lines().filterNot { line ->
                deadWords.any { d -> line.trim().lowercase().startsWith(d.removePrefix("tap(").removeSuffix(")")) }
            }.joinToString("\n")
            val prompt = buildPrompt(aim, offered, history, repeatedNoOp, leftAppFor, pkg, deadWords, routeHint)
            val reply = client.complete(
                listOf(OpenAiClient.ChatMessage("user", prompt)),
                maxTokens = 600,
                temperature = 0.1,
            ).getOrElse { return NavResult(false, "model call failed: ${it.message}", iter - 1) }

            // The screen as the model saw it. Without this the log shows which action it chose and
            // never what it was choosing from, so "why did it keep tapping instead of typing?"
            // could not be answered from a run (AndroidWorld, 2026-09-19).
            android.util.Log.i("UltraNav", "step $iter sees: ${observation.replace("\n", " | ").take(1600)}")

            val said = extractAction(reply) ?: run {
                // One bad reply is not the end of the job, and what it said is the only clue to
                // why: a whole task was lost at step 0 with nothing in the log (2026-09-20).
                android.util.Log.i("UltraNav", "step $iter unparseable reply: ${reply.replace("\n", " ").take(300)}")
                unparseable++
                if (unparseable >= 3) return NavResult(false, "model gave no parseable action", iter - 1)
                history += "step $iter: your reply was not one of the listed actions. Reply with exactly ONE action line, e.g. tap(\"Start\")."
                null
            } ?: continue
            // A tap is turned into the node index it names here, once, so the gate, the dead-action
            // list and the tap itself all see the same thing they always have.
            val isTap = Regex("""^(?:tap(?:_index)?|long_press)\(\s*(?:\d+|["'].+["'])\s*\)$""", RegexOption.IGNORE_CASE).matches(said.trim())
            val isLong = said.trim().startsWith("long_press", ignoreCase = true)
            val action = if (!isTap) said else {
                val idx = resolveTap(said.trim().replaceFirst(Regex("^long_press", RegexOption.IGNORE_CASE), "tap"), labelled, listedIndexes)
                if (idx == null) {
                    android.util.Log.i("UltraNav", "step $iter action: $said — nothing listed fits")
                    history += "step $iter: $said → NOT DONE: nothing listed on this screen fits that. Tap by the exact words shown, e.g. tap(\"Start\")."
                    // Asked twice for something that is not there: the stage wants a screen we
                    // are already on ("Tap Timer" while the Timer tab is open burned 15 steps).
                    if (said.trim() in missing && planActive && stage < plan.size - 1) {
                        android.util.Log.i("UltraNav", "stage ${stage + 1}/${plan.size} skipped at step $iter: its target is not on this screen")
                        stage++
                        stageSteps = 0
                        stagePreSatisfied = NavPlan.satisfied(plan[stage], observation)
                    }
                    missing += said.trim()
                    continue
                }
                if (isLong) "long_press($idx)" else "tap($idx)"
            }
            val byLabel = if (isTap && action != said.trim()) said else null

            if (action.equals("done", true)) {
                // Verify we're still on the expected app before accepting
                val onPkg = controller.activePackage()
                if (onPkg == pkg) return NavResult(true, "model reports goal complete", iter)
                history += "step $iter: tried done but left target app ($onPkg)"
                continue
            }

            // The model was told this action does nothing here and chose it anyway. Asking again
            // costs a model call; doing it again costs a step and teaches nothing.
            if (action in deadHere) {
                android.util.Log.i("UltraNav", "step $iter action: $action — REFUSED, dead on this screen")
                history += "step $iter: $said → REFUSED: already tried on this exact screen, nothing happened. Choose a different action."
                continue
            }

            val before = observation
            val textBefore = screenText(lastFlat)
            val readBefore = lastReadOnly
            // Log the action and its outcome. Without this a failing run gives
            // no way to tell a bad choice from a good choice executed badly,
            // and both look like "the tap did not work".
            // The words go in the log with the index: a route is built from this line, and an
            // index means nothing on the next run.
            val tappedWords = Regex("""^(?:tap|long_press)\((\d+)\)$""").find(action)?.groupValues?.get(1)?.toIntOrNull()
                ?.let { n -> labelled.firstOrNull { it.second == n }?.first }
            android.util.Log.i("UltraNav", "step $iter action: $action" +
                (if (tappedWords != null) "  = \"$tappedWords\"" else "") + (if (byLabel != null) "  <- $said" else ""))
            val ok = executeAction(action, observation)
            delay(900)
            observation = observe()
            // The list of things to tap is not the whole screen. A timer keypad looks the same
            // after every digit while the display above it changes; judged by the list alone,
            // each digit "did nothing" and the second 1 of 11 was refused (2026-09-20).
            val changed = observation != before || screenText(lastFlat) != textBefore

            if (goalSatisfied(goal, observation)) {
                return NavResult(true, "goal visible on screen after $iter steps", iter)
            }

            // Has this stage arrived? The engine checks the text the plan named,
            // which costs nothing. The old loop spent model turns asking itself
            // whether it had finished, and a model asked that question says yes
            // more readily than it should.
            if (planActive) {
                stageSteps++
                val here = plan[stage]
                // A stage the engine cannot check is guidance, not a gate. It
                // is done as soon as an action moves the screen — otherwise a
                // model that answered "EXPECT: none", exactly as it was asked
                // to, would leave the run stuck against a door with no handle.
                if ((!here.checkable || stagePreSatisfied) && changed) {
                    stage++
                    stageSteps = 0
                    stagePreSatisfied = plan.getOrNull(stage)
                        ?.let { NavPlan.satisfied(it, observation) } ?: false
                    android.util.Log.i("UltraNav", "stage $stage/${plan.size} passed (nothing to check) at step $iter")
                    if (stage >= plan.size) {
                        // The last stage had nothing to check, so "done" here means only that the
                        // screen moved. AndroidWorld 2026-09-19: the plan for "Run the stopwatch"
                        // was one uncheckable stage, "Tap Stopwatch" — the tab opened, the
                        // stopwatch never started, and the run reported success. Carry on toward
                        // the goal instead; the model still has to say done, or a later stage has
                        // to check out.
                        android.util.Log.i("UltraNav", "last stage was uncheckable — not calling that done")
                        planActive = false
                        history += "step $iter: the plan ran out and nothing confirmed the task is done — " +
                            "do the action the task actually asks for, then answer done"
                    }
                } else if (NavPlan.satisfied(here, observation)) {
                    stage++
                    stageSteps = 0
                    stagePreSatisfied = plan.getOrNull(stage)
                        ?.let { NavPlan.satisfied(it, observation) } ?: false
                    if (stagePreSatisfied) {
                        android.util.Log.i("UltraNav",
                            "stage ${stage + 1} expects something already on screen — treating it as guidance")
                    }
                    android.util.Log.i("UltraNav", "stage ${stage}/${plan.size} reached at step $iter")
                    history += "step $iter: reached \"${here.description}\" — now do the next stage"
                    if (stage >= plan.size) {
                        return NavResult(true, "all ${plan.size} stages done", iter)
                    }
                } else if (stageSteps >= stageBudget) {
                    // Out of room on this stage. Replan once from where we
                    // actually are, then accept that the plan was wrong and
                    // finish the run on the goal alone rather than looping.
                    if (!replanned) {
                        replanned = true
                        val fresh = requestPlan(goal, observation)
                        if (fresh.isNotEmpty()) {
                            plan = fresh
                            stage = 0
                            stageSteps = 0
                            stagePreSatisfied = fresh.firstOrNull()
                                ?.let { NavPlan.satisfied(it, observation) } ?: false
                            stageBudget = NavPlan.budgetFor(MAX_ITER - iter, fresh.size)
                            android.util.Log.i("UltraNav", "REPLAN at step $iter: ${fresh.size} stages")
                            history += "step $iter: that approach stalled, starting a new plan"
                        } else {
                            planActive = false
                        }
                    } else {
                        android.util.Log.i("UltraNav", "plan abandoned at step $iter, continuing on the goal")
                        planActive = false
                    }
                }
            }

            // An action that changes nothing, twice, is the model looping. Say
            // so in words it can act on, rather than letting it rediscover the
            // same dead end for the rest of the budget.
            if (!changed && action == lastAction) repeatedNoOp++ else repeatedNoOp = 0
            lastAction = action

            // Did that action take us out of the app we were sent to?
            //
            // A tap can open an ad, a share sheet, the Play Store, or a call
            // can arrive mid-task. The screen genuinely changed, so without
            // this the step reads as progress and the loop carries on driving
            // whatever app it landed in.
            //
            // Reported, not corrected. Some goals legitimately leave the app —
            // a link in an email opens the browser — so the engine states the
            // fact and the model decides whether to go back.
            // activePackage() returns "" when it cannot tell, not null, so a
            // null check passes for every String and an unknown foreground
            // would be reported as having left the app. Not knowing where we
            // are is not evidence of being somewhere else.
            val onPkg = controller.activePackage()
            val drifted = onPkg.isNotBlank() && onPkg != pkg && onPkg != OWN_PACKAGE
            if (drifted) {
                leftAppFor++
                if (grace < MAX_RECOVERY_GRACE) grace++
                android.util.Log.i(
                    "UltraNav",
                    "DRIFT: now in $onPkg, target $pkg (step $iter, $leftAppFor in a row, grace $grace)",
                )
            } else {
                leftAppFor = 0
            }

            val refusal = lastRefusal
            lastRefusal = null
            // "screen changed" says nothing about what the tap did. With the display in front of
            // it (00h 00m 01s) a model still pressed 1 again; told what appeared, it can count.
            val appeared = lastReadOnly.filter { it !in readBefore }.take(3)
            val nowShows = if (appeared.isEmpty()) "" else " — now shows: " + appeared.joinToString(", ") { "\"$it\"" }
            val outcome = when {
                refusal != null -> "NOT DONE: $refusal"
                changed -> "screen changed$nowShows"
                ok -> "NO CHANGE - do not repeat this"
                else -> "FAILED - do not repeat this"
            }
            val drift = if (drifted)
                " — you are now in $onPkg, NOT $pkg. Use back() unless leaving was intended."
            else ""
            if (!changed && refusal == null) dead.getOrPut(before) { mutableSetOf() } += action
            if (changed) missing.clear()
            android.util.Log.i("UltraNav", "step $iter outcome: $outcome$drift")
            // In the model's own words: the screen is listed by words now, so "tap(9)" in its
            // history was an index it had never been shown. It could not tell it had already
            // tapped Timer, and tapped Timer, Clock, Timer, Clock for 15 steps (2026-09-20).
            val shown = tappedWords?.let { (if (isLong) "long_press" else "tap") + "(\"$it\")" } ?: action
            history += "step $iter: $shown → $outcome$drift"

            // Stuck detector: same tree twice → scroll down once
            val prefix = observation.take(80)
            if (prefix == lastTreePrefix) {
                stuckCount++
                if (stuckCount >= 2) {
                    controller.scroll("down")
                    delay(600)
                    stuckCount = 0
                    observation = observe()
                }
            } else stuckCount = 0
            lastTreePrefix = prefix
        }
        // Say how far it got. "Iteration budget exhausted" tells the user only
        // that time ran out, and tells the brain nothing it can act on.
        val summary = if (plan.isEmpty()) "iteration budget exhausted"
            else "iteration budget exhausted — " + NavPlan.progressSummary(plan, stage) +
                if (!planActive) " (the plan was abandoned before the end)" else ""
        return NavResult(false, summary, iter)
    }

    /**
     * The dump the current observation was built from.
     *
     * Kept because everything downstream must talk about the screen the MODEL
     * was shown, not whatever the screen looks like by the time an action
     * runs. Reading it again to resolve an index is how a stale index turns
     * into a confident tap on the wrong thing.
     */
    private var lastFlat: String = ""
    /** From the last observe(): words -> node index, and the indexes shown because they had no words. */
    private var labelled: List<Pair<String, Int>> = emptyList()
    private var listedIndexes: Set<Int> = emptySet()
    private var lastReadOnly: List<String> = emptyList()
    private var typeableIndexes: List<Int> = emptyList()   // in screen order: a route says "the 2nd box"

    /**
     * Walk a route the user once showed us.
     *
     * No model turns at all. Each hop is: am I on the next screen yet; if not,
     * try a control that is safe to guess at; if that was wrong, go back and try
     * another. Arrival is a fingerprint match, which is a fact rather than a
     * judgement — the reason this can run without asking anyone anything.
     *
     * Stops and says where it got to rather than flailing. A route that has
     * stopped working is information; a walker that keeps pressing buttons on
     * someone's phone is not.
     */
    /** What a walk did, and anything it learned worth keeping. */
    /**
     * @param completed reached the last screen of the route. Recorded rather
     *   than inferred from the message, because "I got 1 of 2 steps in" and
     *   "followed it" are both ordinary outcomes and only one of them is the
     *   agent knowing how to do the job.
     */
    data class WalkResult(
        val message: String,
        val learned: List<ScreenJourney.Waypoint>,
        val completed: Boolean = false,
    )

    /**
     * Get back to the screen a hop is searching from, or admit we are lost.
     *
     * A wrong guess has to be undone, and undoing it is not always one press
     * of back. Back on a browser's first page closes the browser. Opening a
     * new tab leaves nothing to go back to. So this makes up to three moves —
     * reopening the app when a guess threw us out, pressing back when we are
     * in the right app on the wrong screen — and checks the fingerprint after
     * each one.
     *
     * When it cannot get back, it says so and the hop stops. That is the
     * important part. The alternative, and what this replaces, is a search
     * that carries on pressing things wherever it happens to have landed.
     */
    private suspend fun restoreAnchor(
        anchor: String,
        pkg: String,
        route: List<ScreenJourney.Waypoint>,
        hop: Int,
    ): Boolean {
        if (anchor.isBlank()) return true   // screen we cannot fingerprint: as before
        repeat(3) {
            if (settledKey() == anchor) return true
            if (controller.activePackage() != pkg) {
                android.util.Log.i("UltraWalk", "left $pkg; reopening")
                controller.launchApp(pkg)
                delay(1800)
            } else {
                controller.back()
                delay(1200)
            }
        }
        // Some screens cannot be returned to; they have to be reached again.
        //
        // A menu is the clear case. Guess wrong inside Chrome's menu and the
        // menu closes — back does not reopen it and neither does relaunching
        // the app, so a search of a menu got exactly one guess before losing
        // the screen it was searching. The route itself is the way back: the
        // walker already knows which control opened that menu, because it
        // learned it on the hop before.
        if (replayTo(route, hop)) {
            val landed = settledKey()
            if (landed == anchor) {
                android.util.Log.i("UltraWalk", "hop $hop: walked back to the screen")
                return true
            }
            android.util.Log.i("UltraWalk", "walked back but landed somewhere else")
        }
        return false
    }

    /**
     * Re-reach the screen a hop starts from by walking the route again.
     *
     * Only uses doors it has already learned. A replay that fell back to
     * searching would be a second search running inside the first, pressing
     * things to get back to where it was pressing things — so if any earlier
     * hop is still unknown, this gives up and the walk reports honestly.
     */
    private suspend fun replayTo(route: List<ScreenJourney.Waypoint>, hop: Int): Boolean {
        val needed = (1 until hop).map { route[it] }
        if (needed.any { it.via.isBlank() }) {
            android.util.Log.i("UltraWalk", "cannot walk back: an earlier hop is still unknown")
            return false
        }
        if (!controller.launchApp(route.first().pkg)) {
            android.util.Log.i("UltraWalk", "cannot walk back: ${route.first().pkg} would not open")
            return false
        }
        delay(2500)
        for (w in needed) {
            // Wait for the door to appear rather than demanding it now.
            //
            // A wrong guess often leaves something over the app — tapping
            // Chrome's bookmark button puts an edit sheet on top — and
            // reopening the app puts us behind it, where the control we need
            // is real but covered. One press of back clears that; a page still
            // loading just needs a moment. Both look identical from here, so
            // this alternates waiting with dismissing.
            var choice: RouteWalker.Candidate? = null
            for (look in 1..4) {
                choice = RouteWalker.rememberedChoice(controller.screenFlat(), w.via)
                if (choice != null) break
                if (look % 2 == 0) controller.back()
                delay(1200)
            }
            if (choice == null) {
                android.util.Log.i("UltraWalk", "cannot walk back: ${w.via} is not on this screen")
                return false
            }
            android.util.Log.i("UltraWalk", "walking back through ${w.via}")
            if (!tapCandidate(choice)) return false
            delay(1800)
        }
        return true
    }

    /**
     * Watch what a tap opened for a few seconds before judging it.
     *
     * A screen that has just been opened is not finished. Chrome's History
     * page arrives as a frame and fills in afterwards, and the two states
     * fingerprint differently — so a walk that read it once, immediately,
     * decided it had landed somewhere it had never been, pressed back, and
     * went looking for a door it had already opened.
     *
     * Returns as soon as it sees the screen it wants, or as soon as it is
     * clear nothing moved. Only a screen that is genuinely something else
     * costs the full wait.
     */
    private suspend fun awaitArrival(want: String, anchor: String): String {
        var seen = ""
        repeat(4) {
            seen = settledKey()
            if (seen == want) return seen
            if (seen == anchor) return seen   // nothing happened; no point waiting
            delay(1000)
        }
        return seen
    }

    /** The fingerprint of whatever is on top right now, or "" if unreadable. */
    private suspend fun screenKey(): String {
        val nodes = ScreenStructure.parse(controller.screenTree())
        val fp = ScreenSignature.of(controller.activePackage(), nodes)
        return if (fp.known) fp.key else ""
    }

    /**
     * The fingerprint of a screen that has stopped changing.
     *
     * A screen in motion fingerprints as something that will never be seen
     * again. A web page part-way through loading carries a reload button and a
     * progress bar; a second later it carries neither, and the two hash to
     * different screens. The walk anchored itself to one of those ghosts,
     * pressed a reload button that had no business being a candidate, and then
     * could not match its own anchor no matter where it went.
     *
     * So: read twice, a moment apart, and only believe a fingerprint that
     * holds still. It costs about a second. It buys an anchor that means
     * something.
     */
    private suspend fun settledKey(): String {
        var last = screenKey()
        repeat(3) {
            delay(900)
            val now = screenKey()
            if (now.isNotBlank() && now == last) return now
            last = now
        }
        return ""
    }

    suspend fun walkRoute(name: String, route: List<ScreenJourney.Waypoint>): WalkResult {
        if (!controller.serviceRunning)
            return WalkResult("Error: ${controller.serviceProblem}", route, completed = false)
        if (route.size < 2)
            return WalkResult("Error: \"$name\" has too little to follow.", route, completed = false)

        val start = route.first()
        if (!controller.launchApp(start.pkg))
            return WalkResult("Error: could not open ${start.pkg}", route, completed = false)
        delay(2000)

        // Filled in as the walk succeeds, so the next one is a lookup.
        val learned = route.toMutableList()

        var hop = 1
        var steps = 0
        while (hop < route.size && steps < MAX_ITER) {
            val target = route[hop]
            val here = ScreenStructure.parse(controller.screenTree())
            val pkg = controller.activePackage()

            if (ScreenJourney.arrivedAt(target, pkg, here)) {
                android.util.Log.i("UltraWalk", "hop $hop/${route.size - 1} reached")
                hop++
                continue
            }

            val tried = mutableSetOf<String>()
            var moved = false
            // The screen this hop is searching from.
            //
            // A list of controls and a list of ones already tried mean nothing
            // on their own — they mean something *on one screen*. Without this
            // the walk drifted: a wrong guess sent it out of Chrome, reopening
            // landed on a different tab, and it carried on searching there,
            // ticking off a new tab page's microphone and camera buttons as
            // though they were candidates for a hop that started on a web
            // page. Twelve tries, none of them on the screen in question, and
            // the menu button it needed sat unexamined the whole time.
            val anchor = settledKey()
            for (attempt in 1..RouteWalker.TRIES_PER_HOP) {
                if (steps++ >= MAX_ITER) break
                if (!restoreAnchor(anchor, target.pkg, learned, hop)) {
                    android.util.Log.i("UltraWalk", "hop $hop: lost the screen I was searching from")
                    break
                }
                // Choose from the same dump the tap indexes into. Choosing from
                // the tree and tapping by flat index meant the two disagreed
                // about which node an index named.
                // Read, and if the screen is mid-transition give it one more
                // look before concluding there is nothing to try. Going back
                // from a wrong guess leaves a browser reloading, and the first
                // read after that returns almost nothing — the walk used to
                // give up on hop one because of it.
                var flatNow = controller.screenFlat()
                // The door that worked last time, first. A route walked once
                // should not be searched again — that is the whole point of
                // having walked it.
                var choice = RouteWalker.rememberedChoice(flatNow, target.via, tried)
                if (choice != null) {
                    android.util.Log.i("UltraWalk", "hop $hop: remembered ${target.via}")
                }
                if (choice == null) {
                    choice = RouteWalker.candidatesFor(flatNow, tried, name).firstOrNull()
                }
                if (choice == null) {
                    delay(1200)
                    flatNow = controller.screenFlat()
                    choice = RouteWalker.candidatesFor(flatNow, tried, name).firstOrNull()
                }
                if (choice == null) break
                lastFlat = flatNow
                tried += RouteWalker.keyOf(choice)
                android.util.Log.i(
                    "UltraWalk",
                    "hop $hop: trying ${choice.vid.ifBlank { "[" + choice.index + "]" }}",
                )
                if (!tapCandidate(choice)) continue
                delay(1200)

                val afterKey = awaitArrival(target.key, anchor)
                if (afterKey == target.key) {
                    // Only a control the app named is worth remembering. An
                    // index is a position in one reading of one screen and
                    // means something else next time.
                    if (choice.vid.isNotBlank() && learned[hop].via != choice.vid) {
                        learned[hop] = learned[hop].copy(via = choice.vid)
                        android.util.Log.i("UltraWalk", "hop $hop: learned the way is ${choice.vid}")
                    }
                    android.util.Log.i("UltraWalk", "hop $hop/${route.size - 1} reached")
                    hop++
                    moved = true
                    break
                }
                android.util.Log.i(
                    "UltraWalk",
                    "hop $hop: landed on ${afterKey.ifBlank { "an unreadable screen" }}, " +
                        "wanted ${target.key}",
                )
                // A control that changed nothing needs no undoing.
                //
                // This is where the walk kept destroying its own position. It
                // pressed Chrome's reload button, the page reloaded to exactly
                // the screen it was already on, and the walk pressed back to
                // "undo" it — which closed the browser, because a page opened
                // by a link has no history behind it. It then could not find
                // its way back to a screen it had never actually left. One
                // wasted candidate cost it the entire hop.
                //
                // So: undo only what moved. If we are still on the anchor, the
                // control did nothing worth reversing, and the next candidate
                // gets tried from exactly where this one started.
                if (afterKey == anchor) continue

                // Wrong door, and it did open onto something. Close it before
                // trying the next one, or the search wanders instead of
                // searching. Whether back is enough is checked at the top of
                // the next attempt, which is the only place that can tell.
                controller.back()
                delay(1200)
            }
            if (!moved) {
                return WalkResult(
                    "I got ${hop - 1} of ${route.size - 1} steps into \"$name\" and could " +
                        "not find the way to the next screen. Either the app has changed, or the " +
                        "next step is something I will not press on a guess.",
                    learned,
                    completed = false,
                )
            }
        }

        val allTheWay = hop >= route.size
        return WalkResult(
            if (allTheWay) "Followed \"$name\" — all ${route.size - 1} steps."
            else "I got ${hop - 1} of ${route.size - 1} steps into \"$name\" before running out of tries.",
            learned,
            completed = allTheWay,
        )
    }

    /** a11y flat nodes → indexed TAPPABLE/TYPEABLE/SCROLLABLE lists (visible-only). */
    /** Every piece of text on the screen, tappable or not: what "did anything change" is judged on. */
    private fun screenText(flat: String): String = try {
        val arr = JSONArray(flat)
        (0 until arr.length()).joinToString("\n") { i ->
            arr.getJSONObject(i).let { it.optString("t") + "|" + it.optString("d") }
        }
    } catch (_: Exception) { flat }

    private suspend fun observe(): String {
        val flat = controller.screenFlat()
        lastFlat = flat
        // Every screen the navigator reads goes past the flow tracker. This is
        // the path the taint gate could not see: react_navigate returns a short
        // summary, so nothing the navigator READ ever reached the episode, and
        // a code on screen could be typed into another app with nothing
        // watching.
        controller.noteScreenSecrets(flat)
        return try {
            val arr = JSONArray(flat)
            if (arr.length() == 0) return "Screen: empty or inaccessible"
            val screenH = 2400 // conservative; off-screen filter is a heuristic
            val screenW = 1080.0
            val tappable = mutableListOf<String>()
            val typeable = mutableListOf<String>()
            val scrollable = mutableListOf<String>()
            val readOnly = mutableListOf<String>()
            val words = mutableListOf<Pair<String, Int>>()
            val listed = mutableSetOf<Int>()
            val boxes = mutableListOf<Int>()
            val vidCount = mutableMapOf<String, Int>()
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                if (n.optString("t").isNotBlank() || n.optString("d").isNotBlank()) continue
                if (!(n.optBoolean("c", false) || n.optBoolean("ca", false))) continue
                val v = n.optString("vid").replace(Regex("[^A-Za-z0-9]+"), " ").trim().lowercase()
                if (v.isNotEmpty()) vidCount.merge(v, 1, Int::plus)
            }
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                val label = n.optString("t").ifBlank { n.optString("d") }.trim().take(50)
                val editable = n.optBoolean("e", false)
                // An EMPTY text box has no text and usually no hint, and was dropped here with
                // everything else unlabelled — so a note editor read as "no interactive elements"
                // and the model tapped around it forever (AndroidWorld MarkorCreateNote,
                // 2026-09-19). A field you can type into is worth listing whether or not it has
                // anything written in it yet.
                if (label.isBlank() && !editable && !(n.optBoolean("c", false))) continue
                val y = n.optDouble("y", 0.0)
                if (y < 0 || y > screenH) continue
                val idx = n.optInt("i", i)
                // Tappable through an ancestor counts: the row handles the tap, the label sits on
                // a child (see AgentAccessibilityService.flattenNode).
                val clickable = n.optBoolean("c", false) || n.optBoolean("ca", false)
                val scrollableN = n.optBoolean("s", false)
                val hint = when {
                    editable -> " (input)"
                    clickable && label.lowercase().matches(Regex(".*\\b(ok|cancel|done|save|submit|send|close|accept|deny|allow|skip|next|back|yes|no)\\b.*")) -> " (button)"
                    clickable && label.lowercase().matches(Regex(".*\\b(settings|account|about|privacy|security|general|display|sound|battery)\\b.*")) -> " (menu-item)"
                    else -> ""
                }
                // An unlabelled button is usually the one that creates something: Markor's "+",
                // a compose pencil, a floating action button. Dropped for having no words, they
                // left the model with no way to make a new anything (AndroidWorld, 2026-09-19).
                // A button with no words still has the name its developer gave it (calendar_fab,
                // delete_button). Simple Calendar's screen was twenty identical "(unlabelled
                // button)" lines and even a strong model was blind on it (2026-09-20).
                val vidWords = n.optString("vid").replace(Regex("[^A-Za-z0-9]+"), " ").trim().lowercase()
                    .takeIf { it.length in 3..40 && it.any(Char::isLetter) }
                val named = label.ifBlank {
                    val x = n.optDouble("x", 0.0)
                    val side = if (x > screenW * 0.66) "right" else if (x < screenW * 0.33) "left" else "middle"
                    val band = if (y > screenH * 0.75) "bottom" else if (y < screenH * 0.25) "top" else "middle"
                    if (vidWords != null) "(no text; the app calls it \"$vidWords\", $band $side)"
                    else "(unlabelled button, $band $side)"
                }
                when {
                    editable -> { typeable += "  [$idx] ${label.ifBlank { "(empty text box)" }}$hint"; listed += idx; boxes += idx }
                    // By its words when it has any, and once: a tab's icon and its text are two
                    // nodes with one label. A number beside a label gets read as a value — asked
                    // to enter 16, the model tapped [16], the Alarm tab (2026-09-20).
                    clickable && label.isNotBlank() -> if (words.none { it.first == label.lowercase() }) {
                        words += label.lowercase() to idx
                        tappable += "  \"$label\"$hint"
                    }
                    // Its developer name works as its words when it is the only one so named.
                    clickable && vidWords != null && vidCount[vidWords] == 1 && words.none { it.first == vidWords } -> {
                        words += vidWords to idx
                        tappable += "  \"$vidWords\" (a button with no text; this is the app's own name for it)"
                    }
                    clickable -> { tappable += "  [$idx] $named$hint"; listed += idx }
                    scrollableN && tappable.isEmpty() -> scrollable += "  [$idx] $label"
                    // Words that can't be tapped are still the screen: a timer's display, a
                    // dialog's question, the name of the folder you are in. Without them the
                    // model typed a time blind — 7s until the display was full, then more
                    // (AndroidWorld ClockTimerEntry, 2026-09-20).
                    label.isNotBlank() && label !in readOnly -> readOnly += label
                }
            }
            val parts = mutableListOf<String>()
            if (tappable.isNotEmpty()) parts += "TAPPABLE:\n" + tappable.take(26).joinToString("\n")
            if (typeable.isNotEmpty()) parts += "TYPEABLE:\n" + typeable.take(8).joinToString("\n")
            if (scrollable.isNotEmpty()) parts += "SCROLLABLE:\n" + scrollable.take(3).joinToString("\n")
            if (readOnly.isNotEmpty() && parts.isNotEmpty())
                parts += "TEXT ON SCREEN (read-only, not tappable):\n" + readOnly.take(14).joinToString("\n") { "  $it" }
            labelled = words
            listedIndexes = listed
            lastReadOnly = readOnly.toList()
            typeableIndexes = boxes.toList()
            if (parts.isEmpty()) "Screen has no interactive elements — try scroll(down) or back()"
            else parts.joinToString("\n\n")
        } catch (e: Exception) {
            "Screen: observation failed (${e.message})"
        }
    }

    /** The one action in the reply. See ModelOutput for what changed and why. */
    private fun extractAction(text: String): String? = ModelOutput.action(text)

    private suspend fun executeAction(action: String, observation: String): Boolean {
        val a = action.trim()
        Regex("tap_index\\((\\d+)\\)", RegexOption.IGNORE_CASE).find(a)?.let { m ->
            return tapNodeIndex(m.groupValues[1].toInt())
        }
        Regex("tap\\((\\d+)\\)$", RegexOption.IGNORE_CASE).find(a)?.let { m ->
            return tapNodeIndex(m.groupValues[1].toInt())
        }
        Regex("long_press\\((\\d+)\\)$", RegexOption.IGNORE_CASE).find(a)?.let { m ->
            // Press and hold at the node's centre. Same gate as a tap: what it lands on is judged
            // by its words before anything is touched.
            val index = m.groupValues[1].toInt()
            val label = labelForIndex(index).orEmpty()
            if (!approveTap(label, "long_press($index)")) return false
            return when (controller.longClickByIndex(index, label)) {
                "ok" -> true
                "moved" -> { lastRefusal = "the screen changed before that could be pressed — look at it again"; false }
                "gone" -> { lastRefusal = "[$index] is no longer on the screen — look at it again"; false }
                else -> false
            }
        }
        Regex("tap\\((\\d+)\\s*,\\s*(\\d+)\\)", RegexOption.IGNORE_CASE).find(a)?.let { m ->
            val x = m.groupValues[1].toInt()
            val y = m.groupValues[2].toInt()
            // A raw-coordinate tap hides what it is hitting; look it up before
            // letting it through, or the gate is trivially bypassed by the
            // model choosing coordinates over an index.
            if (!approveTap(labelAtPoint(x, y), "tap($x,$y)")) return false
            return controller.tap(x, y)
        }
        Regex("type\\(\\s*(\\d*)\\s*,?\\s*[\"']([^)]+)[\"']\\s*\\)", RegexOption.IGNORE_CASE).find(a)?.let { m ->
            // An index that is not one of the listed text boxes is a guess, not a choice: given a
            // route that said type("name"), llama wrote type(1, "name"), [1] was not a box, and the
            // same refused keystroke ate the rest of the run (AndroidWorld MarkorCreateNote,
            // 2026-09-20). A guess falls back to what no index means: the focused or first box.
            val idx = m.groupValues[1].toIntOrNull()?.takeIf { it in typeableIndexes }
            val text = m.groupValues[2]
            // Empty selector → the service types into the FOCUSED editable field.
            // If nothing is focused (the proven Chrome failure: TEXT result=false
            // forever), tap the first editable node to focus it first.
            // Always into the FOCUSED box. Finding the box again by its words fails the moment it
            // already holds some: Markor's "Name" box arrives filled with "my_note", the lookup by
            // that label found nothing to type into, and naming a new file failed for a strong
            // model and a weak one alike (AndroidWorld MarkorCreateNote, 2026-09-20). So a box
            // chosen by index is tapped to focus it first — focusing a text box commits nothing,
            // and what gets typed is still checked below before a key is pressed.
            val selector = ""
            if (idx != null) {
                controller.clickByIndex(idx, labelForIndex(idx).orEmpty())
                delay(400)
            }
            // Typing with no index used to tap "the first editable node on
            // screen", which is a guess that is wrong on any page with more
            // than one box. When this screen has been here before, the app's
            // own name for its input is known and is used instead.
            else if (!focusKnownInput()) focusFirstEditable()
            // Before the keystroke, not after. Typing it and then noticing is
            // not a check, it is a log entry about a leak that already happened.
            controller.refuseTyping(text)?.let { why ->
                lastRefusal = "I did not type that: $why"
                android.util.Log.i("UltraFlow", "REFUSED typing into ${controller.activePackage()}: $why")
                return false
            }
            val ok = controller.typeInto(selector, text)
            if (!ok) return false
            delay(300)
            // Read back what actually landed before committing it.
            //
            // Measured on Chrome: "chrome://history/" was typed and the field
            // held "chrome//history/". The colon was gone, the page failed to
            // load, and nothing in the run knew anything had happened — the
            // type reported success and the agent spent the rest of its budget
            // wondering why the site would not open. Pressing enter on text
            // the field did not accept is how an agent searches for, or sends,
            // something nobody asked for.
            val landed = textInFocusedField(idx)
            if (landed != null && !landed.contains(text, ignoreCase = true)) {
                lastRefusal = "the field holds \"$landed\", not \"$text\" — " +
                    "it did not accept that text, so try another way in"
                android.util.Log.i("UltraNav", "type mismatch: wanted \"$text\", field has \"$landed\"")
                return false
            }
            controller.imeEnter()
            return true
        }
        Regex("""scroll_to\(\s*["'](.+)["']\s*\)""", RegexOption.IGNORE_CASE).find(a)?.let { m ->
            // Finding a row in a long list is the engine's job. Given only scroll(down), a strong
            // model still spent 9 of 15 steps scrolling and searching for a file that was two
            // screens down, and never reached it (AndroidWorld FilesDeleteFile, 2026-09-20).
            val want = m.groupValues[1].trim().lowercase()
            var last = ""
            repeat(12) {
                val now = observe()
                // The words themselves, not something that merely contains them: asked to find
                // "q2a8_fancy_banana.mp3", a contains-match stopped at "edited_q2a8_fancy_banana.mp3"
                // — the decoy the benchmark plants — and reported the file found (2026-09-20).
                if (labelled.any { wordsMatch(it.first, want) } || lastReadOnly.any { wordsMatch(it.lowercase(), want) }) return true
                if (now == last) return false          // the list stopped moving: it is not here
                last = now
                scrollEitherWay("down")
                delay(700)
            }
            return false
        }
        Regex("scroll\\((up|down)\\)", RegexOption.IGNORE_CASE).find(a)?.let { m ->
            return scrollEitherWay(m.groupValues[1].lowercase())
        }
        if (a.equals("back()", true)) return controller.back()
        if (a.equals("home()", true)) return controller.home()
        return false
    }

    /**
     * Scroll by asking the list, and by a finger when the list won't answer. The Files app's
     * list refused the accessibility scroll outright — scroll(down) FAILED four times while a
     * file sat one screen below (AndroidWorld FilesDeleteFile, 2026-09-20). A swipe up the middle
     * of the screen is what a person does, and it works on anything that scrolls at all.
     */
    private suspend fun scrollEitherWay(direction: String): Boolean {
        if (controller.scroll(direction)) return true
        val down = direction == "down"
        return controller.swipe(540, if (down) 1650 else 750, 540, if (down) 750 else 1650, 350)
    }

    /** `label` is `want`, or has it as whole words ("file notes.md 10/15" has "notes.md"). */
    private fun wordsMatch(label: String, want: String): Boolean =
        label == want || Regex("(^|\\s)" + Regex.escape(want) + "(\\s|$)").containsMatchIn(label)

    private data class Played(val finished: Boolean, val steps: Int, val note: String)

    /**
     * Play this call's share of the bound route, one action at a time, through the same
     * executeAction every model-chosen action goes through — so the action gate still asks before
     * a commitment and typing is still checked before a keystroke. It stops the moment a step
     * doesn't fit what is on the screen (nothing with those words, or the action changed
     * nothing); whatever is left is the model's, told what has been done.
     */
    private suspend fun playRoute(pkg: String): Played? {
        val (route, values) = routePlay ?: return null
        val navCalls = route.calls.filter { it.tool == "react_navigate" }
        val call = navCalls.getOrNull(routeCallsPlayed) ?: return null
        routeCallsPlayed++
        val script = RoutePlayer.script(call, values)
        if (script.actions.isEmpty()) return null
        var done = 0
        for ((n, said) in script.actions.withIndex()) {
            stopReason?.let { return Played(false, done, "route stopped: $it") }
            val before = observe()
            val textBefore = screenText(lastFlat)
            val isTap = said.startsWith("tap(") || said.startsWith("long_press(")
            // type(#2,"…") is "the second text box on this screen": an index means nothing on
            // another run, an ordinal does.
            val boxed = Regex("""^type\(#(\d+),\s*(".*")\)$""").find(said)
            val action = if (boxed != null) {
                val at = typeableIndexes.getOrNull(boxed.groupValues[1].toInt() - 1)
                if (at == null) {
                    android.util.Log.i("UltraNav", "ROUTE step ${n + 1}/${script.actions.size}: $said — this screen has no such text box; the model takes over")
                    return Played(false, done, "A route that has passed a check was followed for $done step(s); its next step, $said, did not fit this screen. Carry on from here.")
                }
                "type($at, ${boxed.groupValues[2]})"
            } else if (!isTap) said else {
                val idx = resolveTap(said.replaceFirst("long_press", "tap"), labelled, listedIndexes)
                if (idx == null) {
                    android.util.Log.i("UltraNav", "ROUTE step ${n + 1}/${script.actions.size}: $said — nothing on this screen has those words; the model takes over")
                    return Played(false, done, "A route that has passed a check was followed for $done step(s); its next step, $said, did not fit this screen. Carry on from here.")
                }
                (if (said.startsWith("long_press")) "long_press" else "tap") + "($idx)"
            }
            val ok = executeAction(action, before)
            delay(900)
            val after = observe()
            val changed = after != before || screenText(lastFlat) != textBefore
            val refusal = lastRefusal
            lastRefusal = null
            android.util.Log.i("UltraNav", "ROUTE step ${n + 1}/${script.actions.size} played: $said -> " +
                (if (refusal != null) "NOT DONE: $refusal" else if (changed) "screen changed" else if (ok) "no change" else "failed"))
            if (refusal != null || !(changed || (ok && said.startsWith("scroll_to")))) {
                return Played(false, done, "A route that has passed a check was followed for $done step(s); then $said did not work here" +
                    (refusal?.let { " ($it)" } ?: "") + ". Carry on from here.")
            }
            done++
            if (controller.activePackage().let { it.isNotBlank() && it != pkg && it != OWN_PACKAGE }) {
                return Played(false, done, "A route was followed for $done step(s) and the phone is now outside the app. Carry on from here.")
            }
        }
        val more = routeCallsPlayed < navCalls.size
        return if (script.complete)
            Played(true, done, "followed a route that has passed a check: $done steps" + if (more) " (the route has another part)" else "")
        else Played(false, done, "A route that has passed a check was followed for $done step(s); the rest of it could not be played. Finish the job from here.")
    }
    /** Resolve an [index] from the flat list to on-screen coordinates and tap. */
    /**
     * Tap what the model chose, or say why not.
     *
     * The label is carried from the observation the model was shown, so the
     * service can tell whether that index still holds the same thing. A
     * mismatch means the screen moved between being described and being acted
     * on — an advert loading, a page settling — and the honest response is to
     * look again, not to tap whatever is there now.
     */
    /**
     * Press a candidate by its id where it has one, by position otherwise.
     *
     * The index came from a dump read a moment ago and the screen may have
     * moved since. An id has not.
     */
    private suspend fun tapCandidate(c: RouteWalker.Candidate): Boolean {
        if (c.vid.isBlank()) return tapNodeIndex(c.index)
        if (!approveTap(c.label, "tap_index(${c.index})")) return false
        return when (val outcome = controller.clickByViewId(c.vid, c.index, "")) {
            "ok" -> true
            "gone" -> {
                android.util.Log.i("UltraWalk", "${c.vid} left the screen before it could be pressed")
                false
            }
            else -> {
                android.util.Log.i("UltraWalk", "press on ${c.vid} came back $outcome")
                false
            }
        }
    }

    private suspend fun tapNodeIndex(index: Int): Boolean {
        val label = labelForIndex(index).orEmpty()
        if (!approveTap(label, "tap_index($index)")) return false
        return when (val outcome = controller.clickByIndex(index, label)) {
            "ok" -> true
            "moved" -> {
                lastRefusal = "the screen changed before that could be tapped — look at it again"
                android.util.Log.i("UltraNav", "tap refused: [$index] no longer holds \"$label\"")
                false
            }
            "gone" -> {
                lastRefusal = "[$index] is no longer on the screen — look at it again"
                false
            }
            else -> {
                android.util.Log.i("UltraNav", "tap failed on [$index] \"$label\" ($outcome)")
                false
            }
        }
    }

    /** What the focused text field holds now, or null if none can be read. */
    /**
     * What the box that was typed into holds now. With an index it is THAT box: reading "the first
     * box with text in it" checked a file's extension box against its name box, called a typed
     * ".txt" a failure when it had worked, and the step was then missing from the route built from
     * that run (AndroidWorld MarkorCreateNote, 2026-09-20).
     */
    private suspend fun textInFocusedField(index: Int? = null): String? = try {
        val arr = JSONArray(controller.screenFlat())
        var found: String? = null
        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i)
            if (!n.optBoolean("e", false)) continue
            if (index != null && n.optInt("i", i) != index) continue
            val t = n.optString("t")
            if (t.isNotBlank()) { found = t; break }
        }
        found
    } catch (_: Exception) { null }

    /** Why the last action was refused, so the model is told rather than left
     * to guess from a bare failure. Cleared once reported. */
    private var lastRefusal: String? = null

    /**
     * What the **user** typed, as they typed it.
     *
     * Not the goal the navigator was handed. The model rewrites a request
     * before calling the navigator — asked to "pay 240 by pressing confirm
     * payment", it called the tool with "press confirm payment button after
     * entering 240", which no longer names an amount the way a person naming
     * an amount does.
     *
     * That matters beyond inconvenience. A check that reads the model's own
     * restatement of a request is a check the model can walk around by
     * restating it, and a safety property that depends on the model being
     * cooperative is not a safety property. The comparison is against the
     * person's words or it does not happen.
     */
    var userRequest: String = ""
        set(value) {
            field = value
            stoppedOnDisagreement = null   // a new message answers the old question
        }

    /** The route lesson recalled for this request, if any (Brain sets it; empty = none). */
    var routeHint: String = ""

    /**
     * The same route as steps the engine can play, already bound to this request's values — or
     * null when there is none, or the request is not an instance of its template. Brain sets it
     * once per run; each react_navigate call in the run plays the next call's steps.
     */
    var routePlay: Pair<RoutePlayer.Route, Map<String, String>>? = null
        set(value) { field = value; routeCallsPlayed = 0 }
    private var routeCallsPlayed = 0

    /**
     * Set when the screen contradicted the request, and the run must end.
     *
     * Refusing the tap alone was not enough. Watched on the phone: the tap was
     * refused three times and the model simply called the navigator again with
     * the amount removed from its goal — "press confirm payment button" — and
     * would have kept going. The check held because it reads the user's words,
     * but an agent that quietly retries a thing it has judged wrong is
     * obedient, not sensible. A disagreement ends the run and says why.
     */
    private var stopReason: String? = null

    /**
     * Held across the whole request, not one navigation run.
     *
     * Ending the run was still not enough. Watched on the phone: refused once,
     * the model called the navigator again with the same goal and spent
     * fifteen more steps looking for another way in. It never reached the
     * button — the check would have caught it again — but an agent that has
     * decided something is wrong and then keeps trying has not really decided
     * anything. Cleared when the user says something new, because their next
     * message is the answer to the question this raised.
     */
    var stoppedOnDisagreement: String? = null
        private set

    /**
     * Stop and ask before a tap that commits something. Everything else runs
     * untouched — a gate that fires on every tap gets waved through.
     */
    private suspend fun approveTap(label: String, action: String): Boolean {
        val reason = ActionGate.commitmentIn(label) ?: return true
        val pkg = controller.activePackage()

        // Before asking, check whether this is even the right thing to ask
        // about.
        //
        // A confirmation card is a question, and a person who has approved the
        // same question fifty times answers the fifty-first without reading it.
        // That is not carelessness, it is what habituation does, and a design
        // that relies on the user catching the one bad case in fifty is a
        // design that has quietly moved the responsibility onto them.
        //
        // So when the screen contradicts what was asked for, this does not ask.
        // It stops and says which numbers disagree. Refusing is a judgement the
        // agent is in a position to make; approving a payment the user never
        // described is not.
        // Read fresh rather than trusting the cache. A commitment is rare and
        // the screen it lands on is the one that matters; a stale reading here
        // would compare the request against the page before the total appeared.
        val screenNow = controller.screenFlat()
        Disagreement.contradiction(userRequest, screenNow)?.let { why ->
            stopReason = "I stopped before pressing \"$label\": $why. " +
                "Nothing was committed. Tell me which is right and I will carry on."
            stoppedOnDisagreement = stopReason
            lastRefusal = stopReason
            android.util.Log.i("UltraNav", "DISAGREE refused \"$label\": $why")
            return false
        }

        val approved = ActionGate.approve(action, label, pkg ?: "this app", reason)
        if (!approved) {
            android.util.Log.i("UltraNav", "action refused by operator: \"$label\"")
            return false
        }
        // Answering the card put Ultra in front. Tapping now would hit Ultra's
        // own UI at the target's coordinates, so put the target back first.
        if (pkg != null && controller.activePackage() != pkg) {
            controller.launchApp(pkg)
            delay(1500)
        }
        return true
    }

    /** What is at these coordinates, so a raw tap can be described. */
    private suspend fun labelAtPoint(x: Int, y: Int): String {
        return try {
            val arr = JSONArray(controller.screenFlat())
            var best = ""
            var bestDist = Int.MAX_VALUE
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                val label = n.optString("t").ifBlank { n.optString("d") }.trim()
                if (label.isBlank()) continue
                val dx = n.optInt("x", -9999) - x
                val dy = n.optInt("y", -9999) - y
                val d = dx * dx + dy * dy
                if (d < bestDist) { bestDist = d; best = label }
            }
            // Only trust a nearby node; a distant one is not what was tapped.
            if (bestDist <= 40_000) best else ""
        } catch (_: Exception) { "" }
    }

    /** Tap the first editable node's center so performText("") has focus. */
    /**
     * Tap the input this screen is known to have.
     *
     * False when the screen is new, when the app names nothing, or when the
     * remembered control is not on screen right now — in every one of those
     * cases the caller falls back to looking for an editable field, which is
     * what happened before any of this existed.
     */
    private suspend fun focusKnownInput(): Boolean {
        val target = ScreenControls.find(known, "search", ScreenControls.Role.INPUT)
            ?: known.firstOrNull { it.role == ScreenControls.Role.INPUT }
            ?: return false
        return try {
            val arr = JSONArray(controller.screenFlat())
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                if (n.optString("vid") != target.vid) continue
                val x = n.optInt("x", -1)
                val y = n.optInt("y", -1)
                if (x < 0 || y < 0) return false
                controller.tap(x, y)
                delay(500)
                android.util.Log.i("UltraNav", "typed into known control #${target.vid}")
                return true
            }
            false
        } catch (_: Exception) { false }
    }

    /**
     * The controls for the screen just launched, remembered or worked out now.
     *
     * Ids and roles only, never a label — a button's label is user content and
     * a view id is a constant from a layout file. Failing to load or store is
     * not an error: the run carries on exactly as it did before.
     */
    private suspend fun loadOrLearnControls(pkg: String): List<ScreenControls.Control> {
        val dao = screenMemory ?: return emptyList()
        return try {
            val nodes = ScreenStructure.parse(controller.screenTree())
            if (nodes.isEmpty()) return emptyList()
            val fp = ScreenSignature.of(pkg, nodes)
            if (!fp.known) return emptyList()

            // Two places to look, because an app's furniture and its content
            // are not learned at the same rate.
            //
            // A browser's fingerprint includes the ids of whatever page is
            // loaded, so every website is a different screen — which is right,
            // and which means the toolbar was being relearned on every visit
            // to every site. The address bar is the same control on all of
            // them. Controls seen anywhere in an app accumulate under an
            // app-level row and are available everywhere in it; the screen row
            // holds what is specific to that page.
            val appKey = "$pkg/*"
            val screenRow = dao.get(fp.key)
            val appRow = dao.get(appKey)

            val found = ScreenControls.of(nodes)
            val remembered = (
                ScreenControls.fromJson(screenRow?.controlsJson ?: "") +
                    ScreenControls.fromJson(appRow?.controlsJson ?: "")
                ).distinctBy { it.vid }

            if (found.isNotEmpty()) {
                dao.put(
                    (screenRow ?: newRow(fp.key, pkg, fp.confidence.name)).copy(
                        controlsJson = ScreenControls.toJson(found),
                        seenCount = (screenRow?.seenCount ?: 0) + 1,
                        lastSeen = System.currentTimeMillis(),
                    )
                )
                // The app-level set is a union: a control seen on any screen of
                // the app stays available on the others.
                val union = (found + ScreenControls.fromJson(appRow?.controlsJson ?: ""))
                    .distinctBy { it.vid }
                    .take(APP_CONTROL_LIMIT)
                dao.put(
                    (appRow ?: newRow(appKey, pkg, fp.confidence.name)).copy(
                        controlsJson = ScreenControls.toJson(union),
                        seenCount = (appRow?.seenCount ?: 0) + 1,
                        lastSeen = System.currentTimeMillis(),
                    )
                )
            }

            val all = (found + remembered).distinctBy { it.vid }
            android.util.Log.i(
                "UltraNav",
                "screen ${fp.key}: ${found.size} on screen, ${remembered.size} remembered, ${all.size} usable",
            )
            all
        } catch (e: Exception) {
            android.util.Log.w("UltraNav", "controls unavailable: ${e.message}")
            emptyList()
        }
    }

    /**
     * Ask the model for a plan.
     *
     * One call, low temperature, and every failure returns an empty list: no
     * plan simply means the old single-goal loop runs. Planning must never be
     * able to make a run worse than not planning.
     */
    private suspend fun requestPlan(goal: String, observation: String): List<NavPlan.Checkpoint> {
        return try {
            val reply = client.complete(
                listOf(OpenAiClient.ChatMessage("user", NavPlan.prompt(goal, observation, routeHint))),
                maxTokens = 400,
                temperature = 0.1,
            ).getOrNull() ?: return emptyList()
            NavPlan.parse(reply)
        } catch (e: Exception) {
            android.util.Log.w("UltraNav", "planning failed, continuing without one: ${e.message}")
            emptyList()
        }
    }

    private fun newRow(key: String, pkg: String, confidence: String) =
        com.agent.ultra.data.ScreenMemoryEntity(
            screenKey = key, pkg = pkg, template = "", recordCount = 0,
            fieldsCsv = "", seenCount = 0, lastSeen = 0L, confidence = confidence,
        )

    private suspend fun focusFirstEditable() {
        try {
            val arr = JSONArray(controller.screenFlat())
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                if (n.optBoolean("e", false)) {
                    val x = n.optInt("x", -1)
                    val y = n.optInt("y", -1)
                    if (x >= 0 && y >= 0) {
                        controller.tap(x, y)
                        delay(500)
                    }
                    return
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * The label the model was shown for this index.
     *
     * Read from the dump the observation was built from, never a fresh one.
     * Re-reading the screen here would return whatever is at that index NOW,
     * which is exactly the value that cannot be used to detect that the screen
     * moved.
     */
    private fun labelForIndex(index: Int): String? {
        return try {
            val arr = JSONArray(lastFlat.ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                if (n.optInt("i", -1) == index) {
                    val label = n.optString("t").ifBlank { n.optString("d") }.trim()
                    return label.ifBlank { null }
                }
            }
            null
        } catch (_: Exception) { null }
    }
}
