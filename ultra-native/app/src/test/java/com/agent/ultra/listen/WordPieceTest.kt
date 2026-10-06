package com.agent.ultra.listen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fixture copies the real all-MiniLM-L6-v2 vocab.txt layout (checked on the Hub): line 0
 * [PAD], lines 1–99 [unused0..98], then [UNK]=100, [CLS]=101, [SEP]=102, [MASK]=103. The
 * words after that are a small hand-picked set; expected ids are counted by hand from it.
 */
class WordPieceTest {
    private val words = listOf(
        "hello", "world", ",", "!", "un", "##aff", "##able", "cafe", "the", "dentist", "##s", "call", "?",
        "我", "好",
    )
    private val vocab = listOf("[PAD]") + (0..98).map { "[unused$it]" } +
        listOf("[UNK]", "[CLS]", "[SEP]", "[MASK]") + words
    private fun id(w: String) = 104 + words.indexOf(w).also { require(it >= 0) { w } }
    private val wp = WordPiece(vocab, 128)

    @Test fun specialTokensMatchTheRealVocabLayout() {
        assertEquals(0, wp.pad); assertEquals(100, wp.unk); assertEquals(101, wp.cls); assertEquals(102, wp.sep)
    }

    @Test fun lowerCasesAndSplitsPunctuation() {
        assertArrayEquals(
            intArrayOf(101, id("hello"), id(","), id("world"), id("!"), 102),
            wp.encode("Hello, WORLD!"),
        )
    }

    @Test fun greedyLongestMatchWithContinuationPieces() {
        assertArrayEquals(intArrayOf(101, id("un"), id("##aff"), id("##able"), 102), wp.encode("unaffable"))
        assertArrayEquals(intArrayOf(101, id("dentist"), id("##s"), 102), wp.encode("Dentists"))
    }

    @Test fun stripsAccentsLikeHfUncased() {
        assertArrayEquals(intArrayOf(101, id("cafe"), 102), wp.encode("Café"))
    }

    @Test fun aWordThatCannotBeCoveredIsOneUnk() {
        // "callx": "call" matches, "##x" doesn't exist -> the whole word is [UNK], not call + [UNK].
        assertArrayEquals(intArrayOf(101, 100, id("the"), 102), wp.encode("callx the"))
    }

    @Test fun controlCharsDroppedWhitespaceNormalisedCjkSplit() {
        assertArrayEquals(intArrayOf(101, id("the"), id("call"), 102), wp.encode("the\u0000\t​call"))
        assertArrayEquals(intArrayOf(101, id("我"), id("好"), 102), wp.encode("我好"))
    }

    @Test fun truncatesToMaxLenKeepingSep() {
        val short = WordPiece(vocab, 5)
        val out = short.encode("hello world hello world hello world")
        assertArrayEquals(intArrayOf(101, id("hello"), id("world"), id("hello"), 102), out)
    }

    @Test fun emptyTextIsClsSep() {
        assertArrayEquals(intArrayOf(101, 102), wp.encode("   "))
    }
}
