package com.umair.purpose.chat

import com.umair.purpose.data.db.Promise
import com.umair.purpose.promise.due
import com.umair.purpose.time.TimeFacts
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** One "Coming up" row: "Study 1 hour, Monday 8:00am", meta "in 31 hours". */
data class ComingUp(val promiseId: Long, val title: String, val meta: String)

/** "Pick up where you left off": a recent conversation he can continue. */
data class PickUp(val sessionId: Long, val title: String, val meta: String)

/**
 * UPDATE-19, DESIGN.md "Talk: home": everything on the new-conversation screen is computed from the database,
 * never written by the AI, so it can't be wrong about time.
 */
object HomeFacts {
    const val COMING_UP_HOURS = 48L
    const val COMING_UP_MAX = 2
    const val PICK_UP_DAYS = 3L

    /** Open promises due in the next 48 hours (a date-only promise counts while its day hasn't passed). */
    fun comingUp(promises: List<Promise>, now: LocalDateTime): List<ComingUp> {
        val until = now.plusHours(COMING_UP_HOURS)
        return promises.filter { it.status == Promise.OPEN }
            .mapNotNull { p -> p.due?.let { d -> Triple(p, d, d.date.atTime(d.time ?: java.time.LocalTime.MAX)) } }
            .filter { (_, d, end) -> !end.isBefore(now) && !d.date.atTime(d.time ?: java.time.LocalTime.MIN).isAfter(until) }
            .sortedBy { it.third }
            .take(COMING_UP_MAX)
            .map { (p, d, _) ->
                val whenText = TimeFacts.dayDate(d.date).substringBefore(' ').let { day ->
                    d.time?.let { "$day ${TimeFacts.clock(it)}" } ?: day
                }
                val meta = d.time?.let { TimeFacts.relative(d.date.atTime(it), now) }
                    ?: if (d.date == now.toLocalDate()) "today" else "tomorrow"
                ComingUp(p.id, "${p.text.trim().trimEnd('.')}, $whenText", meta)
            }
    }

    /**
     * The "morning perspective": one code-computed sentence about his open promises, refreshed by the nightly
     * garden so the home screen never has to ask the AI what today holds. Null when there is nothing open.
     */
    fun morningPerspective(promises: List<Promise>, now: LocalDateTime): String? {
        val open = promises.filter { it.status == Promise.OPEN }
        if (open.isEmpty()) return null
        // Only timing and counts — never the promise text — because UiPrefs is unencrypted interface state.
        val next = open.mapNotNull { p -> p.due?.let { d -> p to d } }
            .filter { (_, d) -> !d.date.isBefore(now.toLocalDate()) }
            .minWithOrNull(compareBy<Pair<Promise, com.umair.purpose.promise.Due>> { it.second.date }.thenBy { it.first.createdAt })
        val rest = " ${open.size} open in all."
        val suffix = if (open.size > 1) rest else ""
        return when {
            next != null -> "Next: ${TimeFacts.duePhrase(next.second, now)}.$suffix"
            open.size == 1 -> "One promise is open, with no date yet."
            else -> "${open.size} promises are open, none with a date yet."
        }
    }

    /**
     * The most recent conversation, if he talked in it within the last 3 days and it didn't clearly end:
     * a plain talk (not a finished onboarding step, journey day or letter talk) other than the one on screen.
     */
    fun pickUp(conversations: List<Conversation>, currentSessionId: Long?, now: ZonedDateTime): PickUp? {
        val c = conversations.firstOrNull { it.session.id != currentSessionId && !it.session.offTheRecord } ?: return null
        val s = c.session
        val since = Instant.ofEpochMilli(c.lastAt).atZone(now.zone)
        if (since.isBefore(now.minusDays(PICK_UP_DAYS)) || since.isAfter(now)) return null
        if (s.mode == Mode.ONBOARDING.wire || s.mode == Mode.JOURNEY.wire || s.letterId != null) return null
        val title = s.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return PickUp(s.id, title, TimeFacts.since(c.lastAt, now))
    }
}
