package com.umair.purpose.security

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two secrets: the database passphrase (generated on first run) and the user's API key.
 * Both are encrypted with a Keystore key before touching disk.
 */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("purpose_secrets", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher()

    private val _hasApiKey = MutableStateFlow(prefs.contains(KEY_API))
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    /** Hex-encoded 32 random bytes, created once and reused forever after. */
    @Synchronized
    fun dbPassphrase(): ByteArray {
        // An existing but unreadable key is not a first install. Never overwrite the only key to his data.
        if (prefs.contains(KEY_DB)) {
            return read(KEY_DB) ?: throw IllegalStateException("The saved database key could not be opened")
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val passphrase = raw.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
        write(KEY_DB, passphrase)
        return passphrase
    }

    fun apiKey(): String? = read(KEY_API)?.toString(Charsets.UTF_8)

    fun setApiKey(value: String) {
        write(KEY_API, value.trim().toByteArray(Charsets.UTF_8))
        _hasApiKey.value = true
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API).apply()
        _hasApiKey.value = false
    }

    /** UPDATE-16: the backup AI provider's key. */
    fun backupApiKey(): String? = read(KEY_BACKUP_API)?.toString(Charsets.UTF_8)

    fun setBackupApiKey(value: String) = write(KEY_BACKUP_API, value.trim().toByteArray(Charsets.UTF_8))

    fun clearBackupApiKey() {
        prefs.edit().remove(KEY_BACKUP_API).apply()
    }

    /** The passphrase for automatic backups, kept once he turns them on. */
    fun backupPassphrase(): CharArray? = read(KEY_BACKUP)?.toString(Charsets.UTF_8)?.toCharArray()

    fun setBackupPassphrase(value: String) = write(KEY_BACKUP, value.toByteArray(Charsets.UTF_8))

    fun clearBackupPassphrase() {
        prefs.edit().remove(KEY_BACKUP).apply()
    }

    private fun read(name: String): ByteArray? =
        runCatching {
            prefs.getString(name, null)?.let { cipher.decrypt(Base64.decode(it, Base64.NO_WRAP)) }
        }.getOrNull()

    private fun write(name: String, value: ByteArray) {
        val encoded = Base64.encodeToString(cipher.encrypt(value), Base64.NO_WRAP)
        // commit(), not apply(): losing the DB passphrase would lose every conversation.
        check(prefs.edit().putString(name, encoded).commit()) { "Could not save secret" }
    }

    private companion object {
        const val KEY_DB = "db_passphrase"
        const val KEY_API = "api_key"
        const val KEY_BACKUP = "backup_passphrase"
        const val KEY_BACKUP_API = "backup_api_key"
    }
}
