package com.agent.ultra.listen

import java.text.Normalizer

/**
 * BERT uncased tokenizer (BasicTokenizer + WordpieceTokenizer), as configured for
 * sentence-transformers/all-MiniLM-L6-v2 (tokenizer_config.json on the Hub:
 * do_lower_case=true, tokenize_chinese_chars=true, strip_accents=null — which with
 * lower-casing means accents ARE stripped, as in HF BertTokenizer).
 *
 * Pure Kotlin; [vocab] is vocab.txt, one token per line, id = line number.
 * Output: [CLS] tokens… [SEP], truncated so the whole sequence is at most [maxLen].
 */
class WordPiece(vocab: List<String>, private val maxLen: Int = 128) {
    private val ids: Map<String, Int> = HashMap<String, Int>(vocab.size * 2).also { m ->
        vocab.forEachIndexed { i, t -> if (t.isNotEmpty() && t !in m) m[t] = i }
    }
    val cls = idOf("[CLS]"); val sep = idOf("[SEP]"); val unk = idOf("[UNK]")
    val pad = ids["[PAD]"] ?: 0

    private fun idOf(t: String) = ids[t] ?: error("vocab has no $t")

    /** Token ids including [CLS]/[SEP]; length ≤ maxLen. */
    fun encode(text: String): IntArray {
        val out = ArrayList<Int>()
        out += cls
        loop@ for (word in basicTokens(text)) {
            for (id in wordPiece(word)) {
                if (out.size >= maxLen - 1) break@loop
                out += id
            }
        }
        out += sep
        return out.toIntArray()
    }

    /** Greedy longest-match-first; a word that can't be fully covered is one [UNK]. */
    internal fun wordPiece(word: String): List<Int> {
        if (word.codePointCount(0, word.length) > MAX_WORD_CHARS) return listOf(unk)
        val out = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = ids[sub]
                if (id != null) { found = id; break }
                end = word.offsetByCodePoints(end, -1)
            }
            if (found < 0) return listOf(unk)
            out += found
            start = end
        }
        return out
    }

    companion object {
        const val MAX_WORD_CHARS = 100

        /** BasicTokenizer: clean, split CJK, whitespace split, lower + strip accents, split punctuation. */
        fun basicTokens(text: String): List<String> {
            val cleaned = StringBuilder()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i); i += Character.charCount(cp)
                when {
                    cp == 0 || cp == 0xFFFD || isControl(cp) -> {}
                    isWhitespace(cp) -> cleaned.append(' ')
                    isCjk(cp) -> cleaned.append(' ').appendCodePoint(cp).append(' ')
                    else -> cleaned.appendCodePoint(cp)
                }
            }
            val out = ArrayList<String>()
            for (raw in cleaned.split(' ')) {
                if (raw.isEmpty()) continue
                val w = stripAccents(raw.lowercase())
                val cur = StringBuilder()
                var j = 0
                while (j < w.length) {
                    val cp = w.codePointAt(j); j += Character.charCount(cp)
                    if (isPunctuation(cp)) {
                        if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                        out += String(Character.toChars(cp))
                    } else cur.appendCodePoint(cp)
                }
                if (cur.isNotEmpty()) out += cur.toString()
            }
            return out
        }

        private fun stripAccents(s: String): String {
            val d = Normalizer.normalize(s, Normalizer.Form.NFD)
            val sb = StringBuilder(d.length)
            var i = 0
            while (i < d.length) {
                val cp = d.codePointAt(i); i += Character.charCount(cp)
                if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) sb.appendCodePoint(cp)
            }
            return sb.toString()
        }

        private fun isWhitespace(cp: Int) =
            cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
                Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

        private fun isControl(cp: Int): Boolean {
            if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
            val t = Character.getType(cp)
            // HF: any "C*" category (Cc, Cf, Cn, Co, Cs)
            return t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt() ||
                t == Character.UNASSIGNED.toInt() || t == Character.PRIVATE_USE.toInt() ||
                t == Character.SURROGATE.toInt()
        }

        /** HF: ASCII non-alphanumeric printable ranges count as punctuation, plus Unicode P*. */
        private fun isPunctuation(cp: Int): Boolean {
            if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
            return when (Character.getType(cp)) {
                Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt() -> true
                else -> false
            }
        }

        private fun isCjk(cp: Int) =
            cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
                cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF ||
                cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F
    }
}
