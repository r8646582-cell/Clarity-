package com.umair.purpose.promise

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.umair.purpose.MainActivity
import com.umair.purpose.R
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.PurposeDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one exception to "no notifications": a reminder he asked for on a specific promise.
 * Exact alarm when allowed, otherwise an inexact one. The notification only reads the promise at fire time,
 * so a promise kept or dropped in the meantime never buzzes.
 */
@Singleton
class ReminderScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** A time already past goes off in a few seconds rather than never (UPDATE-12). */
    fun schedule(promiseId: Long, atMillis: Long) {
        val now = System.currentTimeMillis()
        val at = if (atMillis <= now) now + PromiseRules.FIRE_NOW_DELAY_MS else atMillis
        scheduleAt(promiseId, at)
    }

    private fun scheduleAt(promiseId: Long, atMillis: Long) {
        val pi = pending(promiseId)
        // Exact while Android allows it (survives Doze via setExactAndAllowWhileIdle); otherwise an inexact
        // window alarm. setWindow never throws where the exact permission is refused, so the reminder still
        // arrives, just within [WINDOW_MS].
        if (canScheduleExactAlarms(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, atMillis, WINDOW_MS, pi)
        }
    }

    fun cancel(promiseId: Long) = alarms.cancel(pending(promiseId))

    /** Developer menu: a reminder through the same alarm path, [delayMs] from now, with no promise behind it. */
    fun scheduleTest(delayMs: Long) {
        val pi = PendingIntent.getBroadcast(
            context,
            TEST_REQUEST,
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_TEST, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = System.currentTimeMillis() + delayMs
        if (canScheduleExactAlarms(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pi)
        }
    }

    /**
     * After a reboot or at launch. A reminder that fires clears itself, so one still set in the past was missed
     * (the phone was off): it goes off now if it's recent, and is left alone (stale) if not. Returns the ids of
     * stale ones, for the caller to clear.
     */
    fun rescheduleAll(promises: List<Promise>): List<Long> {
        val now = System.currentTimeMillis()
        val stale = mutableListOf<Long>()
        promises.forEach { p ->
            val at = p.remindAt ?: return@forEach
            if (p.status != Promise.OPEN) return@forEach
            when (val t = PromiseRules.reminderTime(at, now, justAgreed = false)) {
                null -> stale += p.id
                else -> scheduleAt(p.id, t)
            }
        }
        return stale
    }

    private fun pending(promiseId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        promiseId.toInt(),
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, promiseId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val EXTRA_ID = "promise_id"
        const val EXTRA_TEST = "test"
        private const val TEST_REQUEST = -7
        const val CHANNEL = "promise_reminders"

        /** How late an inexact fallback reminder may arrive when exact alarms aren't allowed. */
        const val WINDOW_MS = 10 * 60 * 1000L

        /**
         * True when Purpose may schedule exact, Doze-surviving alarms. Below API 31 this is always true; from
         * API 31 on the user (or the system) can revoke it, so every scheduling path asks first instead of
         * risking a SecurityException.
         */
        fun canScheduleExactAlarms(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

        /**
         * The system screen where he can allow exact alarms. The UI opens this from "Keep Purpose reliable" when
         * [canScheduleExactAlarms] is false, so reminders fire on time instead of inside a ten-minute window.
         */
        fun createExactAlarmSettingIntent(context: Context): Intent = Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Promise reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Only the reminders you asked for on a promise."
                }
            )
        }

        fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun database(): PurposeDatabase
    fun reminders(): ReminderScheduler
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Posts "It's time: {promise}" if the promise is still open. Tapping opens Talk. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(ReminderScheduler.EXTRA_TEST, false)) {
            val app = context.applicationContext
            if (ReminderScheduler.canNotify(app)) {
                ReminderScheduler.ensureChannel(app)
                try {
                    NotificationManagerCompat.from(app).notify(TEST_NOTIFICATION, reminderNotification(app, "It's time: this is a test reminder."))
                } catch (_: SecurityException) {
                }
            }
            return
        }
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, -1L)
        if (id < 0) return
        val result = goAsync()
        val app = context.applicationContext
        receiverScope.launch {
            try {
                val db = EntryPointAccessors.fromApplication(app, ReminderEntryPoint::class.java).database()
                val p = db.promiseDao().get(id)
                if (p != null && p.status == Promise.OPEN && p.remindAt != null) {
                    var shown = false
                    if (ReminderScheduler.canNotify(app)) {
                        ReminderScheduler.ensureChannel(app)
                        val n = reminderNotification(app, "It's time: ${p.text}")
                        try {
                            NotificationManagerCompat.from(app).notify(id.toInt(), n)
                            shown = true
                        } catch (_: SecurityException) {
                        }
                    }
                    // Done once it was really shown: a reboot won't bring it back, and one still set later on means
                    // it was missed. If notifications are off it stays, so allowing them later (or a reboot while
                    // it is still recent) can still show it instead of it vanishing unseen.
                    if (shown) db.promiseDao().clearReminder(id)
                }
            } finally {
                result.finish()
            }
        }
    }
}

private const val TEST_NOTIFICATION = -7

/** Title "Purpose"; tapping opens Talk. */
private fun reminderNotification(app: Context, text: String) = NotificationCompat.Builder(app, ReminderScheduler.CHANNEL)
    .setSmallIcon(R.drawable.ic_notification)
    .setContentTitle("Purpose")
    .setContentText(text)
    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
    .setContentIntent(
        PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    )
    .setAutoCancel(true)
    .build()

/** Alarms don't survive a restart: put the pending reminders back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent.action != EXACT_ALARM_STATE_CHANGED
        ) return
        val result = goAsync()
        val app = context.applicationContext
        receiverScope.launch {
            try {
                val ep = EntryPointAccessors.fromApplication(app, ReminderEntryPoint::class.java)
                val dao = ep.database().promiseDao()
                ep.reminders().rescheduleAll(dao.withReminders()).forEach { dao.clearReminder(it) }
            } finally {
                result.finish()
            }
        }
    }
}

/** AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31), spelled out for older SDK levels. */
private const val EXACT_ALARM_STATE_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
