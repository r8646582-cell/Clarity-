package com.umair.purpose.chat

import com.umair.purpose.data.db.Journey
import com.umair.purpose.data.db.Session
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** UPDATE-12 audit: continuing a conversation brings back its mode only while that mode is still going. */
class ModeResumeTest {
    private val today = java.time.LocalDate.of(2026, 10, 3)
    private fun s(mode: Mode?, detail: String? = null, day: Int? = null) =
        Session(id = 5, startedAt = 0, endedAt = 1, mode = mode?.wire, modeDetail = detail, journeyDay = day)
    private val journey = Journey(1, "Break the avoidance loop", 0, currentDay = 3, totalDays = 7, status = Journey.ACTIVE, lastStepDate = "2026-10-02")

    @Test
    fun `a plain conversation and session-only modes come back`() {
        assertTrue(ModeResume.keep(s(null), false, null, today))
        assertTrue(ModeResume.keep(s(Mode.PRACTICE, "Abbu"), false, null, today))
        assertTrue(ModeResume.keep(s(Mode.DECISION), false, null, today))
        assertTrue(ModeResume.keep(s(Mode.UNTANGLE), false, null, today))
    }

    @Test
    fun `an onboarding step that's done doesn't come back`() {
        assertFalse(ModeResume.keep(s(Mode.ONBOARDING, "story"), stepDone = true, journey = null, today = today))
        assertTrue(ModeResume.keep(s(Mode.ONBOARDING, "people"), stepDone = false, journey = null, today = today))
    }

    @Test
    fun `a journey comes back only for the step still to do today`() {
        assertTrue(ModeResume.keep(s(Mode.JOURNEY, journey.name, 3), false, journey, today))
        // That day's step is past (the journey moved on), the step was done today, or the journey ended.
        assertFalse(ModeResume.keep(s(Mode.JOURNEY, journey.name, 2), false, journey, today))
        assertFalse(ModeResume.keep(s(Mode.JOURNEY, journey.name, 3), false, journey.copy(lastStepDate = today.toString()), today))
        assertFalse(ModeResume.keep(s(Mode.JOURNEY, journey.name, 3), false, null, today))
        assertFalse(ModeResume.keep(s(Mode.JOURNEY, "Another journey", 3), false, journey, today))
    }
}

class MidnightTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private fun at(h: Int, m: Int, day: Int = 2) = LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a conversation still going at midnight isn't cut in two`() {
        // A reply landed at 23:58; he answers at 00:03.
        assertFalse(SessionPolicy.isStale(at(23, 58), at(0, 3, day = 3), zone))
    }

    @Test
    fun `a pause across midnight still ends it`() {
        assertTrue(SessionPolicy.isStale(at(23, 40), at(0, 5, day = 3), zone))
        assertTrue(SessionPolicy.isStale(at(23, 0), at(9, 0, day = 3), zone))
    }
}
