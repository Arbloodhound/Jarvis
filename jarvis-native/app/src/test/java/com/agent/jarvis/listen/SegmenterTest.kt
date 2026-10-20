package com.agent.jarvis.listen

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

    /** Steady room sound above a too-low floor (Pixel log: every utterance hit the 28 s cap): after
     *  one capped segment the floor rises to the room, and the next segments end on real silence. */
    @Test fun aCappedSegmentRaisesTheFloorToTheRoom() {
        val lens = ArrayList<Int>()
        val seg = Segmenter { pcm, _ -> lens += pcm.size / AmbientConfig.FRAME }
        val buf = ShortArray(AmbientConfig.FRAME)
        fun feed(v: Int) { buf.fill(v.toShort()); seg.push(buf) }
        repeat(50) { feed(1) }                                       // a near-silent start sets a low floor
        val capFrames = AmbientConfig.MAX_UTTER_MS / AmbientConfig.FRAME_MS
        repeat(capFrames + 5) { feed(if (it % 2 == 0) 60 else 90) }  // a fan / TV: never quiet
        assertTrue(seg.noiseFloorDb > -60f)
        lens.clear()
        repeat(30) { feed(2000) }                                    // someone speaks over it
        repeat(60) { feed(if (it % 2 == 0) 60 else 90) }             // back to the room
        assertEquals(1, lens.size)
        assertTrue("speech segment ${lens[0]} frames", lens[0] < capFrames / 2)
    }

    @Test fun cutClosesTheOpenSegmentAndCommandsAreShort() {
        val lens = ArrayList<Int>()
        val seg = Segmenter { pcm, _ -> lens += pcm.size / AmbientConfig.FRAME }
        val buf = ShortArray(AmbientConfig.FRAME)
        fun feed(v: Int) { buf.fill(v.toShort()); seg.push(buf) }
        repeat(20) { feed(0) }
        repeat(100) { feed(2000) }
        assertTrue(seg.openMs > 2_500)
        seg.cut()
        assertEquals(1, lens.size)
        seg.maxMs = AmbientConfig.COMMAND_MAX_MS
        repeat(400) { feed(2000) }                                   // a command window: cut at 9 s
        assertTrue(lens.size >= 2)
        assertTrue(lens[1] * AmbientConfig.FRAME_MS <= AmbientConfig.COMMAND_MAX_MS + 400)
    }
}
