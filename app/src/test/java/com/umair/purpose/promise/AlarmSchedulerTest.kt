package com.umair.purpose.promise

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exact-alarm capability, the inexact fallback and the settings prompt (CLAUDE.md reliability). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class AlarmSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [26])
    fun `before API 31 exact alarms are always allowed`() {
        assertTrue(ReminderScheduler.canScheduleExactAlarms(context))
    }

    @Test
    fun `the inexact fallback window is ten minutes`() {
        assertEquals(10 * 60 * 1000L, ReminderScheduler.WINDOW_MS)
    }

    @Test
    fun `the setting intent opens the exact-alarm screen for this package`() {
        val intent = ReminderScheduler.createExactAlarmSettingIntent(context)
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }
}
