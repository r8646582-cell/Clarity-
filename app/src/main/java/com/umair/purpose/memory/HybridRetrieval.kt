package com.umair.purpose.memory

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Phase 2: the on-device embedding model, behind an interface so the app works without one. A null vector means
 * "no embedding available" and callers fall back to full-text search alone.
 */
interface Embedder {
    /** Names the model and its settings; stored vectors from another id are recomputed. */
    val id: String

    /** A unit-length vector, or null when no model is available or it failed. Never throws. */
    fun embed(text: String): FloatArray?
}

/**
 * Phase 2 (BLUEPRINT-V3): merging full-text and embedding results. Embeddings are finders and flaggers only: they
 * rank candidates and point at likely duplicates or contradictions for the existing, evidence-based paths to judge.
 * Nothing here writes memory text or changes a confidence.
 */
object HybridRetrieval {
    /** The usual reciprocal-rank-fusion constant. */
    const val RRF_K = 60
    /** Cosine similarity at or above which two notes are flagged as probable duplicates. */
    const val DUPLICATE_SIMILARITY = 0.92
    /** A fact this old counts half as much as a fresh one for recency, before the evidence grade is added. */
    private const val RECENCY_HALF_LIFE_DAYS = 180.0
    private const val DAY_MS = 86_400_000.0

    /** One candidate for the current message. [grade]: 0 guess, 1 likely, 2 confirmed (archive items use 0). */
    data class Candidate(val id: String, val fused: Double, val timeMs: Long, val grade: Int = 0)

    /**
     * Reciprocal rank fusion: each list contributes 1 / (k + rank) for an item, so something both searches like
     * beats something only one likes. Best first; ties keep the order of the first list that had the item.
     */
    fun <T> fuse(lists: List<List<T>>, k: Int = RRF_K): List<Pair<T, Double>> {
        val score = LinkedHashMap<T, Double>()
        for (list in lists) list.distinct().forEachIndexed { i, item -> score[item] = (score[item] ?: 0.0) + 1.0 / (k + i + 1) }
        return score.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** Fused score nudged by recency and evidence grade (at most +15% and +10%): relevance still decides. */
    fun rerank(candidates: List<Candidate>, nowMs: Long): List<Candidate> = candidates.sortedByDescending { c ->
        val ageDays = ((nowMs - c.timeMs).coerceAtLeast(0L)) / DAY_MS
        val recency = exp(-ageDays * Math.log(2.0) / RECENCY_HALF_LIFE_DAYS)
        c.fused * (1.0 + 0.15 * recency + 0.10 * c.grade.coerceIn(0, 2) / 2.0)
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
    }

    /** Ids of the [limit] vectors closest to [query], best first. */
    fun nearest(query: FloatArray, vectors: Map<String, FloatArray>, limit: Int): List<String> =
        vectors.entries.map { it.key to cosine(query, it.value) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }

    /** Pairs of ids whose vectors are nearly identical: a flag for review, never an automatic merge. */
    fun likelyDuplicates(vectors: Map<String, FloatArray>, threshold: Double = DUPLICATE_SIMILARITY): List<Pair<String, String>> {
        val ids = vectors.keys.toList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            if (cosine(vectors.getValue(ids[i]), vectors.getValue(ids[j])) >= threshold) out += ids[i] to ids[j]
        }
        return out
    }
}
