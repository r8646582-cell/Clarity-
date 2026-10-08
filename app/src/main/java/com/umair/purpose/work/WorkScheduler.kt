package com.umair.purpose.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.umair.purpose.letter.LetterPlanning
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background work: reflection, letters, snapshot, gardening, backup. AI jobs wait for a network and retry with
 * exponential backoff (see MAX_JOB_ATTEMPTS). Never shows notifications.
 */
@Singleton
class WorkScheduler @Inject constructor(@ApplicationContext context: Context) {
    private val wm = WorkManager.getInstance(context)
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** UPDATE-17: the provider's cheaper hours, kept current by the app from Settings. */
    @Volatile var offPeak: com.umair.purpose.cost.OffPeakConfig = com.umair.purpose.cost.OffPeakConfig()

    /** How long work that can wait should wait for the off-peak price (0 when it's off-peak now). */
    private fun cheapDelayMs(): Long =
        com.umair.purpose.cost.OffPeak.delayUntilOffPeak(java.time.Instant.now(), offPeak).toMillis()

    /**
     * Reflects on every ended, unreflected session. Safe to call often. [backlog]: catch-up at launch, which can
     * wait for the off-peak hours; a conversation that just ended is reflected on straight away (he may be back soon).
     */
    fun reflect(backlog: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<ReflectionWorker>()
            .setConstraints(online)
            .setInitialDelay(if (backlog) cheapDelayMs() else 0L, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        // Append so a conversation that ends while a reflection runs still gets its own pass.
        wm.enqueueUniqueWork(REFLECTION, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Writes any letter that's owed now (catch-up), then sleeps until the next Sunday 20:00 or 1st of the month. */
    suspend fun checkLetters() {
        // Opening the app while a letter is being written must not cancel and restart it: the long model call
        // would be paid for twice. The running job ends by scheduling the next check itself.
        val running = runCatching { wm.getWorkInfosForUniqueWorkFlow(LETTERS).first().any { it.state == WorkInfo.State.RUNNING } }
            .getOrDefault(false)
        if (running) return
        enqueueLetterCheck(Duration.ZERO, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueueLetterCheck(delay: Duration, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<LetterWorker>()
            .setConstraints(online)
            .setInitialDelay(delay.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork(LETTERS, policy, request)
    }

    /**
     * Called at the end of a successful automatic run. Appended, so it starts after the running check
     * finishes (replacing would cancel the running one). App launch restarts the chain if it ever breaks.
     */
    fun scheduleNextLetterCheck(now: LocalDateTime = LocalDateTime.now()) {
        // A minute late is fine; a minute early would find nothing owed. If that moment is in the provider's peak
        // hours, it waits for the cheaper ones (on the current DeepSeek windows, Sunday and the 1st at midnight
        // are already off-peak, so letters still arrive on time).
        val at = Duration.between(now, LetterPlanning.nextMoment(now)).plusMinutes(1)
        val atInstant = java.time.Instant.now().plus(at)
        val extra = com.umair.purpose.cost.OffPeak.delayUntilOffPeak(atInstant, offPeak)
        enqueueLetterCheck(at.plus(extra), ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    fun writeThisWeekNow() {
        val request = OneTimeWorkRequestBuilder<LetterWorker>()
            .setConstraints(online)
            .setInputData(workDataOf(LetterWorker.KEY_THIS_WEEK to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork(LETTER_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** Developer menu: this week's letter, even if one exists, as long as there was a conversation this week. */
    fun writeTestLetter(monthly: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<LetterWorker>()
            .setConstraints(online)
            .setInputData(workDataOf(LetterWorker.KEY_THIS_WEEK to true, LetterWorker.KEY_TEST to true, LetterWorker.KEY_MONTH to monthly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork(LETTER_NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun letterNowState(): Flow<WorkInfo.State?> =
        wm.getWorkInfosForUniqueWorkFlow(LETTER_NOW).map { infos -> infos.lastOrNull()?.state }

    fun writeSnapshot() {
        val request = OneTimeWorkRequestBuilder<SnapshotWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork(SNAPSHOT, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Memory gardening, after a monthly letter (or "Run memory gardening now" / "Clean up now"). [now]: he asked
     * for it and is waiting; otherwise it waits for the off-peak hours.
     */
    fun garden(now: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<GardeningWorker>()
            .setConstraints(online)
            .setInitialDelay(if (now) 0L else cheapDelayMs(), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork(GARDENING, ExistingWorkPolicy.KEEP, request)
    }

    /** Weekly automatic backup on or off. No network needed: it writes to a folder he picked. */
    fun setAutoBackup(on: Boolean) {
        if (!on) {
            wm.cancelUniqueWork(AUTO_BACKUP)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        wm.enqueueUniquePeriodicWork(AUTO_BACKUP, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Phase 4: the daily screen-time copy, on only while he has it switched on. Local only, no network. */
    fun setScreenTime(on: Boolean) {
        if (!on) {
            wm.cancelUniqueWork(SCREEN_TIME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ScreenTimeWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(SCREEN_TIME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** One copy right now, for when he has just switched it on. */
    fun screenTimeNow() {
        wm.enqueueUniqueWork(SCREEN_TIME_NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ScreenTimeWorker>().build())
    }

    /** "Back up now" from Settings, through the same job. */
    fun backupNow() {
        wm.enqueueUniqueWork(BACKUP_NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BackupWorker>().build())
    }

    /**
     * UPDATE-15/17: monthly upkeep (usage totals, search index, VACUUM), only while charging with battery not low.
     * Never "device idle": Android rejects an idle-mode job with backoff ("Cannot set backoff criteria on an idle
     * mode job"), and WorkManager always sets one. That crashed every launch in 1.1.0 and 1.2.0.
     */
    fun scheduleMaintenance() {
        val constraints = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()
        val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(30, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(MAINTENANCE, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * The nightly garden: once a day, first run nudged into the provider's off-peak window, then repeating at the
     * same hour. It touches only the local database (no network, no AI), so the only guard is a battery that isn't
     * low. Updating the existing request keeps the schedule stable across launches.
     */
    fun scheduleDailyGarden() {
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
        val request = PeriodicWorkRequestBuilder<DailyGardenWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInitialDelay(cheapDelayMs(), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(DAILY_GARDEN, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * The broken maintenance job saved by 1.1.0/1.2.0 (an idle job with backoff) crashes WorkManager whenever it
     * tries to schedule it. Cancel it first thing at launch, before anything else is enqueued, so it's never
     * scheduled again. WorkManager runs operations in order, so this lands before any later enqueue.
     */
    fun cancelBrokenMaintenance() {
        wm.cancelUniqueWork(OLD_MAINTENANCE)
    }

    /** UPDATE-18: growth-tree proposals after a journey's last day, in the off-peak hours. */
    fun growthAfterJourney() {
        val request = OneTimeWorkRequestBuilder<GrowthWorker>()
            .setConstraints(online)
            .setInitialDelay(cheapDelayMs(), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork(GROWTH, ExistingWorkPolicy.KEEP, request)
    }

    /** What I know shows "Cleaning up what I know…" while gardening runs. */
    fun gardeningState(): Flow<WorkInfo.State?> =
        wm.getWorkInfosForUniqueWorkFlow(GARDENING).map { infos -> infos.lastOrNull()?.state }

    fun snapshotState(): Flow<WorkInfo.State?> =
        wm.getWorkInfosForUniqueWorkFlow(SNAPSHOT).map { infos -> infos.lastOrNull()?.state }

    private companion object {
        const val REFLECTION = "reflection"
        const val LETTERS = "letters"
        const val LETTER_NOW = "letter_now"
        const val SNAPSHOT = "snapshot"
        const val GARDENING = "gardening"
        const val AUTO_BACKUP = "auto_backup"
        const val SCREEN_TIME = "screen_time"
        const val SCREEN_TIME_NOW = "screen_time_now"
        const val BACKUP_NOW = "backup_now"
        /** Renamed in 1.2.1 so the broken job saved under [OLD_MAINTENANCE] can be cancelled cleanly. */
        const val MAINTENANCE = "maintenance_v2"
        const val OLD_MAINTENANCE = "maintenance"
        const val GROWTH = "growth"
        const val DAILY_GARDEN = "daily_garden"
    }
}
