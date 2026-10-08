package com.umair.purpose.letter

import com.umair.purpose.data.db.Letter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** UPDATE-12 audit: every conversation lands in exactly one letter; never two letters for the same days. */
class LetterPeriodTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private fun ms(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
    // Sunday 4 October 2026, written at 20:00.
    private val lastWeek = Letter(1, Letter.WEEKLY, "2026-09-28", "2026-10-04", createdAt = ms(4, 20, 0), title = "t", content = "c")
    private val nextWeek = LetterPeriod(Letter.WEEKLY, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11))

    @Test
    fun `a talk on Sunday night after the letter goes in next week's letter`() {
        assertTrue(LetterPlanning.belongs(nextWeek, LocalDate.of(2026, 10, 4), ms(4, 22, 30), listOf(lastWeek)))
    }

    @Test
    fun `a talk on Sunday before the letter stays in last week's only`() {
        assertFalse(LetterPlanning.belongs(nextWeek, LocalDate.of(2026, 10, 4), ms(4, 15, 0), listOf(lastWeek)))
        assertTrue(LetterPlanning.belongs(LetterPeriod(Letter.WEEKLY, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4)), LocalDate.of(2026, 10, 4), ms(4, 15, 0), emptyList()))
    }

    @Test
    fun `days inside the period always belong, others never`() {
        assertTrue(LetterPlanning.belongs(nextWeek, LocalDate.of(2026, 10, 7), ms(7, 1, 0), emptyList()))
        assertFalse(LetterPlanning.belongs(nextWeek, LocalDate.of(2026, 10, 2), ms(2, 22, 0), listOf(lastWeek)))
    }

    @Test
    fun `a letter overlapping any of the days counts as written`() {
        val early = Letter(2, Letter.WEEKLY, "2026-10-05", "2026-10-07", createdAt = ms(7, 15, 0), title = "t", content = "c")
        assertTrue(LetterPlanning.alreadyWritten(nextWeek, listOf(early)))
        assertFalse(LetterPlanning.alreadyWritten(nextWeek, listOf(lastWeek)))
        assertFalse(LetterPlanning.alreadyWritten(LetterPeriod(Letter.MONTHLY, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)), listOf(early)))
    }
}
