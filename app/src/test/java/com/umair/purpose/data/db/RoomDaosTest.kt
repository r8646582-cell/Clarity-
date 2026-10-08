package com.umair.purpose.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RoomDaosTest {

    private lateinit var database: PurposeDatabase
    private lateinit var sessionDao: SessionDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, PurposeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionDao = database.sessionDao()
    }

    @After
    fun closeDb() {
        database.close()
    }

    @Test
    fun insertAndRetrieveSession() = runBlocking {
        val session = Session(
            startedAt = 1700000000L,
            mode = "untangle"
        )
        val id = sessionDao.insert(session)

        val retrieved = sessionDao.get(id)
        assertNotNull(retrieved)
        assertEquals("untangle", retrieved?.mode)
        assertEquals(1700000000L, retrieved?.startedAt)
        assertNull(retrieved?.endedAt)
    }

    @Test
    fun openSessionRetrievesLatestActiveSession() = runBlocking {
        val activeSession = Session(
            startedAt = 1700001000L,
            endedAt = null,
            mode = "decision"
        )
        val closedSession = Session(
            startedAt = 1700000000L,
            endedAt = 1700000500L,
            mode = "untangle"
        )
        sessionDao.insert(closedSession)
        val activeId = sessionDao.insert(activeSession)

        val open = sessionDao.openSession()
        assertNotNull(open)
        assertEquals(activeId, open?.id)
        assertEquals("decision", open?.mode)
    }

    @Test
    fun observeSessionFlowUpdates() = runBlocking {
        val session = Session(
            startedAt = 1700002000L,
            summary = "Deep focus initial notes"
        )
        val id = sessionDao.insert(session)

        val observed = sessionDao.observe(id).first()
        assertNotNull(observed)
        assertEquals("Deep focus initial notes", observed?.summary)
    }

    private fun note(id: Long, type: String, text: String, status: String, lastSeen: Long) = Note(
        id = id, type = type, text = text, confidence = "likely",
        status = status, timesSeen = 1, firstSeen = 1L, lastSeen = lastSeen,
    )

    @Test
    fun noteSearchMatchesTextAndTypeButNotRetired() = runBlocking {
        val dao = database.noteDao()
        dao.upsert(
            listOf(
                note(1, "pattern", "He studies best at night", Note.ACTIVE, 100),
                note(2, "what_helps", "Walking clears his head", Note.ACTIVE, 200),
                note(3, "pattern", "Retired note about study", Note.RETIRED, 300),
            )
        )
        assertEquals(listOf(1L), dao.searchMemories("studies").map { it.id })
        assertEquals(listOf(2L), dao.searchMemories("what_helps").map { it.id })
        assertTrue(dao.searchMemories("Retired").isEmpty())
    }

    @Test
    fun recentMemoriesComeBackNewestFirst() = runBlocking {
        val dao = database.noteDao()
        dao.upsert(
            listOf(
                note(1, "pattern", "older", Note.ACTIVE, 10),
                note(2, "thread", "newer", Note.ACTIVE, 20),
            )
        )
        assertEquals(listOf(2L, 1L), dao.recentMemories(5).map { it.id })
    }
}
