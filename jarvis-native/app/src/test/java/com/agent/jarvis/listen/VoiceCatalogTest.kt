package com.agent.jarvis.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCatalogTest {
    @Test fun idsAreUniqueAndPinned() {
        val ids = VoiceCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (m in VoiceCatalog.all) {
            assertTrue(m.id, Regex("[0-9a-f]{64}").matches(m.sha256))
            assertTrue(m.id, m.bytes in 10_000_000L..200_000_000L)
            assertTrue(m.id, m.modelFile.endsWith(".onnx"))
            assertTrue(m.url.endsWith("/tts-models/${m.id}.tar.bz2"))
        }
    }

    @Test fun defaultIsRyanAndKokoroDefaultsToGeorge() {
        assertEquals("vits-piper-en_US-ryan-high-int8", VoiceCatalog.DEFAULT)
        val k = VoiceCatalog.byId(VoiceCatalog.KOKORO)!!
        assertEquals(VoiceCatalog.Kind.KOKORO, k.kind)
        assertEquals("George (British male)", VoiceCatalog.speakerName(k, VoiceCatalog.DEFAULT_SPEAKER))
        assertEquals(11, k.speakers.size)
    }

    @Test fun normanKeepsItsOldPin() {
        val n = VoiceCatalog.byId(VoiceCatalog.NORMAN)!!
        assertEquals("cb481a514bc213ccf3899391c0f27fdcc4e4b814ec30496f28089a027b5aa01b", n.sha256)
        assertEquals(LocalVoice.VOICE, n.id)
    }

    @Test fun speakersAreClampedAndNamed() {
        val lib = VoiceCatalog.byId("vits-piper-en_US-libritts_r-medium-int8")!!
        assertEquals(903, VoiceCatalog.clampSpeaker(lib, 5000))
        assertEquals(0, VoiceCatalog.clampSpeaker(lib, -3))
        assertEquals("Voice 12", VoiceCatalog.speakerName(lib, 12))
        val ryan = VoiceCatalog.byId("vits-piper-en_US-ryan-high-int8")!!
        assertEquals("", VoiceCatalog.speakerName(ryan, 0))
        assertEquals(0, VoiceCatalog.clampSpeaker(ryan, 4))
    }

    @Test fun aStandInSpeaksWhileTheChoiceDownloads() {
        val have = setOf(VoiceCatalog.NORMAN)
        assertEquals(VoiceCatalog.NORMAN, VoiceCatalog.speaking(VoiceCatalog.KOKORO) { it.id in have }?.id)
        assertEquals(VoiceCatalog.KOKORO, VoiceCatalog.speaking(VoiceCatalog.DEFAULT) { it.id == VoiceCatalog.KOKORO }?.id)
        assertEquals(VoiceCatalog.KOKORO, VoiceCatalog.speaking(VoiceCatalog.KOKORO) { true }?.id)
        assertNull(VoiceCatalog.speaking(VoiceCatalog.KOKORO) { false })
        assertNull(VoiceCatalog.speaking(VoiceCatalog.PHONE) { true })
        assertEquals("vits-piper-en_GB-alan-medium-int8",
            VoiceCatalog.speaking("unknown-id") { it.id == "vits-piper-en_GB-alan-medium-int8" }?.id)
    }
}
