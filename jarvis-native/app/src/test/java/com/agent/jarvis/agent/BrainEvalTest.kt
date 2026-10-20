package com.agent.jarvis.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v3 and v4 designs' brain-eval cases that apply to Jarvis, run against the real [Brain]
 * (Robolectric, [BrainHarness]: a scripted cloud model on 127.0.0.1, a key set, no on-device model).
 * Fixture: src/test/resources/brain_eval_jarvis.jsonl, one case per line:
 * `{id, v3_id | v4_id, input, offline_only, spoken?, clipboard?, replies:[…], expect:{requests?, pending?,
 * feedback_contains?, answer_prefix?, answer_contains?, answer_regex?, answer_nonblank?, spoken_nonblank?,
 * history_turns?, route?, no_route?}}`.
 * `spoken`: run as a spoken request. `clipboard`: the clipboard's text before the run.
 * `history_turns`: the user/assistant turns in the first model request ahead of the request itself.
 * A `layer: "interrupt"` case is not a run: it is what [Interrupt.decide] makes of the words with
 * nothing running (`expect.decision`: stop / run).
 * Every case runs on a fresh Brain; all failures are reported together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrainEvalTest {

    private fun cases(): List<JSONObject> =
        javaClass.classLoader!!.getResourceAsStream("brain_eval_jarvis.jsonl")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }.map { JSONObject(it) }

    @Test fun fixtureCoversTheAgreedV3Cases() {
        val ids = cases().mapNotNull { it.optString("v3_id").ifEmpty { null } }.toSet()
        for (want in listOf("lat-005", "conf-002", "tool-001", "tool-002", "v3-secret-003", "off-002", "fail-001",
            "lat-001", "lat-002", "lat-003")) assertTrue("missing $want", want in ids)
    }

    @Test fun fixtureCoversTheAgreedV4Cases() {
        val ids = cases().mapNotNull { it.optString("v4_id").ifEmpty { null } }.toSet()
        for (want in listOf("s-001", "s-002", "p-004", "e-001", "i-002", "a-006", "a-007"))
            assertTrue("missing $want", want in ids)
    }

    @Test fun everyCaseBehavesAsExpected() {
        val failures = mutableListOf<String>()
        for (c in cases()) {
            val id = "${c.getString("id")} (${c.optString("v3_id").ifEmpty { c.optString("v4_id") }})"
            val e = c.getJSONObject("expect")
            if (c.optString("layer") == "interrupt") {
                val got = when (val d = Interrupt.decide(c.getString("input"), null)) {
                    Interrupt.Decision.Stop -> "stop"
                    is Interrupt.Decision.Run -> "run"
                    else -> d.toString()
                }
                if (got != e.getString("decision")) failures += "$id: decision $got != ${e.getString("decision")}"
                continue
            }
            BrainHarness().use { h ->
                com.agent.jarvis.ui.JarvisPrefs.setOfflineOnly(h.ctx, c.optBoolean("offline_only")); com.agent.jarvis.ui.JarvisPrefs.setCloudBrain(h.ctx, true)
                val clip = h.ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                if (c.has("clipboard")) clip.setPrimaryClip(android.content.ClipData.newPlainText("eval", c.getString("clipboard")))
                else clip.clearPrimaryClip()
                val replies = c.optJSONArray("replies")
                if (replies != null) for (i in 0 until replies.length()) h.server.reply(replies.getString(i))
                h.drive { h.brain.run(c.getString("input"), spoken = c.optBoolean("spoken")) }
                fun check(ok: Boolean, what: String) { if (!ok) failures += "$id: $what" }
                val said = h.answers.lastOrNull().orEmpty()
                if (e.has("requests")) check(h.server.requests.size == e.getInt("requests"),
                    "requests ${h.server.requests.size} != ${e.getInt("requests")}")
                if (e.has("pending")) check((h.brain.pendingConfirm != null) == e.getBoolean("pending"),
                    "pending=${h.brain.pendingConfirm != null}")
                if (e.has("feedback_contains")) {
                    val sent = h.server.requests.indices.joinToString("\n") { h.server.messagesOf(it) }
                    check(sent.contains(e.getString("feedback_contains")), "no \"${e.getString("feedback_contains")}\" fed back")
                }
                if (e.has("answer_prefix")) check(said.startsWith(e.getString("answer_prefix")), "answer \"$said\"")
                if (e.has("answer_contains")) check(said.contains(e.getString("answer_contains")), "answer \"$said\"")
                if (e.has("answer_regex")) check(Regex(e.getString("answer_regex")).matches(said), "answer \"$said\" !~ regex")
                if (e.optBoolean("answer_nonblank")) check(said.isNotBlank(), "blank answer: ${h.answers}")
                if (e.optBoolean("spoken_nonblank")) check(h.spoken.any { it.isNotBlank() }, "nothing spoken: ${h.spoken}")
                if (e.has("history_turns")) {
                    val msgs = h.server.requests.firstOrNull()?.let { JSONObject(it).getJSONArray("messages") }
                    val turns = if (msgs == null) -1 else
                        (0 until msgs.length() - 1).count { msgs.getJSONObject(it).getString("role") in setOf("user", "assistant") }
                    check(turns == e.getInt("history_turns"), "history turns $turns != ${e.getInt("history_turns")}")
                }
                if (e.has("route")) {
                    val want = e.getString("route")
                    check(h.awaitAudit { it.optString("kind") == "route" && "${it.optString("route")}/${it.optString("reason")}" == want },
                        "route $want not recorded: ${h.routes()}")
                }
                // After the awaited route above: this run's route lines are written by then.
                if (e.has("no_route")) check(e.getString("no_route") !in h.routes(), "route ${e.getString("no_route")} recorded: ${h.routes()}")
            }
        }
        assertEquals(failures.joinToString("\n"), emptyList<String>(), failures)
    }
}
