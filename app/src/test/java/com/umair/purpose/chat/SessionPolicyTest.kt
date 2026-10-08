package com.umair.purpose.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SessionPolicyTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private fun at(h: Int, m: Int, day: Int = 2) =
        LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a short pause keeps the session`() {
        assertFalse(SessionPolicy.isStale(at(14, 0), at(14, 29), zone))
    }

    @Test
    fun `thirty minutes of quiet ends it`() {
        assertTrue(SessionPolicy.isStale(at(14, 0), at(14, 30), zone))
    }

    @Test
    fun `crossing midnight ends it after a pause`() {
        assertTrue(SessionPolicy.isStale(at(23, 50), at(0, 5, day = 3), zone))
    }
}
