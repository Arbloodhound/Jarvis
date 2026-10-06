package com.agent.ultra.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agent.ultra.gate.Gate
import com.agent.ultra.gate.GateAuditLog
import com.agent.ultra.gate.Manifest
import com.agent.ultra.provider.OpenAiClient
import com.agent.ultra.provider.ProviderConfig
import com.agent.ultra.ui.ChatMessage
import com.agent.ultra.ui.ChatStatus
import com.agent.ultra.ui.ChatStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The brain: a 12-turn tool loop. Ported from the proven BrainExecutor.ts —
 * same prompt shape, same parse rule (first balanced JSON object with a
 * "tool" key), dynamic token budget, push-once follow-through, stuck
 * detector, structured result feedback. Conversation-bleed fix in the port:
 * each request starts from a fresh message list with a capped history window.
 */
class Brain(private val appContext: Context, private val local: com.agent.ultra.local.LocalModelEngine) {

    private val controller = AgentController(appContext)
    private val tools: Tools
    private val client: OpenAiClient?
    private val gate: Gate
    private val taskMemory = com.agent.ultra.data.UltraDatabase.get(appContext).taskMemory()
    private val lessonDao = com.agent.ultra.data.UltraDatabase.get(appContext).lessons()
    /** Lessons served to the run in progress; their counts move when it ends. */
    @Volatile private var servedThisRun: List<String> = emptyList()
    private val recipes = Recipes(com.agent.ultra.data.UltraDatabase.get(appContext).recipes())

    /** Called with the final user-facing answer of a run. The voice session
     * speaks it; the chat screen speaks it when speak-back is enabled. */
    var onAnswer: ((String) -> Unit)? = null

    /** A scam warning raised this run. It is spoken ahead of the answer, so a voice user
     * hears it too (on screen it is already its own message). */
    private var spokenWarning: String? = null

    /** Every spoken/answered ending goes through here: the run's warning first, once. */
    private fun speakOut(text: String) {
        val w = spokenWarning
        spokenWarning = null
        onAnswer?.invoke(if (w != null) "$w\n\n$text" else text)
    }

    init {
        val cfg = ProviderConfig.load(appContext)
        client = if (cfg.isUsable) OpenAiClient(cfg) else null
        tools = Tools(appContext, controller)
        if (client != null) tools.navigator = ReActNavigator(controller, client).also {
            it.screenMemory = com.agent.ultra.data.UltraDatabase.get(appContext).screenMemory()
        }
        tools.recipes = recipes
        tools.screenMemory = com.agent.ultra.data.UltraDatabase.get(appContext).screenMemory()
        tools.recipeRunner = { name -> runRecipe(name) }
        gate = Gate(loadManifest(appContext))
    }

    private fun loadManifest(context: Context): Manifest = Manifest.fromAssets(context)

    val configured: Boolean get() = client != null

    /** What the model chip shows: the cloud model, or the on-device one. */
    // Was hard-coded to "Gemma 3 1B" whatever model was selected (seen on the Pixel with the MoE in use).
    val modelLabel: String get() = client?.modelName ?: "${local.modelLabel.substringBefore(" — ")} (on-device)"

    /** Non-null while a confirmable policy-gate block waits on the operator.
     * The chat UI renders a confirm/cancel card from this. */
    var pendingConfirm by mutableStateOf<PendingConfirm?>(null)

    /** One request at a time on this brain. Quick buttons, voice and ambient
     * commands can all call run() at once; interleaved runs shared episode,
     * lastRun and the chat window. A second request waits its turn. */
    private val runLock = Mutex()

    data class PendingConfirm(
        val description: String,
        val targets: List<String>,
        internal val tool: String,
        internal val params: JSONObject,
        internal val rawAssistant: String,
        internal val messages: MutableList<OpenAiClient.ChatMessage>,
        internal val turn: Int,
        internal val episode: Gate.Episode,
        internal val userInput: String,
        /** What the run had done before it paused, so resuming it keeps the record. */
        internal val sequence: List<Triple<String, JSONObject, Boolean>> = emptyList(),
        internal val steps: List<Experience.Step> = emptyList(),
    )

    /** The operator's decision on a paused action. */
    suspend fun resolvePending(approved: Boolean) = runLock.withLock {
        val p = pendingConfirm ?: return@withLock
        pendingConfirm = null
        val ai = client ?: return@withLock
        if (!approved) {
            answer("Cancelled: ${p.description}")
            return@withLock
        }
        p.targets.forEach { p.episode.confirm(it) }
        p.episode.observeOperatorConfirmation(p.tool)
        GateAuditLog.record(appContext, p.tool, GateAuditLog.Outcome.OVERRIDDEN, null, p.episode.observations)
        android.util.Log.i("UltraGate", "CONFIRMED ${p.tool} targets=${p.targets}")
        val resultText = tools.execute(p.tool, p.params)
        p.episode.observeSecrets(resultText)
        p.episode.observeTool(p.tool, resultText.take(80))
        val failed = resultText.startsWith("Error:") || resultText.startsWith("Could not")
        p.messages += OpenAiClient.ChatMessage("assistant", p.rawAssistant)
        p.messages += OpenAiClient.ChatMessage("user",
            "[RESULT: ${p.tool}] STATUS: ${if (failed) "failed" else "success"}\nDATA: ${resultText.take(2500)}\nDECIDE: Answer the user, or call the next tool.")
        runLoop(
            ai, p.userInput, p.messages, startTurn = p.turn + 1, episodeOverride = p.episode,
            priorSequence = p.sequence + Triple(p.tool, p.params, !failed),
            priorSteps = p.steps + Experience.Step(p.tool, p.params, !failed, resultText),
        )
    }

    suspend fun run(userInput: String) = runLock.withLock { runUnlocked(userInput) }

    private suspend fun runUnlocked(userInput: String) {
        // A paused action belongs to the request that paused it. Left in place it
        // could be confirmed later, in the middle of something else entirely.
        pendingConfirm?.let { stale ->
            pendingConfirm = null
            android.util.Log.i("UltraGate", "CANCELLED stale pause ${stale.tool}: a new request came in")
            emit("Cancelled: ${stale.description} (a new request came in)")
        }
        // A new request starts with no memory of what was on screen during the
        // last one. Secrets outliving the task that saw them would be a worse
        // thing than the leak this prevents.
        controller.forgetScreenSecrets()
        // Experience: take in anything the laptop's Northstar sent, and if this message
        // corrects the last run, that correction is a lesson before anything else happens.
        importVerdict()
        importFromLaptop()
        Experience.correction(lastRequest, userInput)?.let {
            saveLessons(listOf(it))
            // "no, that's wrong" is a verdict on the last run too, from the only judge there is
            // on a real phone. Only when the recorded run IS the last request: a recipe or
            // on-device run records nothing, and the verdict would land on an older one.
            if ((lastRun ?: loadLastRun())?.request == lastRequest) applyVerdict(false, "the user corrected it")
        }
        lastRequest = userInput
        // Whatever this request turns out to be, the recorded run is no longer the last one.
        // Only a cloud run records itself again, at its end.
        lastRun = null
        lastRunFile().delete()
        servedThisRun = emptyList()
        // The person's own words can carry a scam: "my grandson's in jail and needs Google
        // Play cards, help me buy them". Nothing is refused here — it is their request — but
        // they hear the warning before anything happens. Measured: llama-3.3-70b (the
        // default brain) opened Google Play for exactly that request, 3 of 3 times.
        spokenWarning = null
        ScamSignals.assess("", userInput).takeIf { it.scam }?.let { a ->
            android.util.Log.i("UltraBrain", "SCAM IN REQUEST: ${a.reasons}")
            val warning = "Careful — this sounds like a common scam (${a.reasons}). Real family, banks and " +
                "government offices don't ask for gift cards, codes, or money moved to a \"safe\" " +
                "account. If it's someone you know, call them on a number you already have."
            emit(warning)
            spokenWarning = warning
        }
        // A recipe is a name the user chose. Matching it is a lookup, not a
        // judgment call — measured: llama-3.3-70b read "run my morning
        // briefing" as a question about which model it is. The engine owns
        // structure; the model never sees this one.
        // The navigator compares what is on a payment screen against what the
        // person actually asked for, so it needs their words rather than the
        // model's summary of them.
        tools.navigator?.userRequest = userInput
        tools.navigator?.routeHint = ""
        tools.navigator?.routePlay = null
        if (tryRecipeShortcut(userInput)) {
            android.util.Log.i("UltraBrain", "RUN COMPLETE (recipe shortcut)")
            return
        }

        val ai = client
        if (ai == null) {
            // Offline/unconfigured path: the on-device model is the brain.
            if (!loadLocalShown()) {
                // The first thing a brand-new install says to whoever just
                // fought their way past four Android warnings. "No AI provider
                // configured and no on-device model present" is accurate and
                // useless: it names two things they have never heard of and
                // does not say where either lives.
                answer(
                    "I have no brain yet — that is the one thing you have to give me.\n\n" +
                        "Open Settings (the gear, top right) and pick one:\n\n" +
                        "• ON-DEVICE MODEL — download one and I run entirely on this phone, " +
                        "no account and no internet needed afterwards. The list says which " +
                        "ones fit this handset.\n" +
                        "• AI PROVIDER — paste in a service's address and key, if you already " +
                        "have one.\n\n" +
                        "While you are there: I also need the accessibility service switched " +
                        "on before I can see or touch any other app. Tap the red " +
                        "\"agent: a11y off\" at the top of this screen and it takes you " +
                        "straight to it."
                )
                return
            }
            emitLocal(userInput)
            return
        }

        // Local-first routing: simple device commands run on the on-device
        // model — faster, free, private, works offline. Complex or ambiguous
        // requests go straight to the cloud loop. A local miss escalates.
        // A big on-device model is the offline brain, not the fast path: it
        // answers "what is my battery level" in tens of seconds where the cloud
        // takes about one. Local-first only applies when it is actually first.
        if (isSimpleLocalIntent(userInput) && local.suitableForFastPath && local.ensureLoaded()) {
            val handled = runLocalLoop(userInput)
            if (handled) return
            emit("(on-device model couldn't map that — trying the cloud)")
        }

        // Fresh-window history: last exchanges from this conversation, char-capped.
        // (Fix-in-port: Build 29's context bleed came from carrying tool traces
        // across unrelated requests — tool feedback never enters this window.)
        val messages = mutableListOf(OpenAiClient.ChatMessage("system", systemPrompt()))
        // The request itself is already the last chat turn; it is added once, below.
        val history = LocalPrompt.withoutCurrentTurn(
            ChatStore.messages.takeLast(8).map { LocalPrompt.Turn(it.fromUser, it.text) },
            userInput,
        )
        val budget = 4000
        var used = 0
        val kept = mutableListOf<OpenAiClient.ChatMessage>()
        for (m in history.asReversed()) {
            if (used + m.text.length > budget) break
            kept += OpenAiClient.ChatMessage(if (m.fromUser) "user" else "assistant", m.text)
            used += m.text.length
        }
        messages += kept.asReversed()
        messages += OpenAiClient.ChatMessage("user", userInput)

        // Task memory: prior successful sequence for this request, and any
        // tools with a failing record, become a system-side hint.
        memoryHint(userInput)?.let {
            android.util.Log.i("UltraBrain", "MEMORY HINT injected: ${it.take(100)}")
            messages.add(1, OpenAiClient.ChatMessage("system", it))
        }
        lessonsFor(userInput)?.let { messages.add(1, OpenAiClient.ChatMessage("system", it)) }

        runLoop(ai, userInput, messages)
        android.util.Log.i("UltraBrain", "RUN COMPLETE")
    }

    /** Run a saved recipe directly when the request names one. Requires either
     * an explicit run verb ("run my morning briefing") or the bare recipe name,
     * so ordinary requests are never hijacked by a similarly-named routine. */
    private suspend fun tryRecipeShortcut(userInput: String): Boolean {
        return try {
            val row = recipes.resolve(userInput) ?: return false
            val hasRunVerb = Regex("^\\s*(run|start|do|execute|play)\\b")
                .containsMatchIn(userInput.lowercase())
            val isBareName = Recipes.normalize(userInput) == row.name ||
                Recipes.normalizeForName(userInput) == row.name
            if (!hasRunVerb && !isBareName) return false
            answer(runRecipe(row.name))
            true
        } catch (_: Exception) { false }
    }

    /**
     * Replay a saved recipe. The stored arguments were user-attested when the
     * recipe was created, so they are minted as confirmed targets for this
     * episode — otherwise every traceability contract would block, since
     * "run morning briefing" contains none of the recipe's actual targets.
     * Taint, spoof, and undeclared-tool checks are untouched and still apply.
     */
    private suspend fun runRecipe(name: String): String {
        val row = recipes.resolve(name)
            ?: return "Error: no recipe named \"$name\". Say \"list my recipes\" to see what is saved."
        // A routine learned by watching holds UI steps, not tool calls, and
        // replaying those is not built yet. Say that plainly rather than
        // running an empty list and reporting success.
        recipes.journeyOf(row.name)?.let { json ->
            val route = ScreenJourney.fromJson(json)
            val nav = tools.navigator
                ?: return "\"${row.name}\" is a route you showed me — " +
                    ScreenJourney.describe(route) + " — but navigation is unavailable."
            // Say what is known before doing it, not after.
            //
            // A routine the user showed us once and a routine the agent has
            // walked forty times produce identical output today, which makes
            // the confident-sounding one worth nothing. Reported first because
            // afterwards is too late to be told the agent was guessing.
            val before = recipes.competenceOf(row.name)
            android.util.Log.i(
                "UltraWalk",
                "walking \"${row.name}\": ${route.size} screens — ${before?.describe() ?: "no history"}",
            )
            val result = nav.walkRoute(row.name, route)
            // Keep what the walk worked out, and record how it went. A route
            // walked once should not be searched again — the second run is a
            // lookup, which is the whole reason for walking it the first time.
            val moved = ScreenJourney.doorsThatMoved(route, result.learned)
            try {
                recipes.noteWalk(
                    row.name,
                    completed = result.completed,
                    routeJson = ScreenJourney.toJson(result.learned),
                    doorsMoved = moved,
                )
                val known = result.learned.count { it.via.isNotBlank() }
                android.util.Log.i("UltraWalk", "remembered the way for $known hop(s)")
                if (moved > 0) android.util.Log.i("UltraWalk", "$moved door(s) had moved since last time")
            } catch (e: Exception) {
                android.util.Log.w("UltraWalk", "could not keep what was learned: ${e.message}")
            }
            // Only worth saying when the agent was working partly blind, or
            // when the app turned out to have changed. Announcing full
            // competence on every successful run is noise.
            val note = when {
                moved > 0 -> " (something had moved since I learned it, so I found it again)"
                before?.everWalked == false -> " (I had only watched this before, never done it)"
                else -> ""
            }
            return result.message + note
        }
        val steps = recipes.stepsOf(row.name).orEmpty()
        if (steps.isEmpty()) return "Error: recipe \"${row.name}\" has no steps"

        val episode = Gate.Episode("run recipe ${row.name}")
        for (step in steps) {
            for (key in step.params.keys()) {
                step.params.opt(key)?.toString()?.let { episode.confirm(it) }
            }
        }

        android.util.Log.i("UltraBrain", "RECIPE RUN ${row.name} (${steps.size} steps)")
        val lines = mutableListOf<String>()
        var failures = 0
        for ((i, step) in steps.withIndex()) {
            val verdict = gate.enforceCall(episode, step.tool, step.params)
            if (!verdict.allowed) {
                GateAuditLog.record(appContext, step.tool, GateAuditLog.Outcome.BLOCKED, verdict.rule, episode.observations, verdict.riskScore)
                val hint = verdict.violations.firstOrNull()?.hint ?: "blocked"
                android.util.Log.i("UltraGate", "RECIPE BLOCK ${step.tool}: $hint")
                lines += "${i + 1}. ${step.tool} — blocked by policy gate ($hint)"
                failures++
                continue
            }
            val auditOutcome = if (verdict.autoApproved) GateAuditLog.Outcome.AUTO_APPROVED else GateAuditLog.Outcome.ALLOWED
            if (verdict.autoApproved) android.util.Log.i("UltraGate", "RECIPE AUTO-APPROVE ${step.tool} (risk=${verdict.riskScore?.total})")
            GateAuditLog.record(appContext, step.tool, auditOutcome, verdict.rule, episode.observations, verdict.riskScore)
            val result = tools.execute(step.tool, step.params)
            episode.observeSecrets(result)
            episode.observeTool(step.tool, result.take(80))
            if (result.startsWith("Error:")) failures++
            android.util.Log.i("UltraBrain", "RECIPE STEP ${step.tool}: ${result.take(80)}")
            lines += "${i + 1}. ${result.take(200)}"
        }
        recipes.markRun(row.name)
        val header = if (failures == 0) "Ran \"${row.name}\" (${steps.size} steps):"
            else "Ran \"${row.name}\" with $failures problem(s):"
        return header + "\n" + lines.joinToString("\n")
    }

    private fun requestKey(input: String): String =
        input.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

    /** Content words only — the words that carry what the request is about. */
    private fun contentTokens(input: String): Set<String> =
        requestKey(input).split(" ")
            .filter { it.length > 2 && it !in STOPWORDS }
            .toSet()

    /**
     * Find what worked for a request like this one.
     *
     * Exact-string keys were nearly useless: "whats my location" and "what is
     * my location" were separate rows with separate memories. Matching is now
     * a token-set overlap over content words, so the same question phrased two
     * ways hits the same memory.
     */
    private suspend fun bestShortcut(userInput: String): Pair<com.agent.ultra.data.TaskShortcutEntity, Double>? {
        val mine = contentTokens(userInput)
        if (mine.isEmpty()) return null
        var best: com.agent.ultra.data.TaskShortcutEntity? = null
        var bestScore = 0.0
        for (row in taskMemory.allShortcuts()) {
            val theirs = contentTokens(row.requestKey)
            if (theirs.isEmpty()) continue
            // Containment, not Jaccard. The same job asked at different
            // lengths ("battery level" vs "how is the battery doing") scores
            // badly under Jaccard purely for being wordier. What matters is
            // whether the smaller request's subject is present in the larger.
            val score = TaskMatch.score(mine, theirs)
            if (score > bestScore) { bestScore = score; best = row }
        }
        return if (best != null && bestScore >= MATCH_THRESHOLD) best!! to bestScore else null
    }

    private suspend fun memoryHint(userInput: String): String? {
        return try {
            val parts = mutableListOf<String>()
            val match = bestShortcut(userInput)
            if (match != null && match.first.successCount > 0) {
                val row = match.first
                val how = row.stepsJson.ifBlank { row.toolsCsv }
                parts += "MEMORY: a request like this succeeded before " +
                    "(\"${row.requestKey}\") using: $how. Prefer that approach."

                // Only warn about a tool with a bad record when it is relevant
                // here — the tool that worked last time is not it. A blanket
                // warning on every request is noise the model has to ignore.
                val used = row.toolsCsv.split("→").map { it.trim() }.toSet()
                val risky = taskMemory.unreliableTools()
                    .filter { it.failures >= MIN_FAILURES_TO_WARN && it.tool !in used }
                if (risky.isNotEmpty()) {
                    parts += "AVOID: " + risky.take(2).joinToString { "${it.tool} (${it.failures} recent failures)" }
                }
            }
            if (parts.isEmpty()) null else parts.joinToString("\n")
        } catch (_: Exception) { null }
    }

    /**
     * Record what worked — but only when the TASK worked.
     *
     * Success used to be counted per tool call, so a run that ended with "I
     * couldn't find the price" still stored its tool sequence as the way to do
     * that job, and the wrong lesson got replayed. Per-tool reliability is
     * still recorded either way; that genuinely is a per-call fact.
     */
    private suspend fun recordMemory(
        userInput: String,
        sequence: List<Triple<String, JSONObject, Boolean>>,
        taskSucceeded: Boolean,
    ): String? {
        try {
            val steps = sequence.filter { it.third && it.first !in RECIPE_TOOLS }
                .map { Recipes.Step(it.first, it.second) }
            if (steps.isNotEmpty()) lastRunSteps = steps

            for ((tool, _, ok) in sequence) {
                val cur = taskMemory.reliabilityFor(tool)
                taskMemory.upsertReliability(
                    com.agent.ultra.data.ToolReliabilityEntity(
                        tool,
                        (cur?.successes ?: 0) + (if (ok) 1 else 0),
                        (cur?.failures ?: 0) + (if (ok) 0 else 1),
                        if (ok) "" else "recent failure",
                    )
                )
            }

            // Opening an app is not how a job gets done. "Run the stopwatch" ended after one
            // app_launch with nothing failing, so the run counted as a success and the shortcut
            // "run the stopwatch -> app_launch" was stored and served back — teaching Ultra to
            // stop at the front door (AndroidWorld, 2026-09-19).
            val onlyOpened = steps.size == 1 && steps[0].tool == "app_launch"
            val wantsMore = Regex("\\b(run|start|stop|set|create|add|make|send|delete|remove|turn|play|find|search|write|rename|move|copy|record)\\b",
                RegexOption.IGNORE_CASE).containsMatchIn(userInput)
            if (onlyOpened && wantsMore) {
                android.util.Log.i("UltraBrain", "MEMORY: not recorded (only opened the app for an action request)")
                return null
            }
            if (!taskSucceeded || steps.isEmpty()) {
                android.util.Log.i("UltraBrain", "MEMORY: not recorded (task succeeded=$taskSucceeded)")
                return null
            }
            val key = requestKey(userInput)
            val cur = taskMemory.shortcutFor(key)
            val stepsJson = org.json.JSONArray().also { arr ->
                steps.forEach { arr.put(JSONObject().put("tool", it.tool).put("params", it.params)) }
            }.toString()
            taskMemory.upsertShortcut(
                com.agent.ultra.data.TaskShortcutEntity(
                    key,
                    steps.joinToString(" → ") { it.tool },
                    (cur?.successCount ?: 0) + 1,
                    System.currentTimeMillis(),
                    stepsJson,
                )
            )
            android.util.Log.i("UltraBrain", "MEMORY: recorded \"$key\" -> ${steps.joinToString(" → ") { it.tool }}")
            return key
        } catch (_: Exception) {}
        return null
    }

    private fun emit(text: String) {
        ChatStore.add(ChatMessage(fromUser = false, text = text))
    }

    /** A run's final, user-facing answer: shown, persisted, and announced to
     * whoever is listening (the voice session, or chat speak-back). */
    private fun answer(text: String) {
        emit(text)
        speakOut(text)
    }

    /** [LocalModelEngine.ensureLoaded], with "Loading the brain…" under the chat while a real
     * load runs (the 10 GB MoE takes a long time on first use). Nothing shown if already loaded. */
    private suspend fun loadLocalShown(): Boolean {
        if (local.loaded) return true
        val shown = if (local.modelPresent && com.agent.ultra.local.LlmNative.available) ChatStore.setStatus(ChatStatus.Phase.LOADING) else null
        try {
            return local.ensureLoaded()
        } finally {
            shown?.let { ChatStore.clearStatus(it) }
        }
    }

    /** Direct local answer for the offline path. Thinking MoE presets get ChatML,
     * a routed + thermal-scaled think budget and a hidden trace; small models keep
     * the original plain prompt. */
    private suspend fun emitLocal(userInput: String) {
        val msg = ChatMessage(false, "")
        ChatStore.addToState(msg)
        // "Thinking…" from the moment decoding starts (engine lock held, model loaded) until
        // the first visible token: the trace is hidden, so without this the bubble stays
        // silent for the whole think budget. Set and cleared on the engine's generate thread;
        // the finally reads it after generate() has returned.
        var shown: ChatStatus.Status? = null
        val onStart: () -> Unit = { shown = ChatStore.setStatus(ChatStatus.Phase.THINKING) }
        val onPiece: (String) -> Unit = { piece ->
            if (piece.isNotEmpty()) shown?.let { ChatStore.clearStatus(it); shown = null }
            ChatStore.appendTo(msg.id, piece)
        }
        val result = try {
            if (local.isThinkingModel) {
                val level = ThermalGuard.level
                val route = IntentRouter.route(userInput)
                val history = LocalPrompt.withoutCurrentTurn(
                    ChatStore.messages.takeLast(8)
                        .filter { it.id != msg.id }
                        .map { LocalPrompt.Turn(it.fromUser, it.text) },
                    userInput,
                )
                val prompt = LocalPrompt.build(LocalPrompt.OFFLINE_RULES, history, userInput)
                val reserve = LocalPrompt.answerReserve(level)
                val clipped = ThinkBudget.clipToContext(
                    route.thinkBudget, com.agent.ultra.local.LocalModelEngine.CTX_SIZE,
                    LocalPrompt.estimateTokens(prompt), reserve,
                )
                val budget = ThermalPolicy.scaleBudget(clipped, level)
                android.util.Log.d("UltraRoute", "tier=${route.tier} reason=${route.reason} level=$level budget=$budget")
                ThermalPolicy.coolingNote(level, route.tier)?.let { ChatStore.appendTo(msg.id, it + "\n\n") }
                local.generate(
                    prompt, maxTokens = budget + reserve + 32,
                    thinkBudget = ThinkBudget(budget, startsInThink = true),
                    // threads: the engine picks them from the level once it holds the generate lock
                    onStart = onStart,
                ) { piece -> onPiece(piece) }
            } else {
                val prompt = "You are Jarvis, a concise assistant on an offline Android phone. " +
                    "Answer briefly and honestly.\n\nUser: $userInput\nJarvis:"
                local.generate(prompt, 400, onStart = onStart) { piece -> onPiece(piece) }
            }
        } finally {
            shown?.let { ChatStore.clearStatus(it) }
        }
        result.onFailure {
            ChatStore.setText(msg.id, "Error: on-device model failed — ${it.message}")
        }
        val finalMsg = ChatStore.messageById(msg.id) ?: msg
        ChatStore.persist(finalMsg)
        speakOut(finalMsg.text)
    }

    /** The on-device tool loop: compact catalog, max 2 turns, gate enforced. */
    private suspend fun runLocalLoop(userInput: String): Boolean {
        val episode = Gate.Episode(userInput)
        // What worked before, for the 1B model too. One line, and only when a
        // single tool is involved — this model follows a short concrete hint
        // and drowns in a long one.
        val recalled = try {
            bestShortcut(userInput)?.first?.takeIf { !it.toolsCsv.contains("→") }?.toolsCsv
        } catch (_: Exception) { null }
        if (recalled != null) {
            android.util.Log.i("UltraBrain", "MEMORY HINT (local): $recalled")
        }
        // Few-shot examples — 1B models map intents reliably with them, not
        // without (measured: zero-shot picked flashlight_toggle for 'open
        // chrome'). Trimmed catalog for the local route.
        var prompt = """You are Jarvis, an on-device Android agent. Reply with EXACTLY one JSON tool call and nothing else.

TOOLS:
flashlight_toggle {"on":true|false}
wifi_toggle {"on":true|false}
bluetooth_toggle {"on":true|false}
do_not_disturb {"on":true|false}
volume_set {"percent":0-100}
alarm_set {"hour":0-23,"minute":0-59,"label":"..."}
note_create {"text":"..."}
app_launch {"target":"app name"}
battery_status {}
clipboard_read {}
clipboard_write {"text":"..."}

EXAMPLES:
User: turn on the flashlight
JSON: {"tool":"flashlight_toggle","params":{"on":true}}

User: open chrome
JSON: {"tool":"app_launch","params":{"target":"chrome"}}

User: set an alarm for 7 30 am
JSON: {"tool":"alarm_set","params":{"hour":7,"minute":30,"label":"Jarvis alarm"}}

User: what's my battery level
JSON: {"tool":"battery_status","params":{}}

User: turn off wifi
JSON: {"tool":"wifi_toggle","params":{"on":false}}

User: $userInput
JSON:"""
        if (recalled != null) {
            prompt = prompt.replace(
                "User: $userInput",
                "A request like this previously worked with: $recalled\n\nUser: $userInput",
            )
        }
        repeat(2) { attempt ->
            val raw = local.generate(prompt, 200).getOrElse { return false }
            // The 1B model keeps writing after its answer — it replays the
            // few-shot examples as if the conversation continued. Cut at the
            // first echoed turn so the logs and the parser see one answer.
            val out = raw.split(Regex("""\n\s*(User|JSON)\s*:"""), limit = 2).first().trim()
            android.util.Log.i("UltraBrain", "LOCAL turn $attempt: ${out.take(120)}")
            // The 1B model reliably emits the tool NAME, not the JSON wrapper
            // (measured on-device). Parse both: JSON first, bare name second —
            // a deterministic engine shapes the params either way.
            val call = parseToolCall(out) ?: parseBareToolCall(out, userInput) ?: run {
                prompt += "\n\nThat was not a JSON tool call. Reply with ONLY the JSON."
                return@repeat
            }
            val verdict = gate.enforceCall(episode, call.first, call.second)
            if (!verdict.allowed) {
                GateAuditLog.record(appContext, call.first, GateAuditLog.Outcome.BLOCKED, verdict.rule, episode.observations, verdict.riskScore)
                android.util.Log.i("UltraGate", "LOCAL BLOCK ${call.first}: ${verdict.violations.firstOrNull()?.hint}")
                answer("Blocked by policy gate: ${verdict.violations.firstOrNull()?.hint}")
                return true
            }
            val localOutcome = if (verdict.autoApproved) GateAuditLog.Outcome.AUTO_APPROVED else GateAuditLog.Outcome.ALLOWED
            if (verdict.autoApproved) android.util.Log.i("UltraGate", "LOCAL AUTO-APPROVE ${call.first} (risk=${verdict.riskScore?.total})")
            GateAuditLog.record(appContext, call.first, localOutcome, verdict.rule, episode.observations, verdict.riskScore)
            android.util.Log.i("UltraBrain", "LOCAL TOOL: ${call.first} ${call.second.toString().take(80)}")
            val result = tools.execute(call.first, call.second)
            episode.observeSecrets(result)
            episode.observeTool(call.first, result.take(80))
            val verification = verifyAction(call.first, call.second) ?: ""
            val failed = result.startsWith("Error:")
            answer((if (failed) "Tried on-device: $result" else "$result (on-device)") + verification)
            android.util.Log.i("UltraBrain", "RUN COMPLETE (local, tool=${call.first}, ok=${!failed})")
            // On-device runs feed task memory and the recipe buffer too —
            // otherwise "save that as X" after a local command has nothing
            // to save.
            recordMemory(userInput, listOf(Triple(call.first, call.second, !failed)), !failed)
            return true
        }
        return false
    }

    private suspend fun runLoop(
        ai: OpenAiClient,
        userInput: String,
        messages: MutableList<OpenAiClient.ChatMessage>,
        startTurn: Int = 0,
        episodeOverride: Gate.Episode? = null,
        priorSequence: List<Triple<String, JSONObject, Boolean>> = emptyList(),
        priorSteps: List<Experience.Step> = emptyList(),
    ) {
        val maxTurns = 12
        var hasBeenPushed = false
        var finalText = ""
        // The bubble the final answer streamed into, when it did.
        var finalStreamId: String? = null
        var lastTool = priorSteps.lastOrNull()?.tool ?: ""
        var lastToolFailed = priorSteps.lastOrNull()?.ok == false
        var lastParams = ""
        // One security episode per user request; secrets accumulate across tools.
        val episode = episodeOverride ?: Gate.Episode(userInput)
        // Task memory: track this run's tool outcomes (params included so a
        // successful run can be promoted into a named recipe).
        val toolSequence = priorSequence.toMutableList()
        val runSteps = priorSteps.toMutableList()
        val finishedNavs = mutableSetOf<String>()
        // Task-level success: the model finished with its own answer and
        // nothing failed on the way. Running out of turns, giving up after a
        // repeated failure, or ending on a block are all NOT successes, even
        // though the individual calls before them may have returned fine.
        var naturalFinish = false
        var anyToolFailed = priorSequence.any { !it.third }

        for (turn in startTurn until maxTurns) {
            val maxTokens = if (turn == 0) 2000 else if (turn >= maxTurns - 2) 2500 else 1500
            // Stream the turn into a live bubble; the bubble is removed if the
            // turn ends up being a tool call (raw JSON isn't user-facing).
            val streamMsg = ChatMessage(false, "")
            ChatStore.addToState(streamMsg)
            val reply = ai.completeStreaming(messages, maxTokens, 0.2) { piece ->
                ChatStore.appendTo(streamMsg.id, piece)
            }.getOrElse {
                ChatStore.removeById(streamMsg.id)
                // Cloud failed (offline, quota, outage) — the on-device model
                // answers what it can rather than dying.
                if (loadLocalShown()) {
                    emit("(cloud unreachable — answering on-device)")
                    emitLocal(userInput)
                } else {
                    answer("Error: model call failed — ${it.message}")
                }
                return
            }
            val raw = reply.trim()

            val toolCall = parseToolCall(raw)
            // Raw tool JSON is not user-facing — retract the bubble it streamed into.
            if (toolCall != null) ChatStore.removeById(streamMsg.id)

            // A call cut off inside a value is not run half-written (an SMS sent
            // unfinished) and is not an answer either: ask for it again. The provider saying it
            // stopped at max_tokens counts too, even when the cut JSON happens to parse.
            if ((toolCall == null && ModelOutput.cutShortCall(raw)) || (toolCall != null && ai.lastReplyTruncated)) {
                ChatStore.removeById(streamMsg.id)
                android.util.Log.i("UltraBrain", "CUT-SHORT CALL, asking again: ${raw.take(200)}")
                messages += OpenAiClient.ChatMessage("assistant", raw)
                messages += OpenAiClient.ChatMessage("user",
                    "Your tool call was cut off before it ended. Send the complete JSON tool call again.")
                continue
            }

            if (toolCall == null) {
                // Push-once: user asked for an action, brain only described it
                if (turn > 0 && turn < maxTurns - 2 && !hasBeenPushed && userWantsAction(userInput) && raw.length < 800) {
                    hasBeenPushed = true
                    messages += OpenAiClient.ChatMessage("assistant", raw)
                    messages += OpenAiClient.ChatMessage("user",
                        "You described what to do but didn't do it. Use a tool to actually complete the action. Don't explain — execute.")
                    continue
                }
                finalText = raw
                finalStreamId = streamMsg.id
                naturalFinish = true
                android.util.Log.i("UltraBrain", "FINAL TEXT (${raw.length} chars): ${raw.replace("\n", " ").take(200)}")
                break
            }

            // The user's own words for the app, when the model named the same app differently
            // (AppMatch.canonical): the gate then traces it, and the same package opens.
            canonicalizeApp(userInput, toolCall.first, toolCall.second)

            // Logged BEFORE the gate: a blocked call never reached the TOOL CALL line below, so
            // the log said a call was refused and never what it was (AndroidWorld run, 2026-09-19).
            android.util.Log.i("UltraBrain", "TOOL ATTEMPT: ${toolCall.first} params=${toolCall.second.toString().take(200)}")

            // ── POLICY GATE (M3) — deterministic, no model judgment ──
            val verdict = gate.enforceCall(episode, toolCall.first, toolCall.second)
            if (!verdict.allowed) {
                val blockMsg = Gate.renderBlock(verdict)
                android.util.Log.i("UltraGate", "BLOCK ${toolCall.first}: ${verdict.violations.firstOrNull()?.hint}")
                if (verdict.confirmable) {
                    GateAuditLog.record(appContext, toolCall.first, GateAuditLog.Outcome.BLOCKED, verdict.rule, episode.observations, verdict.riskScore)
                    val targets = verdict.violations.mapNotNull { v ->
                        v.arg?.let { a -> toolCall.second.optString(a).takeIf { it.isNotBlank() } }
                    }.distinct()
                    val scam = verdict.violations.firstOrNull { it.rule == "scam_followup" }
                    val desc = (if (scam != null) "Careful: ${scam.hint}. " else "") +
                        describeAction(toolCall.first, toolCall.second)
                    pendingConfirm = PendingConfirm(
                        desc, targets, toolCall.first, toolCall.second, raw,
                        messages, turn, episode, userInput,
                        toolSequence.toList(), runSteps.toList(),
                    )
                    // Said through answer(): the voice screen waits for one, and
                    // this is what it shows next to Confirm / Cancel.
                    answer("Waiting for your OK: $desc. Confirm or cancel.")
                    return
                }
                GateAuditLog.record(appContext, toolCall.first, GateAuditLog.Outcome.BLOCKED, verdict.rule, episode.observations, verdict.riskScore)
                messages += OpenAiClient.ChatMessage("assistant", raw)
                messages += OpenAiClient.ChatMessage("user",
                    "[RESULT: ${toolCall.first}] STATUS: blocked\nDATA: $blockMsg\nDECIDE: Continue with the rest of the task, or answer the user.")
                lastTool = toolCall.first
                lastToolFailed = true
                continue
            }
            val cloudOutcome = if (verdict.autoApproved) GateAuditLog.Outcome.AUTO_APPROVED else GateAuditLog.Outcome.ALLOWED
            if (verdict.autoApproved) android.util.Log.i("UltraGate", "AUTO-APPROVE ${toolCall.first} (risk=${verdict.riskScore?.total})")
            GateAuditLog.record(appContext, toolCall.first, cloudOutcome, verdict.rule, episode.observations, verdict.riskScore)

            // Confirmation notice for destructive tools — UX layer; the gate
            // above is the enforcement layer.
            if (Tools.DESTRUCTIVE.contains(toolCall.first)) {
                val desc = describeAction(toolCall.first, toolCall.second)
                emit("About to: $desc")
            }

            android.util.Log.i("UltraBrain", "TOOL CALL: ${toolCall.first} params=${toolCall.second.toString().take(600)}")

            // Duplicate-call dedupe: the same tool with identical params just
            // succeeded → tell the model it's done instead of re-firing.
            // (Observed on-device: open_url fired twice per task.)
            // A navigation that finished is finished for the whole run, whatever came between:
            // the timer was set right (00h 16m 35s), then app_launch + the same react_navigate
            // ran again over it and typed the digits a second time (2026-09-20).
            val navAgain = toolCall.first == "react_navigate" && toolCall.second.toString() in finishedNavs
            if (navAgain || lastTool == toolCall.first && !lastToolFailed &&
                toolCall.second.toString() == lastParams) {
                messages += OpenAiClient.ChatMessage("assistant", raw)
                messages += OpenAiClient.ChatMessage("user",
                    "[RESULT: ${toolCall.first}] STATUS: success\nDATA: Already done — this exact call just succeeded. Do not repeat it.\nDECIDE: Answer the user, or call a DIFFERENT tool.")
                continue
            }

            val resultText = tools.execute(toolCall.first, toolCall.second)
            episode.observeSecrets(resultText)
            episode.observeTool(toolCall.first, resultText.take(80))
            val failed = resultText.startsWith("Error:") || resultText.startsWith("Could not")
            toolSequence += Triple(toolCall.first, toolCall.second, !failed)
            runSteps += Experience.Step(toolCall.first, toolCall.second, !failed, resultText)
            if (failed) anyToolFailed = true
            if (!failed && toolCall.first == "react_navigate") finishedNavs += toolCall.second.toString()
            val verification = if (!failed) verifyAction(toolCall.first, toolCall.second) else null
            // 120 characters cut a structured read off at its header, so the
            // rows the model actually reasoned over never reached the log. On
            // a release build there is no debugger and this is the only window
            // into what the model was given, so log enough to check it.
            // Android caps a single entry near 4 KB and splits on newlines.
            android.util.Log.i("UltraBrain", "TOOL RESULT (${if (failed) "fail" else "ok"}): ${resultText.take(TOOL_LOG_CHARS)}${verification ?: ""}")

            // Stuck detector: same tool failed twice in a row → stop honestly
            if (failed && lastToolFailed && lastTool == toolCall.first) {
                finalText = "That didn't work — ${toolCall.first} failed twice. ${resultText.take(150)}"
                break
            }
            lastTool = toolCall.first
            lastToolFailed = failed
            lastParams = toolCall.second.toString()

            messages += OpenAiClient.ChatMessage("assistant", raw)
            messages += OpenAiClient.ChatMessage("user",
                buildFeedback(toolCall.first, resultText + (verification ?: ""), turn, maxTurns, failed))

            if (turn == maxTurns - 1) finalText = "Ran ${toolCall.first}: ${resultText.take(300)}"
        }

        val lastOk = runSteps.lastOrNull { it.ok }
        // An empty final reply leaves an empty bubble behind; the answer below replaces it.
        finalStreamId?.takeIf { finalText.isBlank() }?.let { ChatStore.removeById(it) }
        if (finalText.isNotBlank()) {
            // The final answer already streamed into a visible bubble — settle
            // that bubble on the answer and persist it rather than double-emitting.
            // Addressed by id: comparing the last bubble's text failed on the
            // trimmed/untrimmed difference and showed the answer twice.
            val streamId = finalStreamId
            android.util.Log.i("UltraBrain", "EMIT TAIL: ${finalText.length}ch alreadyOnScreen=${streamId != null}")
            if (streamId != null) {
                ChatStore.setText(streamId, finalText)
                ChatStore.persist(ChatMessage(fromUser = false, text = finalText, id = streamId))
                speakOut(finalText)
            } else {
                answer(finalText)
            }
        } else if (lastToolFailed) {
            // Every turn ended in a block or failure — never end silently.
            answer("I couldn't complete that — the policy gate stopped the action and I had no safe alternative. Try rephrasing, or confirm the target if I ask.")
        } else if (lastOk != null) {
            // Out of turns on a call that had already succeeded (a repeat the dedupe
            // turned away, or a resumed pause with no turns left): say what was done.
            answer("Ran ${lastOk.tool}: ${lastOk.result.take(300)}")
        } else {
            answer("I couldn't finish that — I ran out of steps without an answer.")
        }
        android.util.Log.i("UltraBrain", "OBSERVATIONS: ${episode.observations.summary()}")
        val credited = naturalFinish && !anyToolFailed
        val served = servedThisRun
        val memoryKey = recordMemory(userInput, toolSequence, credited)
        val learned = learnFromRun(userInput, runSteps, credited, naturalFinish)
        lastRun = LastRun(userInput, credited, served, learned, memoryKey, runSteps.toList())
        saveLastRun()
    }

    /** What the last run did to memory, kept so an outside verdict can take it back. */
    private data class LastRun(
        val request: String,
        val credited: Boolean,
        val served: List<String>,
        val learnedFixes: List<String>,
        val memoryKey: String?,
        val steps: List<Experience.Step>,
    )
    private var lastRun: LastRun? = null

    // On disk as well as in memory: the process does not always live until the verdict comes.
    // AndroidWorld stops every app between tasks, so the first verdict ever sent found nothing
    // to apply to and was dropped without a word (2026-09-20).
    private fun lastRunFile() = java.io.File(appContext.filesDir, "last_run.json")

    private fun saveLastRun() {
        val run = lastRun ?: return
        try {
            val o = JSONObject().put("request", run.request).put("credited", run.credited)
                .put("served", org.json.JSONArray(run.served)).put("learned", org.json.JSONArray(run.learnedFixes))
                .put("memoryKey", run.memoryKey ?: JSONObject.NULL)
                .put("steps", org.json.JSONArray().also { arr ->
                    run.steps.forEach { arr.put(JSONObject().put("tool", it.tool).put("params", it.params)
                        .put("ok", it.ok).put("result", it.result.take(200))) }
                })
            lastRunFile().writeText(o.toString())
        } catch (e: Exception) { android.util.Log.w("UltraLearn", "last run not saved: ${e.message}") }
    }

    private fun loadLastRun(): LastRun? = try {
        val o = JSONObject(lastRunFile().readText())
        fun strings(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val steps = o.optJSONArray("steps")?.let { a -> (0 until a.length()).map { i ->
            val st = a.getJSONObject(i)
            Experience.Step(st.getString("tool"), st.optJSONObject("params") ?: JSONObject(), st.optBoolean("ok"), st.optString("result"))
        } }.orEmpty()
        LastRun(o.getString("request"), o.getBoolean("credited"), strings("served"), strings("learned"),
            if (o.isNull("memoryKey")) null else o.getString("memoryKey"), steps)
    } catch (_: Exception) { null }

    /**
     * Someone other than Ultra says whether the last run did the job.
     *
     * A pass changes nothing: the run was already counted as one. A fail on a run Ultra had
     * counted as a success takes all of it back — the stored "this is how" shortcut, the good
     * mark on every lesson it was served, any fix it thought it had found — and leaves one
     * lesson saying that approach doesn't do the job.
     */
    suspend fun applyVerdict(passed: Boolean, why: String) {
        val run = lastRun ?: loadLastRun() ?: run {
            android.util.Log.i("UltraLearn", "VERDICT ${if (passed) "pass" else "fail"} ($why): no record of the last run to apply it to")
            return
        }
        lastRun = null
        lastRunFile().delete()
        try {
            if (passed || !run.credited) {
                android.util.Log.i("UltraLearn", "VERDICT ${if (passed) "pass" else "fail"} ($why): nothing to take back")
                return
            }
            for (uid in run.served) lessonDao.recount(uid, -1, 1)
            run.memoryKey?.let { key ->
                val cur = taskMemory.shortcutFor(key)
                if (cur != null && cur.successCount > 1) taskMemory.upsertShortcut(cur.copy(successCount = cur.successCount - 1))
                else taskMemory.deleteShortcut(key)
            }
            for (uid in run.learnedFixes) lessonDao.delete(uid)
            android.util.Log.i("UltraLearn", "VERDICT fail ($why): took back memory=${run.memoryKey != null}, " +
                "${run.served.size} lesson outcome(s), ${run.learnedFixes.size} fix lesson(s)")
            Experience.verdict(run.request, run.steps)?.let { saveLessons(listOf(it)) }
            exportForLaptop()
        } catch (e: Exception) { android.util.Log.w("UltraLearn", "verdict failed: ${e.message}") }
    }

    /** verdict.json, written by whoever checked the last run: {"passed":false,"request":"…"}. */
    private suspend fun importVerdict() {
        for (dir in listOfNotNull(appContext.getExternalFilesDir(null), appContext.filesDir)) {
            val f = java.io.File(dir, "verdict.json")
            if (!f.exists()) continue
            try {
                val o = JSONObject(f.readText())
                f.delete()
                // A verdict for some other request says nothing about this run.
                val mine = (lastRun ?: loadLastRun())?.request
                if (mine == null) {
                    android.util.Log.i("UltraLearn", "VERDICT ignored: no record of the last run")
                    return
                }
                if (Experience.tokens(o.optString("request")) != Experience.tokens(mine)) {
                    android.util.Log.i("UltraLearn", "VERDICT ignored: it is for a different request")
                    return
                }
                applyVerdict(o.getBoolean("passed"), o.optString("by", "outside check"))
                return
            } catch (e: Exception) {
                android.util.Log.i("UltraLearn", "verdict file unreadable at ${dir.path}: ${e.message}")
            }
        }
    }

    private fun canonicalizeApp(userInput: String, tool: String, params: JSONObject) {
        val key = when (tool) { "app_launch" -> "target"; "react_navigate" -> "appHint"; else -> return }
        val named = params.optString(key).takeIf { it.isNotBlank() } ?: return
        try {
            val apps = controller.listLaunchableApps().map { AppMatch.App(it.first, it.second) }
            val mine = AppMatch.canonical(userInput, named, apps) ?: return
            if (mine != named.lowercase()) {
                params.put(key, mine)
                android.util.Log.i("UltraBrain", "APP NAME: \"$named\" -> the user's \"$mine\" (same app)")
            }
        } catch (_: Exception) {}
    }

    // ── Experience: the phone's own Northstar (agent/Experience.kt) ────

    private suspend fun lessonsFor(userInput: String): String? = try {
        val hits = Experience.recall(userInput, lessonDao.all().map { it.lesson() })
        servedThisRun = hits.map { it.uid }
        // A route goes to the navigator too: the brain only picks the tool, the taps happen there.
        // A route the engine can play: this request must be an instance of its template AND the
        // same kind as what it was learned from (a .txt request is not an instance of the .md
        // route). The first recalled route that is both gets played; failing that, the first
        // route is only read by the model, as before.
        val routes = hits.filter { it.source == Experience.ROUTE }
        val playable = routes.firstNotNullOfOrNull { lesson ->
            RoutePlayer.parse(lesson.text)?.let { route ->
                RoutePlayer.bind(route.template, userInput)?.takeIf { RoutePlayer.sameKind(route, it) }
                    ?.let { Triple(lesson, route, it) }
            }
        }
        val routeLesson = playable?.first ?: routes.firstOrNull()
        tools.navigator?.routeHint = routeLesson?.text?.let(RoutePlayer::forModel).orEmpty()
        tools.navigator?.routePlay = playable?.let { (lesson, route, values) ->
            android.util.Log.i("UltraLearn", "ROUTE bound: ${lesson.uid} with ${values.size} value(s) from the request")
            route to values
        }
        Experience.block(hits)?.also {
            android.util.Log.i("UltraLearn", "LESSONS SERVED: ${hits.joinToString { it.uid }}")
        }
    } catch (e: Exception) { android.util.Log.w("UltraLearn", "recall failed: ${e.message}"); null }

    /** The run is over: count it against every lesson it was given, and keep any wall it got past. */
    private suspend fun learnFromRun(userInput: String, steps: List<Experience.Step>, success: Boolean, finished: Boolean): List<String> {
        try {
            for (uid in servedThisRun) {
                // A verdict lesson is judged on the one thing it says, not on the whole task.
                val held = lessonDao.byUid(uid)?.lesson()?.let { Experience.verdictHeld(it, steps) }
                val good = held ?: success
                lessonDao.outcome(uid, if (good) 1 else 0, if (good) 0 else 1)
            }
            if (servedThisRun.isNotEmpty()) android.util.Log.i("UltraLearn", "OUTCOME ${if (success) "ok" else "fail"} for ${servedThisRun.joinToString()}")
            val counted = servedThisRun.isNotEmpty()
            servedThisRun = emptyList()
            val learned = Experience.capture(userInput, steps, finished)
            saveLessons(learned)
            // New counts must reach the laptop too, not only new lessons.
            if (counted && learned.isEmpty()) exportForLaptop()
            // Dead ends stay true whatever a later verdict says; a "fix" is only a fix if the job got done.
            return learned.filter { "-self-" in it.uid }.map { it.uid }
        } catch (e: Exception) { android.util.Log.w("UltraLearn", "learn failed: ${e.message}") }
        return emptyList()
    }

    private suspend fun saveLessons(lessons: List<Experience.Lesson>) {
        if (lessons.isEmpty()) return
        for (l in lessons) {
            // Same uid = same wall and fix: keep its track record, refresh the words.
            val old = lessonDao.byUid(l.uid)
            lessonDao.upsert(com.agent.ultra.data.LessonEntity.of(
                if (old == null) l else l.copy(served = old.served, ok = old.ok, fail = old.fail, createdAt = old.createdAt)))
            android.util.Log.i("UltraLearn", "LEARNED ${l.uid} (${l.source}): ${l.text.take(160)}")
        }
        exportForLaptop()
    }

    /** experience.jsonl in the app's external files dir: `northstar phone pull` reads it. */
    private suspend fun exportForLaptop() {
        try {
            val lines = lessonDao.all().joinToString("\n") { Experience.toJsonl(it.lesson()) }
            for (dir in listOfNotNull(appContext.getExternalFilesDir(null), appContext.filesDir)) {
                val tmp = java.io.File(dir, "experience.jsonl.tmp")
                tmp.writeText(lines + "\n")
                tmp.renameTo(java.io.File(dir, "experience.jsonl"))
            }
        } catch (e: Exception) { android.util.Log.w("UltraLearn", "export failed: ${e.message}") }
    }

    /** northstar_lessons.jsonl, pushed by `northstar phone push`: read once, then deleted. */
    private suspend fun importFromLaptop() {
        try {
            // Two places: the app's external files dir (the phone, over adb push) and its
            // internal one (an emulator, where a pushed file in Android/data is not readable by
            // the app — the laptop writes it with adb root instead).
            // Try each place and skip one that cannot be READ: on an emulator the external copy
            // exists but is unreadable (EACCES), and stopping at it meant the readable internal
            // copy was never used (2026-09-19).
            var got: List<Experience.Lesson> = emptyList()
            var from = ""
            for (dir in listOfNotNull(appContext.getExternalFilesDir(null), appContext.filesDir)) {
                val f = java.io.File(dir, "northstar_lessons.jsonl")
                if (!f.exists()) continue
                try {
                    got = f.readLines().mapNotNull { Experience.fromJsonl(it) }
                    from = dir.path
                    f.delete()
                    break
                } catch (e: Exception) {
                    android.util.Log.i("UltraLearn", "lesson file unreadable at ${dir.path}: ${e.message}")
                }
            }
            if (got.isEmpty()) return
            saveLessons(got)
            android.util.Log.i("UltraLearn", "IMPORTED ${got.size} lesson(s) from Northstar ($from)")
        } catch (e: Exception) { android.util.Log.w("UltraLearn", "import failed: ${e.message}") }
    }

    // ── Parsing & prompt (ported shapes) ───────────────────────────────

    /** First balanced {...} containing a "tool" key, code fences stripped. */
    private fun parseToolCall(text: String): Pair<String, JSONObject>? =
        ModelOutput.toolCall(text)

    /** Bare tool-name fallback for the 1B model: it emits the intent name, a
     * deterministic engine shapes params from the request text. */
    private fun parseBareToolCall(output: String, request: String): Pair<String, JSONObject>? {
        val names = listOf(
            "flashlight_toggle", "wifi_toggle", "bluetooth_toggle", "do_not_disturb",
            "volume_set", "alarm_set", "note_create", "app_launch",
            "battery_status", "clipboard_read", "clipboard_write",
        )
        val found = names.firstOrNull { n -> Regex("(^|\\W)$n(\\W|$)").containsMatchIn(output.trim()) }
            ?: return null
        val u = request.lowercase()
        val params = JSONObject()
        when {
            found.endsWith("_toggle") || found == "do_not_disturb" ->
                params.put("on", !Regex("\\b(off|disable)\\b").containsMatchIn(u))
            found == "volume_set" ->
                params.put("percent", Regex("(\\d{1,3})").find(u)?.value?.toIntOrNull() ?: 50)
            found == "alarm_set" -> {
                val (hour, minute) = alarmTime(u) ?: return null
                params.put("hour", hour)
                params.put("minute", minute)
                params.put("label", "Jarvis alarm")
            }
            found == "app_launch" -> {
                val m = Regex("(?:open|launch|start)\\s+(.+)$").find(u) ?: return null
                params.put("target", m.groupValues[1].trim())
            }
            found == "note_create" -> {
                val m = Regex("note(?:\\s+this)?:?\\s+(.+)$").find(u) ?: return null
                params.put("text", m.groupValues[1].trim())
            }
            found == "clipboard_write" -> {
                val m = Regex("copy\\s+(.+?)(?:\\s+to\\s+(?:my\\s+)?clipboard)?$").find(u) ?: return null
                params.put("text", m.groupValues[1].trim())
            }
            // battery_status / clipboard_read take no params
        }
        return found to params
    }

    private fun userWantsAction(input: String): Boolean =
        Regex("\\b(navigate|send|text|open|go to|take me|set|create|make|call|play|turn on|turn off|toggle|install|download|share|copy|find me|get me|show me|order|book|buy)\\b",
            RegexOption.IGNORE_CASE).containsMatchIn(input)

    /**
     * Deterministic post-action verification — no model judgment. Returns a
     * "\nVERIFIED: …"/"\nUNVERIFIED: …" suffix for known-verifiable tools,
     * null when nothing can be checked.
     */
    private suspend fun verifyAction(tool: String, params: JSONObject): String? {
        return try {
            when (tool) {
                "app_launch" -> {
                    // Launch transitions run through systemui first on this
                    // device — check twice before declaring failure.
                    val target = controller.findPackage(params.optString("target")) ?: ""
                    var active = ""
                    for (waitMs in listOf(1500L, 2500L)) {
                        kotlinx.coroutines.delay(waitMs)
                        active = controller.activePackage()
                        if (active == target) break
                    }
                    if (active.isNotBlank() && active == target) "\nVERIFIED: $active is in front"
                    else "\nUNVERIFIED: expected $target in front, found '${active.ifBlank { "nothing" }}'"
                }
                "open_url" -> {
                    var active = ""
                    for (waitMs in listOf(1500L, 2500L)) {
                        kotlinx.coroutines.delay(waitMs)
                        active = controller.activePackage()
                        if (active.contains("chrome") || active.contains("browser") || active.contains("firefox")) break
                    }
                    if (active.contains("chrome") || active.contains("browser") || active.contains("firefox"))
                        "\nVERIFIED: a browser ($active) is in front"
                    else "\nUNVERIFIED: foreground is '$active'"
                }
                "clipboard_write" -> {
                    val want = params.optString("text")
                    val got = controller.clipboardRead()
                    if (got.contains(want)) "\nVERIFIED: clipboard holds the text"
                    else "\nUNVERIFIED: clipboard reads '$got'"
                }
                "flashlight_toggle" -> {
                    // CameraManager.setTorchMode is fire-and-forget, no query
                    // API without a callback. Trust the return value for now.
                    null
                }
                "wifi_toggle" -> {
                    kotlinx.coroutines.delay(1000)
                    val want = params.optBoolean("on", true)
                    val actual = controller.isWifiEnabled()
                    if (actual == want) "\nVERIFIED: WiFi is ${if (want) "on" else "off"}"
                    else "\nUNVERIFIED: WiFi reports ${actual ?: "unknown"}, wanted ${if (want) "on" else "off"}"
                }
                "bluetooth_toggle" -> {
                    kotlinx.coroutines.delay(1500)
                    val want = params.optBoolean("on", true)
                    val actual = controller.isBluetoothEnabled()
                    if (actual == want) "\nVERIFIED: Bluetooth is ${if (want) "on" else "off"}"
                    else "\nUNVERIFIED: Bluetooth reports ${actual ?: "unknown"}, wanted ${if (want) "on" else "off"}"
                }
                "do_not_disturb" -> {
                    kotlinx.coroutines.delay(500)
                    val want = params.optBoolean("on", true)
                    val actual = controller.isDoNotDisturbOn()
                    if (actual == want) "\nVERIFIED: DND is ${if (want) "on" else "off"}"
                    else "\nUNVERIFIED: DND reports ${actual ?: "unknown"}, wanted ${if (want) "on" else "off"}"
                }
                "volume_set" -> {
                    kotlinx.coroutines.delay(300)
                    var want = params.optInt("percent", -1)
                    if (want < 0) want = (params.optDouble("level", -1.0) * 100).toInt()
                    val actual = controller.volumePercent()
                    if (actual != null && want >= 0 && kotlin.math.abs(actual - want) <= 7)
                        "\nVERIFIED: volume at $actual%"
                    else "\nUNVERIFIED: volume at ${actual ?: "unknown"}%, wanted $want%"
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun describeAction(tool: String, params: JSONObject): String = when (tool) {
        "sms_send" -> "Send a text to ${params.optString("to").ifBlank { "unknown" }}: \"${params.optString("message")}\""
        "file_delete" -> "Delete file: ${params.optString("filename").ifBlank { params.optString("path") }}"
        else -> "Execute $tool"
    }

    private fun buildFeedback(tool: String, result: String, turn: Int, maxTurns: Int, failed: Boolean): String {
        val status = if (failed) "failed" else "success"
        var fb = "[RESULT: $tool] STATUS: $status\nDATA: ${result.take(2500)}\nTURNS_LEFT: ${maxTurns - turn - 1}/$maxTurns"
        fb += if (tool == "web_search" && result.contains("Search results"))
            "\nYou have the search results above. Answer the user directly. Do NOT open a browser."
        else
            "\nDECIDE: Answer the user, or call the next tool."
        return fb
    }

    private fun systemPrompt(): String {
        val now = Date()
        val date = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(now)
        val time = SimpleDateFormat("h:mm a", Locale.US).format(now)
        val env = buildList {
            add(controller.batteryStatus())
            controller.currentWifiSsid()?.let { add("WiFi: $it") }
        }.joinToString(" | ")

        return """You are Jarvis — a capable, concise AI agent controlling this Android phone.
PHONE STATE: $env
TODAY: $date at $time

FORMAT: To use a tool: {"tool":"name","params":{...}} — To talk: plain text. ONE tool call per response.

$TOOL_CATALOG

RULES:
1. Understand what the user WANTS, break it into steps, execute each with a tool call. You get up to 12 tool calls.
2. ALWAYS prefer direct tools over UI automation: toggles > app_launch > react_navigate. Only use react_navigate when you need to interact INSIDE an app.
3. When web_search returns text results, READ THEM and answer directly. Do NOT open a browser to see results you already have. MAX 2 web_searches per task.
4. After every tool call, VERIFY the result. If it failed, try a different approach. If the same tool fails twice, stop and tell the user.
5. Read screen content to gather data, then use it in the next tool call. If what you need could be further down the page — results, prices, list items, article text — use read_screen_deep, not read_text_on_screen. Never report that content is unavailable until you have tried read_screen_deep.
6. When you have enough information to answer, STOP calling tools and give a clear, complete answer.
7. If you hit a login screen, captcha, or permission dialog: STOP and ask the user to handle it.
8. NEVER send messages or make calls unless the user EXPLICITLY asks.
9. RECIPES: "save that as X" / "remember that as X" → recipe_save {name:X}. "what are my routines" → recipe_list. "forget X" → recipe_delete {name:X}. Never answer a recipe request with prose — call the tool.
10. SCAMS: gift cards as payment, sharing a code someone asked for, "family" in trouble on a new number, a bank or agency asking to move money or confirm details — say plainly it looks like a scam and suggest calling the person on a number they already have. Do not help buy the cards, send the code, open the link or move the money.
11. A QUESTION about the phone ("is bluetooth on?", "how much storage?") is answered with system_info — never with a toggle or any change. Never create notes, alarms or recipes, or change a setting, unless the user asked for exactly that.
12. "Open X" is done when X is open — but only when opening IS the whole request. If the request also asks for something to happen (run, start, set, create, add, send, delete, find), keep going until that thing is actually done and you can see it. Never create, change or allow anything the user did not ask for.
13. GROUNDING IS A TOOL, NOT A LEASH. Reason from your own knowledge by default. Reach for web_search only when the answer is time-sensitive, a current fact, or something you are unsure of. Offline, or when search returns nothing, ANSWER from your own knowledge — never stall, never apologize for lacking the web.
14. NOVELTY RULE. If the user describes their own work, a new idea, or something the web cannot confirm, reason it through from first principles. Missing or absent search results are NOT evidence that the user is wrong — the web lags new work. Never contradict the user's premise on the grounds that you could not find it.
15. VERIFICATION LOOP (for any non-trivial factual or technical claim, online or offline): before asserting it, think through what would have to be TRUE for the claim to hold — its premises. State each premise, check each against your knowledge (or a single targeted web_search when available and worth it), and only then give the conclusion. If a premise fails or is unknown, say so and give the conclusion's confidence. Do this reasoning concisely; do not pad.
16. PROVENANCE. When an answer came from web_search, cite the source. When it came from your own knowledge, just answer — and in the risk domains (pharmacology, chemistry, opsec, medical/psychological) add a short "(from model knowledge, unverified)" tag so the user knows to double-check. This is a tag, never a refusal."""
    }

    companion object {
        /** Successful calls from the most recent completed run, process-wide.
         * "save that as morning briefing" is its own request with its own empty
         * sequence, so the steps to save must come from the run before it. */
        @Volatile var lastRunSteps: List<Recipes.Step> = emptyList()

        /** The previous request, so a "no, I meant…" can be paired with what it corrects. */
        @Volatile var lastRequest: String = ""

        /**
         * Tools that are *about* remembering, and so must never be remembered.
         *
         * The recipe store learned this months ago and the task-memory store
         * did not, because they are two stores with two exclusion lists. The
         * watching tools were missing here: asked to "watch me", the model
         * called cancel_watching, the run counted as a success, and the lesson
         * "when they say watch me, cancel watching" was filed as a shortcut —
         * a memory of the agent's own mistake, ready to be replayed the next
         * time someone tries to teach it anything.
         */
        val RECIPE_TOOLS = setOf(
            "recipe_save", "recipe_run", "recipe_list", "recipe_delete",
            "watch_me", "stop_watching", "cancel_watching",
        )

        /** Conservative classifier: only commands that map cleanly to the local
         * tool subset route on-device. Anything else goes cloud. No "timer" or
         * "start": the local catalog has no timer tool, and the 1B model mapped
         * them onto whatever it had and reported that done. */
        internal fun isSimpleLocalIntent(input: String): Boolean {
            val u = input.lowercase()
            // Compounds and URL-like targets exceed the 1B model's measured
            // competence (suite t03/t05/t08): those go cloud.
            if (Regex("\\b(and then|then|after that| and )\\b").containsMatchIn(u)) return false
            if (Regex("[a-z0-9-]+\\.(com|org|net|io|edu|gov)\\b").containsMatchIn(u)) return false
            if (u.contains("http")) return false
            return Regex(
                "\\b(flashlight|torch|wi-?fi|bluetooth|do not disturb|dnd|volume|brightness|" +
                    "airplane|alarm|battery|clipboard|note this|open|launch)\\b"
            ).containsMatchIn(u)
        }

        /** Hour and minute from a spoken alarm time: "7:30 pm", "7 30 am", "7". */
        internal fun alarmTime(u: String): Pair<Int, Int>? {
            val m = Regex("(\\d{1,2})(?:(?::|\\s+)(\\d{2}))?\\s*(am|pm)?\\b").find(u) ?: return null
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            if (m.groupValues[3] == "pm" && hour < 12) hour += 12
            if (m.groupValues[3] == "am" && hour == 12) hour = 0
            return hour to minute
        }

        /** How much two requests must overlap to count as the same job. */
        const val MATCH_THRESHOLD = 0.5

        /** A tool needs a real track record of failing before it gets named. */
        const val MIN_FAILURES_TO_WARN = 3

        /** How much of a tool result reaches logcat. A structured read's
         * header alone is 120 characters, so the old cap logged the sentence
         * describing the rows and none of the rows. */
        const val TOOL_LOG_CHARS = 2000

        /** Words that say nothing about what a request is for. */
        val STOPWORDS = setOf(
            // filler and grammar
            "the", "and", "for", "you", "your", "can", "will", "with", "that",
            "this", "then", "please", "what", "whats", "how", "hows", "why",
            "does", "did", "was", "are", "some", "get", "got", "let", "its",
            "have", "has", "just", "now", "one", "all", "any", "out", "about",
            "from", "into", "when", "where", "which", "there", "here", "again",
            "could", "would", "should", "much", "many", "doing", "going",
            // generic request verbs — they say nothing about the subject.
            // "open chrome" and "open amazon" are different jobs; without
            // dropping "open" they look half the same.
            "open", "launch", "start", "tell", "show", "give", "find", "check",
            "look", "make", "want", "need", "know", "like", "read", "say",
        )

        private const val TOOL_CATALOG = """
LEARNING BY BEING SHOWN — use when the user offers to demonstrate, or when a task
keeps failing and they could just show you:
- watch_me — start watching. ONLY when they are about to show you something.
- stop_watching {"name":"..."} — stop and save under that name. Without a name it
  reports what it saw and waits.
- cancel_watching — stop and throw it away.

Use exactly ONE of these per request, and never watch_me in the same turn as
stop_watching: "stop watching and call it X" means stop_watching {"name":"X"} and
nothing else. Only the screens they passed through are kept, never what was on
them and never what they typed. Say so if they ask.

DEVICE CONTROL (instant, ~99% reliable). Always say which way — {} means ON:
  wifi_toggle {on: true|false}, bluetooth_toggle {on: true|false},
  do_not_disturb {on: true|false}, flashlight_toggle {on: true|false}
  volume_set {percent: 0-100}
  media_play, media_next

APPS & NAVIGATION (use app_launch to just open, react_navigate to open AND interact):
  app_launch — open app (no interaction). params: {target}
  react_navigate — open app AND do things inside it (tap, type, scroll). params: {goal, appHint}
    It can work ANY app's screen — camera shutter, a form, a list — so "take a photo" or "record a
    video" is react_navigate in the camera, never "I can't do that".
  open_url — open a URL in browser. params: {url}

INFORMATION (fast, no UI needed):
  web_search — search internet, returns text results directly. params: {query}
  ambient_recall — search everything the phone has overheard (your long-term ambient memory). Use when the user refers to past conversations, things said earlier, "what did I/they say about…". params: {query, limit?}
  system_info — model, Android version, battery, AND whether Wi-Fi / Bluetooth / DND / dark mode /
    location are on, volume, free storage. Use it to ANSWER questions about the phone.
  device_info, battery_status, device_location

THE OWNER'S LAPTOP (Claude, with his notes, projects and memory — read-only, slow: 30-60 s):
  ask_claude {question} — for anything about his work, files, projects, notes or code, or a
    question that needs real depth. Pass his question in his words. Relay the answer plainly.

SETTINGS (opens the page directly — far more reliable than tapping through Settings):
  settings_open {page: wifi|bluetooth|display|sound|storage|about|location|battery|apps|
    notifications|accessibility|date|security|network|airplane|main}
    "dark mode" and brightness live on display; model number on about.

COMMUNICATION:
  sms_send — send SMS. params: {to: a PHONE NUMBER, message}. Given a name ("text mom"), call contacts_read first.
  sms_read — read inbox. params: {limit?}
  contacts_read — search contacts. params: {name?}

FILES & CLIPBOARD & CREATION:
  clipboard_write {text}, clipboard_read, note_create {text}, alarm_set {hour, minute?, label?}

SCREEN:
  read_text_on_screen — what is visible right now. Fast.
  read_screen_deep — scrolls the page and reads ALL of it. params: {maxScrolls?}
    Use this whenever the answer is in a LIST, FEED, ARTICLE, SEARCH RESULTS,
    PRICES, or anything below the fold. read_text_on_screen sees the header only.
  describe_screen, screenshot, notification_read

RECIPES (the user's saved routines — replay a whole sequence by name):
  recipe_run — run a saved routine. params: {name}
  recipe_save — save the PREVIOUS successful run under a name. params: {name}
  recipe_list — list saved routines
  recipe_delete — delete one. params: {name}
"""
    }
}
