package com.typorb.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.typorb.TyporbApp
import com.typorb.TyporbContainer
import com.typorb.diagnostics.OrbDiagnosticsBus
import com.typorb.domain.DictationCoordinator
import com.typorb.domain.TyporbException
import com.typorb.model.OverlayUiState
import com.typorb.model.ProcessingEngine
import com.typorb.overlay.ImeProbeController
import com.typorb.overlay.OverlayController
import com.typorb.util.Permissions
import com.typorb.util.Haptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The system-wide half of Typorb.
 *
 * Responsibilities:
 *  * decide *when* the floating pill may exist — a focused, editable field **and** a visible IME;
 *  * keep the pill 16dp above the keyboard's real top edge, pinned to the right;
 *  * forward taps to the [DictationCoordinator];
 *  * write the finished text back into the focused field (with the clipboard fallback), then hide.
 *
 * It is also a [LifecycleOwner] (backed by a [LifecycleRegistry] driven from the service callbacks)
 * because the Compose overlay window requires a lifecycle owner on its view tree.
 */
class TyporbAccessibilityService : AccessibilityService(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var container: TyporbContainer
    private lateinit var injector: TextInjector
    private lateinit var imeDetector: ImeDetector
    private lateinit var haptics: Haptics
    private lateinit var overlay: OverlayController
    private var imeProbe: ImeProbeController? = null

    private var coordinator: DictationCoordinator? = null
    private var overlayVisible = false
    private var lastEvaluationMs = 0L

    /** Last breadcrumb emitted, so an unchanged verdict is not logged 25 times a second. */
    private var lastPublishedSummary: String? = null
    private var lastDictationStartedAtMs = 0L

    /**
     * Cached "an editable field holds focus", refreshed only from events raised by the app.
     *
     * Kept as state rather than re-derived, because the only event that announces the keyboard has
     * risen comes from the IME, and reading focus on that event resolves to the keyboard window
     * instead of the app — which made the pill hide itself the instant it became eligible to show.
     */
    @Volatile
    private var editableFieldFocused: Boolean = false

    /**
     * Short re-check loop that runs while a field holds focus but the keyboard has not appeared yet.
     *
     * The keyboard animates in *after* the tap, and several OEM keyboards post no further
     * accessibility event when they finish. Without this poll the pill would only ever be evaluated
     * at tap time — when the IME is still hidden — and would never appear at all.
     */
    private var imeWatchJob: Job? = null

    private val windowManager: WindowManager
        get() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            setUp()
            OrbDiagnosticsBus.update {
                it.copy(serviceConnected = true, setupError = null)
            }
            OrbDiagnosticsBus.note("service connected")
        }.onFailure { error ->
            // Previously this failure was logged and then swallowed, leaving the process alive with
            // no overlay and no explanation anywhere the user could see. It is now recorded where
            // the Settings screen renders it.
            Log.e(TAG, "Typorb could not start", error)
            OrbDiagnosticsBus.update {
                it.copy(
                    serviceConnected = false,
                    setupError = "${error::class.java.simpleName}: ${error.message}",
                )
            }
            OrbDiagnosticsBus.note("startup FAILED: ${error::class.java.simpleName}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        OrbDiagnosticsBus.update { current ->
            current.copy(
                eventCount = current.eventCount + 1,
                lastEventPackage = event.packageName?.toString(),
                lastEventType = event.eventType,
            )
        }

        // Focus and keyboard visibility are separate questions, and the events that answer them
        // come from different windows.
        //
        // The `TYPE_WINDOW_STATE_CHANGED` that fires when the keyboard rises carries the *IME's*
        // package name, so those events must not be dropped — they are the only notice that the
        // keyboard became visible. But an IME event's root is the keyboard itself, and
        // `getRootInActiveWindow()` can resolve to that keyboard window rather than the app
        // underneath it. Re-reading focus on such an event finds no editable field and hides the
        // pill at the exact moment it should appear.
        //
        // So: focus is only ever re-read on events from the app, and cached. Keyboard visibility is
        // re-read on everything.
        val fromIme = imeDetector.isImePackage(event.packageName?.toString())

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> if (fromIme) onKeyboardChanged() else evaluateOverlayVisibility()
        }
    }

    /**
     * Handles an event raised by the keyboard itself: refresh only the keyboard geometry.
     *
     * Focus is deliberately not re-read, because the active window during these events is the IME.
     */
    private fun onKeyboardChanged() {
        if (!::overlay.isInitialized) return
        val now = System.currentTimeMillis()
        if (now - lastEvaluationMs < EVALUATION_THROTTLE_MS) return
        lastEvaluationMs = now
        Log.i(TAG, "IME event → re-evaluating keyboard only")
        applyOverlayState(refreshFocus = false)
    }

    override fun onInterrupt() {
        coordinator?.reset()
    }

    override fun onDestroy() {
        stopImeWatch()
        imeProbe?.stop()
        imeProbe = null
        if (::overlay.isInitialized) overlay.dispose()
        OrbDiagnosticsBus.update { it.copy(serviceConnected = false, probeAttached = false) }
        OrbDiagnosticsBus.note("service destroyed")
        if (::overlay.isInitialized) hideOverlay()
        coordinator?.release()
        coordinator = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    // ------------------------------------------------------------------ setup

    private fun setUp() {
        container = TyporbApp.containerOf(this)
        injector = TextInjector(this)
        imeDetector = ImeDetector(this, this)
        haptics = Haptics(this) { container.settingsRepository.current().hapticsEnabled }

        val dictation = container.createCoordinator(::onTextReady)
        coordinator = dictation

        overlay = OverlayController(
            context = this,
            lifecycleOwner = this,
            state = dictation.state,
            settings = container.settingsRepository.settings,
            onTap = ::onPillTapped,
            onImeInsetChanged = imeDetector::reportInsetFallback,
            // "Display over other apps" is optional. It only decides whether a second window type
            // may be attempted when the platform refuses the accessibility one.
            canUseApplicationOverlay = { Permissions.isOverlayPermissionGranted(this) },
        )

        // Attached before the first event can arrive, so the inset signal is never waiting on the
        // orb window that only appears once a keyboard has already been detected.
        val probe = ImeProbeController(this) { heightPx ->
            imeDetector.reportInsetFallback(heightPx)
        }
        imeProbe = probe
        val probeAttached = probe.start()
        OrbDiagnosticsBus.update {
            it.copy(
                imePackage = imeDetector.currentImePackage,
                probeAttached = probeAttached,
                probeWindowError = probe.lastError,
                overlayPermissionGranted = Permissions.isOverlayPermissionGranted(this),
            )
        }
        Log.i(TAG, "IME probe window attached=$probeAttached ime=${imeDetector.currentImePackage}")

        // Warm the offline model up front only when Local mode is selected.
        lifecycleScope.launch {
            container.settingsRepository.settings
                .map { it.engine }
                .distinctUntilChanged()
                .filter { it == ProcessingEngine.LOCAL }
                .collect { TyporbApp.warmUp(this@TyporbAccessibilityService) }
        }
    }

    

    // --------------------------------------------------------------- detection

    /**
     * The one place that decides whether the pill should exist.
     *
     * Both conditions must hold: an editable field holds input focus, and the IME is on screen.
     * Either one dropping out hides the pill immediately.
     */
    private fun evaluateOverlayVisibility() {
        if (!::overlay.isInitialized) return
        val now = System.currentTimeMillis()
        if (now - lastEvaluationMs < EVALUATION_THROTTLE_MS) return
        lastEvaluationMs = now

        // [applyOverlayState] answers "does a field hold focus", i.e. "are we only waiting on the
        // keyboard". These branches were previously swapped, which meant the poll that exists
        // precisely to catch a slow-rising keyboard was cancelled at the moment it was needed and
        // started when it was pointless.
        if (applyOverlayState(refreshFocus = true)) {
            startImeWatch()
        } else {
            stopImeWatch()
        }
    }

    /**
     * Applies the current focus + IME state to the overlay window.
     *
     * @param refreshFocus whether to re-read the focused field. Pass `false` whenever the caller is
     *   running on a timer or on an IME event: during those the active window is the keyboard, so a
     *   fresh read would clear [editableFieldFocused] and hide a pill that is correctly eligible.
     * @return `true` when an editable field holds focus — i.e. we are only waiting on the keyboard,
     *   and a re-check is worth scheduling.
     */
    private fun applyOverlayState(refreshFocus: Boolean): Boolean {
        if (!::overlay.isInitialized) return false

        if (refreshFocus) {
            editableFieldFocused =
                EditableFieldInspector.findFocusedEditable(getRootInActiveWindow()) != null
        }

        val imeState = imeDetector.currentState(screenHeightPx())
        val shouldShow = editableFieldFocused && imeState.visible
        if (shouldShow) {
            if (overlayVisible) {
                overlay.updateKeyboardHeight(imeState.heightPx)
            } else {
                // Only treat the pill as shown when the window really made it on screen.
                overlayVisible = overlay.show(imeState.heightPx)
            }
        } else {
            hideOverlay()
        }

        publishDiagnostics(imeState.heightPx, imeState.visible, shouldShow)
        return editableFieldFocused
    }

    /** Mirrors the decision just made into the bus the Settings screen renders. */
    private fun publishDiagnostics(imeHeightPx: Int, imeVisible: Boolean, shouldShow: Boolean) {
        val nextOverlay = if (shouldShow) overlayVisible else false
        val insetPx = if (::imeDetector.isInitialized) imeDetector.insetHeightPx else 0
        val windowsSeen = if (::imeDetector.isInitialized) imeDetector.lastWindowsSeen else 0
        val imeWindowFound = if (::imeDetector.isInitialized) imeDetector.lastImeWindowFound else false
        val summary = "focus=$editableFieldFocused ime=$imeVisible/${imeHeightPx}px orb=$nextOverlay"

        OrbDiagnosticsBus.update {
            it.copy(
                editableFieldFocused = editableFieldFocused,
                overlayVisible = nextOverlay,
                imeVisible = imeVisible,
                imeHeightPx = imeHeightPx,
                imeInsetPx = insetPx,
                windowsSeen = windowsSeen,
                imeWindowFound = imeWindowFound,
                overlayWindowError = if (::overlay.isInitialized) overlay.lastWindowError else null,
                overlayWindowType = if (::overlay.isInitialized) {
                    overlay.windowTypeInUse?.let { windowTypeName(it) }
                } else {
                    null
                },
                lastEvaluationAtMs = System.currentTimeMillis(),
            )
        }

        // The watch loop re-evaluates up to 25×/s, so a breadcrumb and a log line are only worth
        // emitting when the verdict actually changed.
        if (summary != lastPublishedSummary) {
            lastPublishedSummary = summary
            Log.i(TAG, "evaluated: $summary")
            OrbDiagnosticsBus.note(summary)
        }
    }

    /**
     * Polls briefly while a field is focused but the keyboard is still animating in, so the pill
     * appears the moment the IME window lands even when no accessibility event announces it.
     *
     * Focus is not re-read on each tick: by the time a tick fires the keyboard may already be the
     * active window, so the cached value from the app's own events is the trustworthy one.
     */
    private fun startImeWatch() {
        if (imeWatchJob?.isActive == true) return
        imeWatchJob = lifecycleScope.launch {
            try {
                val deadline = SystemClock.uptimeMillis() + IME_WATCH_WINDOW_MS
                while (SystemClock.uptimeMillis() < deadline) {
                    delay(IME_WATCH_INTERVAL_MS)
                    // Keep polling for as long as a field still holds focus; that window is exactly
                    // the one where the keyboard is rising. Previously this returned on the *first*
                    // tick where focus was present, i.e. before the keyboard had appeared.
                    if (!applyOverlayState(refreshFocus = false)) return@launch
                }
            } finally {
                imeWatchJob = null
            }
        }
    }

    private fun stopImeWatch() {
        imeWatchJob?.cancel()
        imeWatchJob = null
    }

    private fun onPillTapped() {
        val dictation = coordinator ?: return
        dictation.onToggle()
        if (dictation.state.value is OverlayUiState.Recording) {
            lastDictationStartedAtMs = System.currentTimeMillis()
            haptics.tick()
        }
    }

    /** Files the finished text in the encrypted Transcripts vault. */
    private fun recordTranscript(
        text: String,
        settings: com.typorb.data.TyporbSettings,
        latencyMs: Long,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        container.transcriptRepository.add(
            com.typorb.data.Transcript(
                id = "${System.currentTimeMillis()}-${trimmed.hashCode()}",
                text = trimmed,
                timestampMs = System.currentTimeMillis(),
                mode = settings.contextMode,
                engine = settings.engine,
                latencyMs = latencyMs.coerceAtLeast(0L),
            ),
        )
    }

    private suspend fun onTextReady(text: String) {
        val settingsAtInjection = container.settingsRepository.current()
        val startedAtMs = System.currentTimeMillis() - lastDictationStartedAtMs
        val result = injector.inject(text)
        when (result.method) {
            TextInjector.Method.ACTION_SET_TEXT,
            TextInjector.Method.CLIPBOARD_PASTE,
            -> {
                haptics.confirm()
                recordTranscript(text, settingsAtInjection, startedAtMs)
                Log.i(TAG, "Text injected via ${result.method}")
            }
            TextInjector.Method.NONE -> {
                haptics.reject()
                throw TyporbException(
                    message = "Couldn't insert the text. Tap the field and try again.",
                    retryable = true,
                )
            }
        }
        hideOverlay()
    }

    private fun hideOverlay() {
        if (!overlayVisible) return
        overlayVisible = false
        coordinator?.reset()
        if (::overlay.isInitialized) overlay.hide()
    }

    @Suppress("DEPRECATION")
    private fun screenHeightPx(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windowManager.currentWindowMetrics.bounds.height()
    } else {
        val metrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        metrics.heightPixels
    }

    /** Readable window-type name for the debug console. */
    private fun windowTypeName(type: Int): String = when (type) {
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility overlay"
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY -> "application overlay"
        else -> "type $type"
    }

    private companion object {
        const val TAG = "TyporbService"

        /** Focus events arrive in bursts; one evaluation per frame-ish is plenty. */
        const val EVALUATION_THROTTLE_MS = 40L

        /** How long to keep looking for a keyboard after a field takes focus. */
        const val IME_WATCH_WINDOW_MS = 3_000L

        /** Keyboard-show animation on a budget device is ~200-400ms. */
        const val IME_WATCH_INTERVAL_MS = 120L
    }
}