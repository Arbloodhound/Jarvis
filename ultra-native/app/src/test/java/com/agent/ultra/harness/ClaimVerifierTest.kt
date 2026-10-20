package com.agent.ultra.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the two-lens claim verifier: cosine math, blob round-trip, every verdict branch. */
class ClaimVerifierTest {

    private class FakeNli(private val byPremise: Map<String, String>,
                          private val default: String = ClaimVerifier.NEUTRAL) : NliHead {
        override fun classify(premise: String, hypothesis: String) = byPremise[premise] ?: default
    }

    private val neutralNli = FakeNli(emptyMap())

    // ── embedding helpers ──
    @Test fun blobRoundTripIsExact() {
        val v = floatArrayOf(0f, 1.5f, -2.25f, 3.0e-3f, 123456.78f)
        val back = Embeddings.fromBytes(Embeddings.toBytes(v))
        assertTrue(v.contentEquals(back))
    }

    @Test fun cosineMath() {
        assertEquals(1.0, Embeddings.cosine(floatArrayOf(1f, 0f, 0f), floatArrayOf(2f, 0f, 0f)), 1e-9)
        assertEquals(0.0, Embeddings.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-9)
        assertEquals(-1.0, Embeddings.cosine(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)), 1e-9)
        assertEquals("size mismatch is 0", 0.0, Embeddings.cosine(floatArrayOf(1f), floatArrayOf(1f, 0f)), 1e-9)
        assertEquals("zero vector is 0", 0.0, Embeddings.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 1e-9)
    }

    // ── lens (a): evidence_grounded ──
    @Test fun groundedPassesWhenEvidenceResolvesContainsQuoteAndSupports() {
        val staged = StagedClaim(
            claimText = "user prefers dark mode", quote = "dark mode", speaker = "agent",
            grounded = true, evidenceHandles = listOf("msg:1"), embedding = floatArrayOf(1f, 0f))
        val v = ClaimVerifier.verify(staged,
            resolveHandle = { "The user said they prefer dark mode in settings" },
            nearest = emptyList(), nli = neutralNli)
        assertEquals(ClaimVerifier.Verdict.Pass, v)
    }

    @Test fun groundedFailsOnUnresolvedHandle() {
        val staged = StagedClaim("x happened", "x", "agent", grounded = true,
            evidenceHandles = listOf("msg:gone"), embedding = floatArrayOf(1f))
        val v = ClaimVerifier.verify(staged, resolveHandle = { null }, nearest = emptyList(), nli = neutralNli)
        assertTrue(v is ClaimVerifier.Verdict.Fail)
    }

    @Test fun groundedFailsWhenEvidenceMissingQuote() {
        val staged = StagedClaim("user likes tea", "light mode", "agent", grounded = true,
            evidenceHandles = listOf("msg:1"), embedding = floatArrayOf(1f))
        val v = ClaimVerifier.verify(staged, resolveHandle = { "the user likes tea" },
            nearest = emptyList(), nli = neutralNli)
        assertTrue(v is ClaimVerifier.Verdict.Fail)
    }

    @Test fun groundedFailsWhenEvidenceDoesNotSupportClaim() {
        val staged = StagedClaim("user lives in paris france now", quote = "", speaker = "agent",
            grounded = true, evidenceHandles = listOf("msg:1"), embedding = floatArrayOf(1f))
        val v = ClaimVerifier.verify(staged, resolveHandle = { "the weather is nice today" },
            nearest = emptyList(), nli = neutralNli)
        assertTrue(v is ClaimVerifier.Verdict.Fail)
    }

    @Test fun groundedWithNoHandlesFails() {
        val staged = StagedClaim("saw something", "", "agent", grounded = true,
            evidenceHandles = emptyList(), embedding = floatArrayOf(1f))
        val v = ClaimVerifier.verify(staged, resolveHandle = { "anything" }, nearest = emptyList(), nli = neutralNli)
        assertTrue(v is ClaimVerifier.Verdict.Fail)
    }

    // ── lens (b): refute + NLI ──
    @Test fun userCorrectionContradictingMemorySupersedesOld() {
        val staged = StagedClaim("user prefers coffee", speaker = "user", grounded = false,
            embedding = floatArrayOf(1f, 0f))
        val nearest = listOf(NeighborClaim("claim:old", "user prefers tea", "user", floatArrayOf(0.9f, 0.1f)))
        val nli = FakeNli(mapOf("user prefers tea" to ClaimVerifier.CONTRADICTION))
        val v = ClaimVerifier.verify(staged, resolveHandle = { null }, nearest = nearest, nli = nli)
        assertEquals(ClaimVerifier.Verdict.Supersede("claim:old"), v)
    }

    @Test fun ungroundedAgentInferenceContradictingMemoryFails() {
        val staged = StagedClaim("user hates coffee", speaker = "agent", grounded = false,
            embedding = floatArrayOf(1f, 0f))
        val nearest = listOf(NeighborClaim("claim:old", "user loves coffee", "user", floatArrayOf(1f, 0f)))
        val nli = FakeNli(mapOf("user loves coffee" to ClaimVerifier.CONTRADICTION))
        val v = ClaimVerifier.verify(staged, resolveHandle = { null }, nearest = nearest, nli = nli)
        assertTrue(v is ClaimVerifier.Verdict.Fail)
    }

    @Test fun groundedAgentContradictionSupersedesStaleClaim() {
        val staged = StagedClaim(
            claimText = "account is now closed", quote = "", speaker = "agent",
            grounded = true, evidenceHandles = listOf("msg:1"), embedding = floatArrayOf(1f, 0f))
        val nearest = listOf(NeighborClaim("claim:old", "account is open", "agent", floatArrayOf(1f, 0f)))
        val nli = FakeNli(mapOf("account is open" to ClaimVerifier.CONTRADICTION))
        val v = ClaimVerifier.verify(staged,
            resolveHandle = { "the account is now closed per support" }, nearest = nearest, nli = nli)
        assertEquals(ClaimVerifier.Verdict.Supersede("claim:old"), v)
    }

    @Test fun contradictionFromUnrelatedNeighborIsIgnored() {
        val staged = StagedClaim("user bought a kayak", speaker = "user", grounded = false,
            embedding = floatArrayOf(1f, 0f))
        // sim 0 (< REFUTE_SIM): the NLI label on this distant neighbor must not act.
        val nearest = listOf(NeighborClaim("claim:far", "unrelated topic", "user", floatArrayOf(0f, 1f)))
        val nli = FakeNli(mapOf("unrelated topic" to ClaimVerifier.CONTRADICTION))
        val v = ClaimVerifier.verify(staged, resolveHandle = { null }, nearest = nearest, nli = nli)
        assertEquals(ClaimVerifier.Verdict.Pass, v)
    }

    @Test fun entailmentAboveSimThresholdReinforcesOld() {
        val staged = StagedClaim("user enjoys cycling", speaker = "user", grounded = false,
            embedding = floatArrayOf(1f, 0f, 0f))
        val nearest = listOf(NeighborClaim("claim:dup", "user likes cycling", "user", floatArrayOf(1f, 0f, 0f)))
        val nli = FakeNli(mapOf("user likes cycling" to ClaimVerifier.ENTAILMENT))
        val v = ClaimVerifier.verify(staged, resolveHandle = { null }, nearest = nearest, nli = nli)
        assertEquals(ClaimVerifier.Verdict.NoChange("claim:dup"), v)
    }

    @Test fun entailmentBelowSimThresholdPasses() {
        val staged = StagedClaim("user enjoys cycling", speaker = "user", grounded = false,
            embedding = floatArrayOf(1f, 0f, 0f))
        // sim ~0.6 < 0.92: not a near-duplicate, so it is a genuinely new claim.
        val nearest = listOf(NeighborClaim("claim:far", "user likes sport", "user", floatArrayOf(1f, 1.3f, 0f)))
        val nli = FakeNli(mapOf("user likes sport" to ClaimVerifier.ENTAILMENT))
        val v = ClaimVerifier.verify(staged, resolveHandle = { null }, nearest = nearest, nli = nli)
        assertEquals(ClaimVerifier.Verdict.Pass, v)
    }

    @Test fun neutralNeighborsPass() {
        val staged = StagedClaim("user bought a bike", speaker = "user", grounded = false,
            embedding = floatArrayOf(1f, 0f))
        val nearest = listOf(NeighborClaim("claim:x", "user owns a car", "user", floatArrayOf(1f, 0f)))
        val v = ClaimVerifier.verify(staged, resolveHandle = { null }, nearest = nearest, nli = neutralNli)
        assertEquals(ClaimVerifier.Verdict.Pass, v)
    }

    @Test fun onlyTopFiveNeighborsAreConsidered() {
        // Six neighbors; the contradicting one is the FARTHEST, so it falls outside top-5 and is ignored.
        val staged = StagedClaim("claim", speaker = "agent", grounded = false, embedding = floatArrayOf(1f, 0f))
        val near = (0 until 5).map { NeighborClaim("near$it", "near$it", "user", floatArrayOf(1f, 0.01f * it)) }
        val far = NeighborClaim("far", "far", "user", floatArrayOf(0f, 1f)) // sim 0, ranked last
        val nli = FakeNli(mapOf("far" to ClaimVerifier.CONTRADICTION))
        val v = ClaimVerifier.verify(staged, resolveHandle = { null }, nearest = near + far, nli = nli)
        assertEquals(ClaimVerifier.Verdict.Pass, v)
    }
}
