package com.typorb.overlay

import android.animation.ValueAnimator
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
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import com.typorb.data.TyporbSettings
import com.typorb.model.OverlayUiState
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
 * Because the window *is* the pill's rectangle, tapping the orb is also a resize, and that resize is
 * animated ([applyLayout] lerps width, height and position over [MORPH_DURATION_MS]). Snapping it
 * instead — which is what happened while the size only followed the Settings flow — left the
 * recording capsule drawn inside a square idle window for up to a watchdog tick, and the jump read
 * as a broken layout rather than a transition.
 *
 * All three view-tree owners Compose requires are attached: [LifecycleOwner], a
 * [androidx.savedstate.SavedStateRegistryOwner] and a [androidx.lifecycle.ViewModelStoreOwner].
 * Only the first is available from the accessibility service, so the other two come from
 * [OverlayStateOwner]. The registry requirement is unconditional — it comes from
 * `ComposeView.onAttachedToWindow`, not from whether any composable actually saves state — so an
 * overlay built without one crashes the moment the window is attached.
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
    /** Whether the OEM fallback window type may be used, i.e. "Display over other apps" is granted. */
    private val canUseApplicationOverlay: () -> Boolean,
) {

    private val appContext = context.applicationContext
    /**
     * The service's own [WindowManager], deliberately *not* the application context's.
     *
     * A `TYPE_ACCESSIBILITY_OVERLAY` window is authorised by the accessibility service that adds
     * it, and WindowManager resolves that grant from the context the instance came from. Building
     * it from `applicationContext` therefore makes `addView` fail with a `BadTokenException` on
     * stock Android and on MIUI, which is precisely what the debug console on a Redmi 8A reported:
     * focus detected, keyboard detected, and yet the orb never appeared because both this window
     * and the IME probe were being rejected.
     */
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = appContext.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var keyboardHeightPx: Int = 0
    private var removePending: Runnable? = null
    private var layoutAnimator: ValueAnimator? = null

    /**
     * The window rectangle [applyLayout] is currently heading to.
     *
     * The service re-applies the layout on every evaluation, so without this a morph still in flight
     * would be restarted every few milliseconds instead of finishing.
     */
    private var layoutTarget: OverlayMetrics.Window? = null

    /** Set by [useFallbackWindowTypeNext] when the preferred type has just been lost on this device. */
    private var preferFallbackWindowType = false

    /**
     * Supplies the saved-state and view-model owners for the overlay window, shared by every
     * ComposeView this controller creates so a detach/reattach cycle cannot end up with a window
     * pointing at a disposed owner.
     *
     * It is created as a field initializer, i.e. during the service's `setUp()`, at which point the
     * service lifecycle is already `STARTED`. That is safe precisely because [OverlayStateOwner]
     * owns an independent lifecycle rather than adopting the service's — see its KDoc for the
     * `IllegalStateException` this ordering used to cause.
     */
    private val stateOwner = OverlayStateOwner()

    /** Why the last [show] failed, surfaced verbatim in the Settings debug console. */
    var lastWindowError: String? = null
        private set

    /** Which window type actually got the orb on screen, or `null` while hidden. */
    var windowTypeInUse: Int? = null
        private set

    val isShowing: Boolean get() = view != null

    /**
     * Whether the orb's view is genuinely attached to the display.
     *
     * [isShowing] answers "did `addView` succeed"; this answers "is the window really there now". A
     * platform that removes an overlay window behind the app's back leaves the first `true` and the
     * second `false`, which is the only way the service can notice it has to add the orb again.
     */
    val isAttached: Boolean get() = view?.isAttachedToWindow == true

    /**
     * Shows the pill, or re-positions it if it is already up.
     *
     * @return `true` when a window is on screen afterwards, so the caller never records the overlay
     *   as visible after a failed [WindowManager.addView].
     */
    fun show(imeHeightPx: Int): Boolean {
        keyboardHeightPx = imeHeightPx
        val existing = view
        if (existing != null) {
            // A hide that is still animating out has to be cancelled, not raced with: its delayed
            // removal would take the window away *after* this call has already reported the orb as on
            // screen, and the service would never add it again for the rest of the editing session.
            removePending?.let { mainHandler.removeCallbacks(it) }
            removePending = null
            existing.animate().cancel()
            existing.alpha = 1f
            existing.scaleX = 1f
            existing.scaleY = 1f
            applyLayout(state.value, animate = true)
            return true
        }
        removePending?.let { mainHandler.removeCallbacks(it) }
        removePending = null

        val padding = dp(OverlayMetrics.SHADOW_PADDING_DP)

        val composeView = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            // Both of these are read by ComposeView.onAttachedToWindow and are not optional. The
            // service supplies only the lifecycle owner, so the rest come from OverlayStateOwner.
            setViewTreeSavedStateRegistryOwner(stateOwner)
            setViewTreeViewModelStoreOwner(stateOwner)
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

                // The window is sized outside Compose, so a change in the pill's own rectangle has to
                // drive a re-layout: the Skia content would otherwise render the new pill clipped to
                // the old window until the service's next evaluation.
                //
                // The key is the *size*, not the state. A state that publishes new data every frame
                // (recording's amplitude list) must not restart this, and a state whose size depends
                // on its content — a failure message, which widens the capsule — has to. Both fall out
                // of comparing the rectangle the state asks for.
                val pillSize = OverlayMetrics.pillSizeDp(
                    currentState,
                    currentSettings.overlaySizeDp,
                )
                androidx.compose.runtime.LaunchedEffect(pillSize) {
                    applyLayout(currentState, animate = true)
                }

                TyporbTheme {
                    TyporbOverlayContent(
                        state = currentState,
                        onTap = onTap,
                        cornerRadiusDp = currentSettings.overlayCornerRadiusDp,
                        showWaveform = currentSettings.waveformEnabled,
                        contentPadding = OverlayMetrics.SHADOW_PADDING_DP.dp,
                    )
                }
            }
        }

        // Preferred type first, then the grant-gated fallback. Both are attempted rather than
        // choosing up front, because which one the platform will accept is only knowable by trying:
        // an OEM build can reject the accessibility type outright.
        var failure: Throwable? = null
        for (type in candidateWindowTypes()) {
            val layoutParams = WindowManager.LayoutParams(
                dp(OverlayMetrics.PILL_HEIGHT_DP) + padding * 2,
                dp(OverlayMetrics.PILL_HEIGHT_DP) + padding * 2,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(16)
                y = 0
            }

            val attempt = runCatching { windowManager.addView(composeView, layoutParams) }
            if (attempt.isSuccess) {
                view = composeView
                params = layoutParams
                windowTypeInUse = type
                lastWindowError = null
                // Snapped, not morphed: this window is about to scale in from 0.72, and animating two
                // sizes at once reads as a wobble.
                applyLayout(state.value, animate = false)
                animateIn(composeView)
                Log.i(TAG, "Overlay window added with type=$type")
                return true
            }
            failure = attempt.exceptionOrNull()
            Log.w(TAG, "addView failed for type=$type", failure)
        }

        lastWindowError = failure?.let { "${it::class.java.simpleName}: ${it.message}" }
        windowTypeInUse = null
        return false
    }

    /**
     * Window types to try, in order.
     *
     * The accessibility type is the correct one and needs no user grant. The application type is
     * only offered when "Display over other apps" has actually been granted, so a user who never
     * grants it is never blocked by it and one who does gets a working orb on an OEM build that
     * refuses the first.
     */
    private fun candidateWindowTypes(): List<Int> = buildList {
        val accessibilityOverlay = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val applicationOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // Order only — both are attempted either way, because which one the platform accepts is only
        // knowable by trying. The fallback leads once the accessibility type has been lost on this
        // device (see [useFallbackWindowTypeNext]).
        if (preferFallbackWindowType && canUseApplicationOverlay()) {
            add(applicationOverlay)
            add(accessibilityOverlay)
        } else {
            add(accessibilityOverlay)
            if (canUseApplicationOverlay()) add(applicationOverlay)
        }
    }

    /** Keyboard height changed while the pill is visible (IME show/hide animation). */
    fun updateKeyboardHeight(imeHeightPx: Int) {
        // Guarded, and applied without the morph: the service reports its current idea of the keyboard
        // height on *every* evaluation, and most of those repeat a value that is already in effect.
        // Re-writing the layout then would snap a state morph that is still in flight, which is the
        // one visible way this class could still make a tap look broken.
        if (imeHeightPx == keyboardHeightPx) return
        keyboardHeightPx = imeHeightPx
        if (view != null) applyLayout(state.value, animate = false)
    }

    /** Releases the owners backing the overlay. Call when the service itself goes away. */
    fun dispose() {
        stateOwner.dispose()
    }

    /** Removes the pill, animating it out first. */
    fun hide() {
        val composeView = view ?: return
        cancelLayoutAnimator()
        windowTypeInUse = null
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

    /**
     * Asks every later [show] to lead with the fallback window type.
     *
     * Called after a window disappeared on its own: whatever the platform's reason, the type it just
     * lost is the one to stop trying first.
     */
    fun useFallbackWindowTypeNext() {
        preferFallbackWindowType = true
    }

    /**
     * Removes the window immediately, without the exit animation.
     *
     * Used when the window has to be re-created (it vanished, or the service reconnected): the
     * animated path would race with the replacement `addView`.
     */
    fun removeNow() {
        removePending?.let { mainHandler.removeCallbacks(it) }
        removePending = null
        cancelLayoutAnimator()
        val composeView = view
        view = null
        params = null
        windowTypeInUse = null
        if (composeView != null) {
            composeView.animate().cancel()
            runCatching { windowManager.removeView(composeView) }
                .onFailure { Log.w(TAG, "Overlay already detached", it) }
        }
    }

    /**
     * Moves the window to where [currentState] belongs, morphing when [animate] is set.
     *
     * Idle is a 48dp square and every working state is a wider capsule, and since the pill fills the
     * window, following the state is what makes a tap expand into the recording capsule instead of
     * drawing it clipped inside the old square.
     */
    private fun applyLayout(currentState: OverlayUiState, animate: Boolean = false) {
        val layoutParams = params ?: return
        val target = windowFor(currentState)

        if (!animate) {
            layoutAnimator?.cancel()
            layoutAnimator = null
            layoutTarget = target
            writeLayout(layoutParams, target)
            return
        }

        // Same destination as the morph already running (or as the window already has): nothing to do.
        if (layoutTarget == target &&
            (layoutAnimator?.isRunning == true || layoutMatches(layoutParams, target))
        ) {
            return
        }

        val from = OverlayMetrics.Window(
            width = layoutParams.width,
            height = layoutParams.height,
            x = layoutParams.x,
            y = layoutParams.y,
        )
        layoutTarget = target
        if (from == target) {
            layoutAnimator?.cancel()
            layoutAnimator = null
            return
        }

        layoutAnimator?.cancel()
        layoutAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MORPH_DURATION_MS
            interpolator = ANIMATION_INTERPOLATOR
            addUpdateListener { animator ->
                writeLayout(
                    layoutParams,
                    OverlayMetrics.Window.lerp(from, target, animator.animatedFraction),
                )
            }
            start()
        }
    }

    private fun layoutMatches(
        layoutParams: WindowManager.LayoutParams,
        window: OverlayMetrics.Window,
    ): Boolean = layoutParams.width == window.width &&
        layoutParams.height == window.height &&
        layoutParams.x == window.x &&
        layoutParams.y == window.y

    private fun writeLayout(layoutParams: WindowManager.LayoutParams, window: OverlayMetrics.Window) {
        layoutParams.width = window.width
        layoutParams.height = window.height
        layoutParams.x = window.x
        layoutParams.y = window.y
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
            .onFailure { Log.w(TAG, "Could not reposition the overlay", it) }
    }

    private fun windowFor(state: OverlayUiState): OverlayMetrics.Window {
        val metrics = screenSize()
        return OverlayMetrics.windowFor(
            state = state,
            idleSizeDp = settings.value.overlaySizeDp,
            screenWidthPx = metrics.first,
            screenHeightPx = metrics.second,
            keyboardHeightPx = keyboardHeightPx,
            density = density,
        )
    }

    /** Stops a morph in flight; the window is being removed or recreated underneath it. */
    private fun cancelLayoutAnimator() {
        layoutAnimator?.cancel()
        layoutAnimator = null
        layoutTarget = null
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

    private fun dp(value: Int): Int = OverlayMetrics.dp(value, density)

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

        private const val SHOW_DURATION_MS = 180L
        private const val HIDE_DURATION_MS = 150L
        private const val REMOVE_DELAY_MS = 160L

        /** How long the window takes to become the next state's rectangle. */
        private const val MORPH_DURATION_MS = 190L
        private const val IN_START_SCALE = 0.72f
        private const val OUT_END_SCALE = 0.86f

        private val ANIMATION_INTERPOLATOR =
            android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}