package com.typorb.data

import android.content.Context
import android.content.SharedPreferences
import com.typorb.model.ContextMode
import com.typorb.model.ProcessingEngine
import com.typorb.model.TranscriptionLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable snapshot of everything the user can configure in the dashboard. */
data class TyporbSettings(
    val engine: ProcessingEngine = ProcessingEngine.CLOUD,
    val contextMode: ContextMode = ContextMode.QUICK_CHAT,
    /**
     * Language the engines are told to expect.
     *
     * Defaults to [TranscriptionLanguage.AUTO] because Typorb's dictation is overwhelmingly Hinglish:
     * a forced `en` is what makes Whisper mangle Hindi words, and letting the model detect the
     * language per recording is the only setting that handles a mixed sentence honestly.
     */
    val language: TranscriptionLanguage = TranscriptionLanguage.AUTO,
    val apiKey: String = "",
    /** Which downloaded Whisper build the offline engine should load. */
    val modelVariantId: String = "int8",
    /**
     * Opt-in GPU backend. Off by default: on Adreno 5xx/PowerVR-era GPUs (Redmi 8A class) NNAPI
     * either fails to compile the graph or silently runs a slower CPU path than XNNPACK.
     */
    val useGpuAcceleration: Boolean = false,
    /**
     * Whether the first-launch permission gate has been completed. Kept true afterwards even if the
     * user later revokes a permission, so revoking does not trap them in a loop.
     */
    val onboardingComplete: Boolean = false,
    /** Corner radius of the floating orb, adjustable from Settings. */
    val overlayCornerRadiusDp: Int = DEFAULT_OVERLAY_CORNER_DP,
    /** Edge length of the idle orb, adjustable from Settings. */
    val overlaySizeDp: Int = DEFAULT_OVERLAY_SIZE_DP,
    /**
     * Where the user last dropped the orb, in screen pixels, or `null` while it has never been moved.
     *
     * Null is not "missing data": it means the orb belongs where the keyboard puts it, 16dp up from
     * the keyboard's top edge. Only a deliberate drag turns that into a fixed position the user owns.
     */
    val overlayAnchorRightPx: Int? = null,
    val overlayAnchorTopPx: Int? = null,
    /** Master switch for the tick/confirm/reject haptic pulses. */
    val hapticsEnabled: Boolean = true,
    /** Whether the recording capsule draws the live amplitude bars. */
    val waveformEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_OVERLAY_CORNER_DP = 16
        const val MIN_OVERLAY_CORNER_DP = 8
        const val MAX_OVERLAY_CORNER_DP = 24
        const val DEFAULT_OVERLAY_SIZE_DP = 48
        const val MIN_OVERLAY_SIZE_DP = 40
        const val MAX_OVERLAY_SIZE_DP = 64
    }
    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    /** The orb's top-right corner in px, or `null` for the keyboard-relative default position. */
    val overlayAnchor: Pair<Int, Int>?
        get() {
            val right = overlayAnchorRightPx ?: return null
            val top = overlayAnchorTopPx ?: return null
            return right to top
        }

    /** Cloud mode without a key would fail on every dictation, so the UI pre-emptively warns. */
    val isCloudConfigured: Boolean get() = engine != ProcessingEngine.CLOUD || hasApiKey
}

/**
 * Single source of truth for user preferences.
 *
 * Non-sensitive settings live in a regular SharedPreferences file; the Groq API key is stored with
 * [EncryptedSharedPreferences] (AES256-GCM keys held in the Android Keystore) and excluded from
 * cloud backup via the XML backup rules.
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext

    private val plainPrefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val securePrefs: SharedPreferences = SecurePreferences.create(appContext, SECURE_PREFS_NAME)

    private val _settings = MutableStateFlow(readSnapshot())

    /** Hot settings stream; the overlay and dashboard both observe it. */
    val settings: StateFlow<TyporbSettings> = _settings.asStateFlow()

    init {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            _settings.value = readSnapshot()
        }
        plainPrefs.registerOnSharedPreferenceChangeListener(listener)
        securePrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun current(): TyporbSettings = _settings.value

    fun setEngine(engine: ProcessingEngine) {
        plainPrefs.edit().putString(KEY_ENGINE, engine.name).apply()
    }

    fun setContextMode(mode: ContextMode) {
        plainPrefs.edit().putString(KEY_CONTEXT_MODE, mode.name).apply()
    }

    fun setLanguage(language: TranscriptionLanguage) {
        plainPrefs.edit().putString(KEY_LANGUAGE, language.name).apply()
    }

    fun setModelVariant(variantId: String) {
        plainPrefs.edit().putString(KEY_MODEL_VARIANT, variantId).apply()
    }

    fun setGpuAcceleration(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_GPU, enabled).apply()
    }

    fun setOnboardingComplete(complete: Boolean) {
        plainPrefs.edit().putBoolean(KEY_ONBOARDING, complete).apply()
    }

    /** Clamps to [TyporbSettings.MIN_OVERLAY_CORNER_DP]..[TyporbSettings.MAX_OVERLAY_CORNER_DP]. */
    fun setOverlayCornerRadius(radiusDp: Int) {
        val clamped = radiusDp.coerceIn(
            TyporbSettings.MIN_OVERLAY_CORNER_DP,
            TyporbSettings.MAX_OVERLAY_CORNER_DP,
        )
        plainPrefs.edit().putInt(KEY_OVERLAY_CORNER, clamped).apply()
    }

    /** Clamps to [TyporbSettings.MIN_OVERLAY_SIZE_DP]..[TyporbSettings.MAX_OVERLAY_SIZE_DP]. */
    fun setOverlaySize(sizeDp: Int) {
        val clamped = sizeDp.coerceIn(
            TyporbSettings.MIN_OVERLAY_SIZE_DP,
            TyporbSettings.MAX_OVERLAY_SIZE_DP,
        )
        plainPrefs.edit().putInt(KEY_OVERLAY_SIZE, clamped).apply()
    }

    fun setHapticsEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_HAPTICS, enabled).apply()
    }

    fun setWaveformEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_WAVEFORM, enabled).apply()
    }

    /**
     * Remembers where the user dropped the orb.
     *
     * Written on drag *end* rather than on every frame of the move: a SharedPreferences commit per
     * frame would be disk traffic in the middle of a gesture. Passing `null` for either value clears
     * the position and returns the orb to the keyboard's default.
     */
    fun setOverlayAnchor(rightPx: Int?, topPx: Int?) {
        val editor = plainPrefs.edit()
        if (rightPx == null || topPx == null) {
            editor.remove(KEY_OVERLAY_ANCHOR_RIGHT).remove(KEY_OVERLAY_ANCHOR_TOP)
        } else {
            editor.putInt(KEY_OVERLAY_ANCHOR_RIGHT, rightPx).putInt(KEY_OVERLAY_ANCHOR_TOP, topPx)
        }
        editor.apply()
    }

    /** Stores the API key encrypted. Passing a blank key clears the credential. */
    fun setApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) clearApiKey() else securePrefs.edit().putString(KEY_API_KEY, trimmed).apply()
    }

    fun clearApiKey() {
        securePrefs.edit().remove(KEY_API_KEY).apply()
    }

    private fun readSnapshot(): TyporbSettings {
        val engine = plainPrefs.getString(KEY_ENGINE, null)
            ?.let { name -> runCatching { ProcessingEngine.valueOf(name) }.getOrNull() }
            ?: ProcessingEngine.CLOUD
        val mode = plainPrefs.getString(KEY_CONTEXT_MODE, null)
            ?.let { name -> runCatching { ContextMode.valueOf(name) }.getOrNull() }
            ?: ContextMode.QUICK_CHAT
        val language = plainPrefs.getString(KEY_LANGUAGE, null)
            ?.let { name -> runCatching { TranscriptionLanguage.valueOf(name) }.getOrNull() }
            ?: TranscriptionLanguage.AUTO
        return TyporbSettings(
            engine = engine,
            contextMode = mode,
            language = language,
            apiKey = securePrefs.getString(KEY_API_KEY, "").orEmpty(),
            modelVariantId = plainPrefs.getString(KEY_MODEL_VARIANT, null) ?: "int8",
            useGpuAcceleration = plainPrefs.getBoolean(KEY_GPU, false),
            onboardingComplete = plainPrefs.getBoolean(KEY_ONBOARDING, false),
            overlayCornerRadiusDp = plainPrefs.getInt(
                KEY_OVERLAY_CORNER,
                TyporbSettings.DEFAULT_OVERLAY_CORNER_DP,
            ),
            overlaySizeDp = plainPrefs.getInt(
                KEY_OVERLAY_SIZE,
                TyporbSettings.DEFAULT_OVERLAY_SIZE_DP,
            ),
            // Absence is meaningful here, so it is read as absence instead of as a sentinel pixel.
            overlayAnchorRightPx = if (plainPrefs.contains(KEY_OVERLAY_ANCHOR_RIGHT)) {
                plainPrefs.getInt(KEY_OVERLAY_ANCHOR_RIGHT, 0)
            } else {
                null
            },
            overlayAnchorTopPx = if (plainPrefs.contains(KEY_OVERLAY_ANCHOR_TOP)) {
                plainPrefs.getInt(KEY_OVERLAY_ANCHOR_TOP, 0)
            } else {
                null
            },
            hapticsEnabled = plainPrefs.getBoolean(KEY_HAPTICS, true),
            waveformEnabled = plainPrefs.getBoolean(KEY_WAVEFORM, true),
        )
    }

    private companion object {
        const val PREFS_NAME = "typorb_prefs"
        const val SECURE_PREFS_NAME = "typorb_secure_prefs"
        const val KEY_ENGINE = "processing_engine"
        const val KEY_CONTEXT_MODE = "context_mode"
        const val KEY_LANGUAGE = "transcription_language"
        const val KEY_API_KEY = "groq_api_key"
        const val KEY_MODEL_VARIANT = "model_variant"
        const val KEY_GPU = "gpu_acceleration"
        const val KEY_ONBOARDING = "onboarding_complete"
        const val KEY_OVERLAY_CORNER = "overlay_corner_radius"
        const val KEY_OVERLAY_SIZE = "overlay_size"
        const val KEY_OVERLAY_ANCHOR_RIGHT = "overlay_anchor_right"
        const val KEY_OVERLAY_ANCHOR_TOP = "overlay_anchor_top"
        const val KEY_HAPTICS = "haptics_enabled"
        const val KEY_WAVEFORM = "waveform_enabled"
    }
}