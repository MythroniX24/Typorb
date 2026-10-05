package com.typorb.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.PowerManager
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
import com.typorb.diagnostics.DictationStages
import com.typorb.diagnostics.OrbDiagnosticsBus
import com.typorb.domain.DictationCoordinator
import com.typorb.domain.TyporbException
import com.typorb.model.OverlayUiState
import com.typorb.model.ProcessingEngine
import com.typorb.model.orbVisibility
import com.typorb.model.pinsOrb
import com.typorb.overlay.ImeProbeController
import com.typorb.overlay.OverlayController
import com.typorb.util.Permissions
import com.typorb.util.Haptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The system-wide half of Typorb.
 *
 * Responsibilities:
 *  * decide *when* the floating pill may exist — a soft keyboard on screen (the editable-field
 *    reading is evidence and diagnostics, deliberately not a precondition; see [applyOverlayState]);
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

    /** `true` once [setUp] has built the object graph for a connection. */
    private var setUpDone = false

    private var coordinator: DictationCoordinator? = null
    private var overlayVisible = false
    private var lastEvaluationMs = 0L

    /** Last breadcrumb emitted, so an unchanged verdict is not logged 25 times a second. */
    private var lastPublishedSummary: String? = null
    private var lastDictationStartedAtMs = 0L

    /**
     * Whether an editable field held focus at the last evaluation.
     *
     * This used to be the gate on visibility, cached and refreshed only from events raised by an app.
     * It is now reported rather than acted on: on several OEM builds the focused field cannot be read
     * at all, and gating on it kept the pill off screen while the keyboard was plainly up. It still
     * answers "is a missing keyboard worth waiting for", and it is on the debug console.
     */
    @Volatile
    private var editableFieldFocused: Boolean = false

    /**
     * Which lookup produced [editableFieldFocused], for the debug console.
     *
     * "No field" and "a field we cannot read" are the same two words in a bug report and need
     * completely different fixes, so which of the three routes answered is published with the flag.
     */
    @Volatile
    private var focusSource: EditableFieldInspector.Source = EditableFieldInspector.Source.NONE

    /**
     * How many times the overlay window has been asked for.
     *
     * Zero after a keyboard has been seen means the orb was never eligible; a rising number with an
     * orb that never appears means WindowManager is refusing it — and `overlayWindowError` says why.
     */
    @Volatile
    private var overlayShowAttempts: Int = 0

    /**
     * Short re-check loop that runs while a field holds focus but the keyboard has not appeared yet.
     *
     * The keyboard animates in *after* the tap, and several OEM keyboards post no further
     * accessibility event when they finish. Without this poll the pill would only ever be evaluated
     * at tap time — when the IME is still hidden — and would never appear at all.
     */
    private var imeWatchJob: Job? = null

    /**
     * The always-on safety net: a slow poll that evaluates the overlay whether or not a single
     * accessibility event ever arrives.
     *
     * Everything used to hang off `onAccessibilityEvent`. That is the platform's promise, and on
     * several OEM builds it is not kept — a service that is connected, enabled and correct can be
     * handed no event when a keyboard appears, and the only symptom is an orb that never shows. The
     * loop below is independent of that promise: it re-runs the same decision every
     * [WATCHDOG_INTERVAL_MS] while the screen is on, so the worst case for a missing event becomes an
     * orb that appears half a second late instead of one that never appears.
     */
    private var watchdogJob: Job? = null

    /** How many times the watchdog has ticked, and how many of those saw a keyboard. */
    @Volatile
    private var watchdogTicks: Long = 0

    @Volatile
    private var watchdogKeyboardTicks: Long = 0

    /** Whether a dictation in flight is holding the orb up without a keyboard behind it. */
    @Volatile
    private var orbPinned: Boolean = false

    private val powerManager: PowerManager
        get() = getSystemService(POWER_SERVICE) as PowerManager

    private val windowManager: WindowManager
        get() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            // `onServiceConnected` is not once per process: an OEM that kills the service and lets
            // the user re-enable it can deliver it again on the same instance. Without this the
            // second connection would add a second overlay window on top of the first and start a
            // second watchdog, leaving two orbs stacked and two loops driving them.
            if (setUpDone) tearDownWindows()
            setUp()
            setUpDone = true
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

        // Every subscribed event is a chance to re-check the screen, and no event's origin changes
        // which answer is correct any more: focus comes from a lookup that searches every window, so
        // running it while the keyboard is up is right rather than harmful. The old split — re-read
        // focus only for events raised by an app, and cache it the rest of the time — is what left the
        // orb hidden on builds where no such event ever arrived after the keyboard appeared.
        when (event.eventType) {
            // A tap, a focus move or a window transition is the moment a keyboard is about to rise,
            // so arm the short poll as well: several OEM keyboards never announce that they have
            // finished appearing.
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> evaluateOverlayVisibility(armWatch = true)

            // Re-evaluate, but do not arm a new poll: this one arrives per keystroke.
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            -> evaluateOverlayVisibility(armWatch = false)
        }
    }

    override fun onInterrupt() {
        coordinator?.reset()
    }

    override fun onDestroy() {
        stopImeWatch()
        watchdogJob?.cancel()
        watchdogJob = null
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

        // Started last, after every signal it reads exists.
        startWatchdog()
    }

    /**
     * Releases the windows and jobs left behind by a previous connection (see [onServiceConnected]).
     */
    private fun tearDownWindows() {
        stopImeWatch()
        watchdogJob?.cancel()
        watchdogJob = null
        imeProbe?.stop()
        imeProbe = null
        if (::overlay.isInitialized) {
            overlay.removeNow()
            overlay.dispose()
        }
        coordinator?.release()
        coordinator = null
        overlayVisible = false
    }

    

    // --------------------------------------------------------------- detection

    /**
     * The one place that decides whether the pill should exist.
     *
     * @param armWatch whether this evaluation may start the short poll that catches a keyboard which
     *   never announces itself. `true` for the events that precede a keyboard — a tap, a focus move,
     *   a window change — and `false` for the per-keystroke ones.
     */
    private fun evaluateOverlayVisibility(armWatch: Boolean) {
        if (!::overlay.isInitialized) return
        val now = System.currentTimeMillis()
        if (now - lastEvaluationMs < EVALUATION_THROTTLE_MS) return
        lastEvaluationMs = now

        val imeState = applyOverlayState()
        // Only ever look for a keyboard that is not there yet, and only from an interactive event:
        // once the keyboard is up there is nothing left to wait for, and re-arming on every keystroke
        // would run a 120 ms timer for as long as the user keeps typing.
        // A pinned orb has nothing left to wait for either: work is in flight, and the poll exists
        // only to catch a keyboard that has not appeared yet.
        if (imeState.visible || orbPinned) stopImeWatch() else if (armWatch) startImeWatch()
    }

    /**
     * Applies the current focus + IME state to the overlay window.
     *
     * **The keyboard being on screen is the whole condition.** It used to be "an editable field holds
     * focus *and* the keyboard is up", and the first half is not dependable: on several OEM builds —
     * MIUI among them — the focused field either does not report itself as editable or cannot be read
     * at all, so the pill stayed hidden on a phone whose keyboard was plainly visible. The keyboard is
     * the reliable half of the pair: the platform only puts a soft keyboard on screen for a
     * text-editing session, and the keyboard's own window is a fact any accessibility service with
     * `flagRetrieveInteractiveWindows` can read.
     *
     * The field is therefore evidence and diagnostics rather than a precondition. It still decides
     * whether a keyboard that has not appeared yet is worth polling for.
     *
     * @return the keyboard state this evaluation acted on.
     */
    private fun applyOverlayState(checkWindowLiveness: Boolean = false): ImeDetector.ImeState {
        if (!::overlay.isInitialized) return ImeDetector.ImeState.Hidden

        // Re-read on every evaluation, IME-sourced events included: the probe searches every window,
        // so unlike the old active-window lookup it cannot be shadowed by the keyboard window.
        val focus = EditableFieldInspector.probe(this, getRootInActiveWindow())
        editableFieldFocused = focus.isFieldFocused
        focusSource = focus.source

        val dictation = currentDictationState()
        orbPinned = dictation.pinsOrb()

        val imeState = imeDetector.currentState(screenHeightPx())

        // A window the platform took away behind our back — an OEM's own housekeeping, a display
        // change — would otherwise leave the orb recorded as visible and never shown again. Only the
        // watchdog asks this question: it ticks far enough apart that a freshly added window has
        // certainly attached, so a `false` here is a fact rather than a race with the first frame.
        if (checkWindowLiveness && overlayVisible && !overlay.isAttached) {
            OrbDiagnosticsBus.note("orb window vanished; recreating")
            overlay.removeNow()
            // A window type this build keeps losing is exactly the one to stop leading with.
            overlay.useFallbackWindowTypeNext()
            overlayVisible = false
        }

        if (!orbVisibility(imeState.visible, dictation)) {
            hideOverlay()
        } else if (overlayVisible) {
            overlay.updateKeyboardHeight(imeState.heightPx)
        } else {
            overlayShowAttempts++
            // Only treat the pill as shown when the window really made it on screen.
            overlayVisible = overlay.show(imeState.heightPx)
        }

        publishDiagnostics(imeState.heightPx, imeState.visible)
        return imeState
    }

    /** Mirrors the decision just made into the bus the Settings screen renders. */
    private fun publishDiagnostics(imeHeightPx: Int, imeVisible: Boolean) {
        val insetPx = if (::imeDetector.isInitialized) imeDetector.insetHeightPx else 0
        val windowsSeen = if (::imeDetector.isInitialized) imeDetector.lastWindowsSeen else 0
        val imeWindowFound = if (::imeDetector.isInitialized) imeDetector.lastImeWindowFound else false
        val summary = "focus=$editableFieldFocused(${focusSource.label}) ime=$imeVisible/${imeHeightPx}px " +
            "orb=$overlayVisible pinned=$orbPinned attempts=$overlayShowAttempts"

        OrbDiagnosticsBus.update {
            it.copy(
                editableFieldFocused = editableFieldFocused,
                focusSource = focusSource.label,
                overlayVisible = overlayVisible,
                overlayShowAttempts = overlayShowAttempts,
                watchdogTicks = watchdogTicks,
                watchdogKeyboardTicks = watchdogKeyboardTicks,
                orbPinned = orbPinned,
                overlayAttached = if (::overlay.isInitialized) overlay.isAttached else false,
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
     * Polls briefly while the keyboard has not appeared yet, so the pill appears the moment the IME
     * window lands even when no accessibility event announces it.
     *
     * Focus is re-read on each tick like on any other evaluation: [EditableFieldInspector.probe] is
     * window-agnostic, so a tick that runs while the keyboard is rising cannot clear the flag the way
     * the old active-window lookup could — which is why the previous revision had to stop polling.
     */
    private fun startImeWatch() {
        if (imeWatchJob?.isActive == true) return
        imeWatchJob = lifecycleScope.launch {
            try {
                val deadline = SystemClock.uptimeMillis() + IME_WATCH_WINDOW_MS
                while (SystemClock.uptimeMillis() < deadline) {
                    delay(IME_WATCH_INTERVAL_MS)
                    // Stop as soon as there is nothing left to wait for: the keyboard is on screen, or
                    // a dictation started and the orb is pinned by it anyway.
                    if (applyOverlayState().visible || orbPinned) return@launch
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

    /**
     * Runs [applyOverlayState] on a slow timer for as long as the service is connected and the screen
     * is on, so the orb does not depend on any event being delivered.
     *
     * 600 ms is invisible next to the keyboard's own show animation while costing one window query
     * plus one focus query per tick, and it is skipped entirely while the screen is off.
     */
    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = lifecycleScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!isScreenOn()) continue
                watchdogTicks++
                val imeState = applyOverlayState(checkWindowLiveness = true)
                if (imeState.visible) watchdogKeyboardTicks++
            }
        }
    }

    /** The dictation's current UI state, or [OverlayUiState.Idle] before a coordinator exists. */
    private fun currentDictationState(): OverlayUiState =
        coordinator?.state?.value ?: OverlayUiState.Idle

    private fun isScreenOn(): Boolean =
        runCatching { powerManager.isInteractive }.getOrDefault(true)

    /**
     * A tap either starts or stops a dictation.
     *
     * The focused field is captured *before* the toggle, and only on the way into recording. This is
     * the last instant the answer is certainly right: dictation then takes seconds, during which the
     * user can move the caret, the IME can swap its window, and a field looked up afterwards is
     * regularly the wrong node or no node at all — which is how a perfectly good transcript ended up
     * with nowhere to go.
     */
    private fun onPillTapped() {
        val dictation = coordinator ?: return
        if (dictation.state.value is OverlayUiState.Idle ||
            dictation.state.value is OverlayUiState.Failed
        ) {
            injector.rememberFocus()
        }
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

    private suspend fun onTextReady(text: String) {        val settingsAtInjection = container.settingsRepository.current()
        val startedAtMs = System.currentTimeMillis() - lastDictationStartedAtMs
        val result = injector.inject(text)
        // The captured field belongs to one dictation; holding it would have the *next* one append
        // into wherever this one happened to land.
        injector.forgetFocus()
        // Recorded whatever the outcome: the method names the route that worked (or did not) and the
        // detail carries which of the focus lookups answered, which is the fact a report needs.
        OrbDiagnosticsBus.update {
            it.copy(lastInjection = "${result.method} · ${result.detail}")
        }
        when (result.method) {
            TextInjector.Method.ACTION_SET_TEXT,
            TextInjector.Method.CLIPBOARD_PASTE,
            TextInjector.Method.FOCUS_THEN_SET_TEXT,
            -> {
                haptics.confirm()
                recordTranscript(text, settingsAtInjection, startedAtMs)
                OrbDiagnosticsBus.update {
                    it.copy(dictationStage = DictationStages.INSERTED, dictationError = null)
                }
                Log.i(TAG, "Text injected via ${result.method}")
            }
            // No field could be reached, but the words are not lost: they are on the clipboard and
            // the pill says so. A "couldn't insert" that quietly dropped the transcript was the worst
            // possible answer here.
            TextInjector.Method.COPIED_TO_CLIPBOARD -> {
                haptics.reject()
                recordTranscript(text, settingsAtInjection, startedAtMs)
                OrbDiagnosticsBus.update {
                    it.copy(
                        dictationStage = DictationStages.COPIED,
                        dictationError = null,
                    )
                }
                Log.i(TAG, "No field to insert into; text left on the clipboard")
                throw TyporbException(
                    message = "Copied — long-press to paste.",
                    retryable = false,
                )
            }
            TextInjector.Method.NONE -> {
                haptics.reject()
                // An empty transcript is not an injection failure. Nothing arrived because the
                // engine returned no words, and telling the user to tap the field sends them off to
                // fix a field that was never the problem — while the stage line says the take ran.
                if (result.detail.startsWith(EMPTY_TRANSCRIPT)) {
                    OrbDiagnosticsBus.update { it.copy(dictationStage = DictationStages.EMPTY) }
                    throw TyporbException(
                        message = "Didn't catch that. Try again a little closer to the mic.",
                        retryable = true,
                    )
                }
                throw TyporbException(
                    message = "Couldn't insert the text. Tap the field and try again.",
                    retryable = true,
                )
            }
        }

        // The orb belongs to the keyboard, not to one dictation. Hiding it here — which is what this
        // used to do — meant every successful dictation blinked the orb away and the watchdog brought
        // it back up to 600 ms later, right in front of a user who was still typing. Re-evaluating
        // keeps it in place while the keyboard is up and still takes it down when it is not.
        applyOverlayState()
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

        /**
         * Marks an injection that produced no text at all, so the console can say the *recording*
         * came back empty rather than the write having failed.
         */
        const val EMPTY_TRANSCRIPT = "empty transcript"

        /** Focus events arrive in bursts; one evaluation per frame-ish is plenty. */
        const val EVALUATION_THROTTLE_MS = 40L

        /** How long to keep looking for a keyboard after a field takes focus. */
        const val IME_WATCH_WINDOW_MS = 3_000L

        /** Keyboard-show animation on a budget device is ~200-400ms. */
        const val IME_WATCH_INTERVAL_MS = 120L

        /** How often the always-on watchdog re-checks the screen; see [startWatchdog]. */
        const val WATCHDOG_INTERVAL_MS = 600L
    }
}