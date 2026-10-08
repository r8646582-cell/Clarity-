package com.umair.purpose.dev

import com.umair.purpose.cost.Prices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestBenchCostTest {
    @Test
    fun pricesEachRequestByItsModel() {
        val usage = listOf(BenchUsage("a", 1_000_000, 1_000_000, 1_000_000), BenchUsage("b", 0, 2_000_000, 0))
        val prices = mapOf("a" to Prices(0.1, 1.0, 2.0), "b" to Prices(0.0, 0.5, 0.0))
        val cost = TestBench.runCost(usage) { prices[it] }
        assertEquals(4.1, cost.usd, 1e-9)
        assertTrue(cost.complete)
    }

    @Test
    fun unknownModelCountsAsZeroAndIsMarked() {
        val cost = TestBench.runCost(listOf(BenchUsage("x", 10, 10, 10))) { null }
        assertEquals(0.0, cost.usd, 0.0)
        assertFalse(cost.complete)
    }
}
