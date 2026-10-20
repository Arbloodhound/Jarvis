package com.agent.jarvis.agent

import com.agent.jarvis.local.LocalModelEngine
import com.agent.jarvis.ui.ChatStatus
import com.agent.jarvis.ui.ChatStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Perceive → think → act → verify on the on-device thinker, for when there is no cloud model.
 *
 * - Perceive: [ReActNavigator.perceive] (the same screen read, secrets noted), then
 *   [OfflineNav.compact] — numbered actionable elements, hard char cap.
 * - Think: one no-think ChatML call ([OfflineNav.prompt]), [OfflineNav.STEP_TOKENS] at most, parsed
 *   strictly; one re-ask on a reply that does not parse, then the run ends.
 * - Act: [ReActNavigator.act] — the cloud navigator's own executeAction, so ActionGate, the
 *   Disagreement stop and DirectRequest (option B) apply unchanged. open_app goes through
 *   [AgentController.launchApp] (app policy) like the cloud navigator's launch.
 * - Verify: the screen is read again and compared ([OfflineNav.signature]); the same action on the
 *   same screen twice ends the run ([OfflineNav.LoopGuard]).
 * - Budgets: [OfflineNav.MAX_STEPS], [OfflineNav.MAX_MILLIS]; progress in the chat status line;
 *   [stop] cancels the run, and the engine stops generating at its next token.
 *
 * Runtime on the phone is unproven: nothing here has run on a device.
 */
class OfflineNavigator(
    private val controller: AgentController,
    private val actor: ReActNavigator,
    private val local: LocalModelEngine,
) {
    /** [stopped]: the person pressed Stop. */
    data class Outcome(val success: Boolean, val summary: String, val steps: Int, val stopped: Boolean = false)

    @Volatile private var job: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var stepsTaken = 0

    /** Stop the run in progress (the chat's Stop button). Any open confirmation is denied first. */
    fun stop() {
        stopRequested = true
        ActionGate.cancelOutstanding()
        job?.cancel()
    }

    suspend fun run(task: OfflineNav.Task, userRequest: String): Outcome {
        stopRequested = false
        stepsTaken = 0
        active = this
        var status: ChatStatus.Status? = null
        fun show(detail: String) {
            val s = ChatStore.setStatus(ChatStatus.Phase.NAVIGATING, detail)
            status?.let { ChatStore.clearStatus(it) }
            status = s
        }
        // The person's touches are held off while Jarvis drives the app; the overlay's Stop ends it.
        com.agent.jarvis.AgentAccessibilityService.getInstance()?.showTakeover()
        return try {
            coroutineScope {
                job = coroutineContext[Job]
                loop(task, userRequest, ::show)
            }
        } catch (e: CancellationException) {
            // Our own Stop cancelled the inner scope; the request itself goes on to say so.
            if (stopRequested && currentCoroutineContext().isActive) Outcome(false, "stopped", stepsTaken, stopped = true) else throw e
        } finally {
            com.agent.jarvis.AgentAccessibilityService.getInstance()?.hideTakeover()
            job = null
            if (active === this) active = null
            status?.let { ChatStore.clearStatus(it) }
        }
    }

    private suspend fun loop(task: OfflineNav.Task, userRequest: String, show: (String) -> Unit): Outcome {
        if (!controller.serviceRunning) return Outcome(false, controller.serviceProblem, 0)
        // The same rule as react_navigate: a request already judged wrong does not go round again.
        // Only on a new request: the setter clears stoppedOnDisagreement, which must survive within one.
        if (actor.userRequest != userRequest) actor.userRequest = userRequest
        actor.stoppedOnDisagreement?.let { return Outcome(false, it, 0) }

        val named = if (task.appHint.isBlank()) null else controller.findPackage(task.appHint)
        val pkg = named ?: controller.foregroundPackage()?.takeIf { it != OWN_PACKAGE }
            ?: return Outcome(false, "no app matching '${task.appHint}' and nothing on screen", 0)
        if (named != null) {
            if (!controller.launchApp(pkg)) return Outcome(false, "could not launch $pkg", 0)
            delay(2500)
        }
        actor.beginExternalRun(pkg)

        val t0 = System.currentTimeMillis()
        val history = mutableListOf<String>()
        val guard = OfflineNav.LoopGuard()
        var looked = false
        var flat = actor.perceive()
        while (true) {
            OfflineNav.budgetStop(stepsTaken, System.currentTimeMillis() - t0)?.let {
                return Outcome(false, it, stepsTaken)
            }
            val step = stepsTaken + 1
            val (w, h) = actor.screenSize()
            var screen = OfflineNav.compact(flat, w, h)
            // Never for a protected app: its screen reads as PROTECTED (no elements), and a picture of
            // it must not be read either.
            if (screen.elements.size < OfflineNav.THIN_ELEMENTS && flat != com.agent.jarvis.AgentAccessibilityService.PROTECTED &&
                ScreenVision.unavailable() == null) {
                // A game, map or canvas page: the tree has next to nothing, the picture has the words.
                val words = ScreenVision.readScreen(controller)
                // The vision model at most once a run: it is slow, can't be stopped mid-answer, and
                // loads 2.8 GB next to the thinker.
                val seen = if (words.isNullOrBlank() && !looked) { looked = true; ScreenVision.look(controller, "") } else words
                screen = OfflineNav.withPicture(screen, seen)
            }
            val here = controller.activePackage().ifBlank { pkg }
            val sig = OfflineNav.signature(flat, here)

            show("step $step/${OfflineNav.MAX_STEPS}")
            val action = think(task.goal, history, screen, here)
                ?: return Outcome(false, "the on-device model gave no usable action", stepsTaken)
            android.util.Log.i(TAG, "step $step: ${action.said()}  (screen ${screen.elements.size} items, ${screen.text.length} chars)")

            when (action) {
                is OfflineNav.Action.Done -> return Outcome(true, action.answer.ifBlank { "done" }, stepsTaken)
                is OfflineNav.Action.Fail -> return Outcome(false, action.reason.ifBlank { "the model gave up" }, stepsTaken)
                else -> {}
            }
            if (guard.repeated(sig, action)) {
                return Outcome(false, "stopped: \"${action.said()}\" again on the same screen — it is going in circles", stepsTaken)
            }
            show("step $step/${OfflineNav.MAX_STEPS} · ${action.said().take(30)}")

            stepsTaken = step
            val outcome = perform(action, screen)
            outcome.stop?.let { return Outcome(false, it, stepsTaken) }
            delay(900)
            flat = actor.perceive()
            val after = controller.activePackage().ifBlank { pkg }
            val changed = OfflineNav.signature(flat, after) != sig
            val result = when {
                outcome.refusal != null -> "NOT DONE: ${outcome.refusal}"
                changed -> "screen changed"
                outcome.ok -> "nothing changed"
                else -> "failed"
            }
            val moved = if (after != here && after != OWN_PACKAGE) " (now in $after)" else ""
            history += OfflineNav.historyLine(step, action, screen, result + moved)
            android.util.Log.i(TAG, "step $step outcome: $result$moved")
        }
    }

    /** One step's action: the prompt, one generation, a strict parse; one re-ask if it does not parse. */
    private suspend fun think(goal: String, history: List<String>, screen: OfflineNav.Screen, app: String): OfflineNav.Action? {
        var note: String? = null
        repeat(2) { attempt ->
            val prompt = OfflineNav.prompt(goal, history, screen, app, note)
            val raw = local.generate(prompt, OfflineNav.STEP_TOKENS, foreground = true).getOrElse { e ->
                if (e is CancellationException) throw e
                android.util.Log.w(TAG, "generate failed: ${e.message}")
                return null
            }
            when (val parsed = OfflineNav.parse(raw, screen)) {
                is OfflineNav.Parsed.Ok -> return parsed.action
                is OfflineNav.Parsed.Bad -> {
                    android.util.Log.i(TAG, "reply ${attempt + 1} unusable (${parsed.why}): ${raw.replace("\n", " ").take(120)}")
                    note = OfflineNav.reaskNote(parsed.why)
                }
            }
        }
        return null
    }

    private suspend fun perform(action: OfflineNav.Action, screen: OfflineNav.Screen): ReActNavigator.Acted {
        if (action is OfflineNav.Action.OpenApp) {
            val p = controller.findPackage(action.name)
                ?: return ReActNavigator.Acted(false, "no app called \"${action.name}\"", null)
            val ok = controller.launchApp(p)
            if (ok) delay(1600)
            return ReActNavigator.Acted(ok, if (ok) null else "could not open ${action.name}", null)
        }
        if (action is OfflineNav.Action.Type) {
            val node = screen.element(action.n)?.node
            if (node == null || !actor.isTypeable(node)) {
                return ReActNavigator.Acted(false, "${action.n} is not a text box on this screen", null)
            }
        }
        val nav = OfflineNav.toNavigator(action, screen)
            ?: return ReActNavigator.Acted(false, "not a screen action", null)
        return actor.act(nav)
    }

    companion object {
        private const val TAG = "UltraOfflineNav"
        private const val OWN_PACKAGE = "com.agent.jarvis"

        /** The run in progress, whichever Brain (chat, voice, ambient) started it. */
        @Volatile private var active: OfflineNavigator? = null

        /** The chat's Stop button: stops the run in progress, if any. */
        fun stopActive() { active?.stop() }
    }
}
