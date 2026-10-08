package com.umair.purpose.cost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CostEstimatorTest {
    private val chat = Prices(cacheHit = 0.1, cacheMiss = 1.0, output = 2.0)
    private val deep = Prices(cacheHit = 0.5, cacheMiss = 4.0, output = 8.0)

    @Test
    fun `prices each model by its tier`() {
        val e = CostEstimator.estimate(
            listOf(ModelUsage("flash", 10, 1_000_000, 500_000, 250_000), ModelUsage("pro", 1, 0, 100_000, 50_000)),
            "flash", chat, "pro", deep,
        )
        assertEquals(0.1 + 0.5 + 0.5 + 0.4 + 0.4, e.usd, 1e-9)
        assertTrue(e.complete)
    }

    @Test
    fun `unknown prices or old model names make the estimate incomplete`() {
        val e = CostEstimator.estimate(
            listOf(ModelUsage("flash", 1, 0, 1_000_000, 0), ModelUsage("old-model", 1, 0, 1_000_000, 0)),
            "flash", chat, "pro", Prices(0.0, 0.0, 0.0),
        )
        assertEquals(1.0, e.usd, 1e-9)
        assertFalse(e.complete)
    }
}
