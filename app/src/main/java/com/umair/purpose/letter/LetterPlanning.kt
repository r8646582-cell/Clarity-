package com.umair.purpose.letter

import com.umair.purpose.data.db.Letter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

data class LetterPeriod(val kind: String, val start: LocalDate, val end: LocalDate) {
    operator fun contains(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
}

/**
 * When letters are owed. Weekly on Sunday at 20:00 (Monday to Sunday), monthly on the 1st (the previous month),
 * yearly on Jan 1 (the previous year). Missed ones are caught up on the next run. Never two letters for the
 * same stretch of days; a period never starts before his first conversation; no letter without at least
 * one meaningful conversation in it.
 */
object LetterPlanning {
    val WEEKLY_AT: LocalTime = LocalTime.of(20, 0)
    val MONTHLY_AT: LocalTime = LocalTime.of(0, 5)

    /** The most recent Sunday 20:00 at or before [now]. */
    fun lastWeeklyMoment(now: LocalDateTime): LocalDateTime {
        val sunday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        val moment = sunday.atTime(WEEKLY_AT)
        return if (moment.isAfter(now)) moment.minusWeeks(1) else moment
    }

    /** When the next automatic letter could be due, for scheduling the next check. */
    fun nextMoment(now: LocalDateTime): LocalDateTime {
        val weekly = lastWeeklyMoment(now).plusWeeks(1)
        val monthly = now.toLocalDate().withDayOfMonth(1).plusMonths(1).atTime(MONTHLY_AT)
        return if (weekly.isBefore(monthly)) weekly else monthly
    }

    /** How many missed monthly letters one run may write; the next run picks up any that are left. */
    const val MONTHLY_CATCH_UP_MAX = 3

    /**
     * Letters owed at [now], in the order they should be written (weekly, then monthly, then yearly,
     * so each can read the ones before it).
     */
    fun due(
        now: LocalDateTime,
        existing: List<Letter>,
        firstSession: LocalDate?,
        hasMeaningful: (LetterPeriod) -> Boolean,
    ): List<LetterPeriod> {
        if (firstSession == null) return emptyList()
        val out = mutableListOf<LetterPeriod>()

        val sunday = lastWeeklyMoment(now).toLocalDate()
        weekly(sunday, existing, firstSession)?.takeIf(hasMeaningful)?.let { out += it }

        val today = now.toLocalDate()
        // Every complete month with no letter yet, oldest first. The previous month is finished the moment the
        // 1st arrives, so there is no time-of-day gate: the old 00:05 gate left a five-minute hole on the 1st
        // where a launch saw nothing owed, rescheduled to the next boundary, and never wrote that month's letter.
        out += missedMonths(today, existing, firstSession, MONTHLY_CATCH_UP_MAX, hasMeaningful)
        // The previous year, once January has begun.
        if (today.monthValue == 1) {
            clampedNew(Letter.YEARLY, LocalDate.of(today.year - 1, 1, 1), LocalDate.of(today.year - 1, 12, 31), existing, firstSession)
                ?.takeIf(hasMeaningful)?.let { out += it }
        }
        return out
    }

    /**
     * Complete months that should already have a letter and don't, oldest first, at most [max]. This is how a
     * missed monthly letter is caught up: before, only the month immediately before today was ever considered,
     * so after a gap of several months the earlier ones were lost for good.
     */
    private fun missedMonths(today: LocalDate, existing: List<Letter>, firstSession: LocalDate, max: Int, hasMeaningful: (LetterPeriod) -> Boolean): List<LetterPeriod> {
        val written = existing.filter { it.kind == Letter.MONTHLY }.map { LocalDate.parse(it.periodEnd) }.toSet()
        val lastComplete = today.withDayOfMonth(1).minusMonths(1)
        val out = mutableListOf<LetterPeriod>()
        var month = firstSession.withDayOfMonth(1)
        while (!month.isAfter(lastComplete) && out.size < max) {
            val end = month.plusMonths(1).minusDays(1)
            if (end !in written) {
                val period = LetterPeriod(Letter.MONTHLY, latest(listOf(month, firstSession))!!, end)
                if (hasMeaningful(period)) out += period
            }
            month = month.plusMonths(1)
        }
        return out
    }

    /** The week ending on [sunday], starting after any weekly letter already written for part of it. */
    internal fun weekly(sunday: LocalDate, existing: List<Letter>, firstSession: LocalDate): LetterPeriod? {
        val weeklies = existing.filter { it.kind == Letter.WEEKLY }
        if (weeklies.any { LocalDate.parse(it.periodEnd) >= sunday }) return null
        val afterLast = latest(weeklies.map { LocalDate.parse(it.periodEnd) })?.plusDays(1)
        val start = latest(listOfNotNull(sunday.minusDays(6), afterLast, firstSession))!!
        return if (start.isAfter(sunday)) null else LetterPeriod(Letter.WEEKLY, start, sunday)
    }

    private fun clampedNew(kind: String, start: LocalDate, end: LocalDate, existing: List<Letter>, firstSession: LocalDate): LetterPeriod? {
        if (existing.any { it.kind == kind && it.periodEnd == end.toString() }) return null
        val s = latest(listOf(start, firstSession))!!
        return if (s.isAfter(end)) null else LetterPeriod(kind, s, end)
    }

    /** "Write this week's letter now": from Monday (or the day after the last weekly letter) to today. */
    fun thisWeekNow(today: LocalDate, existing: List<Letter>, firstSession: LocalDate?): LetterPeriod {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val afterLast = latest(existing.filter { it.kind == Letter.WEEKLY }.map { LocalDate.parse(it.periodEnd) })?.plusDays(1)
        val start = latest(listOfNotNull(monday, afterLast, firstSession))!!
        return LetterPeriod(Letter.WEEKLY, if (start.isAfter(today)) today else start, today)
    }

    /**
     * Whether a conversation belongs in [period]'s letter. By the local day it started; plus (UPDATE-12) one from
     * the day before the period that started after that day's letter was written: the weekly letter is written on
     * Sunday at 20:00, so a talk on Sunday night used to fall in no letter at all.
     */
    fun belongs(period: LetterPeriod, startedOn: LocalDate, startedAt: Long, existing: List<Letter>): Boolean {
        if (startedOn in period) return true
        if (startedOn != period.start.minusDays(1)) return false
        val before = existing.filter { it.kind == period.kind && it.periodEnd == startedOn.toString() }
        return before.isNotEmpty() && before.all { startedAt >= it.createdAt }
    }

    /** A letter already covers any of [period]'s days: writing it would say the same days twice. */
    fun alreadyWritten(period: LetterPeriod, existing: List<Letter>): Boolean = existing.any {
        it.kind == period.kind && !LocalDate.parse(it.periodEnd).isBefore(period.start) && !LocalDate.parse(it.periodStart).isAfter(period.end)
    }

    private fun latest(dates: List<LocalDate>): LocalDate? = dates.maxByOrNull { it.toEpochDay() }

    /** First line = title (any markdown marks stripped); the rest = content. */
    fun split(text: String): Pair<String, String> {
        val trimmed = text.trim()
        val firstLine = trimmed.lineSequence().first()
        val title = firstLine.trim().trimStart('#', '*', ' ').trimEnd('*', ' ').trim()
        val rest = trimmed.substring(firstLine.length).trim()
        return title to rest
    }
}
