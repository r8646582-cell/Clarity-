package com.umair.purpose.journey

import com.umair.purpose.data.db.CustomJourney
import com.umair.purpose.data.db.Journey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class JourneyDesignTest {
    private val days = (1..7).joinToString(",") { """{"day":$it,"theme":"Theme $it","explore":"e$it","action":"a$it"}""" }
    private val custom = """Here: {"name":"Studying when home is noisy","description":"For when you can't focus at home","why":"You keep losing evenings to noise.","days":[$days]}"""
    private val catalog = listOf(
        JourneyPlan("Break the avoidance loop", "For when you avoid. 7 days.", (1..7).map { JourneyStep(it, "t$it", "e", "a") }),
        JourneyPlan("Calm under exam pressure", "For exams. 7 days.", (1..7).map { JourneyStep(it, "t$it", "e", "a") }),
        JourneyPlan("Discipline foundation", "For discipline. 14 days.", (1..14).map { JourneyStep(it, "t$it", "e", "a") }),
    )

    @Test
    fun `a custom journey parses into a 7-day preview and back`() {
        val d = JourneyDesign.parseCustom(custom)
        assertEquals("Studying when home is noisy", d.name)
        assertEquals(7, d.steps.size)
        assertEquals("Theme 7", d.steps.last().theme)
        val saved = CustomJourney(name = d.name, description = d.description, why = d.why, daysJson = JourneyDesign.encodeSteps(d.steps), createdAt = 1)
        val plan = JourneyDesign.plan(saved)!!
        assertTrue(plan.custom)
        assertEquals(7, plan.days)
        assertEquals("a3", plan.step(3)!!.action)
    }

    @Test
    fun `an incomplete custom journey is refused`() {
        try {
            JourneyDesign.parseCustom("""{"name":"x","days":[{"day":1,"theme":"t"}]}""")
            fail("should refuse")
        } catch (_: JourneyDesign.DesignException) {
        }
        assertEquals("Calm under exam pressure (2)", JourneyDesign.uniqueName("Calm under exam pressure", catalog.map { it.name }))
    }

    @Test
    fun `suggestions are at most two real journeys, never the active one`() {
        val raw = """{"suggested":[{"name":"break the avoidance loop","why":"You put things off."},{"name":"Made up"},{"name":"Calm under exam pressure"},{"name":"Discipline foundation"}]}"""
        val picks = JourneyDesign.parseSuggestions(raw, catalog, exclude = setOf("Calm under exam pressure"))
        assertEquals(listOf("Break the avoidance loop", "Discipline foundation"), picks.map { it.name })
        assertEquals("You put things off.", picks[0].why)
        assertEquals(picks, JourneyDesign.decodeSuggestions(JourneyDesign.encodeSuggestions(picks)))
        assertTrue(JourneyDesign.suggestionPrompt(catalog, "", emptyList(), "Break the avoidance loop").contains("natural next one"))
    }

    @Test
    fun `finishing the last step marks it done with a date, and groups show it once`() {
        val j = Journey(id = 1, name = "Break the avoidance loop", startedAt = 0, currentDay = 7, totalDays = 7, status = Journey.ACTIVE)
        val done = JourneyRules.advance(j, LocalDate.of(2026, 10, 3), now = 99)
        assertEquals(Journey.DONE, done.status)
        assertEquals(99L, done.completedAt)
        val again = done.copy(id = 2, completedAt = 50)
        val g = JourneyDesign.groups(catalog, listOf(done, again), listOf(Suggestion("Discipline foundation", "why")))
        assertEquals(1, g.completed.size)
        assertEquals(99L, g.completed[0].first.completedAt)
        assertEquals("Discipline foundation", g.suggested.single().first.name)
        assertEquals(3, g.all.size)
    }

    @Test
    fun `takeaway is the first sentence of the summary`() {
        assertEquals("You learned to start small.", JourneyDesign.takeaway("You learned to start small. Then more happened."))
        assertNull(JourneyDesign.takeaway("  "))
        assertTrue(JourneyDesign.takeaway("x".repeat(300))!!.length <= 180)
    }
    @Test fun `unique names keep working after 99 copies`() {
        val taken = listOf("Calm") + (2..99).map { "Calm ($it)" }
        assertEquals("Calm (100)", JourneyDesign.uniqueName("Calm", taken))
    }
}
