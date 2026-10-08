package com.umair.purpose.chat

import com.umair.purpose.data.db.Session
import com.umair.purpose.data.repo.SessionStart
import com.umair.purpose.data.repo.SessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA 10.4: "Talk about this letter" from inside a journey must open a new, normal conversation. */
class ActiveConversationTest {
    private class FakeStore : SessionStore {
        val sessions = mutableListOf<Session>()
        override suspend fun openSession() = sessions.lastOrNull { it.endedAt == null }
        override suspend fun startSession(start: SessionStart, now: Long): Session {
            endOpenSession(now)
            val s = Session(
                id = sessions.size + 1L, startedAt = now, mode = start.mode?.wire, modeDetail = start.modeDetail,
                journeyDay = start.journeyDay, letterId = start.letterId,
            )
            sessions += s
            return s
        }
        override suspend fun endOpenSession(now: Long) {
            val open = openSession() ?: return
            sessions[sessions.indexOf(open)] = open.copy(endedAt = now)
        }
    }

    private var cancels = 0
    private val store = FakeStore()
    private val active = ActiveConversation(store, cancelReply = { cancels++ }, clearPrefix = {})

    @Test
    fun `journey, then talk about a letter, then End, is back to normal Talk`() = runBlocking {
        val journey = active.start(SessionStart(Mode.JOURNEY, "Break the avoidance loop", 3), 1)
        // He opened the journey conversation from the drawer too, which used to stick.
        active.view(journey.id, openId = null)

        val letter = active.start(SessionStart(letterId = 42), 2)
        val open = store.openSession()!!
        assertEquals(letter.id, open.id)
        assertNotEquals(journey.id, open.id)
        assertNull(open.mode)
        assertEquals(42L, open.letterId)
        assertEquals(open.id, active.shown(open.id))
        assertEquals("What stayed with you from this one?", TalkCopy.opening(open, java.time.LocalTime.NOON))

        active.end(3)
        val after = store.openSession()
        assertNull(after)
        assertNull(active.shown(after?.id))
        assertNull(TalkCopy.modeHeader(after))
        assertTrue(cancels >= 3)
    }

    @Test
    fun `End while reading a past conversation goes back to the current one`() = runBlocking {
        val first = active.start(SessionStart(), 1)
        val second = active.start(SessionStart(Mode.DECISION), 2)
        active.view(first.id, openId = second.id)
        assertEquals(first.id, active.shown(second.id))
        active.end(3)
        assertEquals(second.id, active.shown(store.openSession()?.id))
        assertEquals(second.id, store.openSession()?.id)
    }

    @Test
    fun `New conversation always leaves the mode`() = runBlocking {
        val j = active.start(SessionStart(Mode.JOURNEY, "x", 1), 1)
        active.view(99, openId = j.id)
        active.newConversation(2)
        assertNull(store.openSession())
        assertNull(active.viewing.value)
    }
}
