package com.agent.jarvis.harness

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NLI prompt/parse (pure), the LlmNliHead failure modes, and MemoryClaims.writeVerified over fake DAOs. */
class LlmNliHeadTest {

    // ── NliPrompt ──
    @Test fun promptIsNoThinkChatMlWithBothSides() {
        val p = NliPrompt.build("The user lives in Paris.", "The user lives in Rome.")
        assertTrue(p.startsWith("<|im_start|>system\n"))
        assertTrue(p.contains("PREMISE: The user lives in Paris."))
        assertTrue(p.contains("HYPOTHESIS: The user lives in Rome."))
        assertTrue(p.contains("/no_think"))
        assertTrue(p.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test fun promptClipsAndFlattensLongSides() {
        val p = NliPrompt.build("a\n\nb ".repeat(500), "x".repeat(5000))
        assertFalse(p.contains("\n\nb"))
        assertTrue(p.length < 1500)
    }

    @Test fun parseTakesTheLabelCaseInsensitively() {
        assertEquals("ENTAILMENT", NliPrompt.parse("ENTAILMENT"))
        assertEquals("CONTRADICTION", NliPrompt.parse("  contradiction.\n"))
        assertEquals("NEUTRAL", NliPrompt.parse("Neutral<|im_end|>"))
    }

    @Test fun parseTakesTheFirstLabelOnly() {
        assertEquals("CONTRADICTION", NliPrompt.parse("CONTRADICTION (not ENTAILMENT)"))
        assertEquals("ENTAILMENT", NliPrompt.parse("ENTAILMENT or NEUTRAL"))
    }

    @Test fun parseDefaultsToNeutralOnGarbage() {
        assertEquals("NEUTRAL", NliPrompt.parse(null))
        assertEquals("NEUTRAL", NliPrompt.parse(""))
        assertEquals("NEUTRAL", NliPrompt.parse("   "))
        assertEquals("NEUTRAL", NliPrompt.parse("I cannot determine that."))
        // Not a whole word: must not match.
        assertEquals("NEUTRAL", NliPrompt.parse("ANTIENTAILMENTS"))
    }

    @Test fun parseIgnoresAReasoningTrace() {
        assertEquals("CONTRADICTION",
            NliPrompt.parse("<think>maybe ENTAILMENT?</think>\nCONTRADICTION"))
        assertEquals("NEUTRAL", NliPrompt.parse("<think>ENTAILMENT, I think"))
    }

    // ── LlmNliHead ──
    @Test fun headReturnsTheParsedLabel() {
        var seen = ""
        val head = LlmNliHead(generate = { prompt, max ->
            seen = prompt; assertEquals(NliPrompt.MAX_TOKENS, max); Result.success("CONTRADICTION") })
        assertEquals("CONTRADICTION", head.classify("p", "h"))
        assertTrue(seen.contains("PREMISE: p"))
    }

    @Test fun headIsNeutralOnFailureThrowAndTimeout() {
        assertEquals("NEUTRAL",
            LlmNliHead(generate = { _, _ -> Result.failure(IllegalStateException("no model")) }).classify("p", "h"))
        assertEquals("NEUTRAL",
            LlmNliHead(generate = { _, _ -> throw RuntimeException("boom") }).classify("p", "h"))
        assertEquals("NEUTRAL",
            LlmNliHead(timeoutMs = 50, generate = { _, _ -> delay(5_000); Result.success("ENTAILMENT") })
                .classify("p", "h"))
    }

    // ── writeVerified ──
    private class FakeClaimDao : ClaimDao {
        val rows = LinkedHashMap<String, ClaimEntity>()
        override suspend fun upsert(c: ClaimEntity) { rows[c.id] = c }
        override suspend fun byId(id: String) = rows[id]
        override suspend fun active() = rows.values.filter { it.status == "active" }
        override suspend fun markSuperseded(id: String, now: Long) {
            rows[id]?.let { rows[id] = it.copy(status = "superseded", invalidatedAt = now) }
        }
        override suspend fun markRetracted(id: String, now: Long) {
            rows[id]?.let { rows[id] = it.copy(status = "retracted", invalidatedAt = now) }
        }
        override suspend fun reinforce(id: String, c: Double, now: Long): Int {
            val r = rows[id]?.takeIf { it.status == "active" } ?: return 0
            rows[id] = r.copy(confidence = c, reinforcedAt = now); return 1
        }
    }

    private class FakeEmbDao(private val claims: FakeClaimDao) : EmbeddingDao {
        val rows = LinkedHashMap<String, MemoryEmbeddingEntity>()
        override suspend fun upsert(e: MemoryEmbeddingEntity) { rows[e.claimId] = e }
        override suspend fun byClaim(claimId: String) = rows[claimId]
        override suspend fun allActiveWithEmbedding() =
            rows.values.filter { claims.rows[it.claimId]?.status == "active" }
        override suspend fun delete(claimId: String): Int = if (rows.remove(claimId) != null) 1 else 0
    }

    private class MapNli(private val byPremise: Map<String, String>) : NliHead {
        override fun classify(premise: String, hypothesis: String) =
            byPremise[premise] ?: ClaimVerifier.NEUTRAL
    }

    private val v1 = floatArrayOf(1f, 0f)

    private suspend fun seed(
        mc: MemoryClaims, emb: FakeEmbDao, text: String, vec: FloatArray = v1,
    ): String {
        val id = mc.write(text, "fact", "user", "msg:seed")
        emb.upsert(MemoryEmbeddingEntity(id, "m", Embeddings.toBytes(vec)))
        return id
    }

    @Test fun passWritesAndStoresTheEmbedding() = runBlocking {
        val dao = FakeClaimDao(); val emb = FakeEmbDao(dao); val mc = MemoryClaims(dao)
        val out = mc.writeVerified("user likes tea", "preference", "user", "msg:1",
            nli = MapNli(emptyMap()), embeddings = emb, embedding = v1, modelName = "m", log = {})
        out as MemoryClaims.WriteOutcome.Written
        assertTrue(out.superseded.isEmpty())
        assertEquals("user likes tea", dao.rows[out.id]!!.text)
        assertTrue(Embeddings.fromBytes(emb.rows[out.id]!!.embedding).contentEquals(v1))
    }

    @Test fun contradictionFromUserSupersedesTheStaleClaim() = runBlocking {
        val dao = FakeClaimDao(); val emb = FakeEmbDao(dao); val mc = MemoryClaims(dao)
        val old = seed(mc, emb, "user lives in Paris")
        val out = mc.writeVerified("user lives in Rome", "correction", "user", "msg:2",
            nli = MapNli(mapOf("user lives in Paris" to ClaimVerifier.CONTRADICTION)),
            embeddings = emb, embedding = v1, modelName = "m", log = {})
        out as MemoryClaims.WriteOutcome.Written
        assertEquals(listOf(old), out.superseded)
        assertEquals("superseded", dao.rows[old]!!.status)
        assertEquals(old, dao.rows[out.id]!!.supersedes)
        assertEquals("active", dao.rows[out.id]!!.status)
    }

    @Test fun oneCorrectionRetiresSeveralContradictedClaims() = runBlocking {
        val dao = FakeClaimDao(); val emb = FakeEmbDao(dao); val mc = MemoryClaims(dao)
        val a = seed(mc, emb, "user lives in Paris")
        val b = seed(mc, emb, "user's home city is Paris")
        val nli = MapNli(mapOf("user lives in Paris" to ClaimVerifier.CONTRADICTION,
            "user's home city is Paris" to ClaimVerifier.CONTRADICTION))
        val out = mc.writeVerified("user lives in Rome", "correction", "user", "msg:2",
            nli = nli, embeddings = emb, embedding = v1, modelName = "m", log = {})
        out as MemoryClaims.WriteOutcome.Written
        assertEquals(setOf(a, b), out.superseded.toSet())
        assertEquals("superseded", dao.rows[a]!!.status)
        assertEquals("superseded", dao.rows[b]!!.status)
    }

    @Test fun entailmentOfANearDuplicateReinforcesAndWritesNothing() = runBlocking {
        val dao = FakeClaimDao(); val emb = FakeEmbDao(dao); val mc = MemoryClaims(dao)
        val old = seed(mc, emb, "user likes tea")
        val before = dao.rows[old]!!.confidence
        val out = mc.writeVerified("the user likes tea", "preference", "user", "msg:3",
            nli = MapNli(mapOf("user likes tea" to ClaimVerifier.ENTAILMENT)),
            embeddings = emb, embedding = v1, modelName = "m", log = {})
        assertEquals(MemoryClaims.WriteOutcome.Reinforced(old), out)
        assertEquals(1, dao.rows.size)
        assertTrue(dao.rows[old]!!.confidence > before)
    }

    @Test fun failSkipsAndLogs() = runBlocking {
        val dao = FakeClaimDao(); val emb = FakeEmbDao(dao); val mc = MemoryClaims(dao)
        seed(mc, emb, "user lives in Paris")
        val logs = ArrayList<String>()
        // Ungrounded agent inference that contradicts memory.
        val out = mc.writeVerified("user lives in Rome", "observation", "agent", "msg:4",
            nli = MapNli(mapOf("user lives in Paris" to ClaimVerifier.CONTRADICTION)),
            embeddings = emb, embedding = v1, modelName = "m", log = { logs += it })
        assertTrue(out is MemoryClaims.WriteOutcome.Rejected)
        assertEquals(1, dao.rows.size)
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("rejected"))
    }

    @Test fun groundedClaimWithUnresolvedEvidenceIsRejected() = runBlocking {
        val dao = FakeClaimDao(); val emb = FakeEmbDao(dao); val mc = MemoryClaims(dao)
        val out = mc.writeVerified("user said x happened", "finding", "agent", "msg:5",
            nli = MapNli(emptyMap()), embeddings = emb, grounded = true,
            evidenceHandles = listOf("msg:gone"), resolveHandle = { null }, log = {})
        assertTrue(out is MemoryClaims.WriteOutcome.Rejected)
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun noEmbeddingMeansNoNeighborsAndNoStoredVector() = runBlocking {
        val dao = FakeClaimDao(); val emb = FakeEmbDao(dao); val mc = MemoryClaims(dao)
        seed(mc, emb, "user lives in Paris")
        // Would be CONTRADICTION if consulted, but an empty vector relates to nothing.
        val out = mc.writeVerified("user lives in Rome", "observation", "agent", "msg:6",
            nli = MapNli(mapOf("user lives in Paris" to ClaimVerifier.CONTRADICTION)),
            embeddings = emb, log = {})
        out as MemoryClaims.WriteOutcome.Written
        assertEquals(1, emb.rows.size)
    }
}
