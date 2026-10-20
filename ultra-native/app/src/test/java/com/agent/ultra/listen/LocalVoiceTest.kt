package com.agent.ultra.listen

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class TarEntryPathTest {
    @Test fun plainPathIsKept() =
        assertEquals("voice/espeak-ng-data/en_dict", LocalVoice.safeEntryPath("voice/espeak-ng-data/en_dict"))
    @Test fun leadingDotSlashIsDropped() = assertEquals("voice/MODEL_CARD", LocalVoice.safeEntryPath("./voice/MODEL_CARD"))
    @Test fun directoryEntryKeepsItsName() = assertEquals("voice", LocalVoice.safeEntryPath("voice/"))
    @Test fun absoluteIsRefused() = assertNull(LocalVoice.safeEntryPath("/data/data/com.agent.ultra/x"))
    @Test fun parentSegmentIsRefused() = assertNull(LocalVoice.safeEntryPath("voice/../../shared_prefs/x.xml"))
    @Test fun bareParentIsRefused() = assertNull(LocalVoice.safeEntryPath(".."))
    @Test fun backslashParentIsRefused() = assertNull(LocalVoice.safeEntryPath("voice\\..\\..\\x"))
    @Test fun windowsDriveIsRefused() = assertNull(LocalVoice.safeEntryPath("C:/x"))
    @Test fun nulIsRefused() = assertNull(LocalVoice.safeEntryPath("voice/a\u0000b"))
    @Test fun emptyIsRefused() = assertNull(LocalVoice.safeEntryPath(""))
    @Test fun onlyDotIsRefused() = assertNull(LocalVoice.safeEntryPath("./"))
    @Test fun dotsInsideANameAreFine() = assertEquals("v/a..b", LocalVoice.safeEntryPath("v/a..b"))
}

class SpeechChunksTest {
    @Test fun splitsAfterSentences() =
        assertEquals(listOf("Hello there.", "How are you?", "Fine!"), LocalVoice.chunks("Hello there. How are you? Fine!"))
    @Test fun blankGivesNothing() = assertEquals(emptyList<String>(), LocalVoice.chunks("   "))
    @Test fun noPunctuationIsOneChunk() = assertEquals(listOf("turn on the lights"), LocalVoice.chunks("turn on the lights"))
    @Test fun decimalsAreNotSentenceEnds() = assertEquals(listOf("It is 3.5 degrees."), LocalVoice.chunks("It is 3.5 degrees."))
    @Test fun longTextWithoutPunctuationStaysUnderMax() {
        val text = (1..100).joinToString(" ") { "word$it" }
        val c = LocalVoice.chunks(text, max = 50)
        assertTrue(c.all { it.length <= 50 && it.isNotBlank() })
        assertEquals(text, c.joinToString(" "))
    }
    @Test fun prefersCommasInsideALongSentence() =
        assertEquals(listOf("aaaa bbbb,", "cccc dddd"), LocalVoice.chunks("aaaa bbbb, cccc dddd", max = 12))
    @Test fun aWordLongerThanMaxIsCut() {
        val c = LocalVoice.chunks("x".repeat(25), max = 10)
        assertEquals(listOf("x".repeat(10), "x".repeat(10), "x".repeat(5)), c)
    }
    @Test fun nonsenseMaxStillTerminates() = assertEquals(listOf("a", "b"), LocalVoice.chunks("ab", max = 0))
    @Test fun noEmptyChunksFromRunsOfPunctuation() =
        assertTrue(LocalVoice.chunks("Wait... what?!  Really. . .").none { it.isBlank() })
}

class Pcm16Test {
    @Test fun scalesAndClamps() = assertArrayEquals(
        shortArrayOf(0, 16383, -16383, Short.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE, Short.MIN_VALUE, 0),
        LocalVoice.toPcm16(floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 3f, -7f, Float.NaN)),
    )
    @Test fun emptyStaysEmpty() = assertEquals(0, LocalVoice.toPcm16(FloatArray(0)).size)
}

class TarExtractTest {
    private val dir: File = kotlin.io.path.createTempDirectory("voice").toFile()
    private val out = File(dir, "out")

    private fun archive(build: TarArchiveOutputStream.() -> Unit) = File(dir, "a.tar.bz2").also { f ->
        TarArchiveOutputStream(BZip2CompressorOutputStream(f.outputStream())).use { it.build() }
    }
    private fun TarArchiveOutputStream.file(name: String, body: String) {
        val bytes = body.toByteArray()
        putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() }); write(bytes); closeArchiveEntry()
    }
    private fun TarArchiveOutputStream.link(name: String, type: Byte, target: String) {
        putArchiveEntry(TarArchiveEntry(name, type).apply { linkName = target }); closeArchiveEntry()
    }
    private fun refused(a: File) = runCatching { LocalVoice.extractTarBz2(a, out) }.exceptionOrNull() is IOException

    @Test fun unpacksFilesAndDirectories() {
        LocalVoice.extractTarBz2(archive {
            putArchiveEntry(TarArchiveEntry("voice/espeak-ng-data/")); closeArchiveEntry()
            file("./voice/tokens.txt", "a 1")
        }, out)
        assertEquals("a 1", File(out, "voice/tokens.txt").readText())
        assertTrue(File(out, "voice/espeak-ng-data").isDirectory)
    }
    @Test fun parentEscapeIsRefusedAndNothingLandsOutside() {
        assertTrue(refused(archive { file("voice/../../evil.txt", "x") }))
        assertFalse(File(dir.parentFile, "evil.txt").exists())
    }
    @Test fun symlinkIsRefused() = assertTrue(refused(archive { link("voice/s", TarConstants.LF_SYMLINK, "/data/data") }))
    @Test fun hardlinkIsRefused() = assertTrue(refused(archive { link("voice/h", TarConstants.LF_LINK, "voice/tokens.txt") }))

    @Test fun longFirstSentenceIsCutShorterAndNothingIsLost() {
        val first = "This is a long opening sentence, which goes on for quite a while before it finally ends here."
        val text = "$first Second one."
        val parts = LocalVoice.chunks(text, firstMax = 40)
        assertTrue(parts.first().length <= 40)
        assertEquals(text.split(Regex("\\s+")), parts.joinToString(" ").split(Regex("\\s+")))
        assertEquals("Second one.", parts.last())
        assertEquals(LocalVoice.chunks("Short. Two."), LocalVoice.chunks("Short. Two.", firstMax = 40))
    }
}
