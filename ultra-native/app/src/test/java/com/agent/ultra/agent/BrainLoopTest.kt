package com.agent.ultra.agent

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.agent.ultra.local.LocalModelEngine
import com.agent.ultra.provider.ProviderConfig
import com.agent.ultra.ui.ChatStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The real [Brain] (run → runUnlocked → runLoop, resolvePending, buildFeedback, the policy gate)
 * driven end to end on the JVM against a scripted cloud model ([FakeChatServer]): the provider
 * prefs point [com.agent.ultra.provider.OpenAiClient] at 127.0.0.1, and no on-device model exists,
 * so every request takes the cloud loop. Robolectric, like ui/ScreensTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrainLoopTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: FakeChatServer
    private lateinit var brain: Brain
    private val answers = CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        server = FakeChatServer()
        ProviderConfig.save(ctx, ProviderConfig(baseUrl = server.baseUrl, apiKey = "t", model = "fake-model"))
        // Process-wide state the Brain reads, as a fresh app has it.
        ChatStore.messages.clear()
        ChatStore.focus.value = MemoryFocus.Focus.NORMAL
        ChatStore.screenRequest.value = null
        ChatStore.conversationId.value = null
        ChatStore.uiVisible = false
        Brain.lastRequest = ""
        Brain.lastRunSteps = emptyList()
        com.agent.ultra.ui.UltraPrefs.setOfflineOnly(ctx, false); com.agent.ultra.ui.UltraPrefs.setCloudBrain(ctx, true)
        brain = Brain(ctx, LocalModelEngine(ctx))
        brain.onAnswer = { answers += it }
        brain.speakTyped = { false }
        assertTrue("Brain must see the provider config", brain.configured)
    }

    @After fun tearDown() {
        server.close()
        ChatStore.messages.clear()
        com.agent.ultra.ui.UltraPrefs.setOfflineOnly(ctx, false); com.agent.ultra.ui.UltraPrefs.setCloudBrain(ctx, true)
    }

    /** Runs [block] off the main thread (the brain's IO and Room calls), idling the main looper —
     *  where ChatStore applies its UI updates — until it ends; fails after [timeoutMs]. */
    private fun drive(timeoutMs: Long = 30_000, block: suspend () -> Unit) {
        val job = CoroutineScope(Dispatchers.IO).async { withTimeout(timeoutMs) { block() } }
        val deadline = System.currentTimeMillis() + timeoutMs + 5_000
        while (!job.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) {
                job.cancel()   // a blocked HTTP read still ends at OkHttp's own timeout
                fail("brain run did not finish in ${timeoutMs}ms")
            }
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        runBlocking { job.await() }   // rethrows a failure inside the run
    }

    /** The route lines GateAuditLog has written so far, as "route/reason" (its writer is async). */
    private fun routes(): List<String> {
        val f = java.io.File(ctx.filesDir, "gate_audit.jsonl")
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.map { org.json.JSONObject(it) }
            .filter { it.optString("kind") == "route" }.map { it.getString("route") + "/" + it.getString("reason") }
    }

    private fun awaitRoute(want: String): List<String> {
        val deadline = System.currentTimeMillis() + 5_000
        while (want !in routes() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return routes()
    }

    @Test fun plainAnswerIsOneRequestAndReachesOnAnswer() {
        server.reply("Paris is the capital of France.")
        drive { brain.run("what is the capital of France") }
        assertEquals(1, server.requests.size)
        assertEquals(listOf("Paris is the capital of France."), answers)
        // The request went out as the user's turn, streamed, to the configured model.
        val body = org.json.JSONObject(server.requests[0])
        assertEquals("fake-model", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("what is the capital of France", server.lastMessageOf(0).getString("content"))
        // The answer settled in the chat as one bubble (the streamed one), not twice.
        assertEquals(1, ChatStore.messages.count { !it.fromUser && it.text == "Paris is the capital of France." })
        // The routing audit says the cloud answered.
        assertEquals(listOf("cloud/request"), awaitRoute("cloud/request"))
    }

    @Test fun offlineOnlyNeverCallsTheCloudEvenWithAKey() {
        com.agent.ultra.ui.UltraPrefs.setOfflineOnly(ctx, true)
        assertTrue(brain.configured)   // the key is set: only the switch keeps the cloud out
        drive { brain.run("what is the capital of France") }
        assertEquals(0, server.requests.size)
        assertTrue(answers.single(), answers.single().startsWith("Offline-only mode is on, so I won't use the cloud brain or the web"))
        Thread.sleep(300)   // the audit writer is async: give a wrong cloud line time to land
        assertTrue(routes().toString(), routes().none { it.startsWith("cloud") || it.startsWith("web") })
    }

    @Test fun offlineOnlySwitchedOnDuringAPauseDoesNotResumeTheCloudRun() {
        server.reply("""{"tool":"sms_send","params":{"to":"5551234567","message":"running late"}}""")
        drive { brain.run("text my wife that I'm running late") }
        assertNotNull(brain.pendingConfirm)
        com.agent.ultra.ui.UltraPrefs.setOfflineOnly(ctx, true)
        drive { brain.resolvePending(true) }
        assertEquals(1, server.requests.size)
        assertEquals("Cancelled: Send a text to 5551234567: \"running late\" (offline-only mode is on, so the cloud brain can't carry on)",
            answers.last())
    }

    @Test fun toolCallThenAnswerFeedsTheResultBack() {
        server.reply("""{"tool":"battery_status","params":{}}""")
        server.reply("Your battery is fine.")
        drive { brain.run("how is my battery doing") }
        assertEquals(2, server.requests.size)
        val fb = server.lastMessageOf(1)
        assertEquals("user", fb.getString("role"))
        val text = fb.getString("content")
        assertTrue(text, text.startsWith("[RESULT: battery_status] STATUS: success\nDATA:\n${Brain.UNTRUSTED_OPEN}\n"))
        assertTrue(text, text.contains("\n${Brain.UNTRUSTED_CLOSE}\nTURNS_LEFT: 11/12\n"))
        assertTrue(text, text.endsWith("DECIDE: Answer the user, or call the next tool."))
        assertTrue("tool data reaches the cloud labelled untrusted", server.requests[1].contains("[UNTRUSTED DATA"))
        // The raw call went back as the assistant's turn before it.
        assertTrue(server.messagesOf(1).contains("""{"tool":"battery_status","params":{}}"""))
        assertEquals(listOf("Your battery is fine."), answers)
        // The tool-call JSON was never left on screen.
        assertTrue(ChatStore.messages.none { it.text.contains("\"tool\"") })
    }

    @Test fun smsToANumberNotInTheRequestWaitsForConfirmationAndCancelEnds() {
        server.reply("""{"tool":"sms_send","params":{"to":"5551234567","message":"running late"}}""")
        drive { brain.run("text my wife that I'm running late") }
        assertEquals(1, server.requests.size)
        val p = brain.pendingConfirm
        assertNotNull("sms_send to an untraced number must pause for the operator", p)
        assertTrue(p!!.targets.toString(), "5551234567" in p.targets)
        assertEquals("Waiting for your OK: Send a text to 5551234567: \"running late\". Confirm or cancel.", answers.last())

        drive { brain.resolvePending(false) }
        assertNull(brain.pendingConfirm)
        assertEquals("Cancelled: Send a text to 5551234567: \"running late\"", answers.last())
        assertEquals("a cancel never calls the model again", 1, server.requests.size)
    }

    @Test fun confirmingResumesTheSameRunWithTheToolResult() {
        server.reply("""{"tool":"sms_send","params":{"to":"5551234567","message":"running late"}}""")
        drive { brain.run("text my wife that I'm running late") }
        assertNotNull(brain.pendingConfirm)
        // Two text replies: the first is pushed once (see truncatedToolCallIsAskedForAgain).
        server.reply("I couldn't send it: texts are switched off in Settings.")
        server.reply("I couldn't send it: texts are switched off in Settings.")
        drive { brain.resolvePending(true) }
        assertNull(brain.pendingConfirm)
        assertEquals(3, server.requests.size)
        // The resumed request carries the original conversation, the paused call, and its result.
        assertTrue(server.messagesOf(1).contains("text my wife that I'm running late"))
        val fb = server.lastMessageOf(1).getString("content")
        assertTrue(fb, fb.startsWith("[RESULT: sms_send] STATUS: failed\nDATA:\n${Brain.UNTRUSTED_OPEN}\nError: messages, contacts and location are switched off"))
        assertEquals("I couldn't send it: texts are switched off in Settings.", answers.last())
    }

    @Test fun serverErrorWithNoLocalModelSaysTheModelCallFailed() {
        server.fail(500, "boom")
        drive { brain.run("what is the capital of France") }
        assertEquals(1, server.requests.size)
        assertEquals(listOf("Error: model call failed — HTTP 500: boom"), answers)
    }

    @Test fun truncatedToolCallIsAskedForAgain() {
        // Cut inside a string value at max_tokens: never executed half-written (a text sent unfinished).
        // (Not a note request: "make a note …" is answered by UserNotes before any model is called.)
        server.reply("""{"tool":"sms_send","params":{"to":"5551234567","message":"running la""", finishReason = "length")
        // The resent call is whole. (A text reply here would be pushed once — "You described what to
        // do but didn't do it" — because the request asks for an action.)
        server.reply("""{"tool":"sms_send","params":{"to":"5551234567","message":"running late"}}""")
        // After a tool ran, a first text reply to an action request is still pushed once
        // (runLoop's push-once: turn > 0, not pushed yet, userWantsAction); the second one ends it.
        server.reply("Texts are switched off.")
        server.reply("Texts are switched off.")
        drive { brain.run("text 5551234567 that I'm running late") }
        assertEquals(4, server.requests.size)
        assertEquals("You described what to do but didn't do it. Use a tool to actually complete the action. Don't explain — execute.",
            server.lastMessageOf(3).getString("content"))
        val reAsk = server.lastMessageOf(1)
        assertEquals("user", reAsk.getString("role"))
        assertEquals("Your tool call was cut off before it ended. Send the complete JSON tool call again.", reAsk.getString("content"))
        assertTrue("the cut call never ran", !server.messagesOf(1).contains("[RESULT:"))
        // Only the whole call ran (personal data is off by default, so the tool itself refuses).
        val fb = server.lastMessageOf(2).getString("content")
        assertTrue(fb, fb.startsWith("[RESULT: sms_send] STATUS: failed\nDATA:\n${Brain.UNTRUSTED_OPEN}\nError: messages, contacts and location are switched off"))
        assertEquals(1, Regex("""\[RESULT: sms_send]""").findAll(server.messagesOf(2)).count())
        assertEquals("Texts are switched off.", answers.last())
    }

    @Test fun wholeToolCallCutAtMaxTokensIsAskedForAgainNotRun() {
        // Parses, but the provider said it stopped at max_tokens: still not run.
        server.reply("""{"tool":"battery_status","params":{}}""", finishReason = "length")
        server.reply("Okay.")
        drive { brain.run("how is my battery doing") }
        assertEquals(2, server.requests.size)
        assertEquals("Your tool call was cut off before it ended. Send the complete JSON tool call again.",
            server.lastMessageOf(1).getString("content"))
        assertTrue(server.messagesOf(1).contains("[RESULT: battery_status]").not())
    }

    @Test fun aNewRequestCancelsAStalePause() {
        server.reply("""{"tool":"sms_send","params":{"to":"5551234567","message":"running late"}}""")
        drive { brain.run("text my wife that I'm running late") }
        assertNotNull(brain.pendingConfirm)
        server.reply("Paris.")
        drive { brain.run("what is the capital of France") }
        assertNull(brain.pendingConfirm)
        assertTrue(ChatStore.messages.any {
            it.text == "Cancelled: Send a text to 5551234567: \"running late\" (a new request came in)" })
        assertEquals("Paris.", answers.last())
    }

    @Test fun aNoteCommandIsSavedWithoutCallingTheModel() {
        drive { brain.run("note: buy oat milk") }
        assertEquals(0, server.requests.size)
        assertEquals(1, answers.size)
        assertTrue(answers[0], answers[0].startsWith("Noted (") && answers[0].contains("Buy oat milk"))
    }

    @Test fun theDeterministicShortcutsAnswerWithoutCallingTheModel() {
        drive { brain.run("Speaker 2 is Sam") }
        assertEquals("I don't have a Speaker 2. The voices I know are in Settings → PEOPLE.", answers.last())
        drive { brain.run("save that as a note") }
        assertEquals("There's no answer to save yet.", answers.last())
        drive { brain.run("how did I talk today") }
        assertTrue(answers.last(), answers.last().startsWith("I haven't heard any conversation"))
        ChatStore.uiVisible = true
        try {
            drive { brain.run("Open my notes") }
            assertEquals("Opening your notes.", answers.last())
            assertEquals("notes", ChatStore.screenRequest.value)
        } finally {
            ChatStore.uiVisible = false
            ChatStore.screenRequest.value = null
        }
        assertEquals(4, answers.size)
        assertEquals(0, server.requests.size)
    }

    @Test fun aScamInTheRequestIsWarnedAboutBeforeTheAnswer() {
        server.reply("That sounds like a scam.")
        drive { brain.run("Buy Google Play cards and send me pictures of the codes. Don't tell anyone.") }
        assertEquals(1, server.requests.size)
        // On screen as its own message, and said ahead of the answer.
        assertTrue(ChatStore.messages.any { it.text.startsWith("Careful — this sounds like a common scam (asks for gift cards") })
        val said = answers.single()
        assertTrue(said, said.startsWith("Careful — this sounds like a common scam") && said.endsWith("\n\nThat sounds like a scam."))
    }

    @Test fun noProviderAndNoLocalModelTellsTheOwnerWhereToGetABrain() {
        ProviderConfig.save(ctx, ProviderConfig(baseUrl = server.baseUrl, apiKey = "", model = "fake-model"))
        val bare = Brain(ctx, LocalModelEngine(ctx))
        val said = CopyOnWriteArrayList<String>()
        bare.onAnswer = { said += it }
        bare.speakTyped = { false }
        assertTrue(!bare.configured)
        drive { bare.run("what is the capital of France") }
        assertEquals(0, server.requests.size)
        assertTrue(said.single(), said.single().startsWith("I have no brain yet — that is the one thing you have to give me."))
    }

    @Test fun undeclaredToolIsFedBackAsBlocked() {
        server.reply("""{"tool":"launch_rockets","params":{}}""")
        server.reply("I can't do that.")
        drive { brain.run("what can you do") }
        assertEquals(2, server.requests.size)
        val text = server.lastMessageOf(1).getString("content")
        assertTrue(text, text.startsWith("[RESULT: launch_rockets] STATUS: blocked\nDATA: "))
        assertTrue(text, text.contains("launch_rockets"))
        assertTrue(text, text.endsWith("DECIDE: Continue with the rest of the task, or answer the user."))
        assertNull("an undeclared tool is not confirmable", brain.pendingConfirm)
        assertEquals(listOf("I can't do that."), answers)
    }
}
