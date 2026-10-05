package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmenterTest {
    /** The capture loop reads every frame into ONE reused array. Each loud frame here
     * has its own value; the utterance must keep all of them, not N copies of the last. */
    @Test fun keepsEachFrameWhenCallerReusesItsBuffer() {
        val out = ArrayList<ShortArray>()
        val seg = Segmenter { pcm, _ -> out += pcm }
        val buf = ShortArray(AmbientConfig.FRAME)
        fun feed(v: Int) { buf.fill(v.toShort()); seg.push(buf) }

        repeat(20) { feed(0) }                 // quiet room
        repeat(20) { i -> feed(1000 + i) }     // speech, a distinct level per frame
        repeat(40) { feed(0) }                 // silence closes the segment

        assertEquals(1, out.size)
        val levels = out[0].toSet()
        for (i in 0 until 20) assertTrue("frame ${1000 + i} missing", (1000 + i).toShort() in levels)
    }
}
