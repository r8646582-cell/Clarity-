package com.umair.purpose.system

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * CLAUDE.md "Haptics (QA: none worked)". One light click, through one helper. HyperOS/MIUI often ignores
 * View.performHapticFeedback while still reporting success, so on Xiaomi phones (and whenever the view
 * declines) it goes straight to the Vibrator: the predefined click (API 29+) or a 20ms one-shot.
 * Settings > "Vibration" turns it off.
 */
object Haptics {
    @Volatile var enabled: Boolean = true

    fun click(view: View) {
        if (!enabled) return
        val viaView = !Reliability.isXiaomi() && view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
        )
        if (!viaView) vibrate(view.context)
    }

    private fun vibrate(context: Context) {
        val vibrator = runCatching {
            if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        }.getOrNull() ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            else vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
