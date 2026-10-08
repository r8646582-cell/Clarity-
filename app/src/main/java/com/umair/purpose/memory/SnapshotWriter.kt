package com.umair.purpose.memory

import com.umair.purpose.ai.AiClient
import com.umair.purpose.ai.AiException
import com.umair.purpose.ai.AiMessage
import com.umair.purpose.ai.AiMessage.Role
import com.umair.purpose.ai.AiRequest
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.Snapshot
import com.umair.purpose.data.repo.MemoryRepository
import com.umair.purpose.data.repo.PromptRepository
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UsageRepository
import com.umair.purpose.letter.LetterPlanning
import com.umair.purpose.prompt.Templates
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Writes his snapshot with snapshot.md, after the five onboarding conversations. At most once a month on request. */
@Singleton
class SnapshotWriter @Inject constructor(
    private val db: PurposeDatabase,
    private val memory: MemoryRepository,
    private val prompts: PromptRepository,
    private val settings: SettingsRepository,
    private val usage: UsageRepository,
    private val ai: AiClient,
) {
    suspend fun canRefresh(now: Long): Boolean {
        val last = db.snapshotDao().latest() ?: return true
        return Instant.ofEpochMilli(last.createdAt).plus(30, ChronoUnit.DAYS).toEpochMilli() <= now
    }

    suspend fun write(zone: ZoneId = ZoneId.systemDefault()): Long = db.datasetWork.withWriter { writeCurrentDataset(zone) }

    private suspend fun writeCurrentDataset(zone: ZoneId): Long {
        val steps = db.onboardingDao().all().associateBy { it.step }
        val bigFive = steps[OnboardingSteps.BIG_FIVE]?.data ?: "{}"
        val values = steps[OnboardingSteps.VALUES_SORT]?.data ?: "[]"
        val transcripts = OnboardingTopic.entries.mapNotNull { topic ->
            val sessionId = steps[topic.step]?.sessionId ?: return@mapNotNull null
            val msgs = db.messageDao().forSession(sessionId)
            if (msgs.isEmpty()) null else "${topic.title}:\n${ContextFormatter.transcript(msgs)}"
        }
        val prompt = Templates.fill(
            prompts.load("snapshot.md"),
            mapOf(
                "BIG_FIVE" to (OnboardingFormat.bigFiveLine(bigFive) ?: "(not taken)"),
                "VALUES" to (OnboardingFormat.valuesLine(values) ?: "(not picked)"),
                "CONTEXT" to ContextFormatter.portraitContext(memory.memory(), zone),
                "TRANSCRIPTS" to transcripts.joinToString("\n\n=====\n\n").ifEmpty { "(none)" },
            ),
        )
        val appSettings = settings.get()
        val cfg = appSettings.ai
        val jobModel = appSettings.jobModel(com.umair.purpose.ai.AiJob.SNAPSHOT)
        val request = AiRequest(
            model = jobModel.model,
            useBackup = jobModel.useBackup,
            messages = listOf(AiMessage(Role.SYSTEM, prompt), AiMessage(Role.USER, "Write the snapshot now.")),
            temperature = cfg.chatTemperature,
            thinking = true,
        )
        val c = ai.complete(request)
        val now = System.currentTimeMillis()
        c.usage?.let { usage.log("snapshot", request.model, it, now) }
        val (title, portrait) = LetterPlanning.split(c.text.ifBlank { throw AiException("The snapshot came back empty") })
        return db.snapshotDao().insert(
            Snapshot(createdAt = now, bigFiveJson = bigFive, valuesJson = values, title = title, portrait = portrait)
        )
    }
}
