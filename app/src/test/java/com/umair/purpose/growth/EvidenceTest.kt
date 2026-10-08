package com.umair.purpose.growth

import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** UPDATE-18: the evidence behind every leaf is counted in code, and absence of data never counts. */
class EvidenceTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private val today = LocalDate.of(2026, 11, 30)
    private fun ms(d: LocalDate, hour: Int = 18) = d.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun promise(id: Long, d: LocalDate, status: String, key: String? = "study_5pm", text: String = "Study FAR at 5pm") =
        Promise(id = id, text = text, createdAt = ms(d.minusDays(1)), dueAt = "${d}T17:00", status = status, sourceSessionId = 1, actionKey = key)

    @Test
    fun `21 days of consistency meets habit_built`() {
        // 24 planned days from Nov 7, kept on 19 (79%), including this week.
        val days = (0L until 24L).map { today.minusDays(23 - it) }
        val promises = days.mapIndexed { i, d -> promise(i.toLong() + 1, d, if (i % 5 == 1) Promise.BROKEN else Promise.KEPT) }
        val a = Evidence.actions(today, zone, promises).single()
        assertEquals("study_5pm", a.key)
        assertEquals(24, a.planned30)
        assertEquals(19, a.kept30)
        assertEquals(24, a.spanDays)
        assertTrue(a.keptInLast7)
        assertTrue(a.meetsHabitBuilt)
    }

    @Test
    fun `too short, too patchy or stopped this week does not count`() {
        val short = (0L until 14L).map { today.minusDays(13 - it) }.mapIndexed { i, d -> promise(i + 1L, d, Promise.KEPT) }
        assertFalse(Evidence.actions(today, zone, short).single().meetsHabitBuilt)

        val patchy = (0L until 30L).map { today.minusDays(29 - it) }.mapIndexed { i, d -> promise(i + 1L, d, if (i % 2 == 0) Promise.KEPT else Promise.BROKEN) }
        assertFalse(Evidence.actions(today, zone, patchy).single().meetsHabitBuilt)

        // Kept for a month, then nothing kept in the last 7 days.
        val stopped = (0L until 30L).map { today.minusDays(29 - it) }.mapIndexed { i, d -> promise(i + 1L, d, if (i < 23) Promise.KEPT else Promise.BROKEN) }
        val s = Evidence.actions(today, zone, stopped).single()
        assertFalse(s.keptInLast7)
        assertFalse(s.meetsHabitBuilt)
    }

    @Test
    fun `untagged promises group by their words, future ones are not yet planned`() {
        val a = promise(1, today.minusDays(2), Promise.KEPT, key = null, text = "Read FAR for 20 minutes tonight")
        val b = promise(2, today.minusDays(1), Promise.KEPT, key = null, text = "read FAR 20 minutes")
        val future = promise(3, today.plusDays(1), Promise.OPEN, key = null, text = "Read FAR for 20 minutes")
        val stats = Evidence.actions(today, zone, listOf(a, b, future))
        assertEquals(1, stats.size)
        assertEquals(2, stats.single().planned14)
    }

    private fun note(timesSeen: Int, first: LocalDate, last: LocalDate) = Note(
        id = 7, type = "pattern", text = "You scroll when studying feels heavy", confidence = "likely",
        status = Note.ACTIVE, timesSeen = timesSeen, firstSeen = ms(first), lastSeen = ms(last),
    )

    private fun sessions(days: List<LocalDate>) = days.mapIndexed { i, d -> Session(id = i + 1L, startedAt = ms(d, 21), userMessageCount = 6, reflected = true) }

    @Test
    fun `30 days of absence while active meets habit_unlearned`() {
        val n = note(timesSeen = 4, first = today.minusDays(90), last = today.minusDays(35))
        val talked = (1L..8L).map { today.minusDays(it * 4) }
        val s = Evidence.compute(today, zone, emptyList(), listOf(n), sessions(talked), talked.map { ms(it, 21) })
        val p = s.patterns.single()
        assertEquals(35, p.daysSinceLast)
        assertEquals(8, p.conversationsSince)
        assertTrue(p.meetsUnlearned)
        assertTrue(s.anyUnlearned)
    }

    @Test
    fun `absence of data never counts`() {
        // Not seen for 60 days, but he barely talked to Purpose in that time: that's silence, not change.
        val n = note(timesSeen = 5, first = today.minusDays(120), last = today.minusDays(60))
        val talked = listOf(today.minusDays(40), today.minusDays(3))
        val s = Evidence.compute(today, zone, emptyList(), listOf(n), sessions(talked), talked.map { ms(it, 21) })
        assertFalse(s.patterns.single().meetsUnlearned)
        // Seen only twice: never a pattern worth "unlearning".
        val rare = note(timesSeen = 2, first = today.minusDays(120), last = today.minusDays(60))
        val busy = (1L..10L).map { today.minusDays(it * 4) }
        assertFalse(Evidence.compute(today, zone, emptyList(), listOf(rare), sessions(busy), emptyList()).patterns.single().meetsUnlearned)
        // No promises at all: no habit evidence.
        val empty = Evidence.compute(today, zone, emptyList(), emptyList(), emptyList(), emptyList())
        assertFalse(empty.anyHabitBuilt)
        assertFalse(empty.anyUnlearned)
        assertTrue(Evidence.format(empty).contains("(none yet)"))
    }

    @Test
    fun `stats text says plainly what meets a minimum`() {
        val days = (0L until 24L).map { today.minusDays(23 - it) }
        val s = Evidence.compute(today, zone, days.mapIndexed { i, d -> promise(i + 1L, d, Promise.KEPT) }, emptyList(), emptyList(), emptyList())
        val text = Evidence.format(s)
        assertTrue(text.contains("study_5pm"))
        assertTrue(text.contains("30 days 24/24"))
        assertTrue(text.contains("MEETS the habit_built minimum"))
    }

    @Test
    fun `off the record promises never count toward growth evidence`() {
        val remembered = (1..3).map { promise(it.toLong(), today.minusDays(it.toLong()), Promise.KEPT) }
        val secret = (4..9).map { promise(it.toLong(), today.minusDays(it.toLong()), Promise.KEPT).copy(offTheRecord = true) }
        val stats = Evidence.actions(today, zone, remembered + secret).single()
        assertEquals(3, stats.planned14)
        assertEquals(3, stats.kept14)
    }
}
