package com.umair.purpose.backup

import com.umair.purpose.data.db.AreaStatus
import com.umair.purpose.data.db.BehaviorEvent
import com.umair.purpose.data.db.IdeaUsed
import com.umair.purpose.data.db.Journey
import com.umair.purpose.data.db.Letter
import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.OnboardingStep
import com.umair.purpose.data.db.Person
import com.umair.purpose.data.db.ProfileEntry
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.Pulse
import com.umair.purpose.data.db.Quote
import com.umair.purpose.data.db.Session
import com.umair.purpose.data.db.Settings
import com.umair.purpose.data.db.Snapshot
import com.umair.purpose.data.db.Strength
import com.umair.purpose.data.db.UsageStat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Everything in the database. The API key is deliberately not included.
 * Format 1 (before letters) had `reports`; they are read as letters on restore. Format 3 (UPDATE-15) adds the
 * database schema version, chapters, the growth tree, journey adjustments and monthly usage totals.
 */
@Serializable
data class BackupData(
    val formatVersion: Int = FORMAT,
    /** The database version it came from; a newer one than this app knows is refused, never half-read. */
    val schemaVersion: Int = 0,
    val exportedAt: Long,
    val sessions: List<Session>,
    val messages: List<Message>,
    val profile: List<ProfileEntry>,
    val people: List<Person>,
    val notes: List<Note>,
    val promises: List<Promise>,
    val areas: List<AreaStatus>,
    val letters: List<Letter> = emptyList(),
    val usage: List<UsageStat>,
    val settings: Settings? = null,
    val quotes: List<Quote> = emptyList(),
    val ideas: List<IdeaUsed> = emptyList(),
    val behaviorEvents: List<BehaviorEvent> = emptyList(),
    val strengths: List<Strength> = emptyList(),
    val snapshots: List<Snapshot> = emptyList(),
    val onboarding: List<OnboardingStep> = emptyList(),
    val journeys: List<Journey> = emptyList(),
    val pulses: List<Pulse> = emptyList(),
    val customJourneys: List<com.umair.purpose.data.db.CustomJourney> = emptyList(),
    val promptOverrides: List<com.umair.purpose.data.db.PromptOverride> = emptyList(),
    val chapters: List<com.umair.purpose.data.db.Chapter> = emptyList(),
    val milestones: List<com.umair.purpose.data.db.Milestone> = emptyList(),
    val branches: List<com.umair.purpose.data.db.Branch> = emptyList(),
    val journeyAdjustments: List<com.umair.purpose.data.db.JourneyAdjustment> = emptyList(),
    val usageMonths: List<com.umair.purpose.data.db.UsageMonth> = emptyList(),
    /** Phase 3 ledgers. An older backup simply has none. */
    val disagreements: List<com.umair.purpose.data.db.Disagreement> = emptyList(),
    val contradictions: List<com.umair.purpose.data.db.Contradiction> = emptyList(),
    val actionLog: List<com.umair.purpose.data.db.ActionLog> = emptyList(),
    /** Phase 4 opt-in screen time. An older backup simply has none. */
    val screenUsage: List<com.umair.purpose.data.db.ScreenUsage> = emptyList(),
    /** Format 1 only. */
    val reports: List<LegacyReport> = emptyList(),
) {
    companion object {
        const val FORMAT = 3
    }

    /** Letters, including any from a format-1 backup (on-demand reports are skipped, as in the DB migration). */
    fun allLetters(): List<Letter> = letters + reports.filter { it.kind == Letter.MONTHLY || it.kind == Letter.YEARLY }.map { r ->
        val text = r.content.trim()
        val firstLine = text.lineSequence().first()
        Letter(
            id = r.id, kind = r.kind, periodStart = r.periodStart, periodEnd = r.periodEnd, createdAt = r.createdAt,
            title = firstLine.trim(), content = text.substring(firstLine.length).trim(), readAt = r.createdAt,
        )
    }
}

@Serializable
data class LegacyReport(
    val id: Long = 0,
    val kind: String,
    val periodStart: String,
    val periodEnd: String,
    val createdAt: Long,
    val content: String,
)

class BadPassphraseException : Exception("Wrong passphrase, or the file is damaged")

/** A backup made by a newer version of Purpose: update the app first, then restore. Nothing is changed. */
class BackupTooNewException(val schemaVersion: Int) :
    Exception("This backup is from a newer version of Purpose. Update the app, then restore it.")

/**
 * Backup file = "PURPOSE1" | salt(16) | iterations(4) | iv(12) | AES-256-GCM(gzip(json)).
 * The key comes from his passphrase via PBKDF2-HMAC-SHA256, so the file is useless without it.
 */
object BackupCodec {
    private val MAGIC = "PURPOSE1".toByteArray(Charsets.US_ASCII)
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    const val DEFAULT_ITERATIONS = 210_000
    const val MIN_PASSPHRASE = 8
    const val MAX_FILE_BYTES = 64 * 1024 * 1024
    const val MAX_JSON_BYTES = 128 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(data: BackupData, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE) { "Passphrase must be at least $MIN_PASSPHRASE characters" }
        require(iterations in 10_000..5_000_000) { "Invalid backup key iterations" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        val header = ByteBuffer.allocate(MAGIC.size + SALT_SIZE + 4 + IV_SIZE)
            .put(MAGIC).put(salt).putInt(iterations).put(iv).array()
        cipher.updateAAD(header)
        val payload = json.encodeToString(BackupData.serializer(), data).toByteArray()
        require(payload.size <= MAX_JSON_BYTES) { "The backup data is too large" }
        return (header + cipher.doFinal(gzip(payload))).also {
            require(it.size <= MAX_FILE_BYTES) { "The backup file is too large" }
        }
    }

    fun decode(file: ByteArray, passphrase: CharArray): BackupData {
        require(file.size <= MAX_FILE_BYTES) { "This backup file is too large" }
        val headerSize = MAGIC.size + SALT_SIZE + 4 + IV_SIZE
        if (file.size <= headerSize || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IllegalArgumentException("This is not a Purpose backup file")
        }
        val buf = ByteBuffer.wrap(file)
        buf.position(MAGIC.size)
        val salt = ByteArray(SALT_SIZE).also { buf.get(it) }
        val iterations = buf.int
        val iv = ByteArray(IV_SIZE).also { buf.get(it) }
        require(iterations in 10_000..5_000_000) { "This backup file is damaged" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(file, 0, headerSize)
        val plain = try {
            cipher.doFinal(file, headerSize, file.size - headerSize)
        } catch (_: AEADBadTagException) {
            throw BadPassphraseException()
        }
        return json.decodeFromString(BackupData.serializer(), gunzip(plain).decodeToString()).also {
            require(it.formatVersion in 1..BackupData.FORMAT) { "Unsupported backup format. Update Purpose before restoring." }
        }
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun gzip(b: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(b) } }.toByteArray()

    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { input ->
        readBounded(input, MAX_JSON_BYTES)
    }

    /** Stops reading before a damaged or malicious file exhausts the process heap. */
    internal fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) return out.toByteArray()
            require(count <= limit - out.size()) { "This backup file is too large" }
            out.write(chunk, 0, count)
        }
    }
}
