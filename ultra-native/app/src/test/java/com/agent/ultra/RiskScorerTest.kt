package com.agent.ultra

import com.agent.ultra.gate.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RiskScorerTest {

    private fun spec(vararg effects: Effect) = ToolSpec("test_tool", effects.toSet(), emptyList())

    private fun obs(vararg sources: Fact.Source): ObservationLog {
        val log = ObservationLog()
        log.record(Fact(
            tool = "<user>",
            source = Fact.Source.USER_REQUEST,
            confidence = Fact.Confidence.HIGH,
            summary = "test",
        ))
        for (s in sources) {
            val tool = if (s == Fact.Source.SYSTEM_API) "battery_status" else "screen_read"
            log.record(tool, s, "data")
        }
        return log
    }

    @Test
    fun `read-only tool with system api scores low`() {
        val s = RiskScorer.score(
            spec(Effect.READ), obs(Fact.Source.SYSTEM_API),
            emptyList(), JSONObject(), "check battery",
        )
        assertEquals(0, s.effectTier)
        assertEquals(0, s.obsQuality)
        assertEquals(0, s.total)
    }

    @Test
    fun `egress tool scores higher on effect tier`() {
        val s = RiskScorer.score(
            spec(Effect.EGRESS), obs(Fact.Source.SYSTEM_API),
            emptyList(), JSONObject(), "send sms",
        )
        assertEquals(40, s.effectTier)
    }

    @Test
    fun `mutate tool scores mid effect tier`() {
        val s = RiskScorer.score(
            spec(Effect.MUTATE), obs(Fact.Source.SYSTEM_API),
            emptyList(), JSONObject(), "toggle wifi",
        )
        assertEquals(20, s.effectTier)
    }

    @Test
    fun `tree-only observations add risk`() {
        val s = RiskScorer.score(
            spec(Effect.EGRESS), obs(Fact.Source.ACCESSIBILITY_TREE),
            emptyList(), JSONObject(), "search web",
        )
        assertEquals(25, s.obsQuality)
    }

    @Test
    fun `mixed observations use high confidence`() {
        val s = RiskScorer.score(
            spec(Effect.EGRESS),
            obs(Fact.Source.SYSTEM_API, Fact.Source.ACCESSIBILITY_TREE),
            emptyList(), JSONObject(), "search web",
        )
        assertEquals(0, s.obsQuality)
    }

    @Test
    fun `no tool observations add small risk`() {
        val s = RiskScorer.score(
            spec(Effect.READ), obs(),
            emptyList(), JSONObject(), "battery",
        )
        assertEquals(5, s.obsQuality)
    }

    @Test
    fun `taint on egress adds risk`() {
        val args = JSONObject().put("body", "secret123")
        val s = RiskScorer.score(
            spec(Effect.EGRESS), obs(Fact.Source.SYSTEM_API),
            listOf("secret123"), args, "send message",
        )
        assertEquals(25, s.taintExposure)
    }

    @Test
    fun `taint on non-egress scores zero`() {
        val args = JSONObject().put("body", "secret123")
        val s = RiskScorer.score(
            spec(Effect.READ), obs(Fact.Source.SYSTEM_API),
            listOf("secret123"), args, "read something",
        )
        assertEquals(0, s.taintExposure)
    }

    @Test
    fun `untraced args add risk`() {
        val args = JSONObject().put("url", "http://evil.com/steal")
        val s = RiskScorer.score(
            spec(Effect.EGRESS), obs(Fact.Source.SYSTEM_API),
            emptyList(), args, "search the web",
        )
        assertTrue(s.traceability > 0)
    }

    @Test
    fun `traced args score zero traceability`() {
        val args = JSONObject().put("query", "weather today")
        val s = RiskScorer.score(
            spec(Effect.EGRESS), obs(Fact.Source.SYSTEM_API),
            emptyList(), args, "weather today",
        )
        assertEquals(0, s.traceability)
    }

    @Test
    fun `score capped at 100`() {
        val args = JSONObject()
            .put("to", "unknown_number")
            .put("body", "secret_data")
            .put("extra", "more_untraced")
        val s = RiskScorer.score(
            spec(Effect.EGRESS), obs(Fact.Source.ACCESSIBILITY_TREE),
            listOf("secret_data"), args, "send sms",
        )
        assertTrue(s.total <= 100)
    }

    @Test
    fun `score serializes to json`() {
        val s = RiskScorer.Score(65, 40, 25, 0, 0)
        val j = s.toJson()
        assertEquals(65, j.getInt("total"))
        assertEquals(40, j.getInt("effect"))
        assertEquals(25, j.getInt("obs"))
        assertEquals(0, j.getInt("taint"))
        assertEquals(0, j.getInt("trace"))
    }

    @Test
    fun `empty args scores zero traceability`() {
        val s = RiskScorer.score(
            spec(Effect.EGRESS), obs(Fact.Source.SYSTEM_API),
            emptyList(), JSONObject(), "search web",
        )
        assertEquals(0, s.traceability)
    }

    @Test
    fun `short arg values ignored for traceability`() {
        val args = JSONObject().put("on", "true")
        val s = RiskScorer.score(
            spec(Effect.MUTATE), obs(Fact.Source.SYSTEM_API),
            emptyList(), args, "toggle wifi",
        )
        assertEquals(0, s.traceability)
    }
}
