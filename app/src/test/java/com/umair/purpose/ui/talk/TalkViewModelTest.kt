package com.umair.purpose.ui.talk

import android.content.Context
import com.umair.purpose.ai.FailoverPolicy
import com.umair.purpose.chat.ActiveConversation
import com.umair.purpose.chat.ChatPrefixCache
import com.umair.purpose.chat.ReplyEngine
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.repo.ChatRepository
import com.umair.purpose.data.repo.JourneyRepository
import com.umair.purpose.data.repo.OnboardingRepository
import com.umair.purpose.data.repo.PromiseRepository
import com.umair.purpose.data.repo.PulseRepository
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UiPrefs
import com.umair.purpose.growth.GrowthEngine
import com.umair.purpose.security.SecretStore
import com.umair.purpose.system.Connectivity
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
class TalkViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var chat: ChatRepository
    private lateinit var settings: SettingsRepository
    private lateinit var promises: PromiseRepository
    private lateinit var journeys: JourneyRepository
    private lateinit var onboarding: OnboardingRepository
    private lateinit var pulses: PulseRepository
    private lateinit var prefixCache: ChatPrefixCache
    private lateinit var engine: ReplyEngine
    private lateinit var uiPrefs: UiPrefs
    private lateinit var speaker: Speaker
    private lateinit var db: PurposeDatabase
    private lateinit var active: ActiveConversation
    private lateinit var connectivity: Connectivity
    private lateinit var growth: GrowthEngine
    private lateinit var failover: FailoverPolicy
    private lateinit var secrets: SecretStore
    private lateinit var viewModel: TalkViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = smartMock()
        chat = smartMock()
        settings = smartMock()
        promises = smartMock()
        journeys = smartMock()
        onboarding = smartMock()
        pulses = smartMock()
        prefixCache = smartMock()
        engine = smartMock()
        uiPrefs = smartMock()
        speaker = smartMock()
        db = smartMock()
        active = smartMock()
        connectivity = smartMock()
        growth = smartMock()
        failover = smartMock()
        secrets = smartMock()

        viewModel = TalkViewModel(
            context, chat, settings, promises, journeys, onboarding, pulses,
            prefixCache, engine, uiPrefs, speaker, db, active,
            connectivity, growth, com.umair.purpose.chat.ActionReceiptStore(), failover, secrets
        )
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
    fun testDismissStepDoneCallsUiPrefs() = runTest(testDispatcher) {
        viewModel.dismissStepDone()
        verify(uiPrefs).setStepDone(null)
    }
}
