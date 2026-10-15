package com.agent.ultra.harness

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlin.math.exp
import kotlin.math.ln2

/**
 * Memory claims — the unit the agent remembers. Not blobs: each claim has a
 * kind, salience, confidence, the exact source message, and a supersede
 * chain. Newer claims invalidate older ones; history is preserved.
 *
 * Retrieval: hybrid keyword scoring blended with a confidence prior, times
 * a query-time temporal-decay factor (30-day half-life; evergreen exempt).
 *
 * DB wiring (repo session): add ClaimEntity to UltraDatabase entities,
 * bump version, add migration. This file never touches existing files.
 */
@Entity(
    tableName = "memory_claims",
    indices = [Index("status"), Index("kind")],
)
data class ClaimEntity(
    @PrimaryKey val id: String,
    val text: String,
    /** preference|fact|finding|request|completion|correction|observation */
    val kind: String,
    /** low|medium|high */
    val salience: String,
    val confidence: Double,
    /** user|agent */
    val speaker: String,
    /** exact message id / reference the claim came from */
    val sourceMessage: String,
    val quote: String = "",
    /** id of the claim this replaces, "" when none */
    val supersedes: String = "",
    val firstSeen: Long = System.currentTimeMillis(),
    val reinforcedAt: Long = System.currentTimeMillis(),
    /** active|superseded|retracted */
    val status: String = "active",
    /** curated claims never decay */
    val evergreen: Boolean = false,
    /** set when superseded/retracted; 0 when never */
    val invalidatedAt: Long = 0L,
)

@Dao
interface ClaimDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: ClaimEntity)

    @Query("SELECT * FROM memory_claims WHERE id = :id")
    suspend fun byId(id: String): ClaimEntity?

    @Query("SELECT * FROM memory_claims WHERE status = 'active'")
    suspend fun active(): List<ClaimEntity>

    @Query("UPDATE memory_claims SET status = 'superseded', invalidatedAt = :now WHERE id = :id")
    suspend fun markSuperseded(id: String, now: Long)

    @Query("UPDATE memory_claims SET status = 'retracted', invalidatedAt = :now WHERE id = :id")
    suspend fun markRetracted(id: String, now: Long)

    @Query("UPDATE memory_claims SET confidence = :c, reinforcedAt = :now WHERE id = :id AND status = 'active'")
    suspend fun reinforce(id: String, c: Double, now: Long): Int
}

/** Query-time scoring. Pure logic; no Android dependencies. */
object MemoryClaimScoring {
    const val HALF_LIFE_DAYS = 30.0
    const val TEXT_WEIGHT = 0.7
    const val PRIOR_WEIGHT = 0.3

    val KINDS = setOf("preference", "fact", "finding", "request",
        "completion", "correction", "observation")

    /** 1.0 for evergreen or fresh; halves every HALF_LIFE_DAYS. */
    fun temporalFactor(c: ClaimEntity, nowMs: Long = System.currentTimeMillis()): Double {
        if (c.evergreen) return 1.0
        val ageDays = (nowMs - c.reinforcedAt) / 86_400_000.0
        if (ageDays <= 0) return 1.0
        return exp(-ln2() * ageDays / HALF_LIFE_DAYS)
    }

    private fun tokens(s: String) = s.lowercase().split(Regex("\\s+"))
        .filter { it.length > 2 }.toSet()

    /** Fraction of query content-words appearing in the claim. */
    fun keywordScore(query: String, c: ClaimEntity): Double {
        val q = tokens(query)
        if (q.isEmpty()) return 0.0
        val d = tokens(c.text + " " + c.quote)
        return (q intersect d).size.toDouble() / q.size
    }

    /** (TEXT_WEIGHT * keyword + PRIOR_WEIGHT * confidence) * temporal factor. */
    fun score(query: String, c: ClaimEntity, nowMs: Long = System.currentTimeMillis()): Double {
        val kw = keywordScore(query, c)
        if (kw == 0.0) return 0.0
        return (TEXT_WEIGHT * kw + PRIOR_WEIGHT * c.confidence) * temporalFactor(c, nowMs)
    }
}

/** Claim lifecycle over a [ClaimDao]. Suspend functions; call off the main thread. */
class MemoryClaims(private val dao: ClaimDao) {

    suspend fun write(
        text: String, kind: String, speaker: String, sourceMessage: String,
        salience: String = "medium", confidence: Double = 0.7,
        quote: String = "", supersedes: String = "", evergreen: Boolean = false,
    ): String {
        require(kind in MemoryClaimScoring.KINDS) { "bad kind: $kind" }
        require(salience in setOf("low", "medium", "high")) { "bad salience" }
        require(confidence in 0.0..1.0 && confidence > 0) { "bad confidence" }
        val now = System.currentTimeMillis()
        val id = "claim:" + java.util.UUID.randomUUID().toString().take(8)
        if (supersedes.isNotEmpty()) dao.markSuperseded(supersedes, now)
        dao.upsert(ClaimEntity(id = id, text = text, kind = kind, salience = salience,
            confidence = confidence, speaker = speaker, sourceMessage = sourceMessage,
            quote = quote, supersedes = supersedes, firstSeen = now,
            reinforcedAt = now, evergreen = evergreen))
        return id
    }

    suspend fun reinforce(id: String, boost: Double = 0.05): Boolean {
        val c = dao.byId(id) ?: return false
        if (c.status != "active") return false
        val now = System.currentTimeMillis()
        return dao.reinforce(id, (c.confidence + boost).coerceAtMost(1.0), now) > 0
    }

    suspend fun retract(id: String): Boolean {
        val c = dao.byId(id) ?: return false
        dao.markRetracted(id, System.currentTimeMillis())
        return true
    }

    /** Best-first (claim, score) pairs. */
    suspend fun searchScored(query: String, kind: String? = null,
                             minScore: Double = 0.0): List<Pair<ClaimEntity, Double>> {
        val now = System.currentTimeMillis()
        return dao.active()
            .filter { kind == null || it.kind == kind }
            .map { it to MemoryClaimScoring.score(query, it, now) }
            .filter { it.second >= minScore && it.second > 0 }
            .sortedWith(compareByDescending<Pair<ClaimEntity, Double>> { it.second }
                .thenBy { it.first.id })
    }

    suspend fun search(query: String, kind: String? = null) =
        searchScored(query, kind).map { it.first }

    /** Full chain: the claim plus everything it superseded. */
    suspend fun history(id: String): List<ClaimEntity> {
        val chain = mutableListOf<ClaimEntity>()
        val seen = mutableSetOf<String>()
        var cur: String? = id
        while (cur != null && cur !in seen) {
            seen += cur
            val c = dao.byId(cur) ?: break
            chain += c
            cur = c.supersedes.ifEmpty { null }
        }
        return chain
    }
}
