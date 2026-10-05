package com.typorb.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A snapshot of everything Typorb knows about *why* the orb is or is not on screen.
 *
 * The orb's visibility depends on a chain of independent facts — the service is connected, the
 * system is delivering events, a keyboard window exists and can be measured, and WindowManager
 * accepted the overlay. When any link breaks the only symptom is "nothing appears", which is exactly
 * the situation that made this screen necessary.
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
    /** Whether an editable field held focus at the last evaluation. Reported, but no longer gated on. */
    val editableFieldFocused: Boolean = false,
    /**
     * Which lookup produced [editableFieldFocused] — `system findFocus`, `active-window findFocus`,
     * `window walk` or `none`.
     *
     * "No field focused" and "a field the service cannot read" are the same two words in a bug report
     * and need completely different fixes, so the route is published next to the flag.
     */
    val focusSource: String? = null,
    /** Whether the overlay window is currently on screen. */
    val overlayVisible: Boolean = false,
    /**
     * How many times an overlay window has been asked for.
     *
     * `0` after a keyboard has been seen means the orb was never eligible; a number that keeps
     * climbing while nothing appears means WindowManager is refusing it, and [overlayWindowError]
     * says why.
     */
    val overlayShowAttempts: Int = 0,
    /**
     * How many times the always-on watchdog has looked at the screen.
     *
     * The watchdog is the path that does not depend on accessibility events at all, so a rising
     * number here proves the service is alive even when [eventCount] sits at zero — the failure that
     * previously had no symptom other than "nothing ever appears".
     */
    val watchdogTicks: Long = 0,
    /** Of those ticks, how many saw a keyboard window. `0` after typing pinpoints the detector. */
    val watchdogKeyboardTicks: Long = 0,
    /** Whether a dictation in flight is holding the orb on screen with no keyboard behind it. */
    val orbPinned: Boolean = false,
    /** Whether the orb's view is genuinely attached to the display, not just recorded as shown. */
    val overlayAttached: Boolean = false,
    /** Whether the always-on invisible IME probe window could be attached. */
    val probeAttached: Boolean = false,
    /**
     * The verbatim `addView` failure for the orb window, when there was one.
     *
     * This is the single most useful field on the screen: focus and keyboard detection can both be
     * correct while the orb still never appears, and the only evidence of why is this string.
     */
    val overlayWindowError: String? = null,
    /** The same, for the IME probe window. */
    val probeWindowError: String? = null,
    /** Which window type the orb actually got, as a readable name. */
    val overlayWindowType: String? = null,
    /** Whether "Display over other apps" is granted, i.e. the fallback type is available. */
    val overlayPermissionGranted: Boolean = false,
    /**
     * Which engine the last dictation ran on.
     *
     * The whole point of writing it down: "Local does not work" and "the switch did not happen"
     * produce the same experience and completely different fixes, and only the engine the pipeline
     * actually used can tell them apart.
     */
    val dictationEngine: String? = null,
    /** How far the last dictation got, as a [DictationStages] value. */
    val dictationStage: String? = null,
    /** Why the last dictation failed, verbatim, or `null` when it did not. */
    val dictationError: String? = null,
    /** Audio captured for the last dictation, in ms. `0` means nothing was recorded at all. */
    val dictationAudioMs: Long = 0L,
    /** Characters the last dictation produced, i.e. the size of the transcript before injection. */
    val dictationTranscriptChars: Int = 0,
    /** What the injector did with the last transcript, e.g. `ACTION_SET_TEXT · set text via ...`. */
    val lastInjection: String? = null,
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

    /** `true` when the last dictation reached a state the user should hear about. */
    val lastDictationFailed: Boolean
        get() = dictationStage == DictationStages.FAILED

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
        appendLine("orb window type   : ${overlayWindowType ?: "—"}")
        appendLine("overlay perm      : ${if (overlayPermissionGranted) "granted" else "not granted"}")
        overlayWindowError?.let { appendLine("orb addView error : $it") }
        probeWindowError?.let { appendLine("probe addView err : $it") }
        appendLine("field focused     : $editableFieldFocused (${focusSource ?: "—"})")
        appendLine("orb on screen     : $overlayVisible (asked ${overlayShowAttempts}x)")
        appendLine("orb window live   : $overlayAttached")
        appendLine("orb pinned        : $orbPinned")
        appendLine("watchdog          : $watchdogTicks checks, $watchdogKeyboardTicks with a keyboard")
        appendLine()
        appendLine("last dictation    : ${dictationStage ?: "none"} · ${dictationEngine ?: "—"}")
        appendLine("audio captured    : ${dictationAudioMs}ms")
        appendLine("transcript chars  : $dictationTranscriptChars")
        appendLine("injection         : ${lastInjection ?: "—"}")
        dictationError?.let { appendLine("dictation error   : $it") }
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
 * Stage names for [OrbDiagnostics.dictationStage].
 *
 * Shared constants rather than prose, because three different classes write them (the coordinator,
 * the accessibility service and the engines' progress reporter) and a report is only readable when
 * they agree.
 */
object DictationStages {

    /** Microphone open. */
    const val RECORDING = "recording"

    /** Audio captured, engine running. */
    const val TRANSCRIBING = "transcribing"

    /** Transcript ready, formatting pass running. */
    const val FORMATTING = "formatting"

    /** Text ready, injector running. */
    const val INSERTING = "inserting"

    /** Delivered into the field. */
    const val INSERTED = "inserted"

    /** Not delivered, but left on the clipboard for a manual paste. */
    const val COPIED = "copied to clipboard"

    /** Ended in an error; [OrbDiagnostics.dictationError] carries the reason. */
    const val FAILED = "failed"
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
        // Only a problem when the watchdog is silent too: the watchdog is the path that shows the
        // orb without any events at all, so a service that receives nothing every 600 ms is broken
        // while one that receives nothing but ticks is fine.
        d.eventCount == 0L && d.watchdogTicks == 0L -> "Running, but receiving no events"
        // A field that cannot be read is deliberately absent: it no longer keeps the orb hidden, so
        // naming it as the reason something is wrong would send the user after the wrong thing.
        // A pinned orb (dictation in flight) is also exempt: it is up on purpose.
        !d.imeVisible && !d.orbPinned -> "Keyboard not detected"
        !d.overlayVisible -> "Overlay window not added"
        else -> "Orb is on screen"
    }

    fun detail(d: OrbDiagnostics): String = when {
        d.setupError != null -> d.setupError
        !d.serviceConnected ->
            "Turn Typorb on in Settings → Accessibility, then reopen this screen."
        d.eventCount == 0L && d.watchdogTicks == 0L ->
            "The service is enabled but the system is sending it nothing — usually it was enabled " +
                "and then killed by the battery optimiser."
        d.orbPinned && !d.imeVisible ->
            "Still dictating, so the orb stays on screen without a keyboard."
        !d.imeVisible -> when {
            d.windowsSeen == 0 -> "The service can see no windows at all."
            !d.imeWindowFound -> "No window is tagged as a keyboard."
            else -> "A keyboard window exists but measured ${d.imeHeightPx}px."
        }
        // A window error outranks the plain "refused" wording because it names the actual cause,
        // and because focus plus keyboard both being correct while the orb is hidden is exactly
        // the case that used to be impossible to explain from the outside.
        !d.overlayVisible && d.overlayWindowError != null ->
            "WindowManager rejected the orb window: ${d.overlayWindowError}" +
                if (d.overlayPermissionGranted) {
                    " Granting \"Display over other apps\" will not help further; both window " +
                        "types were already tried."
                } else {
                    " Grant \"Display over other apps\" in Settings so the orb can retry with the " +
                        "standard overlay window."
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
        512 -> "TYPE_VIEW_TEXT_SELECTION_CHANGED"
        2048 -> "TYPE_WINDOW_CONTENT_CHANGED"
        4096 -> "TYPE_WINDOWS_CHANGED"
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
