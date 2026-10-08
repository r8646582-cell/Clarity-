package com.umair.purpose.cost

import com.umair.purpose.data.db.UsageMonth
import com.umair.purpose.data.db.UsageStat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * UPDATE-17: single requests older than a few months are folded into monthly totals per feature and model
 * (kept for [KEEP_MONTHS] months), so the usage table never grows without limit. Pure, for tests.
 */
object UsageRollup {
    /** Requests from this many recent months (including this one) stay as single rows. */
    const val DETAIL_MONTHS = 3
    const val KEEP_MONTHS = 24

    fun month(at: Long, zone: ZoneId): String = YearMonth.from(Instant.ofEpochMilli(at).atZone(zone)).toString()

    /** The first moment that stays detailed: the start of the month [DETAIL_MONTHS] - 1 months ago. */
    fun detailFrom(now: Long, zone: ZoneId): Long =
        YearMonth.from(Instant.ofEpochMilli(now).atZone(zone)).minusMonths((DETAIL_MONTHS - 1).toLong())
            .atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** The oldest month kept. */
    fun oldestKept(now: Long, zone: ZoneId): String =
        YearMonth.from(Instant.ofEpochMilli(now).atZone(zone)).minusMonths((KEEP_MONTHS - 1).toLong()).toString()

    /** [stats] added onto [existing] totals (the same key adds up). */
    fun fold(stats: List<UsageStat>, existing: List<UsageMonth>, zone: ZoneId): List<UsageMonth> {
        val out = LinkedHashMap<List<Any>, UsageMonth>()
        existing.forEach { out[key(it.month, it.purpose, it.model, it.tier, it.offPeak)] = it }
        for (s in stats) {
            val k = key(month(s.createdAt, zone), s.purpose, s.model, s.tier.orEmpty(), s.offPeak)
            val cur = out[k] ?: UsageMonth(k[0] as String, s.purpose, s.model, s.tier.orEmpty(), s.offPeak, 0, 0, 0, 0, 0)
            out[k] = cur.copy(
                requests = cur.requests + 1,
                promptTokens = cur.promptTokens + s.promptTokens,
                cacheHitTokens = cur.cacheHitTokens + s.cacheHitTokens,
                cacheMissTokens = cur.cacheMissTokens + s.cacheMissTokens,
                completionTokens = cur.completionTokens + s.completionTokens,
            )
        }
        return out.values.toList()
    }

    private fun key(month: String, purpose: String, model: String, tier: String, offPeak: Boolean) = listOf(month, purpose, model, tier, offPeak)
}
