package com.umair.purpose.memory

import java.text.Normalizer

/**
 * Phase 2: the BERT "uncased" WordPiece tokenizer all-MiniLM-L6-v2 was trained with, so on-device vectors match the
 * model's. Lower-cases, strips accents, splits on whitespace and punctuation, then greedily matches the longest
 * vocabulary pieces ("##" marks a continuation). Pure Kotlin, no Android classes.
 */
class WordPieceTokenizer(vocabLines: List<String>, private val maxLength: Int = 128) {
    private val vocab: Map<String, Int> = HashMap<String, Int>().also { m -> vocabLines.forEachIndexed { i, w -> m.putIfAbsent(w.trim(), i) } }
    private val cls = vocab["[CLS]"] ?: error("vocab has no [CLS]")
    private val sep = vocab["[SEP]"] ?: error("vocab has no [SEP]")
    private val unk = vocab["[UNK]"] ?: error("vocab has no [UNK]")

    /** Token ids with [CLS] first and [SEP] last, at most [maxLength] long. */
    fun encode(text: String): LongArray {
        val ids = ArrayList<Long>(maxLength)
        ids += cls.toLong()
        loop@ for (word in basicTokens(text)) {
            for (id in pieces(word)) {
                if (ids.size >= maxLength - 1) break@loop
                ids += id.toLong()
            }
        }
        ids += sep.toLong()
        return ids.toLongArray()
    }

    internal fun basicTokens(text: String): List<String> {
        val clean = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() && !(it.isISOControl() && !it.isWhitespace()) }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) } }
        for (c in clean) when {
            c.isWhitespace() -> flush()
            isPunctuation(c) -> { flush(); out += c.toString() }
            else -> cur.append(c)
        }
        flush()
        return out
    }

    private fun pieces(word: String): List<Int> {
        if (word.length > MAX_WORD) return listOf(unk)
        val out = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (start < end) {
                val piece = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[piece]
                if (id != null) { found = id; break }
                end--
            }
            if (found < 0) return listOf(unk)
            out += found
            start = end
        }
        return out
    }

    private fun isPunctuation(c: Char): Boolean {
        if (c.code in 33..47 || c.code in 58..64 || c.code in 91..96 || c.code in 123..126) return true
        return when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private companion object {
        const val MAX_WORD = 100
    }
}
