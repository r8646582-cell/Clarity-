package com.umair.purpose.chat

import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class HomeFactsTest {
    private fun promise(id: Long, text: String, due: String?, status: String = Promise.OPEN) =
        Promise(id = id, text = text, createdAt = 0, dueAt = due, status = status, sourceSessionId = null)

    @Test
    fun `coming up shows at most two promises due in the next 48 hours`() {
        val now = LocalDateTime.of(2026, 10, 4, 0, 40)
        val rows = HomeFacts.comingUp(listOf(
            promise(1, "Study 1 hour", "2026-10-05T08:00"),
            promise(2, "Gym", "2026-10-07T08:00"),
            promise(3, "Call Ammi", "2026-10-03T20:00"),
            promise(4, "Read", "2026-10-04"),
            promise(5, "Walk", "2026-10-05T07:00", status = Promise.KEPT),
            promise(6, "Pray on time", "2026-10-05T20:00"),
        ), now)
        assertEquals(listOf("Read, Sunday", "Study 1 hour, Monday 8:00am"), rows.map { it.title })
        assertEquals(listOf("today", "in 31 hours"), rows.map { it.meta })
    }

    @Test
    fun `pick up only a recent plain conversation`() {
        val zone = ZoneId.of("Asia/Karachi")
        val now = ZonedDateTime.of(2026, 10, 4, 9, 0, 0, 0, zone)
        val lastNight = ZonedDateTime.of(2026, 10, 3, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val talk = Conversation(Session(id = 3, startedAt = 0, title = "Planning the Monday start"), lastNight)
        assertEquals(PickUp(3, "Planning the Monday start", "last night"), HomeFacts.pickUp(listOf(talk), currentSessionId = 9, now = now))
        assertNull(HomeFacts.pickUp(listOf(talk), currentSessionId = 3, now = now))
        assertNull(HomeFacts.pickUp(listOf(talk), null, now.plusDays(4)))
        val journey = Conversation(Session(id = 4, startedAt = 0, title = "Day 3", mode = "journey"), lastNight)
        assertNull(HomeFacts.pickUp(listOf(journey), null, now))
    }
}
