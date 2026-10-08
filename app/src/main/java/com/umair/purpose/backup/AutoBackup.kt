package com.umair.purpose.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UiPrefs
import com.umair.purpose.security.SecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Weekly encrypted backup into a folder he picked (Storage Access Framework, so a local folder or Google Drive).
 * Keeps the last [KEEP] automatic backups and deletes older ones. Never touches other files in the folder.
 */
@Singleton
class AutoBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backups: BackupRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val uiPrefs: UiPrefs,
) {
    /** Returns false when it isn't set up (off, no folder, or no passphrase). */
    suspend fun run(): Boolean = withContext(Dispatchers.IO) {
        val s = settings.get()
        val folder = s.backupFolder?.takeIf { s.autoBackup }?.let(Uri::parse) ?: return@withContext false
        val passphrase = secrets.backupPassphrase() ?: return@withContext false
        val bytes = backups.exportBytes(passphrase)
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val name = PREFIX + LocalDateTime.now().format(STAMP) + ".pbak"
        val file = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
            ?: throw IllegalStateException("Couldn't create the backup file")
        try {
            backups.writeVerified(file, bytes)
        } catch (e: Exception) {
            // Never prune healthy recovery copies after a partial or unverifiable new write.
            runCatching { DocumentsContract.deleteDocument(resolver, file) }
            throw e
        }
        prune(folder)
        uiPrefs.lastBackupAt = System.currentTimeMillis()
        true
    }

    /** Oldest automatic backups beyond the newest [KEEP] go. The names sort by time. */
    private fun prune(folder: Uri) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val found = mutableListOf<Pair<String, String>>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(PREFIX) && name.endsWith(".pbak")) found += id to name
            }
        }
        found.sortedByDescending { it.second }.drop(KEEP).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(folder, id)) }
        }
    }

    companion object {
        const val KEEP = 4
        const val PREFIX = "purpose-auto-"
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss-SSS")
    }
}
