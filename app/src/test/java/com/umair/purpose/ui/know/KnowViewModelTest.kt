package com.umair.purpose.ui.know

import com.umair.purpose.chat.ActiveConversation
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.repo.LetterRepository
import com.umair.purpose.data.repo.MemoryRepository
import com.umair.purpose.data.repo.OnboardingRepository
import com.umair.purpose.memory.SnapshotWriter
import com.umair.purpose.testutil.smartMock
import com.umair.purpose.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class KnowViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var memory: MemoryRepository
    private lateinit var onboarding: OnboardingRepository
    private lateinit var snapshots: SnapshotWriter
    private lateinit var work: WorkScheduler
    private lateinit var active: ActiveConversation
    private lateinit var letters: LetterRepository
    private lateinit var viewModel: KnowViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        memory = smartMock()
        onboarding = smartMock()
        snapshots = smartMock()
        work = smartMock()
        active = smartMock()
        letters = smartMock()
        viewModel = KnowViewModel(memory, onboarding, snapshots, work, active, smartMock(), letters)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitializationSuccess() {
        assertNotNull(viewModel)
    }

    @Test
    fun testDeleteNoteCallsRepository() = runTest(testDispatcher) {
        val note = Note(
            id = 15L,
            type = "pattern",
            text = "Consistent work",
            confidence = "likely",
            status = "active",
            timesSeen = 1,
            firstSeen = 0L,
            lastSeen = 0L
        )
        viewModel.deleteNote(note)
        testDispatcher.scheduler.advanceUntilIdle()
        verify(memory).deleteNote(15L)
    }

    @Test
    fun testRestoreNoteCallsRepository() = runTest(testDispatcher) {
        val note = Note(
            id = 20L,
            type = "what_helps",
            text = "Taking small breaks",
            confidence = "confirmed",
            status = "active",
            timesSeen = 2,
            firstSeen = 0L,
            lastSeen = 0L
        )
        viewModel.restoreNote(note)
        testDispatcher.scheduler.advanceUntilIdle()
        verify(memory).restoreNote(note)
    }
}
