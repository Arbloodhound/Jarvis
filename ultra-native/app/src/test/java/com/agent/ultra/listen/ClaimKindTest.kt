package com.agent.ultra.listen

import com.agent.ultra.harness.MemoryClaimScoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimKindTest {
    @Test fun onlyDurableKnowledgePromotes() {
        assertEquals("fact", ClaimKind.of(NoteKind.FACT))
        assertEquals("world", ClaimKind.of(NoteKind.PERSON))
        // Time-bound tasks and evidence-bearing notes are NOT promoted to durable claims.
        assertNull(ClaimKind.of(NoteKind.PLAN))
        assertNull(ClaimKind.of(NoteKind.TODO))
        assertNull(ClaimKind.of(NoteKind.OBSERVATION))
        assertNull(ClaimKind.of(NoteKind.SUMMARY))
        assertTrue(ClaimKind.promotes(NoteKind.FACT))
        assertFalse(ClaimKind.promotes(NoteKind.TODO))
    }

    @Test fun everyPromotedKindIsARealClaimKind() {
        for (k in NoteKind.entries) ClaimKind.of(k)?.let {
            assertTrue("$k → $it must be a valid claim kind", it in MemoryClaimScoring.KINDS)
        }
    }

    @Test fun museBankCategoriesAreAvailableClaimKinds() {
        // The four memory-bank categories are folded onto the claim kind dimension.
        for (c in listOf("experience", "opinion", "reflection", "world"))
            assertTrue("$c is a claim kind", c in MemoryClaimScoring.KINDS)
    }
}
