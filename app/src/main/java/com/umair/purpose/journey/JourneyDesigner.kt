package com.umair.purpose.journey

import com.umair.purpose.ai.AiClient
import com.umair.purpose.ai.AiMessage
import com.umair.purpose.ai.AiMessage.Role
import com.umair.purpose.ai.AiRequest
import com.umair.purpose.data.db.Journey
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.repo.JourneyRepository
import com.umair.purpose.data.repo.MemoryRepository
import com.umair.purpose.data.repo.PromptRepository
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UiPrefs
import com.umair.purpose.data.repo.UsageRepository
import com.umair.purpose.memory.ContextFormatter
import com.umair.purpose.prompt.Templates
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** "Make one for me" (journey_custom.md, Pro model) and "Suggested for you" (Fast model, once a week). */
@Singleton
class JourneyDesigner @Inject constructor(
    private val db: PurposeDatabase,
    private val journeys: JourneyRepository,
    private val memory: MemoryRepository,
    private val prompts: PromptRepository,
    private val settings: SettingsRepository,
    private val usage: UsageRepository,
    private val uiPrefs: UiPrefs,
    private val ai: AiClient,
) {
    /** A 7-day journey just for him, optionally from what he typed. Throws on failure; nothing is saved yet. */
    suspend fun design(request: String?, zone: ZoneId = ZoneId.systemDefault()): CustomDraft = db.datasetWork.withWriter { designCurrentDataset(request, zone) }

    private suspend fun designCurrentDataset(request: String?, zone: ZoneId): CustomDraft {
        val context = ContextFormatter.portraitContext(memory.memory(), zone) +
            db.sessionDao().recentSummarized(5).let { s -> ContextFormatter.recentSummaries(s, zone)?.let { "\n\n$it" } ?: "" }
        val prompt = Templates.fill(
            prompts.load("journey_custom.md"),
            mapOf(
                "CONTEXT" to context,
                "REQUEST" to (request?.trim()?.takeIf { it.isNotEmpty() }?.let { "He asked for: $it" } ?: "He didn't ask for anything specific."),
            ),
        )
        val cfg = settings.get().ai
        val r = AiRequest(
            model = cfg.deepModel,
            messages = listOf(AiMessage(Role.SYSTEM, prompt), AiMessage(Role.USER, "Design the journey now and return the JSON object.")),
            temperature = cfg.chatTemperature,
            jsonOutput = true,
        )
        val c = ai.complete(r)
        c.usage?.let { usage.log("journey", r.model, it, System.currentTimeMillis()) }
        return JourneyDesign.parseCustom(c.text)
    }

    /** Cached picks, fresh enough: a week old at most, and no journey finished since. */
    fun cached(doneCount: Int, now: Long = System.currentTimeMillis()): List<Suggestion>? {
        val fresh = now - uiPrefs.suggestionsAt < WEEK_MS && uiPrefs.suggestionsDoneCount == doneCount
        return if (fresh) JourneyDesign.decodeSuggestions(uiPrefs.suggestionsJson) else null
    }

    /** Asks the Fast model which journeys fit him now; after a finished journey, the natural next one first. */
    suspend fun suggest(zone: ZoneId = ZoneId.systemDefault()): List<Suggestion> = db.datasetWork.withWriter { suggestCurrentDataset(zone) }

    private suspend fun suggestCurrentDataset(zone: ZoneId): List<Suggestion> {
        val all = db.journeyDao().all()
        val done = all.filter { it.status == Journey.DONE }
        cached(done.size)?.let { return it }
        val catalog = journeys.catalog()
        val justCompleted = done.maxByOrNull { it.completedAt ?: 0 }?.takeIf { d ->
            all.none { it.status == Journey.ACTIVE && it.startedAt > (d.completedAt ?: 0) }
        }?.name
        val known = memory.memory()
        val context = listOfNotNull(
            ContextFormatter.noteLines(known.notes.filter { it.status == com.umair.purpose.data.db.Note.ACTIVE }, "pattern").takeIf { it.isNotEmpty() }
                ?.let { "Patterns:\n" + it.joinToString("\n") },
            known.areas.filter { it.status == "stuck" }.takeIf { it.isNotEmpty() }
                ?.let { "Stuck life areas:\n" + ContextFormatter.areaLines(it).joinToString("\n") },
        ).joinToString("\n\n")
        val cfg = settings.get().ai
        val r = AiRequest(
            model = cfg.chatModel,
            messages = listOf(
                AiMessage(Role.SYSTEM, JourneyDesign.suggestionPrompt(catalog, context, done.map { it.name }.distinct(), justCompleted)),
                AiMessage(Role.USER, "Pick now and return the JSON object."),
            ),
            temperature = cfg.reflectionTemperature,
            thinking = false,
            jsonOutput = true,
        )
        val c = ai.complete(r)
        c.usage?.let { usage.log("journey", r.model, it, System.currentTimeMillis(), "fast") }
        val active = all.firstOrNull { it.status == Journey.ACTIVE }?.name
        val picks = JourneyDesign.parseSuggestions(c.text, catalog, setOfNotNull(active))
        uiPrefs.suggestionsJson = JourneyDesign.encodeSuggestions(picks)
        uiPrefs.suggestionsAt = System.currentTimeMillis()
        uiPrefs.suggestionsDoneCount = done.size
        return picks
    }

    private companion object {
        const val WEEK_MS = 7L * 24 * 3600 * 1000
    }
}
