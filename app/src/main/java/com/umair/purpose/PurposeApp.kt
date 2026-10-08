package com.umair.purpose

import android.app.Application
import com.umair.purpose.data.repo.PromiseRepository
import com.umair.purpose.chat.ReplyEngine
import com.umair.purpose.dev.CrashLog
import com.umair.purpose.ui.settings.appVersion
import com.umair.purpose.promise.ReminderScheduler
import com.umair.purpose.work.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PurposeApp : Application() {
    @Inject lateinit var work: WorkScheduler
    @Inject lateinit var promises: PromiseRepository
    @Inject lateinit var replies: ReplyEngine
    @Inject lateinit var uiPrefs: com.umair.purpose.data.repo.UiPrefs
    @Inject lateinit var onboarding: com.umair.purpose.data.repo.OnboardingRepository
    @Inject lateinit var gate: com.umair.purpose.data.db.DatabaseGate
    @Inject lateinit var maintenance: com.umair.purpose.work.Maintenance
    @Inject lateinit var offlineQueue: com.umair.purpose.chat.OfflineQueue
    @Inject lateinit var settings: com.umair.purpose.data.repo.SettingsRepository
    @Inject lateinit var memory: com.umair.purpose.data.repo.MemoryRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this is kept for Settings > Advanced > Developer.
        CrashLog.install(this, appVersion(this))
        // 1.2.1: remove the saved maintenance job that crashed launches in 1.1.0 and 1.2.0, before anything else.
        runCatching { work.cancelBrokenMaintenance() }
        com.umair.purpose.system.Haptics.enabled = uiPrefs.vibration
        // A cadence the coach set in a past conversation keeps applying to the reply reveal.
        com.umair.purpose.ui.talk.RevealPacer.speed = uiPrefs.paceMultiplier.toDouble()
        ReminderScheduler.ensureChannel(this)
        // Everything else waits for the database to open (and migrate) safely. If that fails, the app shows what
        // happened and touches nothing (UPDATE-15).
        scope.launch {
            if (!gate.open()) return@launch
            // A reply cut off because the process died: shown as interrupted, with Try again, never stuck.
            replies.recoverAfterRestart()
            // UPDATE-19: his correction (the blocker app is Dechainer) fixed in the data he already has.
            runCatching { memory.applyKnownCorrections(System.currentTimeMillis()) }
            // UPDATE-17: background jobs that can wait use the provider's off-peak hours.
            launch { settings.observe().collect { work.offPeak = it.offPeak } }
            work.offPeak = settings.get().offPeak
            // Any conversation that ended but was never reflected on gets processed (in the cheaper hours).
            work.reflect(backlog = true)
            // Updates 10, 12 and 15, once: memory gardening rewrites everything to "you", merges duplicate
            // people, and (15) trims memory to the new caps.
            if (!uiPrefs.gardenedForUpdate10 || !uiPrefs.gardenedForUpdate12 || !uiPrefs.gardenedForUpdate15) work.garden(now = true)
            // Catch up on any letter that was due while the phone was off, then sleep until the next one.
            work.checkLetters()
            work.scheduleMaintenance()
            // Nightly off-peak tidy: refresh the morning perspective and de-duplicate what he knows.
            work.scheduleDailyGarden()
            // "Back up automatically every week" lives in the database, but the periodic job does not: a restore
            // brings the setting back with no job behind it, so the setting looked on while nothing ever ran.
            runCatching {
                val s = settings.get()
                if (s.autoBackup && !s.backupFolder.isNullOrBlank()) work.setAutoBackup(true)
            }
            promises.rescheduleReminders()
            // UPDATE-16: messages written offline go out as soon as there's a connection.
            offlineQueue.start()
            // UPDATE-15: the archive's search index, filled the first time after this update.
            runCatching { maintenance.ensureSearchIndex() }
            // Big Five answers from the old item list: political items dropped, the rest rescored.
            runCatching { onboarding.rescoreLegacyBigFive() }
            // All five conversations done but no snapshot (it failed, or the phone killed the job): write it now.
            runCatching { onboarding.state().takeIf { it.allTopicsDone && it.snapshot == null }?.let { work.writeSnapshot() } }
        }
    }
}
