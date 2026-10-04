package com.typorb.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A snapshot of everything Typorb knows about *why* the orb is or is not on screen.
 *
 * The orb's visibility depends on a chain of independent facts — the service is connected, the
 * system is delivering events, a field holds focus, a keyboard window exists and can be measured,
 * and WindowManager accepted the overlay. When any link breaks the only symptom is "nothing
 * appears", which is exactly the situation that made this screen necessary.
 *
 * The accessibility service and the launcher activity live in the same process, so a plain
 * process-wide bus is all that is needed to get these numbers from one to the other.
 */
data class OrbDiagnostics(
    /** `false` until `onServiceConnected` completes. */
    val serviceConnected: Boolean = false,
    /** Startup exception message, when [TyporbAccessibilityService.setUp] threw. */
    val setupError: String? = null,
    /** Package of the IME the system reports as default, e.g. Gboard's. */
    val imePackage: String? = null,
    /** How many on-screen windows the service could see at the last evaluation. */
    val windowsSeen: Int = 0,
    /** Whether one of them was tagged `TYPE_INPUT_METHOD`. */
    val imeWindowFound: Boolean = false,
    /** Keyboard height in px at the last evaluation (`0` when not measured). */
    val imeHeightPx: Int = 0,
    /** The decision Typorb acted on. */
    val imeVisible: Boolean = false,
    /** IME height as reported by `WindowInsets`, the independent third signal. */
    val imeInsetPx: Int = 0,
    /** The cached "an editable field holds focus" flag. */
    val editableFieldFocused: Boolean = false,
    /** Whether the overlay window is currently on screen. */
    val overlayVisible: Boolean = false,
    /** Whether the always-on invisible IME probe window could be attached. */
    val probeAttached: Boolean = false,
    val lastEvaluationAtMs: Long = 0L,
    /** Total accessibility events delivered, to distinguish "silent" from "no events". */
    val eventCount: Long = 0,
    val lastEventPackage: String? = null,
    val lastEventType: Int? = null,
    /** Rolling trail of the last few decisions, newest last. */
    val breadcrumbs: List<String> = emptyList(),
) {

    fun headline(): String = OrbDiagnosis.headline(this)

    fun detail(): String = OrbDiagnosis.detail(this)

    /** Plain-text dump for the clipboard, so a user can paste the state back into a bug report. */
    fun asReport(nowMs: Long): String = buildString {
        appendLine("Typorb diagnostics")
        appendLine("Verdict: ${headline()}")
        appendLine("Detail: ${detail()}")
        appendLine()
        appendLine("service connected : $serviceConnected")
        appendLine("setup error       : ${setupError ?: "none"}")
        appendLine("events received   : $eventCount")
        appendLine("last event        : ${AccessibilityEventNames.name(lastEventType)} " +
            "from ${lastEventPackage ?: "—"}")
        appendLine("IME package       : ${imePackage ?: "unknown"}")
        appendLine("windows visible   : $windowsSeen")
        appendLine("IME window found  : $imeWindowFound")
        appendLine("keyboard height   : ${imeHeightPx}px")
        appendLine("keyboard visible  : $imeVisible")
        appendLine("IME inset signal  : ${imeInsetPx}px")
        appendLine("IME probe window  : ${if (probeAttached) "attached" else "not attached"}")
        appendLine("field focused     : $editableFieldFocused")
        appendLine("orb on screen     : $overlayVisible")
        val age = if (lastEvaluationAtMs == 0L) {
            "never"
        } else {
            "${(nowMs - lastEvaluationAtMs).coerceAtLeast(0L)}ms ago"
        }
        appendLine("last check        : $age")
        if (breadcrumbs.isNotEmpty()) {
            appendLine()
            appendLine("Recent:")
            breadcrumbs.forEach { appendLine("  $it") }
        }
    }
}

/**
 * Turns a snapshot into the two sentences a user actually needs: what is wrong, and what to do.
 *
 * Pure so the wording is unit-tested rather than only ever seen on a device that is already broken.
 */
object OrbDiagnosis {

    fun headline(d: OrbDiagnostics): String = when {
        d.setupError != null -> "Typorb failed to start"
        !d.serviceConnected -> "Typorb is not running"
        d.eventCount == 0L -> "Running, but receiving no events"
        !d.editableFieldFocused -> "No text field has focus"
        !d.imeVisible -> "Keyboard not detected"
        !d.overlayVisible -> "Overlay window not added"
        else -> "Orb is on screen"
    }

    fun detail(d: OrbDiagnostics): String = when {
        d.setupError != null -> d.setupError
        !d.serviceConnected ->
            "Turn Typorb on in Settings → Accessibility, then reopen this screen."
        d.eventCount == 0L ->
            "The service is enabled but the system is sending it nothing — usually it was enabled " +
                "and then killed by the battery optimiser."
        !d.editableFieldFocused ->
            "Tap inside a real text field (a message box, a search bar) and come back to this screen."
        !d.imeVisible -> when {
            d.windowsSeen == 0 -> "The service can see no windows at all."
            !d.imeWindowFound -> "No window is tagged as a keyboard."
            else -> "A keyboard window exists but measured ${d.imeHeightPx}px."
        }
        !d.overlayVisible -> "WindowManager refused the overlay window."
        else -> "All checks pass — keyboard at ${d.imeHeightPx}px."
    }
}

/**
 * Readable names for the event types the service subscribes to.
 *
 * `AccessibilityEvent.getEventTypeName` is hidden API, so the five interesting ones are listed
 * with their documented integer values instead.
 */
object AccessibilityEventNames {

    fun name(type: Int?): String = when (type) {
        null -> "—"
        1 -> "TYPE_VIEW_CLICKED"
        2 -> "TYPE_VIEW_LONG_CLICKED"
        4 -> "TYPE_VIEW_SELECTED"
        8 -> "TYPE_VIEW_FOCUSED"
        16 -> "TYPE_VIEW_TEXT_CHANGED"
        32 -> "TYPE_WINDOW_STATE_CHANGED"
        2048 -> "TYPE_WINDOW_CONTENT_CHANGED"
        else -> "type $type"
    }
}

/**
 * Process-wide publisher for [OrbDiagnostics].
 *
 * Written only from the accessibility service's main thread and read by Compose, so a
 * [MutableStateFlow] is sufficient — and unlike a file-backed store it stays cheap enough to update
 * on every evaluation.
 */
object OrbDiagnosticsBus {

    private const val MAX_BREADCRUMBS = 8

    private val _state = MutableStateFlow(OrbDiagnostics())

    val state: StateFlow<OrbDiagnostics> = _state.asStateFlow()

    val current: OrbDiagnostics get() = _state.value

    fun update(block: (OrbDiagnostics) -> OrbDiagnostics) {
        _state.update(block)
    }

    /** Records a short human-readable step, dropping the oldest once the trail is full. */
    fun note(line: String) {
        update { current ->
            current.copy(breadcrumbs = (current.breadcrumbs + line).takeLast(MAX_BREADCRUMBS))
        }
    }
}
