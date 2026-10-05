package com.typorb.model

/**
 * Selects which transcription/formatting pipeline processes a dictation.
 *
 * [CLOUD] is fast and free-tier friendly (Groq), [LOCAL] runs fully on-device and works offline.
 */
enum class ProcessingEngine {
    CLOUD,
    LOCAL,
}

/**
 * The tone/structure the AI engine should reshape a raw transcript into.
 *
 * [systemInstruction] is appended verbatim to the cloud engine's system prompt and is also used by
 * the local engine's deterministic cleanup pass so both engines behave as consistently as possible.
 */
enum class ContextMode(
    val label: String,
    val shortLabel: String,
    /** Full label for the Control screen's chips, where there is room for it. */
    val chipLabel: String,
    val description: String,
    val systemInstruction: String,
) {
    QUICK_CHAT(
        label = "Quick Chat",
        shortLabel = "Chat",
        chipLabel = "Casual Chat",
        description = "Conversational replies with natural punctuation.",
        systemInstruction = "Rewrite this as a casual chat message. Keep it short and conversational, " +
            "add natural punctuation and capitalisation, and keep the speaker's intent intact.",
    ),
    CODE(
        label = "Code / Bug Report",
        shortLabel = "Code",
        chipLabel = "Code & Bug",
        description = "Markdown formatted technical notes.",
        systemInstruction = "Format this as a developer-ready markdown note. Use fenced code blocks " +
            "for code, bullet lists for steps, and correct any obviously mangled identifiers. " +
            "Do not invent technical details that were not dictated.",
    ),
    NOTES(
        label = "Bullet Notes",
        shortLabel = "Notes",
        chipLabel = "Clean Notes",
        description = "Tight bullet points, one idea per line.",
        systemInstruction = "Convert this into concise markdown bullet points, one idea per bullet. " +
            "Drop empty transitions and merge duplicated thoughts.",
    ),
    FORMAL(
        label = "Formal Email",
        shortLabel = "Formal",
        chipLabel = "Formal Mail",
        description = "Polished professional prose.",
        systemInstruction = "Rewrite this as a polished, professional email paragraph. Use formal " +
            "register, complete sentences and correct punctuation. Do not add a greeting or sign-off.",
    ),
}

/** Stage label shown in the processing pill while the engine works. */
enum class ProcessingStage(val label: String) {
    TRANSCRIBING("Transcribing…"),
    FORMATTING("Formatting…"),
    UPDATING("Inserting text…"),
}

/**
 * The full state machine of the floating Typorb pill.
 *
 * Idle → Recording → Processing → (injected) Idle, with [Failed] reachable from any active state.
 */
sealed interface OverlayUiState {

    /** Collapsed 48dp square waiting for the user to start dictating. */
    data object Idle : OverlayUiState

    /** Expanded capsule with a live waveform. [amplitudes] holds the last N normalised levels (0f..1f). */
    data class Recording(
        val amplitudes: List<Float>,
        val elapsedMs: Long,
    ) : OverlayUiState

    /** Spinning gradient border while the selected engine works. */
    data class Processing(val stage: ProcessingStage) : OverlayUiState

    /** Transient error surface; collapses back to [Idle] automatically. */
    data class Failed(val message: String) : OverlayUiState
}

/**
 * Whether a dictation in flight is, on its own, a reason for Typorb to stay on screen.
 *
 * The keyboard is what calls the orb up, but it must not be what takes it away: the microphone is
 * still open while the user talks, and apps dismiss their keyboards at the worst moment — a tap
 * outside the field, a rotation, a chat app deciding its composer is no longer active. Hiding the
 * orb there removes the only button that can stop the recording, so the two work-in-flight states
 * hold it on screen regardless of the keyboard.
 *
 * [OverlayUiState.Failed] deliberately does not: the text is already on the clipboard, the message
 * is a toast, and a resting error must never park an orb over the home screen.
 */
fun OverlayUiState.pinsOrb(): Boolean = when (this) {
    is OverlayUiState.Recording,
    is OverlayUiState.Processing,
    -> true

    is OverlayUiState.Idle,
    is OverlayUiState.Failed,
    -> false
}

/**
 * The whole "should the orb exist right now" rule, as one function that can be asked without a
 * phone.
 *
 * A visible soft keyboard is the condition the orb is built around — it is the platform saying
 * "someone is editing text" — and a dictation in flight is the one addition that has to survive the
 * keyboard leaving.
 */
fun orbVisibility(keyboardVisible: Boolean, state: OverlayUiState): Boolean =
    keyboardVisible || state.pinsOrb()

/** Successful outcome of one dictation cycle. */
data class DictationResult(
    val text: String,
    val engine: ProcessingEngine,
    val latencyMs: Long,
)