package com.umair.purpose.letter

import com.umair.purpose.data.db.Letter
import com.umair.purpose.data.db.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LetterPlanningTest {
    private val first = LocalDate.of(2026, 9, 1)
    private val always: (LetterPeriod) -> Boolean = { true }
    private fun letter(kind: String, start: String, end: String) =
        Letter(kind = kind, periodStart = start, periodEnd = end, createdAt = 0, title = "t", content = "c")

    @Test
    fun `weekly is due from Sunday 20 00, covering Monday to Sunday`() {
        // Sunday 4 Oct 2026, 19:59: last week's letter, not this one.
        val before = LetterPlanning.due(LocalDateTime.of(2026, 10, 4, 19, 59), emptyList(), first, always)
        assertEquals(LetterPeriod(Letter.WEEKLY, LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27)), before.first())
        val at = LetterPlanning.due(LocalDateTime.of(2026, 10, 4, 20, 0), emptyList(), first, always)
        assertEquals(LetterPeriod(Letter.WEEKLY, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4)), at.first())
    }

    @Test
    fun `nothing twice, and catch-up after the phone was off`() {
        val now = LocalDateTime.of(2026, 10, 7, 9, 0) // Wednesday after
        val written = listOf(letter(Letter.WEEKLY, "2026-09-28", "2026-10-04"), letter(Letter.MONTHLY, "2026-09-01", "2026-09-30"))
        assertTrue(LetterPlanning.due(now, written, first, always).isEmpty())
        val missed = LetterPlanning.due(now, emptyList(), first, always)
        assertEquals(listOf(Letter.WEEKLY, Letter.MONTHLY), missed.map { it.kind })
        assertEquals(LocalDate.of(2026, 9, 30), missed[1].end)
    }

    @Test
    fun `monthly on the 1st and yearly in January, in order`() {
        val due = LetterPlanning.due(LocalDateTime.of(2027, 1, 1, 9, 0), emptyList(), LocalDate.of(2026, 3, 10), always)
        // With nothing written, every complete month since his first conversation is owed, oldest first and
        // capped per run (MONTHLY_CATCH_UP_MAX), with the year last so it can read the months.
        assertEquals(
            listOf(Letter.WEEKLY, Letter.MONTHLY, Letter.MONTHLY, Letter.MONTHLY, Letter.YEARLY),
            due.map { it.kind },
        )
        assertEquals(LetterPeriod(Letter.MONTHLY, LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 31)), due[1])
        assertEquals(LocalDate.of(2026, 5, 31), due[LetterPlanning.MONTHLY_CATCH_UP_MAX].end)
        // The year starts at his first conversation.
        assertEquals(LetterPeriod(Letter.YEARLY, LocalDate.of(2026, 3, 10), LocalDate.of(2026, 12, 31)), due.last())
    }

    @Test
    fun `months already written are skipped and the newest missing one is caught up`() {
        // Everything from March to November is written; only December is missing.
        val written = listOf(
            letter(Letter.WEEKLY, "2026-12-21", "2026-12-27"),
            letter(Letter.MONTHLY, "2026-03-10", "2026-03-31"),
            letter(Letter.MONTHLY, "2026-04-01", "2026-04-30"),
            letter(Letter.MONTHLY, "2026-05-01", "2026-05-31"),
            letter(Letter.MONTHLY, "2026-06-01", "2026-06-30"),
            letter(Letter.MONTHLY, "2026-07-01", "2026-07-31"),
            letter(Letter.MONTHLY, "2026-08-01", "2026-08-31"),
            letter(Letter.MONTHLY, "2026-09-01", "2026-09-30"),
            letter(Letter.MONTHLY, "2026-10-01", "2026-10-31"),
            letter(Letter.MONTHLY, "2026-11-01", "2026-11-30"),
        )
        val due = LetterPlanning.due(LocalDateTime.of(2027, 1, 1, 9, 0), written, LocalDate.of(2026, 3, 10), always)
        assertEquals(listOf(Letter.MONTHLY, Letter.YEARLY), due.map { it.kind })
        assertEquals(LetterPeriod(Letter.MONTHLY, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)), due[0])
    }

    @Test
    fun `periods start no earlier than his first session, and need a meaningful conversation`() {
        val now = LocalDateTime.of(2026, 10, 1, 9, 0)
        val due = LetterPlanning.due(now, emptyList(), LocalDate.of(2026, 9, 25), always)
        assertEquals(LocalDate.of(2026, 9, 25), due.first { it.kind == Letter.MONTHLY }.start)
        assertEquals(LocalDate.of(2026, 9, 25), due.first { it.kind == Letter.WEEKLY }.start)
        assertTrue(LetterPlanning.due(now, emptyList(), LocalDate.of(2026, 9, 25)) { false }.isEmpty())
        assertTrue(LetterPlanning.due(now, emptyList(), null, always).isEmpty())
        assertTrue(LetterPlanning.due(now, emptyList(), LocalDate.of(2026, 10, 1), always).none { it.kind == Letter.MONTHLY })
    }

    @Test
    fun `a weekly written early covers only the days after it`() {
        val early = listOf(letter(Letter.WEEKLY, "2026-09-28", "2026-09-30"))
        val due = LetterPlanning.due(LocalDateTime.of(2026, 10, 4, 21, 0), early, first, always)
        assertEquals(LetterPeriod(Letter.WEEKLY, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4)), due.first())
        assertEquals(
            LetterPeriod(Letter.WEEKLY, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)),
            LetterPlanning.thisWeekNow(LocalDate.of(2026, 10, 2), early, first),
        )
    }

    @Test
    fun `next check is the earlier of next Sunday 20 00 and the 1st`() {
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 5), LetterPlanning.nextMoment(LocalDateTime.of(2026, 9, 30, 12, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 11, 20, 0), LetterPlanning.nextMoment(LocalDateTime.of(2026, 10, 4, 20, 0)))
    }

    @Test
    fun `title is the first line`() {
        assertEquals("The week you admitted it was fear" to "Body one.\n\nBody two.",
            LetterPlanning.split("\n## The week you admitted it was fear**\n\nBody one.\n\nBody two.\n"))
    }

    @Test
    fun `transcript budget takes the most significant first and summarises the rest`() {
        fun s(id: Long, sig: Int?) = Session(id = id, startedAt = id, significance = sig, summary = "sum$id")
        val sessions = listOf(s(1, 2), s(2, 5), s(3, 4), s(4, null))
        val texts = mapOf(1L to "a".repeat(40), 2L to "b".repeat(40), 3L to "c".repeat(40), 4L to "d".repeat(4))
        val picked = TranscriptBudget.select(sessions, texts, budgetTokens = 21) // 84 chars
        assertEquals(listOf(2L, 3L, 1L, 4L), picked.map { it.session.id })
        assertEquals(listOf(true, true, false, true), picked.map { it.fullText != null })
    }
    @Test
    fun `quiet months do not starve later meaningful monthly letters`() {
        val due = LetterPlanning.due(LocalDateTime.of(2026, 10, 5, 12, 0), emptyList(), LocalDate.of(2026, 1, 1)) {
            it.kind == Letter.MONTHLY && it.start.monthValue >= 5
        }
        assertEquals(listOf(5, 6, 7), due.map { it.start.monthValue })
    }
}
