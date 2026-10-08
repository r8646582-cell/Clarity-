package com.umair.purpose.data.repo

import androidx.room.withTransaction
import com.umair.purpose.chat.ChatPromptBuilder
import com.umair.purpose.data.db.AreaStatus
import com.umair.purpose.data.db.BehaviorEvent
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Person
import com.umair.purpose.data.db.ProfileEntry
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.Session
import com.umair.purpose.data.db.Strength
import com.umair.purpose.memory.ChatExtras
import com.umair.purpose.memory.ContextFormatter
import com.umair.purpose.memory.Memory
import com.umair.purpose.memory.MemorySnapshot
import com.umair.purpose.memory.Provenance
import com.umair.purpose.memory.RecordStats
import com.umair.purpose.memory.ReflectionPlanner
import com.umair.purpose.memory.ReflectionResult
import com.umair.purpose.memory.RelevantMemories
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What the coach knows about him: read for prompts, written by reflection, edited by him in What I know. */
@Singleton
class MemoryRepository @Inject constructor(
    private val db: PurposeDatabase,
    private val journeys: JourneyRepository,
    private val onboarding: OnboardingRepository,
    private val settings: SettingsRepository,
    private val search: com.umair.purpose.memory.SearchIndex,
    private val prefixCache: com.umair.purpose.chat.ChatPrefixCache = com.umair.purpose.chat.ChatPrefixCache(),
) {
    private val profile = db.profileDao()
    private val people = db.personDao()
    private val notes = db.noteDao()
    private val promises = db.promiseDao()
    private val areas = db.areaDao()
    private val sessions = db.sessionDao()
    private val strengths = db.strengthDao()
    private val behavior = db.behaviorDao()

    init {
        // Gardening and snapshot workers also change memory outside this repository.
        db.invalidationTracker.addObserver(object : androidx.room.InvalidationTracker.Observer(
            "profile_entry", "person", "note", "strength", "area_status", "snapshot", "chapter",
        ) {
            override fun onInvalidated(tables: Set<String>) { prefixCache.clear() }
        })
    }

    /** User corrections and successful learning invalidate the cached view immediately after commit. */
    private suspend fun <T> changeMemory(block: suspend () -> T): T {
        val result = db.withTransaction { block() }
        prefixCache.clear()
        return result
    }

    suspend fun snapshot() = MemorySnapshot(profile.all(), people.all(), notes.all(), promises.all(), areas.all(), strengths.all())

    /**
     * What's known now. Moments and quotes are the newest ones (all of them stay stored); with [since], every one
     * from then on (letters need the whole period, a yearly letter the whole year).
     */
    suspend fun memory(since: Long? = null): Memory = Memory(
        profile = profile.all(),
        people = people.all(),
        notes = notes.recentMemories(Int.MAX_VALUE),
        areas = areas.all(),
        promises = promises.all(),
        strengths = strengths.all(),
        behaviorEvents = (if (since != null) behavior.since(since) else behavior.recent(RECENT_EVENTS))
            .sortedWith(compareByDescending<BehaviorEvent> { it.createdAt }.thenByDescending { it.id }),
        quotes = if (since != null) db.quoteDao().since(since).sortedByDescending { it.createdAt } else db.quoteDao().recent(200),
        ideas = db.ideaDao().recent(200),
    )

    suspend fun record(today: LocalDate, zone: ZoneId): List<String> =
        RecordStats.lines(promises.all(), sessions.all(), db.pulseDao().all(), today, zone)

    /** Mutable facts override the session's cached snapshot, including explicit absence after deletion. */
    suspend fun currentChatState(today: LocalDate): String = buildString {
        append("Current app state (authoritative; replaces older journey and onboarding state):\n")
        append("Journey: ").append(journeys.contextLine(today) ?: "none").append('\n')
        append("Available journeys (exact names):\n")
        val catalog = journeys.catalog()
        if (catalog.isEmpty()) append("none\n") else catalog.forEach { append("- ").append(it.name).append('\n') }
        append("Onboarding:\n").append(onboarding.soFar().joinToString("\n").ifBlank { "none" })
    }

    /**
     * Items 1–3 of the chat request. Built once per session so the prefix stays cacheable, and trimmed to the
     * context budget (UPDATE-15: the same size after ten years as after ten days).
     */
    suspend fun chatPrefix(session: Session, persona: String, today: LocalDate, zone: ZoneId): ChatPromptBuilder.StablePrefix {
        val fitted = fittedContext(session, today, zone)
        return ChatPromptBuilder.StablePrefix(persona = persona, contextBlock = fitted.contextBlock, recentSummaries = fitted.summaries)
    }

    /** The context block and recent summaries for [session], within the budget. Also what the Health check measures. */
    suspend fun fittedContext(session: Session?, today: LocalDate, zone: ZoneId): ContextFormatter.Fitted {
        val longEarlier = session?.summary?.takeIf {
            it.isNotBlank() && db.messageDao().countForSession(session.id) > ChatPromptBuilder.MAX_SESSION_MESSAGES
        }?.let { "Earlier in this conversation (summary): ${it.trim()}" }
        return ContextFormatter.fitted(
            memory = memory(),
            extras = ChatExtras(
                snapshot = db.snapshotDao().latest(),
                onboardingSoFar = onboarding.soFar(),
                pulses = db.pulseDao().since(today.minusDays(13).toString()),
                journeyLine = journeys.contextLine(today),
                record = record(today, zone),
                letterText = session?.letterId?.let { db.letterDao().get(it) }?.let { "${it.title}\n\n${it.content}" },
                availableJourneys = journeys.catalog().map { it.name },
                chapters = db.chapterDao().latest(2),
            ),
            summarySessions = sessions.recentSummarized(6).filter { it.id != session?.id }.take(5),
            zone = zone,
            // A long conversation he came back to: only its latest messages are sent, so say what came before.
            extraSummaries = listOfNotNull(longEarlier),
        )
    }

    /** UPDATE-15: "Possibly relevant from the past" for his latest message, or null when nothing strong. */
    suspend fun relevantPast(message: String, sessionId: Long?): String? = search.relevant(message, sessionId)

    /** UPDATE-21: tokenized keyword search over his live durable notes, for the chat prompt. */
    suspend fun searchMemories(keyword: String): List<Note> = notes.searchMemories(keyword.trim())

    /** Search every meaningful term, rank actual matches, and never inject unrelated fallback notes. */
    suspend fun relevantInsights(message: String): String? {
        val terms = RelevantMemories.terms(message)
        val matches = terms.flatMap { searchMemories(it) }.distinctBy { it.id }
        return RelevantMemories.insights(RelevantMemories.rankNotes(matches, terms))
    }

    /**
     * Applies a reflection in one transaction. The plan is made from a fresh read inside the transaction,
     * so an edit he made while the AI was thinking is never overwritten.
     */
    suspend fun applyReflection(sessionId: Long, result: ReflectionResult, now: Long, upToMessageId: Long?): List<Long>? = changeMemory {
        // A forgotten conversation must not be recreated by an in-flight reflection.
        val session = sessions.get(sessionId) ?: return@changeMemory null
        if (session.offTheRecord) return@changeMemory null
        // A retried or older result cannot reinforce facts twice or move the reflection cursor backwards.
        if (upToMessageId != null && upToMessageId <= (session.reflectedUpToMessageId ?: 0L))
            return@changeMemory null
        val plan = ReflectionPlanner.plan(result, snapshot(), sessionId, now)
        if (plan.profile.isNotEmpty()) profile.upsert(plan.profile)
        if (plan.people.isNotEmpty()) people.upsert(plan.people)
        if (plan.notes.isNotEmpty()) notes.upsert(plan.notes)
        if (plan.promises.isNotEmpty()) promises.upsert(plan.promises)
        if (plan.areas.isNotEmpty()) areas.upsert(plan.areas)
        // A continued conversation must never add the same moment or quote twice.
        val earlierEvents = behavior.forSession(sessionId)
        val events = plan.behaviorEvents.filter { e ->
            earlierEvents.none { ReflectionPlanner.similar(eventKey(it), eventKey(e)) }
        }
        if (events.isNotEmpty()) behavior.insert(events)
        if (plan.strengths.isNotEmpty()) strengths.insert(plan.strengths)
        val earlierQuotes = db.quoteDao().forSession(sessionId).map { it.text.trim().lowercase() }.toSet()
        val quotes = plan.quotes.filter { it.text.trim().lowercase() !in earlierQuotes }
        if (quotes.isNotEmpty()) db.quoteDao().insert(quotes)
        if (plan.ideas.isNotEmpty()) db.ideaDao().insert(plan.ideas)
        sessions.markReflected(sessionId, plan.summary, plan.significance, plan.tone, upToMessageId)
        plan.title?.let { sessions.setTitleFromReflection(sessionId, it) }
        // Promises that closed: their reminders must go.
        plan.promises.filter { it.id != 0L && it.status != Promise.OPEN }.map { it.id }
    }

    /**
     * UPDATE-20: a memory the coach synthesised autonomously with `[[action: {type: synthesize_memory}]]`. It
     * reinforces an active note already saying the same thing (times seen, confidence) instead of duplicating it,
     * or adds a fresh one. `replaces_ids` retire the notes it absorbed — never a note he edited himself.
     */
    suspend fun synthesizeMemory(insight: String, category: String?, replacesIds: List<Long>, sessionId: Long?, now: Long): Note? = changeMemory {
        val text = insight.trim()
        if (text.isEmpty()) return@changeMemory null
        val type = category?.trim()?.lowercase()?.takeIf { it in Note.TYPES } ?: Note.TYPES.first()
        val allNotes = notes.learningCandidates()
        if (allNotes.any { it.status == Note.DELETED_BY_USER && ReflectionPlanner.similar(it.text, text) }) return@changeMemory null
        val existing = allNotes.firstOrNull { it.status == Note.ACTIVE && ReflectionPlanner.similar(it.text, text) }
        // His own edit is final: reinforce nothing, change nothing.
        if (existing != null && (existing.editedByUser || existing.confidence == "confirmed")) return@changeMemory existing
        val proposed = if (existing != null) {
            // The coach saying the same thing again is not new evidence about him: only the evidence path
            // (reflection, from his own words) raises confidence or times seen. Keep his note as it was; just
            // remember that this conversation touched it.
            existing.copy(
                lastSeen = now,
                sourceSessionIds = sessionId?.let { Provenance.extend(existing.sourceSessionIds, it) } ?: existing.sourceSessionIds,
            )
        } else {
            Note(
                type = type,
                text = text,
                confidence = "guess",
                status = Note.ACTIVE,
                timesSeen = 1,
                firstSeen = now,
                lastSeen = now,
                sourceSessionIds = sessionId?.toString().orEmpty(),
            )
        }
        val absorbed = replacesIds.distinct().mapNotNull { notes.get(it) }
            .filter { it.id != proposed.id && it.status == Note.ACTIVE && !it.editedByUser && it.confidence != "confirmed" }
        val combined = if (absorbed.isEmpty()) proposed else proposed.copy(
            sourceSessionIds = Provenance.merge(listOf(proposed.sourceSessionIds) + absorbed.map { it.sourceSessionIds }),
            firstSeen = minOf(proposed.firstSeen, absorbed.minOf { it.firstSeen }),
        )
        val saved = if (combined.id == 0L) combined.copy(id = notes.insert(combined))
            else combined.also { notes.upsert(listOf(it)) }
        if (absorbed.isNotEmpty()) notes.upsert(absorbed.map { it.copy(status = Note.RETIRED) })
        // Replacement archives become searchable immediately, without waiting for monthly gardening.
        if (absorbed.isNotEmpty()) search.indexArchive()
        saved
    }

    /**
     * The canonical `store_memory` action: stores an insight the coach synthesised, remembering the emotional
     * charge it carried. Goes through the same reinforcement / de-duplication rules as [synthesizeMemory], so a
     * belief said twice is strengthened rather than duplicated.
     */
    suspend fun insertMemory(
        insight: String,
        category: String?,
        emotionalValence: Int?,
        sessionId: Long?,
        now: Long,
        replacesIds: List<Long> = emptyList(),
    ): Note? = changeMemory {
        // The coach may name a belief or a value; both live as a "thread" in the note types.
        val normalized = when (category?.trim()?.lowercase()) {
            "belief", "value", "values" -> "thread"
            else -> category
        }
        val note = synthesizeMemory(insight, normalized, replacesIds, sessionId, now) ?: return@changeMemory null
        if (note.editedByUser || note.confidence == "confirmed") return@changeMemory note
        // The valence only matters while it is fresh; fold it into the note so it is not lost between runs.
        val valence = emotionalValence?.coerceIn(-2, 2) ?: return@changeMemory note
        val tag = when {
            valence > 0 -> " (felt good)"
            valence < 0 -> " (felt heavy)"
            else -> return@changeMemory note
        }
        if (tag in note.text) return@changeMemory note
        val tagged = note.copy(text = (note.text + tag).take(400))
        notes.upsert(listOf(tagged))
        tagged
    }

    /**
     * Lightweight, network-free deduplication for the nightly garden: merges people who are the same person in
     * different casing ("Father" / "father"), and retires exact-duplicate active notes, keeping the strongest and
     * never touching anything he edited or deleted. Returns how many rows were folded away.
     */
    suspend fun deduplicate(now: Long): Int = changeMemory {
        var removed = 0
        // People: same name ignoring case/space. Keep the fullest row, fold the others' notes into it, delete them.
        people.all().filter { !it.deletedByUser }
            .groupBy { it.name.trim().lowercase() }
            .filterValues { it.size > 1 }
            .forEach { (_, group) ->
                if (group.any { it.editedByUser }) return@forEach
                val keeper = group.maxByOrNull { listOfNotNull(it.relation, it.notes).sumOf { s -> s.length } } ?: return@forEach
                val extra = group.filter { it.id != keeper.id }.mapNotNull { it.notes?.trim()?.takeIf { n -> n.isNotEmpty() } }
                people.upsert(listOf(keeper.copy(
                    notes = (listOfNotNull(keeper.notes) + extra).distinct().joinToString("; ").ifEmpty { null },
                    updatedAt = now,
                    sourceSessionIds = Provenance.merge(group.map { it.sourceSessionIds }),
                )))
                people.delete(group.filter { it.id != keeper.id }.map { it.id })
                removed += group.size - 1
            }
        // Notes: identical text repeats. Keep the most trusted one, retire the rest (kept, searchable, not sent).
        val rank = mapOf("confirmed" to 3, "likely" to 2, "guess" to 1)
        notes.recentMemories(Int.MAX_VALUE).filter { !it.editedByUser && it.confidence != "confirmed" }
            .groupBy { it.text.trim().lowercase() }
            .filterValues { it.size > 1 }
            .forEach { (_, group) ->
                val keeper = group.maxByOrNull { (rank[it.confidence] ?: 0) * 10_000 + it.timesSeen } ?: return@forEach
                val retired = group.filter { it.id != keeper.id }.map { it.copy(status = Note.RETIRED) }
                if (retired.isNotEmpty()) {
                    notes.upsert(retired + keeper.copy(
                        timesSeen = group.sumOf { it.timesSeen },
                        firstSeen = group.minOf { it.firstSeen },
                        lastSeen = group.maxOf { it.lastSeen },
                        sourceSessionIds = Provenance.merge(group.map { it.sourceSessionIds }),
                    ))
                    removed += retired.size
                }
            }
        removed
    }

    /**
     * UPDATE-19: corrections he made before corrections were protected. Idempotent: a fixed value no longer
     * matches, so this is safe on every launch. Today: the blocker app is Dechainer, not Purpose.
     */
    suspend fun applyKnownCorrections(now: Long) = changeMemory {
        val fixed = profile.all()
            // His own edit is final and is never rewritten. (A value that is merely confirmed is still corrected:
            // that marker is there precisely because this field had the wrong app in it, and dechainerFix is
            // idempotent once the text says Dechainer.)
            .filterNot { it.editedByUser || it.deletedByUser }
            .mapNotNull { e -> com.umair.purpose.memory.Corrections.dechainerFix(e.value)?.let { e.copy(value = it, updatedAt = now) } }
        if (fixed.isNotEmpty()) profile.upsert(fixed)
    }

    companion object {
        /** Moments kept in memory for prompts; the chat context sends 15 of them. */
        const val RECENT_EVENTS = 50
        /** UPDATE-21: durable memories injected when tokenized search finds no keyword match. */
        const val INSIGHTS_FALLBACK = 5
        /**
         * How many moments "What I know" loads. Behaviour events are never capped or archived, so they grow by
         * thousands a year; the newest few hundred are what the screen shows, and the rest stay searchable.
         */
        const val MOMENTS_SHOWN = 200
    }

    private fun eventKey(e: BehaviorEvent) = listOfNotNull(e.situation, e.action, e.outcome).joinToString(" ")

    // ---- What I know: everything he changes here is final ----

    fun observeProfile(): Flow<List<ProfileEntry>> = profile.observeVisible()
    fun observePeople(): Flow<List<Person>> = people.observeVisible()
    fun observeNotes(): Flow<List<Note>> = notes.observeActive()
    fun observeRetiredNotes(): Flow<List<Note>> = notes.observeRetired()
    fun pagedRetiredNotes(): Flow<androidx.paging.PagingData<Note>> =
        androidx.paging.Pager(androidx.paging.PagingConfig(pageSize = 40, enablePlaceholders = false)) { notes.pagedRetired() }.flow
    fun observeRetiredCount(): Flow<Int> = notes.observeRetiredCount()
    fun searchRetiredNotes(q: String): Flow<List<Note>> = notes.searchRetired(q)
    fun observeAreas(): Flow<List<AreaStatus>> = areas.observeVisible()
    fun observeStrengths(): Flow<List<Strength>> = strengths.observeVisible()
    fun observeBehavior(): Flow<List<BehaviorEvent>> = behavior.observeRecentVisible(MOMENTS_SHOWN)

    suspend fun editProfile(key: String, value: String, now: Long) = changeMemory {
        val existing = profile.get(key) ?: return@changeMemory
        profile.upsert(listOf(existing.copy(value = value.trim(), updatedAt = now, editedByUser = true)))
        search.indexArchive()
    }

    suspend fun deleteProfile(key: String, now: Long) = changeMemory {
        val existing = profile.get(key) ?: return@changeMemory
        // Only the key stays, as a marker so reflection never re-adds it.
        profile.upsert(listOf(existing.copy(value = "", deletedByUser = true, updatedAt = now)))
        search.indexArchive()
    }

    /** Undo for a swipe-delete. */
    suspend fun restoreProfile(entry: ProfileEntry) = changeMemory {
        profile.upsert(listOf(entry))
        search.indexArchive()
    }

    suspend fun editPerson(id: Long, name: String, relation: String, notes: String, now: Long) = changeMemory {
        val existing = people.get(id) ?: return@changeMemory
        people.upsert(listOf(existing.copy(
            name = name.trim().ifEmpty { existing.name },
            relation = relation.trim().ifEmpty { null },
            notes = notes.trim().ifEmpty { null },
            updatedAt = now,
            editedByUser = true,
        )))
    }

    suspend fun deletePerson(id: Long, now: Long) = changeMemory {
        val existing = people.get(id) ?: return@changeMemory
        people.upsert(listOf(existing.copy(relation = null, notes = null, deletedByUser = true, updatedAt = now)))
    }

    suspend fun restorePerson(person: Person) = changeMemory { people.upsert(listOf(person)) }

    suspend fun editNote(id: Long, text: String) = changeMemory {
        val existing = notes.get(id) ?: return@changeMemory
        if (text.isBlank()) return@changeMemory
        notes.upsert(listOf(existing.copy(text = text.trim(), editedByUser = true)))
        search.indexArchive()
    }

    /** Kept as a tombstone so reflection can see it and never re-create it. */
    suspend fun deleteNote(id: Long) = changeMemory {
        val existing = notes.get(id) ?: return@changeMemory
        notes.upsert(listOf(existing.copy(status = Note.DELETED_BY_USER)))
        search.indexArchive()
    }

    suspend fun restoreNote(note: Note) = changeMemory {
        notes.upsert(listOf(note))
        search.indexArchive()
    }

    suspend fun editArea(area: String, status: String, note: String, now: Long) = changeMemory {
        if (area !in AreaStatus.AREAS || status !in AreaStatus.STATUSES) return@changeMemory
        areas.upsert(
            listOf(
                AreaStatus(
                    area = area, status = status, note = note.trim().ifEmpty { null }, updatedAt = now,
                    editedByUser = true, deletedByUser = false,
                )
            )
        )
    }

    /**
     * Kept as a tombstone, the same way a deleted note is, so reflection can see it and never re-create it.
     * Anything he deletes in What I know is final.
     */
    suspend fun deleteArea(area: String, now: Long) = changeMemory {
        val existing = areas.get(area) ?: return@changeMemory
        areas.upsert(listOf(existing.copy(deletedByUser = true, updatedAt = now)))
    }

    suspend fun restoreArea(a: AreaStatus) = changeMemory { areas.upsert(listOf(a.copy(deletedByUser = false))) }

    suspend fun editStrength(id: Long, text: String) = changeMemory {
        val s = strengths.get(id) ?: return@changeMemory
        if (text.isBlank()) return@changeMemory
        strengths.upsert(s.copy(text = text.trim(), editedByUser = true))
        search.indexArchive()
    }

    suspend fun deleteStrength(id: Long) = changeMemory {
        val s = strengths.get(id) ?: return@changeMemory
        strengths.upsert(s.copy(deletedByUser = true))
        search.indexArchive()
    }

    suspend fun restoreStrength(s: Strength) = changeMemory {
        strengths.upsert(s)
        search.indexArchive()
    }

    suspend fun deleteBehavior(id: Long) = changeMemory {
        val e = behavior.get(id) ?: return@changeMemory
        behavior.upsert(e.copy(deletedByUser = true))
        search.remove(com.umair.purpose.memory.SearchDocs.EVENT, "${e.sessionId}:${e.id}")
    }

    suspend fun restoreBehavior(e: BehaviorEvent) = changeMemory {
        behavior.upsert(e)
        search.indexSession(e.sessionId)
    }
}
