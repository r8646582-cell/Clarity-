package com.umair.purpose.data.repo

import androidx.room.withTransaction
import com.umair.purpose.chat.ChatPrefixCache
import com.umair.purpose.chat.Mode
import com.umair.purpose.chat.SessionPolicy
import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.Session
import com.umair.purpose.memory.ForgetPlanner
import com.umair.purpose.promise.ReminderScheduler
import com.umair.purpose.work.WorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import androidx.paging.map
import com.umair.purpose.chat.Conversation
import com.umair.purpose.chat.Conversations
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** How a coach reply was made, for the message "Details" sheet. */
data class ReplyDetails(
    val tier: String,
    val model: String,
    val thinking: Boolean,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val firstTokenMs: Long?,
    val totalMs: Long?,
)

/**
 * Conversations and their messages. Stored sessions live in the database; an off-the-record session lives in
 * [OffRecord] (negative ids), and every call here routes to the right one.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val db: PurposeDatabase,
    private val work: WorkScheduler,
    private val journeys: JourneyRepository,
    private val onboarding: OnboardingRepository,
    private val offRecord: OffRecord,
    private val reminders: ReminderScheduler,
    private val prefixCache: ChatPrefixCache,
    private val search: com.umair.purpose.memory.SearchIndex,
    private val prompts: PromptRepository,
    private val receipts: com.umair.purpose.chat.ActionReceiptStore,
) : SessionStore {
    private val sessions = db.sessionDao()
    private val messages = db.messageDao()

    fun datasetTicket(): Long? = db.datasetWork.ticket()

    suspend fun <T> withDatasetWriter(ticket: Long? = null, block: suspend () -> T): T = db.datasetWork.withWriter(ticket, block)

    fun onDatasetReplaced(listener: () -> Unit) = db.datasetWork.onReplaced(listener)

    fun observeOpenSession(): Flow<Session?> =
        combine(offRecord.session, sessions.observeOpenSession()) { off, stored -> off ?: stored }

    fun observeMessages(sessionId: Long): Flow<List<Message>> =
        if (sessionId < 0) offRecord.messages else messages.observeForSession(sessionId)

    /** UPDATE-15: the latest [limit] messages, oldest first (a long conversation loads more as he scrolls up). */
    fun observeLatestMessages(sessionId: Long, limit: Int): Flow<List<Message>> =
        if (sessionId < 0) offRecord.messages else messages.observeLatest(sessionId, limit).map { it.asReversed() }

    suspend fun messages(sessionId: Long): List<Message> =
        if (sessionId < 0) offRecord.messages.value else messages.forSession(sessionId)

    fun observeSession(id: Long): Flow<Session?> =
        if (id < 0) offRecord.session.map { it?.takeIf { s -> s.id == id } } else sessions.observe(id)

    suspend fun session(id: Long): Session? = if (id < 0) offRecord.session.value?.takeIf { it.id == id } else sessions.get(id)

    override suspend fun openSession(): Session? = offRecord.session.value ?: sessions.openSession()

    /**
     * Ends the open session if it has gone quiet for 30 minutes or the day has changed. One with nothing said in
     * it yet has nothing to end: it stays, mode and all, and its clock starts again (UPDATE-12: a journey step
     * opened at 23:50 and answered at 00:05 used to turn into a plain chat).
     */
    suspend fun closeIfStale(now: Long) {
        offRecord.session.value?.let { off ->
            if (offRecord.messages.value.any { it.queued }) return
            val last = offRecord.messages.value.maxOfOrNull { it.createdAt }
            when {
                last == null -> if (SessionPolicy.isStale(off.startedAt, now)) offRecord.restartClock(now)
                SessionPolicy.isStale(last, now) -> offRecord.end()
            }
            return
        }
        val ended = db.withTransaction {
            val open = sessions.openSession() ?: return@withTransaction null
            val last = messages.lastActivity(open.id)
            // Messages waiting for the internet: the conversation stays open until they've gone out.
            if (open.id in messages.sessionsWithQueued()) return@withTransaction null
            if (last == null) {
                if (SessionPolicy.isStale(open.startedAt, now)) sessions.restartClock(open.id, now)
                return@withTransaction null
            }
            if (!SessionPolicy.isStale(last, now)) return@withTransaction null
            sessions.end(open.id, endedAt = last)
            open
        }
        if (ended != null) onEnded(ended, now)
    }

    /**
     * The session the next message belongs to, starting a fresh one if needed. [offTheRecord]: he's typing in an
     * off-the-record conversation, so if that one timed out the next is off the record too: what he believed
     * would be forgotten is never stored.
     */
    suspend fun ensureOpenSession(now: Long, offTheRecord: Boolean = false): Session {
        closeIfStale(now)
        offRecord.session.value?.let { return it }
        if (offTheRecord) return offRecord.start(now)
        return db.withTransaction {
            sessions.openSession() ?: Session(startedAt = now).let { it.copy(id = sessions.insert(it)) }
        }
    }

    /** Starts a new session in a mode (or about a letter, or off the record), ending whatever was open. */
    override suspend fun startSession(start: SessionStart, now: Long): Session {
        endOpenSession(now)
        if (start.offTheRecord) return offRecord.start(now, start.mode?.wire, start.modeDetail, start.journeyDay)
        val s = Session(
            startedAt = now,
            mode = start.mode?.wire,
            modeDetail = start.modeDetail,
            journeyDay = start.journeyDay,
            letterId = start.letterId,
        )
        return s.copy(id = sessions.insert(s))
    }

    /** The coach suggested a mode and he agreed: the rest of this session runs in it. */
    suspend fun switchMode(sessionId: Long, mode: Mode?, detail: String?, journeyDay: Int?) {
        if (sessionId < 0) offRecord.setMode(mode?.wire, detail, journeyDay)
        else sessions.setMode(sessionId, mode?.wire, detail, journeyDay)
    }

    /** "End conversation", or leaving a mode: close it and let reflection learn from it. Off the record just vanishes. */
    override suspend fun endOpenSession(now: Long) {
        if (offRecord.active) {
            offRecord.session.value?.let { receipts.forgetSession(it.id) }
            offRecord.end()
            return
        }
        val open = sessions.openSession() ?: return
        sessions.end(open.id, now)
        onEnded(open, now)
    }

    /** [queued]: written offline; it goes out with [sendQueued] when the connection is back (UPDATE-16). */
    suspend fun addMessage(sessionId: Long, role: String, content: String, now: Long, details: ReplyDetails? = null, queued: Boolean = false): Long {
        val m = Message(
            sessionId = sessionId, role = role, content = content, createdAt = now,
            status = if (queued) Message.QUEUED else Message.COMPLETE,
            tier = details?.tier, model = details?.model, thinking = details?.thinking ?: false,
            inputTokens = details?.inputTokens, outputTokens = details?.outputTokens,
            firstTokenMs = details?.firstTokenMs, totalMs = details?.totalMs,
        )
        if (sessionId < 0) return offRecord.add(m)
        return db.withTransaction {
            if (role == Message.ROLE_USER) {
                sessions.countUserMessage(sessionId)
                // Until reflection names it, the chat list uses the first words of his first message.
                Conversations.titleFrom(content)?.let { sessions.setTitleIfMissing(sessionId, it) }
            }
            messages.insert(m)
        }
    }

    /** UPDATE-15: the drawer a page at a time, so years of conversations open instantly. */
    fun pagedConversations(query: String): Flow<androidx.paging.PagingData<Conversation>> =
        androidx.paging.Pager(androidx.paging.PagingConfig(pageSize = 40, enablePlaceholders = false)) { sessions.pagedConversations(query.trim()) }
            .flow.map { pd -> pd.map { Conversation(it.session, it.lastAt) } }

    /** The drawer: every conversation he took part in, newest activity first. Blank [query] = all. */
    fun observeConversations(query: String): Flow<List<Conversation>> {
        val q = query.trim()
        val rows = if (q.isEmpty()) sessions.observeConversations() else sessions.searchConversations(q)
        return rows.map { list -> list.map { Conversation(it.session, it.lastAt) } }
    }

    /**
     * The few newest conversations, for Talk's home "Pick up where you left off". Kept bounded: home needs one
     * row, and this Flow re-emits on every write to `message` (about once a second while a reply streams).
     */
    fun observeRecentConversations(limit: Int = RECENT_CONVERSATIONS): Flow<List<Conversation>> =
        sessions.observeRecentConversations(limit).map { list -> list.map { Conversation(it.session, it.lastAt) } }

    /**
     * He's continuing a past conversation: whatever else was open ends (and gets reflected on), and this one
     * becomes the open session again. When it ends, reflection learns only from what's new.
     */
    suspend fun reopen(id: Long, now: Long): Session? {
        if (id < 0) return session(id)
        val target = sessions.get(id) ?: return null
        if (target.endedAt == null) return target
        offRecord.end()
        sessions.openSession()?.takeIf { it.id != id }?.let { open ->
            sessions.end(open.id, now)
            onEnded(open, now)
        }
        sessions.reopen(id)
        // Its mode comes back only if it's still going: not a step that's done, not a journey day that's past.
        val stepDone = Mode.fromWire(target.mode) == Mode.ONBOARDING && onboarding.state().done(target.modeDetail.orEmpty())
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        if (!com.umair.purpose.chat.ModeResume.keep(target, stepDone, journeys.active(), today)) {
            sessions.setMode(id, null, null, null)
        }
        prefixCache.clear()
        return sessions.get(id)
    }

    suspend fun rename(id: Long, title: String) {
        val t = title.trim()
        if (id > 0 && t.isNotEmpty()) sessions.rename(id, t.take(80))
    }

    // ---- A reply written as it streams (see ReplyEngine) ----

    /** An empty coach message with status streaming. The UI shows it as it fills. */
    suspend fun startReply(sessionId: Long, now: Long): Long {
        val m = Message(sessionId = sessionId, role = Message.ROLE_ASSISTANT, content = "", createdAt = now, status = Message.STREAMING)
        return if (sessionId < 0) offRecord.add(m) else messages.insert(m)
    }

    suspend fun updateReply(id: Long, visibleSoFar: String) {
        if (id < 0) offRecord.update(id) { if (it.streaming) it.copy(content = visibleSoFar) else it }
        else messages.updateStreaming(id, visibleSoFar)
    }

    suspend fun completeReply(id: Long, content: String, d: ReplyDetails) {
        if (id < 0) {
            offRecord.update(id) {
                it.copy(
                    content = content, status = Message.COMPLETE, tier = d.tier, model = d.model, thinking = d.thinking,
                    inputTokens = d.inputTokens, outputTokens = d.outputTokens, firstTokenMs = d.firstTokenMs, totalMs = d.totalMs,
                )
            }
        } else {
            messages.complete(id, content, d.tier, d.model, d.thinking, d.inputTokens, d.outputTokens, d.firstTokenMs, d.totalMs)
        }
    }

    suspend fun interruptReply(id: Long) {
        if (id < 0) offRecord.update(id) { it.copy(status = Message.INTERRUPTED) } else messages.markInterrupted(id)
    }

    suspend fun deleteMessage(id: Long) {
        if (id < 0) offRecord.remove(id) else messages.delete(id)
    }

    /** UPDATE-16: conversations with messages waiting for the internet, oldest first. */
    suspend fun sessionsWithQueued(): List<Long> =
        (listOfNotNull(offRecord.session.value?.id?.takeIf { offRecord.messages.value.any { it.queued } }) + messages.sessionsWithQueued())

    /** The waiting messages go out now, in the order he wrote them. */
    suspend fun sendQueued(sessionId: Long) {
        if (sessionId < 0) offRecord.messages.value.filter { it.queued }.forEach { q -> offRecord.update(q.id) { it.copy(status = Message.COMPLETE) } }
        else messages.markQueuedSent(sessionId)
    }

    /** On launch: replies cut off when the process died become "interrupted", never stuck streaming. */
    suspend fun interruptLeftovers(): Int {
        messages.deleteEmptyStreaming()
        return messages.interruptAllStreaming()
    }

    suspend fun message(id: Long): Message? = if (id < 0) offRecord.messages.value.firstOrNull { it.id == id } else messages.get(id)

    /**
     * "Forget this conversation": the session, its messages, and everything learned only from it
     * (notes, people and profile facts whose only source it is; its behavior events, quotes, idea tags,
     * strengths and promises). Memories with other sources just lose the link.
     */
    suspend fun forget(sessionId: Long) {
        receipts.forgetSession(sessionId)
        if (sessionId < 0) {
            offRecord.end()
            return
        }
        val f = db.forgetDao()
        val promiseIds = db.withTransaction {
            val plan = ForgetPlanner.plan(sessionId, db.noteDao().all(), db.profileDao().all(), db.personDao().all())
            if (plan.deleteNoteIds.isNotEmpty()) f.deleteNotes(plan.deleteNoteIds)
            if (plan.updateNotes.isNotEmpty()) db.noteDao().upsert(plan.updateNotes)
            if (plan.deleteProfileKeys.isNotEmpty()) f.deleteProfile(plan.deleteProfileKeys)
            if (plan.updateProfile.isNotEmpty()) db.profileDao().upsert(plan.updateProfile)
            if (plan.deletePeopleIds.isNotEmpty()) f.deletePeople(plan.deletePeopleIds)
            if (plan.updatePeople.isNotEmpty()) db.personDao().upsert(plan.updatePeople)
            f.deleteBehavior(sessionId)
            f.deleteQuotes(sessionId)
            f.deleteIdeas(sessionId)
            f.deleteDisagreements(sessionId)
            f.deleteContradictions(sessionId)
            f.deleteActionLog(sessionId)
            f.deleteStrengths(sessionId)
            val ids = f.promiseIds(sessionId)
            f.deletePromises(sessionId)
            f.unlinkOnboarding(sessionId)
            f.deleteMessages(sessionId)
            f.deleteSession(sessionId)
            ids
        }
        promiseIds.forEach(reminders::cancel)
        prefixCache.clear()
        // Nothing of it may stay findable in the archive.
        search.rebuild()
    }

    /** "Erase everything": all of his data goes. Settings and the API key stay. */
    suspend fun eraseEverything() = db.datasetWork.replace { eraseCurrentDataset() }

    private suspend fun eraseCurrentDataset() {
        receipts.clear()
        offRecord.end()
        val reminderIds = db.promiseDao().all().map { it.id }
        val r = db.restoreDao()
        db.withTransaction {
            r.clearMessages(); r.clearSessions(); r.clearProfile(); r.clearPeople(); r.clearNotes()
            r.clearPromises(); r.clearAreas(); r.clearLetters(); r.clearUsage()
            r.clearQuotes(); r.clearIdeas(); r.clearBehavior(); r.clearStrengths(); r.clearSnapshots()
            r.clearOnboarding(); r.clearJourneys(); r.clearPulses()
            db.customJourneyDao().clear()
            db.chapterDao().clear(); db.milestoneDao().clear(); db.branchDao().clear()
            db.journeyAdjustmentDao().clear(); db.usageMonthDao().clear()
            db.disagreementDao().clear(); db.contradictionDao().clear(); db.actionLogDao().clear()
            db.screenUsageDao().clear()
            db.searchDao().clear()
            db.errorDao().clear()
            // His saved prompt files are his data too, and "Erase everything" says all of it goes.
            db.promptDao().clear()
        }
        db.datasetWork.dataReplaced()
        reminderIds.forEach(reminders::cancel)
        prompts.changed()
        prefixCache.clear()
    }

    /**
     * Ending a conversation never completes a journey day by itself: talking about a step is not doing it, and a
     * conversation that ends in "let's start tomorrow" must leave the day where it was. A journey day (and the
     * onboarding steps) are completed only by the coach's own `advance_journey` / `[[step_done]]` actions, or by
     * reflection's `onboarding_covered`. Then reflection runs.
     */
    private suspend fun onEnded(session: Session, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        work.reflect()
    }

    companion object {
        /**
         * How many conversations Talk's home reads to find "Pick up where you left off". One row is shown, but a
         * few are read because mode and letter conversations are skipped.
         */
        const val RECENT_CONVERSATIONS = 20
    }
}
