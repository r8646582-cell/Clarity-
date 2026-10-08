package com.umair.purpose.data.repo

import com.umair.purpose.chat.ToolCall
import com.umair.purpose.data.db.ActionLog
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.ledger.ActionJournalRules
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 3: the durable record of every coach action. An action is written down as pending before it runs and
 * closed as ok or failed after, so a crash mid-action leaves a pending row to audit, an action asked for twice on
 * the same turn runs once, and a failed one can be tried again (up to [ActionJournalRules.MAX_ATTEMPTS]).
 * Never called for off-the-record conversations, which keep nothing.
 */
@Singleton
class ActionJournal @Inject constructor(db: PurposeDatabase) {
    private val dao = db.actionLogDao()

    /** What to do with [call] on [turnId], and the row to close afterwards (null when it must not run). */
    data class Ticket(val decision: ActionJournalRules.Decision, val logId: Long?, val attempts: Int)

    suspend fun begin(call: ToolCall, sessionId: Long?, turnId: Long, now: Long): Ticket {
        val key = ActionJournalRules.key(call)
        // A turn id of 0 means "no message to anchor to": nothing to de-duplicate against, just log it.
        val existing = if (turnId > 0) dao.find(turnId, key) else null
        val decision = ActionJournalRules.decide(existing)
        if (decision != ActionJournalRules.Decision.RUN) return Ticket(decision, existing?.id, existing?.attempts ?: 0)
        if (existing != null) {
            val attempts = existing.attempts + 1
            dao.update(existing.id, ActionLog.PENDING, existing.detail, attempts, now)
            return Ticket(decision, existing.id, attempts)
        }
        val id = dao.insert(
            ActionLog(
                sessionId = sessionId?.takeIf { it > 0 }, turnId = turnId, kind = call.kind, dedupeKey = key,
                argsJson = ActionJournalRules.encode(call), createdAt = now, updatedAt = now,
            )
        )
        return Ticket(decision, id, 1)
    }

    suspend fun finish(ticket: Ticket, ok: Boolean, detail: String, now: Long) {
        val id = ticket.logId ?: return
        dao.update(id, if (ok) ActionLog.OK else ActionLog.FAILED, detail.take(200), ticket.attempts, now)
    }

    /** Pending (crashed mid-action) and failed rows that still have attempts left, oldest first. */
    suspend fun replayable(): List<ActionLog> = ActionJournalRules.replayable(dao.unfinished())

    suspend fun recent(limit: Int = 100): List<ActionLog> = dao.recent(limit)

    suspend fun trim() = dao.trim()
}
