package com.typorb.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.typorb.data.TyporbSettings
import com.typorb.model.OverlayUiState
import com.typorb.service.KeyboardGeometry
import com.typorb.ui.overlay.TyporbOverlayContent
import com.typorb.ui.theme.TyporbTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * Hosts the Typorb as a real window (`TYPE_ACCESSIBILITY_OVERLAY`) containing a single
 * [ComposeView].
 *
 * The window is sized explicitly per state — idle is a 48dp square, the working states are wide
 * capsules — so the WindowManager position is always exact and never depends on a measure pass.
 * Right edge is pinned 16dp from the display edge and the bottom sits 16dp above the keyboard.
 *
 * Only a [LifecycleOwner] is attached to the view tree: the overlay composes no `rememberSaveable`
 * state, so no `SavedStateRegistryOwner` is needed (and one cannot be constructed outside the
 * framework anyway, since `SavedStateRegistry`'s constructor is internal).
 */
class OverlayController(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val state: StateFlow<OverlayUiState>,
    /** Drives the user-customisable overlay appearance. */
    private val settings: StateFlow<TyporbSettings>,
    private val onTap: () -> Unit,
    /** Reports `WindowInsetsCompat.Type.ime()` observations as an IME-height fallback signal. */
    private val onImeInsetChanged: (Int) -> Unit,
) {

    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = appContext.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var keyboardHeightPx: Int = 0
    private var removePending: Runnable? = null

    val isShowing: Boolean get() = view != null

    /**
     * Shows the pill, or re-positions it if it is already up.
     *
     * @return `true` when a window is on screen afterwards, so the caller never records the overlay
     *   as visible after a failed [WindowManager.addView].
     */
    fun show(imeHeightPx: Int): Boolean {
        keyboardHeightPx = imeHeightPx
        if (view != null) {
            applyLayout(state.value)
            return true
        }
        removePending?.let { mainHandler.removeCallbacks(it) }
        removePending = null

        val padding = dp(SHADOW_PADDING_DP)

        val composeView = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // Observes the real IME height where the platform is cooperative about insets.
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                val imeHeight = insets.getInsets(
                    WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.navigationBars(),
                ).bottom
                if (imeHeight > 0) onImeInsetChanged(imeHeight)
                WindowInsetsCompat.CONSUMED
            }
            setContent {
                val currentState by state.collectAsState()
                val currentSettings by settings.collectAsState()

                // The window is sized outside Compose, so a settings change has to drive a re-layout:
                // the Skia content would otherwise render the new orb size clipped to the old window.
                androidx.compose.runtime.LaunchedEffect(
                    currentSettings.overlaySizeDp,
                    currentSettings.overlayCornerRadiusDp,
                ) {
                    applyLayout(state.value)
                }

                TyporbTheme {
                    TyporbOverlayContent(
                        state = currentState,
                        onTap = onTap,
                        cornerRadiusDp = currentSettings.overlayCornerRadiusDp,
                        showWaveform = currentSettings.waveformEnabled,
                        contentPadding = SHADOW_PADDING_DP.dp,
                    )
                }
            }
        }

        val layoutParams = WindowManager.LayoutParams(
            dp(48) + padding * 2,
            dp(PILL_HEIGHT_DP) + padding * 2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = 0
        }

        return runCatching { windowManager.addView(composeView, layoutParams) }
            .onSuccess {
                view = composeView
                params = layoutParams
                applyLayout(state.value)
                animateIn(composeView)
            }
            .onFailure { error ->
                Log.w(TAG, "Could not add the overlay window", error)
            }
            .isSuccess
    }

    /** Keyboard height changed while the pill is visible (IME show/hide animation). */
    fun updateKeyboardHeight(imeHeightPx: Int) {
        keyboardHeightPx = imeHeightPx
        if (view != null) applyLayout(state.value)
    }

    /** Removes the pill, animating it out first. */
    fun hide() {
        val composeView = view ?: return
        val hideRunnable = Runnable {
            if (view === composeView) {
                runCatching { windowManager.removeView(composeView) }
                    .onFailure { Log.w(TAG, "Overlay already detached", it) }
                view = null
                params = null
            }
            removePending = null
        }
        animateOut(composeView) { mainHandler.postDelayed(hideRunnable, REMOVE_DELAY_MS) }
    }

    private fun applyLayout(currentState: OverlayUiState) {
        val layoutParams = params ?: return
        val metrics = screenSize()
        val (pillWidthDp, pillHeightDp) = stateSizeDp(currentState)
        val width = dp(pillWidthDp)
        val height = dp(pillHeightDp)

        // KeyboardGeometry answers "where does the pill's own top-left belong". The window is larger
        // than the pill so the ambient shadow is not clipped by the window surface, so the window is
        // moved back by the padding on both axes and inflated by it on both sides.
        val (pillX, pillY) = KeyboardGeometry.pillTopLeft(
            screenWidthPx = metrics.first,
            screenHeightPx = metrics.second,
            keyboardHeightPx = keyboardHeightPx,
            pillWidthPx = width,
            pillHeightPx = height,
            density = density,
        )
        val padding = dp(SHADOW_PADDING_DP)

        layoutParams.width = width + padding * 2
        layoutParams.height = height + padding * 2
        // Clamped rather than negative: with a very tall IME the pill's own top can be at the screen
        // edge, and subtracting the shadow padding would otherwise push the window off-screen and
        // clip the top of the orb.
        layoutParams.x = (pillX - padding).coerceAtLeast(0)
        layoutParams.y = (pillY - padding).coerceAtLeast(0)
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
            .onFailure { Log.w(TAG, "Could not reposition the overlay", it) }
    }

    private fun animateIn(target: ComposeView) {
        target.alpha = 0f
        target.scaleX = IN_START_SCALE
        target.scaleY = IN_START_SCALE
        target.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SHOW_DURATION_MS)
            .setInterpolator(ANIMATION_INTERPOLATOR)
            .start()
    }

    private fun animateOut(target: ComposeView, onEnd: () -> Unit) {
        target.animate()
            .alpha(0f)
            .scaleX(OUT_END_SCALE)
            .scaleY(OUT_END_SCALE)
            .setDuration(HIDE_DURATION_MS)
            .setInterpolator(ANIMATION_INTERPOLATOR)
            .withEndAction(onEnd)
            .start()
    }

    /** Pill dimensions in dp; the idle orb is the only state the user can resize. */
    private fun stateSizeDp(currentState: OverlayUiState): Pair<Int, Int> {
        val orb = settings.value.overlaySizeDp
        return when (currentState) {
            is OverlayUiState.Idle -> orb to orb
            is OverlayUiState.Recording -> RECORDING_WIDTH_DP to PILL_HEIGHT_DP
            is OverlayUiState.Processing -> PROCESSING_WIDTH_DP to PILL_HEIGHT_DP
            is OverlayUiState.Failed -> ERROR_WIDTH_DP to PILL_HEIGHT_DP
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.currentWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        val point = android.graphics.Point()
        windowManager.defaultDisplay.getSize(point)
        point.x to point.y
    }

    companion object {
        private const val TAG = "OverlayController"

        const val PILL_HEIGHT_DP = 48

        /**
         * Inflates the overlay window beyond the pill on every side.
         *
         * A window surface is clipped to its own bounds, so a pill-sized window would cut the soft
         * ambient shadow off at the edges. 14dp covers the 8dp elevation plus its blur radius.
         */
        const val SHADOW_PADDING_DP = 14
        const val RECORDING_WIDTH_DP = 160
        const val PROCESSING_WIDTH_DP = 190
        const val ERROR_WIDTH_DP = 220

        private const val SHOW_DURATION_MS = 180L
        private const val HIDE_DURATION_MS = 150L
        private const val REMOVE_DELAY_MS = 160L
        private const val IN_START_SCALE = 0.72f
        private const val OUT_END_SCALE = 0.86f

        private val ANIMATION_INTERPOLATOR =
            android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}