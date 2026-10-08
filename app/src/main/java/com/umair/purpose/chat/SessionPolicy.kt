package com.umair.purpose.chat

import com.umair.purpose.data.db.Journey
import com.umair.purpose.data.db.Session
import com.umair.purpose.journey.JourneyRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** When a conversation is over: "End" tapped, 30 minutes of quiet, or a new day. */
object SessionPolicy {
    const val INACTIVITY_LIMIT_MS = 30 * 60 * 1000L

    /**
     * A new day ends a conversation only after this much quiet, so one still going at midnight (he's mid-thought,
     * or a reply just landed) isn't cut in two (UPDATE-12).
     */
    const val NEW_DAY_QUIET_MS = 10 * 60 * 1000L

    /** True if a session whose last activity was at [lastActivityAt] should be ended at [now]. */
    fun isStale(lastActivityAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val quiet = now - lastActivityAt
        if (quiet >= INACTIVITY_LIMIT_MS) return true
        val lastDay = Instant.ofEpochMilli(lastActivityAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return lastDay != today && quiet >= NEW_DAY_QUIET_MS
    }
}

/** Continuing a past conversation (UPDATE-12): its mode comes back only if that mode is still going. */
object ModeResume {
    /**
     * [stepDone]: the session's onboarding step is already done. [journey]: the active journey, if any.
     * Practice, decision and untangle belong to the conversation itself, so they always come back.
     */
    fun keep(session: Session, stepDone: Boolean, journey: Journey?, today: LocalDate): Boolean =
        when (Mode.fromWire(session.mode)) {
            null -> true
            Mode.ONBOARDING -> !stepDone
            // Only the step it was about, and only while that step is still today's to do.
            Mode.JOURNEY -> journey != null && journey.name == session.modeDetail &&
                journey.currentDay == (session.journeyDay ?: 1) && JourneyRules.stepAvailableToday(journey, today)
            else -> true
        }
}
