package com.umair.purpose.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.umair.purpose.MainActivity
import com.umair.purpose.R
import com.umair.purpose.dev.ErrorLogger
import com.umair.purpose.promise.ReminderScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Whether a Purpose screen is on show (set by MainActivity), so a finished reply knows if he's away. */
object AppVisibility {
    @Volatile var foreground: Boolean = false
}

/**
 * Keeps the process in the foreground while a reply is generated, so switching apps, locking the phone or the
 * screen turning off doesn't kill it. Android 14+: type shortService (fits replies under ~3 minutes, no special
 * permission). Its notification is quiet: "Purpose is replying…". The work itself runs in [ReplyEngine].
 */
class ReplyService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Purpose is replying…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(openApp(this))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        try {
            ServiceCompat.startForeground(this, REPLYING_ID, n, type)
        } catch (e: Exception) {
            // Not allowed right now: started from the background, or a foreground-service type permission this
            // build doesn't hold. The reply still runs in the app's scope, but it can be killed if he leaves.
            // This used to fail silently, which hid a real problem, so it goes to the error log the Developer
            // menu and the Health check read.
            Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}")
            val app = applicationContext
            serviceScope.launch {
                runCatching {
                    EntryPointAccessors.fromApplication(app, ReplyServiceEntryPoint::class.java).errors().log("reply-service", e)
                }
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** shortService's time is up (about 3 minutes). Stop cleanly; the reply carries on as long as the app lives. */
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "replies"
        private const val REPLYING_ID = 4101
        private const val REPLIED_ID = 4102

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ReplyService::class.java))
            }.onFailure { e ->
                Log.w(TAG, "Failed to start ReplyService foreground: ${e.message}", e)
            }
        }

        fun finish(context: Context) {
            context.stopService(Intent(context, ReplyService::class.java))
        }

        /** The reply finished while he was in another app: "Purpose replied", which opens the conversation. */
        fun replied(context: Context) {
            if (AppVisibility.foreground || !ReminderScheduler.canNotify(context)) return
            ensureChannel(context)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Purpose replied")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build()
            try {
                NotificationManagerCompat.from(context).notify(REPLIED_ID, n)
            } catch (_: SecurityException) {
            }
        }

        /** He opened the app: the "Purpose replied" note has done its job. */
        fun clearReplied(context: Context) = NotificationManagerCompat.from(context).cancel(REPLIED_ID)

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Replies", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown only while Purpose finishes a reply, so it isn't lost when you switch apps."
                    setShowBadge(false)
                }
            )
        }
    }
}

private const val TAG = "ReplyService"

/** So a foreground-service failure reaches the error log, not just logcat. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReplyServiceEntryPoint {
    fun errors(): ErrorLogger
}

private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
