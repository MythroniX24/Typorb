package com.typorb.service

import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Detects whether the soft keyboard (IME) is on screen and exactly how tall it is.
 *
 * Primary signal: [AccessibilityService.getWindows] — enabled through `flagRetrieveInteractiveWindows`
 * in `res/xml/accessibility_service_config.xml` — returns every window on screen, including the IME
 * window, with its real bounds. That gives a pixel-accurate keyboard top edge on every OEM skin,
 * which `WindowInsets.Type.ime()` alone does not (an accessibility overlay window does not reliably
 * receive the IME insets of the app underneath it).
 *
 * Fallback: if the window list is unavailable (some OEM builds strip IME windows), the caller passes
 * the height it measured itself via `WindowInsetsCompat`.
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

    /** `true` when [packageName] is the IME whose windows we ignore while tracking focus. */
    fun isImePackage(packageName: String?): Boolean =
        packageName != null && imePackage != null && packageName == imePackage

    /**
     * Feeds an externally measured IME height (`WindowInsetsCompat.Type.ime()` bottom inset) in as a
     * secondary signal. Pass `0` when the IME is known to be hidden.
     */
    fun reportInsetFallback(heightPx: Int) {
        insetFallbackHeight = heightPx.coerceAtLeast(0)
    }

    fun currentState(displayHeightPx: Int): ImeState {
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        val imeWindow = windows.firstOrNull { window ->
            val packageName = window.root?.packageName?.toString()
            packageName != null && packageName == imePackage
        }

        if (imeWindow != null) {
            val bounds = Rect()
            // AccessibilityWindowInfo#getBounds is a hidden API; the IME window's root node carries
            // the same rectangle through the public AccessibilityNodeInfo#getBoundsInScreen.
            imeWindow.root?.getBoundsInScreen(bounds)
            val height = displayHeightPx - bounds.top
            val spansScreen = bounds.width() > 0 && bounds.bottom >= displayHeightPx
            val isFullHeight = height >= displayHeightPx * MIN_KEYBOARD_RATIO
            if (height in 1..displayHeightPx && spansScreen && isFullHeight) {
                return ImeState(visible = true, heightPx = height)
            }
            // A floating or split keyboard is not the full IME: keep the overlay hidden.
            return ImeState.Hidden
        }

        if (insetFallbackHeight > 0) {
            return ImeState(visible = true, heightPx = insetFallbackHeight.coerceAtMost(displayHeightPx))
        }

        return ImeState.Hidden
    }

    private companion object {
        /** Real full-screen IMEs occupy at least ~40% of the display height. */
        const val MIN_KEYBOARD_RATIO = 0.4f
    }
}