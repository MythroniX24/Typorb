package com.typorb.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.typorb.TyporbApp
import com.typorb.data.ModelCatalog
import com.typorb.data.OfflineModelState
import com.typorb.data.TyporbSettings
import com.typorb.model.ContextMode
import com.typorb.model.ProcessingEngine
import com.typorb.util.Permissions
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Live status of everything the user must grant before dictation works. */
data class PermissionStatus(
    val accessibilityService: Boolean,
    val overlayPermission: Boolean,
    val microphonePermission: Boolean,
) {
    /** The pill itself only needs the accessibility service; the rest are user experience extras. */
    val ready: Boolean get() = accessibilityService
}

/**
 * Backs the dashboard: surfaces persisted settings, mirrors system permission state, keeps the
 * API-key field separate from the stored value, and drives the offline-model download.
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val container = TyporbApp.containerOf(application)
    private val settingsRepository = container.settingsRepository

    val settings: StateFlow<TyporbSettings> = settingsRepository.settings

    val modelRepository = container.modelRepository

    val modelVariants: List<ModelCatalog.Variant> = ModelCatalog.variants

    private val _permissions = MutableStateFlow(readPermissions())
    val permissions: StateFlow<PermissionStatus> = _permissions.asStateFlow()

    private val _apiKeyInput = MutableStateFlow("")
    val apiKeyInput: StateFlow<String> = _apiKeyInput.asStateFlow()

    private val _apiKeySaved = MutableStateFlow(false)
    val apiKeySaved: StateFlow<Boolean> = _apiKeySaved.asStateFlow()

    /** Download/verification progress for the offline model card. */
    val modelState: StateFlow<OfflineModelState> = modelRepository.state

    private var downloadJob: Job? = null

    init {
        // Never echo the stored secret into the field; only remember whether one exists.
        _apiKeySaved.value = settingsRepository.current().hasApiKey
        container.selectModelVariant(ModelCatalog.variant(settings.value.modelVariantId))
        refreshModelState()
    }

    /** Re-reads system state — called when the dashboard returns to the foreground. */
    fun refreshPermissions() {
        _permissions.value = readPermissions()
        _apiKeySaved.value = settingsRepository.current().hasApiKey
        refreshModelState()
    }

    /** Re-reads the offline model's on-disk state (e.g. after a failure or an external change). */
    fun refreshModelState() {
        viewModelScope.launch {
            modelRepository.refresh(ModelCatalog.variant(settings.value.modelVariantId))
        }
    }

    fun setEngine(engine: ProcessingEngine) = settingsRepository.setEngine(engine)

    fun setContextMode(mode: ContextMode) = settingsRepository.setContextMode(mode)

    fun setGpuAcceleration(enabled: Boolean) = settingsRepository.setGpuAcceleration(enabled)

    fun onApiKeyChange(value: String) {
        _apiKeyInput.value = value
        if (_apiKeySaved.value) _apiKeySaved.value = false
    }

    fun saveApiKey() {
        val key = _apiKeyInput.value
        if (key.isBlank()) return
        settingsRepository.setApiKey(key)
        _apiKeyInput.value = ""
        _apiKeySaved.value = true
    }

    fun clearApiKey() {
        settingsRepository.clearApiKey()
        _apiKeyInput.value = ""
        _apiKeySaved.value = false
    }

    /**
     * Selects which build the offline engine should use. This never starts a transfer — switching to
     * the 150 MB variant must not silently begin a 150 MB download.
     */
    fun selectModelVariant(variant: ModelCatalog.Variant, refresh: Boolean = true) {
        settingsRepository.setModelVariant(variant.id)
        container.selectModelVariant(variant)
        if (refresh) refreshModelState()
    }

    /** Starts (or restarts) the model download for [variant]. */
    fun downloadModel(variant: ModelCatalog.Variant) {
        if (downloadJob?.isActive == true) return
        selectModelVariant(variant, refresh = false)
        downloadJob = viewModelScope.launch { modelRepository.download(variant) }
    }

    /** Cancels an in-flight download. */
    fun cancelModelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        refreshModelState()
    }

    /** Removes the downloaded weights and frees the space. */
    fun deleteModel(variant: ModelCatalog.Variant) {
        downloadJob?.cancel()
        downloadJob = null
        viewModelScope.launch { modelRepository.delete(variant) }
    }

    private fun readPermissions(): PermissionStatus {
        val context = getApplication<Application>()
        return PermissionStatus(
            accessibilityService = Permissions.isAccessibilityServiceEnabled(context),
            overlayPermission = Permissions.isOverlayPermissionGranted(context),
            microphonePermission = Permissions.isMicrophonePermissionGranted(context),
        )
    }
}