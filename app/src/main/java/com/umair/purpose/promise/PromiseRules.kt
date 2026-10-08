package com.umair.purpose.promise

import com.umair.purpose.data.db.Promise
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** A promise's due moment: always a date, sometimes a time. Stored as "yyyy-MM-dd" or "yyyy-MM-ddTHH:mm". */
data class Due(val date: LocalDate, val time: LocalTime?) {
    override fun toString(): String = if (time == null) date.toString() else LocalDateTime.of(date, time).toString().take(16)

    companion object {
        fun parse(value: String?): Due? {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching {
                if (v.length > 10) LocalDateTime.parse(v.replace(' ', 'T')).let { Due(it.toLocalDate(), it.toLocalTime()) }
                else Due(LocalDate.parse(v), null)
            }.getOrNull()
        }
    }
}

val Promise.due: Due? get() = Due.parse(dueAt)

/** Every promise state change goes through here. Only open promises can change. */
object PromiseRules {

    fun keep(p: Promise, now: Long, whatHappened: String? = null, lesson: String? = null) =
        close(p, Promise.KEPT, now).withOutcome(whatHappened, lesson)

    fun breakPromise(p: Promise, now: Long, whatHappened: String? = null, lesson: String? = null) =
        close(p, Promise.BROKEN, now).withOutcome(whatHappened, lesson)

    fun drop(p: Promise, now: Long) = close(p, Promise.DROPPED, now).copy(remindAt = null)

    /**
     * Resolve an open promise to any terminal status by wire name (KEPT, BROKEN, RENEGOTIATED, DROPPED), for the
     * coach's autonomous `resolve_promise` action. Unknown names are rejected rather than silently guessed.
     */
    fun closeAs(p: Promise, status: String, now: Long): Promise {
        requireOpen(p)
        val raw = status.trim().lowercase()
        val s = when (raw) {
            "let_go", "letgo", "void", "abandoned", "dropped" -> Promise.DROPPED
            "done", "completed", "kept" -> Promise.KEPT
            "failed", "broken" -> Promise.BROKEN
            "renegotiated" -> Promise.RENEGOTIATED
            else -> raw
        }
        require(s in CLOSED) { "Not a promise status: $status (normalized: $s)" }
        return p.copy(status = s, resolvedAt = now, remindAt = null)
    }

    private val CLOSED = setOf(Promise.KEPT, Promise.BROKEN, Promise.RENEGOTIATED, Promise.DROPPED)

    /**
     * His own correction of wording or date. Stays the same promise. A reminder moves with the due time, keeping
     * the same distance before it (UPDATE-12: it used to stay at the old time).
     */
    fun edit(p: Promise, text: String, due: Due?, zone: ZoneId = ZoneId.systemDefault()): Promise {
        requireOpen(p)
        require(text.isNotBlank()) { "Promise text can't be empty" }
        return p.copy(text = text.trim(), dueAt = due?.toString(), remindAt = movedReminder(p, due, zone))
    }

    /** Where the reminder goes when the due moment changes from [p]'s to [newDue]. */
    internal fun movedReminder(p: Promise, newDue: Due?, zone: ZoneId): Long? {
        val at = p.remindAt ?: return null
        val new = newDue ?: return at
        val old = p.due
        if (old == new) return at
        if (new.time != null && (old == null || old.time == null)) {
            // Old date-only promises have no known due clock. Noon was an invented anchor that shifted
            // reminders to the wrong time; use the newly requested clock instead.
            return PromiseTimes.epoch(new, zone) ?: throw IllegalArgumentException("Ambiguous due time")
        }
        if (old == null) return at
        if (new.time == null) {
            return java.time.Instant.ofEpochMilli(at).atZone(zone)
                .plusDays(java.time.temporal.ChronoUnit.DAYS.between(old.date, new.date)).toInstant().toEpochMilli()
        }
        val oldMoment = PromiseTimes.epoch(old, zone) ?: throw IllegalArgumentException("Ambiguous old due time")
        val newMoment = PromiseTimes.epoch(new, zone) ?: throw IllegalArgumentException("Ambiguous due time")
        return at + (newMoment - oldMoment)
    }

    /**
     * When a reminder should go off (UPDATE-12: one already past was silently dropped). Future: as asked.
     * Just agreed to and already past ("at 9" said at 9:05): straight away. Found past later (the phone was off):
     * straight away if missed by up to [MISSED_GRACE_MS], otherwise it's gone stale and is cleared (null).
     */
    fun reminderTime(remindAt: Long, now: Long, justAgreed: Boolean): Long? = when {
        remindAt > now -> remindAt
        justAgreed || now - remindAt <= MISSED_GRACE_MS -> now + FIRE_NOW_DELAY_MS
        else -> null
    }

    const val MISSED_GRACE_MS = 3 * 60 * 60 * 1000L
    const val FIRE_NOW_DELAY_MS = 5_000L

    fun removeReminder(p: Promise): Promise = p.copy(remindAt = null)

    /**
     * An honest renegotiation: the old promise is closed as `renegotiated` and a new open one replaces it,
     * so history shows both. The why carries over.
     */
    fun renegotiate(p: Promise, text: String, due: Due?, now: Long, sessionId: Long?): Pair<Promise, Promise> {
        val closed = close(p, Promise.RENEGOTIATED, now).copy(remindAt = null)
        val replacement = Promise(
            text = text.trim().ifEmpty { p.text },
            why = p.why,
            createdAt = now,
            dueAt = due?.toString(),
            status = Promise.OPEN,
            sourceSessionId = sessionId,
            area = p.area,
        )
        return closed to replacement
    }

    /** At most one promise for the coach to raise: the most overdue open one with a date. */
    fun dueForInjection(promises: List<Promise>, today: LocalDate): Promise? =
        promises.asSequence()
            .filter { it.status == Promise.OPEN }
            .mapNotNull { p -> p.due?.let { p to it } }
            .filter { (_, due) -> !due.date.isAfter(today) }
            .minWithOrNull(compareBy<Pair<Promise, Due>> { it.second.date }.thenBy { it.first.createdAt })
            ?.first

    fun isOverdue(p: Promise, today: LocalDate): Boolean =
        p.status == Promise.OPEN && p.due?.date?.isBefore(today) == true

    data class Counts(val kept: Int, val broken: Int, val open: Int, val dropped: Int, val renegotiated: Int)

    fun counts(promises: List<Promise>) = Counts(
        kept = promises.count { it.status == Promise.KEPT },
        broken = promises.count { it.status == Promise.BROKEN },
        open = promises.count { it.status == Promise.OPEN },
        dropped = promises.count { it.status == Promise.DROPPED },
        renegotiated = promises.count { it.status == Promise.RENEGOTIATED },
    )

    private fun Promise.withOutcome(whatHappened: String?, lesson: String?) = copy(
        remindAt = null,
        whatHappened = whatHappened?.trim()?.takeIf { it.isNotEmpty() } ?: this.whatHappened,
        lesson = lesson?.trim()?.takeIf { it.isNotEmpty() } ?: this.lesson,
    )

    private fun close(p: Promise, status: String, now: Long): Promise {
        requireOpen(p)
        return p.copy(status = status, resolvedAt = now)
    }

    private fun requireOpen(p: Promise) =
        check(p.status == Promise.OPEN) { "Promise ${p.id} is ${p.status}, not open" }
}
