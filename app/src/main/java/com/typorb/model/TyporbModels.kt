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
    /** Emoji used on the Control screen's filter chips. */
    val emoji: String,
    /** Full label for the Control screen's chips, where there is room for it. */
    val chipLabel: String,
    val description: String,
    val systemInstruction: String,
) {
    QUICK_CHAT(
        label = "Quick Chat",
        shortLabel = "Chat",
        emoji = "\uD83D\uDEAC",
        chipLabel = "Casual Chat",
        description = "Conversational replies with natural punctuation.",
        systemInstruction = "Rewrite this as a casual chat message. Keep it short and conversational, " +
            "add natural punctuation and capitalisation, and keep the speaker's intent intact.",
    ),
    CODE(
        label = "Code / Bug Report",
        shortLabel = "Code",
        emoji = "\uD83D\uDCBB",
        chipLabel = "Code & Bug",
        description = "Markdown formatted technical notes.",
        systemInstruction = "Format this as a developer-ready markdown note. Use fenced code blocks " +
            "for code, bullet lists for steps, and correct any obviously mangled identifiers. " +
            "Do not invent technical details that were not dictated.",
    ),
    NOTES(
        label = "Bullet Notes",
        shortLabel = "Notes",
        emoji = "\uD83D\uDCDD",
        chipLabel = "Clean Notes",
        description = "Tight bullet points, one idea per line.",
        systemInstruction = "Convert this into concise markdown bullet points, one idea per bullet. " +
            "Drop empty transitions and merge duplicated thoughts.",
    ),
    FORMAL(
        label = "Formal Email",
        shortLabel = "Formal",
        emoji = "\u2709\uFE0F",
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

/** Successful outcome of one dictation cycle. */
data class DictationResult(
    val text: String,
    val engine: ProcessingEngine,
    val latencyMs: Long,
)