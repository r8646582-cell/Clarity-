package com.umair.purpose.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WordPieceTokenizerTest {
    private val vocab = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello", "world", "un", "##want", "##ed", "cafe", ",", "!", "a",
    )
    private val tok = WordPieceTokenizer(vocab)

    @Test
    fun `adds CLS and SEP and lower-cases`() {
        assertArrayEquals(longArrayOf(2, 4, 5, 3), tok.encode("Hello WORLD"))
    }

    @Test
    fun `splits punctuation and strips accents`() {
        assertArrayEquals(longArrayOf(2, 4, 10, 5, 11, 3), tok.encode("hello, world!"))
        assertArrayEquals(longArrayOf(2, 9, 3), tok.encode("Café"))
    }

    @Test
    fun `greedy longest-match word pieces`() {
        assertArrayEquals(longArrayOf(2, 6, 7, 8, 3), tok.encode("unwanted"))
    }

    @Test
    fun `unknown words become UNK and empty text is just the markers`() {
        assertArrayEquals(longArrayOf(2, 1, 3), tok.encode("zzz"))
        assertArrayEquals(longArrayOf(2, 3), tok.encode("   "))
    }

    @Test
    fun `long input is cut to the maximum with SEP kept last`() {
        val ids = WordPieceTokenizer(vocab, maxLength = 8).encode("a ".repeat(50))
        assertEquals(8, ids.size)
        assertEquals(3L, ids.last())
        assertEquals(2L, ids.first())
    }
}
