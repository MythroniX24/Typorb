package com.typorb.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.typorb.TyporbApp
import com.typorb.TyporbContainer
import com.typorb.domain.DictationCoordinator
import com.typorb.domain.TyporbException
import com.typorb.model.OverlayUiState
import com.typorb.model.ProcessingEngine
import com.typorb.overlay.OverlayController
import com.typorb.util.Haptics
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

    private var coordinator: DictationCoordinator? = null
    private var overlayVisible = false
    private var lastEvaluationMs = 0L
    private var lastDictationStartedAtMs = 0L

    private val windowManager: WindowManager
        get() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            setUp()
        }.onFailure { Log.e(TAG, "Typorb could not start", it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Events emitted by the IME itself carry its own root window; acting on them would hide the
        // pill exactly when the keyboard appears.
        if (imeDetector.isImePackage(event.packageName?.toString())) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> evaluateOverlayVisibility(force = false)
        }
    }

    override fun onInterrupt() {
        coordinator?.reset()
    }

    override fun onDestroy() {
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
        )

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
    private fun evaluateOverlayVisibility(force: Boolean) {
        if (!::overlay.isInitialized) return
        val now = System.currentTimeMillis()
        if (!force && now - lastEvaluationMs < EVALUATION_THROTTLE_MS) return
        lastEvaluationMs = now

        val imeState = imeDetector.currentState(screenHeightPx())
        val editableFieldFocused =
            EditableFieldInspector.findFocusedEditable(getRootInActiveWindow()) != null

        if (editableFieldFocused && imeState.visible) {
            if (overlayVisible) {
                overlay.updateKeyboardHeight(imeState.heightPx)
            } else {
                // Only treat the pill as shown when the window really made it on screen.
                overlayVisible = overlay.show(imeState.heightPx)
            }
        } else {
            hideOverlay()
        }
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

    private companion object {
        const val TAG = "TyporbService"

        /** Focus events arrive in bursts; one evaluation per frame-ish is plenty. */
        const val EVALUATION_THROTTLE_MS = 40L
    }
}