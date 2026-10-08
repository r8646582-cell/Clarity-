package com.umair.purpose.memory

import com.umair.purpose.data.db.Chapter
import java.time.LocalDate

/** A three-month stretch of his life. */
data class ChapterPeriod(val start: LocalDate, val end: LocalDate) {
    operator fun contains(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
    /** "July to September 2026" */
    val label: String
        get() = "${month(start)} to ${month(end)} ${end.year}"

    private fun month(d: LocalDate) = d.month.name.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * UPDATE-15: life chapters, one per quarter (January to March, ...), written once the quarter is over (so the
 * October-to-December one on Jan 1). Missed ones are caught up; a quarter with no meaningful conversation gets
 * none; never two for the same quarter; nothing before his first conversation.
 */
object ChapterPlanning {
    fun quarterOf(d: LocalDate): ChapterPeriod {
        val startMonth = ((d.monthValue - 1) / 3) * 3 + 1
        val start = LocalDate.of(d.year, startMonth, 1)
        return ChapterPeriod(start, start.plusMonths(3).minusDays(1))
    }

    /** Finished quarters, oldest first, that are owed a chapter as of [today]. */
    fun due(
        today: LocalDate,
        firstSession: LocalDate?,
        existing: List<Chapter>,
        hasMeaningful: (ChapterPeriod) -> Boolean,
    ): List<ChapterPeriod> {
        if (firstSession == null) return emptyList()
        val out = mutableListOf<ChapterPeriod>()
        var q = quarterOf(firstSession)
        val current = quarterOf(today)
        while (q.start.isBefore(current.start)) {
            val period = ChapterPeriod(maxOf(q.start, firstSession), q.end)
            val written = existing.any { LocalDate.parse(it.periodEnd) == q.end }
            if (!written && hasMeaningful(period)) out += period
            q = quarterOf(q.end.plusDays(1))
        }
        return out
    }
}
