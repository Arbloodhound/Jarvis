package com.agent.ultra.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechQueueTest {
    /** A fake speaker: records what it was asked to say; [finish] ends the current text. */
    private class Fake {
        val said = mutableListOf<String>()
        var done: (() -> Unit)? = null
        var cuts = 0
        fun finish() { val d = done; done = null; d?.invoke() }
        fun queue() = SpeechQueue(speak = { t, d -> said += t; done = d }, cut = { cuts++; val d = done; done = null; d?.invoke() })
    }

    @Test fun saysInOrderWithoutCuttingOff() {
        val f = Fake(); val q = f.queue()
        q.add("One.")
        q.add("Two.")
        assertEquals(listOf("One."), f.said)
        f.finish()
        assertEquals(listOf("One.", "Two."), f.said)
        f.finish()
        assertFalse(q.busy)
    }

    @Test fun whatQueuedUpGoesOutTogether() {
        val f = Fake(); val q = f.queue()
        q.add("One.")
        q.add("Two."); q.add("Three.")
        f.finish()
        assertEquals(listOf("One.", "Two. Three."), f.said)
    }

    @Test fun afterRunsWhenSaidInFull() {
        val f = Fake(); val q = f.queue()
        var after = 0
        q.add("Yes?") { after++ }
        assertEquals(0, after)
        f.finish()
        assertEquals(1, after)
    }

    @Test fun stopDropsTheRestAndSkipsAfter() {
        val f = Fake(); val q = f.queue()
        var after = 0; var idle = 0
        q.onIdle = { idle++ }
        q.add("One.") { after++ }
        q.add("Two.")
        q.stop()
        assertEquals(1, f.cuts)
        assertEquals(0, after)
        assertEquals(1, idle)
        assertFalse(q.busy)
        f.finish()                         // a late callback from the cut speech changes nothing
        assertEquals(listOf("One."), f.said)
        q.add("Three.")
        assertEquals(listOf("One.", "Three."), f.said)
    }

    @Test fun startAndIdleHooks() {
        val f = Fake(); val q = f.queue()
        val started = mutableListOf<String>(); var idle = 0
        q.onStart = { started += it }; q.onIdle = { idle++ }
        q.add("A."); f.finish()
        assertEquals(listOf("A."), started); assertEquals(1, idle)
    }

    @Test fun stopAllReachesRegisteredQueues() {
        val f = Fake(); val q = SpeechQueue.register(f.queue())
        try {
            q.add("Talking.")
            assertTrue(SpeechQueue.anyBusy)
            assertTrue(SpeechQueue.stopAll())
            assertFalse(q.busy)
        } finally { SpeechQueue.unregister(q) }
    }

    @Test fun blankIsSkipped() {
        val f = Fake(); val q = f.queue()
        var after = 0
        q.add("  ") { after++ }
        assertTrue(f.said.isEmpty()); assertEquals(1, after); assertFalse(q.busy)
    }
}
