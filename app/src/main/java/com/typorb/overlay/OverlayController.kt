package com.typorb.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.typorb.data.TyporbSettings
import com.typorb.model.OverlayUiState
import com.typorb.ui.overlay.TyporbOverlayContent
import com.typorb.ui.theme.TyporbTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * Hosts the Typorb as a real window (`TYPE_ACCESSIBILITY_OVERLAY`) containing a single
 * [ComposeView], wrapped in an [OverlayDragHost] that owns every touch on it.
 *
 * ## Who sizes the window
 *
 * The composition does. [TyporbOverlayContent] animates the pill's rectangle and reports every frame
 * of that animation through `onPillBoxChanged`; this class turns each report into a
 * `WindowManager.LayoutParams` write. That ordering matters more than it looks: the window *is* the
 * clip rectangle the pill is drawn inside, so a window that lags its content cuts the content. The
 * morph used to run here, in a `ValueAnimator`, while Compose animated the same rectangle on its own
 * clock — which is exactly how a tap turned into a pill drawn inside a rectangle that had not caught
 * up yet.
 *
 * The window is `box + SHADOW_PADDING_DP` on each side so the soft shadow has somewhere to fall, and
 * its right and top edges are pinned to the [OverlayMetrics.Anchor] — the pill's top-right corner.
 *
 * ## Who owns the position
 *
 * The user, once they have dragged it. A drag writes the new corner into Settings through
 * [onAnchorChanged], so the orb is still where it was put after a reboot, and every later state
 * change grows the capsule *away* from the corner the user chose instead of snapping back to the
 * default. Until then the corner is recomputed from the keyboard height, which is what keeps the orb
 * 16dp above the keyboard as the IME rises and falls.
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
    /** Drives the user-customisable overlay appearance and the saved orb position. */
    private val settings: StateFlow<TyporbSettings>,
    private val onTap: () -> Unit,
    /** Long press: put the last transcript into the field again, without re-recording it. */
    private val onRetype: () -> Unit,
    /** Reports `WindowInsetsCompat.Type.ime()` observations as an IME-height fallback signal. */
    private val onImeInsetChanged: (Int) -> Unit,
    /** Whether the OEM fallback window type may be used, i.e. "Display over other apps" is granted. */
    private val canUseApplicationOverlay: () -> Boolean,
    /** The user dropped the orb here; the position is theirs from now on. */
    private val onAnchorChanged: (rightPx: Int, topPx: Int) -> Unit,
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

    private var host: OverlayDragHost? = null
    private var params: WindowManager.LayoutParams? = null
    private var keyboardHeightPx: Int = 0
    private var removePending: Runnable? = null

    /** The pill box the composition last reported, in dp. `null` until it reports once. */
    private var pillBoxDp: Pair<Int, Int>? = null

    /** The window rectangle already written, so a repeated evaluation is not an IPC round trip. */
    private var writtenWindow: OverlayMetrics.Window? = null

    /**
     * Where the orb's top-right corner is, in screen px.
     *
     * `null` means "wherever the keyboard puts it", which is the honest default: the orb belongs just
     * above the keyboard. It becomes a fact the moment the user drags the orb, and from then on it is
     * read back from Settings so it survives the window being recreated.
     */
    private var anchor: OverlayMetrics.Anchor? = null

    /** Press and drag state, read by the composition to animate the orb under the finger. */
    private val pressed: MutableState<Boolean> = mutableStateOf(false)
    private val dragging: MutableState<Boolean> = mutableStateOf(false)

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

    val isShowing: Boolean get() = host != null

    /**
     * Whether the orb's view is genuinely attached to the display.
     *
     * [isShowing] answers "did `addView` succeed"; this answers "is the window really there now". A
     * platform that removes an overlay window behind the app's back leaves the first `true` and the
     * second `false`, which is the only way the service can notice it has to add the orb again.
     */
    val isAttached: Boolean get() = host?.isAttachedToWindow == true

    /**
     * Shows the pill, or re-positions it if it is already up.
     *
     * @return `true` when a window is on screen afterwards, so the caller never records the overlay
     *   as visible after a failed [WindowManager.addView].
     */
    fun show(imeHeightPx: Int): Boolean {
        keyboardHeightPx = imeHeightPx
        // The saved position is re-read on every show, so a position the user reset in Settings takes
        // effect the next time the orb appears instead of only after a service restart.
        val saved = settings.value.overlayAnchor
        anchor = if (saved == null) null else OverlayMetrics.Anchor(saved.first, saved.second)

        val existing = host
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
            applyLayout()
            return true
        }
        removePending?.let { mainHandler.removeCallbacks(it) }
        removePending = null
        pillBoxDp = null
        writtenWindow = null

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

                TyporbTheme {
                    TyporbOverlayContent(
                        state = currentState,
                        pressed = pressed.value,
                        dragging = dragging.value,
                        cornerRadiusDp = currentSettings.overlayCornerRadiusDp,
                        showWaveform = currentSettings.waveformEnabled,
                        contentPadding = OverlayMetrics.SHADOW_PADDING_DP.dp,
                        idleSizeDp = currentSettings.overlaySizeDp,
                        onPillBoxChanged = ::onPillBoxChanged,
                    )
                }
            }
        }

        val dragHost = OverlayDragHost(appContext, dragCallbacks).apply {
            addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
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

            val attempt = runCatching { windowManager.addView(dragHost, layoutParams) }
            if (attempt.isSuccess) {
                host = dragHost
                params = layoutParams
                windowTypeInUse = type
                lastWindowError = null
                applyLayout()
                animateIn(dragHost)
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
        if (imeHeightPx == keyboardHeightPx) return
        keyboardHeightPx = imeHeightPx
        if (host != null) applyLayout()
    }

    /** Releases the owners backing the overlay. Call when the service itself goes away. */
    fun dispose() {
        stateOwner.dispose()
    }

    /** Removes the pill, animating it out first. */
    fun hide() {
        val dragHost = host ?: return
        dragHost.cancelGesture()
        windowTypeInUse = null
        val hideRunnable = Runnable {
            if (host === dragHost) {
                runCatching { windowManager.removeView(dragHost) }
                    .onFailure { Log.w(TAG, "Overlay already detached", it) }
                host = null
                params = null
                writtenWindow = null
            }
            removePending = null
        }
        animateOut(dragHost) { mainHandler.postDelayed(hideRunnable, REMOVE_DELAY_MS) }
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
        val dragHost = host
        host = null
        params = null
        writtenWindow = null
        windowTypeInUse = null
        if (dragHost != null) {
            dragHost.cancelGesture()
            dragHost.animate().cancel()
            runCatching { windowManager.removeView(dragHost) }
                .onFailure { Log.w(TAG, "Overlay already detached", it) }
        }
    }

    /**
     * The composition reported the box the pill lives in.
     *
     * Called once per animation frame while a morph is in flight, which is the point: the window has
     * to be the same rectangle the pill is being drawn into, on the same frame, or the pill is clipped
     * by its own window.
     */
    private fun onPillBoxChanged(widthDp: Int, heightDp: Int) {
        if (widthDp <= 0 || heightDp <= 0) return
        pillBoxDp = widthDp to heightDp
        applyLayout()
    }

    /**
     * Writes the window rectangle for the pill as it is right now.
     *
     * Idempotent and cheap on a repeat: the target is compared against the rectangle already written,
     * so the 600ms watchdog and every accessibility event can call it without a stream of
     * `updateViewLayout` transactions.
     */
    private fun applyLayout() {
        val layoutParams = params ?: return
        val pill = currentPillPx()
        val anchor = resolvedAnchor(pill.first, pill.second)
        val target = OverlayMetrics.windowFor(
            anchor = anchor,
            pillWidthPx = pill.first,
            pillHeightPx = pill.second,
            density = density,
        )
        if (target == writtenWindow) return
        writtenWindow = target
        layoutParams.width = target.width
        layoutParams.height = target.height
        layoutParams.x = target.x
        layoutParams.y = target.y
        runCatching { windowManager.updateViewLayout(host, layoutParams) }
            .onFailure { Log.w(TAG, "Could not reposition the overlay", it) }
    }

    /**
     * The pill's size in px for the window being written.
     *
     * The composition's own report wins whenever there is one: mid-morph it is the box that contains
     * both the outgoing and the incoming pill, and sizing the window to the *target* state instead
     * would cut the pill that is still leaving.
     */
    private fun currentPillPx(): Pair<Int, Int> {
        val boxDp = pillBoxDp ?: OverlayMetrics.pillSizeDp(state.value, settings.value.overlaySizeDp)
        return dp(boxDp.first) to dp(boxDp.second)
    }

    /** The anchor to lay out from: the user's, clamped, or the keyboard's default. */
    private fun resolvedAnchor(pillWidthPx: Int, pillHeightPx: Int): OverlayMetrics.Anchor {
        val (screenWidthPx, screenHeightPx) = screenSize()
        val stored = anchor
        return if (stored == null) {
            OverlayMetrics.defaultAnchor(
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                keyboardHeightPx = keyboardHeightPx,
                pillWidthPx = pillWidthPx,
                pillHeightPx = pillHeightPx,
                density = density,
            )
        } else {
            OverlayMetrics.clampAnchor(
                anchor = stored,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                keyboardHeightPx = keyboardHeightPx,
                pillWidthPx = pillWidthPx,
                pillHeightPx = pillHeightPx,
                density = density,
            )
        }
    }

    /**
     * Drag plumbing: the window's top-left *is* the drag's coordinate, and the anchor is derived from
     * it rather than the other way round.
     *
     * Going through the anchor on every move is what makes the clamped edges behave — the orb stops at
     * the screen edge or the keyboard's top and resumes following the finger the instant it comes
     * back, because the next move is measured from where the finger went down, not from the last place
     * the orb was allowed to be.
     */
    private val dragCallbacks = object : OverlayDragHost.Callbacks {

        override fun windowTopLeft(): Pair<Int, Int> =
            params?.let { it.x to it.y } ?: (0 to 0)

        override fun moveWindowTo(x: Int, y: Int) {
            val pill = currentPillPx()
            val padding = dp(OverlayMetrics.SHADOW_PADDING_DP)
            val (screenWidthPx, screenHeightPx) = screenSize()
            anchor = OverlayMetrics.clampAnchor(
                anchor = OverlayMetrics.Anchor(
                    rightPx = x + pill.first + padding,
                    topPx = y + padding,
                ),
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                keyboardHeightPx = keyboardHeightPx,
                pillWidthPx = pill.first,
                pillHeightPx = pill.second,
                density = density,
            )
            applyLayout()
        }

        override fun onPressChanged(pressedNow: Boolean) {
            pressed.value = pressedNow
        }

        override fun onDragStarted() {
            dragging.value = true
        }

        override fun onDragEnded() {
            dragging.value = false
            val dropped = anchor ?: return
            onAnchorChanged(dropped.rightPx, dropped.topPx)
        }

        // Qualified on purpose: `onTap()` inside this object resolves to *this* override, so an
        // unqualified call would be an infinite recursion rather than a forwarded tap.
        override fun onTap(): Unit = this@OverlayController.onTap()

        override fun onLongPress(): Unit = onRetype()
    }

    private fun animateIn(target: OverlayDragHost) {
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

    private fun animateOut(target: OverlayDragHost, onEnd: () -> Unit) {
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

        private const val IN_START_SCALE = 0.72f
        private const val OUT_END_SCALE = 0.86f

        private val ANIMATION_INTERPOLATOR =
            android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
