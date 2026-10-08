package com.umair.purpose.system

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * CLAUDE.md "Keep Purpose reliable on Xiaomi/Redmi". HyperOS/MIUI kills background apps hard, which can stop
 * replies, reminders, letters and reflection. These open the right system screens.
 */
object Reliability {
    /** True when Android won't hold Purpose back to save battery. */
    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun isXiaomi(): Boolean = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco") ||
        Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** Asks Android to stop battery-optimizing Purpose (fine for a sideloaded app). */
    @SuppressLint("BatteryLife")
    fun requestUnrestricted(context: Context) {
        val pkg = context.packageName
        if (!start(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))) {
            if (!start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppSettings(context)
        }
    }

    /** MIUI's Autostart screen, trying the known security-center entries; else the app's own system page. */
    fun openAutostart(context: Context) {
        val tries = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")),
            Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").addCategory(Intent.CATEGORY_DEFAULT),
        )
        if (tries.none { start(context, it) }) openAppSettings(context)
    }

    fun openAppSettings(context: Context) {
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
