package com.umair.purpose.promise

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class PromiseTimesTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private val now = ZonedDateTime.of(2026, 10, 6, 2, 19, 0, 0, zone)

    @Test fun `late night clocks stay local and today differs from tomorrow`() {
        val tonight = Due(LocalDate.of(2026, 10, 6), LocalTime.of(2, 30))
        assertEquals(tonight, PromiseTimes.resolve("tonight 02:30", null, now))
        assertEquals(tonight, PromiseTimes.resolve("today 02:30", null, now))
        assertEquals(tonight, PromiseTimes.resolve("2:30", null, now))
        assertEquals(tonight.copy(date = LocalDate.of(2026, 10, 7)), PromiseTimes.resolve("tomorrow 02:30", null, now))
        assertEquals(tonight.copy(date = LocalDate.of(2026, 10, 7)), PromiseTimes.resolve("02:30", null, now.withHour(3)))
    }

    @Test fun `local input overrides legacy epochs without guessing an invalid field`() {
        val due = Due(LocalDate.of(2026, 10, 6), LocalTime.of(2, 30))
        val millis = now.withMinute(30).toInstant().toEpochMilli()
        assertEquals(due, PromiseTimes.resolve(null, millis, now))
        assertEquals(due, PromiseTimes.resolve("2026-10-06T02:30", 1, now))
        assertNull(PromiseTimes.resolve("2026-10-06T02:30garbage", millis, now))
        assertNull(PromiseTimes.resolve("today 25:30", millis, now))
        assertEquals(millis, PromiseTimes.epoch(due, zone))
    }

    @Test fun `daylight saving gaps and ambiguous clocks require clarification`() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertNull(PromiseTimes.epoch(Due(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30)), berlin))
        assertNull(PromiseTimes.epoch(Due(LocalDate.of(2026, 10, 25), LocalTime.of(2, 30)), berlin))
    }
}
