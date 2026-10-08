package com.umair.purpose.promise

import com.umair.purpose.data.db.Promise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PromiseRulesTest {
    private val today = LocalDate.of(2026, 10, 3)
    private fun p(id: Long, due: String?, status: String = Promise.OPEN, created: Long = id) =
        Promise(id = id, text = "p$id", createdAt = created, dueAt = due, status = status, sourceSessionId = null)

    @Test
    fun `keep, break and drop close an open promise`() {
        assertEquals(Promise.KEPT to 5L, PromiseRules.keep(p(1, null), 5).let { it.status to it.resolvedAt })
        assertEquals(Promise.BROKEN, PromiseRules.breakPromise(p(1, null), 5).status)
        assertEquals(Promise.DROPPED, PromiseRules.drop(p(1, null), 5).status)
    }

    @Test(expected = IllegalStateException::class)
    fun `a closed promise cannot change again`() {
        PromiseRules.drop(p(1, null, Promise.KEPT), 5)
    }

    @Test(expected = IllegalStateException::class)
    fun `a closed promise cannot be edited`() {
        PromiseRules.edit(p(1, null, Promise.BROKEN), "x", null)
    }

    @Test
    fun `edit changes wording and date but not status`() {
        val e = PromiseRules.edit(p(1, "2026-10-01"), " Three chapters ", Due(LocalDate.of(2026, 10, 9), null))
        assertEquals("Three chapters", e.text)
        assertEquals("2026-10-09", e.dueAt)
        assertEquals(Promise.OPEN, e.status)
        assertNull(PromiseRules.edit(p(1, "2026-10-01"), "x", null).dueAt)
    }

    @Test
    fun `renegotiate closes old and opens new`() {
        val (old, new) = PromiseRules.renegotiate(p(1, "2026-10-01").copy(why = "test"), "Smaller", Due(LocalDate.of(2026, 10, 6), null), 50, 7)
        assertEquals(Promise.RENEGOTIATED, old.status)
        assertEquals(50L, old.resolvedAt)
        assertEquals(
            Promise(text = "Smaller", why = "test", createdAt = 50, dueAt = "2026-10-06", status = Promise.OPEN, sourceSessionId = 7),
            new,
        )
    }

    @Test
    fun `kept and broken record what happened and the lesson, and clear the reminder`() {
        val k = PromiseRules.keep(p(1, null).copy(remindAt = 99), 5, " phone in the other room ", "smaller start works")
        assertEquals("phone in the other room", k.whatHappened)
        assertEquals("smaller start works", k.lesson)
        assertNull(k.remindAt)
        val b = PromiseRules.breakPromise(p(2, null), 5, "", null)
        assertNull(b.whatHappened)
        assertNull(b.lesson)
    }

    @Test
    fun `due keeps an optional time`() {
        assertEquals(Due(LocalDate.of(2026, 10, 3), java.time.LocalTime.of(21, 30)), Due.parse("2026-10-03T21:30"))
        assertEquals("2026-10-03T21:30", Due.parse("2026-10-03T21:30").toString())
        assertEquals("2026-10-03", Due.parse("2026-10-03").toString())
        assertNull(Due.parse("garbage"))
        assertNull(Due.parse(null))
        assertEquals(1L, PromiseRules.dueForInjection(listOf(p(1, "2026-10-03T21:00")), today)!!.id)
    }

    @Test
    fun `injection picks the most overdue open promise`() {
        val list = listOf(
            p(1, "2026-10-03"), p(2, "2026-09-28"), p(3, "2026-09-28", created = 0),
            p(4, "2026-09-01", Promise.KEPT), p(5, null), p(6, "2026-10-10"), p(7, "garbage"),
        )
        assertEquals(3L, PromiseRules.dueForInjection(list, today)!!.id)
    }

    @Test
    fun `nothing to inject when nothing is due`() {
        assertNull(PromiseRules.dueForInjection(listOf(p(1, "2026-10-04"), p(2, null)), today))
    }

    @Test
    fun `overdue and counts`() {
        assertTrue(PromiseRules.isOverdue(p(1, "2026-10-02"), today))
        assertFalse(PromiseRules.isOverdue(p(1, "2026-10-03"), today))
        val c = PromiseRules.counts(listOf(p(1, null, Promise.KEPT), p(2, null, Promise.KEPT), p(3, null, Promise.BROKEN), p(4, null)))
        assertEquals(PromiseRules.Counts(kept = 2, broken = 1, open = 1, dropped = 0, renegotiated = 0), c)
    }
}
