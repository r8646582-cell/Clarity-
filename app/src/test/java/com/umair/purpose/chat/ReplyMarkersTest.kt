package com.umair.purpose.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ReplyMarkersTest {
    private val reply = "Good. One page, nine o'clock, phone in the other room.\n\n" +
        "[[promise: Read one page of FAR at 9pm with phone in another room | due: 2026-10-03T21:30 | " +
        "remind: 2026-10-03T21:00 | why: testing whether removing the phone beats the heavy feeling]]"

    @Test
    fun `nothing from the double bracket on is shown while streaming`() {
        assertEquals("Good.", ReplyMarkers.visibleWhileStreaming("Good.\n\n[[prom"))
        assertEquals("Good.", ReplyMarkers.visibleWhileStreaming("Good.\n\n["))
        assertEquals("Good.", ReplyMarkers.visibleWhileStreaming("Good.\n\n[[promise: x | due: none]]"))
        assertEquals("A [note] stays", ReplyMarkers.visibleWhileStreaming("A [note] stays"))
        // Every prefix of the real reply shows only the visible part.
        for (i in reply.indices) assertTrue(!ReplyMarkers.visibleWhileStreaming(reply.substring(0, i + 1)).contains("[["))
    }

    @Test
    fun `promise line is parsed and stripped`() {
        val p = ReplyMarkers.parse(reply)
        assertEquals("Good. One page, nine o'clock, phone in the other room.", p.visible)
        val line = p.promises.single()
        assertEquals("Read one page of FAR at 9pm with phone in another room", line.text)
        assertEquals("2026-10-03T21:30", line.due)
        assertEquals(LocalDateTime.of(2026, 10, 3, 21, 0), line.remindAt)
        assertEquals("testing whether removing the phone beats the heavy feeling", line.why)
        assertEquals(0, p.failures)
    }

    @Test
    fun `extra spaces, none and a plain date are tolerated`() {
        val p = ReplyMarkers.parse("Ok.\n[[ promise :  Walk 20 minutes  |due:2026-10-05| remind : none |why:  energy ]]").promises.single()
        assertEquals("Walk 20 minutes", p.text)
        assertEquals("2026-10-05", p.due)
        assertNull(p.remindAt)
        assertEquals("energy", p.why)
        val q = ReplyMarkers.parse("[[promise: Call Ami | due: none | remind: none | why: none]]").promises.single()
        assertNull(q.due)
        assertNull(q.why)
    }

    @Test
    fun `a broken line saves nothing and is still hidden`() {
        val p = ReplyMarkers.parse("Fine.\n[[promise: x | due: next Thursday | remind: none | why: y]]")
        assertTrue(p.promises.isEmpty())
        assertEquals(1, p.failures)
        assertEquals("Fine.", p.visible)
        assertEquals("Cut off", ReplyMarkers.parse("Cut off\n[[promise: x | due:").visible)
    }

    @Test
    fun `mode lines`() {
        val practice = ReplyMarkers.parse("Okay.\n[[mode: practice | with: Abbu]]")
        assertEquals(Mode.PRACTICE, practice.mode!!.mode)
        assertEquals("Abbu", practice.mode!!.with)
        assertEquals("Okay.", practice.visible)
        assertEquals(Mode.DECISION, ReplyMarkers.parse("[[mode: decision]]").mode!!.mode)
        assertEquals("Break the avoidance loop", ReplyMarkers.parse("[[mode: journey | name: Break the avoidance loop]]").mode!!.journeyName)
        // Practice without a usable name still switches; the coach asks who, and the header says so.
        assertNull(ReplyMarkers.parse("[[mode: practice]]").mode!!.with)
        assertNull(ReplyMarkers.parse("[[mode: practice | with: I need to practice talking to my dad about my career]]").mode!!.with)
        assertNull(ReplyMarkers.parse("[[mode: onboarding]]").mode)
        assertNull(ReplyMarkers.parse("[[mode: dance]]").mode)
    }

    @Test
    fun `promise and mode together`() {
        val p = ReplyMarkers.parse("Yes.\n[[promise: Talk to Abbu | due: 2026-10-10 | remind: none | why: courage]]\n[[mode: practice | with: Abbu]]")
        assertEquals(1, p.promises.size)
        assertEquals(Mode.PRACTICE, p.mode!!.mode)
        assertEquals("Yes.", p.visible)
    }

    @Test
    fun `practice_with is read, hidden, and only ever a short name`() {
        val p = ReplyMarkers.parse("Who is it?\n[[practice_with: Abbu]]")
        assertEquals("Abbu", p.practiceWith)
        assertEquals("Who is it?", p.visible)
        assertNull(ReplyMarkers.parse("ok [[practice_with: I need to practice a talk with him.]]").practiceWith)
        assertEquals("While streaming", ReplyMarkers.visibleWhileStreaming("While streaming\n[[practice_wi"))
    }

    @Test
    fun `practice header never shows his message`() {
        val raw = com.umair.purpose.data.db.Session(id = 1, startedAt = 0, mode = "practice", modeDetail = "I need to practice telling Abbu I want to switch majors")
        assertEquals("Practicing a conversation", TalkCopy.modeHeader(raw))
        assertEquals("Practicing: talking with Abbu", TalkCopy.modeHeader(raw.copy(modeDetail = "Abbu")))
        assertEquals(null, PracticeName.clean("a".repeat(31)))
        assertEquals("my teacher", PracticeName.clean(" \"my teacher\" "))
    }
    @Test
    fun `legacy dates reject timezone suffixes instead of silently dropping them`() {
        org.junit.Assert.assertNull(ReplyMarkers.normalizeDue("2026-10-06T02:30:00+05:00"))
        org.junit.Assert.assertNull(ReplyMarkers.parseDateTime("2026-10-06T02:30:00 trailing text"))
        org.junit.Assert.assertEquals("2026-10-06T02:30", ReplyMarkers.normalizeDue("2026-10-06T02:30:00"))
    }
    @Test
    fun `normal mode marker exits an exercise while unknown modes remain invalid`() {
        val out = ReplyMarkers.parse("Let's talk normally. [[mode: normal]]")
        org.junit.Assert.assertNotNull(out.mode)
        org.junit.Assert.assertNull(out.mode!!.mode)
        org.junit.Assert.assertTrue(out.hasActions)
        org.junit.Assert.assertEquals(0, out.failures)
        org.junit.Assert.assertEquals(1, ReplyMarkers.parse("[[mode: invented_mode]]").failures)
    }
}
