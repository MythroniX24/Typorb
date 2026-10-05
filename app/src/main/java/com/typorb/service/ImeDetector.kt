package com.typorb.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Detects whether the soft keyboard (IME) is on screen and exactly how tall it is.
 *
 * Primary signal: [AccessibilityService.getWindows] — enabled through `flagRetrieveInteractiveWindows`
 * in `res/xml/accessibility_service_config.xml` — returns every window on screen, and the platform
 * tags the keyboard with [AccessibilityWindowInfo.TYPE_INPUT_METHOD]. That is an authoritative,
 * OEM-independent answer to "is the IME up", which package-name matching alone is not.
 *
 * The height comes from the IME window's top edge (see [ImeGeometry.heightAboveBottomInset]).
 *
 * Fallback chain, in order: the window type → a package-name match for OEM builds that fail to tag
 * the type → an externally measured `WindowInsetsCompat.Type.ime()` bottom inset.
 */
class ImeDetector(context: Context, private val service: AccessibilityService) {

    /** Current keyboard state in screen pixels. */
    data class ImeState(
        val visible: Boolean,
        val heightPx: Int,
    ) {
        companion object {
            val Hidden = ImeState(visible = false, heightPx = 0)
        }
    }

    private val appContext = context.applicationContext

    /** Package of the currently selected IME, e.g. `com.google.android.inputmethod.latin`. */
    private val imePackage: String? by lazy {
        val id = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull().orEmpty()
        id.substringBefore('/').takeIf { it.isNotBlank() }
    }

    /** Last value seen through [reportInsetFallback], used when window introspection fails. */
    @Volatile
    private var insetFallbackHeight: Int = 0

    /** Diagnostics only — how the last [currentState] call went, surfaced in Settings. */
    @Volatile
    var lastWindowsSeen: Int = 0
        private set

    /** Diagnostics only — whether the last [currentState] call found an IME-tagged window. */
    @Volatile
    var lastImeWindowFound: Boolean = false
        private set

    /** Diagnostics only — the inset reading currently held, surfaced in Settings. */
    val insetHeightPx: Int get() = insetFallbackHeight

    /** The IME package the system reports as default, or `null` when it cannot be read. */
    val currentImePackage: String? get() = imePackage

    /**
     * Feeds an externally measured IME height (`WindowInsetsCompat.Type.ime()` bottom inset) in as a
     * secondary signal. Pass `0` when the IME is known to be hidden.
     */
    fun reportInsetFallback(heightPx: Int) {
        insetFallbackHeight = heightPx.coerceAtLeast(0)
    }

    fun currentState(displayHeightPx: Int): ImeState {
        if (displayHeightPx <= 0) return ImeState.Hidden
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        lastWindowsSeen = windows.size
        lastImeWindowFound = false
        if (windows.isEmpty()) return insetState(displayHeightPx)

        // 1. Authoritative: the platform's own IME window type.
        val typedWindow = windows.firstOrNull { window ->
            runCatching { window.type }.getOrNull() == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        lastImeWindowFound = typedWindow != null
        if (typedWindow != null) {
            // The platform has told us this window is the keyboard. That is enough to act on even if
            // its root node cannot be measured, so fall back to a typical height rather than
            // reporting "no keyboard" and keeping the pill hidden forever.
            return ImeState(
                visible = true,
                heightPx = imeHeightOf(typedWindow, displayHeightPx, assumeIme = true),
            )
        }

        // 2. Package match, for OEM builds that do not tag the window type.
        val packageWindow = windows.firstOrNull { window ->
            val pkg = runCatching { window.root?.packageName?.toString() }.getOrNull()
            pkg != null && pkg == imePackage
        }
        val packageHeight = packageWindow?.let { imeHeightOf(it, displayHeightPx, assumeIme = false) } ?: 0
        if (packageHeight > 0) return ImeState(visible = true, heightPx = packageHeight)

        // 3. Whatever the overlay last measured through WindowInsets.
        return insetState(displayHeightPx)
    }

    private fun insetState(displayHeightPx: Int): ImeState =
        if (insetFallbackHeight >= ImeGeometry.MIN_KEYBOARD_HEIGHT_PX) {
            ImeState(
                visible = true,
                heightPx = insetFallbackHeight.coerceAtMost(displayHeightPx),
            )
        } else {
            ImeState.Hidden
        }

    /** Keyboard height for [window], or `0` when the window does not look like a keyboard. */
    private fun imeHeightOf(
        window: AccessibilityWindowInfo,
        displayHeightPx: Int,
        assumeIme: Boolean,
    ): Int {
        val rect = android.graphics.Rect()
        val measured = runCatching { window.root?.getBoundsInScreen(rect) }.isSuccess &&
            rect.height() > 0
        // AccessibilityWindowInfo#getBounds is a hidden API, so the rectangle comes from the window's
        // root node instead. A null or empty root means we learned nothing.
        val height = if (measured) {
            ImeGeometry.heightAboveBottomInset(
                displayHeightPx = displayHeightPx,
                windowTopPx = rect.top,
                windowHeightPx = rect.height(),
                windowWidthPx = rect.width(),
            )
        } else {
            0
        }
        if (height > 0) return height
        return if (assumeIme) ImeGeometry.fallbackHeight(displayHeightPx) else 0
    }
}

/**
 * Pure geometry for "how tall is the keyboard", kept Android-free so it can be unit-tested.
 *
 * The one rule that matters: the IME window's **top** edge is trustworthy, its **bottom** edge is
 * not. A keyboard sits *above* the navigation bar, so requiring `bounds.bottom >= screenHeight`
 * — as an earlier revision did — is false on essentially every device with a nav bar, and the pill
 * therefore never appeared. Likewise a hard "the keyboard must be ≥40% of the screen" rule is
 * wrong: Gboard on a 720×1520 Redmi 8A is roughly a third of the display.
 */
object ImeGeometry {

    /**
     * Below this, the "keyboard" is a stray popup or a one-row suggestion strip, not an IME that
     * should push the pill up the screen.
     */
    const val MIN_KEYBOARD_HEIGHT_PX = 120

    /**
     * Height assumed when the platform has confirmed a keyboard window exists but its bounds could
     * not be read.
     *
     * Being wrong here only moves the pill to a slightly wrong height; treating the same situation
     * as "no keyboard" hides the pill permanently, with no way for the user to tell why.
     */
    fun fallbackHeight(displayHeightPx: Int): Int =
        (displayHeightPx * FALLBACK_KEYBOARD_RATIO).toInt().coerceAtLeast(MIN_KEYBOARD_HEIGHT_PX)

    private const val FALLBACK_KEYBOARD_RATIO = 0.4f

    /**
     * @return the keyboard height in px, or `0` when the window cannot be a full keyboard.
     */
    fun heightAboveBottomInset(
        displayHeightPx: Int,
        windowTopPx: Int,
        windowHeightPx: Int,
        windowWidthPx: Int,
    ): Int {
        if (displayHeightPx <= 0) return 0
        if (windowHeightPx <= 0 || windowWidthPx <= 0) return 0
        if (windowTopPx < 0 || windowTopPx >= displayHeightPx) return 0

        // Measured from the top edge down to the screen bottom. This intentionally includes the
        // navigation-bar strip the keyboard rests on: over-estimating by at most a nav bar height
        // keeps the pill safely above the keyboard, whereas under-estimating hides it behind it.
        val height = displayHeightPx - windowTopPx
        if (height < MIN_KEYBOARD_HEIGHT_PX) return 0
        return height.coerceAtMost(displayHeightPx)
    }
}