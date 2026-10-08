package com.umair.purpose.time

import com.umair.purpose.promise.Due
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * UPDATE-19: the model never does date math. Every time fact it sees (now, due times, message times)
 * is worked out here and handed over as finished words.
 */
object TimeFacts {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /**
     * The day he is actually living in. Before 4:00am it is still the previous day — a late-night session
     * belongs to the night it started, so "today" and "tomorrow" in a promise are read against [logicalDate].
     */
    fun logicalDate(now: ZonedDateTime = ZonedDateTime.now(zone)): LocalDate =
        if (now.hour < 4) now.toLocalDate().minusDays(1) else now.toLocalDate()

    /** Greeting bands: 5:00-11:59, 12:00-16:59, 17:00-21:59, 22:00-4:59. */
    fun greeting(t: LocalTime): String = when (t.hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Still up, Umair?"
    }

    /** "It's Sunday 4 October 2026, 12:46am (late night; for him it's still Saturday night)." */
    fun nowSentence(t: ZonedDateTime): String {
        val date = "${day(t.dayOfWeek.value)} ${t.dayOfMonth} ${t.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${t.year}"
        val part = when (t.hour) {
            in 0..4 -> "late night; for him it's still ${day(t.minusDays(1).dayOfWeek.value)} night"
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..21 -> "evening"
            else -> "night"
        }
        return "It's $date, ${clock(t.toLocalTime())} ($part). Time zone: ${t.zone.id}. " +
            "Local now: ${t.toLocalDateTime()}. Today: ${t.toLocalDate()}; tomorrow: ${t.toLocalDate().plusDays(1)}. " +
            "Use calendar dates for today/tomorrow; tonight before 4am means the night still in progress."
    }

    /** "8:00am", "12:46am", "5:30pm". */
    fun clock(t: LocalTime): String {
        val h = t.hour % 12
        return "${if (h == 0) 12 else h}:${"%02d".format(t.minute)}${if (t.hour < 12) "am" else "pm"}"
    }

    /** "Monday 6 Oct". */
    fun dayDate(d: LocalDate): String = "${day(d.dayOfWeek.value)} ${d.dayOfMonth} ${month(d)}"

    /** "in 31 hours", "2 days ago", "in 20 minutes". Hours up to two days, then calendar days. */
    fun relative(target: LocalDateTime, now: LocalDateTime): String {
        val mins = Duration.between(now, target).toMinutes()
        val future = mins >= 0
        val abs = kotlin.math.abs(mins)
        val amount = when {
            abs < 1 -> return "now"
            abs < 60 -> plural(abs, "minute")
            abs < 48 * 60 -> plural(abs / 60, "hour")
            else -> plural(kotlin.math.abs(ChronoUnit.DAYS.between(now.toLocalDate(), target.toLocalDate())), "day")
        }
        return if (future) "in $amount" else "$amount ago"
    }

    /** "due Monday 6 Oct, 8:00am (in 31 hours)" or "was due Friday 3 Oct, 5:00pm (2 days ago)". */
    fun duePhrase(due: Due, now: LocalDateTime): String {
        val time = due.time
        if (time == null) {
            val days = ChronoUnit.DAYS.between(now.toLocalDate(), due.date)
            return when {
                days == 0L -> "due today, ${dayDate(due.date)}"
                days > 0 -> "due ${dayDate(due.date)} (in ${plural(days, "day")})"
                else -> "was due ${dayDate(due.date)} (${plural(-days, "day")} ago)"
            }
        }
        val at = due.date.atTime(time)
        val head = "${dayDate(due.date)}, ${clock(time)}"
        return if (!at.isBefore(now)) "due $head (${relative(at, now)})" else "was due $head (${relative(at, now)})"
    }

    /** The prefix for his messages in the API payload (never on screen): "[Sat 3 Oct, 23:12]". */
    fun messageStamp(epochMs: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        return "[${day(t.dayOfWeek.value).take(3)} ${t.dayOfMonth} ${month(t.toLocalDate())}, ${"%02d:%02d".format(t.hour, t.minute)}]"
    }

    /** "last night", "today", "yesterday", "2 days ago": for "Pick up where you left off". */
    fun since(epochMs: Long, now: ZonedDateTime): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(now.zone)
        val days = ChronoUnit.DAYS.between(t.toLocalDate(), now.toLocalDate())
        return when {
            days == 0L -> "today"
            days == 1L && (t.hour >= 20 || t.hour < 5) -> "last night"
            days == 1L -> "yesterday"
            else -> "$days days ago"
        }
    }

    private fun day(isoDay: Int) = java.time.DayOfWeek.of(isoDay).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    private fun month(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).take(3)
    private fun plural(n: Long, unit: String) = "$n $unit" + if (n == 1L) "" else "s"
}
