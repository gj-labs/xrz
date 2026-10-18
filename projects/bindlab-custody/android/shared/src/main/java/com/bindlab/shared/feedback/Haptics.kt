package com.bindlab.shared.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Named vibration patterns shared by both apps; the WS {type:"haptic", pattern} uses these names. */
class Haptics(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    val available get() = vibrator?.hasVibrator() == true

    fun play(pattern: String) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = when (pattern) {
            "success", "passed" -> VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1)
            "anomaly", "error" -> VibrationEffect.createWaveform(longArrayOf(0, 300, 120, 300, 120, 300), -1)
            "tick" -> VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
            else -> VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        v.vibrate(effect)
    }
}
