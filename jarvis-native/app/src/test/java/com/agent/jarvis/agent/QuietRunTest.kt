package com.agent.jarvis.agent

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The quiet-run line ([QuietStage], [Brain.quietAfterMs] shortened from 20 s): a chat run that has
 * shown nothing for that long leaves exactly one gate-audit line with its stage, elapsed time and
 * route, and the run itself goes on to its answer. Real [Brain] against [FakeChatServer].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuietRunTest {
    private lateinit var h: BrainHarness

    @Before fun setUp() { h = BrainHarness(); h.brain.quietAfterMs = 300 }
    @After fun tearDown() { h.close() }

    private fun quietLines() = h.auditLines().filter { it.optString("kind") == "quiet" }

    @Test fun aSlowCloudCallLeavesOneLineAndStillAnswers() {
        h.server.enqueue(FakeChatServer.Reply.Text("Here's one.", delayMs = 1_500))
        h.drive { h.brain.run("tell me a joke about my secret plan") }
        assertEquals("never cut short", "Here's one.", h.answers.lastOrNull())
        assertTrue(h.awaitAudit { it.optString("kind") == "quiet" })
        Thread.sleep(300)
        val lines = quietLines()
        assertEquals("one line per run: $lines", 1, lines.size)
        val q = lines.single()
        assertEquals(setOf("ts", "kind", "stage", "elapsed_s", "route"), q.keySet())
        assertEquals("cloud call", q.getString("stage"))
        assertEquals("cloud/request", q.getString("route"))
        assertEquals(0L, q.getLong("elapsed_s"))
        assertTrue("no user text", "secret" !in q.toString() && "joke" !in q.toString())
    }

    @Test fun aRunThatAnswersInTimeLeavesNone() {
        h.server.reply("Hi there.")
        h.drive { h.brain.run("tell me a joke") }
        Thread.sleep(800)
        assertEquals(emptyList<Any>(), quietLines())
    }

    @Test fun aRunWaitingBehindAnotherSaysSo() {
        h.server.enqueue(FakeChatServer.Reply.Text("First.", delayMs = 1_500))
        h.server.reply("Second.")
        h.drive {
            coroutineScope {
                launch { h.brain.run("tell me a joke") }
                delay(100)
                launch { h.brain.run("tell me another joke") }
            }
        }
        assertEquals(listOf("First.", "Second."), h.answers.toList())
        val deadline = System.currentTimeMillis() + 5_000
        while (quietLines().size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(300)
        assertEquals(listOf("cloud call", "waiting-for-run-lock"), quietLines().map { it.getString("stage") })
        assertEquals("the second had no route yet", "undecided", quietLines()[1].getString("route"))
    }
}
