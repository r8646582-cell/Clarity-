package com.umair.purpose.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.umair.purpose.chat.ChatPrefixCache
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.repo.PromiseRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypted export/import to a file he picks. Nothing is uploaded anywhere. */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: PurposeDatabase,
    private val prefixCache: ChatPrefixCache,
    private val promises: PromiseRepository,
    private val search: com.umair.purpose.memory.SearchIndex,
    private val prompts: com.umair.purpose.data.repo.PromptRepository,
    private val receipts: com.umair.purpose.chat.ActionReceiptStore,
) {
    suspend fun export(uri: Uri, passphrase: CharArray) = withContext(Dispatchers.IO) {
        val bytes = exportBytes(passphrase)
        writeVerified(uri, bytes)
    }

    /** Verify the provider's persisted bytes before reporting a successful encrypted backup. */
    suspend fun writeVerified(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IllegalStateException("Couldn't open that file")
        val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Couldn't verify that backup file")
        BackupIntegrity.verify(bytes, input)
    }

    /** Everything, encrypted with [passphrase]. */
    suspend fun exportBytes(passphrase: CharArray): ByteArray = withContext(Dispatchers.IO) { BackupCodec.encode(snapshot(), passphrase) }

    /**
     * UPDATE-15 "Export my life": a readable Markdown file and the same data as JSON, unencrypted, into the folder he
     * picked. Returns the two file names.
     */
    suspend fun exportLife(folder: Uri, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<String> = withContext(Dispatchers.IO) {
        val data = snapshot()
        val resolver = context.contentResolver
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(folder, android.provider.DocumentsContract.getTreeDocumentId(folder))
        val stamp = java.time.LocalDate.now(zone).toString()
        listOf(
            Triple("purpose-my-life-$stamp.md", "text/markdown", LifeExport.markdown(data, zone)),
            Triple("purpose-my-life-$stamp.json", "application/json", LifeExport.json(data)),
        ).map { (name, mime, text) ->
            val file = android.provider.DocumentsContract.createDocument(resolver, parent, mime, name)
                ?: throw IllegalStateException("Couldn't create $name")
            resolver.openOutputStream(file, "wt")?.use { it.write(text.toByteArray()) } ?: throw IllegalStateException("Couldn't write $name")
            name
        }
    }

    /** Everything in the database, read in one transaction. */
    private suspend fun snapshot(): BackupData = withContext(Dispatchers.IO) {
        db.withTransaction {
            BackupData(
                exportedAt = System.currentTimeMillis(),
                sessions = db.sessionDao().all(),
                messages = db.messageDao().all(),
                profile = db.profileDao().all(),
                people = db.personDao().all(),
                notes = db.noteDao().all(),
                promises = db.promiseDao().all(),
                areas = db.areaDao().all(),
                letters = db.letterDao().all(),
                usage = db.usageDao().all(),
                settings = db.settingsDao().get(),
                quotes = db.quoteDao().all(),
                ideas = db.ideaDao().all(),
                behaviorEvents = db.behaviorDao().all(),
                strengths = db.strengthDao().all(),
                snapshots = db.snapshotDao().all(),
                onboarding = db.onboardingDao().all(),
                journeys = db.journeyDao().all(),
                pulses = db.pulseDao().all(),
                customJourneys = db.customJourneyDao().all(),
                promptOverrides = db.promptDao().all(),
                chapters = db.chapterDao().all(),
                milestones = db.milestoneDao().all(),
                branches = db.branchDao().all(),
                journeyAdjustments = db.journeyAdjustmentDao().all(),
                usageMonths = db.usageMonthDao().all(),
                schemaVersion = SCHEMA_VERSION,
            )
        }
    }

    /** Replaces everything on this phone with the backup. All or nothing. */
    suspend fun import(uri: Uri, passphrase: CharArray) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { BackupCodec.readBounded(it, BackupCodec.MAX_FILE_BYTES) }
            ?: throw IllegalStateException("Couldn't open that file")
        restore(BackupCodec.decode(bytes, passphrase))
    }

    /** Replaces everything with [data], in one transaction: all or nothing. */
    suspend fun restore(data: BackupData) {
        if (data.schemaVersion > SCHEMA_VERSION) throw BackupTooNewException(data.schemaVersion)
        BackupValidation.check(data)
        db.datasetWork.replace { restoreCurrentDataset(data) }
    }

    private suspend fun restoreCurrentDataset(data: BackupData) = withContext(Dispatchers.IO) {
        if (data.schemaVersion > SCHEMA_VERSION) throw BackupTooNewException(data.schemaVersion)
        BackupValidation.check(data)
        val r = db.restoreDao()
        val oldReminderIds = db.withTransaction {
            val oldIds = db.promiseDao().withReminders().map { it.id }
            r.clearMessages(); r.clearSessions(); r.clearProfile(); r.clearPeople(); r.clearNotes()
            r.clearPromises(); r.clearAreas(); r.clearLetters(); r.clearUsage()
            r.clearQuotes(); r.clearIdeas(); r.clearBehavior(); r.clearStrengths(); r.clearSnapshots()
            r.clearOnboarding(); r.clearJourneys(); r.clearPulses()
            // Format-1 backups have no message counts: count his messages again.
            val userCounts = data.messages.filter { it.role == "user" }.groupingBy { it.sessionId }.eachCount()
            r.insertSessions(
                if (data.formatVersion >= 2) data.sessions
                else data.sessions.map { it.copy(userMessageCount = userCounts[it.id] ?: 0) }
            )
            // No provider is running for these imported rows. A partial reply is retryable, never stuck typing.
            r.insertMessages(data.messages.filterNot { it.streaming && it.content.isBlank() }.map {
                if (it.streaming) it.copy(status = com.umair.purpose.data.db.Message.INTERRUPTED) else it
            })
            r.insertProfile(data.profile)
            r.insertPeople(data.people)
            r.insertNotes(data.notes)
            r.insertPromises(data.promises)
            r.insertAreas(data.areas)
            r.insertLetters(data.allLetters())
            r.insertUsage(data.usage)
            r.insertQuotes(data.quotes)
            r.insertIdeas(data.ideas)
            r.insertBehavior(data.behaviorEvents)
            r.insertStrengths(data.strengths)
            r.insertSnapshots(data.snapshots)
            r.insertOnboarding(data.onboarding)
            r.insertJourneys(data.journeys)
            r.insertPulses(data.pulses)
            db.customJourneyDao().clear()
            db.customJourneyDao().insertAll(data.customJourneys)
            // Format 3 (UPDATE-15). An older backup simply has none of these.
            db.chapterDao().clear(); db.chapterDao().insertAll(data.chapters)
            db.milestoneDao().clear(); db.milestoneDao().insertAll(data.milestones)
            db.branchDao().clear(); db.branchDao().insertAll(data.branches)
            db.journeyAdjustmentDao().clear(); db.journeyAdjustmentDao().insertAll(data.journeyAdjustments)
            db.usageMonthDao().clear(); db.usageMonthDao().upsert(data.usageMonths)
            // Prompt edits come back too, and a restore replaces them like every other table: without the clear,
            // upserting only added to whatever was already on the device, so the "Edited" state afterwards did
            // not match the backup (and an older backup could never remove a newer edit).
            db.promptDao().clear()
            data.promptOverrides.forEach { db.promptDao().upsert(it) }
            data.settings?.let { db.settingsDao().upsert(it) }
            oldIds
        }
        db.datasetWork.dataReplaced()
        receipts.clear()
        prompts.changed()
        prefixCache.clear()
        promises.cancelReminders(oldReminderIds)
        promises.rescheduleReminders()
        // The archive's search index is made from the restored rows.
        search.rebuild()
    }

    companion object {
        /** Keep in step with @Database(version) in PurposeDatabase. */
        const val SCHEMA_VERSION = 10
    }
}
