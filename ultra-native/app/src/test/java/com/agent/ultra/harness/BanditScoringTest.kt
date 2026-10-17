package com.agent.ultra.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for bandit arm scoring and ranking. */
class BanditScoringTest {

    private fun arm(lane: String, reward: Long, obs: Long, fastSum: Double, fastObs: Long, decisions: Long = 0) =
        BanditArmEntity(
            policyId = "ideas", domain = "cycling", lane = lane,
            decisionPoints = decisions, terminalRewards = reward, terminalObservations = obs,
            fastRewardSum = fastSum, fastObservations = fastObs)

    @Test fun fastMeanAndTerminalRate() {
        val a = arm("a", reward = 3, obs = 4, fastSum = 2.0, fastObs = 4)
        assertEquals(0.5, BanditScoring.fastMean(a), 1e-9)
        assertEquals(0.75, BanditScoring.terminalRate(a), 1e-9)
        val fresh = arm("fresh", 0, 0, 0.0, 0)
        assertEquals(0.0, BanditScoring.fastMean(fresh), 1e-9)
        assertEquals(0.0, BanditScoring.terminalRate(fresh), 1e-9)
    }

    @Test fun unobservedArmSortsFirstForExploration() {
        val observed = arm("observed", reward = 5, obs = 5, fastSum = 5.0, fastObs = 5, decisions = 10)
        val fresh = arm("fresh", 0, 0, 0.0, 0, decisions = 10)
        assertEquals(Double.MAX_VALUE, BanditScoring.ucb(fresh, 20), 0.0)
        assertEquals(listOf("fresh", "observed"), BanditScoring.rank(listOf(observed, fresh)).map { it.lane })
    }

    @Test fun higherFastMeanRanksHigherAmongObservedArms() {
        val good = arm("good", reward = 8, obs = 10, fastSum = 6.0, fastObs = 10, decisions = 10)
        val bad = arm("bad", reward = 2, obs = 10, fastSum = -4.0, fastObs = 10, decisions = 10)
        assertTrue(BanditScoring.ucb(good, 20) > BanditScoring.ucb(bad, 20))
        assertEquals(listOf("good", "bad"), BanditScoring.rank(listOf(bad, good)).map { it.lane })
    }

    @Test fun rewardAndPenalizeDeltasAreConsistent() {
        // Model the three ops against the entity directly (DAO is covered under Room).
        var a = arm("a", 0, 0, 0.0, 0, 0)
        // reward
        a = a.copy(terminalRewards = a.terminalRewards + 1, terminalObservations = a.terminalObservations + 1,
            fastRewardSum = a.fastRewardSum + 1.0, fastObservations = a.fastObservations + 1)
        // penalize
        a = a.copy(terminalObservations = a.terminalObservations + 1,
            fastRewardSum = a.fastRewardSum - 1.0, fastObservations = a.fastObservations + 1)
        assertEquals(1L, a.terminalRewards)
        assertEquals(2L, a.terminalObservations)
        assertEquals(0.0, a.fastRewardSum, 1e-9)
        assertEquals(2L, a.fastObservations)
        assertEquals(0.5, BanditScoring.terminalRate(a), 1e-9)
        assertEquals(0.0, BanditScoring.fastMean(a), 1e-9)
    }
}
