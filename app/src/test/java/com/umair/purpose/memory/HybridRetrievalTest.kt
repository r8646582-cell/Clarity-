package com.umair.purpose.memory

import com.umair.purpose.memory.HybridRetrieval.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRetrievalTest {
    private val day = 86_400_000L

    @Test
    fun `an item both searches return beats one only a single search returns`() {
        val fused = HybridRetrieval.fuse(listOf(listOf("a", "b", "c"), listOf("d", "b")))
        assertEquals("b", fused.first().first)
        assertEquals(setOf("a", "b", "c", "d"), fused.map { it.first }.toSet())
    }

    @Test
    fun `fusing with an empty embedding list keeps the full-text order`() {
        assertEquals(listOf("x", "y", "z"), HybridRetrieval.fuse(listOf(listOf("x", "y", "z"), emptyList())).map { it.first })
    }

    @Test
    fun `a fresher and better evidenced fact wins only when relevance is close`() {
        val now = 1_000 * day
        val close = HybridRetrieval.rerank(listOf(
            Candidate("old", 0.0300, now - 700 * day, grade = 0),
            Candidate("new", 0.0295, now - 1 * day, grade = 2),
        ), now)
        assertEquals("new", close.first().id)
        val far = HybridRetrieval.rerank(listOf(
            Candidate("old", 0.0500, now - 700 * day, grade = 0),
            Candidate("new", 0.0250, now - 1 * day, grade = 2),
        ), now)
        assertEquals("old", far.first().id)
    }

    @Test
    fun `cosine and nearest rank by direction`() {
        val q = floatArrayOf(1f, 0f)
        val vectors = mapOf("same" to floatArrayOf(2f, 0f), "near" to floatArrayOf(1f, 1f), "away" to floatArrayOf(0f, 1f))
        assertEquals(1.0, HybridRetrieval.cosine(q, vectors.getValue("same")), 1e-6)
        assertEquals(listOf("same", "near"), HybridRetrieval.nearest(q, vectors, 2))
        assertEquals(0.0, HybridRetrieval.cosine(q, floatArrayOf(1f)), 0.0)
    }

    @Test
    fun `near-identical vectors are flagged as duplicates and nothing else`() {
        val flags = HybridRetrieval.likelyDuplicates(mapOf(
            "a" to floatArrayOf(1f, 0.01f), "b" to floatArrayOf(1f, 0.02f), "c" to floatArrayOf(0f, 1f),
        ))
        assertEquals(listOf("a" to "b"), flags)
        assertTrue(HybridRetrieval.likelyDuplicates(emptyMap()).isEmpty())
    }
}
