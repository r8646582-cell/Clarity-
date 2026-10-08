package com.umair.purpose.data.repo

import com.umair.purpose.ai.TokenUsage
import com.umair.purpose.cost.ModelUsage
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.UsageStat
import com.umair.purpose.data.db.UsageTotals
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageRepository @Inject constructor(db: PurposeDatabase, private val settings: SettingsRepository) {
    private val dao = db.usageDao()

    suspend fun log(purpose: String, model: String, usage: TokenUsage, now: Long, tier: String? = null) {
        val offPeak = runCatching { com.umair.purpose.cost.OffPeak.isOffPeak(java.time.Instant.ofEpochMilli(now), settings.get().offPeak) }
            .getOrDefault(false)
        dao.insert(
            UsageStat(
                createdAt = now,
                purpose = purpose,
                model = model,
                promptTokens = usage.promptTokens,
                cacheHitTokens = usage.cacheHitTokens,
                cacheMissTokens = usage.cacheMissTokens,
                completionTokens = usage.completionTokens,
                tier = tier,
                offPeak = offPeak,
            )
        )
    }

    /** UPDATE-17: this month's cost per feature, cache hit rate and deep share. */
    fun observeThisMonthBreakdown(zone: ZoneId = ZoneId.systemDefault()): Flow<List<com.umair.purpose.cost.FeatureUsage>> =
        thisMonth(zone).let { (from, to) -> dao.observeBreakdown(from, to) }

    suspend fun breakdown(from: Long, to: Long): List<com.umair.purpose.cost.FeatureUsage> = dao.breakdown(from, to)

    fun observeThisMonth(zone: ZoneId = ZoneId.systemDefault()): Flow<UsageTotals> =
        thisMonth(zone).let { (from, to) -> dao.observeTotals(from, to) }

    fun observeThisMonthByModel(zone: ZoneId = ZoneId.systemDefault()): Flow<List<ModelUsage>> =
        thisMonth(zone).let { (from, to) -> dao.observeByModel(from, to) }

    suspend fun thisMonthByModel(zone: ZoneId = ZoneId.systemDefault()): List<ModelUsage> =
        thisMonth(zone).let { (from, to) -> dao.byModel(from, to) }

    fun thisMonth(zone: ZoneId): Pair<Long, Long> {
        val start = LocalDate.now(zone).withDayOfMonth(1)
        return start.atStartOfDay(zone).toInstant().toEpochMilli() to
            start.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
