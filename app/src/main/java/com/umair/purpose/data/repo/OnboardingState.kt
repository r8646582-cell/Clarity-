package com.umair.purpose.data.repo

import com.umair.purpose.data.db.OnboardingStep
import com.umair.purpose.data.db.Snapshot
import com.umair.purpose.memory.OnboardingSteps
import com.umair.purpose.memory.OnboardingTopic
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class OnboardingState(
    val steps: Map<String, OnboardingStep> = emptyMap(),
    val snapshot: Snapshot? = null,
) {
    fun done(step: String) = steps[step]?.completedAt != null
    val welcomeSeen get() = steps.containsKey(OnboardingSteps.WELCOME)
    val valuesDone get() = done(OnboardingSteps.VALUES_SORT)
    val bigFiveDone get() = done(OnboardingSteps.BIG_FIVE)
    val topicsDone get() = OnboardingTopic.entries.count { done(it.step) }
    val allTopicsDone get() = topicsDone == OnboardingTopic.entries.size

    /** The next conversation step, or null when all five are done. */
    val nextTopic: OnboardingTopic? get() = OnboardingTopic.entries.firstOrNull { !done(it.step) }

    /** One conversation per day is offered; he can still do several in a row from inside a step. */
    fun topicOfferedToday(today: LocalDate, zone: ZoneId): Boolean {
        val lastDone = OnboardingTopic.entries.mapNotNull { steps[it.step]?.completedAt }.maxOrNull() ?: return true
        return Instant.ofEpochMilli(lastDone).atZone(zone).toLocalDate() != today
    }
}

/** Which steps a "done" signal really changes, and whether that finishes all five. Pure, for tests. */
object OnboardingProgress {
    fun newlyDone(state: OnboardingState, steps: Collection<String>): List<String> =
        steps.mapNotNull { OnboardingTopic.fromStep(it.trim().lowercase())?.step }.distinct().filter { !state.done(it) }

    fun justFinished(before: OnboardingState, newly: List<String>): Boolean =
        newly.isNotEmpty() && OnboardingTopic.entries.all { before.done(it.step) || it.step in newly }
}
