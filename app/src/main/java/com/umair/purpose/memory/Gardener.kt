package com.umair.purpose.memory

import androidx.room.withTransaction
import com.umair.purpose.ai.AiClient
import com.umair.purpose.ai.AiMessage
import com.umair.purpose.ai.AiMessage.Role
import com.umair.purpose.ai.AiRequest
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.repo.PromptRepository
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UsageRepository
import com.umair.purpose.prompt.Templates
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Monthly memory gardening (prompts/gardening.md): merges, rewrites and retirements, in one transaction. */
@Singleton
class Gardener @Inject constructor(
    private val db: PurposeDatabase,
    private val prompts: PromptRepository,
    private val settings: SettingsRepository,
    private val usage: UsageRepository,
    private val ai: AiClient,
    private val search: SearchIndex,
) {
    /** Throws ReflectionParseException or AiException; the worker retries. */
    suspend fun tend(zone: ZoneId = ZoneId.systemDefault()) = db.datasetWork.withWriter { tendCurrentDataset(zone) }

    private suspend fun tendCurrentDataset(zone: ZoneId) {
        val notes = db.noteDao().recentMemories(Int.MAX_VALUE)
        val people = db.personDao().all()
        val profile = db.profileDao().all()
        val strengths = db.strengthDao().all()
        // Nothing to tidy at all. (Profile and strengths count too: they also need rewriting to "you".)
        if (notes.none { it.status == com.umair.purpose.data.db.Note.ACTIVE } && people.count { !it.deletedByUser } < 2 &&
            profile.none { !it.deletedByUser && !it.editedByUser && !it.retired } && strengths.none { !it.deletedByUser && !it.retired }
        ) return
        val since = LocalDate.now(zone).minusMonths(3).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessions = db.sessionDao().all().filter { it.startedAt >= since }
        val appSettings = settings.get()
        val cfg = appSettings.ai
        val jobModel = appSettings.jobModel(com.umair.purpose.ai.AiJob.GARDENING)
        val request = AiRequest(
            model = jobModel.model,
            useBackup = jobModel.useBackup,
            messages = listOf(
                AiMessage(
                    Role.SYSTEM,
                    Templates.fill(
                        prompts.load("gardening.md"),
                        mapOf(
                            "NOTES" to Gardening.notesBlock(notes, zone),
                            "STRENGTHS" to Gardening.strengthsBlock(strengths),
                            "PROFILE" to Gardening.profileBlock(profile),
                            "PEOPLE" to Gardening.peopleBlock(people),
                            "SUMMARIES" to Gardening.summariesBlock(sessions, zone),
                        ),
                    ),
                ),
                // UPDATE-15: the caps, so merging does the work before anything has to be archived.
                AiMessage(Role.USER, "Tend the notes now and return the JSON object. ${MemoryCaps.describe()}"),
            ),
            temperature = cfg.reflectionTemperature,
            jsonOutput = true,
            maxTokens = com.umair.purpose.ai.AiDefaults.GARDENING_MAX_TOKENS,
        )
        val c = ai.complete(request)
        c.usage?.let { usage.log("gardening", request.model, it, System.currentTimeMillis()) }
        val result = Gardening.parse(c.text)
        db.withTransaction {
            // Planned from a fresh read, so an edit he made meanwhile wins.
            val plan = Gardening.plan(result, db.noteDao().recentMemories(Int.MAX_VALUE), db.strengthDao().all(), db.personDao().all(), db.profileDao().all())
            if (plan.notes.isNotEmpty()) db.noteDao().upsert(plan.notes)
            if (plan.strengths.isNotEmpty()) db.strengthDao().upsertAll(plan.strengths)
            if (plan.deleteStrengthIds.isNotEmpty()) db.strengthDao().delete(plan.deleteStrengthIds)
            if (plan.people.isNotEmpty()) db.personDao().upsert(plan.people)
            if (plan.deletePeopleIds.isNotEmpty()) db.personDao().delete(plan.deletePeopleIds)
            if (plan.profile.isNotEmpty()) db.profileDao().upsert(plan.profile)
            enforceCapsLocked()
        }
        search.indexArchive(zone)
    }

    /**
     * Whatever is still over a cap goes to the archive (kept, searchable, never sent): the weakest first, never
     * anything he edited. Runs inside the caller's transaction.
     */
    private suspend fun enforceCapsLocked() {
        val over = MemoryCaps.overflow(db.noteDao().recentMemories(Int.MAX_VALUE), db.strengthDao().all(), db.profileDao().all())
        if (over.notes.isNotEmpty()) db.noteDao().upsert(over.notes)
        if (over.strengths.isNotEmpty()) db.strengthDao().upsertAll(over.strengths)
        if (over.profile.isNotEmpty()) db.profileDao().upsert(over.profile)
    }

    /** Over a cap without gardening (no key yet, or nothing for the model to merge): just archive the weakest. */
    suspend fun enforceCaps(zone: ZoneId = ZoneId.systemDefault()) = db.datasetWork.withWriter { enforceCurrentDatasetCaps(zone) }

    private suspend fun enforceCurrentDatasetCaps(zone: ZoneId) {
        db.withTransaction { enforceCapsLocked() }
        search.indexArchive(zone)
    }

    suspend fun overCaps(): Boolean = MemoryCaps.over(db.noteDao().recentMemories(Int.MAX_VALUE), db.strengthDao().all(), db.profileDao().all())
}
