package com.umair.purpose.ui.settings

import android.content.Context
import com.umair.purpose.backup.BackupRepository
import com.umair.purpose.data.repo.ChatRepository
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UiPrefs
import com.umair.purpose.data.repo.UsageRepository
import com.umair.purpose.dev.ErrorLogger
import com.umair.purpose.promise.ReminderScheduler
import com.umair.purpose.security.SecretStore
import com.umair.purpose.testutil.smartMock
import com.umair.purpose.ui.talk.Speaker
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
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var repo: SettingsRepository
    private lateinit var secrets: SecretStore
    private lateinit var backups: BackupRepository
    private lateinit var chat: ChatRepository
    private lateinit var work: WorkScheduler
    private lateinit var reminders: ReminderScheduler
    private lateinit var uiPrefs: UiPrefs
    private lateinit var errors: ErrorLogger
    private lateinit var speaker: Speaker
    private lateinit var usage: UsageRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = smartMock()
        repo = smartMock()
        secrets = smartMock()
        backups = smartMock()
        chat = smartMock()
        work = smartMock()
        reminders = smartMock()
        uiPrefs = smartMock()
        errors = smartMock()
        speaker = smartMock()
        usage = smartMock()
        viewModel = SettingsViewModel(
            context, repo, secrets, backups, chat, work, reminders, uiPrefs, errors, speaker, usage
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
    fun testRemoveApiKeyCallsSecretStore() = runTest(testDispatcher) {
        viewModel.removeApiKey()
        verify(secrets).clearApiKey()
    }

    @Test
    fun testBackupNowCallsWorkScheduler() = runTest(testDispatcher) {
        viewModel.backupNow()
        verify(work).backupNow()
    }
}
