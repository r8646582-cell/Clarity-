package com.umair.purpose.time

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CalendarDaysTest {
    @Test fun `midnight changes emit without a database update and identical days do not repeat`() = runTest {
        var today = LocalDate.of(2026, 10, 6)
        val seen = mutableListOf<LocalDate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { calendarDays { today }.collect { seen += it } }
        advanceTimeBy(30_001)
        assertEquals(listOf(today), seen)
        today = today.plusDays(1)
        advanceTimeBy(30_000)
        assertEquals(listOf(today.minusDays(1), today), seen)
    }
    @Test fun `new subscription immediately sees current local day`() = runTest {
        val today = LocalDate.of(2026, 10, 7)
        val seen = mutableListOf<LocalDate>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { calendarDays { today }.collect { seen += it } }
        assertEquals(listOf(today), seen)
        job.cancel()
    }
}
