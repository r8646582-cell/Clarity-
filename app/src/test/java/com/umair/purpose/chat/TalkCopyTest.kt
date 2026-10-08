package com.umair.purpose.chat

import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Session
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class TalkCopyTest {
    private val evening = LocalTime.of(21, 0)

    @Test
    fun `opening line is never AI-written, only the time band`() {
        assertEquals("Good evening. What's on your mind?", TalkCopy.opening(null, evening))
        assertEquals("Good morning. What's on your mind?", TalkCopy.opening(null, LocalTime.of(8, 0)))
        assertEquals("Still up, Umair? What's on your mind?", TalkCopy.opening(null, LocalTime.of(0, 17)))
    }

    @Test
    fun `modes and letters open in their own way`() {
        val letter = Session(id = 1, startedAt = 0, letterId = 4)
        assertEquals("What stayed with you from this one?", TalkCopy.opening(letter, evening))
        val journey = Session(id = 1, startedAt = 0, mode = "journey", modeDetail = "Break the avoidance loop", journeyDay = 3)
        assertEquals("Day 3 of Break the avoidance loop: Shrink the start. How did the last step go?", TalkCopy.opening(journey, evening, "Shrink the start"))
        assertEquals("Journey: Break the avoidance loop, day 3", TalkCopy.modeHeader(journey))
        val practice = Session(id = 1, startedAt = 0, mode = "practice", modeDetail = "Abbu")
        assertEquals("Practicing: talking with Abbu", TalkCopy.modeHeader(practice))
        assertEquals(null, TalkCopy.modeHeader(Session(id = 1, startedAt = 0)))
    }

    @Test
    fun `in-character lines run from I'm Abbu now until he says stop`() {
        fun m(id: Long, role: String, text: String) = Message(id, 1, role, text, id)
        val msgs = listOf(
            m(1, "assistant", "Tell me what he's like."),
            m(2, "user", "Strict but loving."),
            m(3, "assistant", "Okay, I'm Abbu now. Start whenever you're ready."),
            m(4, "user", "Abbu, I want to talk about my career."),
            m(5, "assistant", "Career? You're doing CA. What's there to talk about?"),
            m(6, "user", "I'm not sure it's right for me."),
            m(7, "assistant", "Not sure? After all we've spent?"),
            m(8, "user", "stop"),
            m(9, "assistant", "Let's step out. You did well when you named the feeling."),
        )
        assertEquals(setOf(5L, 7L), TalkCopy.inCharacter(msgs, "Abbu"))
        assertEquals(emptySet<Long>(), TalkCopy.inCharacter(msgs, null))
    }
}
