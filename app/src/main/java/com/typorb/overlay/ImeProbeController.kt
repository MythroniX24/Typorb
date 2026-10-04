package com.typorb.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * An invisible 1×1 window whose only job is to observe `WindowInsetsCompat.Type.ime()`.
 *
 * **Why this exists.** The orb window itself used to be the only inset observer, and that created a
 * deadlock: the orb is only added once the keyboard has already been detected, so on any device
 * where window introspection fails there is nothing to produce the inset reading that would have
 * detected the keyboard. Both signals therefore report "no keyboard" and the orb never appears —
 * with no symptom other than silence.
 *
 * Attaching a dedicated, non-interactive window for the whole life of the service breaks that
 * cycle. It is transparent, one pixel, never focusable and never touchable, so it cannot be seen,
 * focused, or tapped.
 *
 * The window is best-effort: an OEM that refuses it costs one signal, not the feature, so every
 * failure is logged and swallowed.
 */
class ImeProbeController(
    context: Context,
    /** Receives the IME inset in px, or `0` when the keyboard is down. */
    private val onImeInsetChanged: (Int) -> Unit,
) {

    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: View? = null

    /** `true` when the probe window is attached and therefore reporting. */
    val isAttached: Boolean get() = view != null

    /**
     * Adds the probe window. Safe to call more than once; only the first call does anything.
     *
     * @return `true` when the window is attached.
     */
    fun start(): Boolean {
        if (view != null) return true

        val probe = View(appContext).apply {
            // Keep the probe out of the accessibility tree it would otherwise pollute: a stray
            // always-present window is exactly the kind of thing that makes an enabled service
            // look like it is doing something it is not.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                // Always report, including 0, so a dismissed keyboard clears the stale reading.
                onImeInsetChanged(imeBottom)
                WindowInsetsCompat.CONSUMED
            }
        }

        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        return runCatching {
            windowManager.addView(probe, params)
            view = probe
            // Insets are only dispatched to a freshly attached view when asked for.
            ViewCompat.requestApplyInsets(probe)
        }.onFailure { Log.w(TAG, "IME probe window unavailable; inset signal lost", it) }
            .isSuccess
    }

    /** Removes the probe window. */
    fun stop() {
        val probe = view ?: return
        view = null
        runCatching { windowManager.removeView(probe) }
            .onFailure { Log.w(TAG, "IME probe already detached", it) }
    }

    private companion object {
        const val TAG = "TyporbProbe"
    }
}
