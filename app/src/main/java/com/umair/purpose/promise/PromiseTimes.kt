package com.umair.purpose.promise

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Local clock inputs are converted in code; models do not need to calculate Unix timestamps. */
object PromiseTimes {
    fun resolve(local: String?, epochMs: Long?, now: ZonedDateTime): Due? {
        if (local != null) {
            val value = local.trim().lowercase().replace(Regex("\\s+at\\s+"), " ")
            // A present but invalid local field must not silently fall back to a conflicting epoch.
            Due.parse(local)?.let { return it }
            val relative = Regex("^(today|tomorrow|tonight)\\s+(\\d{1,2}:\\d{2})$").matchEntire(value)
            if (relative != null) {
                val time = clock(relative.groupValues[2]) ?: return null
                val date = when (relative.groupValues[1]) {
                    "today" -> now.toLocalDate()
                    "tomorrow" -> now.toLocalDate().plusDays(1)
                    else -> {
                        val night = if (now.hour < 4) now.toLocalDate().minusDays(1) else now.toLocalDate()
                        if (time.hour < 4) night.plusDays(1) else night
                    }
                }
                return Due(date, time)
            }
            val time = clock(value) ?: return null
            val date = if (now.toLocalTime() > time) now.toLocalDate().plusDays(1) else now.toLocalDate()
            return Due(date, time)
        }
        return epochMs?.takeIf { it > 0 }?.let {
            val at = Instant.ofEpochMilli(it).atZone(now.zone)
            Due(at.toLocalDate(), at.toLocalTime().withSecond(0).withNano(0))
        }
    }

    fun epoch(due: Due, zone: ZoneId): Long? {
        val time = due.time ?: return null
        val local = LocalDateTime.of(due.date, time)
        val offsets = zone.rules.getValidOffsets(local)
        // Nonexistent/ambiguous daylight-saving clocks require clarification, not a silent time shift.
        if (offsets.size != 1) return null
        return local.toInstant(offsets.single()).toEpochMilli()
    }

    private fun clock(raw: String): LocalTime? = runCatching {
        if (!Regex("\\d{1,2}:\\d{2}").matches(raw)) return null
        LocalTime.parse(raw.padStart(5, '0'))
    }.getOrNull()
}
