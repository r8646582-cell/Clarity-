package com.umair.purpose.cost

import com.umair.purpose.data.db.UsageMonth
import com.umair.purpose.data.db.UsageStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class Update17CostTest {
    private val cfg = OffPeakConfig()

    @Test
    fun `DeepSeek's peak hours are weekday mornings UTC`() {
        // Wednesday 2026-10-07
        assertTrue(OffPeak.isPeak(Instant.parse("2026-10-07T02:30:00Z"), cfg))
        assertTrue(OffPeak.isPeak(Instant.parse("2026-10-07T09:59:00Z"), cfg))
        assertFalse(OffPeak.isPeak(Instant.parse("2026-10-07T04:00:00Z"), cfg))
        assertFalse(OffPeak.isPeak(Instant.parse("2026-10-07T10:00:00Z"), cfg))
        assertFalse(OffPeak.isPeak(Instant.parse("2026-10-07T15:00:00Z"), cfg))
        // Sunday: all off-peak.
        assertFalse(OffPeak.isPeak(Instant.parse("2026-10-04T02:30:00Z"), cfg))
        assertTrue(OffPeak.isOffPeak(Instant.parse("2026-10-04T02:30:00Z"), cfg))
        assertFalse(OffPeak.isOffPeak(Instant.parse("2026-10-04T02:30:00Z"), cfg.copy(enabled = false)))
    }

    @Test
    fun `waiting for the cheaper hours`() {
        assertEquals(Duration.ZERO, OffPeak.delayUntilOffPeak(Instant.parse("2026-10-07T12:00:00Z"), cfg))
        assertEquals(Duration.ofMinutes(90), OffPeak.delayUntilOffPeak(Instant.parse("2026-10-07T02:30:00Z"), cfg))
        assertEquals(Duration.ofMinutes(30), OffPeak.delayUntilOffPeak(Instant.parse("2026-10-07T09:30:00Z"), cfg))
        assertEquals(Duration.ZERO, OffPeak.delayUntilOffPeak(Instant.parse("2026-10-07T02:30:00Z"), cfg.copy(enabled = false)))
        // Odd settings never break it.
        assertEquals(emptyList<OffPeak.Window>(), OffPeak.parse("nonsense"))
        assertEquals(2, OffPeak.parse(" 1:00-04:00 , 06:00-10:00").size)
    }

    private val pricing = Pricing("flash", Prices(0.01, 0.4, 1.2), "pro", Prices(0.04, 1.2, 3.6), offPeakFactor = 0.5)

    @Test
    fun `cost per feature, cache hit rate and deep share`() {
        val usage = listOf(
            FeatureUsage("chat", "fast", "flash", false, 30, 3_000_000, 2_500_000, 500_000, 100_000),
            FeatureUsage("chat", "deep", "pro", true, 10, 1_000_000, 800_000, 200_000, 50_000),
            FeatureUsage("reflection", null, "pro", true, 5, 200_000, 0, 200_000, 20_000),
            FeatureUsage("letter", null, "pro", false, 1, 100_000, 0, 100_000, 10_000),
            FeatureUsage("chapter", null, "pro", false, 1, 50_000, 0, 50_000, 5_000),
            FeatureUsage("testbench", "fast", "old-model", false, 3, 10, 0, 10, 10),
        )
        val b = CostEstimator.breakdown(usage, pricing)
        val fast = (2.5 * 0.01 + 0.5 * 0.4 + 0.1 * 1.2)
        val deep = (0.8 * 0.04 + 0.2 * 1.2 + 0.05 * 3.6) * 0.5
        assertEquals(fast, b.features.first { it.feature == Feature.CHAT_FAST }.usd, 1e-9)
        assertEquals(deep, b.features.first { it.feature == Feature.CHAT_DEEP }.usd, 1e-9)
        assertEquals(Feature.UPKEEP, b.features.first { it.feature == Feature.UPKEEP }.feature)
        assertEquals(3_300_000.0 / 4_350_010.0, b.cacheHitRate!!, 1e-9)
        assertEquals(0.25, b.deepShare!!, 1e-9)
        assertFalse(b.complete)
        assertEquals(b.features.sumOf { it.usd }, b.totalUsd, 1e-12)
    }

    @Test
    fun `off-peak usage costs less in the monthly estimate`() {
        val peak = CostEstimator.estimate(listOf(ModelUsage("pro", 1, 0, 1_000_000, 0, offPeak = false)), "flash", pricing.chat, "pro", pricing.deep, 0.5)
        val off = CostEstimator.estimate(listOf(ModelUsage("pro", 1, 0, 1_000_000, 0, offPeak = true)), "flash", pricing.chat, "pro", pricing.deep, 0.5)
        assertEquals(1.2, peak.usd, 1e-9)
        assertEquals(0.6, off.usd, 1e-9)
    }

    @Test
    fun `old requests fold into monthly totals`() {
        val zone = ZoneId.of("Asia/Karachi")
        val t = Instant.parse("2026-06-10T10:00:00Z").toEpochMilli()
        val stats = listOf(
            UsageStat(1, t, "chat", "flash", 100, 60, 40, 10, "fast", false),
            UsageStat(2, t + 1000, "chat", "flash", 200, 100, 100, 20, "fast", false),
            UsageStat(3, t + 2000, "reflection", "pro", 50, 0, 50, 5, null, true),
        )
        val existing = listOf(UsageMonth("2026-06", "chat", "flash", "fast", false, 1, 10, 5, 5, 1))
        val out = UsageRollup.fold(stats, existing, zone)
        val chat = out.first { it.purpose == "chat" }
        assertEquals(3, chat.requests)
        assertEquals(310, chat.promptTokens)
        assertEquals(31, chat.completionTokens)
        assertEquals("", out.first { it.purpose == "reflection" }.tier)
        assertEquals("2026-01", UsageRollup.oldestKept(Instant.parse("2027-12-15T00:00:00Z").toEpochMilli(), zone))
        assertEquals(
            Instant.parse("2026-09-30T19:00:00Z").toEpochMilli(),
            UsageRollup.detailFrom(Instant.parse("2026-12-15T00:00:00Z").toEpochMilli(), zone),
        )
    }
}
