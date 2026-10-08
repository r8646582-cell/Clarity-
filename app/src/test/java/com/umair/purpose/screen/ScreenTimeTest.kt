package com.umair.purpose.screen

import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.ScreenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ScreenTimeTest {
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 10, 7)

    private fun t(h: Int, m: Int = 0, d: LocalDate = day) = d.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun ev(app: String, cat: String, at: Long, resumed: Boolean) = UsageEv(app, cat, at, resumed)

    @Test
    fun `an app open from 17-30 to 18-30 is split across both hours`() {
        val rows = ScreenTimeAggregator.aggregate(
            listOf(ev("a", "social", t(17, 30), true), ev("a", "social", t(18, 30), false)), t(23), zone,
        )
        assertEquals(listOf(30, 30), rows.map { it.minutes })
        assertEquals(listOf(17, 18), rows.map { it.hour })
        assertEquals("2026-10-07", rows.first().date)
    }

    @Test
    fun `categories are kept apart and an app still open is closed at the window end`() {
        val rows = ScreenTimeAggregator.aggregate(
            listOf(
                ev("a", "social", t(9), true), ev("a", "social", t(9, 20), false),
                ev("b", "video", t(9, 30), true),
            ),
            t(9, 45), zone,
        )
        assertEquals(20, rows.single { it.category == "social" }.minutes)
        assertEquals(15, rows.single { it.category == "video" }.minutes)
    }

    @Test
    fun `a resume without a pause restarts the clock instead of double counting`() {
        val rows = ScreenTimeAggregator.aggregate(
            listOf(ev("a", "game", t(10), true), ev("a", "game", t(10, 10), true), ev("a", "game", t(10, 20), false)), t(12), zone,
        )
        assertEquals(20, rows.sumOf { it.minutes })
    }

    @Test
    fun `use across midnight lands on both days`() {
        val rows = ScreenTimeAggregator.aggregate(
            listOf(ev("a", "video", t(23, 30), true), ev("a", "video", t(0, 30, day.plusDays(1)), false)), t(23), zone,
        )
        assertEquals(setOf("2026-10-07", "2026-10-08"), rows.map { it.date }.toSet())
        assertEquals(60, rows.sumOf { it.minutes })
    }

    @Test
    fun `no events and sub-half-minute blips give nothing`() {
        assertTrue(ScreenTimeAggregator.aggregate(emptyList(), t(12), zone).isEmpty())
        val blip = listOf(ev("a", "social", t(9), true), ev("a", "social", t(9, 0).plus(10_000), false))
        assertTrue(ScreenTimeAggregator.aggregate(blip, t(12), zone).isEmpty())
    }

    private fun usage(d: LocalDate, hour: Int, minutes: Int, cat: String = "social") = ScreenUsage(d.toString(), hour, cat, minutes)

    @Test
    fun `weekly line averages per day and needs enough days`() {
        val rows = (0..3).flatMap { listOf(usage(day.minusDays(it.toLong()), 18, 60), usage(day.minusDays(it.toLong()), 20, 60, "video")) }
        val line = ScreenLedger.weeklyLine(rows, day)
        assertNotNull(line)
        assertTrue(line!!.contains("2h 0m a day"))
        assertNull(ScreenLedger.weeklyLine(rows.take(2), day))
        assertNull(ScreenLedger.weeklyLine(emptyList(), day))
    }

    private fun promise(id: Long, status: String, due: String) =
        Promise(id = id, text = "Phone away", createdAt = 1, status = status, sourceSessionId = 1, dueAt = due)

    @Test
    fun `due hour line compares kept and broken with the counts`() {
        val d1 = day.minusDays(1); val d2 = day.minusDays(2); val d3 = day.minusDays(3)
        val rows = listOf(usage(d1, 17, 5), usage(d2, 17, 10), usage(d3, 17, 120))
        val ps = listOf(
            promise(1, Promise.KEPT, "${d1}T17:00"), promise(2, Promise.KEPT, "${d2}T17:30"), promise(3, Promise.BROKEN, "${d3}T17:00"),
        )
        val line = ScreenLedger.dueHourLine(ps, rows, day, zone)
        assertNotNull(line)
        assertTrue(line!!.contains("7m on average for 2 kept"))
        assertTrue(line.contains("2h 0m on average for 1 broken"))
    }

    @Test
    fun `due hour line needs a clock time, resolved promises and phone data for that day`() {
        val rows = listOf(usage(day.minusDays(1), 17, 5))
        val ps = listOf(
            promise(1, Promise.KEPT, day.minusDays(1).toString()),
            promise(2, Promise.OPEN, "${day.minusDays(1)}T17:00"),
            promise(3, Promise.KEPT, "${day.minusDays(9)}T17:00"),
        )
        assertNull(ScreenLedger.dueHourLine(ps, rows, day, zone))
        assertNull(ScreenLedger.dueHourLine(ps, emptyList(), day, zone))
    }

    @Test
    fun `summary lists newest day first with categories`() {
        val rows = listOf(usage(day, 9, 90), usage(day, 10, 60, "video"), usage(day.minusDays(1), 9, 30))
        val lines = ScreenTimeSummary.days(rows)
        assertEquals("2026-10-07: 2h 30m (social 90, video 60)", lines.first())
        assertEquals("2026-10-06: 30m (social 30)", lines[1])
    }

    @Test
    fun `android categories map to plain names`() {
        assertEquals("social", ScreenLedger.categoryName(4))
        assertEquals("other", ScreenLedger.categoryName(-1))
    }
}
