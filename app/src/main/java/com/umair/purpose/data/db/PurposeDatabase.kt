package com.umair.purpose.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        Session::class, Message::class, Settings::class, UsageStat::class,
        ProfileEntry::class, Person::class, Note::class, Promise::class, AreaStatus::class, Letter::class,
        Quote::class, IdeaUsed::class, BehaviorEvent::class, Strength::class,
        Snapshot::class, OnboardingStep::class, Journey::class, Pulse::class, ErrorLog::class,
        PromptOverride::class, CustomJourney::class,
        // UPDATE-15 to 18
        Chapter::class, Milestone::class, Branch::class, JourneyAdjustment::class, UsageMonth::class, SearchDoc::class,
        // Phase 2
        EmbeddingRow::class,
        // Phase 3 ledgers
        Disagreement::class, Contradiction::class, ActionLog::class,
        // Phase 4
        ScreenUsage::class,
        // Phase 5
        JobModel::class,
    ],
    version = 15,
    exportSchema = true,
)
abstract class PurposeDatabase : RoomDatabase() {
    /** Process-local coordination; no stored column or schema change. */
    val datasetWork = DatasetWork()
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun settingsDao(): SettingsDao
    abstract fun usageDao(): UsageDao
    abstract fun profileDao(): ProfileDao
    abstract fun personDao(): PersonDao
    abstract fun noteDao(): NoteDao
    abstract fun promiseDao(): PromiseDao
    abstract fun areaDao(): AreaDao
    abstract fun letterDao(): LetterDao
    abstract fun quoteDao(): QuoteDao
    abstract fun ideaDao(): IdeaDao
    abstract fun behaviorDao(): BehaviorDao
    abstract fun strengthDao(): StrengthDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun onboardingDao(): OnboardingDao
    abstract fun journeyDao(): JourneyDao
    abstract fun pulseDao(): PulseDao
    abstract fun restoreDao(): RestoreDao
    abstract fun errorDao(): ErrorDao
    abstract fun forgetDao(): ForgetDao
    abstract fun promptDao(): PromptOverrideDao
    abstract fun customJourneyDao(): CustomJourneyDao
    abstract fun chapterDao(): ChapterDao
    abstract fun milestoneDao(): MilestoneDao
    abstract fun branchDao(): BranchDao
    abstract fun journeyAdjustmentDao(): JourneyAdjustmentDao
    abstract fun usageMonthDao(): UsageMonthDao
    abstract fun searchDao(): SearchDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun disagreementDao(): DisagreementDao
    abstract fun contradictionDao(): ContradictionDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun screenUsageDao(): ScreenUsageDao
    abstract fun jobModelDao(): JobModelDao

    companion object {
        /**
         * Every migration, oldest first. There is no destructive fallback anywhere, on purpose (UPDATE-15): a
         * missing or failing migration stops with an error and leaves his data exactly as it was.
         */
        val MIGRATIONS: Array<Migration> get() = Migrations.ALL

        /**
         * Builds the SQLCipher-encrypted database. Nothing secret is read here: the key and the native library are
         * touched on the first real open, which [DatabaseGate] wraps. A lost Keystore key then shows the error
         * screen instead of crashing every launch before the app can say anything.
         */
        fun open(context: Context, passphrase: () -> ByteArray): PurposeDatabase =
            Room.databaseBuilder(context, PurposeDatabase::class.java, "purpose.db")
                .openHelperFactory(LazySqlCipherFactory(passphrase))
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

/** Creates the real SQLCipher helper only when the database is first used (see [PurposeDatabase.open]). */
private class LazySqlCipherFactory(private val passphrase: () -> ByteArray) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper = LazyHelper(configuration) {
        System.loadLibrary("sqlcipher")
        SupportOpenHelperFactory(passphrase()).create(configuration)
    }
}

private class LazyHelper(
    configuration: SupportSQLiteOpenHelper.Configuration,
    private val make: () -> SupportSQLiteOpenHelper,
) : SupportSQLiteOpenHelper {
    override val databaseName: String? = configuration.name
    private var wal: Boolean? = null
    private val real: SupportSQLiteOpenHelper by lazy { make().also { h -> wal?.let(h::setWriteAheadLoggingEnabled) } }
    private val created = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun helper(): SupportSQLiteOpenHelper = real.also { created.set(true) }

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        if (created.get()) real.setWriteAheadLoggingEnabled(enabled) else wal = enabled
    }

    override val writableDatabase: SupportSQLiteDatabase get() = helper().writableDatabase
    override val readableDatabase: SupportSQLiteDatabase get() = helper().readableDatabase

    override fun close() {
        if (created.get()) real.close()
    }
}
