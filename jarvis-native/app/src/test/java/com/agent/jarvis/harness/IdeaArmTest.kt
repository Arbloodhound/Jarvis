package com.agent.jarvis.harness

import org.junit.Assert.assertEquals
import org.junit.Test

class IdeaArmTest {
    @Test fun domainIsThePrimaryTopic() {
        assertEquals("cook", IdeaArm.domain("Cook more at home"))
        assertEquals("cook", IdeaArm.domain("  cook  dinner "))
        assertEquals("general", IdeaArm.domain("   "))
    }

    @Test fun laneBandsFeasibility() {
        assertEquals("high", IdeaArm.lane(0.9))
        assertEquals("high", IdeaArm.lane(0.66))
        assertEquals("med", IdeaArm.lane(0.5))
        assertEquals("low", IdeaArm.lane(0.1))
    }

    @Test fun rankFavoursArmsWithBetterFastMean() {
        // Same exploration state, different learned reward → higher fast mean ranks first.
        val good = BanditArmEntity("ideas", "cook", "high", fastRewardSum = 3.0, fastObservations = 3, terminalObservations = 3)
        val bad = BanditArmEntity("ideas", "spend", "high", fastRewardSum = -3.0, fastObservations = 3, terminalObservations = 3)
        assertEquals(listOf("cook", "spend"), BanditScoring.rank(listOf(bad, good)).map { it.domain })
    }
}
