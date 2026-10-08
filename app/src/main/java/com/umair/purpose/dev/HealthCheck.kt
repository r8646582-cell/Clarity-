package com.umair.purpose.dev

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** How one background job last went. */
data class JobRecord(val lastRunAt: Long?, val lastOk: Boolean?, val lastOkAt: Long?)

/** Everything the Health check looks at, gathered on the device. No content, only counts and times. */
data class HealthInputs(
    val now: Long,
    val zone: ZoneId,
    val reflection: JobRecord,
    val sessionsWaiting: Int,
    val lastWeeklyLetterAt: Long?,
    val lastMonthlyLetterAt: Long?,
    /** A letter that couldn't be written after all retries ("weekly", "monthly", "yearly"), or null. */
    val letterDelayed: String?,
    val gardening: JobRecord,
    val pendingReminders: Int,
    val exactAlarmsAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val xiaomi: Boolean,
    val failedJobsLast7Days: Int,
    val notes: Int,
    val people: Int,
    val events: Int,
    val quotes: Int,
    val possibleDuplicates: Int,
    val contextTokens: Int,
    val monthCostUsd: Double,
    val budgetUsd: Double,
    val autoBackup: Boolean,
    val lastBackupAt: Long?,
    val promptOverrides: List<String>,
    /** UPDATE-13/14: edited prompts whose built-in version changed in an update since. */
    val promptsBuiltInChanged: List<String> = emptyList(),
    // UPDATE-15
    val archived: Int = 0,
    val chapters: Int = 0,
    // UPDATE-16: once a year
    val lastRestoreTestAt: Long? = null,
    val keystoreConfirmedAt: Long? = null,
    val providerKeyExpiry: String? = null,
    val backupProviderSet: Boolean = false,
    val usingBackupProvider: Boolean = false,
    val queuedMessages: Int = 0,
    // UPDATE-17
    val cacheHitRate: Double? = null,
    val deepShare: Double? = null,
)

enum class HealthLevel(val word: String) { GREEN("ok"), AMBER("check"), RED("fix") }

/** One row: a dot, a title and a plain sentence. [action] names a button the screen can offer. */
data class HealthItem(val level: HealthLevel, val title: String, val sentence: String, val action: Action? = null) {
    enum class Action { OPEN_ERROR_LOG, CLEAN_UP, OPEN_RELIABILITY }
}

/** CLAUDE.md "Health check": what's working, what needs a look, in plain sentences. Pure, so it's tested. */
object HealthCheck {
    const val CONTEXT_AMBER = 12_000
    const val CONTEXT_RED = 20_000

    fun items(i: HealthInputs): List<HealthItem> = buildList {
        val now = Instant.ofEpochMilli(i.now).atZone(i.zone).toLocalDateTime()

        // Reflection
        add(
            when {
                i.reflection.lastOk == false -> HealthItem(
                    HealthLevel.RED, "Reflection",
                    "The last reflection failed ${ago(i.reflection.lastRunAt, i.now)}. ${waiting(i.sessionsWaiting)}",
                    HealthItem.Action.OPEN_ERROR_LOG,
                )
                i.sessionsWaiting > 3 -> HealthItem(HealthLevel.AMBER, "Reflection", "${waiting(i.sessionsWaiting)} The phone may be stopping background work.", HealthItem.Action.OPEN_RELIABILITY)
                i.reflection.lastOkAt == null -> HealthItem(HealthLevel.GREEN, "Reflection", "No reflection yet. ${waiting(i.sessionsWaiting)}")
                else -> HealthItem(HealthLevel.GREEN, "Reflection", "Last reflection worked ${ago(i.reflection.lastOkAt, i.now)}. ${waiting(i.sessionsWaiting)}")
            }
        )

        // Weekly letter
        val nextWeekly = nextWeekly(now)
        val weeklyLate = i.lastWeeklyLetterAt != null && i.now - i.lastWeeklyLetterAt > Duration.ofDays(15).toMillis()
        add(
            when {
                i.letterDelayed == "weekly" -> HealthItem(HealthLevel.RED, "Weekly letter", "The last weekly letter couldn't be written. Open Mirror and tap Try again.", HealthItem.Action.OPEN_ERROR_LOG)
                weeklyLate -> HealthItem(HealthLevel.AMBER, "Weekly letter", "No weekly letter for over two weeks (last ${ago(i.lastWeeklyLetterAt, i.now)}). Fine if you didn't talk much; otherwise check Keep Purpose reliable. Next: ${at(nextWeekly)}.")
                else -> HealthItem(
                    HealthLevel.GREEN, "Weekly letter",
                    (i.lastWeeklyLetterAt?.let { "Last one ${ago(it, i.now)}. " } ?: "None yet. ") + "Next: ${at(nextWeekly)}.",
                )
            }
        )

        // Monthly letter and gardening
        val nextMonthly = nextMonthly(now)
        add(
            when {
                i.letterDelayed == "monthly" || i.letterDelayed == "yearly" ->
                    HealthItem(HealthLevel.RED, "Monthly letter", "The last ${i.letterDelayed} letter couldn't be written. Open Mirror and tap Try again.", HealthItem.Action.OPEN_ERROR_LOG)
                else -> HealthItem(
                    HealthLevel.GREEN, "Monthly letter",
                    (i.lastMonthlyLetterAt?.let { "Last one ${ago(it, i.now)}. " } ?: "None yet. ") + "Next: ${at(nextMonthly)}.",
                )
            }
        )
        add(
            when (i.gardening.lastOk) {
                false -> HealthItem(HealthLevel.AMBER, "Memory gardening", "The last tidy-up failed ${ago(i.gardening.lastRunAt, i.now)}. It runs again after the next monthly letter.", HealthItem.Action.OPEN_ERROR_LOG)
                true -> HealthItem(HealthLevel.GREEN, "Memory gardening", "Last tidy-up ${ago(i.gardening.lastOkAt, i.now)}.")
                null -> HealthItem(HealthLevel.GREEN, "Memory gardening", "Not run yet. It runs after each monthly letter.")
            }
        )

        // Reminders
        val reminderProblems = listOfNotNull(
            "notifications are off".takeIf { !i.notificationsAllowed },
            "exact alarms aren't allowed, so reminders may come a little late".takeIf { !i.exactAlarmsAllowed },
        )
        add(
            when {
                i.pendingReminders > 0 && !i.notificationsAllowed -> HealthItem(HealthLevel.RED, "Reminders", "${count(i.pendingReminders, "reminder")} waiting, but notifications are off, so they won't show.")
                reminderProblems.isNotEmpty() -> HealthItem(HealthLevel.AMBER, "Reminders", "${count(i.pendingReminders, "reminder")} waiting; ${reminderProblems.joinToString(" and ")}.")
                else -> HealthItem(HealthLevel.GREEN, "Reminders", "${count(i.pendingReminders, "reminder")} waiting. Notifications and exact alarms are allowed.")
            }
        )

        // Battery
        val autostart = if (i.xiaomi) " On Xiaomi, also check that Autostart is on." else ""
        add(
            if (i.batteryUnrestricted) HealthItem(HealthLevel.GREEN, "Battery", "Battery: unrestricted.$autostart")
            else HealthItem(HealthLevel.AMBER, "Battery", "Battery: restricted. The phone may stop letters, reflection and reminders.$autostart", HealthItem.Action.OPEN_RELIABILITY)
        )

        // Failed jobs
        add(
            when {
                i.failedJobsLast7Days == 0 -> HealthItem(HealthLevel.GREEN, "Failed jobs", "Nothing failed in the last 7 days.")
                i.failedJobsLast7Days < 5 -> HealthItem(HealthLevel.AMBER, "Failed jobs", "${count(i.failedJobsLast7Days, "failure")} in the last 7 days. Tap to see the error log.", HealthItem.Action.OPEN_ERROR_LOG)
                else -> HealthItem(HealthLevel.RED, "Failed jobs", "${count(i.failedJobsLast7Days, "failure")} in the last 7 days. Tap to see the error log.", HealthItem.Action.OPEN_ERROR_LOG)
            }
        )

        // Memory size
        val sizes = "${count(i.notes, "note")}, ${count(i.people, "person", "people")}, ${count(i.events, "event")}, ${count(i.quotes, "quote")}" +
            (if (i.archived > 0) ", ${i.archived} archived" else "") + (if (i.chapters > 0) ", ${count(i.chapters, "chapter")}" else "") + "."
        add(
            if (i.possibleDuplicates > 0) HealthItem(HealthLevel.AMBER, "Memory", "$sizes ${count(i.possibleDuplicates, "possible duplicate")}.", HealthItem.Action.CLEAN_UP)
            else HealthItem(HealthLevel.GREEN, "Memory", "$sizes No duplicates.")
        )

        // Context size
        add(
            HealthItem(
                when {
                    i.contextTokens > CONTEXT_RED -> HealthLevel.RED
                    i.contextTokens > CONTEXT_AMBER -> HealthLevel.AMBER
                    else -> HealthLevel.GREEN
                },
                "Context size",
                "About ${String.format(Locale.US, "%,d", i.contextTokens)} tokens of memory go with each message." +
                    if (i.contextTokens > CONTEXT_AMBER) " That's a lot; memory gardening trims it." else "",
            )
        )

        // Cost
        add(
            when {
                i.budgetUsd <= 0 -> HealthItem(HealthLevel.GREEN, "Cost", "${money(i.monthCostUsd)} this month, no budget set.")
                i.monthCostUsd >= i.budgetUsd -> HealthItem(HealthLevel.AMBER, "Cost", "${money(i.monthCostUsd)} of ${money(i.budgetUsd)}. Chat uses the fast model until the month ends.")
                i.monthCostUsd >= i.budgetUsd * 0.8 -> HealthItem(HealthLevel.AMBER, "Cost", "${money(i.monthCostUsd)} of ${money(i.budgetUsd)} this month.")
                else -> HealthItem(HealthLevel.GREEN, "Cost", "${money(i.monthCostUsd)} of ${money(i.budgetUsd)} this month.")
            }
        )

        // Backup
        add(
            when {
                !i.autoBackup -> HealthItem(HealthLevel.AMBER, "Backup", "Automatic backup is off. Turn it on in Settings > Backup.")
                i.lastBackupAt == null || i.lastBackupAt == 0L -> HealthItem(HealthLevel.AMBER, "Backup", "Automatic backup is on, but none has been written yet.")
                i.now - i.lastBackupAt > Duration.ofDays(10).toMillis() -> HealthItem(HealthLevel.RED, "Backup", "Last backup ${ago(i.lastBackupAt, i.now)}. Weekly backups have stopped.", HealthItem.Action.OPEN_ERROR_LOG)
                else -> HealthItem(HealthLevel.GREEN, "Backup", "Last backup ${ago(i.lastBackupAt, i.now)}.")
            }
        )

        // Prompt overrides
        add(
            when {
                i.promptOverrides.isEmpty() -> HealthItem(HealthLevel.GREEN, "Prompts", "All prompts are built-in.")
                i.promptsBuiltInChanged.isNotEmpty() -> HealthItem(
                    HealthLevel.AMBER, "Prompts",
                    "Edited: ${i.promptOverrides.joinToString(", ")}. An update changed the built-in ${i.promptsBuiltInChanged.joinToString(", ")} since you edited it: open the Prompt editor to keep yours or reset to the new one.",
                )
                else -> HealthItem(HealthLevel.AMBER, "Prompts", "Edited: ${i.promptOverrides.joinToString(", ")}. Run the test bench after changes.")
            }
        )

        // AI provider (UPDATE-16)
        add(
            when {
                i.usingBackupProvider -> HealthItem(HealthLevel.AMBER, "AI provider", "The main provider failed 3 times in a row, so your backup provider is answering for now. Check your DeepSeek key and balance.")
                !i.backupProviderSet -> HealthItem(HealthLevel.AMBER, "AI provider", "No backup provider set. If DeepSeek is down, replies wait until it's back. Settings > Advanced > Backup provider.")
                else -> HealthItem(HealthLevel.GREEN, "AI provider", "Main provider working, backup provider ready.")
            }
        )
        if (i.queuedMessages > 0) add(HealthItem(HealthLevel.AMBER, "Offline", "${count(i.queuedMessages, "message")} waiting to send when you're online."))

        // Cache and routing (UPDATE-17)
        i.cacheHitRate?.let { rate ->
            val pct = (rate * 100).toInt()
            val deep = i.deepShare?.let { " ${(it * 100).toInt()}% of chat messages went to the deep model." }.orEmpty()
            add(
                if (rate >= 0.6) HealthItem(HealthLevel.GREEN, "Cache", "$pct% of input came from the provider's cache this month (cheaper).$deep")
                else HealthItem(HealthLevel.AMBER, "Cache", "Only $pct% of input came from the cache this month. Something at the start of each request may be changing.$deep")
            )
        }

        // Once a year (UPDATE-16, MAINTENANCE.md)
        val year = Duration.ofDays(366).toMillis()
        add(
            when {
                i.lastRestoreTestAt == null -> HealthItem(HealthLevel.AMBER, "Restore test", "You haven't tested restoring a backup yet. Once a year, try it on another phone or after a reset, then mark it in Settings > Once a year.")
                i.now - i.lastRestoreTestAt > year -> HealthItem(HealthLevel.AMBER, "Restore test", "Last restore test ${ago(i.lastRestoreTestAt, i.now)}. Time for this year's.")
                else -> HealthItem(HealthLevel.GREEN, "Restore test", "Last restore test ${ago(i.lastRestoreTestAt, i.now)}.")
            }
        )
        add(
            when {
                i.keystoreConfirmedAt == null || i.now - i.keystoreConfirmedAt > year -> HealthItem(HealthLevel.AMBER, "Signing keystore", "Check that your keystore file and its 4 passwords are saved safely (without them you can't update Purpose), then confirm in Settings > Once a year.")
                else -> HealthItem(HealthLevel.GREEN, "Signing keystore", "Confirmed saved ${ago(i.keystoreConfirmedAt, i.now)}.")
            }
        )
        i.providerKeyExpiry?.let { raw ->
            val expires = runCatching { java.time.LocalDate.parse(raw) }.getOrNull()
            val today = Instant.ofEpochMilli(i.now).atZone(i.zone).toLocalDate()
            if (expires != null) add(
                when {
                    expires.isBefore(today) -> HealthItem(HealthLevel.RED, "API key", "Your API key expired on $expires. Make a new one and add it in Settings.")
                    expires.isBefore(today.plusDays(31)) -> HealthItem(HealthLevel.AMBER, "API key", "Your API key expires on $expires. Make a new one before then.")
                    else -> HealthItem(HealthLevel.GREEN, "API key", "Your API key is good until $expires.")
                }
            )
        }
    }

    /** "Copy health report". */
    fun report(items: List<HealthItem>, version: String, now: Long, zone: ZoneId): String = buildString {
        append("Purpose health check, ").append(Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime().format(STAMP)).append(", version ").append(version).append('\n')
        items.forEach { append("[").append(it.level.word).append("] ").append(it.title).append(": ").append(it.sentence).append('\n') }
    }

    /** Sunday 20:00 local, the next one after [now]. */
    fun nextWeekly(now: LocalDateTime): LocalDateTime {
        val thisSunday = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(20, 0)
        return if (thisSunday.isAfter(now)) thisSunday else thisSunday.plusWeeks(1)
    }

    /** The 1st of next month, early morning. */
    fun nextMonthly(now: LocalDateTime): LocalDateTime = now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay()

    /** Same text, or same person's name, ignoring case and spaces at the ends. */
    fun duplicates(texts: List<String>): Int =
        texts.groupBy { it.trim().lowercase().replace(Regex("""\s+"""), " ") }.values.sumOf { it.size - 1 }

    fun ago(at: Long?, now: Long): String {
        if (at == null || at <= 0) return "never"
        val d = Duration.ofMillis((now - at).coerceAtLeast(0))
        return when {
            d.toMinutes() < 2 -> "just now"
            d.toHours() < 1 -> "${d.toMinutes()} minutes ago"
            d.toHours() < 24 -> count(d.toHours().toInt(), "hour") + " ago"
            d.toDays() == 1L -> "yesterday"
            else -> "${d.toDays()} days ago"
        }
    }

    private fun waiting(n: Int) = when (n) {
        0 -> "Nothing waiting."
        1 -> "1 conversation waiting."
        else -> "$n conversations waiting."
    }

    private fun count(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    private fun money(d: Double) = "$" + String.format(Locale.US, "%.2f", d)

    private val AT = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)
    private fun at(t: LocalDateTime) = t.format(AT)
}
