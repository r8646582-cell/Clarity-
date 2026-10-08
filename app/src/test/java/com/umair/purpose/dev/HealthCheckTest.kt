package com.umair.purpose.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HealthCheckTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private val now = LocalDateTime.of(2026, 10, 3, 21, 0).atZone(zone).toInstant().toEpochMilli()
    private val day = 24L * 3600 * 1000
    private val okJob = JobRecord(now - day, true, now - day)

    private fun healthy() = HealthInputs(
        now = now, zone = zone, reflection = okJob, sessionsWaiting = 0, lastWeeklyLetterAt = now - 6 * day,
        lastMonthlyLetterAt = now - 2 * day, letterDelayed = null, gardening = okJob, pendingReminders = 1,
        exactAlarmsAllowed = true, notificationsAllowed = true, batteryUnrestricted = true, xiaomi = true,
        failedJobsLast7Days = 0, notes = 30, people = 5, events = 12, quotes = 8, possibleDuplicates = 0,
        contextTokens = 6_000, monthCostUsd = 1.2, budgetUsd = 5.0, autoBackup = true, lastBackupAt = now - 3 * day,
        promptOverrides = emptyList(), lastRestoreTestAt = now - 30 * day, keystoreConfirmedAt = now - 30 * day,
        backupProviderSet = true,
    )

    private fun level(i: HealthInputs, title: String) = HealthCheck.items(i).first { it.title == title }.level

    @Test
    fun `a healthy phone is all green`() {
        assertTrue(HealthCheck.items(healthy()).all { it.level == HealthLevel.GREEN })
    }

    @Test
    fun `a year without a restore test or keystore check is amber`() {
        val old = healthy().copy(lastRestoreTestAt = null, keystoreConfirmedAt = now - 400 * day)
        assertTrue(HealthCheck.items(old).any { it.level == HealthLevel.AMBER })
    }

    @Test
    fun `failed reflection is red and opens the error log`() {
        val item = HealthCheck.items(healthy().copy(reflection = JobRecord(now - 3600_000, false, now - 2 * day)))
            .first { it.title == "Reflection" }
        assertEquals(HealthLevel.RED, item.level)
        assertEquals(HealthItem.Action.OPEN_ERROR_LOG, item.action)
    }

    @Test
    fun `context size thresholds`() {
        assertEquals(HealthLevel.AMBER, level(healthy().copy(contextTokens = 12_500), "Context size"))
        assertEquals(HealthLevel.RED, level(healthy().copy(contextTokens = 21_000), "Context size"))
    }

    @Test
    fun `duplicates offer clean up`() {
        val item = HealthCheck.items(healthy().copy(possibleDuplicates = 2)).first { it.title == "Memory" }
        assertEquals(HealthItem.Action.CLEAN_UP, item.action)
        assertEquals(2, HealthCheck.duplicates(listOf("Father", "father ", "Ammi", "FATHER")))
    }

    @Test
    fun `restricted battery and prompt overrides are amber`() {
        assertEquals(HealthLevel.AMBER, level(healthy().copy(batteryUnrestricted = false), "Battery"))
        assertEquals(HealthLevel.AMBER, level(healthy().copy(promptOverrides = listOf("persona.md")), "Prompts"))
    }

    @Test
    fun `reminders with notifications off are red`() {
        assertEquals(HealthLevel.RED, level(healthy().copy(notificationsAllowed = false), "Reminders"))
    }

    @Test
    fun `next weekly letter is the coming Sunday at 20_00`() {
        // Saturday 3 Oct 2026, 21:00 → Sunday 4 Oct, 20:00.
        assertEquals(LocalDateTime.of(2026, 10, 4, 20, 0), HealthCheck.nextWeekly(LocalDateTime.of(2026, 10, 3, 21, 0)))
        // Sunday after 20:00 → next Sunday.
        assertEquals(LocalDateTime.of(2026, 10, 11, 20, 0), HealthCheck.nextWeekly(LocalDateTime.of(2026, 10, 4, 20, 30)))
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), HealthCheck.nextMonthly(LocalDateTime.of(2026, 10, 3, 21, 0)))
    }

    @Test
    fun `report has one line per item`() {
        val items = HealthCheck.items(healthy())
        val report = HealthCheck.report(items, "1.0.0", now, zone)
        assertEquals(items.size + 1, report.trim().lines().size)
        assertTrue(report.contains("[ok] Reflection"))
    }
}
