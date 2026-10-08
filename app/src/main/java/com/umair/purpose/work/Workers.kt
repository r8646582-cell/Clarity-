package com.umair.purpose.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.umair.purpose.ai.AiException
import com.umair.purpose.backup.AutoBackup
import com.umair.purpose.data.db.Letter
import com.umair.purpose.data.repo.UiPrefs
import com.umair.purpose.dev.ErrorLogger
import com.umair.purpose.letter.LetterWriter
import com.umair.purpose.memory.Gardener
import com.umair.purpose.memory.ReflectionEngine
import com.umair.purpose.memory.ReflectionParseException
import com.umair.purpose.memory.SnapshotWriter
import com.umair.purpose.security.SecretStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlin.coroutines.cancellation.CancellationException
import java.time.LocalDate
import java.time.LocalDateTime

/** How workers reach Hilt singletons without an extra hilt-work dependency. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkerEntryPoint {
    fun database(): com.umair.purpose.data.db.PurposeDatabase
    fun reflectionEngine(): ReflectionEngine
    fun letterWriter(): LetterWriter
    fun snapshotWriter(): SnapshotWriter
    fun gardener(): Gardener
    fun autoBackup(): AutoBackup
    fun scheduler(): WorkScheduler
    fun secrets(): SecretStore
    fun errors(): ErrorLogger
    fun uiPrefs(): UiPrefs
    fun chapterWriter(): com.umair.purpose.letter.ChapterWriter
    fun searchIndex(): com.umair.purpose.memory.SearchIndex
    fun maintenance(): Maintenance
    fun growth(): com.umair.purpose.growth.GrowthEngine
    fun journeyAdapter(): com.umair.purpose.journey.JourneyAdapter
    fun promiseRepository(): com.umair.purpose.data.repo.PromiseRepository
    fun memoryRepository(): com.umair.purpose.data.repo.MemoryRepository
    fun screenTime(): com.umair.purpose.data.repo.ScreenTimeRepository
}

private fun Context.entryPoint() = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java)

/** Background jobs try this many times (the first run plus retries), with exponential backoff. Never a raw error. */
const val MAX_JOB_ATTEMPTS = 4

/** Errors that only he can fix (bad key, no balance): stop retrying; the next launch tries again. */
private fun AiException.needsUser() = httpCode == 401 || httpCode == 402 || httpCode == 403

/** Retry while attempts remain and the error isn't one only he can fix. Every failure goes to the error log. */
private suspend fun ListenableWorker.failed(source: String, e: Exception, errors: ErrorLogger): ListenableWorker.Result {
    if (e is CancellationException) throw e
    errors.log(source, e)
    val permanent = e is AiException && e.needsUser()
    return if (!permanent && runAttemptCount + 1 < MAX_JOB_ATTEMPTS) ListenableWorker.Result.retry() else ListenableWorker.Result.failure()
}

/** Reflects on every pending session, one job at a time. Throws on the first failure; what's done stays done. */
private suspend fun reflectAll(engine: ReflectionEngine) = engine.reflectPending()

class ReflectionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = applicationContext.entryPoint().database().datasetWork.withWriter { workForCurrentDataset() }

    private suspend fun workForCurrentDataset(): Result {
        val ep = applicationContext.entryPoint()
        if (!ep.secrets().hasApiKey.value) return Result.success()
        return try {
            reflectAll(ep.reflectionEngine())
            // Update 12, once: steps he covered in his earlier onboarding conversations count now.
            if (!ep.uiPrefs().onboardingRechecked) {
                ep.reflectionEngine().recheckOnboarding()
                ep.uiPrefs().onboardingRechecked = true
            }
            ep.uiPrefs().recordJob("reflection", ok = true)
            // UPDATE-18: a journey session that was just reflected on may change the rest of the journey.
            runCatching { ep.journeyAdapter().adaptPending() }.onFailure { if (it is CancellationException) throw it else ep.errors().log("journey_adapt", it) }
            // UPDATE-15: memory over a cap gets tidied: gardening (at most weekly) merges; otherwise the weakest are archived.
            runCatching { ep.maintenance().keepWithinCaps() }.onFailure { if (it is CancellationException) throw it }
            Result.success()
        } catch (e: Exception) {
            if (e !is CancellationException) ep.uiPrefs().recordJob("reflection", ok = false)
            // Left unreflected; picked up again on a retry or the next launch.
            failed("reflection", e, ep.errors())
        }
    }
}

/**
 * Automatic run: writes every letter owed now (weekly, then monthly, then yearly), then schedules the next check.
 * With [KEY_THIS_WEEK]: "Write this week's letter now". With [KEY_TEST] too: the Developer menu's test letter.
 * After all retries fail, Mirror shows "This week's letter is delayed." with Try again.
 */
class LetterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = applicationContext.entryPoint().database().datasetWork.withWriter { workForCurrentDataset() }

    private suspend fun workForCurrentDataset(): Result {
        val ep = applicationContext.entryPoint()
        val manual = inputData.getBoolean(KEY_THIS_WEEK, false)
        val test = inputData.getBoolean(KEY_TEST, false)
        val testMonth = inputData.getBoolean(KEY_MONTH, false)
        if (!ep.secrets().hasApiKey.value) {
            if (manual) return Result.failure()
            ep.scheduler().scheduleNextLetterCheck()
            return Result.success()
        }
        val writer = ep.letterWriter()
        var kind = Letter.WEEKLY
        return try {
            // Letters read the summaries, so finish any pending reflection first.
            runCatching { reflectAll(ep.reflectionEngine()) }.onFailure { if (it is CancellationException) throw it }
            if (manual) {
                when {
                    test && testMonth -> if (writer.hadConversationIn(writer.testMonthPeriod(LocalDate.now()))) {
                        writer.write(writer.testMonthPeriod(LocalDate.now()), test = true)
                    }
                    test -> if (writer.hadConversationThisWeek(LocalDate.now())) writer.write(writer.testWeekPeriod(LocalDate.now()), test = true)
                    writer.canWriteThisWeek() -> writer.write(writer.thisWeekPeriod(LocalDate.now()))
                }
            } else {
                // UPDATE-18: growth-tree proposals first (weekly), so the weekly letter can mention a new one.
                val growthNote = runCatching { ep.growth().runIfDue() }
                    .onFailure { if (it is CancellationException) throw it else ep.errors().log("milestone", it) }
                    .getOrNull()
                for (period in writer.due(LocalDateTime.now())) {
                    kind = period.kind
                    writer.write(period, appendix = growthNote.takeIf { period.kind == Letter.WEEKLY })
                    // Memory gardening follows each monthly letter.
                    if (period.kind == Letter.MONTHLY) ep.scheduler().garden()
                }
                // UPDATE-15: a life chapter for every finished quarter (after its monthly letters). A chapter is
                // not a letter: a failure here must not relabel letters that were written successfully as
                // delayed, and "chapter" is not a kind the Mirror or the Health check understands.
                for (q in ep.chapterWriter().due(LocalDate.now())) {
                    runCatching { ep.chapterWriter().write(q) }
                        .onFailure { if (it is CancellationException) throw it else ep.errors().log("chapter", it) }
                }
                ep.scheduler().scheduleNextLetterCheck()
            }
            ep.uiPrefs().setLetterDelayed(null)
            ep.uiPrefs().recordJob("letters", ok = true)
            Result.success()
        } catch (e: Exception) {
            if (e !is CancellationException) ep.uiPrefs().recordJob("letters", ok = false)
            val r = failed("letter", e, ep.errors())
            if (r is Result.Failure) {
                ep.uiPrefs().setLetterDelayed(kind)
                // The chain stops here; Try again on Mirror, or the next launch, starts it again.
            }
            r
        }
    }

    companion object {
        const val KEY_THIS_WEEK = "this_week"
        const val KEY_TEST = "test"
        const val KEY_MONTH = "month"
    }
}

class SnapshotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = applicationContext.entryPoint().database().datasetWork.withWriter { workForCurrentDataset() }

    private suspend fun workForCurrentDataset(): Result {
        val ep = applicationContext.entryPoint()
        if (!ep.secrets().hasApiKey.value) return Result.failure()
        return try {
            // The snapshot reads what reflection learned from the onboarding conversations.
            runCatching { reflectAll(ep.reflectionEngine()) }.onFailure { if (it is CancellationException) throw it }
            ep.snapshotWriter().write()
            ep.uiPrefs().recordJob("snapshot", ok = true)
            Result.success()
        } catch (e: Exception) {
            if (e !is CancellationException) ep.uiPrefs().recordJob("snapshot", ok = false)
            failed("snapshot", e, ep.errors())
        }
    }
}

class GardeningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = applicationContext.entryPoint().database().datasetWork.withWriter { workForCurrentDataset() }

    private suspend fun workForCurrentDataset(): Result {
        val ep = applicationContext.entryPoint()
        if (!ep.secrets().hasApiKey.value) return Result.success()
        return try {
            ep.gardener().tend()
            ep.uiPrefs().recordJob("gardening", ok = true)
            ep.uiPrefs().gardenedForUpdate10 = true
            ep.uiPrefs().gardenedForUpdate12 = true
            ep.uiPrefs().gardenedForUpdate15 = true
            Result.success()
        } catch (e: Exception) {
            if (e !is CancellationException) ep.uiPrefs().recordJob("gardening", ok = false)
            failed("gardening", e, ep.errors())
        }
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = applicationContext.entryPoint().database().datasetWork.withWriter { workForCurrentDataset() }

    private suspend fun workForCurrentDataset(): Result {
        val ep = applicationContext.entryPoint()
        return try {
            ep.autoBackup().run()
            ep.uiPrefs().recordJob("backup", ok = true)
            Result.success()
        } catch (e: Exception) {
            if (e !is CancellationException) ep.uiPrefs().recordJob("backup", ok = false)
            failed("backup", e, ep.errors())
        }
    }
}

/**
 * UPDATE-15/17: monthly upkeep while the phone charges and sits idle: usage folded into monthly totals, the
 * archive's search index checked, and SQLite VACUUM. Nothing here calls the AI.
 */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = applicationContext.entryPoint().database().datasetWork.withWriter { workForCurrentDataset() }

    private suspend fun workForCurrentDataset(): Result {
        val ep = applicationContext.entryPoint()
        return try {
            ep.maintenance().monthly()
            ep.uiPrefs().recordJob("maintenance", ok = true)
            Result.success()
        } catch (e: Exception) {
            if (e !is CancellationException) ep.uiPrefs().recordJob("maintenance", ok = false)
            failed("maintenance", e, ep.errors())
        }
    }
}

/**
 * Nightly, off-peak, network-free tidy. Reads his open promises to refresh the "morning perspective" line, then
 * merges duplicate people and exact-duplicate notes. No AI call, so it is cheap and safe to run every day; anything
 * heavier (merging by meaning) stays in the monthly [GardeningWorker].
 */
class DailyGardenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = applicationContext.entryPoint().database().datasetWork.withWriter { workForCurrentDataset() }

    private suspend fun workForCurrentDataset(): Result {
        val ep = applicationContext.entryPoint()
        return try {
            val open = ep.promiseRepository().open()
            ep.uiPrefs().morningPerspective = com.umair.purpose.chat.HomeFacts.morningPerspective(open, LocalDateTime.now())
            ep.memoryRepository().deduplicate(System.currentTimeMillis())
            ep.uiPrefs().recordJob("daily_garden", ok = true)
            Result.success()
        } catch (e: Exception) {
            if (e !is CancellationException) ep.uiPrefs().recordJob("daily_garden", ok = false)
            failed("daily_garden", e, ep.errors())
        }
    }
}

/** UPDATE-18: growth-tree proposals after a journey's final day (the weekly run happens in the letter job). */
class GrowthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = applicationContext.entryPoint().database().datasetWork.withWriter { workForCurrentDataset() }

    private suspend fun workForCurrentDataset(): Result {
        val ep = applicationContext.entryPoint()
        if (!ep.secrets().hasApiKey.value) return Result.success()
        return try {
            // Not recorded as the weekly job's success: a run stopped by its guards would postpone the weekly one.
            ep.growth().run(trigger = com.umair.purpose.growth.GrowthEngine.Trigger.JOURNEY_DONE)
            Result.success()
        } catch (e: Exception) {
            if (e !is CancellationException) ep.uiPrefs().recordJob("milestones", ok = false)
            failed("milestone", e, ep.errors())
        }
    }
}

/**
 * Phase 4: once a day, copies the last two days of phone use (minutes per hour and app category) into the local
 * database. Does nothing unless he switched it on and granted usage access; no network, no AI.
 */
class ScreenTimeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = applicationContext.entryPoint().database().datasetWork.withWriter { workForCurrentDataset() }

    private suspend fun workForCurrentDataset(): Result {
        val ep = applicationContext.entryPoint()
        if (!ep.uiPrefs().screenTimeEnabled) return Result.success()
        return try {
            ep.screenTime().sync(System.currentTimeMillis(), java.time.ZoneId.systemDefault())
            Result.success()
        } catch (e: Exception) {
            failed("screen_time", e, ep.errors())
        }
    }
}
