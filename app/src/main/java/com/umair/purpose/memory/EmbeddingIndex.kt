package com.umair.purpose.memory

import com.umair.purpose.data.db.EmbeddingRow
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2: keeps one embedding per archive search document and per live note, and finds the ones closest to a
 * message. Derived data (rebuildable). Embeddings only find and rank; they never write memory text and never change
 * a confidence. With no model available every method is a harmless no-op.
 *
 * Embedding is slow, so it never runs inside a database transaction or on the caller's thread: writers call
 * [requestSync], which a single background worker answers a moment after the transaction has committed.
 */
@Singleton
class EmbeddingIndex @Inject constructor(private val db: PurposeDatabase, private val embedder: Embedder) {
    private class Entry(val hit: SearchHit, val vector: FloatArray)

    /** What one message found: archive documents and live notes, each with its cosine similarity, best first. */
    class Related(val archive: List<Pair<SearchHit, Double>>, val noteIds: List<Pair<Long, Double>>)

    @Volatile private var cache: Map<String, Entry>? = null
    /** One sync at a time: reflection, a letter and launch can all ask for one. */
    private val syncLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    /** The last message's vector, so the archive and the notes searches for one message embed it once. */
    @Volatile private var lastQuery: Pair<String, FloatArray?>? = null

    init {
        db.datasetWork.onReplaced { cache = null; lastQuery = null }
        scope.launch {
            for (r in requests) {
                // Let the writer's transaction commit before reading what it wrote.
                delay(DEBOUNCE_MS)
                runCatching { sync() }
            }
        }
    }

    /** Asks for a sync soon. Never blocks, never throws; many requests become one. */
    fun requestSync() { requests.trySend(Unit) }

    /** Embeds documents that have no current vector and drops vectors whose document is gone. Safe to call often. */
    suspend fun sync(maxNew: Int = MAX_PER_SYNC): Int = syncLock.withLock { withContext(Dispatchers.Default) { syncLocked(maxNew) } }

    /** Loads the model now (a few hundred milliseconds) so his first message after launch does not pay for it. */
    suspend fun warmUp() = withContext(Dispatchers.Default) { embedder.embed("warm up"); Unit }

    private suspend fun documents(): List<SearchHit> {
        val live = db.noteDao().recentMemories(Int.MAX_VALUE).map {
            SearchHit(LIVE_NOTE, it.id.toString(), "", it.text)
        }
        return db.searchDao().allDocs() + live
    }

    private suspend fun syncLocked(maxNew: Int): Int {
        val docs = documents()
        val dao = db.embeddingDao()
        val existing = dao.all().associateBy { key(it.kind, it.refId) }
        val liveKeys = docs.mapTo(HashSet()) { key(it.kind, it.refId) }
        existing.filterKeys { it !in liveKeys }.values.forEach { dao.delete(it.kind, it.refId) }
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
        // More left than one round allows: keep going in the background.
        if (added >= maxNew) requestSync()
        return added
    }

    /** What is closest to [message]. Empty without a model, or when nothing is related enough. */
    suspend fun related(message: String, limit: Int = SEMANTIC_LIMIT): Related = withContext(Dispatchers.Default) {
        val q = queryVector(message) ?: return@withContext Related(emptyList(), emptyList())
        val scored = load().values.map { it to HybridRetrieval.cosine(q, it.vector) }.filter { it.second >= MIN_SIMILARITY }
            .sortedByDescending { it.second }
        Related(
            archive = scored.filter { it.first.hit.kind != LIVE_NOTE }.take(limit).map { it.first.hit to it.second },
            noteIds = scored.filter { it.first.hit.kind == LIVE_NOTE }.take(limit)
                .mapNotNull { (e, s) -> e.hit.refId.toLongOrNull()?.let { it to s } },
        )
    }

    private fun queryVector(message: String): FloatArray? {
        lastQuery?.let { if (it.first == message) return it.second }
        val v = embedder.embed(message.take(MAX_CHARS))
        lastQuery = message to v
        return v
    }

    private suspend fun load(): Map<String, Entry> {
        cache?.let { return it }
        val docs = documents().associateBy { key(it.kind, it.refId) }
        val loaded = db.embeddingDao().all().asSequence()
            .filter { it.model == embedder.id }
            .mapNotNull { r ->
                val k = key(r.kind, r.refId)
                // A vector made from older text is skipped until the next sync refreshes it.
                docs[k]?.takeIf { hash(it.text) == r.textHash }?.let { k to Entry(it, unpack(r.vector)) }
            }
            .toMap()
        cache = loaded
        return loaded
    }

    companion object {
        const val MAX_PER_SYNC = 200
        const val SEMANTIC_LIMIT = 10
        /** [SearchHit.kind] of a live note's embedding (archive documents use the kinds in [SearchDocs]). */
        const val LIVE_NOTE = "live_note"
        private const val DEBOUNCE_MS = 1_500L
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
