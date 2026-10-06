package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeGatePlacementTest {
    @Test fun clearlyFasterAndMatchingUsesTheAccelerator() =
        assertTrue(WakeGate.useAccelerator(cpuMs = 2.0, accelMs = 0.8, maxDiff = 1e-5f, scale = 30f))
    @Test fun barelyFasterStaysOnCpu() =
        assertFalse(WakeGate.useAccelerator(cpuMs = 2.0, accelMs = 1.8, maxDiff = 0f, scale = 30f))
    @Test fun differentNumbersStayOnCpu() =   // an fp16-relaxed driver would move the wake score
        assertFalse(WakeGate.useAccelerator(cpuMs = 2.0, accelMs = 0.5, maxDiff = 0.5f, scale = 30f))
    @Test fun slowerStaysOnCpu() =
        assertFalse(WakeGate.useAccelerator(cpuMs = 1.0, accelMs = 3.0, maxDiff = 0f, scale = 1f))
    @Test fun diffOfDifferentLengthsIsHuge() =
        assertEquals(Float.MAX_VALUE, WakeGate.maxAbsDiff(floatArrayOf(1f), floatArrayOf(1f, 2f)))
}

class LocalSpeechTest {
    @Test fun oneWordCommandIsKept() =
        assertEquals("flashlight", LocalSpeech.accepted(WhisperNative.Result(" flashlight ", 0.9f)))
    @Test fun lowConfidenceIsDropped() =
        assertNull(LocalSpeech.accepted(WhisperNative.Result("turn on the lights", 0.2f)))
    @Test fun whisperSilencePhrasesAreDropped() {
        assertNull(LocalSpeech.accepted(WhisperNative.Result("Thank you.", 0.9f)))
        assertNull(LocalSpeech.accepted(WhisperNative.Result("[BLANK_AUDIO]", 0.9f)))
    }
    @Test fun nothingIsNull() = assertNull(LocalSpeech.accepted(null))
}
