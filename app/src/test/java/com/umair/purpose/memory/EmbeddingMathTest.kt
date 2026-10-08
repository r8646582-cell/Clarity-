package com.umair.purpose.memory

import com.umair.purpose.data.db.SearchHit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingMathTest {
    @Test
    fun `vectors survive being packed into a blob`() {
        val v = floatArrayOf(0.25f, -1.5f, 3f)
        assertArrayEquals(v, EmbeddingIndex.unpack(EmbeddingIndex.pack(v)), 0f)
        assertEquals(12, EmbeddingIndex.pack(v).size)
    }

    @Test
    fun `the text hash changes with the text`() {
        assertEquals(EmbeddingIndex.hash("a"), EmbeddingIndex.hash("a"))
        assertTrue(EmbeddingIndex.hash("a") != EmbeddingIndex.hash("b"))
    }

    @Test
    fun `pooling averages tokens and gives unit length`() {
        val out = OnnxEmbedder.meanPoolAndNormalize(arrayOf(floatArrayOf(3f, 0f), floatArrayOf(3f, 4f)))
        assertEquals(1.0, Math.sqrt(out.sumOf { (it * it).toDouble() }), 1e-6)
        assertEquals(0.8f, out[0], 1e-6f)
        assertEquals(0.6f, out[1], 1e-6f)
    }

    private fun hit(ref: String, text: String, day: String = "2026-01-01") = SearchHit("summary", ref, day, text)

    @Test
    fun `without meaning matches the hybrid ranking is the old ranking`() {
        val hits = listOf(hit("1", "she worries about money and rent"), hit("2", "unrelated"))
        val terms = listOf("money", "rent")
        assertEquals(RelevantMemories.rank(hits, terms, null), RelevantMemories.rankHybrid(hits, emptyList(), terms, null))
    }

    @Test
    fun `a meaning-only match is added and a double match comes first`() {
        val both = hit("1", "worried about money and rent every month")
        val meaningOnly = hit("2", "anxious about not being able to pay the landlord")
        val out = RelevantMemories.rankHybrid(
            lexical = listOf(both), semantic = listOf(meaningOnly to 0.7, both to 0.6),
            terms = listOf("money", "rent"), excludeSession = null,
        )
        assertEquals(listOf("1", "2"), out.map { it.refId })
    }

    @Test
    fun `the conversation he is in is left out of meaning matches too`() {
        val current = hit("7", "same chat")
        val out = RelevantMemories.rankHybrid(emptyList(), listOf(current to 0.9), listOf("chat"), excludeSession = 7)
        assertTrue(out.isEmpty())
    }
}
