package com.umair.purpose.journey

import com.umair.purpose.data.db.Journey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class JourneysTest {
    /** Unit tests run from the app module directory. */
    private val real = File("src/main/assets/prompts/journeys.md").readText()

    @Test
    fun `parses the real journeys file`() {
        val plans = JourneyCatalog.parse(real)
        assertEquals(10, plans.size)
        val loop = plans.first()
        assertEquals("Break the avoidance loop", loop.name)
        assertEquals(7, loop.days)
        assertEquals("For when you know what to do and keep not doing it.", loop.blurb)
        assertEquals("Shrink the start", loop.step(3)!!.theme)
        assertTrue(loop.step(3)!!.action.startsWith("One if-then plan"))
        assertEquals(14, plans.first { it.name == "Discipline foundation" }.days)
        assertTrue(plans.all { p -> p.steps.map { it.day } == (1..p.days).toList() })
    }

    @Test
    fun `find tolerates case and partial names`() {
        val plans = JourneyCatalog.parse(real)
        assertEquals("Worth beyond output", JourneyCatalog.find(plans, "worth beyond output")!!.name)
        assertEquals("Calm under exam pressure", JourneyCatalog.find(plans, "Calm under exam")!!.name)
        assertNull(JourneyCatalog.find(plans, "Space travel"))
    }

    @Test
    fun `only a live run counts as active - stopped and done are archived`() {
        fun j(status: String) = Journey(name = "x", startedAt = 0, currentDay = 1, totalDays = 7, status = status)
        assertFalse(j(Journey.ACTIVE).isArchived)
        assertFalse(j(Journey.PAUSED).isArchived)
        assertTrue(j(Journey.STOPPED).isArchived)
        assertTrue(j(Journey.DONE).isArchived)
    }

    @Test
    fun `one step per calendar day`() {
        val today = LocalDate.of(2026, 10, 3)
        val j = Journey(1, "Break the avoidance loop", 0, currentDay = 1, totalDays = 7, status = Journey.ACTIVE)
        assertTrue(JourneyRules.stepAvailableToday(j, today))
        val after = JourneyRules.advance(j, today)
        assertEquals(2, after.currentDay)
        assertEquals("2026-10-03", after.lastStepDate)
        assertFalse(JourneyRules.stepAvailableToday(after, today))
        assertEquals(after, JourneyRules.advance(after, today))
        assertTrue(JourneyRules.stepAvailableToday(after, today.plusDays(1)))
        assertEquals(1, JourneyRules.daysDone(after))
    }

    @Test
    fun `the last step finishes the journey`() {
        val j = Journey(1, "x", 0, currentDay = 7, totalDays = 7, status = Journey.ACTIVE, lastStepDate = "2026-10-01")
        val done = JourneyRules.advance(j, LocalDate.of(2026, 10, 3))
        assertEquals(Journey.DONE, done.status)
        assertFalse(JourneyRules.stepAvailableToday(done, LocalDate.of(2026, 10, 4)))
    }
}

/** UPDATE-12 audit: a day advances once, only for the step the conversation was about, and resumes after a gap. */
class JourneyDayTest {
    private val j = Journey(1, "x", 0, currentDay = 3, totalDays = 7, status = Journey.ACTIVE, lastStepDate = "2026-10-01")
    private val today = LocalDate.of(2026, 10, 3)

    @Test
    fun `a conversation about day 3 never completes day 4`() {
        val after = JourneyRules.advance(j, today, forDay = 3)
        assertEquals(4, after.currentDay)
        // The same talk carries on past midnight: tomorrow's step is still to do.
        assertEquals(after, JourneyRules.advance(after, today.plusDays(1), forDay = 3))
    }

    @Test
    fun `two journey chats on one day advance it once`() {
        val once = JourneyRules.advance(j, today, forDay = 3)
        assertEquals(once, JourneyRules.advance(once, today, forDay = 4))
    }

    @Test
    fun `missing several days resumes at the next step, never resets or skips`() {
        val later = JourneyRules.advance(j, today.plusDays(5), forDay = 3)
        assertEquals(4, later.currentDay)
        assertEquals(Journey.ACTIVE, later.status)
    }

    @Test
    fun `finishing the last day marks it done exactly once`() {
        val last = j.copy(currentDay = 7)
        val done = JourneyRules.advance(last, today, now = 50, forDay = 7)
        assertEquals(Journey.DONE, done.status)
        assertEquals(50L, done.completedAt)
        assertEquals(done, JourneyRules.advance(done, today.plusDays(1), now = 99, forDay = 8))
    }
}
