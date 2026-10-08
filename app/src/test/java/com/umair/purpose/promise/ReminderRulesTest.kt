package com.umair.purpose.promise

import com.umair.purpose.data.db.Promise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** UPDATE-12 audit: reminders follow the promise. */
class ReminderRulesTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private fun ms(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun p(due: String?, remind: Long?) =
        Promise(id = 1, text = "Read", createdAt = 0, dueAt = due, remindAt = remind, status = Promise.OPEN, sourceSessionId = 1)

    @Test
    fun `editing the due time moves the reminder with it`() {
        val e = PromiseRules.edit(p("2026-10-03T21:30", ms(3, 21, 0)), "Read", Due(LocalDate.of(2026, 10, 3), LocalTime.of(22, 30)), zone)
        assertEquals(ms(3, 22, 0), e.remindAt)
    }

    @Test
    fun `moving the due date moves the reminder by whole days`() {
        val e = PromiseRules.edit(p("2026-10-03", ms(3, 9, 0)), "Read", Due(LocalDate.of(2026, 10, 5), null), zone)
        assertEquals(ms(5, 9, 0), e.remindAt)
    }

    @Test
    fun `no reminder stays none, and a wording edit leaves it alone`() {
        assertNull(PromiseRules.edit(p("2026-10-03", null), "Read", Due(LocalDate.of(2026, 10, 5), null), zone).remindAt)
        assertEquals(ms(3, 21, 0), PromiseRules.edit(p("2026-10-03T21:30", ms(3, 21, 0)), "Read two pages", Due(LocalDate.of(2026, 10, 3), LocalTime.of(21, 30)), zone).remindAt)
    }

    @Test
    fun `a reminder already past when agreed goes off now, never dropped`() {
        val now = ms(3, 21, 5)
        assertEquals(now + PromiseRules.FIRE_NOW_DELAY_MS, PromiseRules.reminderTime(ms(3, 21, 0), now, justAgreed = true))
        assertEquals(ms(3, 22, 0), PromiseRules.reminderTime(ms(3, 22, 0), now, justAgreed = true))
    }

    @Test
    fun `one missed while the phone was off goes off now if recent, and is stale if not`() {
        val now = ms(4, 8, 0)
        assertEquals(now + PromiseRules.FIRE_NOW_DELAY_MS, PromiseRules.reminderTime(ms(4, 7, 0), now, justAgreed = false))
        assertNull(PromiseRules.reminderTime(ms(3, 21, 0), now, justAgreed = false))
    }

    @Test fun `setting a clock on a date only promise never shifts from invented noon`() {
        val edited = PromiseRules.edit(p("2026-10-03", ms(3, 1, 0)), "Sleep", Due(LocalDate.of(2026, 10, 3), LocalTime.of(2, 30)), zone)
        assertEquals(ms(3, 2, 30), edited.remindAt)
    }
}
