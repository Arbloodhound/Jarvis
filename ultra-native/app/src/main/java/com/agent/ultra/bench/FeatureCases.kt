package com.agent.ultra.bench

import com.agent.ultra.agent.ActionGate
import com.agent.ultra.agent.DirectRequest
import com.agent.ultra.agent.Disagreement
import com.agent.ultra.agent.HistoryPolicy
import com.agent.ultra.agent.LocalPrompt
import com.agent.ultra.agent.LocalToolCall
import com.agent.ultra.agent.MemoryBrief
import com.agent.ultra.agent.OfflineNav
import com.agent.ultra.bench.FeatureGrade.Check
import com.agent.ultra.listen.HeardLine
import com.agent.ultra.listen.NoteDraft
import com.agent.ultra.listen.NoteKind

/**
 * The fixed inputs of the debug-only "Feature check", and its pure checks (the parts that need
 * no phone: history policy, safety gate decisions, offline-navigation parsing). Lives in main so
 * the JVM tests run the same cases the phone runs; no release code path references it, so R8
 * drops it. Pure, JVM-tested.
 */
object FeatureCases {

    /** One fixed case: what was expected, what came out, and whether they agree. */
    data class Case(val name: String, val expected: String, val got: String) {
        val ok: Boolean get() = expected == got
        fun line(): String = (if (ok) "ok   " else "WRONG") + " $name: expected $expected, got $got"
    }

    // ── The Jarvis Tests questions (shared with the brain test) ────────────────────────────

    val LONG_TEXT = (1..40).joinToString(" ") {
        "Paragraph $it. The town council met on Tuesday to discuss the new library, the bus routes " +
            "and the summer market, and agreed to publish the minutes next week."
    }

    /** One per thinking tier the router uses: light, short maths, normal. */
    val QUICK_QUESTIONS = listOf("hi", "what's 17 times 23", "who was Napoleon in two sentences")

    val KIT_QUESTIONS = listOf(
        "hi",
        "explain why the sky is blue",
        "turn on the flashlight",
        "reply with three emojis and the word café",
        "what are three good dinners with chicken",
        "who was Napoleon in two sentences",
        "what's 17 times 23",
        "give me a packing list for a weekend trip",
        "explain what a mortgage is",
        "summarize everything we talked about",
        "summarize this: $LONG_TEXT",
    )

    // ── 2. Thinker quality ─────────────────────────────────────────────────────────────────

    data class Question(val ask: String, val check: Check)

    /** Six questions with one checkable answer each. */
    val THINKER_QUESTIONS = listOf(
        Question("What is 17 times 23? Reply with just the number.", Check.Number("391")),
        Question("How many centimetres are in 2.5 metres? Reply with just the number.", Check.Number("250")),
        Question("Tom is taller than Sam. Sam is taller than Ann. Who is the shortest? Reply with one name.",
            Check.Word("Ann", not = listOf("Tom", "Sam"))),
        Question("What is the capital city of Australia? Reply with one word.", Check.Word("Canberra")),
        Question("Reply with exactly the word: banana", Check.Exact("banana")),
        Question("A train leaves at 3:40 pm and the trip takes 50 minutes. What time does it arrive? Reply with just the time.",
            Check.Number("4:30")),
    )

    /** Q8/Q11 regression (DEVLOG 2026-10-06): earlier answers must not leak into later ones. */
    const val REGRESSION_MATH = "what's 17 times 23"
    const val REGRESSION_PACKING = "give me a packing list for a weekend trip"
    const val PASTE = "The beekeeping club met on Saturday morning. Members inspected twelve hives, found the " +
        "queen in hive four, and harvested thirty jars of honey. They agreed to sell the honey at the " +
        "village fair in June and to buy two new hives with the money."
    const val REGRESSION_SUMMARY = "summarize this: $PASTE"
    /** Any of these in the summary shows it read the paste. */
    val PASTE_NOUNS = listOf("honey", "hive", "hives", "beekeeping", "bee", "bees", "beekeepers")
    /** Any of these in the summary shows it copied the previous answer instead. */
    val PACKING_WORDS = listOf("toothbrush", "charger", "packing", "toiletries", "underwear", "socks")

    fun packingLeak(answer: String): Boolean = FeatureGrade.passes(answer, Check.Number("391"))

    fun summaryReadPaste(answer: String): Boolean =
        PASTE_NOUNS.any { FeatureGrade.passes(answer, Check.Word(it)) } &&
            PACKING_WORDS.none { FeatureGrade.passes(answer, Check.Word(it)) } &&
            !packingLeak(answer)

    /**
     * The offline prompt for [request] after [history], assembled the way Brain.emitLocal does
     * (HistoryPolicy decides; no memory brief). Used when the phone is online and the real chat
     * path would go to the cloud.
     */
    fun mirroredPrompt(history: List<LocalPrompt.Turn>, request: String, historyChars: Int = 3000): String {
        val h = if (HistoryPolicy.decide(request).include) HistoryPolicy.window(history, historyChars) else emptyList()
        return LocalPrompt.build(LocalPrompt.OFFLINE_RULES, h, request, historyChars, null)
    }

    // ── 3. Librarian notes ─────────────────────────────────────────────────────────────────

    /** A 4-line overheard transcript: one person, one plan with a time, one todo, one bit of small talk. */
    fun notesTranscript(startTs: Long): List<HeardLine> = listOf(
        "Priya is my sister-in-law, she just moved to Leeds with her two kids.",
        "We have a dentist appointment with Dr Lee tomorrow at 3pm, don't be late.",
        "And you still need to renew your passport before the Lisbon trip.",
        "Yeah the weather has been lovely this week.",
    ).mapIndexed { i, t -> HeardLine(id = i + 1L, ts = startTs + i * 30_000L, text = t) }

    /** A note the librarian should write: [kind], mentioning [keyword], with time words when [needsWhen]. */
    data class ExpectedNote(val label: String, val kind: NoteKind, val keyword: String, val needsWhen: Boolean = false)

    val EXPECTED_NOTES = listOf(
        ExpectedNote("person (Priya)", NoteKind.PERSON, "Priya"),
        ExpectedNote("plan with a time (dentist)", NoteKind.PLAN, "dentist", needsWhen = true),
        ExpectedNote("todo (passport)", NoteKind.TODO, "passport"),
    )

    /** For each expected note: was one written? (A fact about the same thing does not count.) */
    fun notesFound(drafts: List<NoteDraft>, expected: List<ExpectedNote> = EXPECTED_NOTES): List<Case> =
        expected.map { e ->
            val hit = drafts.any {
                it.kind == e.kind && it.text.contains(e.keyword, ignoreCase = true) &&
                    (!e.needsWhen || !it.whenPhrase.isNullOrBlank())
            }
            Case(e.label, "found", if (hit) "found" else "not found")
        }

    // ── 6. Pattern notes ───────────────────────────────────────────────────────────────────

    /** A cue-tagged dialogue with a hedge, a topic shift and a commitment in it. */
    fun patternDialogue(startTs: Long): List<HeardLine> = listOf(
        "[Me] So are you coming to the dinner on Friday?",
        "[Sam] I mean, maybe Friday, [pause 1.8s] I'll see how work goes.",
        "[Me] You said that last week too.",
        "[Sam] [fast] Anyway, did you watch the match last night?",
        "[Me] I'll book the table for seven tomorrow morning, I promise.",
        "[Sam] Fine, okay, I'll be there at seven.",
    ).mapIndexed { i, t -> HeardLine(id = i + 1L, ts = startTs + i * 20_000L, text = t) }

    // ── 4. Memory search ───────────────────────────────────────────────────────────────────

    /** A synthetic overheard line, [daysAgo] days and at [hour]:[minute] local time. */
    data class Heard(val text: String, val daysAgo: Int, val hour: Int, val minute: Int = 0)

    val HEARD = listOf(
        Heard("The garage said the Honda will be fixed by Friday afternoon.", 2, 10),
        Heard("Priya said the Lisbon flight leaves at six in the morning.", 1, 9),
        Heard("Dinner at Marco's was great, the lasagne especially.", 1, 20),
        Heard("Dinner with the neighbours on Saturday was fun too.", 4, 20),
        Heard("I still need to pay Daniel back forty pounds for the concert tickets.", 3, 18),
        Heard("The dentist moved my appointment to Thursday at nine.", 0, 8),
        Heard("The weather is supposed to be rainy all week.", 0, 7),
        Heard("Can you pass the salt please.", 2, 19),
        Heard("We need more milk and eggs from the shop.", 3, 8),
        Heard("The kids have football practice on Wednesday after school.", 5, 16),
    )

    /** When [h] was "heard": its day and time in [zone], never later than ten minutes before [nowMs]. */
    fun heardTs(h: Heard, nowMs: Long, zone: java.time.ZoneId): Long {
        val day = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().minusDays(h.daysAgo.toLong())
        val ts = day.atTime(h.hour, h.minute).atZone(zone).toInstant().toEpochMilli()
        return minOf(ts, nowMs - 10 * 60_000L)
    }

    /** [query] should find the line containing [expected]; [how] is what the query tests. */
    data class Recall(val query: String, val expected: String, val how: String)

    val RECALL = listOf(
        Recall("when is my vehicle getting repaired", "Honda", "meaning (no shared words)"),
        Recall("what did Priya say about Lisbon", "Lisbon flight", "keyword"),
        Recall("where did we eat yesterday evening", "Marco's", "yesterday + meaning"),
        Recall("how much money do I owe for the gig", "forty pounds", "meaning"),
        Recall("dentist appointment", "dentist moved", "keyword + meaning"),
    )

    /** A memory question gets a "What I remember" block; a sum doesn't. */
    const val BRIEF_MEMORY_QUESTION = "what did Priya say about Lisbon yesterday"
    const val BRIEF_PLAIN_QUESTION = "what's 2+2"

    fun briefDecisions(): List<Case> = listOf(
        Case("memory search for \"$BRIEF_MEMORY_QUESTION\"", "yes", yn(MemoryBrief.wants(BRIEF_MEMORY_QUESTION).search)),
        Case("memory search for \"$BRIEF_PLAIN_QUESTION\"", "no", yn(MemoryBrief.wants(BRIEF_PLAIN_QUESTION).search)),
    )

    // ── 5. History policy ──────────────────────────────────────────────────────────────────

    /** Of the 11 Jarvis Tests questions only Q10 ("summarize everything we talked about") gets history. */
    fun historyCases(): List<Case> = KIT_QUESTIONS.mapIndexed { i, q ->
        val d = HistoryPolicy.decide(q)
        val shown = if (q.length > 40) q.take(37) + "…" else q
        Case("Q${i + 1} \"$shown\"", if (i == 9) "history" else "none", if (d.include) "history" else "none")
    }

    // ── 9. Safety gates ────────────────────────────────────────────────────────────────────

    enum class Gate { ALLOW, ASK, REFUSE }

    /**
     * The decision on one tap, in ReActNavigator.approveTap's order: not a commitment → ALLOW; the
     * screen's amounts contradict the request → REFUSE; asked for in the person's own words
     * (option B, with the setting on) → ALLOW; otherwise ASK (the confirmation card).
     */
    fun gate(request: String, label: String, screen: String, contacts: List<String>, skipWhenAsked: Boolean = true): Gate {
        val word = ActionGate.commitmentIn(label) ?: return Gate.ALLOW
        if (Disagreement.contradiction(request, screen) != null) return Gate.REFUSE
        if (skipWhenAsked && DirectRequest.skipConfirmation(request, word, screen, contacts)) return Gate.ALLOW
        return Gate.ASK
    }

    data class GateCase(
        val name: String, val request: String, val label: String, val screen: String,
        val contacts: List<String>, val expected: Gate,
    )

    val GATE_CASES = listOf(
        GateCase("wrong amount on a pay screen", "pay £240 for the electricity bill", "Pay now",
            "Electricity bill Total £2,400.00", emptyList(), Gate.REFUSE),
        GateCase("right amount, still money", "pay £240 for the electricity bill", "Pay now",
            "Electricity bill Total £240.00", emptyList(), Gate.ASK),
        GateCase("message to a named contact", "send hi to Anna on WhatsApp", "Send",
            "Anna Smith  hi", listOf("Anna Smith", "Bob Jones"), Gate.ALLOW),
        GateCase("message to someone else", "send hi to Anna on WhatsApp", "Send",
            "Bob Jones  hi", listOf("Anna Smith", "Bob Jones"), Gate.ASK),
        GateCase("navigation that shares a word", "show my orders", "Your Orders",
            "Amazon home", emptyList(), Gate.ALLOW),
        GateCase("delete when asked to post", "post this photo", "Delete",
            "Photo 1 of 3", emptyList(), Gate.ASK),
        GateCase("install when asked to install", "install Signal", "Install",
            "Signal Private Messenger", emptyList(), Gate.ALLOW),
        GateCase("button Jarvis can't read", "pay for the order", "Оплатить",
            "Итого 500", emptyList(), Gate.ASK),
    )

    fun gateCases(skipWhenAsked: Boolean = true): List<Case> = GATE_CASES.map { c ->
        val expected = if (!skipWhenAsked && c.expected == Gate.ALLOW && ActionGate.commitmentIn(c.label) != null) Gate.ASK else c.expected
        Case(c.name, expected.name, gate(c.request, c.label, c.screen, c.contacts, skipWhenAsked).name)
    }

    // ── 8. Offline phone control ───────────────────────────────────────────────────────────

    /** A two-element screen: 1 = a switch, 2 = a text box. */
    val NAV_SCREEN = OfflineNav.Screen(
        text = "1 \"Wi-Fi\"\n2 [box] \"Search settings\"",
        elements = listOf(OfflineNav.Element(1, 10, "Wi-Fi", false), OfflineNav.Element(2, 11, "Search settings", true)),
        hidden = 0,
    )

    private fun parsed(reply: String): String = when (val p = OfflineNav.parse(reply, NAV_SCREEN)) {
        is OfflineNav.Parsed.Ok -> p.action.said()
        is OfflineNav.Parsed.Bad -> "unusable"
    }

    fun navCases(): List<Case> {
        val guard = OfflineNav.LoopGuard()
        val first = guard.repeated("screenA", OfflineNav.Action.Tap(1))
        val again = guard.repeated("screenA", OfflineNav.Action.Tap(1))
        val otherScreen = guard.repeated("screenB", OfflineNav.Action.Tap(1))
        val task = OfflineNav.task("open settings and turn on dark mode")
        return listOf(
            Case("reply \"tap 1\"", "tap 1", parsed("tap 1")),
            Case("reply after a think block", "tap 1", parsed("<think>the switch is 1</think>\ntap 1")),
            Case("reply type into the box", "type 2 \"wifi\"", parsed("type 2 \"wifi\"")),
            Case("reply tap a number not listed", "unusable", parsed("tap 9")),
            Case("reply type into a non-box", "unusable", parsed("type 1 \"x\"")),
            Case("reply with chatter", "unusable", parsed("Sure! I will tap 1 for you.")),
            Case("reply done", "done \"Wi-Fi is on\"", parsed("done \"Wi-Fi is on\"")),
            Case("loop guard: first try", "new", if (first) "repeat" else "new"),
            Case("loop guard: same action, same screen", "repeat", if (again) "repeat" else "new"),
            Case("loop guard: same action, new screen", "new", if (otherScreen) "repeat" else "new"),
            Case("\"turn on the flashlight\" is a one-shot tool", "tool",
                if (LocalToolCall.offlineRoute("turn on the flashlight", canDecline = true)) "tool" else "not a tool"),
            Case("\"open settings and turn on dark mode\" is navigation", "navigation in settings",
                task?.let { "navigation in ${it.appHint}" } ?: "not navigation"),
            Case("a question is never navigation", "not navigation",
                OfflineNav.task("what is dark mode")?.let { "navigation" } ?: "not navigation"),
        )
    }

    private fun yn(b: Boolean) = if (b) "yes" else "no"
}
