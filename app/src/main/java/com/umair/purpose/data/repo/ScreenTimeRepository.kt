package com.umair.purpose.data.repo

import androidx.room.withTransaction
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.ScreenUsage
import com.umair.purpose.screen.ScreenTimeAggregator
import com.umair.purpose.screen.ScreenTimeCollector
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Phase 4: collects, stores, shows and deletes the opt-in screen-time data. */
@Singleton
class ScreenTimeRepository @Inject constructor(
    private val db: PurposeDatabase,
    private val collector: ScreenTimeCollector,
) {
    private val dao = db.screenUsageDao()

    fun hasAccess(): Boolean = collector.hasAccess()
    fun accessSettingsIntent() = collector.accessSettingsIntent()

    /**
     * Reads the last [DAYS_BACK] days from the phone and replaces what is stored for them (the phone is the source
     * of truth for those days, so a re-run never double counts). Older days stay as stored; days past
     * [KEEP_DAYS] are dropped. Returns the number of day/hour rows written.
     */
    suspend fun sync(now: Long, zone: ZoneId): Int {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val firstDay = today.minusDays(DAYS_BACK.toLong())
        val from = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = ScreenTimeAggregator.aggregate(collector.events(from, now), now, zone)
        // No access, or the phone returned nothing: keep what is stored rather than wiping days it can no longer see.
        if (!collector.hasAccess() || rows.isEmpty()) return 0
        db.withTransaction {
            dao.clearRange(firstDay.toString(), today.toString())
            dao.upsert(rows)
            dao.pruneBefore(today.minusDays(KEEP_DAYS.toLong()).toString())
        }
        return rows.size
    }

    suspend fun since(day: LocalDate): List<ScreenUsage> = dao.since(day.toString())

    suspend fun all(): List<ScreenUsage> = dao.all()

    suspend fun count(): Int = dao.count()

    /** Deletes every stored screen-time row. The backup, the exports and the ledger no longer see anything. */
    suspend fun deleteAll() = dao.clear()

    /** CSV he can open anywhere: date,hour,category,minutes. */
    suspend fun csv(): String = buildString {
        append("date,hour,category,minutes\n")
        dao.all().forEach { append(it.date).append(',').append(it.hour).append(',').append(it.category).append(',').append(it.minutes).append('\n') }
    }

    companion object {
        /** Days re-read from the phone on every sync (Android only keeps a few days of events). */
        const val DAYS_BACK = 2
        /** About two years, like the monthly usage totals. */
        const val KEEP_DAYS = 730
    }
}
