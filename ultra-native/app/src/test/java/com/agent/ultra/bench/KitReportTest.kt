package com.agent.ultra.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KitReportTest {
    private val line = "I/UltraLlm( 123): gen tokens=180 ms=9000 tokPerSec=20.0 majfaults=3 availMB=900 model=x.gguf cpu=i8mm threads=6"

    @Test fun parsesGenLine() = assertEquals(KitReport.Gen(180, 9000, 20.0, 3, 900), KitReport.parseGen(line))
    @Test fun parsesCommaDecimal() = assertEquals(5.3, KitReport.parseGen("gen tokens=10 ms=1886 tokPerSec=5,3 majfaults=0 availMB=1")!!.tokPerSec, 1e-9)
    @Test fun missingFieldsAreMinusOne() = assertEquals(-1L, KitReport.parseGen("gen tokens=1 ms=2 tokPerSec=0.5")!!.availMb)
    @Test fun otherLinesIgnored() = assertNull(KitReport.parseGen("I/UltraLlm: cpu variant ultra_llm_i8mm"))
    @Test fun mainGenIsLongest() = assertEquals(180, KitReport.mainGen(listOf("gen tokens=12 ms=1 tokPerSec=1.0", line))!!.tokens)
    @Test fun mainGenNoneIsNull() = assertNull(KitReport.mainGen(listOf("nothing")))

    @Test fun keepWhenFastAndSteady() = assertEquals(KitReport.Model.KEEP, KitReport.modelVerdict(listOf(4.0, 7.0, 7.0, 6.5, 6.8), false).first)
    @Test fun smallerWhenSlow() = assertEquals(KitReport.Model.TRY_SMALLER, KitReport.modelVerdict(listOf(3.0, 3.5, 3.2, 3.1), false).first)
    @Test fun smallerWhenSlowingDown() = assertEquals(KitReport.Model.TRY_SMALLER, KitReport.modelVerdict(listOf(8.0, 9.0, 8.0, 7.0, 5.5), false).first)
    @Test fun smallerWhenCrashed() = assertEquals(KitReport.Model.TRY_SMALLER, KitReport.modelVerdict(listOf(9.0, 9.0, 9.0), true).first)
    @Test fun tooFewAnswers() = assertEquals(KitReport.Model.NOT_ENOUGH_DATA, KitReport.modelVerdict(listOf(9.0, null, null), false).first)
    @Test fun unmeasuredQuestionsSkipped() = assertEquals(KitReport.Model.KEEP, KitReport.modelVerdict(listOf(null, 6.0, 6.0, 6.0), false).first)

    @Test fun logcatSinceFormat() = assertEquals("1700000000.005", KitReport.logcatSince(1_700_000_000_005))
    @Test fun crashDetected() = assertTrue(KitReport.hasCrash(listOf("E/AndroidRuntime: FATAL EXCEPTION: main")))
    @Test fun noCrash() = assertFalse(KitReport.hasCrash(listOf("I/UltraLlm: ok")))
}
