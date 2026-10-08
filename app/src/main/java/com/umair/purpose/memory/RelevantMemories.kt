package com.umair.purpose.memory

import com.umair.purpose.data.db.BehaviorEvent
import com.umair.purpose.data.db.Chapter
import com.umair.purpose.data.db.Letter
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.ProfileEntry
import com.umair.purpose.data.db.Quote
import com.umair.purpose.data.db.SearchDoc
import com.umair.purpose.data.db.SearchHit
import com.umair.purpose.data.db.Session
import com.umair.purpose.data.db.Strength
import java.time.ZoneId

/**
 * UPDATE-15 part 3: "Possibly relevant from the past". The archive (summaries, quotes, moments, letters, chapters
 * and archived notes) is indexed with SQLite full-text search; each new message is searched by its keywords and
 * the best few matches, with dates, go in the volatile part of the request. Weak matches are left out, so nothing
 * is sent when nothing really fits (UPDATE-17: no wasted tokens).
 */
object RelevantMemories {
    const val MAX_RESULTS = 5
    const val MAX_TERMS = 8
    /** How many candidates SQLite returns before they're ranked here. */
    const val CANDIDATES = 60
    private const val MAX_CHARS = 320

    /** Words that say nothing about what he's talking about (English only, like the app). */
    private val STOP = setOf(
        "the", "and", "for", "that", "this", "with", "you", "your", "are", "was", "were", "have", "has", "had", "but", "not",
        "what", "when", "where", "why", "how", "who", "can", "could", "would", "should", "will", "just", "like", "really",
        "about", "from", "they", "them", "their", "there", "then", "than", "been", "being", "into", "out", "all", "any",
        "some", "get", "got", "did", "does", "doing", "done", "its", "it's", "i'm", "im", "dont", "don't", "cant", "can't",
        "know", "think", "feel", "feeling", "want", "need", "today", "yesterday", "tomorrow", "now", "still", "also",
        "very", "much", "more", "most", "lot", "lots", "thing", "things", "something", "anything", "nothing", "everything",
        "okay", "yeah", "yes", "hmm", "one", "two", "time", "day", "days", "again", "even", "only", "too", "him", "her",
        "his", "she", "our", "out", "off", "over", "why", "said", "say", "says", "going", "gonna", "make", "made",
    )

    /** His message's meaningful words, longest first (more specific), at most [MAX_TERMS]. */
    fun terms(message: String): List<String> =
        Regex("""[\p{L}\p{N}']+""").findAll(message.lowercase())
            .map { it.value.trim('\'') }
            .filter { it.length >= 3 && it !in STOP && it.any(Char::isLetter) }
            .distinct()
            .sortedByDescending { it.length }
            .take(MAX_TERMS)
            .toList()

    /** An FTS4 query: any of the words, each as a prefix ("abbu*" finds "Abbu's"). Null when there's nothing to look for. */
    fun matchQuery(terms: List<String>): String? =
        terms.flatMap { t -> t.split('\'') }
            .map { it.filter { c -> c.isLetterOrDigit() } }
            .filter { it.length >= 3 }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" OR ") { "$it*" }

    /**
     * Ranks candidates by how many different words of his message they contain, newest first among equals. Strong
     * means at least two of his words, or the only one when he used just one meaningful word. [excludeSession]: the
     * conversation he's in (it's already in front of the coach).
     */
    fun rank(hits: List<SearchHit>, terms: List<String>, excludeSession: Long?, limit: Int = MAX_RESULTS): List<SearchHit> {
        if (terms.isEmpty()) return emptyList()
        val need = if (terms.size == 1) 1 else 2
        return hits.asSequence()
            .filter { excludeSession == null || sessionOf(it) != excludeSession }
            .map { h -> h to score(h.text, terms) }
            .filter { it.second >= need }
            .sortedWith(compareByDescending<Pair<SearchHit, Int>> { it.second }.thenByDescending { it.first.day })
            .map { it.first }
            .distinctBy { it.text.trim().lowercase() }
            .take(limit)
            .toList()
    }

    /**
     * Phase 2: full-text matches and meaning matches, merged by reciprocal rank fusion and then nudged by recency.
     * The lexical list is exactly what [rank] would return before its cut-off; the meaning list adds documents that
     * share no words with the message. With no meaning matches (no model) this is [rank], unchanged.
     */
    fun rankHybrid(
        lexical: List<SearchHit>, semantic: List<Pair<SearchHit, Double>>, terms: List<String>, excludeSession: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SearchHit> {
        if (semantic.isEmpty()) return rank(lexical, terms, excludeSession)
        fun id(h: SearchHit) = "${h.kind}|${h.refId}"
        val byId = LinkedHashMap<String, SearchHit>()
        val lexIds = rank(lexical, terms, excludeSession, limit = Int.MAX_VALUE).onEach { byId.putIfAbsent(id(it), it) }.map(::id)
        val semIds = semantic.map { it.first }
            .filter { excludeSession == null || sessionOf(it) != excludeSession }
            .onEach { byId.putIfAbsent(id(it), it) }.map(::id)
        val fused = HybridRetrieval.fuse(listOf(lexIds, semIds))
        val ranked = HybridRetrieval.rerank(fused.map { (key, score) ->
            val h = byId.getValue(key)
            HybridRetrieval.Candidate(key, score, dayMillis(h.day, zone))
        }, System.currentTimeMillis())
        return ranked.map { byId.getValue(it.id) }
            .distinctBy { it.text.trim().lowercase() }
            .take(MAX_RESULTS)
    }

    private fun dayMillis(day: String, zone: ZoneId): Long =
        runCatching { java.time.LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrDefault(0L)

    fun score(text: String, terms: List<String>): Int {
        val words = Regex("""[\p{L}\p{N}]+""").findAll(text.lowercase()).map { it.value }.toList()
        return terms.count { t -> words.any { it.startsWith(t.filter(Char::isLetterOrDigit)) } }
    }

    /** The block for the request, oldest first so it reads like a timeline. Null when nothing strong was found. */
    fun block(hits: List<SearchHit>): String? {
        if (hits.isEmpty()) return null
        return "Possibly relevant from the past (from your archive; mention only if it helps):\n" +
            hits.sortedBy { it.day }.joinToString("\n") { "- ${it.day}, ${label(it)}: ${clip(it.text.removePrefix(ReflectionPlanner.HISTORY_PREFIX))}" }
    }

    /**
     * UPDATE-21: the block of durable memories tokenized search pulled up for this message. Unlike [block],
     * these are his live, active notes ("What I Know"), so they read as insights and realizations rather than
     * archive material. Null when there is nothing to show.
     */
    const val INSIGHTS_HEADER = "RELEVANT INSIGHTS & REALIZATIONS:"

    /** Active matching notes only; confidence stays visible so a guess is never presented as a fact. */
    fun rankNotes(notes: List<Note>, terms: List<String>, limit: Int = MAX_RESULTS): List<Note> = notes.asSequence()
        .filter { it.status == Note.ACTIVE }
        .distinctBy { it.id }
        .map { it to score(it.text, terms) }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<Note, Int>> { it.second }
            .thenByDescending { it.first.lastSeen }.thenByDescending { it.first.id })
        .map { it.first }.take(limit).toList()

    /**
     * Phase 2: keyword matches and meaning matches among his live notes, merged by reciprocal rank fusion and nudged
     * by recency and evidence grade (guess, likely, confirmed). Without meaning matches this is [rankNotes].
     */
    fun rankNotesHybrid(lexical: List<Note>, semantic: List<Pair<Note, Double>>, terms: List<String>): List<Note> {
        val related = semantic.map { it.first }.filter { it.status == Note.ACTIVE }
        if (related.isEmpty()) return rankNotes(lexical, terms)
        val byId = LinkedHashMap<Long, Note>()
        val lexIds = rankNotes(lexical, terms, limit = Int.MAX_VALUE).onEach { byId.putIfAbsent(it.id, it) }.map { it.id }
        val semIds = related.onEach { byId.putIfAbsent(it.id, it) }.map { it.id }
        val ranked = HybridRetrieval.rerank(HybridRetrieval.fuse(listOf(lexIds, semIds)).map { (id, score) ->
            val n = byId.getValue(id)
            HybridRetrieval.Candidate(id.toString(), score, n.lastSeen, Note.CONFIDENCES.indexOf(n.confidence).coerceAtLeast(0))
        }, System.currentTimeMillis())
        return ranked.mapNotNull { byId[it.id.toLong()] }.distinctBy { it.text.trim().lowercase() }.take(MAX_RESULTS)
    }

    fun insights(notes: List<Note>): String? {
        val active = notes.filter { it.status == Note.ACTIVE }.distinctBy { it.id }.take(MAX_RESULTS)
        if (active.isEmpty()) return null
        return INSIGHTS_HEADER + "\n" + active.joinToString("\n") { "- ${clip(it.text)} [${it.confidence}]" }
    }

    /** A history note is an earlier value of a profile line: say it used to be true and when it changed. */
    private fun label(h: SearchHit) =
        if (h.kind == SearchDocs.NOTE && h.text.startsWith(ReflectionPlanner.HISTORY_PREFIX)) "used to be true, changed then" else label(h.kind)

    private fun label(kind: String) = when (kind) {
        SearchDocs.SUMMARY -> "a conversation"
        SearchDocs.QUOTE -> "he said"
        SearchDocs.EVENT -> "a moment"
        SearchDocs.LETTER -> "a letter"
        SearchDocs.CHAPTER -> "a chapter"
        SearchDocs.NOTE -> "an older note, set aside then"
        else -> kind
    }

    private fun clip(s: String): String {
        val t = s.replace(Regex("""\s+"""), " ").trim()
        return if (t.length <= MAX_CHARS) t else t.take(MAX_CHARS).substringBeforeLast(' ') + "…"
    }

    /** Summaries, quotes and moments carry their conversation: "12" or "12:40". */
    fun sessionOf(h: SearchHit): Long? = h.refId.substringBefore(':').toLongOrNull()?.takeIf {
        h.kind == SearchDocs.SUMMARY || h.kind == SearchDocs.QUOTE || h.kind == SearchDocs.EVENT
    }
}

/** The archive's search documents, made from the real tables (so the index can always be rebuilt). */
object SearchDocs {
    const val SUMMARY = "summary"
    const val QUOTE = "quote"
    const val EVENT = "event"
    const val LETTER = "letter"
    const val CHAPTER = "chapter"
    const val NOTE = "note"

    fun summary(s: Session, zone: ZoneId): SearchDoc? = s.summary?.takeIf { it.isNotBlank() && !s.offTheRecord }?.let {
        SearchDoc(kind = SUMMARY, refId = s.id.toString(), day = ContextFormatter.date(s.startedAt, zone).toString(),
            text = listOfNotNull(s.title, it.trim()).joinToString(". "))
    }

    fun quote(q: Quote, zone: ZoneId) = SearchDoc(
        kind = QUOTE, refId = "${q.sessionId}:${q.id}", day = ContextFormatter.date(q.createdAt, zone).toString(), text = "\"${q.text.trim()}\"",
    )

    fun event(e: BehaviorEvent, zone: ZoneId): SearchDoc? = e.takeIf { !it.deletedByUser }?.let {
        SearchDoc(kind = EVENT, refId = "${e.sessionId}:${e.id}", day = ContextFormatter.date(e.createdAt, zone).toString(),
            text = ContextFormatter.eventLine(e, zone).substringAfter(": "))
    }

    fun letter(l: Letter) = SearchDoc(kind = LETTER, refId = l.id.toString(), day = l.periodEnd, text = "${l.title}. ${l.content}")

    fun chapter(c: Chapter) = SearchDoc(kind = CHAPTER, refId = c.id.toString(), day = c.periodEnd, text = "${c.title}. ${c.content}")

    /** Archived memory: retired notes, profile lines and strengths (never ones he deleted). */
    fun retiredNote(n: Note, zone: ZoneId): SearchDoc? = n.takeIf { it.status == Note.RETIRED }?.let {
        SearchDoc(kind = NOTE, refId = "n${n.id}", day = ContextFormatter.date(n.validTo ?: n.lastSeen, zone).toString(), text = n.text)
    }

    fun retiredProfile(p: ProfileEntry, zone: ZoneId): SearchDoc? = p.takeIf { it.retired && !it.deletedByUser && it.value.isNotBlank() }?.let {
        SearchDoc(kind = NOTE, refId = "p${p.key}", day = ContextFormatter.date(p.validTo ?: p.updatedAt, zone).toString(), text = "${p.key}: ${p.value}")
    }

    fun retiredStrength(s: Strength, zone: ZoneId): SearchDoc? = s.takeIf { it.retired && !it.deletedByUser }?.let {
        SearchDoc(kind = NOTE, refId = "s${s.id}", day = ContextFormatter.date(s.createdAt, zone).toString(), text = "Strength: ${s.text}")
    }
}
