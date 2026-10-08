package com.umair.purpose.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.umair.purpose.chat.ActionExecutor
import com.umair.purpose.chat.ToolCall
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Person
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.Session
import com.umair.purpose.testutil.smartMock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DataIntegrityTest {
    private lateinit var db: PurposeDatabase
    private lateinit var memory: MemoryRepository
    private lateinit var promises: PromiseRepository
    private val zone = ZoneId.of("UTC")

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PurposeDatabase::class.java)
            .allowMainThreadQueries().build()
        memory = MemoryRepository(db, smartMock(), smartMock(), smartMock(), com.umair.purpose.memory.SearchIndex(db))
        promises = PromiseRepository(db, smartMock())
    }

    @After fun close() { db.close() }

    @Test fun `journey action receipts reflect persisted advancement and reject replays`() = runBlocking {
        val journeys = JourneyRepository(db, smartMock(), smartMock())
        val executor = ActionExecutor(journeys, promises, memory, smartMock(), smartMock(), smartMock())
        val now = java.time.ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val session = Session(id = 1, startedAt = now, mode = "journey", modeDetail = "Test journey", journeyDay = 1)
        val action = listOf(ToolCall(action = "advance_journey"))
        val missing = executor.execute(action, session, 1, zone, now).single()
        assertFalse(missing.ok)
        assertEquals("not_advanced", missing.detail)
        val id = db.journeyDao().insert(com.umair.purpose.data.db.Journey(
            name = "Test journey", startedAt = now, currentDay = 1, totalDays = 7,
            status = com.umair.purpose.data.db.Journey.ACTIVE,
        ))
        assertTrue(executor.execute(action, session, 1, zone, now).single().ok)
        val saved = db.journeyDao().get(id)!!
        assertEquals(2, saved.currentDay)
        val replay = executor.execute(action, session, 2, zone, now).single()
        assertFalse(replay.ok)
        assertEquals("not_advanced", replay.detail)
        assertEquals(saved, db.journeyDao().get(id))
        val nextDay = now + 24 * 60 * 60_000L
        assertFalse(executor.execute(action, session, 3, zone, nextDay).single().ok)
        assertFalse(executor.execute(action, session.copy(modeDetail = "Other journey", journeyDay = 2), 4, zone, nextDay).single().ok)
        assertEquals(saved, db.journeyDao().get(id))
    }

    private fun note(id: Long, text: String = "Walking clears your head", status: String = Note.ACTIVE, edited: Boolean = false, confidence: String = "guess", source: String = "1") =
        Note(id, "what_helps", text, confidence, status, 1, 10, 20, edited, source)

    @Test fun `valence updates the inserted row instead of creating another note`() = runBlocking {
        val saved = memory.insertMemory("Walking clears your head", "what_helps", 1, 1, 100)!!
        assertTrue(saved.id > 0)
        assertEquals(listOf(saved), db.noteDao().all())
    }

    @Test fun `tool memory cannot recreate a deleted note`() = runBlocking {
        db.noteDao().upsert(listOf(note(1, status = Note.DELETED_BY_USER)))
        assertNull(memory.insertMemory("Walking clears your head", "what_helps", 1, 2, 100))
        assertEquals(Note.DELETED_BY_USER, db.noteDao().all().single().status)
    }

    @Test fun `valence cannot alter a user edited or confirmed note`() = runBlocking {
        for (protected in listOf(note(1, edited = true), note(1, confidence = "confirmed"))) {
            db.noteDao().upsert(listOf(protected))
            memory.insertMemory(protected.text, "what_helps", -1, 2, 100)
            assertEquals(protected, db.noteDao().get(1))
        }
    }

    @Test fun `replacement ids do not retire the saved or protected notes`() = runBlocking {
        val rows = listOf(note(1), note(2, "Your own wording", edited = true), note(3, "An agreed pattern", confidence = "confirmed"))
        db.noteDao().upsert(rows)
        memory.synthesizeMemory(rows[0].text, "what_helps", listOf(1, 2, 3), 2, 100)
        assertTrue(db.noteDao().all().all { it.status == Note.ACTIVE })
    }

    @Test fun `repeated model insights never reinforce themselves`() = runBlocking {
        repeat(3) { memory.insertMemory("Walking clears your head", "what_helps", null, 1, 100) }
        val only = db.noteDao().all().single()
        assertEquals("guess", only.confidence)
        assertEquals(1, only.timesSeen)
    }

    @Test fun `nightly cleanup preserves edited people and merged provenance`() = runBlocking {
        val edited = Person(1, "Ali", "friend", "My own description", 10, editedByUser = true, sourceSessionIds = "1")
        val other = Person(2, "ali", "friend", "More detail", 20, sourceSessionIds = "2")
        db.personDao().upsert(listOf(edited, other))
        memory.deduplicate(100)
        assertEquals(edited, db.personDao().get(1))
        assertEquals(other, db.personDao().get(2))
        db.personDao().upsert(listOf(edited.copy(editedByUser = false)))
        memory.deduplicate(100)
        val merged = db.personDao().all().single()
        assertEquals(setOf(1L, 2L), com.umair.purpose.memory.Provenance.parse(merged.sourceSessionIds))
        assertTrue(merged.notes!!.contains("My own description"))
        assertTrue(merged.notes!!.contains("More detail"))
    }

    @Test fun `duplicate notes preserve sightings and sources`() = runBlocking {
        db.noteDao().upsert(listOf(note(1), note(2, source = "2")))
        memory.deduplicate(100)
        val active = db.noteDao().all().single { it.status == Note.ACTIVE }
        assertEquals(2, active.timesSeen)
        assertEquals(setOf(1L, 2L), com.umair.purpose.memory.Provenance.parse(active.sourceSessionIds))
    }

    @Test fun `invalid ids and unmatched titles never target the only open promise`() = runBlocking {
        val p = Promise(id = 1, text = "Read the chapter", createdAt = 10, status = Promise.OPEN, sourceSessionId = null)
        db.promiseDao().insert(p)
        assertNull(promises.resolveFromTool(99, p.text, "KEPT", 100))
        assertNull(promises.resolveFromTool(null, "Call your mother", "KEPT", 100))
        assertNull(promises.resolveFromTool(0, p.text, "KEPT", 100))
        assertEquals(Promise.OPEN, db.promiseDao().get(1)!!.status)
        assertNotNull(promises.resolveFromTool(null, p.text, "KEPT", 100))
    }

    @Test fun `off record tools cannot alter saved promises or journeys`() = runBlocking {
        val executor = ActionExecutor(smartMock(), promises, memory, smartMock(), smartMock(), smartMock())
        val p = Promise(id = 1, text = "Read the chapter", createdAt = 10, status = Promise.OPEN, sourceSessionId = null)
        db.promiseDao().insert(p)
        val actions = listOf(
            ToolCall(action = "resolve_promise", promiseId = 1, status = "KEPT"),
            ToolCall(action = "edit_promise", promiseId = 1, newTitle = "Altered"),
            ToolCall(action = "reschedule_promise", promiseId = 1, newDueEpochMs = 100000),
            ToolCall(action = "archive_journey", journeyId = 1),
        )
        val receipts = executor.execute(actions, Session(id = -1, startedAt = 10), -2, zone, 100)
        assertTrue(receipts.all { !it.ok && it.detail == "off_the_record" })
        assertEquals(p, db.promiseDao().get(1))
    }
    @Test fun `restore drops cached prompt overrides and obsolete alarms`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prompts = PromptRepository(context, db, smartMock())
        db.promptDao().upsert(com.umair.purpose.data.db.PromptOverride("persona.md", "old", 1))
        assertEquals("old", prompts.load("persona.md"))
        val scheduler = smartMock<com.umair.purpose.promise.ReminderScheduler>()
        val promiseRepo = PromiseRepository(db, scheduler)
        db.promiseDao().insert(Promise(id = 1, text = "Old reminder", createdAt = 1, status = Promise.OPEN, remindAt = 100, sourceSessionId = null))
        val backup = com.umair.purpose.backup.BackupRepository(context, db, smartMock(), promiseRepo, smartMock(), prompts, com.umair.purpose.chat.ActionReceiptStore())
        backup.restore(com.umair.purpose.backup.BackupData(
            exportedAt = 1, sessions = emptyList(), messages = emptyList(), profile = emptyList(),
            people = emptyList(), notes = emptyList(), promises = emptyList(), areas = emptyList(), usage = emptyList(),
            promptOverrides = listOf(com.umair.purpose.data.db.PromptOverride("persona.md", "restored", 2)),
        ))
        assertEquals("restored", prompts.load("persona.md"))
        org.mockito.kotlin.verify(scheduler).cancel(1)
    }

    @Test fun `concurrent settings updates preserve every change`() = runBlocking {
        val settings = SettingsRepository(db)
        kotlinx.coroutines.coroutineScope {
            repeat(40) {
                launch(kotlinx.coroutines.Dispatchers.Default) {
                    settings.update { it.copy(monthlyBudget = it.monthlyBudget + 1) }
                }
            }
        }
        assertEquals(45.0, settings.get().monthlyBudget, 0.0)
    }

    @Test fun `invalid restore preserves existing rows and valid restore settles partial replies`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prompts = PromptRepository(context, db, smartMock())
        val backup = com.umair.purpose.backup.BackupRepository(context, db, smartMock(), promises, smartMock(), prompts, com.umair.purpose.chat.ActionReceiptStore())
        db.noteDao().upsert(listOf(note(1)))
        val data = com.umair.purpose.backup.BackupData(exportedAt = 1, sessions = listOf(Session(id = 1, startedAt = 1)),
            messages = listOf(com.umair.purpose.data.db.Message(id = 1, sessionId = 1, role = "assistant", content = "partial", createdAt = 1,
                status = com.umair.purpose.data.db.Message.STREAMING)),
            profile = emptyList(), people = emptyList(), notes = emptyList(), promises = emptyList(), areas = emptyList(), usage = emptyList())
        try {
            backup.restore(data.copy(sessions = emptyList()))
            fail("Invalid restore must fail")
        } catch (_: IllegalArgumentException) { }
        assertEquals(listOf(note(1)), db.noteDao().all())
        backup.restore(data)
        assertEquals(com.umair.purpose.data.db.Message.INTERRUPTED, db.messageDao().get(1)!!.status)
        assertTrue(db.noteDao().all().isEmpty())
    }

    @Test fun `restore waits for an old writers cancellation cleanup before replacing reused ids`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prompts = PromptRepository(context, db, smartMock())
        val backup = com.umair.purpose.backup.BackupRepository(context, db, smartMock(), promises, smartMock(), prompts, com.umair.purpose.chat.ActionReceiptStore())
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val writer = launch {
            db.datasetWork.withWriter {
                ready.complete(Unit)
                try { kotlinx.coroutines.awaitCancellation() } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { db.noteDao().upsert(listOf(note(1, text = "old cleanup"))) }
                }
            }
        }
        ready.await()
        backup.restore(com.umair.purpose.backup.BackupData(exportedAt = 1, sessions = emptyList(), messages = emptyList(), profile = emptyList(),
            people = emptyList(), notes = listOf(note(1, text = "restored wording")), promises = emptyList(), areas = emptyList(), usage = emptyList()))
        writer.join()
        assertEquals("restored wording", db.noteDao().get(1)!!.text)
    }

    @Test fun `deleting archived memory removes searchable text and undo restores it`() = runBlocking {
        val archived = note(1, text = "zebrapath private memory", status = Note.RETIRED)
        db.noteDao().upsert(listOf(archived))
        val search = com.umair.purpose.memory.SearchIndex(db)
        search.indexArchive()
        assertEquals(1, db.searchDao().search("zebrapath*", 10).size)
        memory.deleteNote(1)
        assertTrue(db.searchDao().search("zebrapath*", 10).isEmpty())
        memory.restoreNote(archived)
        assertEquals(1, db.searchDao().search("zebrapath*", 10).size)
        memory.editNote(1, "replacement memory")
        assertTrue(db.searchDao().search("zebrapath*", 10).isEmpty())
    }

    @Test fun `letter deletion removes its search document`() = runBlocking {
        val search = com.umair.purpose.memory.SearchIndex(db)
        db.searchDao().insert(listOf(com.umair.purpose.data.db.SearchDoc(
            kind = com.umair.purpose.memory.SearchDocs.LETTER, refId = "7", day = "2026-01-01", text = "zebrapath letter")))
        LetterRepository(db, search).delete(7)
        assertTrue(db.searchDao().search("zebrapath*", 10).isEmpty())
    }

    @Test fun `reflection arriving after forgetting cannot recreate memory`() = runBlocking {
        val result = com.umair.purpose.memory.ReflectionResult("summary", notes =
            com.umair.purpose.memory.ReflectionResult.NoteChanges(add = listOf(
                com.umair.purpose.memory.ReflectionResult.NewNote("pattern", "late arrival"))))
        assertNull(memory.applyReflection(999, result, 100, null))
        assertTrue(db.noteDao().all().isEmpty())
    }

    @Test fun `chat tool keeps due clock and a local reschedule uses Karachi time`() = runBlocking {
        val localZone = ZoneId.of("Asia/Karachi")
        val now = java.time.ZonedDateTime.of(2026, 10, 6, 2, 19, 0, 0, localZone).toInstant().toEpochMilli()
        val executor = ActionExecutor(smartMock(), promises, memory, smartMock(), smartMock(), smartMock())
        val session = Session(id = 1, startedAt = now)
        db.sessionDao().insert(session)
        val first = executor.execute(listOf(ToolCall(action = "record_promise", title = "Sleep", due = "today 02:20", remind = "today 02:20")), session, 1, localZone, now)
        assertTrue(first.single().ok)
        assertTrue(first.single().wantsReminder)
        val p = promises.open().single()
        assertEquals("2026-10-06T02:20", p.dueAt)
        val moved = executor.execute(listOf(ToolCall(action = "reschedule_promise", promiseId = p.id, newDue = "today 02:30")), session, 1, localZone, now)
        assertTrue(moved.single().ok)
        val saved = db.promiseDao().get(p.id)!!
        assertEquals("2026-10-06T02:30", saved.dueAt)
        assertEquals(java.time.Instant.ofEpochMilli(now).atZone(localZone).withMinute(30).toInstant().toEpochMilli(), saved.remindAt)
        val invalid = executor.execute(listOf(ToolCall(action = "edit_promise", promiseId = p.id, newTitle = "Wrong change", newDue = "invalid")), session, 1, localZone, now)
        assertFalse(invalid.single().ok)
        assertEquals(saved, db.promiseDao().get(p.id))
    }

    @Test fun `ambiguous title cannot select an arbitrary promise`() = runBlocking {
        db.promiseDao().insert(Promise(text = "Read FAR chapter", createdAt = 1, status = Promise.OPEN, sourceSessionId = null))
        db.promiseDao().insert(Promise(text = "Read tax chapter", createdAt = 2, status = Promise.OPEN, sourceSessionId = null))
        assertNull(promises.editFromTool(null, "Read", "Wrong", null, zone))
        assertEquals(2, promises.open().size)
    }

    @Test fun `saving an unchanged promise again succeeds without another row or source reassignment`() = runBlocking {
        val line = com.umair.purpose.chat.ReplyMarkers.PromiseLine("Read FAR", "2026-10-06T08:00", null, null)
        val first = promises.saveFromChat(line, 1, 10, zone, 1)!!
        val second = promises.saveFromChat(line, 1, 20, zone, 2)!!
        assertEquals(first, second)
        assertEquals(10L, second.sourceMessageId)
        assertEquals(1, promises.open().size)
    }

    @Test fun `repeating a chat promise with a new due time moves its existing reminder`() = runBlocking {
        val localZone = ZoneId.of("Asia/Karachi")
        val now = java.time.ZonedDateTime.of(2026, 10, 6, 2, 0, 0, 0, localZone).toInstant().toEpochMilli()
        val before = promises.saveFromChat(com.umair.purpose.chat.ReplyMarkers.PromiseLine(
            "Sleep", "2026-10-06T02:20", java.time.LocalDateTime.of(2026, 10, 6, 2, 20), null), null, null, localZone, now)!!
        val after = promises.saveFromChat(com.umair.purpose.chat.ReplyMarkers.PromiseLine(
            "Sleep", "2026-10-06T02:30", null, null), null, null, localZone, now)!!
        assertEquals(before.id, after.id)
        assertEquals(before.remindAt!! + 10 * 60_000L, after.remindAt)
        assertEquals(1, promises.open().size)
    }
    @Test fun `synthesized replacements retain every known source and index the archive`() = runBlocking {
        db.noteDao().upsert(listOf(note(1, "zebrapath walking", source = "1"), note(2, "Exercise supports calm", source = "2")))
        val saved = memory.synthesizeMemory("Movement helps you regulate stress", "what_helps", listOf(1, 2, 2), 3, 100)!!
        assertEquals(setOf(1L, 2L, 3L), com.umair.purpose.memory.Provenance.parse(saved.sourceSessionIds))
        assertEquals(10L, saved.firstSeen)
        assertEquals(2, db.noteDao().all().count { it.status == Note.RETIRED })
        assertEquals(1, db.searchDao().search("zebrapath*", 10).size)
        val plan = com.umair.purpose.memory.ForgetPlanner.plan(3, db.noteDao().all(), emptyList(), emptyList())
        assertFalse(saved.id in plan.deleteNoteIds)
    }

    @Test fun `retrieval uses multiple terms and never falls back to unrelated memory`() = runBlocking {
        db.noteDao().upsert(listOf(note(1, "Walking with Abbu clears your head"), note(2, "Acne affects confidence")))
        assertTrue(memory.relevantInsights("Complicated thoughts about Abbu walking")!!.contains("Walking with Abbu"))
        assertNull(memory.relevantInsights("Astronomy nebulae"))
        assertNull(memory.relevantInsights("okay yeah"))
    }

    @Test fun `a replayed reflection does not reinforce memory or move its cursor backwards`() = runBlocking {
        db.sessionDao().insert(Session(id = 1, startedAt = 1, endedAt = 2))
        val result = com.umair.purpose.memory.ReflectionResult("summary", notes =
            com.umair.purpose.memory.ReflectionResult.NoteChanges(add = listOf(
                com.umair.purpose.memory.ReflectionResult.NewNote("pattern", "Walking clears your head"))))
        assertNotNull(memory.applyReflection(1, result, 100, 10))
        val saved = db.noteDao().all()
        assertNull(memory.applyReflection(1, result, 101, 10))
        assertNull(memory.applyReflection(1, result, 102, 9))
        assertEquals(saved, db.noteDao().all())
        assertEquals(10L, db.sessionDao().get(1)!!.reflectedUpToMessageId)
    }

    @Test fun `off the record cannot be reflected even through direct repository invocation`() = runBlocking {
        db.sessionDao().insert(Session(id = -1, startedAt = 1, endedAt = 2))
        assertNull(memory.applyReflection(-1, com.umair.purpose.memory.ReflectionResult("private"), 100, 10))
        assertFalse(db.sessionDao().get(-1)!!.reflected)
    }

    @Test fun `editing or deleting memory invalidates its cached chat prefix before returning`() = runBlocking {
        val cache = com.umair.purpose.chat.ChatPrefixCache()
        val repo = MemoryRepository(db, smartMock(), smartMock(), smartMock(), com.umair.purpose.memory.SearchIndex(db), cache)
        db.noteDao().upsert(listOf(note(1)))
        suspend fun cached() = cache.getOrBuild(1) {
            com.umair.purpose.chat.ChatPromptBuilder.StablePrefix("persona", contextBlock = db.noteDao().get(1)!!.text)
        }.contextBlock
        assertEquals("Walking clears your head", cached())
        repo.editNote(1, "Your corrected wording")
        assertEquals("Your corrected wording", cached())
        repo.deleteNote(1)
        var rebuilt = false
        cache.getOrBuild(1) { rebuilt = true; com.umair.purpose.chat.ChatPromptBuilder.StablePrefix("fresh") }
        assertTrue(rebuilt)
    }

    @Test fun `duplicate actions in one reply execute only once`() = runBlocking {
        val executor = ActionExecutor(smartMock(), promises, memory, smartMock(), smartMock(), smartMock())
        val call = ToolCall(action = "store_memory", insight = "Walking clears your head", category = "what_helps")
        assertEquals(1, executor.execute(listOf(call, call), Session(id = 1, startedAt = 1), 1, zone, 100).size)
        assertEquals(1, db.noteDao().all().single().timesSeen)
    }

}
