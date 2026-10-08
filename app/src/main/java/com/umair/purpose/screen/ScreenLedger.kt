package com.umair.purpose.screen

import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.ScreenUsage
import com.umair.purpose.promise.due
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 4: objective phone data for the values ledger. Counted in code from the opt-in screen-time table; it only
 * grounds say-do gaps ("you said phone away at 5; the phone shows 2h at 5 to 7") and never says why. With no data
 * (the feature is off, or nothing collected yet) every function returns nothing, so no line is ever invented.
 */
object ScreenLedger {
    const val WEEKS = 4
    /** Fewest days with data before weekly numbers are shown. */
    const val MIN_DAYS = 3
    /** Fewest promises with a clock time and phone data before the due-hour line is shown. */
    const val MIN_PROMISES = 3

    private fun fmt(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

    /** Average daily minutes per week (oldest first), only for weeks with at least [MIN_DAYS] days of data. */
    fun weeklyLine(rows: List<ScreenUsage>, today: LocalDate): String? {
        val first = today.minusDays(today.dayOfWeek.value - 1L).minusWeeks(WEEKS - 1L)
        val inWindow = rows.filter { r -> LocalDate.parse(r.date).let { !it.isBefore(first) && !it.isAfter(today) } }
        if (inWindow.isEmpty()) return null
        val parts = (0 until WEEKS).mapNotNull { i ->
            val start = first.plusWeeks(i.toLong())
            val end = start.plusDays(6)
            val week = inWindow.filter { LocalDate.parse(it.date).let { d -> !d.isBefore(start) && !d.isAfter(end) } }
            val days = week.map { it.date }.distinct().size
            if (days < MIN_DAYS) null else {
                val avg = week.sumOf { it.minutes } / days
                val top = week.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.minutes } }
                    .filterKeys { it != "other" }.maxByOrNull { it.value }?.key
                "$start: ${fmt(avg)} a day" + (top?.let { " (most: $it)" } ?: "")
            }
        }
        if (parts.isEmpty()) return null
        return "Phone screen time, weekly average per day, last $WEEKS weeks (measured by his phone, opt-in): " + parts.joinToString("; ") + "."
    }

    /**
     * Screen minutes in the hour of the day a promise was due, for promises he resolved that had a clock time.
     * Described side by side for kept and broken, with the counts; never as a cause.
     */
    fun dueHourLine(promises: List<Promise>, rows: List<ScreenUsage>, today: LocalDate, zone: ZoneId): String? {
        if (rows.isEmpty()) return null
        val byDayHour = rows.groupBy { it.date to it.hour }.mapValues { e -> e.value.sumOf { it.minutes } }
        val days = rows.map { it.date }.toSet()
        val samples = promises.filter { !it.offTheRecord && (it.status == Promise.KEPT || it.status == Promise.BROKEN) }
            .mapNotNull { p ->
                val d = p.due ?: return@mapNotNull null
                val time = d.time ?: return@mapNotNull null
                val date = d.date
                if (date.isAfter(today) || date.toString() !in days) return@mapNotNull null
                p.status to (byDayHour[date.toString() to time.hour] ?: 0)
            }
        if (samples.size < MIN_PROMISES) return null
        fun avg(status: String) = samples.filter { it.first == status }.map { it.second }.takeIf { it.size >= 1 }?.let { it.sum() / it.size to it.size }
        val kept = avg(Promise.KEPT)
        val broken = avg(Promise.BROKEN)
        val bits = listOfNotNull(
            kept?.let { "${fmt(it.first)} on average for ${it.second} kept" },
            broken?.let { "${fmt(it.first)} on average for ${it.second} broken" },
        )
        return "Phone screen time in the hour a promise was due: " + bits.joinToString(", ") + "."
    }

    fun lines(promises: List<Promise>, rows: List<ScreenUsage>, today: LocalDate, zone: ZoneId): List<String> =
        listOfNotNull(weeklyLine(rows, today), dueHourLine(promises, rows, today, zone))

    /** The category an Android `ApplicationInfo.CATEGORY_*` value stands for. */
    fun categoryName(androidCategory: Int): String = when (androidCategory) {
        0 -> "game"; 1 -> "audio"; 2 -> "video"; 3 -> "image"; 4 -> "social"
        5 -> "news"; 6 -> "maps"; 7 -> "productivity"; else -> "other"
    }
}

/** Phase 4: what "View my data" shows, so he can see exactly what is stored. */
object ScreenTimeSummary {
    /** Newest day first: "2026-10-07: 3h 5m (social 90, video 60, other 35)". */
    fun days(rows: List<ScreenUsage>, limit: Int = 14): List<String> =
        rows.groupBy { it.date }.toSortedMap(reverseOrder()).entries.take(limit).map { (date, day) ->
            val total = day.sumOf { it.minutes }
            val cats = day.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.minutes } }
                .entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }
            "$date: ${if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"} ($cats)"
        }
}
