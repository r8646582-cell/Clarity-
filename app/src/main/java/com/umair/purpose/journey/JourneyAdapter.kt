package com.umair.purpose.journey

import com.umair.purpose.ai.AiClient
import com.umair.purpose.ai.AiDefaults
import com.umair.purpose.ai.AiMessage
import com.umair.purpose.ai.AiMessage.Role
import com.umair.purpose.ai.AiRequest
import com.umair.purpose.chat.Mode
import com.umair.purpose.data.db.Journey
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.repo.JourneyRepository
import com.umair.purpose.data.repo.MemoryRepository
import com.umair.purpose.data.repo.PromptRepository
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UsageRepository
import com.umair.purpose.memory.ContextFormatter
import com.umair.purpose.prompt.Templates
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UPDATE-18 "Adaptive journeys": after each journey conversation is reflected on (and after a missed day),
 * prompts/journey_adapt.md (the fast model, JSON) decides whether the rest of the run should change. The decision
 * and its reason are stored; Path shows the reason, and the next journey conversation hears it.
 */
@Singleton
class JourneyAdapter @Inject constructor(
    private val db: PurposeDatabase,
    private val journeys: JourneyRepository,
    private val memory: MemoryRepository,
    private val prompts: PromptRepository,
    private val settings: SettingsRepository,
    private val usage: UsageRepository,
    private val ai: AiClient,
) {
    /** Adapts the journey under way if a conversation or a missed day hasn't been considered yet. */
    suspend fun adaptPending(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) = db.datasetWork.withWriter { adaptCurrentDataset(now, zone) }

    private suspend fun adaptCurrentDataset(now: Long, zone: ZoneId) {
        val j = journeys.current()?.takeIf { it.status == Journey.ACTIVE } ?: return
        val adjustments = db.journeyAdjustmentDao().forJourney(j.id)
        val sessions = db.sessionDao().all().filter {
            it.mode == Mode.JOURNEY.wire && it.modeDetail == j.name && it.startedAt >= j.startedAt && it.userMessageCount > 0
        }
        val latestSession = sessions.maxByOrNull { it.startedAt }
        val lastAdjusted = adjustments.maxOfOrNull { it.createdAt } ?: 0L
        val today = LocalDate.now(zone)
        when {
            // A journey conversation that's been reflected on and not yet considered.
            latestSession != null && latestSession.reflected && latestSession.startedAt > lastAdjusted -> {
                val day = latestSession.journeyDay ?: (j.currentDay - 1).coerceAtLeast(1)
                adapt(j, day, todayFor(j, latestSession, sessions, zone), now, zone)
            }
            // A missed day: no step (and no adjustment) for 2+ days. At most once every 2 days, and a pause stops it.
            else -> {
                val lastActivity = maxOf(latestSession?.startedAt ?: j.startedAt, lastAdjusted)
                val idle = ChronoUnit.DAYS.between(ContextFormatter.date(lastActivity, zone), today)
                val stepDoneToday = j.lastStepDate == today.toString()
                if (idle >= MISSED_DAYS && !stepDoneToday) {
                    adapt(j, j.currentDay, "Day ${j.currentDay}. He hasn't done a journey step for $idle days (last activity ${ContextFormatter.date(lastActivity, zone)}). No conversation about it since.", now, zone)
                }
            }
        }
    }

    private suspend fun todayFor(j: Journey, s: com.umair.purpose.data.db.Session, all: List<com.umair.purpose.data.db.Session>, zone: ZoneId): String {
        val previous = all.filter { it.startedAt < s.startedAt }.maxByOrNull { it.startedAt }
        val yesterday = previous?.let { p -> db.promiseDao().all().filter { it.sourceSessionId == p.id } }.orEmpty()
        val action = when {
            previous == null -> "This was the first step."
            yesterday.isEmpty() -> "No action was saved from the previous step."
            yesterday.any { it.status == Promise.KEPT } -> "The previous step's action was kept."
            yesterday.any { it.status == Promise.BROKEN } -> "The previous step's action was not done (broken)."
            else -> "The previous step's action is still open: no word on whether it happened."
        }
        return "Day ${s.journeyDay ?: "?"} of ${j.totalDays}, on ${ContextFormatter.date(s.startedAt, zone)}. " +
            "How it went: ${s.summary?.trim() ?: "(no summary)"} His tone: ${s.tone ?: "unknown"}. $action " +
            "Steps done so far: ${(j.currentDay - 1).coerceAtLeast(0)} of ${j.totalDays}."
    }

    private suspend fun adapt(j: Journey, sessionDay: Int, today: String, now: Long, zone: ZoneId) {
        val plan = journeys.runPlan(j) ?: return
        val history = db.journeyAdjustmentDao().forJourney(j.id)
        val journeyText = buildString {
            append(j.name).append(": ").append(plan.description.ifBlank { "(no description)" }).append('\n')
            append("Days (").append(plan.days).append(" now; next to do: day ").append(j.currentDay).append("):\n")
            plan.steps.forEach { st ->
                append("- ").append(st.line()).append(if (st.day < j.currentDay) " [done]" else "").append('\n')
            }
            if (history.isNotEmpty()) {
                append("Past adjustments:\n")
                history.forEach { append("- day ${it.day}: ${it.decision} (${it.reason})\n") }
            }
        }
        val known = memory.memory()
        val context = ContextFormatter.portraitContext(known, zone) + "\n\nLife areas:\n" +
            ContextFormatter.areaLines(known.areas).joinToString("\n").ifEmpty { "(none yet)" }
        val cfg = settings.get().ai
        val request = AiRequest(
            model = cfg.chatModel,
            messages = listOf(
                AiMessage(Role.SYSTEM, Templates.fill(prompts.load("journey_adapt.md"), mapOf("JOURNEY" to journeyText, "TODAY" to today, "CONTEXT" to context))),
                AiMessage(Role.USER, "Decide now and return the JSON object."),
            ),
            temperature = cfg.reflectionTemperature,
            thinking = false,
            jsonOutput = true,
            maxTokens = AiDefaults.JOURNEY_ADAPT_MAX_TOKENS,
        )
        val c = ai.complete(request)
        c.usage?.let { usage.log("journey_adapt", request.model, it, System.currentTimeMillis()) }
        // An unreadable answer changes nothing: the plan simply continues.
        val result = JourneyAdaptation.parse(c.text) ?: AdaptResult("continue", "")
        journeys.applyAdjustment(j.id, sessionDay, result, now)
    }

    companion object {
        /** No step for this many days counts as a missed day worth adapting to. */
        const val MISSED_DAYS = 2L
    }
}
