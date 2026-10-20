package com.agent.ultra.harness

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Two-lens claim verifier. Sits in front of [MemoryClaims.write] so a staged
 * claim is checked before it is promoted to durable memory. Pure logic: the
 * embedding model and the NLI head are injected, so the JVM suite exercises
 * every branch without a device.
 *
 * Lens (a) evidence_grounded — a claim that asserts it saw evidence must have
 *   every evidence handle resolve (message body, or source_path:line text),
 *   and the resolved text must carry the quote / support the claim text.
 *   An unresolved handle, or evidence that doesn't back the claim, fails.
 *
 * Lens (b) refute + NLI — over the top-5 nearest existing claims by cosine
 *   similarity, run the NLI head (premise = existing claim, hypothesis =
 *   staged claim):
 *     - CONTRADICTION from a user correction, or from a grounded agent claim
 *       that carries its own evidence → SUPERSEDE the stale claim (never two
 *       contradictory claims active at once).
 *     - CONTRADICTION from an ungrounded agent inference → FAIL (don't keep it).
 *     - ENTAILMENT with similarity > 0.92     → NO_CHANGE, reinforce the old one.
 *     - otherwise                             → PASS.
 *   A label is only acted on when the neighbor is actually related
 *   (cosine ≥ REFUTE_SIM); a distant neighbor in the top-K window is ignored.
 *
 * The on-device NLI head (a small classifier) and the embedding model are the
 * device integration points; they are injected here as [NliHead] and as the
 * [MemoryEmbeddingEntity] blobs that [EmbeddingDao] stores. Device behaviour is
 * SOURCE-FIXED BUT RUNTIME-UNPROVEN.
 */
@Entity(tableName = "memory_embeddings")
data class MemoryEmbeddingEntity(
    @PrimaryKey @ColumnInfo(name = "claim_id") val claimId: String,
    @ColumnInfo(name = "model_name") val modelName: String,
    /** float32[] serialised little-endian; see [Embeddings.toBytes]. */
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
) {
    // Room entities with an array field need value equality for tests and caches.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryEmbeddingEntity) return false
        return claimId == other.claimId && modelName == other.modelName &&
            createdAt == other.createdAt && embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var r = claimId.hashCode()
        r = 31 * r + modelName.hashCode()
        r = 31 * r + embedding.contentHashCode()
        r = 31 * r + createdAt.hashCode()
        return r
    }
}

@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: MemoryEmbeddingEntity)

    @Query("SELECT * FROM memory_embeddings WHERE claim_id = :claimId")
    suspend fun byClaim(claimId: String): MemoryEmbeddingEntity?

    /** Embeddings whose claim is still active — the search space for lens (b). */
    @Query(
        "SELECT e.* FROM memory_embeddings e " +
            "INNER JOIN memory_claims c ON c.id = e.claim_id " +
            "WHERE c.status = 'active'",
    )
    suspend fun allActiveWithEmbedding(): List<MemoryEmbeddingEntity>

    @Query("DELETE FROM memory_embeddings WHERE claim_id = :claimId")
    suspend fun delete(claimId: String): Int
}

/** float32[] ↔ ByteArray (little-endian) and cosine similarity. Pure. */
object Embeddings {
    fun toBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return buf.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        require(b.size % 4 == 0) { "embedding bytes not a multiple of 4" }
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.float }
    }

    /** Cosine similarity in [-1, 1]; 0 when either vector is zero or sizes differ. */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }
}

/** Natural-language-inference head. On-device model injected; tests use a fake. */
interface NliHead {
    /** Returns ENTAILMENT, NEUTRAL, or CONTRADICTION. */
    fun classify(premise: String, hypothesis: String): String
}

/** A claim awaiting promotion, with its evidence handles and embedding. */
data class StagedClaim(
    val claimText: String,
    val quote: String = "",
    /** user|agent */
    val speaker: String,
    /** true when the claim asserts it saw evidence (lens a applies). */
    val grounded: Boolean,
    /** message ids / source_path:line references backing the claim. */
    val evidenceHandles: List<String> = emptyList(),
    val embedding: FloatArray = FloatArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StagedClaim) return false
        return claimText == other.claimText && quote == other.quote &&
            speaker == other.speaker && grounded == other.grounded &&
            evidenceHandles == other.evidenceHandles &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var r = claimText.hashCode()
        r = 31 * r + quote.hashCode()
        r = 31 * r + speaker.hashCode()
        r = 31 * r + grounded.hashCode()
        r = 31 * r + evidenceHandles.hashCode()
        r = 31 * r + embedding.contentHashCode()
        return r
    }
}

/** An existing active claim to compare against, with its embedding. */
data class NeighborClaim(
    val id: String,
    val text: String,
    /** user|agent */
    val speaker: String,
    val embedding: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NeighborClaim) return false
        return id == other.id && text == other.text && speaker == other.speaker &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + text.hashCode()
        r = 31 * r + speaker.hashCode()
        r = 31 * r + embedding.contentHashCode()
        return r
    }
}

object ClaimVerifier {
    const val ENTAILMENT = "ENTAILMENT"
    const val NEUTRAL = "NEUTRAL"
    const val CONTRADICTION = "CONTRADICTION"

    const val TOP_K = 5
    const val REINFORCE_SIM = 0.92
    /** Below this cosine a neighbor is treated as unrelated, so an NLI label on it is ignored. */
    const val REFUTE_SIM = 0.5
    const val SUPPORT_OVERLAP = 0.6

    /** The outcome of verifying a staged claim. */
    sealed class Verdict {
        /** Promote the claim as-is. */
        object Pass : Verdict()
        /** Reject the claim; [reason] explains why. */
        data class Fail(val reason: String) : Verdict()
        /** Promote, marking [oldId] superseded (a user correction). */
        data class Supersede(val oldId: String) : Verdict()
        /** Don't add; reinforce the existing claim [reinforceId] it duplicates. */
        data class NoChange(val reinforceId: String) : Verdict()
    }

    /**
     * @param staged       the claim awaiting promotion.
     * @param resolveHandle resolves an evidence handle to its text, or null if gone.
     * @param nearest      candidate existing claims (lens b ranks them by cosine).
     * @param nli          the NLI head.
     */
    fun verify(
        staged: StagedClaim,
        resolveHandle: (String) -> String?,
        nearest: List<NeighborClaim>,
        nli: NliHead,
    ): Verdict {
        // ── Lens (a): evidence_grounded ──
        if (staged.grounded) {
            if (staged.evidenceHandles.isEmpty()) {
                return Verdict.Fail("grounded claim has no evidence handle")
            }
            val resolved = ArrayList<String>()
            for (h in staged.evidenceHandles) {
                val text = resolveHandle(h)
                    ?: return Verdict.Fail("evidence handle unresolved: $h")
                resolved += text
            }
            if (staged.quote.isNotBlank()) {
                val q = normalize(staged.quote)
                if (resolved.none { normalize(it).contains(q) }) {
                    return Verdict.Fail("evidence does not contain the quote")
                }
            }
            if (resolved.none { supports(it, staged.claimText) }) {
                return Verdict.Fail("evidence does not support the claim")
            }
        }

        // ── Lens (b): refute + NLI over the top-K nearest ──
        val ranked = nearest
            .map { it to Embeddings.cosine(staged.embedding, it.embedding) }
            .sortedByDescending { it.second }
            .take(TOP_K)

        // A CONTRADICTION/ENTAILMENT label is only trusted when the neighbor is
        // actually related (cosine ≥ [REFUTE_SIM]); a distant neighbor that slipped
        // into the top-K window is ignored so an unrelated claim can't trigger a
        // supersede or fail. If a user correction contradicts more than one claim,
        // the nearest is superseded here; the caller re-verifies to catch the rest.
        var entail: Pair<NeighborClaim, Double>? = null
        for ((neighbor, sim) in ranked) {
            if (sim < REFUTE_SIM) continue
            when (nli.classify(neighbor.text, staged.claimText)) {
                CONTRADICTION -> return when {
                    // A user correction, or a grounded agent claim backed by its own
                    // evidence, supersedes the stale claim — never two contradictory
                    // claims active at once.
                    staged.speaker == "user" || staged.grounded -> Verdict.Supersede(neighbor.id)
                    // An ungrounded agent inference that fights memory is dropped.
                    else -> Verdict.Fail("contradicts existing claim ${neighbor.id} without evidence")
                }
                ENTAILMENT -> if (sim > REINFORCE_SIM && entail == null) entail = neighbor to sim
            }
        }
        if (entail != null) return Verdict.NoChange(entail.first.id)
        return Verdict.Pass
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun tokens(s: String) = normalize(s).split(' ')
        .filter { it.length > 2 }.toSet()

    /** The evidence supports the claim when it carries most of the claim's content words. */
    private fun supports(evidence: String, claim: String): Boolean {
        val c = tokens(claim)
        if (c.isEmpty()) return true
        val e = tokens(evidence)
        return (c intersect e).size.toDouble() / c.size >= SUPPORT_OVERLAP
    }
}
