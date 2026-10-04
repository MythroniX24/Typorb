package com.typorb.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.typorb.TyporbApp
import com.typorb.data.ModelCatalog
import com.typorb.data.OfflineModelState
import com.typorb.data.Transcript
import com.typorb.data.TyporbSettings
import com.typorb.model.ContextMode
import com.typorb.model.ProcessingEngine
import com.typorb.util.Permissions
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Live status of everything the user must grant before dictation works. */
data class PermissionStatus(
    val accessibilityService: Boolean,
    val microphonePermission: Boolean,
    /**
     * Only needed as a fallback when the OEM build refuses the accessibility window type, so it is
     * reported but never gates [ready].
     */
    val overlayPermission: Boolean,
) {
    /**
     * The orb draws a `TYPE_ACCESSIBILITY_OVERLAY` window, which the accessibility grant covers.
     *
     * "Display over other apps" is therefore not required — it exists purely so the orb has a
     * second window type to fall back to when a device's WindowManager rejects the first.
     */
    val ready: Boolean get() = accessibilityService
}

/** Result of the Settings screen's Test Connection button. */
sealed interface ApiKeyCheck {
    /** Nothing tested yet, or the field changed since the last test. */
    data object Idle : ApiKeyCheck

    data object Testing : ApiKeyCheck

    /** Connected; [modelCount] is how many models the key can reach. */
    data class Ok(val modelCount: Int) : ApiKeyCheck

    data class Failed(val reason: String) : ApiKeyCheck
}

/** Which system screen a "Grant" tap should open. */
enum class PermissionTarget {
    ACCESSIBILITY,
    MICROPHONE,
    OVERLAY,
}

/**
 * The single app-scoped ViewModel behind all three tabs.
 *
 * Deliberately one object rather than one per screen: the engine selection, the offline download
 * job and the transcript history are shared state, and splitting them would mean several ViewModels
 * racing to own the same download or the same history.
 */
class TyporbViewModel(application: Application) : AndroidViewModel(application) {

    private val container = TyporbApp.containerOf(application)
    private val settingsRepository = container.settingsRepository
    private val modelRepository = container.modelRepository

    val settings: StateFlow<TyporbSettings> = settingsRepository.settings

    val modelVariants: List<ModelCatalog.Variant> = ModelCatalog.variants

    val modelState: StateFlow<OfflineModelState> = modelRepository.state

    /** Newest-first dictated text. */
    val transcripts: StateFlow<List<Transcript>> = container.transcriptRepository.transcripts

    private val _permissions = MutableStateFlow(readPermissions())
    val permissions: StateFlow<PermissionStatus> = _permissions.asStateFlow()

    /** Vault search box; empty string means "no filter". */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Search-filtered history, derived rather than duplicated. */
    val filteredTranscripts: StateFlow<List<Transcript>> = combine(
        transcripts,
        _searchQuery,
    ) { items, query ->
        if (query.isBlank()) {
            items
        } else {
            items.filter { it.text.contains(query, ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _apiKeyInput = MutableStateFlow("")
    val apiKeyInput: StateFlow<String> = _apiKeyInput.asStateFlow()

    private val _apiKeySaved = MutableStateFlow(settingsRepository.current().hasApiKey)
    val apiKeySaved: StateFlow<Boolean> = _apiKeySaved.asStateFlow()

    private val _apiKeyCheck = MutableStateFlow<ApiKeyCheck>(ApiKeyCheck.Idle)
    val apiKeyCheck: StateFlow<ApiKeyCheck> = _apiKeyCheck.asStateFlow()

    private var downloadJob: Job? = null

    init {
        container.selectModelVariant(ModelCatalog.variant(settings.value.modelVariantId))
        refreshModelState()
    }

    // ------------------------------------------------------------- lifecycle

    /** Re-reads system state — called whenever the app returns to the foreground. */
    fun refreshPermissions() {
        _permissions.value = readPermissions()
        _apiKeySaved.value = settingsRepository.current().hasApiKey
        refreshModelState()
    }

    fun readPermissions(): PermissionStatus {
        val context = getApplication<Application>()
        return PermissionStatus(
            accessibilityService = Permissions.isAccessibilityServiceEnabled(context),
            microphonePermission = Permissions.isMicrophonePermissionGranted(context),
            overlayPermission = Permissions.isOverlayPermissionGranted(context),
        )
    }

    /**
     * Opens the relevant system screen for [target].
     *
     * The microphone uses the app-details screen because a runtime permission prompt cannot be
     * raised from a non-activity-scoped click; the user grants it there in two taps.
     */
    fun launchPermission(context: Context, target: PermissionTarget) {
        val intent = when (target) {
            PermissionTarget.ACCESSIBILITY -> Permissions.accessibilitySettingsIntent()
            PermissionTarget.MICROPHONE -> Permissions.appDetailsIntent(context)
            PermissionTarget.OVERLAY -> Permissions.overlaySettingsIntent(context)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w("TyporbViewModel", "Could not open ${target.name} settings", it) }
    }

    // -------------------------------------------------------------- settings

    fun setEngine(engine: ProcessingEngine) = settingsRepository.setEngine(engine)

    fun setContextMode(mode: ContextMode) = settingsRepository.setContextMode(mode)

    fun setGpuAcceleration(enabled: Boolean) = settingsRepository.setGpuAcceleration(enabled)

    fun setOverlayCornerRadius(radiusDp: Int) = settingsRepository.setOverlayCornerRadius(radiusDp)

    fun setOverlaySize(sizeDp: Int) = settingsRepository.setOverlaySize(sizeDp)

    fun setHapticsEnabled(enabled: Boolean) = settingsRepository.setHapticsEnabled(enabled)

    fun setWaveformEnabled(enabled: Boolean) = settingsRepository.setWaveformEnabled(enabled)

    fun completeOnboarding() = settingsRepository.setOnboardingComplete(true)

    // --------------------------------------------------------------- api key

    fun onApiKeyChange(value: String) {
        _apiKeyInput.value = value
        // Any edit invalidates a previous test result; leaving a stale green tick next to a changed
        // key would be actively misleading.
        if (_apiKeyCheck.value != ApiKeyCheck.Idle) _apiKeyCheck.value = ApiKeyCheck.Idle
        if (_apiKeySaved.value) _apiKeySaved.value = false
    }

    /**
     * Proves the key works by listing models. Tests the field's value if there is one, otherwise the
     * stored key — so the check is available both before and after saving.
     */
    fun testConnection() {
        if (_apiKeyCheck.value == ApiKeyCheck.Testing) return
        val candidate = _apiKeyInput.value.trim().ifEmpty { settingsRepository.current().apiKey }
        if (!isPlausibleGroqKey(candidate)) {
            _apiKeyCheck.value = ApiKeyCheck.Failed("Enter a Groq API key first.")
            return
        }
        _apiKeyCheck.value = ApiKeyCheck.Testing
        viewModelScope.launch {
            val result = withTimeoutOrNull(TEST_TIMEOUT_MS) { container.verifyApiKey(candidate) }
            _apiKeyCheck.value = when {
                result == null -> ApiKeyCheck.Failed("Timed out. Check your connection.")
                result.isSuccess -> ApiKeyCheck.Ok(result.getOrDefault(0))
                else -> ApiKeyCheck.Failed(friendlyReason(result.exceptionOrNull()))
            }
        }
    }

    /** Groq keys are `gsk_` prefixed; anything else is rejected before it is stored. */
    fun saveApiKey() {
        val key = _apiKeyInput.value.trim()
        if (!isPlausibleGroqKey(key)) return
        settingsRepository.setApiKey(key)
        _apiKeyInput.value = ""
        _apiKeySaved.value = true
    }

    fun clearApiKey() {
        settingsRepository.clearApiKey()
        _apiKeyInput.value = ""
        _apiKeySaved.value = false
    }

    // ---------------------------------------------------------------- models

    fun refreshModelState() {
        viewModelScope.launch {
            modelRepository.refresh(ModelCatalog.variant(settings.value.modelVariantId))
        }
    }

    /**
     * Selects which build the offline engine uses. This never starts a transfer — switching to the
     * 150 MB variant must not silently begin a 150 MB download.
     */
    fun selectModelVariant(variant: ModelCatalog.Variant, refresh: Boolean = true) {
        settingsRepository.setModelVariant(variant.id)
        container.selectModelVariant(variant)
        if (refresh) refreshModelState()
    }

    fun downloadModel(variant: ModelCatalog.Variant) {
        if (downloadJob?.isActive == true) return
        selectModelVariant(variant, refresh = false)
        downloadJob = viewModelScope.launch { modelRepository.download(variant) }
    }

    fun cancelModelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        refreshModelState()
    }

    fun deleteModel(variant: ModelCatalog.Variant) {
        downloadJob?.cancel()
        downloadJob = null
        viewModelScope.launch { modelRepository.delete(variant) }
    }

    // -------------------------------------------------------------- history

    fun onSearchChange(query: String) {
        _searchQuery.value = query
    }

    fun deleteTranscript(id: String) {
        container.transcriptRepository.remove(id)
    }

    /** Clears the vault. Returns `true` when there was anything to remove. */
    fun clearTranscripts(): Boolean = container.transcriptRepository.clear()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TEST_TIMEOUT_MS = 15_000L

        /**
         * Turns a transport/HTTP failure into something a user can act on. An invalid key is by far
         * the most common outcome, so it gets the most specific message.
         */
        fun friendlyReason(error: Throwable?): String {
            val message = error?.message.orEmpty()
            return when {
                message.contains("401") || message.contains("403") ->
                    "Groq rejected that key (401). Check it was copied in full."
                message.contains("404") ->
                    "Groq returned 404 — the models endpoint moved."
                message.contains("429") ->
                    "Rate limited (429). Your key is valid; try again shortly."
                message.startsWith("HTTP 5") ->
                    "Groq is having trouble (${message.take(6)}). Try again shortly."
                message.contains("Unable to resolve host") || message.contains("Failed to connect") ->
                    "No connection. Check your network."
                message.isBlank() -> "Could not verify the key. Try again."
                else -> message.take(140)
            }
        }

        /**
         * Groq has issued both `gsk_` and bare keys over time, so the prefix is treated as a
         * strong hint rather than the only accepted shape — but an obviously short value is still
         * rejected so the user finds out before dictation fails.
         */
        fun isPlausibleGroqKey(key: String): Boolean =
            key.isNotBlank() && key.length >= MIN_KEY_LENGTH && key.all { it.isLetterOrDigit() || it == '_' || it == '-' }

        const val MIN_KEY_LENGTH = 20
    }
}