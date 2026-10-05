package com.typorb

import android.content.Context
import com.typorb.audio.AudioRecorder
import com.typorb.cloud.CloudTextProcessor
import com.typorb.cloud.groq.GroqClientFactory
import com.typorb.data.ModelCatalog
import com.typorb.data.ModelRepository
import com.typorb.data.SettingsRepository
import com.typorb.data.TranscriptRepository
import com.typorb.domain.DictationCoordinator
import com.typorb.domain.TextProcessingEngine
import com.typorb.local.LocalTextProcessor
import com.typorb.local.WhisperOnnxEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Wires the object graph.
 *
 * Engines and the HTTP stack are created lazily so launching the app never touches the network or
 * loads a 40 MB model until the user actually asks for it.
 */
class TyporbContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Supervisor scope so a failed dictation never cancels the service's own lifecycle. */
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository: SettingsRepository = SettingsRepository(appContext)

    val audioRecorder: AudioRecorder = AudioRecorder(appContext)

    /** In-app download + integrity verification for the offline weights. */
    val modelRepository: ModelRepository = ModelRepository(appContext)

    /** Encrypted history of everything the accessibility service has typed. */
    val transcriptRepository: TranscriptRepository = TranscriptRepository(appContext)

    private val whisperEngine: WhisperOnnxEngine by lazy {
        WhisperOnnxEngine(
            context = appContext,
            downloadedFiles = {
                // Resolved per call so a model downloaded after process start is picked up.
                modelRepository.filesFor(selectedModelVariant)
            },
            // Read per load, not captured once. The engine is a process-lifetime `by lazy` singleton
            // and the accessibility service builds it on the very first dictation, which is long
            // before anyone visits Settings — capturing the value here froze whatever the setting
            // happened to be at that moment, so switching acceleration in the dashboard did nothing
            // until the process was killed.
            preferNnapi = { settingsRepository.current().useGpuAcceleration },
        )
    }

    /** Model variant the offline engine should load (downloaded files, else bundled assets). */
    var selectedModelVariant: ModelCatalog.Variant = ModelCatalog.recommended
        private set

    fun selectModelVariant(variant: ModelCatalog.Variant) {
        selectedModelVariant = variant
    }

    init {
        // Mirrors the variant setting into the engine's resolver.
        //
        // The dashboard writes this setting, but the accessibility service is an independent entry
        // point: dictation can run in a process where the dashboard was never opened, and until now
        // the engine would have resolved "recommended" while the user had a different build selected.
        serviceScope.launch {
            settingsRepository.settings
                .map { it.modelVariantId }
                .distinctUntilChanged()
                .collect { id -> selectModelVariant(ModelCatalog.variant(id)) }
        }
    }

    private val groqApi: com.typorb.cloud.groq.GroqApi by lazy { GroqClientFactory.groqApi() }

    /**
     * Checks a Groq key by listing models, which proves the credential is live without spending a
     * transcription request.
     *
     * @return the number of models the key can reach, or the failure reason.
     */
    suspend fun verifyApiKey(apiKey: String): Result<Int> = runCatching {
        groqApi.listModels("Bearer ${apiKey.trim()}").data.size
    }

    private val cloudEngine: TextProcessingEngine by lazy {
        CloudTextProcessor(
            api = groqApi,
            apiKeyProvider = { settingsRepository.current().apiKey },
            transcribeModel = BuildConfig.GROQ_TRANSCRIBE_MODEL,
            chatModel = BuildConfig.GROQ_CHAT_MODEL,
        )
    }

    private val localEngine: TextProcessingEngine by lazy { LocalTextProcessor(whisperEngine) }

    /**
     * Picks the pipeline matching the user's current engine setting.
     *
     * Both engines are long-lived singletons on purpose — a local session must not be torn down
     * between dictations — so the *choice* is re-made from the settings snapshot passed in on every
     * dictation rather than cached. That snapshot is read fresh when recording stops, which is what
     * makes a switch in the dashboard take effect on the very next take instead of the next launch.
     */
    fun engineFor(settings: com.typorb.data.TyporbSettings): TextProcessingEngine =
        if (settings.engine == com.typorb.model.ProcessingEngine.LOCAL) localEngine else cloudEngine

    /**
     * Loads the offline model ahead of the first dictation so the tap feels instant.
     *
     * The session then stays resident for the life of the process — see [WhisperOnnxEngine] for why it
     * is not torn down when the accessibility service disconnects.
     */
    suspend fun warmUpLocalEngine() {
        whisperEngine.warmUp()
    }

    /** Creates a coordinator bound to the service scope and the current settings. */
    fun createCoordinator(onTextReady: suspend (String) -> Unit): DictationCoordinator =
        DictationCoordinator(
            settings = settingsRepository,
            recorder = audioRecorder,
            engineProvider = ::engineFor,
            scope = serviceScope,
            onTextReady = onTextReady,
        )
}