package com.umair.purpose.cost

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * UPDATE-17: the provider's cheaper hours. DeepSeek (from August 2026) charges full price in two weekday windows,
 * 01:00-04:00 and 06:00-10:00 UTC, and half price at every other hour and all weekend. The windows are settings,
 * so a change in pricing is a settings change, not an app update.
 */
data class OffPeakConfig(
    val enabled: Boolean = true,
    /** "01:00-04:00,06:00-10:00", in UTC. */
    val peakWindows: String = DEFAULT_WINDOWS,
    val weekdaysOnly: Boolean = true,
    /** Off-peak price as a share of the normal one. */
    val factor: Double = 0.5,
) {
    companion object {
        const val DEFAULT_WINDOWS = "01:00-04:00,06:00-10:00"
    }
}

object OffPeak {
    data class Window(val start: LocalTime, val end: LocalTime)

    /** Malformed parts are skipped; an empty result means no peak hours at all. */
    fun parse(windows: String): List<Window> = windows.split(',').mapNotNull { part ->
        val (a, b) = part.trim().split('-').map { it.trim() }.takeIf { it.size == 2 } ?: return@mapNotNull null
        runCatching { Window(LocalTime.parse(a.padStart(5, '0')), LocalTime.parse(b.padStart(5, '0'))) }.getOrNull()
    }

    fun isPeak(at: Instant, c: OffPeakConfig): Boolean {
        val t = at.atZone(ZoneOffset.UTC)
        if (c.weekdaysOnly && (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY)) return false
        val time = t.toLocalTime()
        return parse(c.peakWindows).any { w ->
            if (w.start <= w.end) !time.isBefore(w.start) && time.isBefore(w.end)
            else !time.isBefore(w.start) || time.isBefore(w.end)
        }
    }

    /** Whether a request at [at] is billed at the off-peak price. */
    fun isOffPeak(at: Instant, c: OffPeakConfig): Boolean = c.enabled && !isPeak(at, c)

    /**
     * How long a job that can wait should wait for the cheaper hours: zero when it's off-peak now (or the feature
     * is off). Steps forward minute by minute through the next day, so it's always right at window edges.
     */
    fun delayUntilOffPeak(now: Instant, c: OffPeakConfig): Duration {
        if (!c.enabled || !isPeak(now, c)) return Duration.ZERO
        var t: ZonedDateTime = now.atZone(ZoneOffset.UTC).withSecond(0).withNano(0).plusMinutes(1)
        val limit = now.plus(Duration.ofDays(1))
        while (t.toInstant().isBefore(limit)) {
            if (!isPeak(t.toInstant(), c)) return Duration.between(now, t.toInstant())
            t = t.plusMinutes(1)
        }
        return Duration.ZERO
    }
}
