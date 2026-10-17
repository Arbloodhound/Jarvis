package com.agent.ultra.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The tool dispatcher. Each tool returns a plain-text result string;
 * failures start with "Error:" (the brain's success heuristic keys on that).
 * Ported set from the proven Build 29 catalog — not all 79 legacy cases.
 */
class Tools(
    private val context: Context,
    private val controller: AgentController,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    var navigator: ReActNavigator? = null

    /** What the agent has learned about screens it has read before. Set by
     * Brain, which owns the database. */
    var screenMemory: com.agent.ultra.data.ScreenMemoryDao? = null

    /** Recipe store and the replay entry point. The runner lives in Brain —
     * replay needs the policy gate and the episode, which Tools does not own. */
    var recipes: Recipes? = null
    var recipeRunner: (suspend (String) -> String)? = null

    /** The tool running now (its name only), for the quiet-run line ([QuietStage]); null between tools. */
    @Volatile var running: String? = null
        private set

    suspend fun execute(tool: String, params: JSONObject): String {
        val outer = running   // a recipe step runs inside recipe_run
        running = tool
        try {
            return executeNow(tool, params)
        } finally {
            running = outer
        }
    }

    private suspend fun executeNow(tool: String, params: JSONObject): String {
        // Messages, contacts and location reach Android's own providers, so
        // the app-access list does not cover them — that list gates what the
        // accessibility service sees on screen, and reading the SMS database
        // never goes near a screen. On a phone taking real calls and texts,
        // the default has to be no.
        if (tool in PERSONAL_TOOLS && !com.agent.ultra.ui.UltraPrefs.allowPersonalData(context)) {
            return "Error: messages, contacts and location are switched off for this agent. " +
                "The user can turn them on in Settings under PERSONAL DATA. " +
                "Do not retry — tell them, and carry on with the rest of the task."
        }
        // A tracked secret may not appear in ANY tool's arguments.
        //
        // Found by the model itself: refused permission to text a one-time code
        // it had just read, it wrote the code to the CLIPBOARD, and when that
        // was read back empty it wrote the code to a NOTE FILE. Both succeeded.
        // The guard covered typing and SMS because those were the exits anyone
        // had thought of.
        //
        // So the rule is central and inverted. Every tool is checked, and the
        // exemptions are named — because a list of exits will always be shorter
        // than the list of ways out, and the one nobody wrote down is the one
        // that gets used.
        if (tool !in SECRET_MAY_PASS) {
            val carrying = params.keys().asSequence()
                .mapNotNull { params.opt(it)?.toString() }
                .firstNotNullOfOrNull { controller.outboundSecret(it) }
            if (carrying != null) {
                return "Error: that contains $carrying, which was on screen a moment ago. " +
                    "I will not put a code or a card number into another app, the clipboard, " +
                    "a file or a message. Ask the user to do it themselves if they meant to."
            }
        }

        return try {
            when (tool) {
                // ── Perception ───────────────────────────────────────
                "read_text_on_screen" -> {
                    val flat = controller.screenFlat()
                    when {
                        flat == PROTECTED -> PROTECTED_MSG
                        flat == "[]" || flat.isBlank() ->
                            withPictureText("", "Error: nothing could be read — " + controller.serviceProblem.ifBlank { "the screen was empty" })
                        else -> withPictureText(summarizeFlat(flat))
                    }
                }
                "read_screen_deep" -> {
                    // Measured on an Amazon results page: still gaining new
                    // content at scroll 10, so the default is not timid.
                    val maxScrolls = params.optInt("maxScrolls", 12).coerceIn(1, 30)
                    deepRead(maxScrolls)
                }
                "describe_screen" -> {
                    val flat = controller.screenFlat()
                    when {
                        flat == PROTECTED -> PROTECTED_MSG
                        flat == "[]" || flat.isBlank() ->
                            withPictureText("", "Error: nothing could be read — " + controller.serviceProblem.ifBlank { "the screen was empty" })
                        else -> "Current app: ${controller.activePackage()}\n" + withPictureText(summarizeFlat(flat))
                    }
                }
                "screen_ocr" -> {
                    if (controller.screenFlat() == PROTECTED) PROTECTED_MSG
                    else ScreenVision.unavailable()?.let { "Error: $it" }
                        ?: ScreenVision.readScreen(controller)?.ifBlank { "No text in the picture of the screen." }
                        ?: "Error: the screen could not be captured (a secure screen, or the accessibility service needs " +
                            "turning off and on again once to allow screenshots)."
                }
                "look_at_screen" -> {
                    val q = params.optString("question")
                    when {
                        controller.screenFlat() == PROTECTED -> PROTECTED_MSG
                        ScreenVision.unavailable() != null -> "Error: ${ScreenVision.unavailable()}"
                        !com.agent.ultra.local.VisionModel.ready(context) ->
                            "The vision model is not downloaded (Settings → SCREEN VISION), so here is the text read from " +
                                "the picture instead:\n" + (ScreenVision.readScreen(controller) ?: "(nothing could be read)")
                        else -> ScreenVision.look(controller, q)
                            ?: ("The vision model could not look just now; the text read from the picture:\n" +
                                (ScreenVision.readScreen(controller) ?: "(nothing could be read)"))
                    }
                }
                "screenshot" -> {
                    if (controller.takeScreenshot()) "Screenshot taken — saved to the device gallery"
                    else "Error: screenshot failed (accessibility service not running)"
                }
                "notification_read" -> controller.readNotifications(params.optInt("limit", 15))
                "global_action" -> {
                    val action = params.optString("action")
                    if (controller.globalAction(action)) "Done: $action"
                    else "Error: '$action' failed (accessibility service not running, or not one of home, back, " +
                        "recents, notifications, quick_settings, lock_screen)"
                }

                // ── Learning by being shown ──────────────────────────
                "watch_me" -> {
                    if (!controller.serviceRunning)
                        "Error: nothing could be watched — " + controller.serviceProblem
                    else {
                        if (!Demonstration.start())
                            "Already watching — ${Demonstration.soFar().size} steps so far. " +
                                "Carry on, then say \"stop watching\" and give it a name."
                        else
                        "Watching. Do the task on your phone now, then say \"stop watching\" " +
                            "and give it a name.\n\nI record which app and which control you " +
                            "touch — never what you type into it. If the task needs an amount " +
                            "or a message, I will ask for that each time rather than remember " +
                            "yours."
                    }
                }
                "stop_watching" -> {
                    if (Demonstration.tooSoonToStop)
                        "Still watching — you only just started, so the user has not had time " +
                            "to show you anything. Do NOT call this again. Tell them to do the " +
                            "task on the phone now and say \"stop watching\" when they are done."
                    else if (!Demonstration.isRecording)
                        "I was not watching anything. Do not start watching now — tell the " +
                            "user to say \"watch me\" when they are ready to show you."
                    else {
                        // Stop first, then read. Stopping is what commits the
                        // screen the user ended on, and that screen is usually
                        // the whole point of the demonstration — they arrive at
                        // History and say "stop watching". Reading the route
                        // before stopping judged a route one screen short of
                        // the one that was recorded, and threw away good
                        // demonstrations as "nothing was recorded".
                        val steps = Demonstration.stop()
                        val route = Demonstration.journey
                        val name = params.optString("name").trim()
                        when {
                            // The route is what actually works. Android reports
                            // taps only when an app chooses to, so the step list
                            // is a bonus rather than the substance.
                            // Deliberately gives the model nothing to DO.
                            //
                            // The first wording ended "Start watching first,
                            // then do the task" and the model obediently called
                            // watch_me, hit the same message, and called it
                            // again — stop, watch, stop, watch, until the
                            // budget ran out. An error that instructs an action
                            // which re-triggers the same error is a trap. This
                            // one asks it to speak to the user instead.
                            !ScreenJourney.worthKeeping(route) ->
                                "Nothing was recorded, so there is nothing to save. Do not " +
                                    "start watching again. Tell the user: say \"watch me\", " +
                                    "then do the task on the phone, then say \"stop watching\"."
                            name.isBlank() ->
                                "I followed you through " + ScreenJourney.describe(route) +
                                    ".\n\nSay \"stop watching and call it <name>\" to keep it."
                            else -> saveJourney(name, route, steps)
                        }
                    }
                }
                "cancel_watching" -> {
                    val was = Demonstration.isRecording
                    Demonstration.cancel()
                    if (was) "Stopped, and threw away everything I saw."
                    else "I was not watching anything."
                }

                // ── Apps & navigation ────────────────────────────────
                "app_launch" -> {
                    val target = params.optString("target")
                    com.agent.ultra.net.TorBrowserGuard.check(context, target, params.optBoolean("vpnOk"))?.let { return it }
                    val pkg = controller.findPackage(target)
                    when {
                        pkg == null -> "Error: no launchable app matching '$target'"
                        !com.agent.ultra.AgentAccessibilityService.agentMayUse(pkg) ->
                            "Error: '$target' is not on the user's allowed-apps list, so it " +
                                "cannot be opened or read. Do not retry. Tell the user which " +
                                "app it was; they can change the list in Settings."
                        controller.launchApp(pkg) -> "Launched $target ($pkg)"
                        else -> "Error: could not launch '$target'"
                    }
                }
                "open_url" -> {
                    val url = params.optString("url")
                    if (url.isBlank()) "Error: missing url"
                    else if (controller.openUrl(url)) "Opened $url"
                    else "Error: could not open $url"
                }
                "react_navigate" -> {
                    // Already judged this request wrong; do not go round again.
                    navigator?.stoppedOnDisagreement?.let { return it }

                    val goal = params.optString("goal")
                    // A blank goal does not stop the navigator: with no app named it falls back
                    // to whatever is on screen and drives it with nothing to aim at.
                    if (goal.isBlank()) return "Error: react_navigate needs a 'goal'"
                    val hint = params.optString("appHint")
                    com.agent.ultra.net.TorBrowserGuard.check(context, "$hint $goal", params.optBoolean("vpnOk"))?.let { return it }
                    val nav = navigator ?: return "Error: navigator not wired"
                    // Takeover screen while the cloud navigator drives the app (Stop in the corner).
                    val svc = com.agent.ultra.AgentAccessibilityService.getInstance()
                    svc?.showTakeover()
                    try { nav.run(goal, hint) } finally { svc?.hideTakeover() }
                }

                // ── Information ──────────────────────────────────────
                "web_search" -> webSearch(params.optString("query"), params.optBoolean("skipResearch"))
                "ambient_recall" -> {
                    // Meaning + keywords + recency + "yesterday"-style windows (keyword-only
                    // until the embedder is downloaded). Same "[time] line" format as before, best
                    // match first; a hit from another source (e.g. notes) is labelled with it.
                    val hits = com.agent.ultra.listen.MemoryRecall.relevant(
                        context, params.optString("query"), System.currentTimeMillis(),
                        params.optInt("limit", 8).coerceIn(1, 20),
                    )
                    if (hits.isEmpty()) "Nothing overheard matching that."
                    else hits.joinToString("\n") { h ->
                        val t = java.text.SimpleDateFormat("MMM d h:mm a", java.util.Locale.US)
                            .format(java.util.Date(h.ts))
                        val src = if (h.source == com.agent.ultra.listen.MemoryRecall.SOURCE_HEARD) "" else "(${h.source}) "
                        "[$t] $src${h.text}"
                    }
                }
                "device_info" -> controller.deviceInfo()
                "battery_status" -> controller.batteryStatus()
                "system_info" -> controller.deviceInfo() + " | " + controller.batteryStatus() +
                    " | " + controller.settingsStatus()
                "ask_claude" -> askClaude(params.optString("question"))
                "settings_open" -> {
                    val page = params.optString("page")
                    when {
                        page.lowercase().trim() !in controller.SETTINGS_PAGES ->
                            "Error: unknown settings page '$page'. Pages: ${controller.SETTINGS_PAGES.keys.joinToString()}"
                        !com.agent.ultra.AgentAccessibilityService.agentMayUse("com.android.settings") ->
                            "Error: Settings is not on the user's allowed-apps list. Tell the user; do not retry."
                        controller.openSettings(page) -> "Opened $page settings"
                        else -> "Error: could not open $page settings"
                    }
                }

                // ── Shopping & autonomous browsing ───────────────────
                "shop_search" -> {
                    val q = params.optString("query")
                    // Only the two mechanical sorts exist; anything else falls back to price rather
                    // than discarding the fetched pages (compareTable refuses an unknown sort).
                    val sort = if (params.optString("sort").lowercase() == "year") "year" else com.agent.ultra.research.ShoppingRun.SORT_PRICE
                    if (q.isBlank()) "Error: shop_search needs a 'query'" else shopSearch(q, sort)
                }
                "web_browse" -> {
                    val task = params.optString("task")
                    if (task.isBlank()) "Error: web_browse needs a 'task'"
                    else webBrowse(task)
                }
                "shop_checkout_prep" -> checkoutPrep(params)
                "forget" -> forget(params.optString("subject"))
                "connector" -> connector(params)
                // Starts Tor (Orbot) for the onion route; never turns onion research on.
                "tor_connect" -> when {
                    NetPolicy.offlineOnly(context) -> "Offline-only mode is on, so Tor stays off. Nothing was started."
                    !com.agent.ultra.ui.UltraPrefs.onionResearch(context) ->
                        "Onion research via Tor is off in Settings, so Tor stays off — the owner can turn it on there. Nothing was started."
                    else -> com.agent.ultra.net.TorConnect.describe(com.agent.ultra.net.TorConnect.connect(context))
                }

                // ── Device control ───────────────────────────────────
                "flashlight_toggle" -> {
                    val on = ToolArgs.bool(params, "on")
                    if (on == null) "Error: flashlight_toggle needs \"on\": true or false"
                    else if (controller.setFlashlight(on)) "Flashlight ${if (on) "on" else "off"}"
                    else "Error: flashlight toggle failed"
                }
                "wifi_toggle" -> {
                    val on = ToolArgs.bool(params, "on")
                    if (on == null) "Error: wifi_toggle needs \"on\": true or false"
                    else if (controller.setWifi(on)) "Wi-Fi ${if (on) "on" else "off"}"
                    else "Error: couldn't switch Wi-Fi directly (Android doesn't allow apps to); opened the Wi-Fi panel for the user"
                }
                "bluetooth_toggle" -> {
                    val on = ToolArgs.bool(params, "on")
                    if (on == null) "Error: bluetooth_toggle needs \"on\": true or false"
                    else if (controller.setBluetooth(on)) "Bluetooth ${if (on) "on" else "off"}"
                    else "Error: couldn't switch Bluetooth directly (Android doesn't allow apps to); opened Bluetooth settings for the user"
                }
                "do_not_disturb" -> {
                    val on = ToolArgs.bool(params, "on")
                    if (on == null) "Error: do_not_disturb needs \"on\": true or false"
                    else if (controller.setDoNotDisturb(on)) "Do Not Disturb ${if (on) "on" else "off"}"
                    else "Error: DND toggle failed (needs notification policy access)"
                }
                "volume_set" -> {
                    val pct = ToolArgs.int(params, "percent")?.coerceIn(0, 100)
                    if (pct == null) "Error: volume_set needs \"percent\" 0-100"
                    else if (controller.setVolume(android.media.AudioManager.STREAM_MUSIC, pct)) "Media volume set to $pct%"
                    else "Error: volume set failed"
                }

                // ── Communication ────────────────────────────────────
                "sms_send" -> {
                    val to = params.optString("to")
                    val msg = params.optString("message")
                    when {
                        to.isBlank() -> "Error: sms_send needs a 'to' phone number — ask the user or use contacts_read first"
                        msg.isBlank() -> "Error: sms_send needs a 'message'"
                        // The classic one: read the code off the screen, text it
                        // somewhere. A message leaves the phone entirely, so
                        // "back to the same app" cannot apply and any secret in
                        // it is a secret leaving.
                        secretInOutbound(msg) != null ->
                            "Error: that message contains ${secretInOutbound(msg)} that was on " +
                                "screen a moment ago. I will not send a code or a card number in " +
                                "a text. Ask the user to send it themselves if they meant to."
                        controller.sendSms(to, msg) -> "SMS sent to $to"
                        else -> "Error: SMS send failed"
                    }
                }
                "sms_read" -> controller.readSms(params.optInt("limit", 10))
                "contacts_read" -> controller.readContacts(params.optString("name"))

                // ── Location / clipboard / media / alarms / notes ────
                "device_location" -> controller.lastKnownLocation()
                "clipboard_write" -> {
                    val text = params.optString("text")
                    if (text.isBlank()) "Error: missing text"
                    else if (controller.clipboardWrite(text)) "Copied to clipboard"
                    else "Error: clipboard write failed"
                }
                "clipboard_read" -> controller.clipboardRead()
                "media_play" -> if (controller.mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) "Play/pause toggled" else "Error: media key failed"
                "media_next" -> if (controller.mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)) "Skipped to next track" else "Error: media key failed"
                "alarm_set" -> {
                    val hour = params.optInt("hour", -1)
                    val minute = params.optInt("minute", 0)
                    if (hour !in 0..23 || minute !in 0..59) "Error: alarm_set needs hour (0-23) and optional minute (0-59)"
                    else if (controller.setAlarm(hour, minute, params.optString("label", "Jarvis alarm"))) "Alarm set for %02d:%02d".format(hour, minute)
                    else "Error: alarm failed"
                }
                "note_create" -> {
                    val text = params.optString("text")
                    if (text.isBlank()) "Error: missing text"
                    else controller.createNote(text)
                }

                // ── Recipes — named, replayable routines ─────────────
                "recipe_save" -> {
                    val r = recipes ?: return "Error: recipes unavailable"
                    val name = params.optString("name")
                    if (name.isBlank()) "Error: missing name"
                    else r.save(name, Brain.lastRunSteps)
                }
                "recipe_run" -> {
                    val run = recipeRunner ?: return "Error: recipes unavailable"
                    val name = params.optString("name")
                    if (name.isBlank()) "Error: missing name" else run(name)
                }
                "recipe_list" -> recipes?.list() ?: "Error: recipes unavailable"
                "recipe_delete" -> {
                    val r = recipes ?: return "Error: recipes unavailable"
                    val name = params.optString("name")
                    if (name.isBlank()) "Error: missing name" else r.delete(name)
                }

                else -> "Error: unknown tool '$tool'"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // Stop / a timeout is not a tool error
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /**
     * Flatten the a11y node list into the readable text the brain consumes.
     *
     * There used to be a hard 40-label cap here. On any dense page — a search
     * result list, a feed, an article — 40 labels is the header and the
     * sign-in prompt, and the agent would confidently report that the content
     * "is not available". The limit is now a character budget, so a sparse
     * screen costs nothing and a rich one is actually readable.
     */
    /**
     * [tree] as it is, or with the words read from a picture of the screen added when the tree is
     * too thin to rely on ([OcrLayout.sparse]: games, maps, canvas and many web pages). [ifNothing]
     * is the answer when neither has anything.
     */
    private suspend fun withPictureText(tree: String, ifNothing: String = tree): String {
        if (!OcrLayout.sparse(tree) || ScreenVision.unavailable() != null) return ifNothing
        val seen = ScreenVision.readScreen(controller)?.takeIf { it.isNotBlank() } ?: return ifNothing
        val head = if (tree.isBlank() || tree == "Screen has no readable text") "" else tree + "\n"
        return head + "Text read from a picture of the screen (OCR — not tappable by number):\n" + seen
    }

    private fun summarizeFlat(flat: String, budget: Int = VIEWPORT_BUDGET): String {
        return try {
            val labels = labelsOf(flat)
            if (labels.isEmpty()) return "Screen has no readable text"
            val out = StringBuilder()
            var used = 0
            var shown = 0
            for (l in labels) {
                if (used + l.length + 1 > budget) break
                out.append(l).append('\n')
                used += l.length + 1
                shown++
            }
            if (shown < labels.size) {
                out.append("… ${labels.size - shown} more items on this screen; ")
                    .append("use read_screen_deep to read the whole page.")
            }
            out.toString().trimEnd()
        } catch (e: Exception) {
            "Error: could not parse screen: ${e.message}"
        }
    }

    /** Distinct, ordered, non-empty labels from a flat node dump. */
    private fun labelsOf(flat: String): List<String> = try {
        val arr = org.json.JSONArray(flat)
        val seen = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            val label = n.optString("t").ifBlank { n.optString("d") }.trim()
            if (label.isNotBlank()) seen.add(label.take(LABEL_CHARS))
        }
        seen.toList()
    } catch (_: Exception) { emptyList() }

    /**
     * Keep what was demonstrated, under a name the user chose.
     *
     * Stored in the same table as spoken recipes so there is one place to look
     * and one way to run them, with a marker saying this one was learned by
     * watching rather than assembled from tools.
     */
    /**
     * Keep the route, and whatever taps happened to be visible.
     *
     * The route is the substance: it is built from screens, which can always be
     * read. The tap list is kept when it exists but is never relied on, because
     * Android reports a tap only when the app chooses to — measured, four
     * deliberate taps produced one event.
     */
    private suspend fun saveJourney(
        name: String,
        route: List<ScreenJourney.Waypoint>,
        steps: List<Demonstration.Step>,
    ): String {
        val store = recipes ?: return "Error: routines are unavailable"
        return try {
            val saved = store.saveJourney(name, ScreenJourney.toJson(route), Demonstration.toJson(steps))
            if (saved.startsWith("Error:")) return saved
            "Saved as \"$saved\" — " + ScreenJourney.describe(route) + ".\n\n" +
                "I remember the screens you passed through, not what was on them, and " +
                "nothing you typed."
        } catch (e: Exception) {
            "Error: could not save that routine (${e.message})"
        }
    }

    /**
     * A secret from any app, about to leave the phone.
     *
     * Unlike typing, there is no "back where it came from" here: a message goes
     * outward, so every tracked value counts regardless of which app it was
     * read in.
     */
    private fun secretInOutbound(text: String): String? = controller.outboundSecret(text)

    /** The template this screen used last time, or "" if it is new to us. */
    private suspend fun rememberedTemplate(fp: ScreenSignature.Fingerprint?): String {
        val dao = screenMemory ?: return ""
        if (fp == null || !fp.known) return ""
        return try { dao.get(fp.key)?.template ?: "" } catch (_: Exception) { "" }
    }

    /**
     * Record what this screen turned out to be.
     *
     * Structure only — the template signature and the field names, never a
     * value. A memory row says "this screen's records look like this and carry
     * a price and a rating"; it never says what the price was.
     *
     * Never fails a read. A screen that cannot be remembered is simply read
     * from scratch next time, which is exactly what happened before any of
     * this existed.
     */
    private suspend fun learnScreen(
        fp: ScreenSignature.Fingerprint?,
        pkg: String,
        rows: List<ScreenStructure.Item>,
    ) {
        val dao = screenMemory ?: return
        if (fp == null || !fp.known || rows.size < 3) return
        val template = ScreenStructure.lastTemplate
        if (template.isBlank()) return
        val controls = ScreenControls.of(ScreenStructure.parse(controller.screenTree()))
        try {
            val fields = rows.asSequence()
                .flatMap { ScreenStructure.fields(it).asSequence() }
                .mapNotNull { it.name }
                .filter { it != "title" }
                .distinct().sorted().take(20).joinToString(",")
            val prior = dao.get(fp.key)
            dao.put(
                com.agent.ultra.data.ScreenMemoryEntity(
                    screenKey = fp.key,
                    pkg = pkg,
                    template = template,
                    recordCount = rows.size,
                    fieldsCsv = fields,
                    seenCount = (prior?.seenCount ?: 0) + 1,
                    lastSeen = System.currentTimeMillis(),
                    confidence = fp.confidence.name,
                    // Keep what is already known if this read found nothing —
                    // a control list is not wrong just because the screen was
                    // scrolled away from it.
                    controlsJson = if (controls.isNotEmpty()) ScreenControls.toJson(controls)
                        else prior?.controlsJson.orEmpty(),
                )
            )
            dao.trimTo(SCREEN_MEMORY_LIMIT)
            android.util.Log.i(
                "UltraPerceive",
                "learned screen ${fp.key} (${fp.confidence}) ${rows.size} records, fields=[$fields], ${controls.size} controls",
            )
        } catch (e: Exception) {
            android.util.Log.w("UltraPerceive", "could not record screen: ${e.message}")
        }
    }

    /**
     * Put the page back where the read found it.
     *
     * Reading is not supposed to move anything. Without this, a deep read
     * leaves the screen wherever it stopped, so the next tool call sees a
     * different page than the one the model was told about — it taps the
     * third row and hits whatever scrolled into that spot.
     *
     * Scrolls back the same number of times rather than jumping to the top,
     * because the page was not necessarily at the top when the read began.
     * Best-effort: a failed restore is logged and never fails a read that
     * already succeeded.
     */
    private suspend fun restoreScroll(scrolls: Int) {
        if (scrolls <= 0) return
        var undone = 0
        try {
            while (undone < scrolls) {
                // Two ways up. Measured on a store page: after reading to the
                // bottom, the node that scrolled forward refused
                // ACTION_SCROLL_BACKWARD and the page stayed where it was —
                // "restored 0/7". The plain gesture works there, so a refusal
                // from one is not the end of the attempt.
                val moved = controller.scrollDeep("up") || controller.scroll("up")
                if (!moved) break
                undone++
                kotlinx.coroutines.delay(RESTORE_SETTLE_MS)
            }
        } catch (e: Exception) {
            android.util.Log.w("UltraPerceive", "deep read: scroll restore failed: ${e.message}")
        }
        if (undone < scrolls) {
            android.util.Log.w(
                "UltraPerceive",
                "deep read: restored only $undone/$scrolls scrolls — page left part-way down",
            )
        } else {
            android.util.Log.i("UltraPerceive", "deep read: restored $undone/$scrolls scrolls")
        }
    }

    /**
     * Read a whole scrollable page, not just the viewport.
     *
     * Scrolls the largest container forward, re-reads, and accumulates labels
     * in order, stopping as soon as a scroll produces nothing new. Order is
     * preserved and duplicates dropped, so overlapping reads across scrolls
     * come out as one clean document.
     */
    private suspend fun deepRead(maxScrolls: Int): String {
        val seen = LinkedHashSet<String>()                       // flat fallback
        val items = LinkedHashMap<String, ScreenStructure.Item>() // structured rows
        val first = controller.screenFlat()
        if (first == PROTECTED) return PROTECTED_MSG
        if (first == "[]" || first.isBlank()) {
            return "Error: nothing could be read — " + controller.serviceProblem.ifBlank { "the screen was empty" }
        }
        // What this screen looked like last time, if it has been read before.
        // Looked up once for the whole read rather than per scroll: scrolling
        // does not change which screen you are on.
        val pkg = controller.activePackage()
        var recalled = ""
        var fingerprint: ScreenSignature.Fingerprint? = null

        suspend fun absorb(flat: String): Int {
            val before = seen.size + items.size
            controller.noteScreenSecrets(flat)
            seen += labelsOf(flat)
            val nodes = ScreenStructure.parse(controller.screenTree())
            if (fingerprint == null && nodes.isNotEmpty()) {
                fingerprint = ScreenSignature.of(pkg, nodes)
                recalled = rememberedTemplate(fingerprint)
            }
            for (it in ScreenStructure.items(nodes, knownTemplate = recalled)) {
                items.putIfAbsent(it.signature, it)
            }
            return seen.size + items.size - before
        }
        absorb(first)

        var scrolls = 0
        var stoppedBecause = "reached the end of the page"
        while (scrolls < maxScrolls) {
            if (!controller.scrollDeep("down")) {
                stoppedBecause = if (scrolls == 0) "this screen does not scroll" else "reached the end of the page"
                break
            }
            scrolls++
            kotlinx.coroutines.delay(SCROLL_SETTLE_MS)
            val gained = absorb(controller.screenFlat())
            android.util.Log.i("UltraPerceive", "deep scroll $scrolls: +$gained new (labels ${seen.size}, items ${items.size})")
            if (gained == 0) break
            if (seen.size >= MAX_DEEP_LABELS) { stoppedBecause = "hit the ${MAX_DEEP_LABELS}-item limit"; break }
        }
        if (scrolls >= maxScrolls) stoppedBecause = "hit the $maxScrolls-scroll limit"

        restoreScroll(scrolls)
        learnScreen(fingerprint, pkg, items.values.toList())

        // Structured when the screen genuinely has a repeating list; flat when
        // it does not. Inventing groups where there are none is how a wrong
        // pairing gets stated confidently.
        if (items.size >= 3) {
            val rows = items.values.sortedBy { it.top }
            val (body, shown) = ScreenStructure.render(rows, DEEP_BUDGET)
            android.util.Log.i("UltraPerceive", "deep read: STRUCTURED ${rows.size} items, $scrolls scrolls")
            val omitted = if (shown < rows.size) "\n… ${rows.size - shown} more items not shown (output limit)." else ""
            return "Read ${rows.size} list items across ${scrolls + 1} screen(s) — $stoppedBecause.\n" +
                "Each numbered item's lines belong together; they come from the same element on the page.\n\n" +
                body + omitted
        }

        val all = seen.toList()
        val out = StringBuilder()
        var used = 0
        var shown = 0
        for (l in all) {
            if (used + l.length + 1 > DEEP_BUDGET) break
            out.append(l).append('\n')
            used += l.length + 1
            shown++
        }
        android.util.Log.i("UltraPerceive", "deep read: FLAT ${all.size} labels, $scrolls scrolls")
        val footer = if (shown < all.size) "\n… ${all.size - shown} more items not shown (output limit)." else ""
        return "Read ${all.size} items across ${scrolls + 1} screen(s) — $stoppedBecause. " +
            "No repeating list structure on this screen, so these are in page order.\n" +
            out.toString().trimEnd() + footer
    }

    /** Web search: DDG instant-answer JSON first, Wikipedia second, HTML
     * scrape last (the scrape returned page boilerplate in this network
     * environment — proven in the M5 suite). */
    /**
     * Ask Claude on the owner's laptop, through the USB cable (laptop: vault/runtime/bridge/
     * ultra_bridge.py; `adb reverse tcp:8787 tcp:8787`). Claude there reads files and notes,
     * read-only, with the laptop's Northstar memory. The token is a file adb put in this app's
     * external dir; without it, or without the cable, this says so plainly.
     */
    private suspend fun askClaude(question: String): String = withContext(Dispatchers.IO) {
        if (question.isBlank()) return@withContext "Error: ask_claude needs a 'question'"
        val token = try {
            java.io.File(context.getExternalFilesDir(null), "ultra_bridge_token").readText().trim()
        } catch (_: Exception) { "" }
        if (token.isBlank()) return@withContext "Error: the laptop bridge is not set up on this phone (no token)."
        try {
            val c = java.net.URL("http://127.0.0.1:8787/ask").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 3000
            c.readTimeout = 240_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Ultra-Token", token)
            c.outputStream.use { it.write(JSONObject().put("question", question.take(2000)).toString().toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) return@withContext "Error: laptop bridge answered $code: ${body.take(120)}"
            val o = JSONObject(body)
            val answer = o.optString("answer")
            if (o.optBoolean("error") || answer.isBlank()) "Error: Claude on the laptop could not answer."
            else "Claude (on the laptop) says: $answer"
        } catch (e: java.net.ConnectException) {
            "Error: can't reach the laptop — it's only reachable over the USB cable with the bridge running."
        } catch (e: Exception) {
            "Error: laptop bridge failed: ${e.message?.take(100)}"
        }
    }

    private fun isOnline(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) { false }

    private suspend fun webSearch(query: String, skipResearch: Boolean = false): String = withContext(Dispatchers.IO) {
        // Offline only (Settings): no web at all, before anything else is checked.
        if (!NetPolicy.webAllowed(NetPolicy.offlineOnly(context))) return@withContext NetPolicy.OFFLINE_RESULT
        if (query.isBlank()) return@withContext "Error: missing query"
        // Grounding is a tool, not a leash: offline, tell the model to use its own knowledge
        // rather than stalling. (See system-prompt rules 13-15.)
        if (!isOnline()) return@withContext "OFFLINE: no network. " +
            "Answer from your own knowledge; do not treat this as the fact being unavailable."
        // Several targeted searches with the best pages read (WebResearch); the quick sources if it finds nothing.
        // [skipResearch]: the caller already ran the research and it found nothing.
        if (!skipResearch) try {
            WebResearchRun.run(query, ::readPageOnScreen, onion = OnionAccess.of(context), dir = context.filesDir)?.let { return@withContext it }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e   // Stop must reach the caller
        } catch (e: Exception) { android.util.Log.w("UltraWeb", "research failed: ${e.message}") }
        instantAnswer(query)?.let { return@withContext it }
        wikipedia(query)?.let { return@withContext it }
        scrapeDdg(query)
    }

    /**
     * [url] opened in the browser on screen, under the takeover overlay, and its text read once the
     * page's scripts have run (the tree, plus OCR when the tree is thin); then Back, to where the
     * person was. Null when the screen is off or locked, the service is not running, or nothing
     * could be read. For [WebResearchRun] when plain HTTP gets no text from a page.
     */
    suspend fun readPageOnScreen(url: String): String? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (!controller.serviceRunning || pm?.isInteractive != true || km?.isKeyguardLocked == true) return null
        if (!withContext(Dispatchers.Main) { controller.openUrl(url) }) return null
        val svc = com.agent.ultra.AgentAccessibilityService.getInstance()
        svc?.showTakeover()
        return try {
            kotlinx.coroutines.delay(4500)
            val flat = controller.screenFlat()
            if (flat == PROTECTED) return null
            val tree = labelsOf(flat).filter { it.length > 2 }.joinToString("\n")
            val text = if (OcrLayout.sparse(tree, 200)) listOf(tree, ScreenVision.readScreen(controller).orEmpty())
                .filter { it.isNotBlank() }.joinToString("\n") else tree
            controller.noteScreenSecrets(text)
            android.util.Log.i("UltraWeb", "read on screen ${WebResearch.domainOf(url)}: ${text.length} chars")
            text.takeIf { it.isNotBlank() }
        } finally {
            svc?.hideTakeover()
            // Even when cancelled (Stop, or the 15 s limit): the browser must not be left in front.
            withContext(kotlinx.coroutines.NonCancellable) { controller.globalAction("back") }
        }
    }

    private suspend fun instantAnswer(query: String): String? {
        return try {
        val url = "https://api.duckduckgo.com/?q=" + URLEncoder.encode(query, "UTF-8") +
            "&format=json&no_html=1&skip_disambig=1"
        val req = Request.Builder().url(url)
            .header("User-Agent", "AgentUltra/2.0").build()
        val call = http.newCall(req)
        com.agent.ultra.net.cancelOnStop(call) { call.execute().use { resp ->
            if (!resp.isSuccessful) return@cancelOnStop null
            val j = JSONObject(resp.body?.string() ?: return@cancelOnStop null)
            val out = mutableListOf<String>()
            j.optString("Answer").takeIf { it.isNotBlank() }?.let { out += "Answer: $it" }
            j.optString("AbstractText").takeIf { it.isNotBlank() }?.let { abs ->
                out += abs
                j.optString("AbstractURL").takeIf { u -> u.isNotBlank() }?.let { out += "Source: $it" }
            }
            val topics = j.optJSONArray("RelatedTopics")
            if (topics != null) {
                var n = 0
                for (i in 0 until topics.length()) {
                    val t = topics.optJSONObject(i) ?: continue
                    val text = t.optString("Text")
                    if (text.isNotBlank()) { out += "• $text"; if (++n >= 4) break }
                }
            }
            if (out.isEmpty()) null
            else "Search results for \"$query\":\n" + out.joinToString("\n")
        } }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e   // Stop, not "no answer"
        } catch (_: Exception) { null }
    }

    private suspend fun wikipedia(query: String): String? {
        return try {
        val searchUrl = "https://en.wikipedia.org/w/api.php?action=opensearch&limit=3&format=json&search=" +
            URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder().url(searchUrl)
            .header("User-Agent", "AgentUltra/2.0").build()
        val call = http.newCall(req)
        val title = com.agent.ultra.net.cancelOnStop(call) { call.execute().use { resp ->
            if (!resp.isSuccessful) return@cancelOnStop null
            val arr = org.json.JSONArray(resp.body?.string() ?: return@cancelOnStop null)
            arr.optJSONArray(1)?.optString(0)
        } }
        if (title.isNullOrBlank()) return null
        val sumUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/" +
            URLEncoder.encode(title, "UTF-8")
        val req2 = Request.Builder().url(sumUrl)
            .header("User-Agent", "AgentUltra/2.0").build()
        val call2 = http.newCall(req2)
        com.agent.ultra.net.cancelOnStop(call2) { call2.execute().use { resp2 ->
            if (!resp2.isSuccessful) return@cancelOnStop null
            val j = JSONObject(resp2.body?.string() ?: return@cancelOnStop null)
            val extract = j.optString("extract")
            if (extract.isBlank()) null
            else "Search results for \"$query\":\n$extract\nSource: en.wikipedia.org/wiki/${title.replace(" ", "_")}"
        } }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e   // Stop, not "no answer"
        } catch (_: Exception) { null }
    }

    /** DuckDuckGo HTML scrape — last resort, ported from the proven implementation. */
    private suspend fun scrapeDdg(query: String): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext "Error: missing query"
        try {
            val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            val call = http.newCall(req)
            com.agent.ultra.net.cancelOnStop(call) { call.execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@cancelOnStop "Error: search HTTP ${resp.code}"
                val results = extractResults(html)
                if (results.isNotEmpty())
                    "Search results for \"$query\":\n" + results.joinToString("\n")
                else {
                    val textOnly = html
                        .replace(Regex("<script[\\s\\S]*?</script>"), "")
                        .replace(Regex("<style[\\s\\S]*?</style>"), "")
                        .replace(Regex("<[^>]*>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim().take(800)
                    if (textOnly.length > 50) "Search results for \"$query\" (raw):\n$textOnly"
                    else "Error: no results extracted"
                }
            } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // Stop must reach the caller, not come back as "Error: search failed"
        } catch (e: Exception) {
            "Error: search failed: ${e.message}"
        }
    }

    private fun extractResults(html: String): List<String> {
        val results = mutableListOf<String>()
        val p1 = Regex("<a class=\"result__a\"[^>]*>([\\s\\S]*?)</a>[\\s\\S]*?<a class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>")
        for (m in p1.findAll(html)) {
            if (results.size >= 6) break
            val t = m.groupValues[1].replace(Regex("<[^>]*>"), "").trim()
            val s = m.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
            if (t.isNotEmpty() && s.isNotEmpty()) results.add("• $t: $s")
        }
        if (results.isEmpty()) {
            val p2 = Regex("<a class=\"result__a\"[^>]*>([\\s\\S]*?)</a>")
            for (m in p2.findAll(html)) {
                if (results.size >= 6) break
                val t = m.groupValues[1].replace(Regex("<[^>]*>"), "").trim()
                if (t.isNotEmpty()) results.add("• $t")
            }
        }
        return results
    }

    /**
     * "find me / cheapest / compare / reviews of …": candidate pages read on the ordinary web path,
     * one row per seller-offer, seller reputation merged and ranked, laid out as a sortable table
     * ([com.agent.ultra.research.ShoppingRun]). Onion marketplace rows are the one carve-out that is
     * excluded (inside ShoppingRun). No pick, no buy language.
     */
    private suspend fun shopSearch(query: String, sort: String): String = withContext(Dispatchers.IO) {
        if (!NetPolicy.webAllowed(NetPolicy.offlineOnly(context))) return@withContext NetPolicy.OFFLINE_RESULT
        if (!isOnline()) return@withContext "OFFLINE: no network. Tell the user shopping needs a connection; " +
            "do not treat this as the product being unavailable."
        try {
            com.agent.ultra.research.ShoppingRun.run(query, onion = OnionAccess.of(context), sort = sort)
                ?: "No shopping listings found for \"$query\"."
        } catch (e: kotlinx.coroutines.CancellationException) { throw e   // Stop must reach the caller
        } catch (e: Exception) { android.util.Log.w("UltraShop", "shop_search failed: ${e.message}"); "Error: shopping search failed: ${e.message}" }
    }

    /**
     * "browse …" / "research deeply …": autonomous browsing within hard bounds
     * ([com.agent.ultra.research.BrowseRun]). The onion leg runs only when onion research is opted in
     * and Tor is reachable; blocked onions are never fetched; there is no clearnet fallback.
     */
    private suspend fun webBrowse(task: String): String = withContext(Dispatchers.IO) {
        if (!NetPolicy.webAllowed(NetPolicy.offlineOnly(context))) return@withContext NetPolicy.OFFLINE_RESULT
        if (!isOnline()) return@withContext "OFFLINE: no network. Answer from your own knowledge; " +
            "do not treat this as the fact being unavailable."
        try {
            com.agent.ultra.research.BrowseRun.run(task, onion = OnionAccess.of(context))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e   // Stop must reach the caller
        } catch (e: Exception) { android.util.Log.w("UltraBrowse", "web_browse failed: ${e.message}"); "Error: browse failed: ${e.message}" }
    }

    /**
     * Gated checkout PREPARATION only ([com.agent.ultra.research.ShoppingCheckout]): it lays out the
     * order and, when given, the DISPLAY-ONLY crypto details {amount, address}. It never buys, pays,
     * places an order, or touches keys — there is no purchase path here. The real commitment (a
     * pay/checkout tap) still goes through the on-screen confirm card (ActionGate).
     */
    private fun checkoutPrep(params: JSONObject): String {
        val title = params.optString("title")
        if (title.isBlank()) return "Error: shop_checkout_prep needs a 'title'"
        val amount = params.optDouble("price", 0.0)
        val currency = params.optString("currency").ifBlank { "USD" }
        val qty = params.optInt("qty", 1)
        val price = if (amount > 0) WebResearch.Price(amount, currency, "%s %.2f".format(currency, amount)) else null
        val row = com.agent.ultra.research.ShoppingListings.Row(
            title, price, null, params.optString("seller"), "", "", "",
            params.optString("link"), params.optString("sourceUrl"), "",
        )
        val plan = com.agent.ultra.research.ShoppingCheckout.prepareOrder(row, qty)
        return buildString {
            append("Order prepared — NOT placed, nothing was bought:\n")
            append("- ").append(plan.title).append('\n')
            if (plan.seller.isNotBlank()) append("- Seller: ").append(plan.seller).append('\n')
            if (plan.unitPrice > 0) append("- ${plan.qty} × ${plan.currency} ${"%.2f".format(plan.unitPrice)} = ${plan.currency} ${"%.2f".format(plan.total)}\n")
            else append("- Quantity: ${plan.qty}\n")
            if (plan.link.isNotBlank()) append("- Link: ").append(plan.link).append('\n')
            val addr = params.optString("cryptoAddress")
            if (addr.isNotBlank()) {
                // Never guess the crypto amount from the fiat total — the units differ. Show the
                // address, and the amount only when it was actually given, for the owner to verify.
                val camt = params.optDouble("cryptoAmount", 0.0)
                val cp = com.agent.ultra.research.ShoppingCheckout.cryptoPrep(camt, addr)
                append("- Crypto (display only, for you to verify independently — I never touch keys or send anything): ")
                append(if (camt > 0) "${cp.amount} (in the coin's own units) to ${cp.address}\n"
                    else "pay to ${cp.address} (amount not given — check it yourself)\n")
            }
            append("I have not paid, sent anything, or placed any order; this only lays out what a purchase would be. ")
            append("Completing it is your own action, and any pay/checkout tap asks you first. ")
            append(com.agent.ultra.research.ShoppingCheckout.DIRECTION_QUESTION)
        }
    }

    /**
     * "Forget X" (harness Forget, HANDOFF_V5 §3): plan every copy, show the plan on the owner's
     * confirm card, delete only after their tap, then search again and report. The card's yes is
     * the only thing that mints the confirmation; the model can't.
     */
    private suspend fun forget(subject: String): String {
        if (subject.isBlank()) return "Error: forget needs a 'subject'"
        val notes = com.agent.ultra.listen.MemoryNotes.get(context)
        val f = com.agent.ultra.harness.Forget(listOf(
            com.agent.ultra.harness.ClaimsForgetSource(com.agent.ultra.data.UltraDatabase.get(context).claimForget()),
            com.agent.ultra.harness.NotesForgetSource(
                { q -> withContext(Dispatchers.IO) { notes.list(null, q, 200).map { it.id to it.text } } },
                { id -> withContext(Dispatchers.IO) { notes.delete(id) } }),
        ))
        val plan = f.plan(subject)
        if (plan.items.isEmpty()) return plan.summary()
        val ok = ActionGate.approve("forget", subject, "Jarvis", "forget",
            title = "Forget \u201C${subject.take(60)}\u201D? (${plan.items.size} item${if (plan.items.size == 1) "" else "s"})",
            body = plan.summary().take(600))
        if (!ok) return "Nothing was forgotten: the owner didn't confirm.\n" + plan.summary()
        return f.execute(f.userConfirmed(plan.id)).summary()
    }

    /**
     * The connectors (Gmail, Calendar, Drive, Docs — HANDOFF_V5 §1): status first, then the
     * registry's approval rule; a write or send the grants don't cover goes to the owner's confirm
     * card, and only their tap mints the token the call needs. Codes in results are scrubbed.
     */
    private suspend fun connector(params: JSONObject): String {
        val reg = com.agent.ultra.harness.connectors.AppConnectors.registry(context)
        val id = params.optString("connector")
        val op = params.optString("op")
        if (id.isBlank() || op.isBlank() || op == "list") return "Connectors:\n" + reg.directory()
        val args = params.optJSONObject("args")?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } } ?: emptyMap()
        if (id in com.agent.ultra.harness.connectors.GoogleAccess.CONNECTORS) {
            com.agent.ultra.harness.connectors.GoogleAccess.refresh(context,
                com.agent.ultra.harness.connectors.AppConnectors.vault(context),
                com.agent.ultra.harness.connectors.AppConnectors.google(context))
        }
        var r = reg.call(id, op, args)
        if (r is com.agent.ultra.harness.connectors.CallResult.NeedsApproval) {
            val ok = ActionGate.approve("connector", "$id.$op", id, "approval",
                title = "Allow Jarvis: $id — $op?", body = r.action.take(600))
            if (!ok) return "Not done: the owner didn't approve.\n${r.action}"
            r = reg.call(id, op, args, approval = reg.approve(r.key))
        }
        return when (r) {
            is com.agent.ultra.harness.connectors.CallResult.Ok -> r.text
            is com.agent.ultra.harness.connectors.CallResult.NeedsConnect ->
                r.why + " The owner connects Google in Settings → CONNECTORS → Connect Google."
            is com.agent.ultra.harness.connectors.CallResult.Failed -> "Error: ${r.reason}"
            is com.agent.ultra.harness.connectors.CallResult.NeedsApproval -> "Not done: it still needs the owner's approval."
        }
    }

    companion object {
        /** Tools that mutate the outside world — confirmation gate targets. */
        val DESTRUCTIVE = setOf("sms_send", "file_delete")

        /** Reach personal data directly, bypassing the app-access list. */
        val PERSONAL_TOOLS = setOf("sms_send", "sms_read", "contacts_read", "device_location")

        /** Perception budgets. Characters, not node counts — a node count
         * punishes a page for having short labels. */
        const val LABEL_CHARS = 120
        const val VIEWPORT_BUDGET = 4000
        const val DEEP_BUDGET = 12000
        const val MAX_DEEP_LABELS = 600
        const val SCROLL_SETTLE_MS = 650L

        /** Shorter than SCROLL_SETTLE_MS: scrolling back reads nothing, so it
         * only has to let each gesture land, not wait for content to render. */
        const val RESTORE_SETTLE_MS = 250L

        /**
         * Tools a tracked secret may legitimately pass through.
         *
         * `react_navigate` types into apps and does its own, stricter check:
         * a value may go back into the app it came from and nowhere else, which
         * is the normal case of entering a code in the app that sent it.
         * Everything else on this list moves nothing anywhere — they are reads.
         *
         * Deliberately an allow-list. Naming the exits instead would mean the
         * exit nobody thought of stays open, which is precisely how the
         * clipboard and note-file routes were found: by an agent taking them
         * after being refused the one that was guarded.
         */
        val SECRET_MAY_PASS = setOf(
            "react_navigate",
            "read_text_on_screen", "read_screen_deep", "describe_screen",
            "screen_ocr", "look_at_screen", "screenshot", "notification_read", "device_info", "battery_status",
            "system_info", "recipe_list", "watch_me", "stop_watching",
            "cancel_watching",
        )

        /** Screens are cheap to relearn, so the table stays small. */
        const val SCREEN_MEMORY_LIMIT = 300

        const val PROTECTED = com.agent.ultra.AgentAccessibilityService.PROTECTED
        const val PROTECTED_MSG =
            "Error: this app is on the user's protected list - its screen cannot be read. " +
                "Do not try again with another screen-reading tool. Tell the user which app " +
                "it is, and that they can change it in Settings if they want to."
    }
}
