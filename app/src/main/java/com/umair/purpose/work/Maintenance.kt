package com.umair.purpose.work

import androidx.room.withTransaction
import com.umair.purpose.cost.UsageRollup
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.repo.UiPrefs
import com.umair.purpose.memory.Gardener
import com.umair.purpose.memory.SearchIndex
import com.umair.purpose.security.SecretStore
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Upkeep that keeps Purpose quick and small after years: caps, usage totals, the search index, VACUUM. */
@Singleton
class Maintenance @Inject constructor(
    private val db: PurposeDatabase,
    private val gardener: Gardener,
    private val search: SearchIndex,
    private val scheduler: WorkScheduler,
    private val uiPrefs: UiPrefs,
    private val secrets: SecretStore,
) {
    /**
     * Memory over a cap: gardening merges what it can (at most once a week, and only with a key); otherwise the
     * weakest are archived straight away, so the cap always holds.
     */
    suspend fun keepWithinCaps(now: Long = System.currentTimeMillis()) {
        if (!gardener.overCaps()) return
        val lastGarden = uiPrefs.job("gardening").lastRunAt ?: 0L
        if (secrets.hasApiKey.value && now - lastGarden > WEEK) scheduler.garden() else gardener.enforceCaps()
    }

    /** On launch: the search index is filled the first time (after the update), or if it was ever lost. */
    suspend fun ensureSearchIndex() = db.datasetWork.withWriter { ensureCurrentDatasetSearchIndex() }

    private suspend fun ensureCurrentDatasetSearchIndex() {
        if (search.isEmpty() && db.sessionDao().firstStartedAt() != null) search.rebuild()
    }

    /**
     * Phase 2: embeddings for any archive document that has none, and the model loaded before the first message
     * needs it. Outside the writer lock on purpose: it is slow, optional, and a failure here costs nothing.
     */
    suspend fun prepareEmbeddings() {
        search.syncEmbeddings()
        search.warmUp()
    }

    suspend fun monthly(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) = db.datasetWork.withWriter { monthlyCurrentDataset(now, zone) }

    private suspend fun monthlyCurrentDataset(now: Long, zone: ZoneId) {
        rollUpUsage(now, zone)
        ensureSearchIndex()
        // Gives back the space of deleted rows. Can't run inside a transaction.
        db.openHelper.writableDatabase.execSQL("VACUUM")
    }

    /** Single requests older than [UsageRollup.DETAIL_MONTHS] months become monthly totals; totals past 24 months go. */
    suspend fun rollUpUsage(now: Long, zone: ZoneId) = db.withTransaction {
        val cutoff = UsageRollup.detailFrom(now, zone)
        val old = db.usageDao().before(cutoff)
        if (old.isNotEmpty()) {
            val months = old.map { UsageRollup.month(it.createdAt, zone) }.toSet()
            val existing = months.flatMap { db.usageMonthDao().forMonth(it) }
            db.usageMonthDao().upsert(UsageRollup.fold(old, existing, zone))
            db.usageDao().deleteBefore(cutoff)
        }
        db.usageMonthDao().pruneBefore(UsageRollup.oldestKept(now, zone))
    }

    private companion object {
        const val WEEK = 7L * 24 * 60 * 60 * 1000
    }
}
