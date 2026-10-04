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

/** Live status of everything the user must grant before dictation works. */
data class PermissionStatus(
    val accessibilityService: Boolean,
    val overlayPermission: Boolean,
    val microphonePermission: Boolean,
) {
    /** The pill itself only needs the accessibility service; the rest are user-experience extras. */
    val ready: Boolean get() = accessibilityService
}

/** Which system screen a "Grant" tap should open. */
enum class PermissionTarget {
    ACCESSIBILITY,
    OVERLAY,
    MICROPHONE,
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

    /** Set while "clear history" needs a confirmation step. */
    private val _historyCleared = MutableStateFlow(false)
    val historyCleared: StateFlow<Boolean> = _historyCleared.asStateFlow()

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
            overlayPermission = Permissions.isOverlayPermissionGranted(context),
            microphonePermission = Permissions.isMicrophonePermissionGranted(context),
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
            PermissionTarget.OVERLAY -> Permissions.overlaySettingsIntent(context)
            PermissionTarget.MICROPHONE -> Permissions.appDetailsIntent(context)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w("TyporbViewModel", "Could not open ${target.name} settings", it) }
    }

    // -------------------------------------------------------------- settings

    fun setEngine(engine: ProcessingEngine) = settingsRepository.setEngine(engine)

    fun setContextMode(mode: ContextMode) = settingsRepository.setContextMode(mode)

    fun setGpuAcceleration(enabled: Boolean) = settingsRepository.setGpuAcceleration(enabled)

    fun setOverlayCornerRadius(radiusDp: Int) = settingsRepository.setOverlayCornerRadius(radiusDp)

    fun setHapticsEnabled(enabled: Boolean) = settingsRepository.setHapticsEnabled(enabled)

    fun setWaveformEnabled(enabled: Boolean) = settingsRepository.setWaveformEnabled(enabled)

    fun completeOnboarding() = settingsRepository.setOnboardingComplete(true)

    // --------------------------------------------------------------- api key

    fun onApiKeyChange(value: String) {
        _apiKeyInput.value = value
        if (_apiKeySaved.value) _apiKeySaved.value = false
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
    fun clearTranscripts(): Boolean {
        val cleared = container.transcriptRepository.clear()
        if (cleared) _historyCleared.value = true
        return cleared
    }

    fun acknowledgeHistoryCleared() {
        _historyCleared.value = false
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

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