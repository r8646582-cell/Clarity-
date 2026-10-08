package com.umair.purpose.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umair.purpose.ai.AiConfig
import com.umair.purpose.backup.BackupRepository
import com.umair.purpose.backup.BadPassphraseException
import com.umair.purpose.chat.ToughLove
import com.umair.purpose.cost.CostEstimate
import com.umair.purpose.cost.CostEstimator
import com.umair.purpose.cost.Prices
import com.umair.purpose.data.db.UsageTotals
import com.umair.purpose.data.repo.AppSettings
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.ThemeChoice
import com.umair.purpose.data.repo.VoiceLanguage
import com.umair.purpose.data.repo.UsageRepository
import android.content.Context
import com.umair.purpose.data.repo.ChatRepository
import com.umair.purpose.data.repo.UiPrefs
import android.content.Intent
import com.umair.purpose.dev.CrashLog
import com.umair.purpose.dev.ErrorLogger
import kotlinx.coroutines.withContext
import com.umair.purpose.promise.ReminderScheduler
import com.umair.purpose.security.SecretStore
import com.umair.purpose.work.WorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings? = null,
    val hasApiKey: Boolean = false,
    val usage: UsageTotals? = null,
    val cost: CostEstimate? = null,
    val backup: BackupStatus = BackupStatus.Idle,
    /** UPDATE-17: this month's cost per feature, the cache hit rate and the deep share. */
    val breakdown: com.umair.purpose.cost.Breakdown? = null,
    /** UPDATE-16: a backup provider key is saved. */
    val hasBackupKey: Boolean = false,
)

sealed interface BackupStatus {
    data object Idle : BackupStatus
    data object Working : BackupStatus
    data class Done(val message: String) : BackupStatus
    data class Failed(val message: String) : BackupStatus
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
    private val secrets: SecretStore,
    private val backups: BackupRepository,
    private val chat: ChatRepository,
    private val work: WorkScheduler,
    private val reminders: ReminderScheduler,
    private val uiPrefs: UiPrefs,
    private val errors: ErrorLogger,
    private val speaker: com.umair.purpose.ui.talk.Speaker,
    usage: UsageRepository,
) : ViewModel() {

    private val backupStatus = MutableStateFlow<BackupStatus>(BackupStatus.Idle)

    private val backupKeySaved = MutableStateFlow(secrets.backupApiKey() != null)

    val state: StateFlow<SettingsUiState> = combine(
        combine(repo.observe(), secrets.hasApiKey, ::Pair),
        combine(usage.observeThisMonth(), usage.observeThisMonthByModel(), ::Pair),
        backupStatus,
        usage.observeThisMonthBreakdown(),
        backupKeySaved,
    ) { (s, hasKey), (totals, byModel), backup, features, backupKey ->
        SettingsUiState(
            settings = s,
            hasApiKey = hasKey,
            usage = totals,
            cost = CostEstimator.estimate(byModel, s.ai.chatModel, s.chatPrices, s.ai.deepModel, s.deepPrices, s.pricing.offPeakFactor, s.pricing.extra),
            backup = backup,
            breakdown = CostEstimator.breakdown(features, s.pricing),
            hasBackupKey = backupKey,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    // ---- UPDATE-16: the backup AI provider ----

    /** [key] blank keeps the saved one. */
    fun saveBackupProvider(kind: String, baseUrl: String, key: String, chatModel: String, deepModel: String) {
        viewModelScope.launch {
            if (key.isNotBlank()) withContext(Dispatchers.IO) { secrets.setBackupApiKey(key) }
            backupKeySaved.value = secrets.backupApiKey() != null
            repo.update {
                it.copy(backupProvider = com.umair.purpose.data.repo.BackupProvider(kind, baseUrl.trim(), chatModel.trim(), deepModel.trim().ifBlank { chatModel.trim() }))
            }
        }
    }

    fun removeBackupProvider() {
        viewModelScope.launch {
            secrets.clearBackupApiKey()
            backupKeySaved.value = false
            repo.update { it.copy(backupProvider = null) }
        }
    }

    // ---- UPDATE-17: off-peak ----

    fun saveOffPeak(enabled: Boolean, windows: String, factor: Double) =
        change { it.copy(offPeak = com.umair.purpose.cost.OffPeakConfig(enabled, windows.trim(), it.offPeak.weekdaysOnly, factor.coerceIn(0.05, 1.0))) }

    // ---- UPDATE-16: once a year ----

    fun markRestoreTested() = change { it.copy(lastRestoreTestAt = System.currentTimeMillis()) }
    fun confirmKeystore() = change { it.copy(keystoreConfirmedAt = System.currentTimeMillis()) }
    fun setKeyExpiry(date: String?) = change { it.copy(providerKeyExpiry = date?.trim()?.takeIf { d -> d.isNotEmpty() }) }

    // ---- UPDATE-15: Export my life ----

    /** A readable Markdown file and the same as JSON, unencrypted, into the folder he picks. */
    fun exportLife(folder: Uri) = runBackup("Saved. Two files, unencrypted: keep them somewhere private.") {
        val names = backups.exportLife(folder)
        backupStatus.value = BackupStatus.Done("Saved ${names.joinToString(" and ")}. Unencrypted: keep them somewhere private.")
    }

    fun saveApiKey(key: String) {
        if (key.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { secrets.setApiKey(key) }
    }

    fun removeApiKey() = secrets.clearApiKey()

    fun setToughLove(level: ToughLove) {
        viewModelScope.launch { repo.update { it.copy(toughLove = level) } }
    }

    fun saveAi(config: AiConfig) {
        viewModelScope.launch { repo.update { it.copy(ai = config) } }
    }

    fun savePrices(chat: Prices, deep: Prices) {
        viewModelScope.launch { repo.update { it.copy(chatPrices = chat, deepPrices = deep) } }
    }

    fun setTheme(t: ThemeChoice) = change { it.copy(theme = t) }
    fun setLock(on: Boolean) = change { it.copy(lockEnabled = on) }
    fun setHideInRecents(on: Boolean) = change { it.copy(hideInRecents = on) }
    fun setPulse(on: Boolean) = change { it.copy(pulseEnabled = on) }
    fun setReadAloud(on: Boolean) = change { it.copy(readAloud = on) }
    fun setVoiceLanguage(v: VoiceLanguage) = change { it.copy(voiceLanguage = v) }
    fun setAlwaysDeep(on: Boolean) = change { it.copy(alwaysDeep = on) }

    // ---- Read aloud: voices (CLAUDE.md "Read aloud: voices") ----

    val voices = MutableStateFlow<List<com.umair.purpose.ui.talk.VoiceOption>?>(null)
    val voiceName = MutableStateFlow(uiPrefs.voiceName)
    val voiceSpeed = MutableStateFlow(uiPrefs.voiceSpeed)
    val voicePitch = MutableStateFlow(uiPrefs.voicePitch)

    fun loadVoices(language: VoiceLanguage) {
        viewModelScope.launch { voices.value = speaker.voices(language) }
    }

    fun pickVoice(name: String?) {
        uiPrefs.voiceName = name
        voiceName.value = name
    }

    fun setVoiceSpeed(v: Float) {
        uiPrefs.voiceSpeed = v
        voiceSpeed.value = v
    }

    fun setVoicePitch(v: Float) {
        uiPrefs.voicePitch = v
        voicePitch.value = v
    }

    fun tryVoice(language: VoiceLanguage) =
        speaker.speak("This is how I'll sound when I read my replies to you, Umair.", language)

    fun openVoiceSettings() = speaker.openSystemSettings()

    val vibration = MutableStateFlow(uiPrefs.vibration)

    fun setVibration(on: Boolean) {
        uiPrefs.vibration = on
        com.umair.purpose.system.Haptics.enabled = on
        vibration.value = on
    }

    fun setBudget(usd: Double) = change { it.copy(monthlyBudget = usd) }

    // ---- Automatic backup ----

    val lastBackupAt = MutableStateFlow(uiPrefs.lastBackupAt)

    fun refreshLastBackup() {
        lastBackupAt.value = uiPrefs.lastBackupAt
    }

    /** Keeps access to the folder he picked, stores the passphrase with Keystore, and backs up now and weekly. */
    fun enableAutoBackup(folder: Uri, passphrase: String) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            withContext(Dispatchers.IO) { secrets.setBackupPassphrase(passphrase) }
            repo.update { it.copy(autoBackup = true, backupFolder = folder.toString()) }
            work.setAutoBackup(true)
            work.backupNow()
        }
    }

    fun disableAutoBackup() {
        viewModelScope.launch {
            work.setAutoBackup(false)
            secrets.clearBackupPassphrase()
            val old = repo.get().backupFolder
            repo.update { it.copy(autoBackup = false, backupFolder = null) }
            old?.let { runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        }
    }

    fun backupNow() = work.backupNow()

    // ---- Erase everything ----

    val erased = MutableStateFlow(false)

    fun eraseEverything() {
        viewModelScope.launch {
            chat.eraseEverything()
            erased.value = true
        }
    }

    private fun change(f: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(f) }
    }

    fun export(uri: Uri, passphrase: String) = runBackup("Backup saved.") { backups.export(uri, passphrase.toCharArray()) }

    fun restore(uri: Uri, passphrase: String) = runBackup("Restored. Everything from the backup is back.") {
        backups.import(uri, passphrase.toCharArray())
    }

    fun clearBackupStatus() {
        backupStatus.value = BackupStatus.Idle
    }

    private fun runBackup(success: String, block: suspend () -> Unit) {
        if (backupStatus.value == BackupStatus.Working) return
        backupStatus.value = BackupStatus.Working
        viewModelScope.launch {
            backupStatus.value = try {
                block()
                (backupStatus.value as? BackupStatus.Done) ?: BackupStatus.Done(success)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: BadPassphraseException) {
                BackupStatus.Failed("Wrong passphrase, or the file is damaged.")
            } catch (e: Exception) {
                BackupStatus.Failed(e.message ?: "Something went wrong.")
            }
        }
    }
}
