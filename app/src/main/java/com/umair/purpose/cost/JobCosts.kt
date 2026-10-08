package com.umair.purpose.cost

import com.umair.purpose.ai.AiJob

/**
 * Phase 5: what the slow jobs cost, from the usage the app has already logged. Actual cost uses the prices of the
 * models really used; the projection re-prices the same tokens with the prices of a model he is considering.
 * Both are estimates from this month so far, never a bill.
 */
object JobCosts {
    data class Line(
        val job: AiJob,
        val requests: Int,
        val actualUsd: Double,
        /** False when some of the usage was on a model with no known price. */
        val complete: Boolean,
    )

    private fun of(job: AiJob, usage: List<FeatureUsage>) = usage.filter { it.purpose in job.purposes }

    fun actual(job: AiJob, usage: List<FeatureUsage>, pricing: Pricing): Line {
        var usd = 0.0
        var complete = true
        val rows = of(job, usage)
        for (u in rows) {
            val c = CostEstimator.cost(u.model, u.cacheHitTokens, u.cacheMissTokens, u.completionTokens, u.offPeak, pricing)
            if (c == null) complete = false else usd += c
        }
        return Line(job, rows.sumOf { it.requests }, usd, complete)
    }

    /**
     * This month's tokens for [job] priced at [prices], or null when there is no usage to project from or the prices
     * are not known. Off-peak requests keep their off-peak discount.
     */
    fun projected(job: AiJob, usage: List<FeatureUsage>, prices: Prices?, offPeakFactor: Double): Double? {
        if (prices == null || !prices.known) return null
        val rows = of(job, usage)
        if (rows.isEmpty()) return null
        return rows.sumOf { u ->
            (u.cacheHitTokens * prices.cacheHit + u.cacheMissTokens * prices.cacheMiss + u.completionTokens * prices.output) /
                1_000_000.0 * (if (u.offPeak) offPeakFactor else 1.0)
        }
    }
}
