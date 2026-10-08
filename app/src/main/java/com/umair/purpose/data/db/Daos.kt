package com.umair.purpose.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.umair.purpose.cost.ModelUsage
import kotlinx.coroutines.flow.Flow

/** A drawer row: the session and when it was last active. */
data class ConversationRow(
    @androidx.room.Embedded val session: Session,
    val lastAt: Long,
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: Session): Long

    @Upsert
    suspend fun upsert(session: Session)

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun get(id: Long): Session?

    @Query("SELECT * FROM session WHERE id = :id")
    fun observe(id: Long): Flow<Session?>

    @Query("SELECT * FROM session WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun openSession(): Session?

    @Query("SELECT * FROM session WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeOpenSession(): Flow<Session?>

    @Query("UPDATE session SET endedAt = :endedAt WHERE id = :id AND endedAt IS NULL")
    suspend fun end(id: Long, endedAt: Long)

    /** A session with nothing said in it yet: it counts as started now. */
    @Query("UPDATE session SET startedAt = :now WHERE id = :id")
    suspend fun restartClock(id: Long, now: Long)

    @Query("UPDATE session SET mode = :mode, modeDetail = :detail, journeyDay = :journeyDay WHERE id = :id")
    suspend fun setMode(id: Long, mode: String?, detail: String?, journeyDay: Int?)

    @Query("UPDATE session SET userMessageCount = userMessageCount + 1 WHERE id = :id")
    suspend fun countUserMessage(id: Long)

    @Query("SELECT * FROM session WHERE endedAt IS NOT NULL AND reflected = 0 ORDER BY startedAt")
    suspend fun unreflected(): List<Session>

    /**
     * Learned up to [upTo]. It only counts as reflected if it's still ended and nothing new from him came after
     * [upTo]: continued while reflection ran, it waits for its own pass over what's new (UPDATE-12).
     */
    @Query(
        "UPDATE session SET reflected = CASE WHEN endedAt IS NULL OR EXISTS (SELECT 1 FROM message WHERE " +
            "message.sessionId = :id AND message.role = 'user' AND message.id > IFNULL(:upTo, 0)) THEN 0 ELSE 1 END, " +
            "summary = :summary, significance = :significance, tone = :tone, reflectedUpToMessageId = :upTo WHERE id = :id"
    )
    suspend fun markReflected(id: Long, summary: String?, significance: Int?, tone: String?, upTo: Long?)

    @Query("UPDATE session SET title = :title WHERE id = :id AND titleByUser = 0")
    suspend fun setTitleFromReflection(id: Long, title: String)

    @Query("UPDATE session SET title = :title WHERE id = :id AND title IS NULL")
    suspend fun setTitleIfMissing(id: Long, title: String)

    @Query("UPDATE session SET title = :title, titleByUser = 1 WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    /** Opening a past conversation again: it's the active one now, and reflection will look at what's new. */
    @Query("UPDATE session SET endedAt = NULL, reflected = 0 WHERE id = :id")
    suspend fun reopen(id: Long)

    @Query(
        """SELECT session.*, COALESCE((SELECT MAX(createdAt) FROM message WHERE sessionId = session.id), startedAt) AS lastAt
           FROM session WHERE userMessageCount > 0 ORDER BY lastAt DESC"""
    )
    fun observeConversations(): Flow<List<ConversationRow>>

    /**
     * Talk's home shows at most one "Pick up where you left off" row, so it reads the few newest conversations
     * instead of every conversation he has ever had — the old path scanned the whole session table (plus a
     * correlated MAX(createdAt) per row) and Room re-ran it on every write to `message`, which is roughly once a
     * second while a reply streams. The drawer uses [pagedConversations].
     */
    @Query(
        """SELECT session.*, COALESCE((SELECT MAX(createdAt) FROM message WHERE sessionId = session.id), startedAt) AS lastAt
           FROM session WHERE userMessageCount > 0 ORDER BY lastAt DESC LIMIT :limit"""
    )
    fun observeRecentConversations(limit: Int): Flow<List<ConversationRow>>

    /** Titles and message text, locally. */
    @Query(
        """SELECT session.*, COALESCE((SELECT MAX(createdAt) FROM message WHERE sessionId = session.id), startedAt) AS lastAt
           FROM session WHERE userMessageCount > 0 AND (title LIKE '%' || :q || '%'
             OR id IN (SELECT sessionId FROM message WHERE content LIKE '%' || :q || '%'))
           ORDER BY lastAt DESC"""
    )
    fun searchConversations(q: String): Flow<List<ConversationRow>>

    @Query("SELECT * FROM session WHERE summary IS NOT NULL ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recentSummarized(limit: Int): List<Session>

    /** UPDATE-15: the drawer as pages; [q] blank = every conversation, else titles and message text. */
    @Query(
        """SELECT session.*, COALESCE((SELECT MAX(createdAt) FROM message WHERE sessionId = session.id), startedAt) AS lastAt
           FROM session WHERE userMessageCount > 0 AND (:q = '' OR title LIKE '%' || :q || '%'
             OR id IN (SELECT sessionId FROM message WHERE content LIKE '%' || :q || '%'))
           ORDER BY lastAt DESC"""
    )
    fun pagedConversations(q: String): androidx.paging.PagingSource<Int, ConversationRow>

    /**
     * UPDATE-15: the drawer, a page at a time (the speed test). **Not** a Paging data source: Paging 3 wraps its
     * query in an outer LIMIT/OFFSET, so this inner LIMIT would make page 2 and beyond come back empty. Anything
     * Paging-driven must use [pagedConversations].
     */
    @Query(
        """SELECT session.*, COALESCE((SELECT MAX(createdAt) FROM message WHERE sessionId = session.id), startedAt) AS lastAt
           FROM session WHERE userMessageCount > 0 ORDER BY lastAt DESC LIMIT :limit OFFSET :offset"""
    )
    suspend fun pageConversations(limit: Int, offset: Int): List<ConversationRow>

    @Query("SELECT id FROM session ORDER BY startedAt DESC LIMIT 1")
    suspend fun openSessionOrLatestId(): Long?

    @Query("SELECT * FROM session WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt")
    suspend fun between(from: Long, to: Long): List<Session>

    @Query("SELECT MIN(startedAt) FROM session")
    suspend fun firstStartedAt(): Long?

    @Query("SELECT * FROM session ORDER BY startedAt")
    suspend fun all(): List<Session>

    @Query("DELETE FROM session") suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: Message): Long

    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY createdAt, id")
    fun observeForSession(sessionId: Long): Flow<List<Message>>

    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY createdAt, id")
    suspend fun forSession(sessionId: Long): List<Message>

    @Query("SELECT MAX(createdAt) FROM message WHERE sessionId = :sessionId")
    suspend fun lastActivity(sessionId: Long): Long?

    @Query("SELECT COUNT(*) FROM message WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int

    @Query("SELECT createdAt FROM message WHERE role = 'user' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentUserMessageTimes(limit: Int): List<Long>

    @Query("SELECT createdAt FROM message WHERE role = 'user' AND createdAt >= :from ORDER BY createdAt")
    suspend fun userMessageTimesSince(from: Long): List<Long>

    @Query("SELECT * FROM message ORDER BY id")
    suspend fun all(): List<Message>

    /** Phase 3: his newest written messages from conversations that are remembered, to find where he said something. */
    @Query("SELECT m.* FROM message m JOIN session s ON s.id = m.sessionId WHERE m.role = 'user' AND s.offTheRecord = 0 AND m.createdAt <= :until ORDER BY m.createdAt DESC LIMIT 2000")
    suspend fun userMessagesUntil(until: Long): List<Message>

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun get(id: Long): Message?

    /** Partial text of a reply still arriving (never hidden lines). */
    @Query("UPDATE message SET content = :content WHERE id = :id AND status = 'streaming'")
    suspend fun updateStreaming(id: Long, content: String)

    @Query(
        """UPDATE message SET content = :content, status = 'complete', tier = :tier, model = :model, thinking = :thinking,
           inputTokens = :inputTokens, outputTokens = :outputTokens, firstTokenMs = :firstTokenMs, totalMs = :totalMs
           WHERE id = :id"""
    )
    suspend fun complete(
        id: Long, content: String, tier: String?, model: String?, thinking: Boolean,
        inputTokens: Int?, outputTokens: Int?, firstTokenMs: Long?, totalMs: Long?,
    )

    @Query("UPDATE message SET status = 'interrupted' WHERE id = :id")
    suspend fun markInterrupted(id: Long)

    /** On launch: a reply that was still streaming when the process died. */
    @Query("UPDATE message SET status = 'interrupted' WHERE status = 'streaming'")
    suspend fun interruptAllStreaming(): Int

    /** A reply that died before any words arrived: nothing to show, so it just goes (his message gets Retry). */
    @Query("DELETE FROM message WHERE status = 'streaming' AND trim(content) = ''")
    suspend fun deleteEmptyStreaming(): Int

    @Query("DELETE FROM message WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun latestPage(sessionId: Long, limit: Int): List<Message>

    /** UPDATE-16: conversations with messages written offline, waiting to go out. */
    @Query("SELECT DISTINCT sessionId FROM message WHERE status = 'queued'")
    suspend fun sessionsWithQueued(): List<Long>

    @Query("SELECT COUNT(*) FROM message WHERE status = 'queued'")
    fun observeQueuedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM message WHERE status = 'queued'")
    suspend fun queuedCount(): Int

    /** They're going out now, in order, as part of the conversation. */
    @Query("UPDATE message SET status = 'complete' WHERE sessionId = :sessionId AND status = 'queued'")
    suspend fun markQueuedSent(sessionId: Long)

    /**
     * UPDATE-15: a long conversation shows its latest [limit] messages; more load as he scrolls up. Newest first
     * here (the screen puts them back in order).
     */
    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeLatest(sessionId: Long, limit: Int): Flow<List<Message>>
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 0")
    fun observe(): Flow<Settings?>

    @Query("SELECT * FROM settings WHERE id = 0")
    suspend fun get(): Settings?

    @Upsert
    suspend fun upsert(settings: Settings)
}

data class UsageTotals(
    val requests: Int,
    val promptTokens: Long,
    val cacheHitTokens: Long,
    val cacheMissTokens: Long,
    val completionTokens: Long,
)

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(stat: UsageStat)

    @Query(
        """SELECT COUNT(*) AS requests,
                  COALESCE(SUM(promptTokens), 0) AS promptTokens,
                  COALESCE(SUM(cacheHitTokens), 0) AS cacheHitTokens,
                  COALESCE(SUM(cacheMissTokens), 0) AS cacheMissTokens,
                  COALESCE(SUM(completionTokens), 0) AS completionTokens
           FROM usage_stat WHERE createdAt >= :from AND createdAt < :to"""
    )
    fun observeTotals(from: Long, to: Long): Flow<UsageTotals>

    @Query(
        """SELECT model, offPeak, COUNT(*) AS requests,
                  COALESCE(SUM(cacheHitTokens), 0) AS cacheHitTokens,
                  COALESCE(SUM(cacheMissTokens), 0) AS cacheMissTokens,
                  COALESCE(SUM(completionTokens), 0) AS completionTokens
           FROM usage_stat WHERE createdAt >= :from AND createdAt < :to GROUP BY model, offPeak"""
    )
    fun observeByModel(from: Long, to: Long): Flow<List<ModelUsage>>

    @Query(
        """SELECT model, offPeak, COUNT(*) AS requests,
                  COALESCE(SUM(cacheHitTokens), 0) AS cacheHitTokens,
                  COALESCE(SUM(cacheMissTokens), 0) AS cacheMissTokens,
                  COALESCE(SUM(completionTokens), 0) AS completionTokens
           FROM usage_stat WHERE createdAt >= :from AND createdAt < :to GROUP BY model, offPeak"""
    )
    suspend fun byModel(from: Long, to: Long): List<ModelUsage>

    @Query("SELECT * FROM usage_stat ORDER BY id")
    suspend fun all(): List<UsageStat>

    @Query("SELECT * FROM usage_stat WHERE createdAt < :before ORDER BY id")
    suspend fun before(before: Long): List<UsageStat>

    @Query("DELETE FROM usage_stat WHERE createdAt < :before")
    suspend fun deleteBefore(before: Long)

    /** UPDATE-17: per feature (purpose and tier), model and price window, for the cost breakdown. */
    @Query(
        """SELECT purpose, tier, model, offPeak, COUNT(*) AS requests,
                  COALESCE(SUM(promptTokens), 0) AS promptTokens,
                  COALESCE(SUM(cacheHitTokens), 0) AS cacheHitTokens,
                  COALESCE(SUM(cacheMissTokens), 0) AS cacheMissTokens,
                  COALESCE(SUM(completionTokens), 0) AS completionTokens
           FROM usage_stat WHERE createdAt >= :from AND createdAt < :to GROUP BY purpose, tier, model, offPeak"""
    )
    fun observeBreakdown(from: Long, to: Long): Flow<List<com.umair.purpose.cost.FeatureUsage>>

    @Query(
        """SELECT purpose, tier, model, offPeak, COUNT(*) AS requests,
                  COALESCE(SUM(promptTokens), 0) AS promptTokens,
                  COALESCE(SUM(cacheHitTokens), 0) AS cacheHitTokens,
                  COALESCE(SUM(cacheMissTokens), 0) AS cacheMissTokens,
                  COALESCE(SUM(completionTokens), 0) AS completionTokens
           FROM usage_stat WHERE createdAt >= :from AND createdAt < :to GROUP BY purpose, tier, model, offPeak"""
    )
    suspend fun breakdown(from: Long, to: Long): List<com.umair.purpose.cost.FeatureUsage>

    @Query("SELECT COUNT(*) FROM usage_stat WHERE purpose = 'chat' AND createdAt >= :from")
    suspend fun chatRequestsSince(from: Long): Int

    @Query("SELECT COUNT(DISTINCT date(createdAt / 1000, 'unixepoch', 'localtime')) FROM usage_stat WHERE purpose = 'chat' AND createdAt >= :from")
    suspend fun chatDaysSince(from: Long): Int
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile_entry WHERE deletedByUser = 0 ORDER BY `key`")
    fun observeVisible(): Flow<List<ProfileEntry>>

    @Query("SELECT * FROM profile_entry")
    suspend fun all(): List<ProfileEntry>

    @Query("SELECT * FROM profile_entry WHERE `key` = :key")
    suspend fun get(key: String): ProfileEntry?

    @Upsert
    suspend fun upsert(entries: List<ProfileEntry>)
}

@Dao
interface PersonDao {
    @Query("SELECT * FROM person WHERE deletedByUser = 0 ORDER BY name COLLATE NOCASE")
    fun observeVisible(): Flow<List<Person>>

    @Query("SELECT * FROM person")
    suspend fun all(): List<Person>

    @Query("SELECT * FROM person WHERE id = :id")
    suspend fun get(id: Long): Person?

    @Upsert
    suspend fun upsert(people: List<Person>)

    /** Memory gardening: duplicates merged into another row. */
    @Query("DELETE FROM person WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: Note): Long

    @Query("SELECT * FROM note WHERE status = 'active' ORDER BY type, id")
    fun observeActive(): Flow<List<Note>>

    /** Tidied away by memory gardening: shown only in What I know's collapsed "Archived". */
    @Query("SELECT * FROM note WHERE status = 'retired' ORDER BY lastSeen DESC, id DESC")
    fun observeRetired(): Flow<List<Note>>

    /** UPDATE-15: the archive, a page at a time. */
    @Query("SELECT * FROM note WHERE status = 'retired' ORDER BY lastSeen DESC, id DESC")
    fun pagedRetired(): androidx.paging.PagingSource<Int, Note>

    @Query("SELECT COUNT(*) FROM note WHERE status = 'retired'")
    fun observeRetiredCount(): Flow<Int>

    /** What I know's search reaches the archive too. */
    @Query("SELECT * FROM note WHERE status = 'retired' AND text LIKE '%' || :q || '%' ORDER BY lastSeen DESC LIMIT 100")
    fun searchRetired(q: String): Flow<List<Note>>

    /**
     * UPDATE-21: tokenized keyword search over the durable, active memories for the chat prompt. No rigid
     * category filter: a word from his message matches the note text or its type. Newest first, capped small.
     */
    @Query(
        """SELECT * FROM note WHERE status = 'active'
           AND (text LIKE '%' || :query || '%' OR type LIKE '%' || :query || '%')
           ORDER BY lastSeen DESC, id DESC LIMIT 8"""
    )
    suspend fun searchMemories(query: String): List<Note>

    /** The fallback when nothing matched his keywords: the most recently touched durable memories. */
    @Query("SELECT * FROM note WHERE status = 'active' ORDER BY lastSeen DESC, id DESC LIMIT :limit")
    suspend fun recentMemories(limit: Int): List<Note>

    /** Learning needs live notes and user tombstones, never the entire lifetime archive. */
    @Query("SELECT * FROM note WHERE status IN ('active', 'deleted_by_user')")
    suspend fun learningCandidates(): List<Note>

    @Query("SELECT * FROM note")
    suspend fun all(): List<Note>

    @Query("SELECT * FROM note WHERE id = :id")
    suspend fun get(id: Long): Note?

    @Upsert
    suspend fun upsert(notes: List<Note>)
}

@Dao
interface PromiseDao {
    @Query("SELECT * FROM promise ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<Promise>>

    @Query("SELECT * FROM promise WHERE sourceSessionId = :sessionId")
    fun observeForSession(sessionId: Long): Flow<List<Promise>>

    @Query("SELECT * FROM promise")
    suspend fun all(): List<Promise>

    @Query("DELETE FROM promise WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM promise WHERE status = 'open'")
    suspend fun open(): List<Promise>

    @Query("SELECT * FROM promise WHERE status != 'open' ORDER BY COALESCE(resolvedAt, createdAt) DESC LIMIT :limit OFFSET :offset")
    suspend fun pastPage(limit: Int, offset: Int): List<Promise>

    /** UPDATE-15: past promises, a page at a time. */
    @Query("SELECT * FROM promise WHERE status != 'open' ORDER BY COALESCE(resolvedAt, createdAt) DESC, id DESC")
    fun pagedPast(): androidx.paging.PagingSource<Int, Promise>

    @Query("SELECT * FROM promise WHERE status = 'open' ORDER BY createdAt DESC, id DESC")
    fun observeOpen(): Flow<List<Promise>>

    /**
     * For "kept 6 of your last 7" and the current run: every closed status, so a dropped or renegotiated
     * promise is in the list and naturally stops a "kept in a row" streak at that point.
     */
    @Query("SELECT * FROM promise WHERE status IN ('kept', 'broken', 'dropped', 'renegotiated') ORDER BY resolvedAt DESC, id DESC LIMIT :limit")
    fun observeRecentResolved(limit: Int): Flow<List<Promise>>

    /** The reminder went off (or went stale): nothing left to schedule. */
    @Query("UPDATE promise SET remindAt = NULL WHERE id = :id")
    suspend fun clearReminder(id: Long)

    @Query("SELECT * FROM promise WHERE status = 'open' AND remindAt IS NOT NULL")
    suspend fun withReminders(): List<Promise>

    @Query("SELECT * FROM promise WHERE id = :id")
    suspend fun get(id: Long): Promise?

    @Insert
    suspend fun insert(promise: Promise): Long

    @Upsert
    suspend fun upsert(promises: List<Promise>)
}

@Dao
interface AreaDao {
    /** Everything, including the tombstones of areas he deleted: reflection needs to see those so it never
     *  re-creates them. Screens and the context block use [observeVisible] / [visible] instead. */
    @Query("SELECT * FROM area_status")
    fun observeAll(): Flow<List<AreaStatus>>

    @Query("SELECT * FROM area_status")
    suspend fun all(): List<AreaStatus>

    /** What he hasn't deleted. */
    @Query("SELECT * FROM area_status WHERE deletedByUser = 0")
    fun observeVisible(): Flow<List<AreaStatus>>

    @Query("SELECT * FROM area_status WHERE area = :area")
    suspend fun get(area: String): AreaStatus?

    @Upsert
    suspend fun upsert(areas: List<AreaStatus>)

    @Query("DELETE FROM area_status WHERE area = :area")
    suspend fun delete(area: String)
}

@Dao
interface LetterDao {
    @Query("SELECT * FROM letter ORDER BY periodEnd DESC, createdAt DESC")
    fun observeAll(): Flow<List<Letter>>

    @Query("SELECT * FROM letter WHERE id = :id")
    fun observe(id: Long): Flow<Letter?>

    @Query("SELECT * FROM letter WHERE id = :id")
    suspend fun get(id: Long): Letter?

    @Query("SELECT COUNT(*) FROM letter WHERE readAt IS NULL")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT * FROM letter ORDER BY periodEnd DESC, createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<Letter>

    /** UPDATE-15: Mirror, a page at a time. */
    @Query("SELECT * FROM letter ORDER BY periodEnd DESC, createdAt DESC")
    fun paged(): androidx.paging.PagingSource<Int, Letter>

    @Query("SELECT COUNT(*) FROM letter")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM letter")
    suspend fun all(): List<Letter>

    @Query("UPDATE letter SET readAt = :now WHERE id = :id AND readAt IS NULL")
    suspend fun markRead(id: Long, now: Long)

    @Query("DELETE FROM letter WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insert(letter: Letter): Long
}

@Dao
interface QuoteDao {
    @Insert suspend fun insert(rows: List<Quote>)
    @Query("SELECT * FROM quote ORDER BY createdAt DESC, id DESC LIMIT :limit") suspend fun recent(limit: Int): List<Quote>
    @Query("SELECT * FROM quote ORDER BY createdAt, id") suspend fun all(): List<Quote>
    @Query("SELECT * FROM quote WHERE sessionId = :sessionId ORDER BY id") suspend fun forSession(sessionId: Long): List<Quote>
    @Query("SELECT COUNT(*) FROM quote") suspend fun count(): Int
    @Query("SELECT * FROM quote WHERE createdAt >= :from ORDER BY createdAt, id") suspend fun since(from: Long): List<Quote>
}

@Dao
interface IdeaDao {
    @Insert suspend fun insert(rows: List<IdeaUsed>)
    @Query("SELECT * FROM idea_used ORDER BY createdAt DESC, id DESC LIMIT :limit") suspend fun recent(limit: Int): List<IdeaUsed>
    @Query("SELECT * FROM idea_used ORDER BY id") suspend fun all(): List<IdeaUsed>
}

@Dao
interface BehaviorDao {
    @Insert suspend fun insert(rows: List<BehaviorEvent>)
    @Upsert suspend fun upsert(row: BehaviorEvent)
    @Query("SELECT * FROM behavior_event WHERE id = :id") suspend fun get(id: Long): BehaviorEvent?
    @Query("SELECT * FROM behavior_event WHERE deletedByUser = 0 ORDER BY createdAt DESC, id DESC")
    fun observeVisible(): Flow<List<BehaviorEvent>>
    /**
     * The newest [limit] he hasn't deleted. "What I know" reads these rather than the whole table: behaviour
     * events are never capped or archived, so they grow by thousands a year and the screen would eventually
     * load all of them at once.
     */
    @Query("SELECT * FROM behavior_event WHERE deletedByUser = 0 ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecentVisible(limit: Int): Flow<List<BehaviorEvent>>
    @Query("SELECT * FROM behavior_event ORDER BY createdAt, id") suspend fun all(): List<BehaviorEvent>
    @Query("SELECT * FROM behavior_event WHERE sessionId = :sessionId ORDER BY id") suspend fun forSession(sessionId: Long): List<BehaviorEvent>
    @Query("SELECT COUNT(*) FROM behavior_event WHERE deletedByUser = 0") suspend fun count(): Int
    /** The newest [limit], for the chat context (all of them stay stored). */
    @Query("SELECT * FROM behavior_event WHERE deletedByUser = 0 ORDER BY createdAt DESC, id DESC LIMIT :limit") suspend fun recent(limit: Int): List<BehaviorEvent>
    @Query("SELECT * FROM behavior_event WHERE createdAt >= :from ORDER BY createdAt DESC, id DESC") suspend fun since(from: Long): List<BehaviorEvent>
}

@Dao
interface StrengthDao {
    @Insert suspend fun insert(rows: List<Strength>)
    @Upsert suspend fun upsert(row: Strength)
    @Query("SELECT * FROM strength WHERE id = :id") suspend fun get(id: Long): Strength?
    @Query("SELECT * FROM strength WHERE deletedByUser = 0 ORDER BY createdAt, id") fun observeVisible(): Flow<List<Strength>>
    @Query("SELECT * FROM strength ORDER BY createdAt, id") suspend fun all(): List<Strength>
    @Upsert suspend fun upsertAll(rows: List<Strength>)
    @Query("DELETE FROM strength WHERE id IN (:ids)") suspend fun delete(ids: List<Long>)
}

@Dao
interface SnapshotDao {
    @Insert suspend fun insert(row: Snapshot): Long
    @Query("SELECT * FROM snapshot ORDER BY createdAt DESC, id DESC LIMIT 1") suspend fun latest(): Snapshot?
    @Query("SELECT * FROM snapshot ORDER BY createdAt DESC, id DESC LIMIT 1") fun observeLatest(): Flow<Snapshot?>
    @Query("SELECT * FROM snapshot ORDER BY id") suspend fun all(): List<Snapshot>
    @Query("UPDATE snapshot SET bigFiveJson = :json WHERE id = :id") suspend fun setBigFive(id: Long, json: String)
}

@Dao
interface OnboardingDao {
    @Upsert suspend fun upsert(row: OnboardingStep)
    @Query("SELECT * FROM onboarding_step WHERE step = :step") suspend fun get(step: String): OnboardingStep?
    @Query("SELECT * FROM onboarding_step") fun observeAll(): Flow<List<OnboardingStep>>
    @Query("SELECT * FROM onboarding_step") suspend fun all(): List<OnboardingStep>
}

@Dao
interface JourneyDao {
    @Insert suspend fun insert(row: Journey): Long
    @Upsert suspend fun upsert(row: Journey)
    @Query("SELECT * FROM journey WHERE status = 'active' ORDER BY startedAt DESC LIMIT 1") suspend fun active(): Journey?
    @Query("SELECT * FROM journey WHERE status = 'active' ORDER BY startedAt DESC LIMIT 1") fun observeActive(): Flow<Journey?>
    @Query("SELECT * FROM journey ORDER BY startedAt DESC") fun observeAll(): Flow<List<Journey>>
    /** UPDATE-18: the journey under way, active or paused. */
    @Query("SELECT * FROM journey WHERE status IN ('active', 'paused') ORDER BY startedAt DESC LIMIT 1") suspend fun current(): Journey?
    @Query("SELECT * FROM journey WHERE status IN ('active', 'paused') ORDER BY startedAt DESC LIMIT 1") fun observeCurrent(): Flow<Journey?>
    @Query("SELECT * FROM journey WHERE id = :id") suspend fun get(id: Long): Journey?
    @Query("SELECT * FROM journey ORDER BY id") suspend fun all(): List<Journey>
}

@Dao
interface PromptOverrideDao {
    @Upsert suspend fun upsert(row: PromptOverride)
    @Query("DELETE FROM prompt_override WHERE name = :name") suspend fun delete(name: String)
    @Query("SELECT * FROM prompt_override") suspend fun all(): List<PromptOverride>
    @Query("SELECT * FROM prompt_override ORDER BY name") fun observeAll(): Flow<List<PromptOverride>>
    @Query("DELETE FROM prompt_override") suspend fun clear()
}

@Dao
interface CustomJourneyDao {
    @Insert suspend fun insert(row: CustomJourney): Long
    @Query("SELECT * FROM custom_journey ORDER BY createdAt DESC") suspend fun all(): List<CustomJourney>
    @Query("SELECT * FROM custom_journey ORDER BY createdAt DESC") fun observeAll(): Flow<List<CustomJourney>>
    @Query("DELETE FROM custom_journey WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM custom_journey") suspend fun clear()
    @Insert suspend fun insertAll(rows: List<CustomJourney>)
}

@Dao
interface PulseDao {
    @Upsert suspend fun upsert(row: Pulse)
    @Query("SELECT * FROM pulse WHERE date = :date") fun observe(date: String): Flow<Pulse?>
    @Query("SELECT * FROM pulse WHERE date >= :from ORDER BY date") suspend fun since(from: String): List<Pulse>
    @Query("SELECT * FROM pulse ORDER BY date") suspend fun all(): List<Pulse>
}

/** Wipe-and-load for restoring a backup. Always called inside one transaction. */
@Dao
interface RestoreDao {
    @Query("DELETE FROM message") suspend fun clearMessages()
    @Query("DELETE FROM session") suspend fun clearSessions()
    @Query("DELETE FROM profile_entry") suspend fun clearProfile()
    @Query("DELETE FROM person") suspend fun clearPeople()
    @Query("DELETE FROM note") suspend fun clearNotes()
    @Query("DELETE FROM promise") suspend fun clearPromises()
    @Query("DELETE FROM area_status") suspend fun clearAreas()
    @Query("DELETE FROM letter") suspend fun clearLetters()
    @Query("DELETE FROM usage_stat") suspend fun clearUsage()
    @Query("DELETE FROM quote") suspend fun clearQuotes()
    @Query("DELETE FROM idea_used") suspend fun clearIdeas()
    @Query("DELETE FROM behavior_event") suspend fun clearBehavior()
    @Query("DELETE FROM strength") suspend fun clearStrengths()
    @Query("DELETE FROM snapshot") suspend fun clearSnapshots()
    @Query("DELETE FROM onboarding_step") suspend fun clearOnboarding()
    @Query("DELETE FROM journey") suspend fun clearJourneys()
    @Query("DELETE FROM pulse") suspend fun clearPulses()

    @Insert suspend fun insertSessions(rows: List<Session>)
    @Insert suspend fun insertMessages(rows: List<Message>)
    @Insert suspend fun insertProfile(rows: List<ProfileEntry>)
    @Insert suspend fun insertPeople(rows: List<Person>)
    @Insert suspend fun insertNotes(rows: List<Note>)
    @Insert suspend fun insertPromises(rows: List<Promise>)
    @Insert suspend fun insertAreas(rows: List<AreaStatus>)
    @Insert suspend fun insertLetters(rows: List<Letter>)
    @Insert suspend fun insertUsage(rows: List<UsageStat>)
    @Insert suspend fun insertQuotes(rows: List<Quote>)
    @Insert suspend fun insertIdeas(rows: List<IdeaUsed>)
    @Insert suspend fun insertBehavior(rows: List<BehaviorEvent>)
    @Insert suspend fun insertStrengths(rows: List<Strength>)
    @Insert suspend fun insertSnapshots(rows: List<Snapshot>)
    @Insert suspend fun insertOnboarding(rows: List<OnboardingStep>)
    @Insert suspend fun insertJourneys(rows: List<Journey>)
    @Insert suspend fun insertPulses(rows: List<Pulse>)
}

@Dao
interface ErrorDao {
    @Insert suspend fun insert(row: ErrorLog)
    @Query("SELECT * FROM error_log ORDER BY createdAt DESC, id DESC LIMIT 50") fun observeRecent(): Flow<List<ErrorLog>>
    @Query("DELETE FROM error_log") suspend fun clear()
    /** Background job failures (not chat or the test bench) since [since], for the Health check. */
    @Query("SELECT COUNT(*) FROM error_log WHERE createdAt >= :since AND source NOT LIKE 'chat%' AND source NOT LIKE 'testbench%'")
    suspend fun jobFailuresSince(since: Long): Int
    /** Keeps the log small. */
    @Query("DELETE FROM error_log WHERE id NOT IN (SELECT id FROM error_log ORDER BY id DESC LIMIT 200)") suspend fun trim()
}

/** "Forget this conversation" and "Erase everything". Always called inside one transaction. */
@Dao
interface ForgetDao {
    @Query("DELETE FROM message WHERE sessionId = :id") suspend fun deleteMessages(id: Long)
    @Query("DELETE FROM session WHERE id = :id") suspend fun deleteSession(id: Long)
    @Query("DELETE FROM behavior_event WHERE sessionId = :id") suspend fun deleteBehavior(id: Long)
    @Query("DELETE FROM quote WHERE sessionId = :id") suspend fun deleteQuotes(id: Long)
    @Query("DELETE FROM idea_used WHERE sessionId = :id") suspend fun deleteIdeas(id: Long)
    @Query("DELETE FROM disagreement WHERE sessionId = :id") suspend fun deleteDisagreements(id: Long)
    @Query("DELETE FROM contradiction WHERE sessionId = :id OR sessionIdA = :id") suspend fun deleteContradictions(id: Long)
    @Query("DELETE FROM action_log WHERE sessionId = :id") suspend fun deleteActionLog(id: Long)
    @Query("DELETE FROM strength WHERE sessionId = :id") suspend fun deleteStrengths(id: Long)
    @Query("SELECT id FROM promise WHERE sourceSessionId = :id") suspend fun promiseIds(id: Long): List<Long>
    @Query("DELETE FROM promise WHERE sourceSessionId = :id") suspend fun deletePromises(id: Long)
    @Query("UPDATE onboarding_step SET sessionId = NULL WHERE sessionId = :id") suspend fun unlinkOnboarding(id: Long)

    @Query("DELETE FROM note WHERE id IN (:ids)") suspend fun deleteNotes(ids: List<Long>)
    @Query("DELETE FROM profile_entry WHERE `key` IN (:keys)") suspend fun deleteProfile(keys: List<String>)
    @Query("DELETE FROM person WHERE id IN (:ids)") suspend fun deletePeople(ids: List<Long>)
}

/** UPDATE-15: life chapters. */
@Dao
interface ChapterDao {
    @Insert suspend fun insert(row: Chapter): Long
    @Query("SELECT * FROM chapter ORDER BY periodEnd DESC, id DESC") fun observeAll(): Flow<List<Chapter>>
    @Query("SELECT * FROM chapter ORDER BY periodEnd DESC, id DESC LIMIT :limit") suspend fun latest(limit: Int): List<Chapter>
    @Query("SELECT * FROM chapter ORDER BY periodEnd, id") suspend fun all(): List<Chapter>
    @Query("SELECT * FROM chapter WHERE id = :id") suspend fun get(id: Long): Chapter?
    @Query("SELECT * FROM chapter WHERE id = :id") fun observe(id: Long): Flow<Chapter?>
    @Query("SELECT COUNT(*) FROM chapter WHERE periodStart = :start AND periodEnd = :end") suspend fun countFor(start: String, end: String): Int
    @Query("UPDATE chapter SET readAt = :now WHERE id = :id AND readAt IS NULL") suspend fun markRead(id: Long, now: Long)
    @Query("DELETE FROM chapter WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM chapter") suspend fun clear()
    @Insert suspend fun insertAll(rows: List<Chapter>)
}

/** UPDATE-18: growth tree leaves and proposals. */
@Dao
interface MilestoneDao {
    @Insert suspend fun insert(row: Milestone): Long
    @Upsert suspend fun upsert(row: Milestone)
    @Query("SELECT * FROM milestone WHERE id = :id") suspend fun get(id: Long): Milestone?
    @Query("SELECT * FROM milestone ORDER BY proposedAt, id") suspend fun all(): List<Milestone>
    @Query("SELECT * FROM milestone WHERE status = 'accepted' ORDER BY decidedAt, id") fun observeLeaves(): Flow<List<Milestone>>
    @Query("SELECT * FROM milestone WHERE status = 'accepted' ORDER BY decidedAt, id") suspend fun leaves(): List<Milestone>
    /** Proposals waiting for him, and snoozed ones whose 30 days are up. */
    @Query(
        "SELECT * FROM milestone WHERE status = 'proposed' OR (status = 'snoozed' AND snoozeUntil <= :now) " +
            "ORDER BY proposedAt, id"
    )
    fun observeWaiting(now: Long): Flow<List<Milestone>>
    @Query("DELETE FROM milestone WHERE status = 'considered'") suspend fun clearConsidered()
    @Query("DELETE FROM milestone") suspend fun clear()
    @Insert suspend fun insertAll(rows: List<Milestone>)
}

@Dao
interface BranchDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) suspend fun insert(row: Branch): Long
    @Query("SELECT * FROM branch ORDER BY createdAt, id") suspend fun all(): List<Branch>
    @Query("SELECT * FROM branch ORDER BY createdAt, id") fun observeAll(): Flow<List<Branch>>
    @Query("DELETE FROM branch") suspend fun clear()
    @Insert suspend fun insertAll(rows: List<Branch>)
}

@Dao
interface JourneyAdjustmentDao {
    @Insert suspend fun insert(row: JourneyAdjustment): Long
    @Query("SELECT * FROM journey_adjustment WHERE journeyId = :journeyId ORDER BY createdAt, id") suspend fun forJourney(journeyId: Long): List<JourneyAdjustment>
    @Query("SELECT * FROM journey_adjustment WHERE journeyId = :journeyId ORDER BY createdAt DESC, id DESC LIMIT 1")
    fun observeLatest(journeyId: Long): Flow<JourneyAdjustment?>
    @Query("SELECT * FROM journey_adjustment WHERE journeyId = :journeyId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latest(journeyId: Long): JourneyAdjustment?
    @Query("SELECT * FROM journey_adjustment ORDER BY id") suspend fun all(): List<JourneyAdjustment>
    @Query("DELETE FROM journey_adjustment") suspend fun clear()
    @Insert suspend fun insertAll(rows: List<JourneyAdjustment>)
}

/** UPDATE-17: monthly usage totals, kept for 24 months. */
@Dao
interface UsageMonthDao {
    @Upsert suspend fun upsert(rows: List<UsageMonth>)
    @Query("SELECT * FROM usage_month WHERE month = :month") suspend fun forMonth(month: String): List<UsageMonth>
    @Query("SELECT * FROM usage_month ORDER BY month") suspend fun all(): List<UsageMonth>
    @Query("DELETE FROM usage_month WHERE month < :oldest") suspend fun pruneBefore(oldest: String)
    @Query("DELETE FROM usage_month") suspend fun clear()
}

/** UPDATE-15: the archive's full-text index. */
@Dao
interface SearchDao {
    @Insert suspend fun insert(rows: List<SearchDoc>)
    @Query("DELETE FROM search_doc WHERE kind = :kind AND refId = :refId") suspend fun delete(kind: String, refId: String)
    @Query("DELETE FROM search_doc WHERE kind = :kind") suspend fun deleteKind(kind: String)
    @Query("DELETE FROM search_doc") suspend fun clear()
    @Query("SELECT COUNT(*) FROM search_doc") suspend fun count(): Int
    /**
     * The candidates for "possibly relevant from the past", newest first. FTS4 without `matchinfo` can't rank by
     * relevance, and a LIMIT with no ORDER BY returns rows in rowid order — insertion order, oldest first — so a
     * strong recent match could fall outside the limit and only old material was ever considered.
     * RelevantMemories then ranks these candidates by term overlap.
     */
    @Query("SELECT kind, refId, day, text FROM search_doc WHERE search_doc MATCH :query ORDER BY day DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<SearchHit>

    /** Every document, for computing embeddings (Phase 2). */
    @Query("SELECT kind, refId, day, text FROM search_doc")
    suspend fun allDocs(): List<SearchHit>
}

/** Phase 3 ledgers. */
@Dao
interface DisagreementDao {
    @Insert suspend fun insert(rows: List<Disagreement>)
    @Query("SELECT * FROM disagreement ORDER BY raisedAt, id") suspend fun all(): List<Disagreement>
    @Query("SELECT * FROM disagreement WHERE resolved = 0 ORDER BY raisedAt DESC, id DESC") suspend fun open(): List<Disagreement>
    @Query("UPDATE disagreement SET resolved = 1, resolvedAt = :now WHERE id = :id") suspend fun resolve(id: Long, now: Long)
    @Query("DELETE FROM disagreement") suspend fun clear()
}

@Dao
interface ContradictionDao {
    @Insert suspend fun insert(rows: List<Contradiction>)
    @Query("SELECT * FROM contradiction ORDER BY statedAtB, id") suspend fun all(): List<Contradiction>
    @Query("SELECT * FROM contradiction WHERE status = 'open' ORDER BY statedAtB DESC, id DESC") suspend fun open(): List<Contradiction>
    @Query("UPDATE contradiction SET status = 'explained', explanation = :explanation, resolvedAt = :now WHERE id = :id")
    suspend fun explain(id: Long, explanation: String?, now: Long)
    @Query("DELETE FROM contradiction") suspend fun clear()
}

@Dao
interface ActionLogDao {
    @Insert suspend fun insert(row: ActionLog): Long
    @Insert suspend fun insertAll(rows: List<ActionLog>)
    @Query("SELECT * FROM action_log WHERE turnId = :turnId AND dedupeKey = :key ORDER BY id DESC LIMIT 1")
    suspend fun find(turnId: Long, key: String): ActionLog?
    @Query("UPDATE action_log SET status = :status, detail = :detail, attempts = :attempts, updatedAt = :now WHERE id = :id")
    suspend fun update(id: Long, status: String, detail: String?, attempts: Int, now: Long)
    @Query("SELECT * FROM action_log WHERE status != 'ok' ORDER BY id") suspend fun unfinished(): List<ActionLog>
    @Query("SELECT * FROM action_log ORDER BY id") suspend fun all(): List<ActionLog>
    @Query("SELECT * FROM action_log ORDER BY id DESC LIMIT :limit") suspend fun recent(limit: Int): List<ActionLog>
    /** Keeps the journal small: old finished rows go, anything unfinished stays. */
    @Query("DELETE FROM action_log WHERE status = 'ok' AND id NOT IN (SELECT id FROM action_log ORDER BY id DESC LIMIT 2000)")
    suspend fun trim()
    @Query("DELETE FROM action_log") suspend fun clear()
}
