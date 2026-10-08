package com.umair.purpose.time

import com.umair.purpose.promise.Due
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeFactsTest {
    private val karachi = ZoneId.of("Asia/Karachi")

    @Test
    fun `greeting bands and their edges`() {
        fun g(h: Int, m: Int) = TimeFacts.greeting(LocalTime.of(h, m))
        assertEquals("Still up, Umair?", g(4, 59))
        assertEquals("Good morning", g(5, 0))
        assertEquals("Good morning", g(11, 59))
        assertEquals("Good afternoon", g(12, 0))
        assertEquals("Good afternoon", g(16, 59))
        assertEquals("Good evening", g(17, 0))
        assertEquals("Good evening", g(21, 59))
        assertEquals("Still up, Umair?", g(22, 0))
        assertEquals("Still up, Umair?", g(0, 17))
    }

    @Test
    fun `logical day keeps late hours on the night before`() {
        fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 4, h, m, 0, 0, karachi)
        assertEquals(LocalDate.of(2026, 10, 3), TimeFacts.logicalDate(at(0, 46)))
        assertEquals(LocalDate.of(2026, 10, 3), TimeFacts.logicalDate(at(3, 59)))
        assertEquals(LocalDate.of(2026, 10, 4), TimeFacts.logicalDate(at(4, 0)))
        assertEquals(LocalDate.of(2026, 10, 4), TimeFacts.logicalDate(at(23, 59)))
    }

    @Test
    fun `now is a finished sentence, and after midnight it's still last night for him`() {
        val t = ZonedDateTime.of(2026, 10, 4, 0, 46, 0, 0, karachi)
        assertEquals(
            "It's Sunday 4 October 2026, 12:46am (late night; for him it's still Saturday night). Time zone: Asia/Karachi. Local now: 2026-10-04T00:46. Today: 2026-10-04; tomorrow: 2026-10-05. Use calendar dates for today/tomorrow; tonight before 4am means the night still in progress.",
            TimeFacts.nowSentence(t),
        )
        assertEquals("It's Saturday 3 October 2026, 9:05pm (evening). Time zone: Asia/Karachi. Local now: 2026-10-03T21:05. Today: 2026-10-03; tomorrow: 2026-10-04. Use calendar dates for today/tomorrow; tonight before 4am means the night still in progress.", TimeFacts.nowSentence(t.withDayOfMonth(3).withHour(21).withMinute(5)))
    }

    @Test
    fun `due phrases are worked out in code`() {
        val now = LocalDateTime.of(2026, 10, 4, 1, 0)
        assertEquals("due Monday 5 Oct, 8:00am (in 31 hours)", TimeFacts.duePhrase(Due(LocalDate.of(2026, 10, 5), LocalTime.of(8, 0)), now))
        assertEquals("due Tuesday 6 Oct, 8:00am (in 2 days)", TimeFacts.duePhrase(Due(LocalDate.of(2026, 10, 6), LocalTime.of(8, 0)), now))
        assertEquals("was due Friday 2 Oct, 5:00pm (32 hours ago)", TimeFacts.duePhrase(Due(LocalDate.of(2026, 10, 2), LocalTime.of(17, 0)), now))
        assertEquals("was due Wednesday 30 Sep (4 days ago)", TimeFacts.duePhrase(Due(LocalDate.of(2026, 9, 30), null), now))
        assertEquals("due today, Sunday 4 Oct", TimeFacts.duePhrase(Due(LocalDate.of(2026, 10, 4), null), now))
        assertEquals("due Sunday 4 Oct, 1:20am (in 20 minutes)", TimeFacts.duePhrase(Due(LocalDate.of(2026, 10, 4), LocalTime.of(1, 20)), now))
    }

    @Test
    fun `his messages carry a short timestamp`() {
        val ms = ZonedDateTime.of(2026, 10, 3, 23, 12, 0, 0, karachi).toInstant().toEpochMilli()
        assertEquals("[Sat 3 Oct, 23:12]", TimeFacts.messageStamp(ms, karachi))
        assertEquals("last night", TimeFacts.since(ms, ZonedDateTime.of(2026, 10, 4, 9, 0, 0, 0, karachi)))
    }
}
