package com.umair.purpose.journey

import com.umair.purpose.data.db.Journey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UPDATE-18: a journey adjusts after a missed or failed day, and never past its limits. */
class JourneyAdaptationTest {
    private val steps = (1..7).map { JourneyStep(it, "Theme $it", "Explore $it", "Action $it") }
    private val plan = JourneyPlan("Break the avoidance loop", "For when you know.", steps)
    /** Day 3 done, day 4 next. */
    private val j = Journey(id = 1, name = plan.name, startedAt = 0, currentDay = 4, totalDays = 7, status = Journey.ACTIVE)

    private fun run(r: AdaptResult, journey: Journey = j, s: List<JourneyStep> = steps, sessionDay: Int = 3) =
        JourneyAdaptation.apply(journey, s, 7, r, now = 99, sessionDay = sessionDay)

    @Test
    fun `parses decisions and rejects unknown ones`() {
        assertEquals("make_smaller", JourneyAdaptation.parse("""{"decision": "Make smaller", "reason": "r", "changes": []}""")!!.decision)
        assertNull(JourneyAdaptation.parse("""{"decision": "skip_everything"}"""))
        assertNull(JourneyAdaptation.parse("not json"))
    }

    @Test
    fun `continue changes nothing`() {
        assertFalse(run(AdaptResult("continue", "Going well.")).changed)
    }

    @Test
    fun `make smaller changes upcoming days, never past days or the review`() {
        val r = AdaptResult("make_smaller", "Twenty minutes felt like too much, so tomorrow is ten.", listOf(
            AdaptChange(2, "Old", "x", "x"), AdaptChange(4, "", "", "Ten minutes only"), AdaptChange(7, "No", "no", "no"),
        ))
        val out = run(r)
        assertTrue(out.changed)
        val s = JourneyAdaptation.steps(out.journey, plan)
        assertEquals("Theme 2", s[1].theme)
        assertEquals("Theme 4", s[3].theme)
        assertEquals("Ten minutes only", s[3].action)
        assertEquals("Theme 7", s[6].theme)
        assertEquals(7, out.journey.totalDays)
    }

    @Test
    fun `a failed day is repeated tomorrow, adjusted, and the journey grows by one`() {
        val out = run(AdaptResult("repeat_day", "Yesterday didn't land; let's try it smaller.", listOf(AdaptChange(4, "", "", "Two minutes of it"))))
        val s = JourneyAdaptation.steps(out.journey, plan)
        assertEquals(8, out.journey.totalDays)
        assertEquals("Theme 3", s[3].theme)
        assertEquals("Two minutes of it", s[3].action)
        assertEquals("Theme 4", s[4].theme)
        assertEquals((1..8).toList(), s.map { it.day })
        assertEquals("Theme 7", s.last().theme)
    }

    @Test
    fun `a step that didn't count is adjusted in place`() {
        // He barely talked in day 4's conversation, so day 4 is still next.
        val out = run(AdaptResult("repeat_day", "r", listOf(AdaptChange(4, "", "", "Smaller"))), sessionDay = 4)
        assertEquals(7, out.journey.totalDays)
        assertEquals("Smaller", JourneyAdaptation.steps(out.journey, plan)[3].action)
    }

    @Test
    fun `rest days, at most 3 extra days`() {
        var journey = j
        var s = steps
        repeat(3) {
            val out = run(AdaptResult("rest_day", "You're exhausted."), journey, s)
            journey = out.journey
            s = JourneyAdaptation.steps(journey, plan)
        }
        assertEquals(10, journey.totalDays)
        assertEquals("Rest", s[3].theme)
        // A fourth can't add a day: it takes the next day's place instead.
        val fourth = run(AdaptResult("rest_day", "Still tired."), journey, s)
        assertEquals(10, fourth.journey.totalDays)
        assertEquals("Theme 7", JourneyAdaptation.steps(fourth.journey, plan).last().theme)
    }

    @Test
    fun `pause and resume`() {
        val paused = run(AdaptResult("pause", "Exams this week.")).journey
        assertEquals(Journey.PAUSED, paused.status)
        assertEquals(99L, paused.pausedAt)
        // Nothing adapts a paused journey.
        assertFalse(run(AdaptResult("make_smaller", "r", listOf(AdaptChange(5, "a", "b", "c"))), paused).changed)
        val back = JourneyAdaptation.resume(paused)
        assertEquals(Journey.ACTIVE, back.status)
        assertEquals(4, back.currentDay)
        assertNull(back.pausedAt)
    }

    @Test
    fun `the written plan is never touched`() {
        run(AdaptResult("swap_step", "r", listOf(AdaptChange(5, "New", "n", "n"))))
        assertEquals("Theme 5", plan.step(5)!!.theme)
        assertEquals(steps, JourneyAdaptation.steps(j, plan))
    }
}
