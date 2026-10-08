package com.umair.purpose.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.umair.purpose.ai.AiDefaults
import com.umair.purpose.chat.Conversations

/**
 * Every schema change, as a written migration. His life's data lives here: never a destructive fallback, and every
 * step is tested against the schema Room exports (MigrationTest).
 */
object Migrations {
    val ALL: Array<Migration> by lazy {
        arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
        )
    }

    /** The old DeepSeek prices (USD per 1M tokens), replaced in 8 → 9 only where he never changed them. */
    private const val OLD_HIT = 0.028
    private const val OLD_MISS = 0.28
    private const val OLD_OUT = 0.42

    /** The wrong Flash prices 8 → 9 wrote in (a mangled copy of the pro row); corrected in 9 → 10 if untouched. */
    private const val BAD_HIT = 0.014
    private const val BAD_MISS = 0.44
    private const val BAD_OUT = 1.32

    /**
     * Phase 2, bitemporal facts: when a note or profile line was recorded, when it started being true and when it
     * stopped (null = still true). Existing rows are backfilled from what the app already knew: recorded and valid
     * from when first seen, and a retired or resolved one stopped being true when it was last seen. Only adds.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (table in listOf("note", "profile_entry")) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `recordedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `validFrom` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `validTo` INTEGER")
            }
            db.execSQL("UPDATE `note` SET `recordedAt` = `firstSeen`, `validFrom` = `firstSeen`")
            db.execSQL("UPDATE `note` SET `validTo` = `lastSeen` WHERE `status` IN ('retired', 'resolved')")
            db.execSQL("UPDATE `profile_entry` SET `recordedAt` = `updatedAt`, `validFrom` = `updatedAt`")
            db.execSQL("UPDATE `profile_entry` SET `validTo` = `updatedAt` WHERE `retired` = 1")
        }
    }

    /**
     * The Flash prices 8 → 9 wrote were wrong — 0.014 / 0.44 / 1.32 is a mangled copy of the pro row, and roughly
     * 2.3x too high on cache hits — which inflated every cost estimate and made the monthly budget reach 100%
     * early, silently routing all chat to Fast. Prices he set himself are left alone.
     *
     * Also: promises saved in an off-the-record conversation are marked, so their words stay out of the context
     * block of later requests (the reminder he asked for still fires), and his own edits or deletions of life-area
     * statuses are protected from reflection, which may neither overwrite nor re-create them.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "UPDATE `settings` SET `chatPriceCacheHit` = 0.006, `chatPriceCacheMiss` = 0.30, `chatPriceOutput` = 1.20 " +
                    "WHERE `chatPriceCacheHit` = $BAD_HIT AND `chatPriceCacheMiss` = $BAD_MISS AND `chatPriceOutput` = $BAD_OUT"
            )
            db.execSQL("ALTER TABLE `promise` ADD COLUMN `offTheRecord` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `area_status` ADD COLUMN `editedByUser` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `area_status` ADD COLUMN `deletedByUser` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Updates 13 to 18, one step: indexes for years of data, the archive search index, life chapters, the growth
     * tree, adaptive journeys, monthly usage totals, a backup provider, off-peak pricing and the yearly checks.
     * Only adds; nothing is dropped or rewritten except prices still at the old defaults.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // New columns
            db.execSQL("ALTER TABLE `promise` ADD COLUMN `actionKey` TEXT")
            db.execSQL("ALTER TABLE `profile_entry` ADD COLUMN `retired` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `strength` ADD COLUMN `retired` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `journey` ADD COLUMN `stepsJson` TEXT")
            db.execSQL("ALTER TABLE `journey` ADD COLUMN `pausedAt` INTEGER")
            db.execSQL("ALTER TABLE `prompt_override` ADD COLUMN `builtInHash` TEXT")
            db.execSQL("ALTER TABLE `usage_stat` ADD COLUMN `offPeak` INTEGER NOT NULL DEFAULT 0")
            listOf(
                "`backupProvider` TEXT NOT NULL DEFAULT ''", "`backupBaseUrl` TEXT NOT NULL DEFAULT ''",
                "`backupChatModel` TEXT NOT NULL DEFAULT ''", "`backupDeepModel` TEXT NOT NULL DEFAULT ''",
                "`offPeakEnabled` INTEGER NOT NULL DEFAULT 1", "`peakWindows` TEXT NOT NULL DEFAULT '01:00-04:00,06:00-10:00'",
                "`peakWeekdaysOnly` INTEGER NOT NULL DEFAULT 1", "`offPeakFactor` REAL NOT NULL DEFAULT 0.5",
                "`lastRestoreTestAt` INTEGER", "`keystoreConfirmedAt` INTEGER", "`providerKeyExpiry` TEXT",
            ).forEach { db.execSQL("ALTER TABLE `settings` ADD COLUMN $it") }

            // DeepSeek's prices changed (Aug 2026). Prices he set himself stay as they are. Written out, not read
            // from AiDefaults, so this step does the same thing whenever it runs.
            db.execSQL(
                "UPDATE `settings` SET `chatPriceCacheHit` = 0.014, `chatPriceCacheMiss` = 0.44, `chatPriceOutput` = 1.32 " +
                    "WHERE `chatPriceCacheHit` = $OLD_HIT AND `chatPriceCacheMiss` = $OLD_MISS AND `chatPriceOutput` = $OLD_OUT"
            )
            db.execSQL(
                "UPDATE `settings` SET `deepPriceCacheHit` = 0.044, `deepPriceCacheMiss` = 1.32, `deepPriceOutput` = 3.96 " +
                    "WHERE `deepPriceCacheHit` = $OLD_HIT AND `deepPriceCacheMiss` = $OLD_MISS AND `deepPriceOutput` = $OLD_OUT"
            )

            // New tables
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chapter` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `periodStart` TEXT NOT NULL, " +
                    "`periodEnd` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `readAt` INTEGER)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `milestone` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, " +
                    "`area` TEXT NOT NULL, `branch` TEXT, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `evidenceJson` TEXT NOT NULL, " +
                    "`confidence` TEXT NOT NULL, `proposedAt` INTEGER NOT NULL, `decidedAt` INTEGER, `status` TEXT NOT NULL, " +
                    "`snoozeUntil` INTEGER, `quote` TEXT)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `branch` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `area` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `journey_adjustment` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`journeyId` INTEGER NOT NULL, `day` INTEGER NOT NULL, `decision` TEXT NOT NULL, `reason` TEXT NOT NULL, " +
                    "`changesJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `usage_month` (`month` TEXT NOT NULL, `purpose` TEXT NOT NULL, `model` TEXT NOT NULL, " +
                    "`tier` TEXT NOT NULL, `offPeak` INTEGER NOT NULL, `requests` INTEGER NOT NULL, `promptTokens` INTEGER NOT NULL, " +
                    "`cacheHitTokens` INTEGER NOT NULL, `cacheMissTokens` INTEGER NOT NULL, `completionTokens` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`month`, `purpose`, `model`, `tier`, `offPeak`))"
            )
            // Filled from the real tables on first launch (SearchIndex.rebuild): it holds nothing of its own.
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `search_doc` USING FTS4(`kind` TEXT NOT NULL, `refId` TEXT NOT NULL, " +
                    "`day` TEXT NOT NULL, `text` TEXT NOT NULL, notindexed=`kind`, notindexed=`refId`, notindexed=`day`)"
            )

            // Indexes, so every screen stays quick after years
            listOf(
                "session" to "startedAt", "session" to "endedAt",
                "message" to "createdAt", "message" to "status",
                "note" to "status", "note" to "type",
                "promise" to "sourceSessionId", "promise" to "createdAt",
                "letter" to "kind", "letter" to "periodEnd",
                "quote" to "sessionId", "quote" to "createdAt",
                "idea_used" to "sessionId", "idea_used" to "createdAt",
                "behavior_event" to "sessionId", "behavior_event" to "createdAt",
                "strength" to "sessionId",
                "journey" to "status",
                "chapter" to "periodEnd",
                "milestone" to "status", "milestone" to "area",
                "journey_adjustment" to "journeyId",
            ).forEach { (table, col) ->
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_$col` ON `$table` (`$col`)")
            }
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_branch_area_name` ON `branch` (`area`, `name`)")
        }
    }

    /** Update 10: prompt editor overrides, custom journeys, and a completion date and takeaway on journeys. */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `journey` ADD COLUMN `completedAt` INTEGER")
            db.execSQL("ALTER TABLE `journey` ADD COLUMN `takeaway` TEXT")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `prompt_override` (`name` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`name`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `custom_journey` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `description` TEXT NOT NULL, `why` TEXT NOT NULL, `daysJson` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL)"
            )
        }
    }

    /** Update 9: replies are written as they stream, so every existing message is complete. */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `message` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'complete'")
        }
    }

    /**
     * Update 7: conversations he can return to. Titles are backfilled from the first words of his first
     * message (or the summary), and reflected sessions are marked as reflected up to their last message.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `session` ADD COLUMN `title` TEXT")
            db.execSQL("ALTER TABLE `session` ADD COLUMN `titleByUser` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `session` ADD COLUMN `reflectedUpToMessageId` INTEGER")
            db.execSQL(
                "UPDATE `session` SET `reflectedUpToMessageId` = " +
                    "(SELECT MAX(`id`) FROM `message` WHERE `message`.`sessionId` = `session`.`id`) WHERE `reflected` = 1"
            )
            val titles = mutableListOf<Pair<Long, String>>()
            db.query(
                "SELECT s.`id`, (SELECT m.`content` FROM `message` m WHERE m.`sessionId` = s.`id` AND m.`role` = 'user' " +
                    "ORDER BY m.`createdAt`, m.`id` LIMIT 1), s.`summary` FROM `session` s"
            ).use { c ->
                while (c.moveToNext()) {
                    val first = if (c.isNull(1)) null else c.getString(1)
                    val summary = if (c.isNull(2)) null else c.getString(2)
                    val title = first?.let(Conversations::titleFrom)
                        ?: summary?.lineSequence()?.firstOrNull()?.let(Conversations::titleFrom)
                    if (title != null) titles += c.getLong(0) to title
                }
            }
            titles.forEach { (id, title) -> db.execSQL("UPDATE `session` SET `title` = ? WHERE `id` = ?", arrayOf<Any>(title, id)) }
        }
    }

    /**
     * Update 6: message details, the error log, where each memory came from (unknown = empty, which means
     * "keep" when a conversation is forgotten), the monthly budget, and automatic backup.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf(
                "`tier` TEXT", "`model` TEXT", "`thinking` INTEGER NOT NULL DEFAULT 0", "`inputTokens` INTEGER",
                "`outputTokens` INTEGER", "`firstTokenMs` INTEGER", "`totalMs` INTEGER",
            ).forEach { db.execSQL("ALTER TABLE `message` ADD COLUMN $it") }
            listOf("note", "profile_entry", "person").forEach {
                db.execSQL("ALTER TABLE `$it` ADD COLUMN `sourceSessionIds` TEXT NOT NULL DEFAULT ''")
            }
            listOf(
                "`monthlyBudget` REAL NOT NULL DEFAULT 5.0", "`helpNumbersEdited` INTEGER NOT NULL DEFAULT 0",
                "`autoBackup` INTEGER NOT NULL DEFAULT 0", "`backupFolder` TEXT",
            ).forEach { db.execSQL("ALTER TABLE `settings` ADD COLUMN $it") }
            // Numbers he had already typed in count as his own.
            db.execSQL("UPDATE `settings` SET `helpNumbersEdited` = 1 WHERE `helpNumbers` != ''")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `error_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `source` TEXT NOT NULL, `model` TEXT, `httpCode` INTEGER, " +
                    "`errorType` TEXT NOT NULL, `errorBody` TEXT, `estimatedInputTokens` INTEGER)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_error_log_createdAt` ON `error_log` (`createdAt`)")
        }
    }

    /** Update 5: model routing (the "always deep" setting, and which tier answered each chat request). */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `settings` ADD COLUMN `alwaysDeep` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `usage_stat` ADD COLUMN `tier` TEXT")
        }
    }

    /** Phase 1 → memory, promises, reports, and per-tier prices. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `profile_entry` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, `editedByUser` INTEGER NOT NULL, `deletedByUser` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`key`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `person` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `relation` TEXT, `notes` TEXT, `updatedAt` INTEGER NOT NULL, " +
                    "`editedByUser` INTEGER NOT NULL, `deletedByUser` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `note` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`type` TEXT NOT NULL, `text` TEXT NOT NULL, `confidence` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`timesSeen` INTEGER NOT NULL, `firstSeen` INTEGER NOT NULL, `lastSeen` INTEGER NOT NULL, " +
                    "`editedByUser` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `promise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `dueDate` TEXT, `status` TEXT NOT NULL, " +
                    "`sourceSessionId` INTEGER, `resolvedAt` INTEGER)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_promise_status` ON `promise` (`status`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `area_status` (`area` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`note` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`area`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `report` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`kind` TEXT NOT NULL, `periodStart` TEXT NOT NULL, `periodEnd` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `content` TEXT NOT NULL)"
            )
            listOf(
                "chatPriceCacheHit", "chatPriceCacheMiss", "chatPriceOutput",
                "deepPriceCacheHit", "deepPriceCacheMiss", "deepPriceOutput",
            ).forEach { db.execSQL("ALTER TABLE `settings` ADD COLUMN `$it` REAL NOT NULL DEFAULT 0") }
        }
    }

    /**
     * Updates 1–4: letters replace reports, richer sessions and promises, and the new memory tables
     * (quotes, ideas, behavior events, strengths, snapshot, onboarding, journeys, pulse). Keeps all data,
     * except on-demand test reports, which no longer exist as a kind.
     */
    /** Spaces, tabs and line breaks, for SQLite's trim(), which otherwise strips spaces only. */
    private const val WS = "' ' || char(9) || char(10) || char(13)"

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Session
            listOf(
                "`significance` INTEGER", "`tone` TEXT", "`userMessageCount` INTEGER NOT NULL DEFAULT 0",
                "`mode` TEXT", "`modeDetail` TEXT", "`journeyDay` INTEGER", "`letterId` INTEGER",
            ).forEach { db.execSQL("ALTER TABLE `session` ADD COLUMN $it") }
            db.execSQL(
                "UPDATE `session` SET `userMessageCount` = " +
                    "(SELECT COUNT(*) FROM `message` WHERE `message`.`sessionId` = `session`.`id` AND `message`.`role` = 'user')"
            )

            // Promise: dueDate (a date) becomes dueAt (a date or a date-time); old values stay valid.
            db.execSQL("ALTER TABLE `promise` RENAME COLUMN `dueDate` TO `dueAt`")
            listOf(
                "`why` TEXT", "`remindAt` INTEGER", "`sourceMessageId` INTEGER",
                "`whatHappened` TEXT", "`lesson` TEXT", "`area` TEXT",
            ).forEach { db.execSQL("ALTER TABLE `promise` ADD COLUMN $it") }

            // Settings
            listOf(
                "`theme` TEXT NOT NULL DEFAULT 'night'", "`lockEnabled` INTEGER NOT NULL DEFAULT 1",
                "`hideInRecents` INTEGER NOT NULL DEFAULT 1", "`voiceLanguage` TEXT NOT NULL DEFAULT 'default'",
                "`pulseEnabled` INTEGER NOT NULL DEFAULT 0", "`readAloud` INTEGER NOT NULL DEFAULT 0",
                "`helpNumbers` TEXT NOT NULL DEFAULT ''", "`nextOpening` TEXT",
            ).forEach { db.execSQL("ALTER TABLE `settings` ADD COLUMN $it") }
            // The old default chat temperature was 1.0; the new one is 0.7.
            db.execSQL("UPDATE `settings` SET `chatTemperature` = 0.7 WHERE `chatTemperature` = 1.0")
            val c = AiDefaults.CHAT_PRICES
            val d = AiDefaults.DEEP_PRICES
            db.execSQL(
                "UPDATE `settings` SET `chatPriceCacheHit` = ${c.cacheHit}, `chatPriceCacheMiss` = ${c.cacheMiss}, " +
                    "`chatPriceOutput` = ${c.output} WHERE `chatPriceCacheMiss` = 0 AND `chatPriceOutput` = 0"
            )
            db.execSQL(
                "UPDATE `settings` SET `deepPriceCacheHit` = ${d.cacheHit}, `deepPriceCacheMiss` = ${d.cacheMiss}, " +
                    "`deepPriceOutput` = ${d.output} WHERE `deepPriceCacheMiss` = 0 AND `deepPriceOutput` = 0"
            )

            // Report → Letter. The first line of a report was its title.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `letter` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`kind` TEXT NOT NULL, `periodStart` TEXT NOT NULL, `periodEnd` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `readAt` INTEGER)"
            )
            db.execSQL(
                """INSERT INTO `letter` (`id`, `kind`, `periodStart`, `periodEnd`, `createdAt`, `title`, `content`, `readAt`)
                   SELECT `id`, `kind`, `periodStart`, `periodEnd`, `createdAt`,
                          CASE WHEN instr(`c`, char(10)) > 0 THEN trim(substr(`c`, 1, instr(`c`, char(10)) - 1), $WS) ELSE `c` END,
                          CASE WHEN instr(`c`, char(10)) > 0 THEN trim(substr(`c`, instr(`c`, char(10)) + 1), $WS) ELSE '' END,
                          `createdAt`
                   FROM (SELECT *, trim(`content`, $WS) AS `c` FROM `report` WHERE `kind` IN ('monthly', 'yearly'))"""
            )
            db.execSQL("DROP TABLE IF EXISTS `report`")

            // New tables
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `quote` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sessionId` INTEGER NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `idea_used` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sessionId` INTEGER NOT NULL, `tag` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `behavior_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sessionId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `whenText` TEXT, `situation` TEXT, " +
                    "`feelingBefore` TEXT, `action` TEXT, `payoff` TEXT, `outcome` TEXT, `deletedByUser` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `strength` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sessionId` INTEGER NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`editedByUser` INTEGER NOT NULL, `deletedByUser` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `snapshot` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `bigFiveJson` TEXT NOT NULL, `valuesJson` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `portrait` TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `onboarding_step` (`step` TEXT NOT NULL, `sessionId` INTEGER, " +
                    "`completedAt` INTEGER, `data` TEXT, PRIMARY KEY(`step`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `journey` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `currentDay` INTEGER NOT NULL, " +
                    "`totalDays` INTEGER NOT NULL, `status` TEXT NOT NULL, `lastStepDate` TEXT)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `pulse` (`date` TEXT NOT NULL, `mood` INTEGER NOT NULL, " +
                    "`energy` INTEGER NOT NULL, `word` TEXT, PRIMARY KEY(`date`))"
            )
        }
    }
}
