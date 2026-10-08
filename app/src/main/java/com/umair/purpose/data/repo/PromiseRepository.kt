package com.umair.purpose.data.repo

import com.umair.purpose.chat.ReplyMarkers
import com.umair.purpose.data.db.AreaStatus
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.growth.ActionKeys
import com.umair.purpose.memory.ReflectionPlanner
import com.umair.purpose.promise.Due
import com.umair.purpose.promise.PromiseRules
import com.umair.purpose.promise.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PromiseRepository @Inject constructor(
    db: PurposeDatabase,
    private val reminders: ReminderScheduler,
) {
    private val dao = db.promiseDao()

    /** UPDATE-15: open ones, and the latest resolved ones (for "kept 6 of your last 7"), never all of them. */
    fun observeOpen(): kotlinx.coroutines.flow.Flow<List<Promise>> = dao.observeOpen()
    fun observeRecentResolved(limit: Int = 200): kotlinx.coroutines.flow.Flow<List<Promise>> = dao.observeRecentResolved(limit)
    fun pagedPast(): kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<Promise>> =
        androidx.paging.Pager(androidx.paging.PagingConfig(pageSize = 30, enablePlaceholders = false)) { dao.pagedPast() }.flow

    fun observeAll(): Flow<List<Promise>> = dao.observeAll()

    fun observeForSession(sessionId: Long): Flow<List<Promise>> = dao.observeForSession(sessionId)

    /** The one promise the coach may raise today, if any. */
    suspend fun open(): List<Promise> = dao.open()

    suspend fun dueForInjection(today: LocalDate): Promise? = PromiseRules.dueForInjection(dao.open(), today)

    /**
     * A promise the coach saved in chat. A promise already open in other words is not added twice — but if this
     * line asks for a reminder, that reminder is attached to the existing promise rather than thrown away.
     * Returns the saved promise (with its reminder scheduled), or null.
     */
    suspend fun saveFromChat(line: ReplyMarkers.PromiseLine, sessionId: Long?, messageId: Long?, zone: ZoneId, now: Long): Promise? {
        val open = dao.open()
        val duplicate = open.firstOrNull { ReflectionPlanner.samePromise(it.text, line.text) }
        // A time already past (agreed at 9:05 for 9:00) goes off straight away, never silently dropped.
        val remindAt = line.remindAt?.atZone(zone)?.toInstant()?.toEpochMilli()?.let { PromiseRules.reminderTime(it, now, justAgreed = true) }
        if (duplicate != null) {
            // He agreed to the same thing again. An explicit new time is an implicit reschedule: the new dueAt
            // is applied instead of being silently discarded. A requested reminder is still scheduled.
            val newDue = line.due ?: duplicate.dueAt
            val isTimeChanged = line.due != null && line.due != duplicate.dueAt

            if (remindAt == null && !isTimeChanged) return duplicate

            val moved = PromiseRules.edit(duplicate, duplicate.text, Due.parse(newDue), zone)
            val updated = moved.copy(
                remindAt = remindAt ?: moved.remindAt,
                why = duplicate.why ?: line.why?.trim(),
                dueAt = newDue,
            )
            applyChange(duplicate, updated)
            return updated
        }
        val p = Promise(
            text = line.text.trim(),
            why = line.why?.trim(),
            createdAt = now,
            dueAt = line.due,
            remindAt = remindAt,
            status = Promise.OPEN,
            sourceSessionId = sessionId,
            sourceMessageId = messageId,
            offTheRecord = sessionId == null,
        )
        val saved = p.copy(id = dao.insert(p))
        saved.remindAt?.let { reminders.schedule(saved.id, it) }
        return saved
    }

    /**
     * UPDATE-20: a promise the coach saved autonomously with `[[action: {type: record_promise}]]`. It goes through
     * the same de-duplication and reminder rules as a chat promise, then its `tags` are read for a stable action
     * key and a life area, so consistency is still measurable.
     */
    suspend fun saveFromTool(
        title: String,
        reminderEpoch: Long?,
        tags: List<String>,
        sessionId: Long?,
        messageId: Long?,
        zone: ZoneId,
        now: Long,
        /** A code-derived due date ("yyyy-MM-dd") from the action's `due_epoch_ms`, when it carried one. */
        due: String? = null,
    ): Promise? {
        val text = title.trim()
        if (text.isEmpty()) return null
        val remindAt = reminderEpoch?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
        val saved = saveFromChat(ReplyMarkers.PromiseLine(text = text, due = due, remindAt = remindAt, why = null), sessionId, messageId, zone, now)
            ?: return null
        val key = tags.firstNotNullOfOrNull { ActionKeys.clean(it) }
        val area = tags.map { it.trim().lowercase() }.firstOrNull { it in AreaStatus.AREAS }
        if ((key == null || saved.actionKey == key) && (area == null || saved.area == area)) return saved
        val updated = saved.copy(actionKey = key ?: saved.actionKey, area = area ?: saved.area)
        dao.upsert(listOf(updated))
        return updated
    }

    suspend fun markKept(id: Long, now: Long) = change(id) { PromiseRules.keep(it, now) }

    suspend fun drop(id: Long, now: Long) = change(id) { PromiseRules.drop(it, now) }

    /**
     * The coach's autonomous `resolve_promise`: resolve an open promise by id, or — when only the wording is
     * known — the most recent matching open one (same id-or-title lookup as [rescheduleFromTool]). Null when
     * nothing open matches, or the status is not a real terminal one. Returns the resolved promise so callers
     * (e.g. the growth engine) can act on what it actually was.
     */
    suspend fun resolveFromTool(id: Long?, title: String?, status: String, now: Long): Promise? {
        val p = findOpen(id, title) ?: return null
        val next = runCatching { PromiseRules.closeAs(p, status, now) }.getOrNull() ?: return null
        dao.upsert(listOf(next))
        reminders.cancel(p.id)
        return next
    }

    suspend fun edit(id: Long, text: String, due: Due?, zone: ZoneId = ZoneId.systemDefault()) = change(id) { PromiseRules.edit(it, text, due, zone) }

    /**
     * The coach's autonomous `reschedule_promise`: move an open promise's due time (its reminder moves with it).
     * The promise is found by id, or — when the id is omitted — the most recent open promise whose wording
     * matches [title]. Returns null when nothing open matches, so the action fails cleanly instead of guessing.
     */
    suspend fun rescheduleFromTool(id: Long?, title: String?, due: Due, zone: ZoneId): Promise? {
        val p = findOpen(id, title) ?: return null
        val next = PromiseRules.edit(p, p.text, due, zone)
        applyChange(p, next)
        return next
    }

    /**
     * The coach's autonomous `edit_promise`: change an open promise's wording and/or due time. Same id-or-title
     * lookup as [rescheduleFromTool]; a null [newTitle] or [due] keeps that field as it was.
     */
    suspend fun editFromTool(id: Long?, title: String?, newTitle: String?, due: Due?, zone: ZoneId): Promise? {
        val p = findOpen(id, title) ?: return null
        val text = newTitle?.trim()?.takeIf { it.isNotEmpty() } ?: p.text
        val next = PromiseRules.edit(p, text, due ?: Due.parse(p.dueAt), zone)
        applyChange(p, next)
        return next
    }

    suspend fun removeReminder(id: Long) = change(id) { PromiseRules.removeReminder(it) }

    /** Undo for a swipe: put the promise back exactly as it was. */
    suspend fun restore(p: Promise) {
        dao.upsert(listOf(p))
        p.remindAt?.let { if (p.status == Promise.OPEN) reminders.schedule(p.id, it) }
    }

    /** "Undo" under "Promise saved": it was saved by mistake, so it goes entirely, reminder too. */
    suspend fun deleteSaved(p: Promise) {
        reminders.cancel(p.id)
        dao.delete(p.id)
    }

    /** After reflection closed some promises. */
    fun cancelReminders(ids: List<Long>) = ids.forEach(reminders::cancel)

    /** At launch and after a reboot: pending reminders back on; one missed a little while ago goes off now. */
    suspend fun rescheduleReminders() = reminders.rescheduleAll(dao.withReminders()).forEach { dao.clearReminder(it) }

    private suspend fun change(id: Long, f: (Promise) -> Promise) {
        val p = dao.get(id) ?: return
        if (p.status != Promise.OPEN) return
        applyChange(p, f(p))
    }

    /** Persists an open promise's change and keeps its reminder in step, whatever path asked for it. */
    private suspend fun applyChange(p: Promise, next: Promise) {
        dao.upsert(listOf(next))
        if (next.remindAt == null || next.status != Promise.OPEN) reminders.cancel(p.id)
        // Same id, so this replaces the old alarm (an edited due time moves its reminder).
        else if (next.remindAt != p.remindAt) reminders.schedule(p.id, next.remindAt)
    }

    /**
     * The open promise a tool action targets: by id when given, otherwise the most recent open one whose words
     * match [title]. An id that isn't open (or doesn't exist) is not silently swapped for a guessed one.
     */
    private suspend fun findOpen(id: Long?, title: String?): Promise? {
        val validId = id?.takeIf { it > 0 }
        if (validId != null) {
            return dao.get(validId)?.takeIf { it.status == Promise.OPEN }
        }
        if (id != null) return null
        val query = title?.trim().orEmpty()
        val openPromises = dao.open()
        if (query.isNotEmpty()) {
            val exact = openPromises.filter { it.text.trim().equals(query, ignoreCase = true) }
            if (exact.isNotEmpty()) return exact.singleOrNull()
            // Never pick an arbitrary promise when several match a paraphrase.
            // 1. Try ReflectionPlanner fuzzy match
            val fuzzyMatch = openPromises
                .filter { ReflectionPlanner.samePromise(it.text, query) }
            if (fuzzyMatch.isNotEmpty()) return fuzzyMatch.singleOrNull()

            // 2. Fallback to substring / token containment (handles short paraphrases)
            val subMatch = openPromises
                .filter {
                    it.text.contains(query, ignoreCase = true) ||
                        query.contains(it.text, ignoreCase = true)
                }
            if (subMatch.isNotEmpty()) return subMatch.singleOrNull()
        }

        return null
    }
}
