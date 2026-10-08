package com.umair.purpose.chat

import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ConversationsTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 3)
    private fun at(d: LocalDate) = d.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun conv(id: Long, d: LocalDate) = Conversation(Session(id = id, startedAt = at(d)), at(d))

    @Test
    fun titlesFromTheFirstWords() {
        assertEquals("I keep avoiding FAR again and…", Conversations.titleFrom("I keep avoiding FAR again and again tonight"))
        assertEquals("hey", Conversations.titleFrom("  hey "))
        assertNull(Conversations.titleFrom("   "))
    }

    @Test
    fun groupsByRecency() {
        val g = Conversations.groups(
            listOf(
                conv(1, today), conv(2, today.minusDays(1)), conv(3, today.minusDays(5)), conv(4, today.minusDays(20)),
                conv(5, LocalDate.of(2026, 7, 9)), conv(6, LocalDate.of(2025, 9, 1)),
            ),
            today, zone,
        )
        assertEquals(listOf("Today", "Yesterday", "Previous 7 days", "Previous 30 days", "July", "September 2025"), g.map { it.first })
    }

    @Test
    fun markerGoesAfterTheLastReflectedMessage() {
        val msgs = (1L..4L).map { Message(id = it, sessionId = 1, role = if (it % 2 == 1L) "user" else "assistant", content = "m$it", createdAt = it) }
        val t = Conversations.transcriptWithMarker(msgs, 2) { it.content }
        assertEquals("m1\n\nm2\n\n${Conversations.ALREADY_REFLECTED}\n\nm3\n\nm4", t)
        // First reflection, or nothing new: no marker.
        assertEquals("m1\n\nm2\n\nm3\n\nm4", Conversations.transcriptWithMarker(msgs, null) { it.content })
        assertEquals("m1\n\nm2\n\nm3\n\nm4", Conversations.transcriptWithMarker(msgs, 4) { it.content })
    }

    @Test
    fun modeLabels() {
        assertEquals("Journey, day 3", Conversations.modeLabel(Session(startedAt = 0, mode = "journey", journeyDay = 3)))
        assertNull(Conversations.modeLabel(Session(startedAt = 0)))
    }
}
