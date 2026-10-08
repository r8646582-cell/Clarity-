package com.umair.purpose.memory

import com.umair.purpose.data.db.EmbeddingRow
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.SearchHit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2: keeps one embedding per archive search document and finds the documents closest to a message. Derived
 * data (rebuildable from the search documents). Embeddings only find and rank; they never write memory text and
 * never change a confidence. With no model available every method is a harmless no-op.
 */
@Singleton
class EmbeddingIndex @Inject constructor(private val db: PurposeDatabase, private val embedder: Embedder) {
    private class Entry(val hit: SearchHit, val vector: FloatArray)

    @Volatile private var cache: Map<String, Entry>? = null

    init { db.datasetWork.onReplaced { cache = null } }

    /** Embeds documents that have no current vector and drops vectors whose document is gone. Safe to call often. */
    suspend fun sync(maxNew: Int = MAX_PER_SYNC): Int {
        val docs = db.searchDao().allDocs()
        val dao = db.embeddingDao()
        val existing = dao.all().associateBy { key(it.kind, it.refId) }
        val live = docs.associateBy { key(it.kind, it.refId) }
        existing.filterKeys { it !in live }.values.forEach { dao.delete(it.kind, it.refId) }
        var added = 0
        val batch = ArrayList<EmbeddingRow>()
        for (d in docs) {
            if (added >= maxNew) break
            val have = existing[key(d.kind, d.refId)]
            val hash = hash(d.text)
            if (have != null && have.model == embedder.id && have.textHash == hash) continue
            val v = embedder.embed(d.text.take(MAX_CHARS)) ?: break // no model: stop trying this round
            batch += EmbeddingRow(d.kind, d.refId, embedder.id, hash, pack(v))
            added++
            if (batch.size >= 50) { dao.upsert(batch.toList()); batch.clear() }
        }
        if (batch.isNotEmpty()) dao.upsert(batch)
        cache = null
        return added
    }

    /** The documents nearest to [message], best first, each with its cosine similarity. Empty without a model. */
    suspend fun nearest(message: String, limit: Int = SEMANTIC_LIMIT): List<Pair<SearchHit, Double>> {
        val q = embedder.embed(message.take(MAX_CHARS)) ?: return emptyList()
        val entries = load().values
        return entries.map { it.hit to HybridRetrieval.cosine(q, it.vector) }
            .filter { it.second >= MIN_SIMILARITY }
            .sortedByDescending { it.second }
            .take(limit)
    }

    private suspend fun load(): Map<String, Entry> {
        cache?.let { return it }
        val docs = db.searchDao().allDocs().associateBy { key(it.kind, it.refId) }
        val loaded = db.embeddingDao().all().asSequence()
            .filter { it.model == embedder.id }
            .mapNotNull { r -> docs[key(r.kind, r.refId)]?.let { key(r.kind, r.refId) to Entry(it, unpack(r.vector)) } }
            .toMap()
        cache = loaded
        return loaded
    }

    companion object {
        const val MAX_PER_SYNC = 200
        const val SEMANTIC_LIMIT = 10
        /** Below this cosine similarity a document is not related enough to mention. */
        const val MIN_SIMILARITY = 0.45
        private const val MAX_CHARS = 1500

        fun key(kind: String, refId: String) = "$kind|$refId"

        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

        fun pack(v: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            v.forEach { b.putFloat(it) }
            return b.array()
        }

        fun unpack(bytes: ByteArray): FloatArray {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { b.getFloat() }
        }
    }
}
