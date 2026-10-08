package com.umair.purpose.chat

import com.umair.purpose.data.db.Session
import com.umair.purpose.data.repo.JourneyRepository
import com.umair.purpose.data.repo.MemoryRepository
import com.umair.purpose.data.repo.PromiseRepository
import com.umair.purpose.data.repo.UiPrefs
import com.umair.purpose.dev.ErrorLogger
import com.umair.purpose.growth.GrowthEngine
import com.umair.purpose.journey.CustomDraft
import com.umair.purpose.journey.JourneyStep
import com.umair.purpose.promise.PromiseTimes
import com.umair.purpose.promise.Due
import com.umair.purpose.system.Haptics
import com.umair.purpose.ui.talk.RevealPacer
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** The outcome of one autonomous action, for the receipt fed back to the coach and the error log. */
data class ActionReceipt(val type: String, val ok: Boolean, val detail: String, val wantsReminder: Boolean = false, val promise: com.umair.purpose.data.db.Promise? = null) {
    fun encode(): String = "$type=${if (ok) detail else "failed:$detail"}"
}

/**
 * UPDATE-20: runs the hidden `[[action: …]]` blocks a reply asked for, after the reply is complete. Every database
 * touch goes through the existing repositories, so the same rules apply as everywhere else. Each action is wrapped
 * in try-catch: a failure is logged (status only, never content) and never breaks the reply or the next action.
 *
 * Off-the-record conversations keep nothing, so memory-affecting actions are skipped there — except a reminder he
 * explicitly asked for, exactly as CLAUDE.md requires.
 */
@Singleton
class ActionExecutor @Inject constructor(
    private val journeys: JourneyRepository,
    private val promises: PromiseRepository,
    private val memory: MemoryRepository,
    private val growth: GrowthEngine,
    private val uiPrefs: UiPrefs,
    private val errors: ErrorLogger,
) {
    suspend fun execute(actions: List<ToolCall>, session: Session, messageId: Long, zone: ZoneId, now: Long): List<ActionReceipt> =
        actions.distinct().mapNotNull { action -> run(action, session, messageId, zone, now) }

    private suspend fun run(action: ToolCall, session: Session, messageId: Long, zone: ZoneId, now: Long): ActionReceipt? {
        if (session.offTheRecord && action.kind !in setOf("record_promise", "set_pacing", "set_cadence")) {
            return ActionReceipt(action.kind, ok = false, detail = "off_the_record")
        }
        if (!action.known) {
            errors.logParse("action", "Unknown tool action: ${action.kind}", null)
            return ActionReceipt(action.kind.ifBlank { "unknown" }, ok = false, detail = "unknown")
        }
        return try {
            when (action.kind) {
                "create_journey" -> createJourney(action, session, now)
                "replace_journey" -> replaceJourney(action, session, now)
                "archive_journey" -> archiveJourney(action)
                "advance_journey" -> advanceJourney(session, zone, now)
                "record_promise" -> recordPromise(action, session, messageId, zone, now)
                "resolve_promise" -> resolvePromise(action, session, now)
                "reschedule_promise" -> reschedulePromise(action, zone, now)
                "edit_promise" -> editPromise(action, zone, now)
                "synthesize_memory", "store_memory" -> storeMemory(action, session, now)
                "trigger_milestone", "award_milestone" -> awardMilestone(action, session, now)
                "set_pacing", "set_cadence" -> setCadence(action)
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.log("action", e)
            ActionReceipt(action.kind, ok = false, detail = "failed")
        }
    }

    private suspend fun createJourney(action: ToolCall, session: Session, now: Long): ActionReceipt {
        if (session.offTheRecord) return ActionReceipt("create_journey", ok = false, detail = "off_the_record")
        val title = action.title?.trim().orEmpty().take(60)
        if (title.isEmpty()) return ActionReceipt("create_journey", ok = false, detail = "no_title")
        val stages = action.stages.map { it.trim() }.filter { it.isNotEmpty() }.take(14)
        if (stages.size < 5) return ActionReceipt("create_journey", ok = false, detail = "too_few_stages")
        val steps = stages.mapIndexed { i, theme -> JourneyStep(i + 1, theme.take(80), "", "") }
        val description = action.description?.trim().orEmpty()
        val plan = journeys.saveCustom(CustomDraft(title, description, description, steps), now)
        val started = journeys.start(plan.name, now)
        return if (started != null) ActionReceipt("create_journey", ok = true, detail = started.name)
        else ActionReceipt("create_journey", ok = false, detail = "not_started")
    }

    /** `replace_journey`: swap the journey under way for a new plan (the old run is stopped). */
    private suspend fun replaceJourney(action: ToolCall, session: Session, now: Long): ActionReceipt {
        if (session.offTheRecord) return ActionReceipt("replace_journey", ok = false, detail = "off_the_record")
        val title = action.title?.trim().orEmpty().take(60)
        if (title.isEmpty()) return ActionReceipt("replace_journey", ok = false, detail = "no_title")
        val stages = action.stages.map { it.trim() }.filter { it.isNotEmpty() }.take(14)
        if (stages.size < 5) return ActionReceipt("replace_journey", ok = false, detail = "too_few_stages")
        val steps = stages.mapIndexed { i, theme -> JourneyStep(i + 1, theme.take(80), "", "") }
        val started = journeys.replace(title, steps, action.description?.trim().orEmpty(), now)
        return if (started != null) ActionReceipt("replace_journey", ok = true, detail = started.name)
        else ActionReceipt("replace_journey", ok = false, detail = "not_started")
    }

    /** `archive_journey`: put a run aside so it no longer counts as active. */
    private suspend fun archiveJourney(action: ToolCall): ActionReceipt {
        val id = action.journeyId ?: return ActionReceipt("archive_journey", ok = false, detail = "no_id")
        val ok = journeys.archive(id)
        return ActionReceipt("archive_journey", ok = ok, detail = if (ok) "stopped" else "not_found")
    }

    /** `advance_journey`: mark today's step of the journey this session is about as done (never more than once a day). */
    private suspend fun advanceJourney(session: Session, zone: ZoneId, now: Long): ActionReceipt {
        if (session.offTheRecord) return ActionReceipt("advance_journey", ok = false, detail = "off_the_record")
        if (Mode.fromWire(session.mode) != Mode.JOURNEY) return ActionReceipt("advance_journey", ok = false, detail = "not_a_journey")
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val advanced = journeys.advance(session.modeDetail, session.journeyDay, today)
        return if (advanced) ActionReceipt("advance_journey", ok = true, detail = session.modeDetail.orEmpty())
        else ActionReceipt("advance_journey", ok = false, detail = "not_advanced")
    }

    private suspend fun recordPromise(action: ToolCall, session: Session, messageId: Long, zone: ZoneId, now: Long): ActionReceipt {
        val title = action.title?.trim().orEmpty()
        if (title.isEmpty()) return ActionReceipt("record_promise", ok = false, detail = "no_title")
        val clock = Instant.ofEpochMilli(now).atZone(zone)
        val due = PromiseTimes.resolve(action.due, action.dueEpochMs, clock)
        if ((action.due != null || action.dueEpochMs != null) && due == null)
            return ActionReceipt("record_promise", false, "invalid_due")
        val reminderDue = PromiseTimes.resolve(action.remind, action.reminderEpoch, clock)
        val reminder = reminderDue?.let { PromiseTimes.epoch(it, zone) }
        if ((action.remind != null || action.reminderEpoch != null) && reminder == null)
            return ActionReceipt("record_promise", false, "invalid_reminder")
        // Off the record keeps nothing, except the one reminder he explicitly asked for.
        if (session.offTheRecord && reminder == null) return ActionReceipt("record_promise", ok = false, detail = "off_the_record")
        val saved = promises.saveFromTool(
            title = title,
            reminderEpoch = reminder,
            due = due?.toString(),
            tags = action.tags,
            sessionId = session.id.takeIf { it > 0 },
            messageId = messageId.takeIf { it > 0 },
            zone = zone,
            now = now,
        )
        return if (saved != null) ActionReceipt("record_promise", ok = true, detail = "id=${saved.id}, due=${saved.dueAt ?: "none"}", wantsReminder = saved.remindAt != null, promise = saved)
        else ActionReceipt("record_promise", ok = false, detail = "not_saved")
    }

    private suspend fun resolvePromise(action: ToolCall, session: Session, now: Long): ActionReceipt {
        val rawStatus = action.status?.trim()?.lowercase().orEmpty()
        val normalizedStatus = when (rawStatus) {
            "let_go", "letgo", "void", "abandoned" -> "DROPPED"
            "done", "completed" -> "KEPT"
            "failed" -> "BROKEN"
            else -> rawStatus.uppercase()
        }
        if (normalizedStatus.isEmpty()) return ActionReceipt("resolve_promise", ok = false, detail = "no_status")
        if (action.promiseId == null && action.title.isNullOrBlank()) return ActionReceipt("resolve_promise", ok = false, detail = "no_id")
        val resolved = promises.resolveFromTool(action.promiseId, action.title, normalizedStatus, now)
            ?: return ActionReceipt("resolve_promise", ok = false, detail = "not_open")
        // UPDATE-22: a kept promise is real evidence. Feed it to the growth engine, exactly like the
        // autonomous award_milestone action, so the tree starts sprouting the moment he keeps his word.
        // Off-the-record doesn't feed the tree, but the promise itself still resolves above. A growth
        // failure must never make a successful resolution look failed.
        if (normalizedStatus == "KEPT" && !session.offTheRecord) {
            try {
                growth.recordEvidence("Promises", "Kept: ${action.title ?: "Promise"}", now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.log("growth", e)
            }
        }
        return ActionReceipt("resolve_promise", ok = true, detail = normalizedStatus.lowercase(), promise = resolved)
    }

    /** `reschedule_promise`: move an open promise's due time. [ToolCall.title] is the fallback match when no id. */
    private suspend fun reschedulePromise(action: ToolCall, zone: ZoneId, now: Long): ActionReceipt {
        val due = PromiseTimes.resolve(action.newDue, action.newDueEpochMs, Instant.ofEpochMilli(now).atZone(zone))
            ?: return ActionReceipt("reschedule_promise", ok = false, detail = "invalid_due")
        val updated = promises.rescheduleFromTool(action.promiseId, action.title, due, zone)
        return if (updated != null) ActionReceipt("reschedule_promise", ok = true, detail = "id=${updated.id}, due=${updated.dueAt ?: "none"}", promise = updated)
        else ActionReceipt("reschedule_promise", ok = false, detail = "not_found")
    }

    /** `edit_promise`: change an open promise's wording and/or due time. Either field may stand alone. */
    private suspend fun editPromise(action: ToolCall, zone: ZoneId, now: Long): ActionReceipt {
        val due = PromiseTimes.resolve(action.newDue, action.newDueEpochMs, Instant.ofEpochMilli(now).atZone(zone))
        if ((action.newDue != null || action.newDueEpochMs != null) && due == null)
            return ActionReceipt("edit_promise", false, "invalid_due")
        val newTitle = action.newTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (due == null && newTitle == null) return ActionReceipt("edit_promise", ok = false, detail = "nothing_to_change")
        val updated = promises.editFromTool(action.promiseId, action.title, newTitle, due, zone)
        return if (updated != null) ActionReceipt("edit_promise", ok = true, detail = "id=${updated.id}, due=${updated.dueAt ?: "none"}", promise = updated)
        else ActionReceipt("edit_promise", ok = false, detail = "not_found")
    }

    private suspend fun storeMemory(action: ToolCall, session: Session, now: Long): ActionReceipt {
        if (session.offTheRecord) return ActionReceipt("store_memory", ok = false, detail = "off_the_record")
        val insight = action.insight?.trim().orEmpty()
        if (insight.isEmpty()) return ActionReceipt("store_memory", ok = false, detail = "no_insight")
        val note = memory.insertMemory(insight, action.category, action.emotionalValence, session.id.takeIf { it > 0 }, now, action.replacesIds)
        return if (note != null) ActionReceipt("store_memory", ok = true, detail = note.type)
        else ActionReceipt("store_memory", ok = false, detail = "not_saved")
    }

    private suspend fun awardMilestone(action: ToolCall, session: Session, now: Long): ActionReceipt {
        if (session.offTheRecord) return ActionReceipt("award_milestone", ok = false, detail = "off_the_record")
        val key = action.actionKey?.trim()?.ifEmpty { action.milestoneKey?.trim() }.orEmpty()
        val proof = action.evidence?.trim()?.ifEmpty { action.evidenceSummary?.trim() }.orEmpty()
        if (key.isEmpty() && proof.isEmpty()) return ActionReceipt("award_milestone", ok = false, detail = "no_evidence")
        val m = growth.recordEvidence(key, proof, now)
        return if (m != null) ActionReceipt("award_milestone", ok = true, detail = m.title)
        else ActionReceipt("award_milestone", ok = false, detail = "not_earned")
    }

    private fun setCadence(action: ToolCall): ActionReceipt {
        val pace = action.paceMultiplier?.coerceIn(0.5, 2.0) ?: 1.0
        val raw = action.hapticPulse?.trim()?.ifEmpty { action.hapticLevel?.trim() }?.lowercase().orEmpty()
        val level = when {
            raw.isEmpty() -> "balanced"
            raw == "crisp" -> "firm"
            raw == "gentle" -> "gentle"
            raw == "off" || raw == "none" || raw == "silent" -> "off"
            raw in HAPTIC_LEVELS -> raw
            else -> "balanced"
        }
        uiPrefs.paceMultiplier = pace.toFloat()
        uiPrefs.hapticLevel = level
        Haptics.enabled = level != "off"
        // The reveal speed is read live by StreamingText's pacer, so a cadence change is felt immediately.
        RevealPacer.speed = pace
        return ActionReceipt("set_cadence", ok = true, detail = "pace=$pace, haptic=$level")
    }

    private companion object {
        val HAPTIC_LEVELS = setOf("gentle", "balanced", "firm", "off")
    }
}

/**
 * UPDATE-20: the last actions the coach took, kept so the next request can carry a short receipt in its runtime
 * flags. That closes the loop without a second API call: the coach sees what it actually did and can confirm it.
 */
@Singleton
class ActionReceiptStore @Inject constructor() {
    private val latest = java.util.concurrent.atomic.AtomicReference<Pair<Long, String>?>(null)

    // Recent UI receipts live only in memory, including off-the-record results. Never write them to preferences.
    private val _messages = kotlinx.coroutines.flow.MutableStateFlow<Map<Pair<Long, Long>, List<ActionReceipt>>>(emptyMap())
    val messages: kotlinx.coroutines.flow.StateFlow<Map<Pair<Long, Long>, List<ActionReceipt>>> = _messages

    @Volatile var generation: Long = 0
        private set

    @Synchronized
    fun record(sessionId: Long, receipts: List<ActionReceipt>, messageId: Long? = null, expectedGeneration: Long = generation) {
        if (receipts.isEmpty() || expectedGeneration != generation) return
        latest.set(sessionId to receipts.joinToString("; ") { it.encode() }.take(400))
        if (messageId != null) {
            val next = LinkedHashMap(_messages.value)
            next[sessionId to messageId] = receipts.toList()
            while (next.size > 100) next.remove(next.keys.first())
            _messages.value = next
        }
    }

    @Synchronized
    fun clear() {
        generation++
        latest.set(null)
        _messages.value = emptyMap()
    }

    @Synchronized
    fun forgetSession(sessionId: Long) {
        latest.get()?.takeIf { it.first == sessionId }?.let { latest.set(null) }
        _messages.value = _messages.value.filterKeys { it.first != sessionId }
    }

    /** Reading cannot consume it: request preparation may be followed by a network failure. */
    fun forSession(sessionId: Long): String? = latest.get()?.takeIf { it.first == sessionId }?.second

}
