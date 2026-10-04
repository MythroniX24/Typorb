package com.typorb.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Haptic feedback for the overlay lifecycle: a light tick when recording starts, a strong click
 * when text has been injected and a heavy double pulse on failure.
 *
 * Predefined effects are used from API 29 (the constants are inlined by the compiler); older
 * devices fall back to equivalent one-shot pulses so the feedback still exists.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = resolveVibrator(context)

    /** Fired on the first tap, as the pill expands into the recorder. */
    fun tick() = vibrate(effect(VibrationEffect.EFFECT_TICK, TICK_MS, TICK_AMPLITUDE))

    /** Fired after a successful injection: a deliberately heavier "it worked" pulse. */
    fun confirm() = vibrate(effect(VibrationEffect.EFFECT_CLICK, CONFIRM_MS, CONFIRM_AMPLITUDE))

    /** Fired when a dictation fails, so the user knows something went wrong. */
    fun reject() = vibrate(effect(VibrationEffect.EFFECT_HEAVY_CLICK, REJECT_MS, REJECT_AMPLITUDE))

    private fun effect(predefined: Int, fallbackMs: Long, amplitude: Int): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(predefined)
        } else {
            VibrationEffect.createOneShot(fallbackMs, amplitude)
        }

    private fun vibrate(effect: VibrationEffect) {
        try {
            vibrator?.vibrate(effect)
        } catch (error: SecurityException) {
            Log.w(TAG, "Vibration permission missing", error)
        }
    }

    private companion object {
        private const val TAG = "Haptics"
        private const val TICK_MS = 24L
        private const val TICK_AMPLITUDE = 60
        private const val CONFIRM_MS = 45L
        private const val CONFIRM_AMPLITUDE = 200
        private const val REJECT_MS = 60L
        private const val REJECT_AMPLITUDE = 180

        private fun resolveVibrator(context: Context): Vibrator? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()
    }
}