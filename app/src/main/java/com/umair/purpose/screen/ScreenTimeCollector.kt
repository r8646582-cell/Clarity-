package com.umair.purpose.screen

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4: reads the phone's own foreground events (Android's usage access, which only he can grant, in system
 * Settings). Package names are turned into a category straight away and are never stored or logged.
 */
@Singleton
class ScreenTimeCollector @Inject constructor(@ApplicationContext private val context: Context) {

    /** True when he has granted Purpose "usage access". */
    fun hasAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        // Some Android versions answer MODE_DEFAULT while the permission itself is granted in Settings.
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** The system screen where he grants or revokes usage access. */
    fun accessSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Foreground changes in [from, to). Empty when access is missing or the phone has nothing for that window. */
    fun events(from: Long, to: Long): List<UsageEv> {
        if (!hasAccess()) return emptyList()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val cache = HashMap<String, String>()
        val out = ArrayList<UsageEv>()
        val raw = usm.queryEvents(from, to) ?: return emptyList()
        val e = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(e)
            val resumed = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> true
                UsageEvents.Event.ACTIVITY_PAUSED -> false
                else -> continue
            }
            val pkg = e.packageName ?: continue
            if (pkg == context.packageName) continue
            val category = cache.getOrPut(pkg) { categoryOf(pkg) }
            out += UsageEv(pkg + "/" + (e.className ?: ""), category, e.timeStamp, resumed)
        }
        return out
    }

    private fun categoryOf(pkg: String): String = try {
        ScreenLedger.categoryName(context.packageManager.getApplicationInfo(pkg, 0).category)
    } catch (e: Exception) {
        "other"
    }

}
