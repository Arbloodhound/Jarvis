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
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BrainLoopTest's setup, drive and audit helpers (copied: they are private there), for the newer
 * Robolectric Brain tests. A real [Brain] whose cloud model is a [FakeChatServer] on 127.0.0.1 and
 * which has no on-device model. Call [close] in @After. Test-only.
 */
class BrainHarness : AutoCloseable {
    val ctx: Context get() = ApplicationProvider.getApplicationContext()
    val server = FakeChatServer()
    val answers = CopyOnWriteArrayList<String>()
    /** What the brain said out loud ([Brain.onSpeak]), in order. */
    val spoken = CopyOnWriteArrayList<String>()
    val brain: Brain

    init {
        ProviderConfig.save(ctx, ProviderConfig(baseUrl = server.baseUrl, apiKey = "t", model = "fake-model"))
        ChatStore.messages.clear()
        ChatStore.focus.value = MemoryFocus.Focus.NORMAL
        ChatStore.screenRequest.value = null
        ChatStore.conversationId.value = null
        ChatStore.uiVisible = false
        Brain.lastRequest = ""
        Brain.lastRunSteps = emptyList()
        com.agent.ultra.ui.UltraPrefs.setOfflineOnly(ctx, false)
        brain = Brain(ctx, LocalModelEngine(ctx))
        brain.onAnswer = { answers += it }
        brain.onSpeak = { spoken += it }
        brain.speakTyped = { false }
    }

    override fun close() {
        awaitAuditQuiet()
        server.close()
        ChatStore.messages.clear()
        com.agent.ultra.ui.UltraPrefs.setOfflineOnly(ctx, false)
    }

    /** Runs [block] off the main thread, idling the main looper (ChatStore's UI updates) until it ends. */
    fun drive(timeoutMs: Long = 30_000, block: suspend () -> Unit) {
        val job = CoroutineScope(Dispatchers.IO).async { withTimeout(timeoutMs) { block() } }
        val deadline = System.currentTimeMillis() + timeoutMs + 5_000
        while (!job.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) {
                job.cancel()
                fail("brain run did not finish in ${timeoutMs}ms")
            }
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        runBlocking { job.await() }
    }

    private val auditFile get() = java.io.File(ctx.filesDir, "gate_audit.jsonl")

    /** Every GateAuditLog line written since this harness was made (its writer is async). */
    fun auditLines(): List<org.json.JSONObject> {
        val f = auditFile
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.map { org.json.JSONObject(it) }
    }

    /** Until the audit file has not changed for 300 ms (at most 5 s): the writer has caught up. */
    private fun awaitAuditQuiet() {
        val deadline = System.currentTimeMillis() + 5_000
        var last = -1L
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val now = if (auditFile.exists()) auditFile.length() else 0L
            if (now != last) { last = now; stableSince = System.currentTimeMillis() }
            else if (System.currentTimeMillis() - stableSince >= 300) return
            Thread.sleep(20)
        }
    }

    init {
        // Each harness reads only its own lines: an earlier harness in the same test has finished
        // writing (close waits for that), so its log can go.
        auditFile.delete()
        java.io.File(ctx.filesDir, "gate_audit.1.jsonl").delete()
    }

    /** The route lines as "route/reason". */
    fun routes(): List<String> = auditLines().filter { it.optString("kind") == "route" }
        .map { it.getString("route") + "/" + it.getString("reason") }

    /** Waits up to 5 s for an audit line matching [want]; returns whether one came. */
    fun awaitAudit(want: (org.json.JSONObject) -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (auditLines().any(want)) return true
            Thread.sleep(20)
        }
        return false
    }
}
