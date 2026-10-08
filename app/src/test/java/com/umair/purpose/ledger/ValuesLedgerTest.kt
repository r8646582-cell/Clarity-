package com.umair.purpose.ledger

import com.umair.purpose.data.db.Promise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ValuesLedgerTest {
    private val zone: ZoneId = ZoneOffset.UTC
    // Wednesday 2026-09-23: this week is Mon 09-21 .. Sun 09-27.
    private val today = LocalDate.of(2026, 9, 23)

    private fun at(d: LocalDate) = d.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun promise(id: Long, status: String, resolved: LocalDate?, area: String? = null, text: String = "Do a thing", off: Boolean = false) =
        Promise(id = id, text = text, createdAt = 1, status = status, sourceSessionId = 1, resolvedAt = resolved?.let(::at), area = area, offTheRecord = off)

    @Test
    fun `counts kept and broken per week for the value's life area`() {
        val ps = listOf(
            promise(1, Promise.KEPT, today.minusDays(1), area = "health"),
            promise(2, Promise.BROKEN, today, area = "health"),
            promise(3, Promise.KEPT, today.minusWeeks(1), area = "health"),
            promise(4, Promise.KEPT, today.minusWeeks(1), area = "money"),
        )
        val row = ValuesLedger.compute(listOf("Health"), ps, today, zone).single()
        assertEquals(3, row.resolved)
        assertEquals(2, row.kept)
        assertEquals(66, row.rate)
        assertEquals(listOf(0, 0, 1, 1), row.weeks.map { it.kept })
        assertEquals(ValuesLedger.WEEKS, row.weeks.size)
        assertEquals(LocalDate.of(2026, 9, 21), row.weeks.last().start)
    }

    @Test
    fun `open promises and a conversation about a promise count for nothing`() {
        val ps = listOf(
            promise(1, Promise.OPEN, null, area = "health"),
            promise(2, Promise.DROPPED, today, area = "health"),
            promise(3, Promise.KEPT, today, area = "health"),
        )
        val row = ValuesLedger.compute(listOf("Health"), ps, today, zone).single()
        assertEquals(1, row.resolved)
        assertNull(row.rate)
        assertFalse(row.tracked)
    }

    @Test
    fun `off the record promises and anything after today are left out`() {
        val ps = listOf(
            promise(1, Promise.KEPT, today, area = "health", off = true),
            promise(2, Promise.KEPT, today.plusDays(1), area = "health"),
            promise(3, Promise.KEPT, today.minusDays(1), area = "health"),
            promise(4, Promise.BROKEN, today.minusDays(2), area = "health"),
        )
        val row = ValuesLedger.compute(listOf("Health"), ps, today, zone).single()
        assertEquals(2, row.resolved)
    }

    @Test
    fun `promises older than the window do not count`() {
        val ps = listOf(
            promise(1, Promise.BROKEN, today.minusWeeks(6), area = "health"),
            promise(2, Promise.KEPT, today, area = "health"),
            promise(3, Promise.KEPT, today, area = "health"),
        )
        val row = ValuesLedger.compute(listOf("Health"), ps, today, zone).single()
        assertEquals(100, row.rate)
    }

    @Test
    fun `a value is matched by its own words in the promise when no area fits`() {
        val ps = listOf(
            promise(1, Promise.KEPT, today, text = "Call Abbu about patience"),
            promise(2, Promise.BROKEN, today, text = "Practice patience at lunch"),
        )
        val row = ValuesLedger.compute(listOf("Patience with others"), ps, today, zone).single()
        assertEquals(2, row.resolved)
    }

    @Test
    fun `values with no promise record are named, not guessed`() {
        val rows = ValuesLedger.compute(listOf("Honesty", "Health"), emptyList(), today, zone)
        val lines = ValuesLedger.lines(rows)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("Honesty, Health"))
    }

    @Test
    fun `lines carry the computed numbers`() {
        val ps = (1..4).map { promise(it.toLong(), if (it <= 3) Promise.KEPT else Promise.BROKEN, today.minusDays(it.toLong() - 1), area = "health") }
        val lines = ValuesLedger.lines(ValuesLedger.compute(listOf("Health"), ps, today, zone))
        assertTrue(lines.any { it.contains("Health: kept 3 of 4 (75%)") })
    }

    @Test
    fun `no values means no lines`() {
        assertTrue(ValuesLedger.lines(ValuesLedger.compute(emptyList(), emptyList(), today, zone)).isEmpty())
    }

    @Test
    fun `duplicate and blank values collapse`() {
        assertEquals(1, ValuesLedger.compute(listOf("Health", " health ", ""), emptyList(), today, zone).size)
    }
}
