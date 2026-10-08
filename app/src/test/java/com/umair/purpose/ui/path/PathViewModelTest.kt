package com.umair.purpose.ui.path

import com.umair.purpose.chat.ActiveConversation
import com.umair.purpose.data.repo.JourneyRepository
import com.umair.purpose.data.repo.PromiseRepository
import com.umair.purpose.growth.TreeRepository
import com.umair.purpose.testutil.smartMock
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
class PathViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var promises: PromiseRepository
    private lateinit var journeys: JourneyRepository
    private lateinit var active: ActiveConversation
    private lateinit var trees: TreeRepository
    private lateinit var viewModel: PathViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        promises = smartMock()
        journeys = smartMock()
        active = smartMock()
        trees = smartMock()
        viewModel = PathViewModel(promises, journeys, active, trees)
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
    fun testStopJourneyCallsRepository() = runTest(testDispatcher) {
        viewModel.stopJourney()
        testDispatcher.scheduler.advanceUntilIdle()
        verify(journeys).stop()
    }

    @Test
    fun testResumeJourneyCallsRepository() = runTest(testDispatcher) {
        viewModel.resumeJourney()
        testDispatcher.scheduler.advanceUntilIdle()
        verify(journeys).resume()
    }
}
