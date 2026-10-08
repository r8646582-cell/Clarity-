package com.umair.purpose.data.repo

import androidx.room.withTransaction
import com.umair.purpose.data.db.Journey
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.CustomJourney
import com.umair.purpose.journey.CustomDraft
import com.umair.purpose.journey.JourneyCatalog
import com.umair.purpose.journey.JourneyDesign
import com.umair.purpose.journey.JourneyPlan
import com.umair.purpose.journey.JourneyRules
import com.umair.purpose.journey.JourneyStep
import com.umair.purpose.growth.GrowthEngine
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JourneyRepository @Inject constructor(
    private val db: PurposeDatabase,
    private val prompts: PromptRepository,
    private val growth: GrowthEngine,
) {
    private val dao = db.journeyDao()
    @Volatile private var plans: List<JourneyPlan>? = null
    private var catalogGeneration = 0L

    private fun invalidateCatalog() = synchronized(this) {
        catalogGeneration++
        plans = null
    }

    init {
        // An edited journeys.md (Prompt editor) is read again.
        prompts.addOnChange { invalidateCatalog() }
    }

    /** journeys.md, then the ones made for him (custom names never clash with built-in ones). */
    suspend fun catalog(): List<JourneyPlan> {
        while (true) {
            val started = synchronized(this) {
                plans?.let { return it }
                catalogGeneration
            }
            val loaded = JourneyCatalog.parse(prompts.load("journeys.md")) +
                db.customJourneyDao().all().mapNotNull(JourneyDesign::plan)
            synchronized(this) {
                if (catalogGeneration == started) {
                    plans = loaded
                    return loaded
                }
            }
        }
    }

    fun observeAll(): Flow<List<Journey>> = dao.observeAll()

    /** Catalog changes come from prompt edits and custom plans, even without a journey-run write. */
    fun observeCatalog(): Flow<List<JourneyPlan>> = kotlinx.coroutines.flow.combine(
        db.promptDao().observeAll(), db.customJourneyDao().observeAll(),
    ) { overrides, custom ->
        JourneyCatalog.parse(overrides.firstOrNull { it.name == "journeys.md" }?.text ?: prompts.builtIn("journeys.md")) +
            custom.mapNotNull(JourneyDesign::plan)
    }

    /** "Make one for me" → Start: saved under "Yours", then started. */
    suspend fun saveCustom(d: CustomDraft, now: Long): JourneyPlan {
        val name = JourneyDesign.uniqueName(d.name, catalog().map { it.name })
        db.customJourneyDao().insert(
            CustomJourney(name = name, description = d.description, why = d.why, daysJson = JourneyDesign.encodeSteps(d.steps), createdAt = now)
        )
        invalidateCatalog()
        return plan(name)!!
    }

    /** After the last day's reflection: one line he takes away, on the finished journey. */
    suspend fun recordTakeaway(name: String?, day: Int?, summary: String?) {
        val line = JourneyDesign.takeaway(summary) ?: return
        val j = dao.all().filter { it.name == name && it.status == Journey.DONE && it.takeaway == null }
            .maxByOrNull { it.completedAt ?: it.startedAt } ?: return
        if (day != null && day < j.totalDays) return
        dao.upsert(j.copy(takeaway = line))
    }

    suspend fun plan(name: String?): JourneyPlan? = JourneyCatalog.find(catalog(), name)

    /**
     * UPDATE-18: a journey run's plan, with its adaptations: the run's own copy of the days if it was adjusted,
     * else the plan as written. Null if the journey no longer exists in the catalog and was never adapted.
     */
    suspend fun runPlan(j: Journey): JourneyPlan? {
        val written = plan(j.name)
        val steps = com.umair.purpose.journey.JourneyAdaptation.steps(j, written)
        if (steps.isEmpty()) return null
        return (written ?: JourneyPlan(j.name, "", steps)).copy(steps = steps)
    }

    /** The plan for a journey conversation: the current run's, when it's the one under way. */
    suspend fun planForSession(name: String?): JourneyPlan? {
        val current = dao.current()
        return if (current != null && current.name == name) runPlan(current) else plan(name)
    }

    fun observeCurrent(): Flow<Journey?> = dao.observeCurrent()

    suspend fun current(): Journey? = dao.current()

    /** The latest adaptation of the journey under way, for its next conversation ("adjusted: …"). */
    suspend fun adjustmentFor(name: String?): String? {
        val j = dao.current()?.takeIf { it.name == name } ?: return null
        val a = db.journeyAdjustmentDao().latest(j.id) ?: return null
        val recent = System.currentTimeMillis() - a.createdAt < 3L * 24 * 60 * 60 * 1000
        return a.reason.takeIf { recent && a.decision != "continue" && it.isNotBlank() }
    }

    fun observeLatestAdjustment(journeyId: Long): Flow<com.umair.purpose.data.db.JourneyAdjustment?> =
        db.journeyAdjustmentDao().observeLatest(journeyId)

    /** Applies an adaptation and keeps its history, in one transaction. Returns false if nothing changed. */
    suspend fun applyAdjustment(
        journeyId: Long,
        sessionDay: Int,
        result: com.umair.purpose.journey.AdaptResult,
        now: Long,
    ): Boolean = db.withTransaction {
        val j = dao.get(journeyId) ?: return@withTransaction false
        val written = plan(j.name)
        val steps = com.umair.purpose.journey.JourneyAdaptation.steps(j, written)
        val applied = com.umair.purpose.journey.JourneyAdaptation.apply(j, steps, written?.days ?: steps.size, result, now, sessionDay)
        if (applied.changed) dao.upsert(applied.journey)
        db.journeyAdjustmentDao().insert(
            com.umair.purpose.data.db.JourneyAdjustment(
                journeyId = journeyId, day = sessionDay, decision = result.decision, reason = result.reason.trim().take(300),
                changesJson = com.umair.purpose.journey.JourneyDesign.encodeSteps(
                    result.changes.map { com.umair.purpose.journey.JourneyStep(it.day, it.theme, it.explore, it.action) }
                ),
                createdAt = now,
            )
        )
        applied.changed
    }

    /** Path: "Resume" on a paused journey, from the same day. */
    suspend fun resume() {
        dao.current()?.takeIf { it.status == Journey.PAUSED }?.let { dao.upsert(com.umair.purpose.journey.JourneyAdaptation.resume(it)) }
    }

    suspend fun pause(now: Long) {
        dao.current()?.takeIf { it.status == Journey.ACTIVE }?.let { dao.upsert(it.copy(status = Journey.PAUSED, pausedAt = now)) }
    }

    /** Only a journey that really exists, by its exact name (any case). */
    suspend fun planExact(name: String?): JourneyPlan? = JourneyCatalog.exact(catalog(), name)

    fun observeActive(): Flow<Journey?> = dao.observeActive()

    suspend fun active(): Journey? = dao.active()

    /**
     * The journey being run right now, and nothing else. Strictly `status == active` and not archived, so a
     * stopped, paused, finished or archived run returns null rather than being mistaken for the live one.
     */
    suspend fun getActiveJourney(): Journey? =
        dao.active()?.takeIf { it.status == Journey.ACTIVE && !it.isArchived }

    /** `archive_journey`: put a run aside so it never counts as active again. False if unknown or already archived. */
    suspend fun archive(journeyId: Long): Boolean {
        val j = dao.get(journeyId) ?: return false
        if (j.isArchived) return false
        dao.upsert(j.copy(status = Journey.STOPPED))
        return true
    }

    /**
     * `replace_journey`: swap the journey under way for a new plan built from [title] and [steps]. The old run is
     * stopped and kept in history; the new plan is saved (reusing a custom journey of the same name, so repeated
     * replaces don't pile up "Title (2)", "Title (3)") and started. Null when there is nothing to start.
     */
    suspend fun replace(title: String, steps: List<JourneyStep>, description: String, now: Long): Journey? {
        if (steps.isEmpty()) return null
        val cleanTitle = title.trim().take(60).ifEmpty { "My journey" }
        val existing = db.customJourneyDao().all().firstOrNull { it.name.equals(cleanTitle, ignoreCase = true) }
        val name = existing?.name ?: JourneyDesign.uniqueName(cleanTitle, catalog().map { it.name })
        db.withTransaction {
            // Only the run under way is replaced; finished and already-stopped history stays as it is.
            dao.current()?.takeIf { !it.isArchived }?.let { dao.upsert(it.copy(status = Journey.STOPPED)) }
            if (existing != null) db.customJourneyDao().delete(existing.id)
            db.customJourneyDao().insert(
                CustomJourney(
                    name = name,
                    description = description.trim(),
                    why = "",
                    daysJson = JourneyDesign.encodeSteps(steps),
                    createdAt = now,
                )
            )
        }
        invalidateCatalog()
        return start(name, now)
    }

    /** One active journey at a time: starting one stops any other. Returns null if the name isn't in the catalog. */
    suspend fun start(name: String, now: Long): Journey? {
        val plan = plan(name) ?: return null
        return db.withTransaction {
            dao.current()?.let {
                when {
                    it.name == plan.name && it.status == Journey.ACTIVE -> return@withTransaction it
                    // Starting the paused one again resumes it, from the same day.
                    it.name == plan.name -> return@withTransaction com.umair.purpose.journey.JourneyAdaptation.resume(it).also { r -> dao.upsert(r) }
                    else -> dao.upsert(it.copy(status = Journey.STOPPED))
                }
            }
            val j = Journey(name = plan.name, startedAt = now, currentDay = 1, totalDays = plan.days, status = Journey.ACTIVE)
            j.copy(id = dao.insert(j))
        }
    }

    suspend fun stop() {
        dao.current()?.let { dao.upsert(it.copy(status = Journey.STOPPED)) }
    }

    /** After a journey session about [day] with real talk in it. In one transaction, so two can't both count. */
    suspend fun advance(name: String?, day: Int?, today: LocalDate): Boolean {
        var changed = false
        val finished = db.withTransaction {
            val j = dao.active() ?: return@withTransaction null
            if (name != null && j.name != name) return@withTransaction null
            val next = JourneyRules.advance(j, today, System.currentTimeMillis(), forDay = day)
            changed = next != j
            if (changed) dao.upsert(next)
            next.name.takeIf { next.status == Journey.DONE && j.status != Journey.DONE }
        }
        // UPDATE-18: a finished journey earns a leaf proposal deterministically, not through the gated AI run.
        if (finished != null) growth.recordJourneyCompletion(finished)
        return changed
    }

    /** For the chat context: the journey, the day, and today's step line. */
    suspend fun contextLine(today: LocalDate): String? {
        val j = dao.current() ?: return null
        if (j.status == Journey.PAUSED) return "${j.name}, paused on day ${j.currentDay.coerceAtMost(j.totalDays)} of ${j.totalDays} (he paused it; don't push)."
        val plan = runPlan(j) ?: return null
        val day = j.currentDay.coerceAtMost(j.totalDays)
        val stepDone = !JourneyRules.stepAvailableToday(j, today)
        val step = plan.step(day) ?: return null
        return "${j.name}, day $day of ${j.totalDays}" + (if (stepDone) " (today's step is done; next one tomorrow)" else "") +
            ". Step: ${step.line()}"
    }
}
