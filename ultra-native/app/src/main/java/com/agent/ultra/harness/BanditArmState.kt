package com.agent.ultra.harness

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Contextual-bandit arm state — the counters behind "which proactive card /
 * policy lane is worth surfacing". An arm is a (policy, domain, lane) triple.
 *
 * Counters:
 *  - decision_points        — times this arm was a candidate the agent decided on.
 *  - terminal_rewards       — terminal outcomes that were rewarded.
 *  - terminal_observations  — terminal outcomes seen (rewarded or penalised).
 *  - fast_reward_sum        — fast (recency-weighted) signed reward signal.
 *  - fast_observations      — fast outcomes seen (denominator of the fast mean).
 *
 * [reward] records a +1 terminal reward; [penalize] a terminal observation with a
 * −1 fast signal; [decision] a decision point. Each terminal outcome is also a
 * terminal observation and a fast observation, so the terminal success rate
 * (terminal_rewards / terminal_observations) and the fast mean
 * (fast_reward_sum / fast_observations) are both well-formed.
 *
 * Pure ranking lives in [BanditScoring] (JVM-tested); the DAO is exercised under
 * Room. Which model/policy actually runs on-device is injected by the caller;
 * device behaviour is SOURCE-FIXED BUT RUNTIME-UNPROVEN.
 */
@Entity(
    tableName = "bandit_arm_state",
    primaryKeys = ["policy_id", "domain", "lane"],
)
data class BanditArmEntity(
    @ColumnInfo(name = "policy_id") val policyId: String,
    @ColumnInfo(name = "domain") val domain: String,
    @ColumnInfo(name = "lane") val lane: String,
    @ColumnInfo(name = "decision_points") val decisionPoints: Long = 0,
    @ColumnInfo(name = "terminal_rewards") val terminalRewards: Long = 0,
    @ColumnInfo(name = "terminal_observations") val terminalObservations: Long = 0,
    @ColumnInfo(name = "fast_reward_sum") val fastRewardSum: Double = 0.0,
    @ColumnInfo(name = "fast_observations") val fastObservations: Long = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface BanditArmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(a: BanditArmEntity)

    @Query("SELECT * FROM bandit_arm_state WHERE policy_id = :policy AND domain = :domain AND lane = :lane")
    suspend fun get(policy: String, domain: String, lane: String): BanditArmEntity?

    @Query("SELECT * FROM bandit_arm_state WHERE policy_id = :policy AND domain = :domain")
    suspend fun armsFor(policy: String, domain: String): List<BanditArmEntity>

    @Query("SELECT * FROM bandit_arm_state")
    suspend fun all(): List<BanditArmEntity>

    @Query("DELETE FROM bandit_arm_state")
    suspend fun clear()
}

/** Pure ranking over bandit counters. No Android deps. */
object BanditScoring {
    /** Recent signed reward per observation; 0 when never observed. */
    fun fastMean(a: BanditArmEntity): Double =
        a.fastRewardSum / maxOf(1L, a.fastObservations)

    /** Terminal success rate in [0,1]; 0 when never observed. */
    fun terminalRate(a: BanditArmEntity): Double =
        if (a.terminalObservations == 0L) 0.0
        else a.terminalRewards.toDouble() / a.terminalObservations

    /**
     * UCB1 score: fast mean plus an exploration bonus that shrinks as an arm is
     * observed. [totalObservations] is the sum of fast observations across the arms
     * ranked together — the same counter as the per-arm denominator, so the bonus
     * is a well-formed UCB1 ratio (decision points track "shown" and are not mixed
     * in here). An unobserved arm scores [Double.MAX_VALUE] so it is tried first.
     */
    fun ucb(a: BanditArmEntity, totalObservations: Long): Double {
        if (a.fastObservations == 0L) return Double.MAX_VALUE
        val bonus = sqrt(2.0 * ln(maxOf(2.0, totalObservations.toDouble())) / a.fastObservations)
        return fastMean(a) + bonus
    }

    /** Arms best-first by UCB; ties broken by lane for a stable order. */
    fun rank(arms: List<BanditArmEntity>): List<BanditArmEntity> {
        val total = arms.sumOf { it.fastObservations }
        return arms.sortedWith(
            compareByDescending<BanditArmEntity> { ucb(it, total) }.thenBy { it.lane },
        )
    }
}

/** Arm-state lifecycle over a [BanditArmDao]. Suspend; call off the main thread. */
class BanditArmState(private val dao: BanditArmDao) {

    private suspend fun mutate(
        policy: String, domain: String, lane: String, now: Long,
        f: (BanditArmEntity) -> BanditArmEntity,
    ) {
        val cur = dao.get(policy, domain, lane)
            ?: BanditArmEntity(policyId = policy, domain = domain, lane = lane, updatedAt = now)
        dao.upsert(f(cur).copy(updatedAt = now))
    }

    /** A rewarded terminal outcome: +1 reward, +1 observation (terminal and fast). */
    suspend fun reward(policy: String, domain: String, lane: String, now: Long = System.currentTimeMillis()) =
        mutate(policy, domain, lane, now) {
            it.copy(
                terminalRewards = it.terminalRewards + 1,
                terminalObservations = it.terminalObservations + 1,
                fastRewardSum = it.fastRewardSum + 1.0,
                fastObservations = it.fastObservations + 1,
            )
        }

    /** A penalised terminal outcome: +1 observation (terminal and fast), −1 fast signal. */
    suspend fun penalize(policy: String, domain: String, lane: String, now: Long = System.currentTimeMillis()) =
        mutate(policy, domain, lane, now) {
            it.copy(
                terminalObservations = it.terminalObservations + 1,
                fastRewardSum = it.fastRewardSum - 1.0,
                fastObservations = it.fastObservations + 1,
            )
        }

    /** A decision point: this arm was a candidate the agent decided on. */
    suspend fun decision(policy: String, domain: String, lane: String, now: Long = System.currentTimeMillis()) =
        mutate(policy, domain, lane, now) { it.copy(decisionPoints = it.decisionPoints + 1) }

    suspend fun get(policy: String, domain: String, lane: String) = dao.get(policy, domain, lane)

    /** Arms of one policy+domain, best-first by [BanditScoring.rank]. */
    suspend fun ranked(policy: String, domain: String): List<BanditArmEntity> =
        BanditScoring.rank(dao.armsFor(policy, domain))
}

/**
 * Reward hook for the proactive surfaces ([Ideas], [Proactivity]) — kept separate
 * so neither of those files changes. The surface's own call site (ViewModel /
 * worker) maps a user outcome to an arm update: an idea acted on, or a
 * proactivity send the user engaged with, is a [reward]; an idea dismissed, or a
 * suggestion ignored, is a [penalize]; showing a card is a [decision].
 *
 * Policy and lane are injected so the caller controls the arm partition (e.g.
 * policy "ideas", domain = the idea's primary topic, lane = a feasibility band).
 */
object BanditFeedback {
    const val POLICY_IDEAS = "ideas"
    const val POLICY_PROACTIVITY = "proactivity"

    /** Idea acted on → reward its (policy, domain, lane). */
    suspend fun onIdeaActed(state: BanditArmState, domain: String, lane: String) =
        state.reward(POLICY_IDEAS, domain, lane)

    /** Idea dismissed → penalize its arm. */
    suspend fun onIdeaDismissed(state: BanditArmState, domain: String, lane: String) =
        state.penalize(POLICY_IDEAS, domain, lane)

    /** Idea shown to the user → a decision point on its arm. */
    suspend fun onIdeaShown(state: BanditArmState, domain: String, lane: String) =
        state.decision(POLICY_IDEAS, domain, lane)

    /** A proactivity suggestion the user engaged with → reward. */
    suspend fun onProactivityEngaged(state: BanditArmState, topic: String, lane: String) =
        state.reward(POLICY_PROACTIVITY, topic, lane)

    /** A proactivity suggestion sent but ignored → penalize. */
    suspend fun onProactivityIgnored(state: BanditArmState, topic: String, lane: String) =
        state.penalize(POLICY_PROACTIVITY, topic, lane)

    /** A proactivity candidate decided on → decision point. */
    suspend fun onProactivityDecided(state: BanditArmState, topic: String, lane: String) =
        state.decision(POLICY_PROACTIVITY, topic, lane)
}
