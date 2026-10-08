package com.umair.purpose.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** UPDATE-12 audit: every hidden line is read however it's written, and never shown. */
class HiddenLinesTest {
    private val today = LocalDate.of(2026, 10, 3)

    @Test
    fun `step_done is read and hidden`() {
        val p = ReplyMarkers.parse("You keep carrying it alone.\n\n[[step_done: story]]", today)
        assertEquals(listOf("story"), p.stepsDone)
        assertEquals("You keep carrying it alone.", p.visible)
        assertEquals(0, p.failures)
    }

    @Test
    fun `step_done tolerates case, spaces, hyphens and a line break`() {
        assertEquals(listOf("future_self"), ReplyMarkers.parse("Ok.\n[[ Step_Done :  Future self ]]", today).stepsDone)
        assertEquals(listOf("how_you_work"), ReplyMarkers.parse("Ok.\n[[step-done:\nhow-you-work]]", today).stepsDone)
        assertEquals(listOf("people"), ReplyMarkers.parse("Ok.\n[[STEP DONE: People.]]", today).stepsDone)
    }

    @Test
    fun `an unknown step is hidden and counted, not marked`() {
        val p = ReplyMarkers.parse("Thanks.\n[[step_done: childhood]]", today)
        assertTrue(p.stepsDone.isEmpty())
        assertEquals(1, p.failures)
        assertEquals("Thanks.", p.visible)
    }

    @Test
    fun `several hidden lines in one reply are all handled`() {
        val reply = "Two things, then.\n" +
            "[[promise: Call Ami tonight | due: 2026-10-03T21:00 | remind: none | why: closeness]]\n" +
            "[[promise: Walk 20 minutes | due: 2026-10-04 | remind: 2026-10-04T07:00 | why: energy]]\n" +
            "[[step_done: people]]"
        val p = ReplyMarkers.parse(reply, today)
        assertEquals(listOf("Call Ami tonight", "Walk 20 minutes"), p.promises.map { it.text })
        assertEquals(listOf("people"), p.stepsDone)
        assertEquals("Two things, then.", p.visible)
        assertEquals(0, p.failures)
    }

    @Test
    fun `a line break inside a promise line is just a space`() {
        val p = ReplyMarkers.parse("Good.\n[[promise: Read one page\nof FAR | due:\n2026-10-03T21:30 | remind: none | why: start small]]", today)
        assertEquals("Read one page of FAR", p.promises.single().text)
        assertEquals("2026-10-03T21:30", p.promises.single().due)
    }

    @Test
    fun `upper-case kinds and keys are read`() {
        val p = ReplyMarkers.parse("Ok.\n[[PROMISE: Sleep by 12 | Due: 2026-10-03 | Remind: none | Why: rest]]", today)
        assertEquals("Sleep by 12", p.promises.single().text)
        assertEquals("2026-10-03", p.promises.single().due)
        assertEquals(Mode.DECISION, ReplyMarkers.parse("[[Mode: Decision]]", today).mode!!.mode)
    }

    @Test
    fun `today, tonight and tomorrow resolve against the reply's own day`() {
        fun due(word: String, day: LocalDate) =
            ReplyMarkers.parse("[[promise: x | due: $word | remind: none | why: y]]", day).promises.single().due
        assertEquals("2026-10-03", due("tonight", today))
        assertEquals("2026-10-03", due("today", today))
        assertEquals("2026-10-04", due("tomorrow", today))
        // Just after midnight, "tomorrow" is the day after the new day.
        assertEquals("2026-10-05", due("tomorrow", today.plusDays(1)))
    }

    @Test
    fun `malformed and unknown lines are hidden and counted`() {
        val p = ReplyMarkers.parse("Fine.\n[[practice_with: I think it should be my father, honestly.]]\n[[nonsense]]\n[[weather: sunny]]", today)
        assertNull(p.practiceWith)
        assertEquals(3, p.failures)
        assertEquals("Fine.", p.visible)
    }

    @Test
    fun `no part of a step_done line ever shows while streaming`() {
        val reply = "Thank you for telling me all that.\n\n[[step_done: story]]"
        for (i in reply.indices) {
            val shown = ReplyMarkers.visibleWhileStreaming(reply.substring(0, i + 1))
            assertFalse(shown, shown.contains("[") || shown.contains("step_done"))
        }
    }
}
