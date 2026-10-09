package com.agent.ultra.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v3 design's brain-eval cases that apply to Jarvis, run against the real [Brain] (Robolectric,
 * [BrainHarness]: a scripted cloud model on 127.0.0.1, a key set, no on-device model).
 * Fixture: src/test/resources/brain_eval_jarvis.jsonl, one case per line:
 * `{id, v3_id, input, offline_only, replies:[…], expect:{requests?, pending?, feedback_contains?,
 * answer_prefix?, answer_contains?, answer_regex?, route?}}`.
 * Every case runs on a fresh Brain; all failures are reported together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrainEvalTest {

    private fun cases(): List<JSONObject> =
        javaClass.classLoader!!.getResourceAsStream("brain_eval_jarvis.jsonl")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }.map { JSONObject(it) }

    @Test fun fixtureCoversTheAgreedV3Cases() {
        val ids = cases().map { it.getString("v3_id") }.toSet()
        for (want in listOf("lat-005", "conf-002", "tool-001", "tool-002", "v3-secret-003", "off-002", "fail-001",
            "lat-001", "lat-002", "lat-003")) assertTrue("missing $want", want in ids)
    }

    @Test fun everyCaseBehavesAsExpected() {
        val failures = mutableListOf<String>()
        for (c in cases()) {
            val id = "${c.getString("id")} (${c.getString("v3_id")})"
            val e = c.getJSONObject("expect")
            BrainHarness().use { h ->
                com.agent.ultra.ui.UltraPrefs.setOfflineOnly(h.ctx, c.optBoolean("offline_only"))
                val replies = c.optJSONArray("replies")
                if (replies != null) for (i in 0 until replies.length()) h.server.reply(replies.getString(i))
                h.drive { h.brain.run(c.getString("input")) }
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
                if (e.has("route")) {
                    val want = e.getString("route")
                    check(h.awaitAudit { it.optString("kind") == "route" && "${it.optString("route")}/${it.optString("reason")}" == want },
                        "route $want not recorded: ${h.routes()}")
                }
            }
        }
        assertEquals(failures.joinToString("\n"), emptyList<String>(), failures)
    }
}
