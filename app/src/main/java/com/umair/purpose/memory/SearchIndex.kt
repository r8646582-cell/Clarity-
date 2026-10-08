package com.umair.purpose.memory

import androidx.room.withTransaction
import com.umair.purpose.data.db.Chapter
import com.umair.purpose.data.db.Letter
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.SearchDoc
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UPDATE-15: keeps the archive's full-text index in step with the real tables. Everything it holds can be rebuilt
 * from them, so after a restore, a forgotten conversation or "Erase everything" it's simply rebuilt.
 */
@Singleton
class SearchIndex @Inject constructor(
    private val db: PurposeDatabase,
    /** Phase 2: meaning-based search beside the full-text index. Null (as in older tests) = full-text only. */
    private val embeddings: EmbeddingIndex? = null,
) {
    private val dao = db.searchDao()

    /** Everything, from scratch, in one transaction. A few thousand rows a year: quick, and only run rarely. */
    suspend fun rebuild(zone: ZoneId = ZoneId.systemDefault()) {
        rebuildDocs(zone)
        syncEmbeddings()
    }

    /** Embeds new documents; never throws and never blocks anything that matters. */
    /** Loads the embedding model in the background; harmless without one. */
    suspend fun warmUp() { runCatching { embeddings?.warmUp() } }

    suspend fun syncEmbeddings() { runCatching { embeddings?.sync() } }

    private suspend fun rebuildDocs(zone: ZoneId) = db.withTransaction {
        dao.clear()
        val docs = buildList {
            db.sessionDao().all().mapNotNullTo(this) { SearchDocs.summary(it, zone) }
            db.quoteDao().all().mapTo(this) { SearchDocs.quote(it, zone) }
            db.behaviorDao().all().mapNotNullTo(this) { SearchDocs.event(it, zone) }
            db.letterDao().all().mapTo(this) { SearchDocs.letter(it) }
            db.chapterDao().all().mapTo(this) { SearchDocs.chapter(it) }
            db.noteDao().all().mapNotNullTo(this) { SearchDocs.retiredNote(it, zone) }
            db.profileDao().all().mapNotNullTo(this) { SearchDocs.retiredProfile(it, zone) }
            db.strengthDao().all().mapNotNullTo(this) { SearchDocs.retiredStrength(it, zone) }
        }
        docs.chunked(500).forEach { dao.insert(it) }
    }

    /** After a reflection: that conversation's summary, quotes and moments. */
    suspend fun indexSession(sessionId: Long, zone: ZoneId = ZoneId.systemDefault()) {
        indexSessionDocs(sessionId, zone)
        syncEmbeddings()
    }

    private suspend fun indexSessionDocs(sessionId: Long, zone: ZoneId) = db.withTransaction {
        val s = db.sessionDao().get(sessionId) ?: return@withTransaction
        dao.delete(SearchDocs.SUMMARY, sessionId.toString())
        val docs = buildList {
            SearchDocs.summary(s, zone)?.let(::add)
            db.quoteDao().forSession(sessionId).forEach { q -> dao.delete(SearchDocs.QUOTE, "${q.sessionId}:${q.id}"); add(SearchDocs.quote(q, zone)) }
            db.behaviorDao().forSession(sessionId).forEach { e ->
                dao.delete(SearchDocs.EVENT, "${e.sessionId}:${e.id}")
                SearchDocs.event(e, zone)?.let(::add)
            }
        }
        if (docs.isNotEmpty()) dao.insert(docs)
    }

    suspend fun indexLetter(l: Letter) {
        dao.delete(SearchDocs.LETTER, l.id.toString())
        dao.insert(listOf(SearchDocs.letter(l)))
        syncEmbeddings()
    }

    suspend fun indexChapter(c: Chapter) {
        dao.delete(SearchDocs.CHAPTER, c.id.toString())
        dao.insert(listOf(SearchDocs.chapter(c)))
        syncEmbeddings()
    }

    /** After gardening or a cap: archived memory becomes searchable. */
    suspend fun indexArchive(zone: ZoneId = ZoneId.systemDefault()) {
        indexArchiveDocs(zone)
        syncEmbeddings()
    }

    private suspend fun indexArchiveDocs(zone: ZoneId) = db.withTransaction {
        val docs: List<SearchDoc> = buildList {
            db.noteDao().all().mapNotNullTo(this) { SearchDocs.retiredNote(it, zone) }
            db.profileDao().all().mapNotNullTo(this) { SearchDocs.retiredProfile(it, zone) }
            db.strengthDao().all().mapNotNullTo(this) { SearchDocs.retiredStrength(it, zone) }
        }
        dao.deleteKind(SearchDocs.NOTE)
        if (docs.isNotEmpty()) dao.insert(docs)
    }

    suspend fun remove(kind: String, refId: String) = dao.delete(kind, refId)

    suspend fun isEmpty(): Boolean = dao.count() == 0

    /** The strongest few matches for his message, or nothing. Never throws: search is a bonus, never a blocker. */
    suspend fun relevant(message: String, currentSession: Long?): String? = runCatching {
        val terms = RelevantMemories.terms(message)
        val lexical = RelevantMemories.matchQuery(terms)?.let { dao.search(it, RelevantMemories.CANDIDATES) }.orEmpty()
        // Meaning matches are a bonus: any failure leaves the full-text result.
        val semantic = runCatching { embeddings?.nearest(message) }.getOrNull().orEmpty()
        if (terms.isEmpty() && semantic.isEmpty()) return null
        RelevantMemories.block(RelevantMemories.rankHybrid(lexical, semantic, terms, currentSession))
    }.getOrNull()
}
