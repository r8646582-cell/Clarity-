package com.umair.purpose.memory

import com.umair.purpose.data.db.EmbeddingRow
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.SearchHit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    /** One sync at a time: reflection, a letter and launch can all ask for one. */
    private val syncLock = Mutex()

    init { db.datasetWork.onReplaced { cache = null } }

    /** Embeds documents that have no current vector and drops vectors whose document is gone. Safe to call often. */
    suspend fun sync(maxNew: Int = MAX_PER_SYNC): Int = syncLock.withLock { withContext(Dispatchers.Default) { syncLocked(maxNew) } }

    /** Loads the model now (a few hundred milliseconds) so his first message after launch does not pay for it. */
    suspend fun warmUp() = withContext(Dispatchers.Default) { embedder.embed("warm up"); Unit }

    private suspend fun syncLocked(maxNew: Int): Int {
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
        return withContext(Dispatchers.Default) {
            val q = embedder.embed(message.take(MAX_CHARS)) ?: return@withContext emptyList()
            load().values.map { it.hit to HybridRetrieval.cosine(q, it.vector) }
                .filter { it.second >= MIN_SIMILARITY }
                .sortedByDescending { it.second }
                .take(limit)
        }
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
        /**
         * Below this cosine similarity a document is not related enough to mention. Measured on the memory exam
         * (tools/eval, `--retriever hybrid`): retrieval coverage 70.8 with full-text only, 72.2 at 0.45, 80.6 at 0.30,
         * 83.3 at 0.20. It plateaus below 0.25, so 0.30 keeps the gain without padding the request with weak matches.
         */
        const val MIN_SIMILARITY = 0.30
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
