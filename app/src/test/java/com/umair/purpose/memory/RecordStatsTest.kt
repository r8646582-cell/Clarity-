package com.umair.purpose.memory

import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.Pulse
import com.umair.purpose.data.db.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class RecordStatsTest {
    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 3)
    private fun ms(d: LocalDate, hour: Int = 12) = d.atTime(hour, 0).toInstant(utc).toEpochMilli()

    private fun p(id: Long, status: String, resolved: LocalDate, due: String? = null, area: String? = null) = Promise(
        id = id, text = "p$id", createdAt = 0, dueAt = due, status = status, sourceSessionId = 1, resolvedAt = ms(resolved), area = area,
    )

    @Test
    fun `fewer than three resolved promises gives no promise line`() {
        val ps = listOf(p(1, Promise.KEPT, today), p(2, Promise.BROKEN, today), p(3, Promise.OPEN, today))
        assertNull(RecordStats.promiseLine(ps, today, utc))
    }

    @Test
    fun `overall, recent and current run`() {
        val ps = listOf(
            p(1, Promise.BROKEN, today.minusDays(60)),
            p(2, Promise.KEPT, today.minusDays(50)),
            p(3, Promise.BROKEN, today.minusDays(5)),
            p(4, Promise.KEPT, today.minusDays(3)),
            p(5, Promise.KEPT, today.minusDays(2)),
            p(6, Promise.KEPT, today.minusDays(1)),
            p(7, Promise.DROPPED, today),
        )
        assertEquals(
            "Promises: kept 4 of 6 overall, 3 of 4 in the last 30 days. Current run: 3 kept in a row.",
            RecordStats.promiseLine(ps, today, utc),
        )
    }

    @Test
    fun `area and due-time lines need three behind each number`() {
        val ps = listOf(
            p(1, Promise.KEPT, today, "2026-10-01T09:00", "studies_career"),
            p(2, Promise.KEPT, today, "2026-10-01T10:00", "studies_career"),
            p(3, Promise.BROKEN, today, "2026-10-01T23:30", "studies_career"),
            p(4, Promise.BROKEN, today, "2026-10-01T23:45", "health"),
            p(5, Promise.BROKEN, today, "2026-10-02T00:30", "health"),
            p(6, Promise.KEPT, today, "2026-10-02T08:00"),
        )
        assertEquals("Kept rate by area: studies career 2/3.", RecordStats.areaLine(ps))
        assertEquals("Kept rate by due time: morning 3/3, after 11pm 0/3.", RecordStats.dueTimeLine(ps))
    }

    @Test
    fun `when he talks finds the busiest three hours`() {
        fun s(id: Long, hour: Int) = Session(id = id, startedAt = ms(today.minusDays(id), hour), userMessageCount = 5)
        val sessions = listOf(s(1, 22), s(2, 23), s(3, 0), s(4, 23), s(5, 14), s(6, 22))
        assertEquals("When he talks: mostly 10pm to 1am.", RecordStats.whenHeTalks(sessions, utc))
        assertNull(RecordStats.whenHeTalks(sessions.take(4), utc))
    }

    @Test
    fun `tone over the last two weeks, oldest first`() {
        val sessions = listOf("hopeful", "low", "tired", "old").mapIndexed { i, t ->
            Session(id = i.toLong(), startedAt = ms(today.minusDays(if (t == "old") 30 else 10L - i)), tone = t)
        }
        assertEquals("Tone over the last 2 weeks, oldest to newest: hopeful → low → tired.", RecordStats.toneLine(sessions, today, utc))
    }

    @Test
    fun `mood lines`() {
        val pulses = (0..13).map { Pulse(today.minusDays(it.toLong()).toString(), if (it < 7) 4 else 2, 3) }
        assertEquals("Mood and energy (out of 5): this week 4.0 and 3.0, last week 2.0 and 3.0.", RecordStats.moodWeekLine(pulses, today))

        val promises = (0..2).map { p(it.toLong(), Promise.KEPT, today.minusDays(it.toLong())) } +
            (7..9).map { p(it.toLong(), Promise.BROKEN, today.minusDays(it.toLong())) }
        assertEquals("Mood was 4.0 on days he kept a promise and 2.0 on days he broke one.", RecordStats.moodPromiseLine(pulses, promises, utc))

        val late = (8..10).map { Session(id = it.toLong(), startedAt = ms(today.minusDays(it.toLong()), 23), userMessageCount = 3) }
        val line = RecordStats.moodLateNightLine(pulses, late, utc)!!
        assertTrue(line.startsWith("Mood was 2.0 the day after a late-night conversation"))
    }

    @Test
    fun `with little data there are no lines at all`() {
        assertTrue(RecordStats.lines(emptyList(), emptyList(), emptyList(), today, utc).isEmpty())
    }
}
